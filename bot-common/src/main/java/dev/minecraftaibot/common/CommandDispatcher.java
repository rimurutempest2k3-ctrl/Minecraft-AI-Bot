package dev.minecraftaibot.common;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class CommandDispatcher {
    private static final Logger LOG = Logger.getLogger(CommandDispatcher.class.getName());
    private final BotCore bot;
    private final Supplier<String> information;
    private final Function<String, String> actions;
    private final Supplier<String> inventory;
    private final Function<String, String> ai;
    private final Function<String, String> assignKey;
    private final Function<String, String> chest;

    public CommandDispatcher(BotCore bot) {
        this(bot, () -> "Chế độ thử core độc lập: chưa kết nối với Minecraft.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information) {
        this(bot, information, command -> "Chế độ thử core độc lập: chưa kết nối với Baritone.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions) {
        this(bot, information, actions, () -> "Chế độ thử core độc lập: chưa kết nối với túi đồ Minecraft.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory) {
        this(bot, information, actions, inventory, input -> "Chưa kết nối API AI.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory, Function<String, String> ai) {
        this(bot, information, actions, inventory, ai, key -> "Chưa kết nối phần cấu hình API AI.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory, Function<String, String> ai, Function<String, String> assignKey) {
        this(bot, information, actions, inventory, ai, assignKey, input -> "Chưa kết nối thao tác rương.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory, Function<String, String> ai, Function<String, String> assignKey,
                             Function<String, String> chest) {
        this.bot = Objects.requireNonNull(bot);
        this.information = Objects.requireNonNull(information);
        this.actions = Objects.requireNonNull(actions);
        this.inventory = Objects.requireNonNull(inventory);
        this.ai = Objects.requireNonNull(ai);
        this.assignKey = Objects.requireNonNull(assignKey);
        this.chest = Objects.requireNonNull(chest);
    }
    public String execute(String input) {
        if (input == null || input.isBlank()) return "";
        String original = input.trim();
        // Credential commands must be handled before normalization/logging or AI forwarding.
        if (original.matches("(?is)^bot\\s+get(?:\\s.*)?$")) {
            var match = java.util.regex.Pattern.compile("(?is)^bot\\s+get\\s+api(?:\\s+(.+))?$").matcher(original);
            if (!match.matches() || match.group(1) == null)
                return "Dùng bot get API [groq/gemini] để nhập key ẩn, hoặc bot get API [groq/gemini] <key>.";
            return assignKey.apply(match.group(1));
        }
        if (original.equalsIgnoreCase("bot ai")) return ai.apply("");
        if (original.regionMatches(true, 0, "bot ai ", 0, 7)) return ai.apply(original.substring(7));
        String command = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        LOG.fine("Command received: " + command);
        if (command.equals("bot chest")) return "Lệnh: bot chest open [x y z hoặc ID], bot chest list/memory, bot chest show <ID>, bot chest take/put <mã vật phẩm> <số lượng>, bot chest status/close";
        if (command.startsWith("bot chest ")) {
            String[] parts = command.split(" ");
            if (parts.length == 3 && java.util.Set.of("list", "status", "close", "memory").contains(parts[2])) return chest.apply(parts[2]);
            if (parts.length == 4 && parts[2].equals("show")) return chest.apply("show " + parts[3]);
            if (parts[2].equals("open") && (parts.length == 3 || parts.length == 4 || parts.length == 6)) {
                if (parts.length == 6) try { for (int i = 3; i < 6; i++) Integer.parseInt(parts[i]); }
                catch (NumberFormatException invalid) { return "Tọa độ rương phải là số nguyên."; }
                if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước.";
                return chest.apply(command.substring(10));
            }
            if ((parts[2].equals("take") || parts[2].equals("put")) && parts.length == 5 && parts[3].matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                try {
                    int quantity = Integer.parseInt(parts[4]);
                    if (quantity < 1 || quantity > 2304) return "Số lượng phải là 1-2304.";
                    if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước.";
                    return chest.apply(parts[2] + " " + parts[3] + " " + quantity);
                } catch (NumberFormatException invalid) { return "Số lượng phải là số nguyên 1-2304."; }
            }
            return "Sai cú pháp. Dùng bot chest để xem các lệnh.";
        }
        if (command.equals("bot task") || command.equals("bot task list")) return TaskPresets.list();
        if (command.startsWith("bot task ")) {
            String task = command.substring(9);
            if (task.equals("check")) return information.get() + " | " + inventory.get();
            if (task.equals("inventory")) return inventory.get();
            try { return execute(TaskPresets.miningCommand(task)); }
            catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
        }
        if (command.equals("bot goto") || command.startsWith("bot goto ")) {
            String[] parts = command.split(" ");
            if (parts.length != 5) return "Cú pháp: bot goto <x> <y> <z> (tọa độ nguyên).";
            try {
                int x = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]), z = Integer.parseInt(parts[4]);
                if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước.";
                return actions.apply("goto " + x + " " + y + " " + z);
            } catch (NumberFormatException failure) { return "Tọa độ phải là số nguyên hợp lệ."; }
        }
        if (command.equals("bot mine") || command.startsWith("bot mine ")) {
            String[] parts = command.split(" ");
            if (parts.length != 4 || !parts[2].matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))
                return miningUsage();
            int quantity;
            try { quantity = Integer.parseInt(parts[3]); }
            catch (NumberFormatException failure) { return miningUsage(); }
            if (quantity < 1 || quantity > 2304) return miningUsage();
            if (bot.state() != BotState.RUNNING) return "Hãy nhập bot start trước.";
            return actions.apply("mine " + parts[2] + " " + quantity);
        }
        if (command.equals("bot move") || command.startsWith("bot move "))
            return "Di chuyển đã chuyển sang Baritone. Dùng bot goto <x> <y> <z>.";
        return switch (command) {
            case "help", "bot help" -> "Lệnh: bot start, bot status, bot info, bot inventory, bot chest, bot task list, bot task <tên> [số lượng], bot goto <x> <y> <z>, bot mine <block> <số lượng>, bot pause, bot resume, bot stop, bot get API, bot ai status/ask/result, exit";
            case "bot info" -> information.get();
            case "bot inventory" -> inventory.get();
            case "bot start" -> bot.start();
            case "bot status" -> "Bot state: " + bot.state();
            case "bot pause" -> {
                if (bot.state() == BotState.RUNNING) actions.apply("pause");
                yield bot.pause();
            }
            case "bot resume" -> {
                if (bot.state() == BotState.PAUSED) actions.apply("resume");
                yield bot.resume();
            }
            case "bot stop" -> { actions.apply("stop"); yield bot.stop(); }
            default -> "Unknown command. Type: bot help";
        };
    }
    private static String miningUsage() {
        return "Cú pháp: bot mine <mã block> <số lượng nguyên 1-2304>. Ví dụ: bot mine minecraft:oak_log 16";
    }
}
