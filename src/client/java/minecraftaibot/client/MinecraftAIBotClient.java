package minecraftaibot.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import dev.minecraftaibot.common.*;
import dev.minecraftaibot.common.ai.AgentLoop;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinecraftAIBotClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("minecraft-ai-bot");
    private final BotCore bot = new BotCore();
    private final BaritoneController movement = new BaritoneController(bot);
    private final ChestController.MemoryObserver chestMemory = new ChestController.MemoryObserver(this::notifyConsole);
    private final ChestController chest = new ChestController(bot, movement, chestMemory, this::notifyConsole);
    private final ProductionController.TablePlacement placement = new ProductionController.TablePlacement(movement,this::notifyConsole);
    private final LocalTaskRunner localTasks = new LocalTaskRunner(bot, movement, placement, this::notifyConsole);
    private final CommandDispatcher commands = new CommandDispatcher(bot, this::describeGame,
            this::executeAction, this::describeInventory, this::askAi,
            this::assignAiKey, this::executeChest);
    private String executeChest(String input) {
        if(placement.busy()) return "Đang chờ đặt bàn chế tạo. Chờ kết quả hoặc dùng bot stop.";
        if(localTasks.busy() && !(input.equals("status") || input.equals("memory") || input.startsWith("show ")))
            return "Đang chạy nhiệm vụ local. Dùng bot stop trước khi thao tác rương.";
        return chest.execute(input);
    }
    private volatile ConsoleServer console;
    private AiController ai;
    private AgentLoop agent;
    private int agentTicks;
    private net.minecraft.client.multiplayer.ClientLevel agentWorld;
    private net.minecraft.client.player.LocalPlayer agentPlayer;
    private boolean agentValid(Minecraft client) {
        return client.level != null && client.level == agentWorld && client.player != null && client.player == agentPlayer
                && client.player.isAlive() && bot.state() == BotState.RUNNING;
    }
    private String observation() {
        Minecraft client = Minecraft.getInstance();
        return describeGame() + "\n" + describeInventory() + "\nRương đang mở: " + chest.execute("list")
                + "\nBộ nhớ rương SERVER/CHIỀU HIỆN TẠI (nội dung lần cuối, cần mở để kiểm tra): "
                + (client.level == null ? "Chưa vào thế giới." : chestMemory.summary(client));
    }
    private void stopAgentGame() { if (chest.busy()) chest.cancel(); localTasks.cancel("Nhiệm vụ local được AI hủy."); placement.cancel("Đã hủy bước đặt bàn."); movement.stop(); }
    private String consoleCommand(String input) {
        String normalized=input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if(normalized.equals("bot furnace") || normalized.startsWith("bot furnace "))
            return ProductionController.FurnaceLogic.query(Minecraft.getInstance(),normalized.equals("bot furnace")?"":normalized.substring(12));
        boolean readOnly=java.util.Set.of("bot info", "bot inventory", "bot status", "bot help", "bot task list", "bot task check", "bot task inventory", "bot ai status", "bot ai result", "bot chest memory", "bot chest list", "bot chest status").contains(normalized)
                || normalized.startsWith("bot chest show ");
        boolean aiControl=normalized.equals("bot ai auto") || normalized.startsWith("bot ai auto ")
                || normalized.startsWith("bot ai run ") || (agent != null && agent.autoEnabled() && normalized.startsWith("bot ai ask "))
                || normalized.equals("bot ai cancel");
        if (agent != null && agent.active() && !readOnly && !aiControl)
            agent.cancel("Nhiệm vụ AI đã ngắt bởi lệnh thủ công.");
        if(localTasks.busy() && !readOnly && (normalized.startsWith("bot mine ") || normalized.startsWith("bot goto ") || normalized.startsWith("bot task ")))
            return "Đang chạy nhiệm vụ local. Dùng bot stop trước khi giao tác vụ khác.";
        return commands.execute(input);
    }
    private void notifyConsole(String message) {
        ConsoleServer connected = console;
        if (connected != null) connected.notifyConsole(message);
    }
    private String executeAction(String input) {
        if (input.equals("stop") || input.equals("pause")) { if (chest.busy()) chest.cancel(); localTasks.cancel("Đã hủy quy trình local bởi bot " + input + "."); placement.cancel("Đã hủy chờ đặt bàn."); }
        else if (chest.busy()) return "Đang thao tác rương. Dùng bot stop trước khi giao tác vụ di chuyển/đào.";
        else if(localTasks.busy()) return "Đang chạy quy trình local. Dùng bot stop trước.";
        else if(placement.busy()) return "Đang chờ đặt bàn. Chờ kết quả hoặc dùng bot stop.";
        if(input.startsWith("place ")) {
            if(movement.busy()) return "Đang đi/đào. Dùng bot stop rồi bot start trước khi đặt bàn.";
            String[] parts=input.split(" ");
            net.minecraft.core.BlockPos target=parts.length==5 ? new net.minecraft.core.BlockPos(Integer.parseInt(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4])):null;
            try { return placement.start(Minecraft.getInstance(),target); }
            catch(IllegalArgumentException failure) { return failure.getMessage(); }
        }
        if(input.startsWith("task ")) {
            String[] parts=input.split(" ");return localTasks.start(parts[1],Integer.parseInt(parts[2]));
        }
        if(input.startsWith("mine ")) {
            String[] parts=input.split(" ");
            if(parts[1].equals("cobblestone") || parts[1].equals("minecraft:cobblestone")) return localTasks.start("cobblestone",Integer.parseInt(parts[2]));
        }
        return movement.execute(input);
    }

    private String assignAiKey(String key) {
        return ai == null ? "Module AI chưa khởi tạo được." : ai.assignKey(key);
    }

    private String askAi(String input) {
        if (ai == null) return "Module AI chưa khởi tạo được; xem nhật ký game.";
        if(agent==null) return "Bộ thực thi AI chưa khởi tạo được.";
        Minecraft client = Minecraft.getInstance();
        if(input.equalsIgnoreCase("auto") || input.equalsIgnoreCase("auto status"))
            return "AI tự thực thi: "+(agent.autoEnabled()?"ON":"OFF")+". Lệnh: bot ai auto on/off. Mỗi lần mở game mặc định OFF.";
        if(input.equalsIgnoreCase("auto on")) return agent.setAuto(true);
        if(input.equalsIgnoreCase("auto off")) return agent.setAuto(false);
        if(input.regionMatches(true,0,"auto ",0,5)) return "Lệnh: bot ai auto on/off/status.";
        if (input.equalsIgnoreCase("cancel")) {
            agent.cancel("Đã hủy nhiệm vụ AI; phản hồi đang chờ sẽ không được thực thi."); return agent.status();
        }
        if (input.equalsIgnoreCase("status")) return "AI tự thực thi: "+(agent.autoEnabled()?"ON":"OFF")+" | "+agent.status() + "\n" + ai.execute(input, "");
        if(agent.autoEnabled() && input.regionMatches(true,0,"ask ",0,4) && !input.substring(4).isBlank())
            input="run "+input.substring(4);
        if (input.regionMatches(true,0,"run ",0,4) && !input.substring(4).isBlank()) {
            if(!agent.autoEnabled()) return "AI tự thực thi đang OFF. Bật bằng bot ai auto on trước; bot ai ask hiện chỉ đề xuất.";
            if (agent.active()) return agent.status() + ". Dùng bot ai cancel trước.";
            if (ai.busy() || movement.busy() || chest.busy() || localTasks.busy() || placement.busy()) return "Đang có yêu cầu AI hoặc tác vụ game; chờ xong hoặc dùng bot stop trước.";
            if (client.player == null || client.level == null || !client.player.isAlive()) return "Hãy vào thế giới trước.";
            if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước khi dùng bot ai run.";
            agentWorld=client.level; agentPlayer=client.player;
            return agent.start(input.substring(4));
        }
        if (input.isBlank()) return "Lệnh: bot ai auto on/off/status; bot ai ask/run <nhiệm vụ>; bot ai cancel/status. Khi auto OFF, ask chỉ đề xuất.";
        return ai.execute(input, observation());
    }

    private String describeInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "Chưa vào thế giới. Hãy vào game trước khi xem túi đồ.";
        }
        var slots = client.player.getInventory().getNonEquipmentItems();
        var counts = new TreeMap<String, Integer>();
        int empty = 0;
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) {
                empty++;
            } else {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
                total += stack.getCount();
            }
        }
        String items = counts.isEmpty() ? "Túi đồ trống" : counts.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining("; "));
        return "Túi đồ + thanh nhanh: " + slots.size() + " ô | Ô trống: " + empty + "/" + slots.size()
                + " | Tổng vật phẩm: " + total + " | " + items;
    }

    // Called by the dispatcher on Minecraft's client thread, like all console commands.
    private String describeGame() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "Chưa vào thế giới. Hãy mở một thế giới chơi đơn hoặc kết nối máy chủ.";
        }
        var player = client.player;
        ItemStack held = player.getMainHandItem();
        String item = held.isEmpty() ? "Tay không"
                : held.getHoverName().getString() + " x" + held.getCount();
        return String.format(Locale.ROOT,
                "Đã vào thế giới | Bot: %s | Tọa độ: X=%.2f Y=%.2f Z=%.2f"
                        + " | Chiều không gian: %s | Máu: %.1f/%.1f | Độ đói: %d/20 | Đang cầm: %s | Baritone: %s",
                bot.state(), player.getX(), player.getY(), player.getZ(),
                client.level.dimension().identifier(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), item, movement.status()) + " | Rương: " + chest.status() + " | Local: " + localTasks.status() + " | Đặt bàn: " + placement.status();
    }

	@Override
	public void onInitializeClient() {
        try { TaskPresets.initialize(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/local-tasks.json")); }
        catch(java.io.IOException failure) {LOG.error("Could not initialize local task definitions",failure);}
        try {
            chestMemory.initialize(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/chest-memory.json"));
        } catch (java.io.IOException failure) { LOG.error("Could not initialize chest memory", failure); }
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (player == client.player && world == client.level) chestMemory.interaction(client, hit.getBlockPos());
            return net.minecraft.world.InteractionResult.PASS;
        });
        try {
            ai = new AiController(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot"), message -> {
                ConsoleServer connected = console;
                if (connected != null) connected.notifyConsole(message);
            });
            agent = new AgentLoop((request, answer, failure) -> ai.requestStep(request,
                    proposal -> Minecraft.getInstance().execute(() -> {
                        if (!agentValid(Minecraft.getInstance())) agent.cancel("Đã dừng nhiệm vụ vì trạng thái game thay đổi.");
                        answer.accept(proposal);
                    }), error -> Minecraft.getInstance().execute(() -> failure.accept(error))),
                    commands::execute, this::stopAgentGame, this::notifyConsole, () -> System.nanoTime()/1_000_000);
        } catch (java.io.IOException failure) {
            LOG.error("Could not initialize AI module", failure);
        }
        ClientTickEvents.START_CLIENT_TICK.register(movement::tick);
        ClientTickEvents.START_CLIENT_TICK.register(chest::tick);
        ClientTickEvents.START_CLIENT_TICK.register(placement::tick);
        ClientTickEvents.START_CLIENT_TICK.register(localTasks::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> chestMemory.tick(client, chest.busy()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (agent != null && agent.active() && ++agentTicks % 20 == 0) agent.tick(agentValid(client), movement.busy() || chest.busy() || localTasks.busy(), observation());
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Path gameDirectory = FabricLoader.getInstance().getGameDir();
            try {
                Logging.initialize(gameDirectory.resolve("logs/minecraft-ai-bot"));
                console = new ConsoleServer(gameDirectory.resolve("bot-console.properties"), input -> {
                    CompletableFuture<String> result = new CompletableFuture<>();
                    client.execute(() -> {
                        try {
                            String response = consoleCommand(input);
                            result.complete(response);
                        } catch (RuntimeException failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                    try {
                        return result.get(10, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                        throw new IllegalStateException("Client command could not complete", failure);
                    }
                });
            } catch (Exception failure) {
                LOG.error("Could not start bot console", failure);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {
                if (console != null) console.close();
            } catch (Exception failure) {
                LOG.warn("Could not close bot console", failure);
            } finally {
                if (agent != null) agent.cancel("Game đóng; đã hủy nhiệm vụ AI.");
                localTasks.cancel("Game đóng; đã hủy nhiệm vụ local.");
                placement.cancel("Game đóng; đã hủy chờ đặt bàn.");
                if (ai != null) ai.close();
                chestMemory.close();
                movement.stop();
                bot.stop();
                Logging.close();
            }
        });
	}
}
