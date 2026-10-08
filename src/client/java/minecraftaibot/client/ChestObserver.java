package minecraftaibot.client;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.minecraftaibot.common.ChestMemory;
import dev.minecraftaibot.common.ChestMemories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Binds only a witnessed block interaction to a chest menu; never guesses from nearby blocks. */
final class ChestObserver implements AutoCloseable {
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
    ChestObserver(Consumer<String> notifyConsole) { this.notifyConsole = notifyConsole; }
    void initialize(Path file) throws IOException { memory = new ChestMemories(file); }
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
            if (current != null && positions == null) notifyConsole.accept("Bộ nhớ rương: chưa xác định được tọa độ; hãy đóng rồi mở lại rương bằng tương tác với block.");
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
                if (first) notifyConsole.accept("Đã nhớ rương: " + id + (coordinates.size() == 2 ? " (rương đôi, 2 tọa độ)." : "."));
            } catch (IOException failure) {
                notifyConsole.accept("Không lưu được bộ nhớ rương; kiểm tra file và quyền ghi. File cũ được giữ nguyên.");
                client.execute(() -> { if (observed == captured) saved = ""; });
            }
        });
    }
    String summary(Minecraft client) {
        try {
            return memory == null ? "Bộ nhớ rương chưa khởi tạo được." : memory.summary(world(client), client.level.dimension().identifier().toString());
        } catch (IOException failure) { return "Không đọc được bộ nhớ rương của server này; file cũ được giữ nguyên."; }
    }
    String show(Minecraft client, String id) {
        JsonObject value = find(client, id);
        return value == null ? "Không có ID rương trong thế giới/chiều không gian hiện tại." : value.get("id").getAsString()
                + " | Tọa độ: " + value.get("positions") + " | Đồ lần cuối: " + value.get("items") + " | Quan sát: " + value.get("lastSeen").getAsString();
    }
    JsonObject find(Minecraft client, String id) {
        JsonObject value;
        try { value = memory == null ? null : memory.find(world(client), id); }
        catch (IOException failure) {
            notifyConsole.accept("Không đọc được bộ nhớ rương của server này; file cũ được giữ nguyên.");
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
