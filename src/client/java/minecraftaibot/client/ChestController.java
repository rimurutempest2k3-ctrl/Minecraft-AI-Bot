package minecraftaibot.client;

import dev.minecraftaibot.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** All reads and vanilla container clicks run on the client thread. */
final class ChestController {
    private final BotCore bot;
    private final Runnable stopMovement;
    private final BaritoneController movement;
    private final ChestObserver observer;
    private final Consumer<String> notifyConsole;
    private String status = "Chưa thao tác rương.";
    private long openingUntil;
    private ChestMenu menu;
    private LocalPlayer owner;
    private ClientLevel world;
    private List<ChestTransferPlan.Step> steps;
    private List<ChestTransferPlan.Stack> expected;
    private ChestTransferPlan.Stack cursor = ChestTransferPlan.Stack.empty();
    private final List<ItemStack> kinds = new ArrayList<>();
    private int index, delay, returnSlot = -1, quantity, initialCount;
    private Identifier item;
    private boolean depositing;
    private BlockPos approaching;
    private long approachUntil;
    private int arrivedTicks;
    ChestController(BotCore bot, BaritoneController movement, ChestObserver observer, Consumer<String> notifyConsole) {
        this.observer = observer;
        this.bot = bot; this.movement = movement; this.stopMovement = movement::stop; this.notifyConsole = notifyConsole;
    }
    boolean busy() { return steps != null || openingUntil != 0 || approaching != null; }
    String status() { return status; }
    String execute(String command) {
        Minecraft client = Minecraft.getInstance();
        if (command.equals("status")) return status;
        if (client.player == null || client.level == null || client.gameMode == null || !client.player.isAlive()) return "Chưa vào thế giới hoặc nhân vật đã chết.";
        if (command.equals("memory")) return observer.summary(client);
        if (command.startsWith("show ")) return observer.show(client, command.substring(5));
        if (command.equals("list")) return list(client);
        if (command.equals("close")) {
            if (busy()) return "Đang thao tác rương. Dùng bot stop trước khi đóng.";
            if (!(client.player.containerMenu instanceof ChestMenu)) return "Chưa mở rương.";
            if (!client.player.containerMenu.getCarried().isEmpty()) return "Đang giữ vật phẩm ở con trỏ; hãy cất vật phẩm trước khi đóng.";
            client.player.closeContainer(); return "Đã đóng rương.";
        }
        if (busy()) return "Đang xử lý rương. Chờ kết quả hoặc dùng bot stop.";
        if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước.";
        if (client.isPaused() || client.gui.overlay() != null) return "Game đang tạm dừng; dùng F3+P để tắt tự tạm dừng khi chuyển cửa sổ.";
        String[] parts = command.split(" ");
        if (parts[0].equals("open")) {
            if (parts.length == 2) {
                var remembered = observer.find(client, parts[1]);
                if (remembered == null) return "Không có ID rương trong thế giới/chiều không gian hiện tại.";
                var position = remembered.getAsJsonArray("positions").get(0).getAsJsonObject();
                parts = new String[]{"open", position.get("x").getAsString(), position.get("y").getAsString(), position.get("z").getAsString()};
            }
            return open(client, parts);
        }
        if (parts[0].equals("take") || parts[0].equals("put")) return transfer(client, parts[1], Integer.parseInt(parts[2]), parts[0].equals("put"));
        return "Lệnh rương không hợp lệ.";
    }
    private String open(Minecraft client, String[] parts) {
        if (client.player.containerMenu != client.player.inventoryMenu || client.gui.screen() != null) return "Đóng menu đang mở trước khi mở rương khác.";
        if (client.player.isSecondaryUseActive()) return "Hãy thả phím cúi người trước khi mở rương.";
        Vec3 eye = client.player.getEyePosition(); BlockPos target = null; double nearest = Double.MAX_VALUE;
        BlockPos origin = client.player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        if (parts.length == 4) candidates.add(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
        else {
            for (int x = (origin.getX() - 64) >> 4; x <= (origin.getX() + 64) >> 4; x++)
                for (int z = (origin.getZ() - 64) >> 4; z <= (origin.getZ() + 64) >> 4; z++) {
                    var chunk = client.level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                    if (chunk != null) candidates.addAll(chunk.getBlockEntities().keySet());
                }
        }
        for (BlockPos pos : candidates) {
            if (Math.abs((long)pos.getX()) > 29999984 || Math.abs((long)pos.getZ()) > 29999984 || pos.getY() < client.level.getMinY() || pos.getY() > client.level.getMaxY()) continue;
            if (client.level.hasChunkAt(pos) && !isChest(client, pos)) continue;
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if ((parts.length == 4 || distance <= 64 * 64) && distance < nearest) { target = pos.immutable(); nearest = distance; }
        }
        if (target == null) return "Không tìm thấy rương trong vùng đã tải, bán kính 64 block. Có thể chỉ định bot chest open <x> <y> <z>.";
        if (emptyHand(client) == null) return "Hãy để trống tay chính hoặc tay phụ trước khi mở rương.";
        BlockHitResult hit = reachable(client, target);
        owner = client.player; world = client.level;
        if (hit == null) {
            approaching = target; approachUntil = System.nanoTime() + 120_000_000_000L; arrivedTicks = 0;
            movement.approachChest(target);
            status = "Baritone đang đi tới rương " + target.toShortString() + "; sẽ tự mở khi tới gần. bot stop để hủy.";
            return status;
        }
        return interact(client, hit);
    }
    private boolean isChest(Minecraft client, BlockPos pos) {
        var block = client.level.getBlockState(pos);
        return block.is(Blocks.CHEST) || block.is(Blocks.TRAPPED_CHEST);
    }
    private BlockHitResult reachable(Minecraft client, BlockPos target) {
        if (!client.level.hasChunkAt(target) || !isChest(client, target)) return null;
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult ray = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(target)
                && eye.distanceToSqr(ray.getLocation()) <= Math.pow(client.player.blockInteractionRange(), 2) ? ray : null;
    }
    private InteractionHand emptyHand(Minecraft client) {
        if (client.player.getMainHandItem().isEmpty()) return InteractionHand.MAIN_HAND;
        return client.player.getOffhandItem().isEmpty() ? InteractionHand.OFF_HAND : null;
    }
    private String interact(Minecraft client, BlockHitResult hit) {
        InteractionHand hand;
        hand = emptyHand(client);
        if (hand == null) return "Hãy để trống một tay trước khi mở rương.";
        stopMovement.run(); owner = client.player; world = client.level;
        observer.interaction(client, hit.getBlockPos());
        client.gameMode.useItemOn(owner, hand, hit);
        openingUntil = System.nanoTime() + 5_000_000_000L;
        status = "Đang chờ mở rương tại " + hit.getBlockPos().toShortString();
        return status + ". Khi mở xong dùng bot chest list.";
    }
    private String list(Minecraft client) {
        if (!(client.player.containerMenu instanceof ChestMenu chest)) return "Chưa mở rương. Dùng bot chest open hoặc mở rương bằng tay.";
        if (busy()) return "Đang thao tác rương; hãy chờ xong để đọc danh sách.";
        Map<String,Integer> counts = new TreeMap<>(); int size = chest.getRowCount() * 9;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = chest.getSlot(slot).getItem();
            if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return "Rương: " + size + " ô | " + (counts.isEmpty() ? "Rương trống" : counts.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).collect(Collectors.joining("; ")));
    }
    private String transfer(Minecraft client, String id, int amount, boolean deposit) {
        if (!(client.player.containerMenu instanceof ChestMenu chest)) return "Chưa mở rương. Dùng bot chest open rồi bot chest list.";
        if (!chest.getCarried().isEmpty()) return "Đang giữ vật phẩm ở con trỏ; hãy cất vật phẩm trước.";
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || BuiltInRegistries.ITEM.getOptional(identifier).isEmpty()) return "Mã vật phẩm không hợp lệ.";
        int chestSlots = chest.getRowCount() * 9;
        if (chest.slots.size() != chestSlots + 36) return "Menu này chưa được hỗ trợ.";
        for (int slot = chestSlots; slot < chest.slots.size(); slot++) if (chest.getSlot(slot).container != client.player.getInventory()) return "Menu túi đồ không chuẩn; chưa thao tác.";
        kinds.clear(); Set<String> selected = new HashSet<>();
        List<ChestTransferPlan.Stack> snapshot = new ArrayList<>();
        for (int slot = 0; slot < chest.slots.size(); slot++) {
            ItemStack stack = chest.getSlot(slot).getItem();
            if (!stack.isEmpty() && (!chest.getSlot(slot).mayPickup(client.player) || !chest.getSlot(slot).mayPlace(stack))) return "Menu hạn chế thao tác vật phẩm; chưa lấy gì.";
            var value = stack(stack); snapshot.add(value);
            if ((deposit ? slot >= chestSlots : slot < chestSlots) && !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(identifier)) selected.add(value.kind());
        }
        List<ChestTransferPlan.Step> plan;
        try { plan = deposit ? ChestTransferPlan.deposit(snapshot, chestSlots, selected, amount) : ChestTransferPlan.create(snapshot, chestSlots, selected, amount); }
        catch (IllegalArgumentException failure) { return failure.getMessage(); }
        stopMovement.run(); menu = chest; owner = client.player; world = client.level;
        steps = plan; expected = snapshot; cursor = ChestTransferPlan.Stack.empty();
        item = identifier; quantity = amount; initialCount = inventoryCount(); index = 0; delay = 4; returnSlot = -1;
        depositing = deposit;
        status = (deposit ? "Đang cất " : "Đang lấy ") + item + " x" + quantity + (deposit ? " vào rương." : " từ rương.");
        return status + " Không thao tác chuột/túi đồ trong lúc chuyển; bot stop để hủy.";
    }
    private ChestTransferPlan.Stack stack(ItemStack stack) {
        if (stack.isEmpty()) return ChestTransferPlan.Stack.empty();
        int kind = 0;
        while (kind < kinds.size() && !ItemStack.isSameItemSameComponents(kinds.get(kind), stack)) kind++;
        if (kind == kinds.size()) kinds.add(stack.copy());
        return new ChestTransferPlan.Stack(Integer.toString(kind), stack.getCount(), stack.getMaxStackSize());
    }
    private boolean matches() {
        if (!stack(menu.getCarried()).equals(cursor)) return false;
        for (int slot = 0; slot < expected.size(); slot++) if (!stack(menu.getSlot(slot).getItem()).equals(expected.get(slot))) return false;
        return true;
    }
    private int inventoryCount() { return owner.getInventory().getNonEquipmentItems().stream().filter(s -> !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(item)).mapToInt(ItemStack::getCount).sum(); }
    void tick(Minecraft client) {
        if (!busy()) return;
        if (client.player != owner || client.level != world || !owner.isAlive()) { abandon("Đã dừng thao tác rương do đổi thế giới/nhân vật hoặc chết."); return; }
        if (bot.state() != BotState.RUNNING) { cancel(); return; }
        if (approaching != null) {
            if (client.isPaused()) return;
            if (owner.containerMenu != owner.inventoryMenu || client.gui.screen() != null) { abandon("Đã dừng đi tới rương vì có menu khác mở."); return; }
            if (System.nanoTime() > approachUntil) { abandon("Không tới/mở được rương trong 120 giây. Kiểm tra đường đi và tọa độ."); return; }
            if (client.level.hasChunkAt(approaching) && !isChest(client, approaching)) { abandon("Rương mục tiêu không còn tồn tại; đã dừng di chuyển."); return; }
            BlockHitResult hit = reachable(client, approaching);
            if (hit != null) {
                stopMovement.run();
                if (owner.isSecondaryUseActive()) return;
                approaching = null;
                String response = interact(client, hit);
                if (openingUntil == 0) abandon(response); else notifyConsole.accept(response);
            } else if (movement.besideChest(approaching) && ++arrivedTicks >= 40) abandon("Đã tới cạnh rương nhưng không thể tương tác; hãy kiểm tra vật cản.");
            return;
        }
        if (openingUntil != 0) {
            if (owner.containerMenu instanceof ChestMenu) { openingUntil = 0; status = "Đã mở rương. Dùng bot chest list."; notifyConsole.accept(status); }
            else if (System.nanoTime() > openingUntil) abandon("Không mở được rương trong 5 giây; kiểm tra vật cản, khóa rương hoặc quyền truy cập.");
            return;
        }
        if (owner.containerMenu != menu) { abandon("Menu rương đã đóng/thay đổi; tác vụ lấy đồ đã dừng."); return; }
        if (client.isPaused()) return;
        if (!matches()) { cancel(); status = "Trạng thái rương thay đổi hoặc server sửa dữ liệu; đã dừng. Kiểm tra bot inventory và vật phẩm trên con trỏ."; notifyConsole.accept(status); return; }
        if (--delay > 0) return;
        if (index == steps.size()) {
            int acquired = depositing ? initialCount - inventoryCount() : inventoryCount() - initialCount;
            status = acquired == quantity ? (depositing ? "Đã cất " : "Đã lấy ") + item + " x" + acquired + (depositing ? " từ túi đồ vào rương" : " từ rương vào túi đồ") + " (kiểm tra trạng thái client)."
                    : "Tác vụ rương kết thúc nhưng số lượng thay đổi: " + acquired + "/" + quantity + ". Hãy kiểm tra túi đồ.";
            steps = null; notifyConsole.accept(status); return;
        }
        var step = steps.get(index++);
        if (cursor.count() == 0 && step.cursor().count() > 0) returnSlot = step.slot();
        client.gameMode.handleContainerInput(menu.containerId, step.slot(), step.button(), ContainerInput.PICKUP, owner);
        expected = step.slots(); cursor = step.cursor(); delay = index == steps.size() ? 20 : 4;
    }
    void cancel() {
        Minecraft client = Minecraft.getInstance();
        if (steps != null && client.player == owner && owner.containerMenu == menu && client.gameMode != null && !menu.getCarried().isEmpty()) {
            // Return to the original slot only when that cannot swap or overflow. Never click outside.
            ItemStack carried = menu.getCarried();
            if (returnSlot >= 0) {
                Slot slot = menu.getSlot(returnSlot); ItemStack existing = slot.getItem();
                if (slot.mayPlace(carried) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, carried))
                        && existing.getCount() + carried.getCount() <= slot.getMaxStackSize(carried))
                    client.gameMode.handleContainerInput(menu.containerId, returnSlot, 0, ContainerInput.PICKUP, owner);
            }
        }
        abandon("Đã hủy thao tác rương. Nếu vật phẩm còn trên con trỏ, hãy cất vào một ô trống; mod không thả đồ ra ngoài.");
    }
    private void abandon(String message) {
        if (approaching != null) stopMovement.run();
        approaching = null; openingUntil = 0; steps = null; status = message; notifyConsole.accept(message);
    }
}
