package minecraftaibot.client;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.minecraftaibot.common.*;
import dev.minecraftaibot.common.ChestMemories;
import dev.minecraftaibot.common.ChestMemory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** All reads and vanilla container clicks run on the client thread. */
final class ChestController {
    private final BotCore bot;
    private final Runnable stopMovement;
    private final BaritoneController movement;
    private final ChestController.MemoryObserver observer;
    private final Consumer<String> notifyConsole;
    private String status = "No chest action yet.";
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
    ChestController(BotCore bot, BaritoneController movement, ChestController.MemoryObserver observer, Consumer<String> notifyConsole) {
        this.observer = observer;
        this.bot = bot; this.movement = movement; this.stopMovement = movement::stop; this.notifyConsole = notifyConsole;
    }
    boolean busy() { return steps != null || openingUntil != 0 || approaching != null; }
    String status() { return status; }
    String execute(String command) {
        Minecraft client = Minecraft.getInstance();
        if (command.equals("status")) return status;
        if (client.player == null || client.level == null || client.gameMode == null || !client.player.isAlive()) return "Not in a world, or player is dead.";
        if (command.equals("memory")) return observer.summary(client);
        if (command.startsWith("show ")) return observer.show(client, command.substring(5));
        if (command.equals("list")) return list(client);
        if (command.equals("close")) {
            if (busy()) return "Chest action active. Use bot stop before closing.";
            if (!(client.player.containerMenu instanceof ChestMenu)) return "No chest open.";
            if (!client.player.containerMenu.getCarried().isEmpty()) return "Cursor holds an item; store it before closing.";
            client.player.closeContainer(); return "Chest closed.";
        }
        if (busy()) return "Chest action processing. Wait for the result or use bot stop.";
        if (bot.state() != BotState.RUNNING) return "Enter bot start first.";
        if (client.isPaused() || client.gui.overlay() != null) return "Game paused; use F3+P to disable automatic pause when switching windows.";
        String[] parts = command.split(" ");
        if (parts[0].equals("open")) {
            if (parts.length == 2) {
                var remembered = observer.find(client, parts[1]);
                if (remembered == null) return "Chest ID not found in the current world/dimension.";
                var position = remembered.getAsJsonArray("positions").get(0).getAsJsonObject();
                parts = new String[]{"open", position.get("x").getAsString(), position.get("y").getAsString(), position.get("z").getAsString()};
            }
            return open(client, parts);
        }
        if (parts[0].equals("take") || parts[0].equals("put")) return transfer(client, parts[1], Integer.parseInt(parts[2]), parts[0].equals("put"));
        return "Invalid chest command.";
    }
    private String open(Minecraft client, String[] parts) {
        if (client.player.containerMenu != client.player.inventoryMenu || client.gui.screen() != null) return "Close the current menu before opening another chest.";
        if (client.player.isSecondaryUseActive()) return "Release the sneak key before opening the chest.";
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
        if (target == null) return "No chest found in loaded chunks within 64 blocks. Use bot chest open <x> <y> <z> to specify one.";
        BlockHitResult hit = reachable(client, target);
        owner = client.player; world = client.level;
        if (hit == null) {
            approaching = target; approachUntil = System.nanoTime() + 120_000_000_000L; arrivedTicks = 0;
            movement.approachChest(target);
            status = "Baritone approaching chest " + target.toShortString() + "; will open automatically when near. bot stop to cancel.";
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
    private String interact(Minecraft client, BlockHitResult hit) {
        if(client.player.isSecondaryUseActive()) return "Release the sneak key before opening the chest.";
        var inventory=client.player.getInventory().getNonEquipmentItems();
        for(int i=0;i<9;i++) if(inventory.get(i).isEmpty()) {client.player.getInventory().setSelectedSlot(i);break;}
        stopMovement.run(); owner = client.player; world = client.level;
        observer.interaction(client, hit.getBlockPos());
        var result=client.gameMode.useItemOn(owner, InteractionHand.MAIN_HAND, hit);
        org.slf4j.LoggerFactory.getLogger("minecraft-ai-bot").info("Open chest at {} with MAIN_HAND: {}",hit.getBlockPos().toShortString(),result);
        openingUntil = System.nanoTime() + 5_000_000_000L;
        status = "Waiting to open chest at " + hit.getBlockPos().toShortString();
        return status + ". Once opened, use bot chest list.";
    }
    private String list(Minecraft client) {
        if (!(client.player.containerMenu instanceof ChestMenu chest)) return "No chest open. Use bot chest open or open one manually.";
        if (busy()) return "Chest action active; wait for completion before listing contents.";
        Map<String,Integer> counts = new TreeMap<>(); int size = chest.getRowCount() * 9;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = chest.getSlot(slot).getItem();
            if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return "Chest: " + size + " slots | " + (counts.isEmpty() ? "Empty chest" : counts.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).collect(Collectors.joining("; ")));
    }
    private String transfer(Minecraft client, String id, int amount, boolean deposit) {
        if (!(client.player.containerMenu instanceof ChestMenu chest)) return "No chest open. Use bot chest open, then bot chest list.";
        if (!chest.getCarried().isEmpty()) return "Cursor holds an item; store it first.";
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || BuiltInRegistries.ITEM.getOptional(identifier).isEmpty()) return "Invalid item ID.";
        int chestSlots = chest.getRowCount() * 9;
        if (chest.slots.size() != chestSlots + 36) return "Menu not supported yet.";
        for (int slot = chestSlots; slot < chest.slots.size(); slot++) if (chest.getSlot(slot).container != client.player.getInventory()) return "Nonstandard inventory menu; no action taken.";
        kinds.clear(); Set<String> selected = new HashSet<>();
        List<ChestTransferPlan.Stack> snapshot = new ArrayList<>();
        for (int slot = 0; slot < chest.slots.size(); slot++) {
            ItemStack stack = chest.getSlot(slot).getItem();
            if (!stack.isEmpty() && (!chest.getSlot(slot).mayPickup(client.player) || !chest.getSlot(slot).mayPlace(stack))) return "Menu restricts item actions; nothing taken.";
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
        status = (deposit ? "Storing " : "Taking ") + item + " x" + quantity + (deposit ? " into chest." : " from chest.");
        return status + " Do not interact with inventory during transfer; bot stop cancels.";
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
        if (client.player != owner || client.level != world || !owner.isAlive()) { abandon("Chest action stopped due to world/player change or death."); return; }
        if (bot.state() != BotState.RUNNING) { cancel(); return; }
        if (approaching != null) {
            if (client.isPaused()) return;
            if (owner.containerMenu != owner.inventoryMenu || client.gui.screen() != null) { abandon("Chest approach stopped because another menu opened."); return; }
            if (System.nanoTime() > approachUntil) { abandon("Could not reach/open chest within 120 seconds. Check route and coordinates."); return; }
            if (client.level.hasChunkAt(approaching) && !isChest(client, approaching)) { abandon("Target chest no longer exists; movement stopped."); return; }
            BlockHitResult hit = reachable(client, approaching);
            if (hit != null) {
                stopMovement.run();
                if (owner.isSecondaryUseActive()) return;
                approaching = null;
                String response = interact(client, hit);
                if (openingUntil == 0) abandon(response); else notifyConsole.accept(response);
            } else if (movement.besideChest(approaching) && ++arrivedTicks >= 40) abandon("Reached chest but cannot interact; check obstacles.");
            return;
        }
        if (openingUntil != 0) {
            if (owner.containerMenu instanceof ChestMenu) { openingUntil = 0; status = "Chest opened. Use bot chest list."; notifyConsole.accept(status); }
            else if (System.nanoTime() > openingUntil) abandon("Could not open chest within 5 seconds; check obstacles, locks or permissions.");
            return;
        }
        if (owner.containerMenu != menu) { abandon("Chest menu closed/changed; transfer stopped."); return; }
        if (client.isPaused()) return;
        if (!matches()) { cancel(); status = "Chest state changed or server corrected data; stopped. Check bot inventory and cursor items."; notifyConsole.accept(status); return; }
        if (--delay > 0) return;
        if (index == steps.size()) {
            int acquired = depositing ? initialCount - inventoryCount() : inventoryCount() - initialCount;
            status = acquired == quantity ? (depositing ? "Stored " : "Took ") + item + " x" + acquired + (depositing ? " from inventory to chest" : " from chest to inventory") + " (check client state)."
                    : "Chest action finished but counts changed: " + acquired + "/" + quantity + ". Check inventory.";
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
        abandon("Chest action canceled. Store cursor items in an empty slot; the mod will not drop them.");
    }
    private void abandon(String message) {
        if (approaching != null) stopMovement.run();
        approaching = null; openingUntil = 0; steps = null; status = message; notifyConsole.accept(message);
    }

    /** Binds only a witnessed block interaction to a chest menu; never guesses from nearby blocks. */
    static final class MemoryObserver implements AutoCloseable {
        private final Consumer<String> notifyConsole;
        private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "bot-chest-memory"); t.setDaemon(true); return t; });
        private ChestMemories memory;
        private BlockPos clicked;
        private ClientLevel clickedWorld;
        private long clickedUntil;
        private ChestMenu observed;
        private ClientLevel observedWorld;
        private List<ChestMemory.Position> positions;
        private String scope, dimension, previous = "", saved = "";
        private int clock, stable;
        private String unknownSession = UUID.randomUUID().toString();
        private ClientLevel unknownWorld;
        MemoryObserver(Consumer<String> notifyConsole) { this.notifyConsole = notifyConsole; }
        void initialize(Path file) throws IOException { memory = new ChestMemories(file); }
        JsonObject webSnapshot(Minecraft client) {
            JsonObject result=new JsonObject();result.add("entries",new JsonArray());
            if(memory==null || client.level==null) return result;
            String currentWorld=world(client),currentDimension=client.level.dimension().identifier().toString();
            result.addProperty("scope",currentWorld.startsWith("local:")?"Single-player world":currentWorld);
            result.addProperty("dimension",currentDimension);
            result.addProperty("file",memory.fileFor(currentWorld).getFileName().toString());
            try {result.add("entries",memory.snapshot(currentWorld,currentDimension));}
            catch(IOException failure) {result.addProperty("error","Cannot read chest memory; old file preserved.");}
            return result;
        }
        String world(Minecraft client) {
            if (client.getSingleplayerServer() != null) return "local:" + client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (client.getCurrentServer() != null) return "server:" + client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            if (unknownWorld != client.level) { unknownWorld = client.level; unknownSession = UUID.randomUUID().toString(); }
            return "unknown-session:" + unknownSession;
        }
        void interaction(Minecraft client, BlockPos position) {
            if (client.level == null || client.player == null || client.player.containerMenu != client.player.inventoryMenu) return;
            var state = client.level.getBlockState(position);
            if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) return;
            clicked = position.immutable(); clickedWorld = client.level; clickedUntil = System.nanoTime() + 5_000_000_000L;
        }
        void tick(Minecraft client, boolean transferring) {
            if (memory == null) return;
            ChestMenu current = client.player != null && client.player.containerMenu instanceof ChestMenu menu ? menu : null;
            if (current != observed || client.level != observedWorld) {
                if (observed != null && positions != null && !transferring && observed.getCarried().isEmpty())
                    queue(client, observed, snapshot(observedWorld, observed));
                observed = current; observedWorld = client.level; positions = null; previous = ""; saved = ""; stable = 0;
                if (current != null && clicked != null && clickedWorld == client.level && System.nanoTime() <= clickedUntil) {
                    var state = client.level.getBlockState(clicked);
                    if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
                        List<BlockPos> blocks = new ArrayList<>(); blocks.add(clicked);
                        if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                            BlockPos other = ChestBlock.getConnectedBlockPos(clicked, state);
                            var second = client.level.getBlockState(other);
                            if (second.is(state.getBlock()) && second.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()
                                    && ChestBlock.getConnectedBlockPos(other, second).equals(clicked)) blocks.add(other);
                        }
                        if (current.getRowCount() * 9 == blocks.size() * 27) {
                            positions = blocks.stream().map(p -> new ChestMemory.Position(p.getX(),p.getY(),p.getZ())).toList();
                            scope = world(client); dimension = client.level.dimension().identifier().toString();
                        }
                    }
                }
                if (current != null && positions == null) notifyConsole.accept("Chest memory: position unknown; close and reopen the chest by interacting with its block.");
                clicked = null;
            }
            if (current == null || positions == null || transferring || !current.getCarried().isEmpty() || ++clock % 5 != 0) return;
            JsonArray slots = snapshot(client.level, current);
            String fingerprint = slots.toString();
            if (!fingerprint.equals(previous)) { previous = fingerprint; stable = 0; return; }
            if (++stable < 2 || fingerprint.equals(saved)) return;
            queue(client, current, slots);
        }
        private JsonArray snapshot(ClientLevel level, ChestMenu current) {
            JsonArray slots = new JsonArray();
            for (int index = 0; index < current.getRowCount() * 9; index++) {
                JsonObject slot = new JsonObject(); slot.addProperty("slot", index);
                ItemStack stack = current.getSlot(index).getItem();
                if (!stack.isEmpty()) {
                    slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); slot.addProperty("count", stack.getCount());
                    slot.addProperty("name", stack.getHoverName().getString());
                    ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).result().ifPresent(encoded -> slot.add("stackData", encoded));
                }
                slots.add(slot);
            }
            return slots;
        }
        private void queue(Minecraft client, ChestMenu current, JsonArray slots) {
            String fingerprint = slots.toString();
            if (fingerprint.equals(saved)) return;
            boolean first = saved.isEmpty(); saved = fingerprint;
            String currentScope = scope, currentDimension = dimension; var coordinates = positions;
            ChestMenu captured = current;
            writer.submit(() -> {
                try {
                    String id = memory.observe(currentScope, currentDimension, coordinates, slots);
                    if (first) notifyConsole.accept("Chest remembered: " + id + (coordinates.size() == 2 ? " (double chest, 2 positions)." : "."));
                } catch (IOException failure) {
                    notifyConsole.accept("Could not save chest memory; check file and write permissions. Old file preserved.");
                    client.execute(() -> { if (observed == captured) saved = ""; });
                }
            });
        }
        String summary(Minecraft client) {
            try {
                return memory == null ? "Chest memory could not initialize." : memory.summary(world(client), client.level.dimension().identifier().toString());
            } catch (IOException failure) { return "Cannot read this server's chest memory; old file preserved."; }
        }
        String show(Minecraft client, String id) {
            JsonObject value = find(client, id);
            return value == null ? "Chest ID not found in the current world/dimension." : value.get("id").getAsString()
                    + " | Coordinates: " + value.get("positions") + " | Last contents: " + value.get("items") + " | Observation: " + value.get("lastSeen").getAsString();
        }
        JsonObject find(Minecraft client, String id) {
            JsonObject value;
            try { value = memory == null ? null : memory.find(world(client), id); }
            catch (IOException failure) {
                notifyConsole.accept("Cannot read this server's chest memory; old file preserved.");
                return null;
            }
            return value != null && value.get("world").getAsString().equals(world(client)) && value.get("dimension").getAsString().equals(client.level.dimension().identifier().toString()) ? value : null;
        }
        public void close() {
            writer.shutdown();
            try { writer.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
