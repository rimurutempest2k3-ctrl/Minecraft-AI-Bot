package minecraftaibot.client;

import dev.minecraftaibot.common.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Validated vanilla PICKUP clicks; the server supplies and consumes the recipe result. */
final class ProductionController {
    private AbstractCraftingMenu menu;
    private List<ChestTransferPlan.Step> steps;
    private List<ChestTransferPlan.Stack> expected;
    private ChestTransferPlan.Stack cursor=ChestTransferPlan.Stack.empty();
    private final List<ItemStack> kinds=new ArrayList<>();
    private ItemStack result;
    private int index, ticks, settle, resultIndex;
    private String failure;
    boolean busy() { return steps!=null; }
    String failure() { return failure; }
    void start(Minecraft client, Map<Integer,Predicate<ItemStack>> recipe, ItemStack product) {
        failure=null;
        if (!(client.player.containerMenu instanceof AbstractCraftingMenu current)) throw new IllegalArgumentException("Cần menu chế tạo 2x2 hoặc bàn 3x3.");
        if(!current.getCarried().isEmpty()) throw new IllegalArgumentException("Hãy cất vật phẩm trên con trỏ trước.");
        menu=current; kinds.clear(); result=product.copy();
        List<Integer> inventory=new ArrayList<>(),grid=new ArrayList<>();
        for(int i=0;i<menu.slots.size();i++) {
            Slot slot=menu.getSlot(i);
            if(slot.container==client.player.getInventory() && slot.getContainerSlot()<36) inventory.add(i);
        }
        for(Slot slot:menu.getInputGridSlots()) grid.add(menu.slots.indexOf(slot));
        resultIndex=menu.slots.indexOf(menu.getResultSlot());
        List<ChestTransferPlan.Stack> initial=capture();
        var output=stack(product);
        Map<Integer,Set<String>> ingredients=new LinkedHashMap<>();
        for(var entry:recipe.entrySet()) {
            if(entry.getKey()<0 || entry.getKey()>=grid.size()) throw new IllegalArgumentException("Công thức cần bàn chế tạo 3x3.");
            Set<String> allowed=new HashSet<>();
            for(int source:inventory) if(entry.getValue().test(menu.getSlot(source).getItem())) allowed.add(stack(menu.getSlot(source).getItem()).kind());
            ingredients.put(grid.get(entry.getKey()),allowed);
        }
        steps=CraftingPlan.create(initial,inventory,grid,ingredients,resultIndex,output);
        expected=initial; cursor=ChestTransferPlan.Stack.empty(); index=0; ticks=0;settle=0;
    }
    private ChestTransferPlan.Stack stack(ItemStack value) {
        if(value.isEmpty()) return ChestTransferPlan.Stack.empty();
        int kind=0; while(kind<kinds.size() && !ItemStack.isSameItemSameComponents(kinds.get(kind),value)) kind++;
        if(kind==kinds.size()) kinds.add(value.copyWithCount(1));
        return new ChestTransferPlan.Stack(Integer.toString(kind),value.getCount(),value.getMaxStackSize());
    }
    private List<ChestTransferPlan.Stack> capture() { return menu.slots.stream().map(s->stack(s.getItem())).toList(); }
    private boolean matches() {
        List<ChestTransferPlan.Stack> actual=capture();
        for(int i=0;i<actual.size();i++) if(i!=resultIndex && !actual.get(i).equals(expected.get(i))) return false;
        return stack(menu.getCarried()).equals(cursor);
    }
    void tick(Minecraft client) {
        if(!busy()) return;
        if(client.player.containerMenu!=menu) { fail("Menu chế tạo đã thay đổi."); return; }
        ticks++;
        if(!matches()) { if(ticks>40) fail("Nguyên liệu/túi đồ bị thay đổi hoặc server sửa dữ liệu."); return; }
        if(index==steps.size()) {
            if(++settle>=20) steps=null;
            return;
        }
        if(ticks<4) return;
        var step=steps.get(index);
        if(step.slot()==resultIndex) {
            ItemStack output=menu.getResultSlot().getItem();
            if(!ItemStack.isSameItemSameComponents(output,result) || output.getCount()!=result.getCount()) {
                if(ticks>60) fail("Server không trả sản phẩm đúng công thức; chưa lấy sản phẩm.");
                return;
            }
        }
        client.gameMode.handleContainerInput(menu.containerId,step.slot(),step.button(),ContainerInput.PICKUP,client.player);
        expected=step.slots(); cursor=step.cursor();index++;ticks=0;
    }
    private void fail(String reason) { failure=reason;steps=null; }
    void cancel(Minecraft client) {
        steps=null;
        if(menu==null || client.player==null || client.player.containerMenu!=menu || menu.getCarried().isEmpty()) return;
        ItemStack carried=menu.getCarried();
        for(int i=0;i<menu.slots.size();i++) {
            Slot slot=menu.getSlot(i);ItemStack existing=slot.getItem();
            if(slot.container==client.player.getInventory() && slot.getContainerSlot()<36 && slot.mayPlace(carried)
                    && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing,carried) && existing.getCount()+carried.getCount()<=slot.getMaxStackSize(carried))) {
                client.gameMode.handleContainerInput(menu.containerId,i,0,ContainerInput.PICKUP,client.player);return;
            }
        }
    }

    /** Nearby placement, jump fallback and short bounded relocations through Baritone. */
    static final class TablePlacement {
        private enum Phase { JUMP, CONFIRM, LAND, MOVE, SETTLE }
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("minecraft-ai-bot");
        private final Consumer<String> notify;
        private final BaritoneController movement;
        private LocalPlayer owner;
        private ClientLevel world;
        private BlockPos target,destination;
        private int selected,stableTicks,settled;
        private long deadline,overallDeadline,phaseStarted;
        private JumpPlacementGate jump;
        private boolean holdingJump,automatic;
        private Phase phase;
        private PlacementRecovery recovery;
        private final Set<BlockPos> attempted=new LinkedHashSet<>();
        private String status="Chưa đặt bàn chế tạo.";
        TablePlacement(BaritoneController movement,Consumer<String> notify) {this.movement=movement;this.notify=notify;}
        boolean busy() {return deadline!=0;}
        BlockPos target() {return target;}
        String status() {return status;}
        private String announce(String message) {status=message;LOG.info("{}",message);notify.accept(message);return message;}
        private void begin(Phase next,int seconds) {phase=next;phaseStarted=System.nanoTime();deadline=phaseStarted+seconds*1_000_000_000L;settled=0;}
        String start(Minecraft client,BlockPos requested) {
            if(busy()) throw new IllegalArgumentException("Đang chờ đặt bàn chế tạo.");
            if(client.player==null || client.level==null || client.gameMode==null || !client.player.isAlive()) throw new IllegalArgumentException("Hãy vào thế giới trước.");
            if(client.player.containerMenu!=client.player.inventoryMenu || client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused()) throw new IllegalArgumentException("Đóng menu và dùng F3+P trước khi đặt bàn.");
            if(!client.player.inventoryMenu.getCarried().isEmpty()) throw new IllegalArgumentException("Hãy cất vật phẩm trên con trỏ trước.");
            owner=client.player;world=client.level;target=null;destination=null;jump=null;automatic=requested==null;
            selected=owner.getInventory().getSelectedSlot();recovery=new PlacementRecovery();attempted.clear();overallDeadline=System.nanoTime()+60_000_000_000L;
            if(requested!=null && (Math.abs((long)requested.getX())>29999984 || Math.abs((long)requested.getZ())>29999984 || requested.getY()<world.getMinY() || requested.getY()>world.getMaxY())) throw new IllegalArgumentException("Tọa độ nằm ngoài giới hạn thế giới.");
            if(requested!=null && world.hasChunkAt(requested) && world.getBlockState(requested).is(Blocks.CRAFTING_TABLE)) {target=requested;return status="Đã có bàn chế tạo tại "+requested.toShortString()+"; không đặt thêm.";}
            if(tableSlot()<0) throw new IllegalArgumentException("Túi đồ chưa có minecraft:crafting_table. Hãy chế bàn trước.");
            begin(Phase.CONFIRM,5);
            try {
                if(automatic) attempt(client);
                else {
                    BlockHitResult hit=placementHit(requested);
                    if(hit==null) throw new IllegalArgumentException("Ô chỉ định phải trống trên nền đặc, nhìn/chạm tới được.");
                    target=requested.immutable();selectTable(client);place(client,hit);
                }
                return status;
            } catch(RuntimeException failure) {cancel("Không bắt đầu được bước đặt bàn.");throw failure;}
        }
        private int tableSlot() {
            var items=owner.getInventory().getNonEquipmentItems();for(int i=0;i<items.size();i++) if(items.get(i).is(Items.CRAFTING_TABLE)) return i;return -1;
        }
        private void selectTable(Minecraft client) {
            int source=tableSlot();if(source<0) throw new IllegalArgumentException("Không còn bàn chế tạo trong túi; không thử đặt thêm.");
            if(source<9) owner.getInventory().setSelectedSlot(source);
            else {
                int slot=owner.getInventory().getSelectedSlot();ItemStack expected=owner.getInventory().getNonEquipmentItems().get(source).copy();
                client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,source,slot,ContainerInput.SWAP,owner);
                if(!ItemStack.isSameItemSameComponents(owner.getMainHandItem(),expected) || owner.getMainHandItem().getCount()!=expected.getCount()) throw new IllegalArgumentException("Chưa chuyển được bàn lên thanh nhanh; kiểm tra túi đồ.");
            }
        }
        private void attempt(Minecraft client) {
            target=null;stableTicks=0;BlockHitResult hit=null;BlockPos origin=owner.blockPosition();double best=Double.MAX_VALUE;
            for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-2,-1,-2),origin.offset(2,0,2))) {
                if(attempted.contains(pos)) continue;
                BlockHitResult candidate=placementHit(pos);double distance=owner.position().distanceToSqr(Vec3.atCenterOf(pos));
                if(candidate!=null && distance<best) {target=pos.immutable();hit=candidate;best=distance;}
            }
            if(target!=null) {selectTable(client);place(client,hit);return;}
            if(!attempted.contains(origin) && canJumpPlace(origin)) {
                target=origin.immutable();selectTable(client);jump=new JumpPlacementGate();begin(Phase.JUMP,5);
                holdingJump=true;client.options.keyJump.setDown(true);announce("Không có chỗ đặt gần; đang nhảy đặt bàn dưới chân tại "+target.toShortString()+".");
            } else recover("Không có ô đặt gần và chưa thể nhảy đặt dưới chân.");
        }
        private void place(Minecraft client,BlockHitResult hit) {
            attempted.add(target.immutable());jump=null;releaseJump();stableTicks=0;begin(Phase.CONFIRM,5);
            client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,hit);announce("Đang đặt bàn chế tạo tại "+target.toShortString()+"; chờ trạng thái game.");
        }
        private boolean canJumpPlace(BlockPos pos) {
            BlockPos support=pos.below();var state=world.getBlockState(support);
            return owner.onGround() && owner.getDeltaMovement().horizontalDistanceSqr()<0.0004 && world.getBlockState(pos).isAir()
                    && state.isCollisionShapeFullBlock(world,support) && !(state.getBlock() instanceof EntityBlock)
                    && Math.abs(owner.getBoundingBox().minY-pos.getY())<0.02 && world.noCollision(owner,owner.getBoundingBox().expandTowards(0,1.3,0));
        }
        private void releaseJump() {if(holdingJump) {Minecraft.getInstance().options.keyJump.setDown(false);holdingJump=false;}}
        private BlockHitResult placementHit(BlockPos pos) {
            if(!world.hasChunkAt(pos) || !world.getBlockState(pos).isAir() || owner.getBoundingBox().intersects(new AABB(pos))) return null;
            BlockPos support=pos.below();var state=world.getBlockState(support);
            if(!state.isCollisionShapeFullBlock(world,support) || state.getBlock() instanceof EntityBlock) return null;
            Vec3 inside=new Vec3(support.getX()+0.5,support.getY()+0.999,support.getZ()+0.5);
            BlockHitResult ray=world.clip(new ClipContext(owner.getEyePosition(),inside,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
            return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(support) && ray.getDirection()==Direction.UP
                    && owner.getEyePosition().distanceToSqr(ray.getLocation())<=Math.pow(owner.blockInteractionRange(),2)?ray:null;
        }
        private void recover(String reason) {
            if(target!=null) attempted.add(target.immutable());jump=null;releaseJump();
            if(!automatic || recovery.moves()>=2) {cancel(reason+" Đã dừng sau giới hạn thử đổi chỗ.");return;}
            if(tableSlot()<0) {cancel(reason+" Không còn bàn trong túi; đã dừng.");return;}
            begin(Phase.LAND,3);announce(reason+" Đợi đứng vững rồi tìm chỗ cách 1–2 block để thử lại.");
        }
        private boolean safeStanding(PlacementRecovery.Cell cell) {
            BlockPos pos=new BlockPos(cell.x(),cell.y(),cell.z());
            if(!world.hasChunkAt(pos) || !world.getBlockState(pos).isAir() || !world.getBlockState(pos.above()).isAir()) return false;
            var floor=world.getBlockState(pos.below());
            if(!floor.isCollisionShapeFullBlock(world,pos.below()) || floor.getBlock() instanceof EntityBlock || floor.is(Blocks.MAGMA_BLOCK)) return false;
            Vec3 delta=new Vec3(pos.getX()+0.5-owner.getX(),0,pos.getZ()+0.5-owner.getZ());
            return world.noCollision(owner,owner.getBoundingBox().expandTowards(delta));
        }
        private void relocate(Minecraft client) {
            BlockPos origin=owner.blockPosition();
            var chosen=recovery.choose(new PlacementRecovery.Cell(origin.getX(),origin.getY(),origin.getZ()),this::safeStanding);
            if(chosen.isEmpty()) {cancel("Không còn chỗ đứng phù hợp cách 1–2 block để thử đặt bàn.");return;}
            var cell=chosen.get();destination=new BlockPos(cell.x(),cell.y(),cell.z());movement.execute("goto "+cell.x()+" "+cell.y()+" "+cell.z());
            if(!movement.busy()) {cancel("Không bắt đầu được bước đi tới chỗ đặt bàn.");return;}
            begin(Phase.MOVE,15);announce("Đổi chỗ đặt bàn lần "+recovery.moves()+"/2: Baritone đi tới "+destination.toShortString()+" (cách 1–2 block).");
        }
        void tick(Minecraft client) {
            if(!busy()) return;
            if(client.player!=owner || client.level!=world || !owner.isAlive()) {cancel("Đã hủy đặt bàn vì trạng thái game thay đổi.");return;}
            if(client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused() || owner.containerMenu!=owner.inventoryMenu) {cancel("Đã hủy đặt bàn vì menu/tạm dừng thay đổi.");return;}
            long now=System.nanoTime();if(now>overallDeadline) {cancel("Đặt bàn quá 60 giây; đã dừng.");return;}
            // Earlier server confirmation takes priority over another placement at the new position.
            for(BlockPos pos:attempted) if(world.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                if(!pos.equals(target)) {target=pos;stableTicks=0;}
                if(phase!=Phase.CONFIRM) {if(phase==Phase.MOVE) movement.stop();releaseJump();jump=null;begin(Phase.CONFIRM,5);}
                if(++stableTicks>=20) cancel("Đã đặt bàn chế tạo tại "+target.toShortString()+" (trạng thái client).");return;
            }
            stableTicks=0;
            try {
                switch(phase) {
                    case LAND,SETTLE -> {
                        if(owner.onGround() && owner.getDeltaMovement().horizontalDistanceSqr()<0.0004) {
                            if(++settled>=3) {if(phase==Phase.SETTLE) attempt(client);else relocate(client);}
                        } else settled=0;
                        if(busy() && (phase==Phase.LAND || phase==Phase.SETTLE) && now>deadline) cancel("Chưa đứng vững để thử đổi chỗ/đặt bàn.");
                    }
                    case MOVE -> {
                        if(owner.blockPosition().equals(destination) && owner.onGround()) {movement.stop();begin(Phase.SETTLE,3);announce("Đã tới chỗ mới; đang kiểm tra lại vị trí đặt bàn.");}
                        else if(now>deadline || now-phaseStarted>2_000_000_000L && !movement.busy()) {movement.stop();recover("Không đi tới được chỗ mới.");}
                    }
                    case CONFIRM -> {if(now>deadline) recover("Chưa thấy bàn được đặt ở vị trí vừa thử.");}
                    case JUMP -> {
                        if(!owner.onGround()) releaseJump();BlockPos column=owner.blockPosition();
                        var decision=jump.tick(column.getX()==target.getX() && column.getZ()==target.getZ(),world.getBlockState(target).isAir(),owner.getBoundingBox().minY,target.getY(),owner.onGround());
                        if(decision==JumpPlacementGate.Decision.ABORT || now>deadline) {recover("Nhảy đặt dưới chân chưa thành công.");return;}
                        if(decision==JumpPlacementGate.Decision.PLACE) {
                            releaseJump();BlockHitResult hit=placementHit(target);
                            if(hit==null || !owner.getMainHandItem().is(Items.CRAFTING_TABLE)) {recover("Chưa đặt được bàn dưới chân.");return;}
                            place(client,hit);
                        }
                    }
                }
            } catch(RuntimeException failure) {cancel("Không tiếp tục được bước đặt/đổi chỗ bàn chế tạo.");}
        }
        void cancel(String message) {
            if(!busy()) return;if(phase==Phase.MOVE) movement.stop();deadline=0;jump=null;releaseJump();
            if(Minecraft.getInstance().player==owner) owner.getInventory().setSelectedSlot(selected);announce(message);
        }
    }

    /** Read-only furnace eligibility and planning; live game rules take priority over vanilla JSON. */
    static final class FurnaceLogic {
        private static com.google.gson.JsonObject load() throws java.io.IOException {
            var path=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/furnace-rules-26.2.json");
            if(java.nio.file.Files.size(path)>2_000_000) throw new IllegalArgumentException("File quy tắc lò quá lớn.");
            var root=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            if(root.get("schemaVersion").getAsInt()!=1 || !root.get("minecraftVersion").getAsString().equals("26.2")) throw new IllegalArgumentException("Phiên bản quy tắc lò không phù hợp.");
            for(String machine:List.of("furnace","blast_furnace","smoker")) {
                var settings=root.getAsJsonObject("machines").getAsJsonObject(machine);
                if(settings.get("defaultCookingTicks").getAsInt()<1 || settings.get("fuelTickDivisor").getAsInt()<1) throw new IllegalArgumentException("Thời gian nung không hợp lệ.");
                for(var entry:root.getAsJsonObject("acceptedInputs").getAsJsonObject(machine).entrySet()) for(var id:entry.getValue().getAsJsonArray()) {
                    var recipe=root.getAsJsonObject("recipes").getAsJsonObject(id.getAsString());
                    if(recipe==null || !recipe.get("type").getAsString().equals(settings.get("recipeType").getAsString())) throw new IllegalArgumentException("Tham chiếu công thức lò không hợp lệ.");
                }
            }
            return root;
        }
        private static ItemStack item(String id) {
            var key=net.minecraft.resources.Identifier.tryParse(id);
            return new ItemStack(key==null?Items.AIR:net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR));
        }
        private static String id(String text) {return text.contains(":")?text:"minecraft:"+text;}
        private static boolean liveInput(Minecraft client,String machine,ItemStack stack) {
            var key=switch(machine) {
                case "furnace" -> net.minecraft.world.item.crafting.RecipePropertySet.FURNACE_INPUT;
                case "blast_furnace" -> net.minecraft.world.item.crafting.RecipePropertySet.BLAST_FURNACE_INPUT;
                case "smoker" -> net.minecraft.world.item.crafting.RecipePropertySet.SMOKER_INPUT;
                default -> throw new IllegalArgumentException("Loại lò: furnace, blast_furnace hoặc smoker.");
            };
            return client.level.recipeAccess().propertySet(key).test(stack);
        }
        static boolean allowsInsertion(Minecraft client,String machine,int slot,ItemStack stack) {
            if(client.level==null || stack.isEmpty()) return false;
            if(slot==0) return liveInput(client,machine,stack);
            if(slot==1) return client.level.fuelValues().isFuel(stack) && client.level.fuelValues().burnDuration(stack)>0;
            return false; // The result slot, empty buckets and unknown slots are never bot insertion targets.
        }
        static String query(Minecraft client,String command) {
            String usage="Lệnh đọc quy tắc: bot furnace check <furnace|blast_furnace|smoker> <item>; bot furnace plan <loại lò> <nguyên liệu> <số lượng> <nhiên liệu>. Chưa tự nạp/đốt lò.";
            if(command.isBlank()) return usage;
            String[] parts=command.split(" ");
            if(!(parts.length==3 && parts[0].equals("check") || parts.length==5 && parts[0].equals("plan"))) return usage;
            try {
                var root=load();String machine=parts[1],inputId=id(parts[2]);
                if(!List.of("furnace","blast_furnace","smoker").contains(machine)) return usage;
                ItemStack input=item(inputId);if(input.isEmpty()) return "Mã vật phẩm không tồn tại: "+inputId;
                var matches=root.getAsJsonObject("acceptedInputs").getAsJsonObject(machine).getAsJsonArray(inputId);
                boolean inputAllowed=client.level!=null?allowsInsertion(client,machine,0,input):matches!=null;
                int burn=client.level!=null?client.level.fuelValues().burnDuration(input):fuelTicks(root,inputId);
                String source=client.level!=null?"Kiểm tra theo game đang chạy":"Tra dữ liệu vanilla 26.2 (chưa vào thế giới)";
                if(parts[0].equals("check")) {
                    String outputs=matches==null?"không có công thức vanilla":matches.toString();
                    return source+" | "+machine+" | "+inputId+" | Ô nguyên liệu: "+(inputAllowed?"được":"không được")
                            +" | Là nhiên liệu: "+(burn>0?"có ("+burn+" tick ở lò thường)":"không")+" | Công thức: "+outputs+" | Ô kết quả: không nạp.";
                }
                int quantity=Integer.parseInt(parts[3]);if(quantity<1 || quantity>2304) return "Số lượng phải là số nguyên 1-2304.";
                String fuelId=id(parts[4]);ItemStack fuel=item(fuelId);if(fuel.isEmpty()) return "Nhiên liệu không tồn tại: "+fuelId;
                if(!inputAllowed) return "Không được nạp "+inputId+" vào ô nguyên liệu của "+machine+"; không có công thức phù hợp.";
                int fuelBurn=client.level!=null?client.level.fuelValues().burnDuration(fuel):fuelTicks(root,fuelId);
                if(fuelBurn<=0) return fuelId+" không phải nhiên liệu; không nạp vào ô nhiên liệu.";
                if(matches==null || matches.size()!=1) return "Nguyên liệu được game chấp nhận nhưng chưa xác định duy nhất công thức vanilla; chưa ước lượng sản phẩm/nhiên liệu.";
                var recipe=root.getAsJsonObject("recipes").getAsJsonObject(matches.get(0).getAsString());
                var settings=root.getAsJsonObject("machines").getAsJsonObject(machine);
                int cook=recipe.has("cookingtime")?recipe.get("cookingtime").getAsInt():settings.get("defaultCookingTicks").getAsInt();
                int duration=fuelBurn/settings.get("fuelTickDivisor").getAsInt();if(cook<1 || duration<1) return "Thời gian nung/đốt không hợp lệ.";
                long needed=((long)quantity*cook+duration-1)/duration;
                var result=recipe.getAsJsonObject("result");int count=result.has("count")?result.get("count").getAsInt():1;
                return "Kế hoạch vanilla (chưa thực thi): "+inputId+" x"+quantity+" → "+result.get("id").getAsString()+" x"+((long)quantity*count)
                        +" | "+machine+" | Ước lượng "+fuelId+" x"+needed+" khi nung liên tục, không tính lửa còn lại. Game/server có thể thay đổi công thức; kiểm tra sản phẩm thực tế.";
            } catch(NumberFormatException bad) {return "Số lượng phải là số nguyên 1-2304.";}
            catch(Exception failure) {return "Không đọc/kiểm tra được furnace-rules-26.2.json; hãy kiểm tra cấu trúc file. File cũ được giữ nguyên.";}
        }
        private static int fuelTicks(com.google.gson.JsonObject root,String id) {
            var fuel=root.getAsJsonObject("fuels").getAsJsonObject(id);return fuel==null?0:Math.max(0,fuel.get("burnTicks").getAsInt());
        }
    }
}
