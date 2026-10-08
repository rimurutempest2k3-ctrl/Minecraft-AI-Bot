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
    private final ChestObserver chestMemory = new ChestObserver(this::notifyConsole);
    private final ChestController chest = new ChestController(bot, movement, chestMemory, this::notifyConsole);
    private final LocalStoneTask localStone = new LocalStoneTask(bot, movement, this::notifyConsole);
    private final CommandDispatcher commands = new CommandDispatcher(bot, this::describeGame,
            this::executeAction, this::describeInventory, this::askAi,
            this::assignAiKey, this::executeChest);
    private String executeChest(String input) {
        if(localStone.busy() && !(input.equals("status") || input.equals("memory") || input.startsWith("show ")))
            return "Đang chuẩn bị/đào đá local. Dùng bot stop trước khi thao tác rương.";
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
    private void stopAgentGame() { if (chest.busy()) chest.cancel(); localStone.cancel("Nhiệm vụ local được AI hủy."); movement.stop(); }
    private String consoleCommand(String input) {
        String normalized=input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean readOnly=java.util.Set.of("bot info", "bot inventory", "bot status", "bot help", "bot task list", "bot task check", "bot task inventory", "bot ai status", "bot ai result", "bot chest memory", "bot chest list", "bot chest status").contains(normalized)
                || normalized.startsWith("bot chest show ");
        if (agent != null && agent.active() && !readOnly && !normalized.startsWith("bot ai run ") && !normalized.equals("bot ai cancel"))
            agent.cancel("Nhiệm vụ AI đã ngắt bởi lệnh thủ công.");
        if(localStone.busy() && !readOnly && (normalized.startsWith("bot mine ") || normalized.startsWith("bot goto ") || normalized.startsWith("bot task ")))
            return "Đang chạy nhiệm vụ local. Dùng bot stop trước khi giao tác vụ khác.";
        return commands.execute(input);
    }
    private void notifyConsole(String message) {
        ConsoleServer connected = console;
        if (connected != null) connected.notifyConsole(message);
    }
    private String executeAction(String input) {
        if (input.equals("stop") || input.equals("pause")) { if (chest.busy()) chest.cancel(); localStone.cancel("Đã hủy quy trình local bởi bot " + input + "."); }
        else if (chest.busy()) return "Đang thao tác rương. Dùng bot stop trước khi giao tác vụ di chuyển/đào.";
        else if(localStone.busy()) return "Đang chạy quy trình local. Dùng bot stop trước.";
        if(input.startsWith("mine ")) {
            String[] parts=input.split(" ");
            if(parts[1].equals("stone") || parts[1].equals("minecraft:stone")) return localStone.start(Integer.parseInt(parts[2]));
        }
        return movement.execute(input);
    }

    private String assignAiKey(String key) {
        return ai == null ? "Module AI chưa khởi tạo được." : ai.assignKey(key);
    }

    private String askAi(String input) {
        if (ai == null) return "Module AI chưa khởi tạo được; xem nhật ký game.";
        Minecraft client = Minecraft.getInstance();
        if (input.equalsIgnoreCase("cancel")) {
            agent.cancel("Đã hủy nhiệm vụ AI; phản hồi đang chờ sẽ không được thực thi."); return agent.status();
        }
        if (input.equalsIgnoreCase("status")) return agent.status() + "\n" + ai.execute(input, "");
        if (input.regionMatches(true,0,"run ",0,4) && !input.substring(4).isBlank()) {
            if (agent.active()) return agent.status() + ". Dùng bot ai cancel trước.";
            if (ai.busy() || movement.busy() || chest.busy() || localStone.busy()) return "Đang có yêu cầu AI hoặc tác vụ game; chờ xong hoặc dùng bot stop trước.";
            if (client.player == null || client.level == null || !client.player.isAlive()) return "Hãy vào thế giới trước.";
            if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước khi dùng bot ai run.";
            agentWorld=client.level; agentPlayer=client.player;
            return agent.start(input.substring(4));
        }
        if (input.isBlank()) return "Lệnh: bot ai run <nhiệm vụ>; bot ai cancel/status; bot ai ask <câu hỏi> chỉ đề xuất.";
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
                player.getFoodData().getFoodLevel(), item, movement.status()) + " | Rương: " + chest.status() + " | Local: " + localStone.status();
    }

	@Override
	public void onInitializeClient() {
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
        ClientTickEvents.START_CLIENT_TICK.register(localStone::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> chestMemory.tick(client, chest.busy()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (agent != null && agent.active() && ++agentTicks % 20 == 0) agent.tick(agentValid(client), movement.busy() || chest.busy() || localStone.busy(), observation());
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
                localStone.cancel("Game đóng; đã hủy nhiệm vụ local.");
                if (ai != null) ai.close();
                chestMemory.close();
                movement.stop();
                bot.stop();
                Logging.close();
            }
        });
	}
}
