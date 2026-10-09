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
        this(bot, () -> "Standalone core test: Minecraft is not connected.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information) {
        this(bot, information, command -> "Standalone core test: Baritone is not connected.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions) {
        this(bot, information, actions, () -> "Standalone core test: Minecraft inventory is not connected.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory) {
        this(bot, information, actions, inventory, input -> "AI API is not connected.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory, Function<String, String> ai) {
        this(bot, information, actions, inventory, ai, key -> "AI API configuration is not connected.");
    }
    public CommandDispatcher(BotCore bot, Supplier<String> information, Function<String, String> actions,
                             Supplier<String> inventory, Function<String, String> ai, Function<String, String> assignKey) {
        this(bot, information, actions, inventory, ai, assignKey, input -> "Chest actions are not connected.");
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
                return "Use bot get API [openai/groq/gemini] for hidden key input, or bot get API [openai/groq/gemini] <key>.";
            return assignKey.apply(match.group(1));
        }
        if (original.equalsIgnoreCase("bot ai")) return ai.apply("");
        if (original.regionMatches(true, 0, "bot ai ", 0, 7)) return ai.apply(original.substring(7));
        String command = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if(command.equals("bot language"))return I18n.command("status");
        if(command.startsWith("bot language "))return I18n.command(command.substring(13));
        LOG.fine("Command received: " + command);
        if(command.equals("bot furnace") || command.equals("bot furnace status")) return actions.apply(command.substring(4));
        if(command.startsWith("bot furnace ")) {
            String[] parts=command.split(" ");
            if(parts[2].equals("check") || parts[2].equals("plan")) return actions.apply(command.substring(4));
            if((parts.length==6 || parts.length==7) && parts[2].equals("run")
                    && java.util.Set.of("furnace","blast_furnace","smoker").contains(parts[3])
                    && parts[4].matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")
                    && (parts.length==6 || parts[6].matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))) {
                try {
                    int quantity=Integer.parseInt(parts[5]);
                    if(quantity<1 || quantity>64) return "A smelting batch accepts 1-64 ingredients.";
                    if(bot.state()!=BotState.RUNNING) return "Enter bot start first.";
                    return actions.apply("furnace run "+parts[3]+" "+parts[4]+" "+quantity+" "+(parts.length==7?parts[6]:"auto"));
                } catch(NumberFormatException invalid) {return "Smelting quantity must be an integer from 1-64.";}
            }
            return "Commands: bot furnace run <furnace|blast_furnace|smoker> <ingredient> <1-64> [fuel or auto]; bot furnace status.";
        }
        if (command.equals("bot chest")) return "Commands: bot chest open [x y z or ID], bot chest list/memory, bot chest show <ID>, bot chest take/put <item ID> <quantity>, bot chest status/close";
        if (command.startsWith("bot chest ")) {
            String[] parts = command.split(" ");
            if (parts.length == 3 && java.util.Set.of("list", "status", "close", "memory").contains(parts[2])) return chest.apply(parts[2]);
            if (parts.length == 4 && parts[2].equals("show")) return chest.apply("show " + parts[3]);
            if (parts[2].equals("open") && (parts.length == 3 || parts.length == 4 || parts.length == 6)) {
                if (parts.length == 6) try { for (int i = 3; i < 6; i++) Integer.parseInt(parts[i]); }
                catch (NumberFormatException invalid) { return "Chest coordinates must be integers."; }
                if (bot.state() != BotState.RUNNING) return "Enter bot start first.";
                return chest.apply(command.substring(10));
            }
            if ((parts[2].equals("take") || parts[2].equals("put")) && parts.length == 5 && parts[3].matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                try {
                    int quantity = Integer.parseInt(parts[4]);
                    if (quantity < 1 || quantity > 2304) return "Quantity must be 1-2304.";
                    if (bot.state() != BotState.RUNNING) return "Enter bot start first.";
                    return chest.apply(parts[2] + " " + parts[3] + " " + quantity);
                } catch (NumberFormatException invalid) { return "Quantity must be an integer from 1-2304."; }
            }
            return "Invalid syntax. Use bot chest to list commands.";
        }
        if (command.equals("bot task") || command.equals("bot task list")) {
            try {return TaskPresets.list()+" | Smelting JSON: "+String.join(", ",ProductionPlan.tasks().keySet())+" (requires ingredients/fuel in inventory).";}
            catch(IllegalArgumentException failure) {return TaskPresets.list()+" | "+failure.getMessage();}
        }
        if (command.startsWith("bot task ")) {
            String task = command.substring(9);
            if (task.equals("check")) return information.get() + " | " + inventory.get();
            if (task.equals("inventory")) return inventory.get();
            if(task.startsWith("smelt_")) {
                try {
                    String[] parts=task.split(" ");
                    if(parts.length>2) return "Command: bot task <smelt_name> [ingredient quantity 1-64].";
                    var definition=ProductionPlan.tasks().get(parts[0]);
                    if(definition==null) return "JSON smelting task not found.";
                    int quantity=parts.length==2?Integer.parseInt(parts[1]):definition.quantity();
                    return execute("bot furnace run "+definition.machine()+" "+definition.input()+" "+quantity+" "+definition.fuel());
                } catch(IllegalArgumentException failure) {return "Smelting task: "+failure.getMessage();}
            }
            try {
                String validated=TaskPresets.miningCommand(task);
                if(bot.state()!=BotState.RUNNING) return "Enter bot start first.";
                String[] parts=task.split(" ");
                return actions.apply("task "+parts[0]+" "+validated.substring(validated.lastIndexOf(' ')+1)+(parts.length==3?" "+parts[2]:""));
            }
            catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
        }
        if (command.equals("bot goto") || command.startsWith("bot goto ")) {
            String[] parts = command.split(" ");
            if (parts.length != 5) return "Syntax: bot goto <x> <y> <z> (integer coordinates).";
            try {
                int x = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]), z = Integer.parseInt(parts[4]);
                if (bot.state() != BotState.RUNNING) return "Enter bot start first.";
                return actions.apply("goto " + x + " " + y + " " + z);
            } catch (NumberFormatException failure) { return "Coordinates must be valid integers."; }
        }
        if (command.equals("bot mine") || command.startsWith("bot mine ")) {
            String[] parts = command.split(" ");
            if (parts.length != 4 || !parts[2].matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))
                return miningUsage();
            int quantity;
            try { quantity = Integer.parseInt(parts[3]); }
            catch (NumberFormatException failure) { return miningUsage(); }
            if (quantity < 1 || quantity > 2304) return miningUsage();
            if (bot.state() != BotState.RUNNING) return "Enter bot start first.";
            return actions.apply("mine " + parts[2] + " " + quantity);
        }
        if (command.equals("bot place") || command.startsWith("bot place ")) {
            String[] parts=command.split(" ");
            if ((parts.length!=3 && parts.length!=6) || !(parts[2].equals("crafting_table") || parts[2].equals("minecraft:crafting_table")))
                return "Syntax: bot place crafting_table [x y z]. Currently supports crafting tables.";
            if(parts.length==6) try {
                int x=Integer.parseInt(parts[3]),y=Integer.parseInt(parts[4]),z=Integer.parseInt(parts[5]);
                if(Math.abs((long)x)>29999984 || Math.abs((long)z)>29999984) return "Coordinates outside world bounds.";
            } catch(NumberFormatException invalid) { return "Table placement coordinates must be integers."; }
            if(bot.state()!=BotState.RUNNING) return "Enter bot start first.";
            return actions.apply("place crafting_table"+(parts.length==6?" "+parts[3]+" "+parts[4]+" "+parts[5]:""));
        }
        if (command.equals("bot move") || command.startsWith("bot move "))
            return "Movement now uses Baritone. Use bot goto <x> <y> <z>.";
        return switch (command) {
            case "help", "bot help" -> "Commands: bot start, bot status, bot info, bot inventory, bot chest, bot furnace, bot task list, bot task <name> [quantity] [total|additional], bot goto <x> <y> <z>, bot mine <block> <quantity>, bot place crafting_table [x y z], bot pause, bot resume, bot stop, bot get API, bot ai auto on/off/status, bot ai status/ask/result, exit";
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
        return "Syntax: bot mine <block ID> <integer quantity 1-2304>. Example: bot mine minecraft:oak_log 16";
    }
}
