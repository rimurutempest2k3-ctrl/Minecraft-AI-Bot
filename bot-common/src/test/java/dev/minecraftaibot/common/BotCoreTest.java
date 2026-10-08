package dev.minecraftaibot.common;

import java.util.ArrayList;
import java.util.List;

public final class BotCoreTest {
    public static void main(String[] args) {
        ChestTransferTest.run();
        BotCore bot = new BotCore();
        List<String> actions = new ArrayList<>();
        CommandDispatcher commands = new CommandDispatcher(bot, () -> "Snapshot", command -> {
            actions.add(command); return "accepted";
        }, () -> "minecraft:oak_log x16");
        check(commands.execute("bot goto 1 64 2").contains("bot start"));
        check(commands.execute("bot place crafting_table").contains("bot start"));
        check(actions.isEmpty());
        check(commands.execute("bot start").equals("Bot started."));
        check(commands.execute(" BOT GOTO 1 64 -2 ").equals("accepted"));
        check(actions.getLast().equals("goto 1 64 -2"));
        check(commands.execute("bot mine minecraft:coal_ore 16").equals("accepted"));
        check(actions.getLast().equals("mine minecraft:coal_ore 16"));
        check(commands.execute("bot place crafting_table").equals("accepted"));
        check(actions.getLast().equals("place crafting_table"));
        check(commands.execute("BOT PLACE minecraft:crafting_table 1 64 -2").equals("accepted"));
        check(actions.getLast().equals("place crafting_table 1 64 -2"));
        int count = actions.size();
        for (String invalid : new String[]{"bot goto 1 2", "bot goto 1.5 64 2", "bot goto 999999999999 1 2",
                "bot mine", "bot mine coal_ore", "bot mine coal_ore extra", "bot mine coal_ore;stop 16",
                "bot mine oak_log 0", "bot mine oak_log -1", "bot mine oak_log 2305",
                "bot mine oak_log 1.5", "bot mine oak_log 999999999999", "bot mine oak_log 16 extra",
                "bot place", "bot place dirt", "bot place crafting_table 1 2", "bot place crafting_table 1.5 64 2",
                "bot place crafting_table 30000000 64 2", "bot place crafting_table;stop", "bot place crafting_table 1 64 2 extra"}) {
            commands.execute(invalid); check(actions.size() == count);
        }
        check(commands.execute("bot pause").equals("Bot paused."));
        check(bot.state() == BotState.PAUSED && actions.getLast().equals("pause"));
        count = actions.size();
        commands.execute("bot pause");
        commands.execute("bot mine stone 8");
        commands.execute("bot place crafting_table");
        check(actions.size() == count);
        check(commands.execute("bot resume").equals("Bot resumed."));
        check(bot.state() == BotState.RUNNING && actions.getLast().equals("resume"));
        check(commands.execute("bot stop").equals("Bot stopped."));
        check(bot.state() == BotState.STOPPED && actions.getLast().equals("stop"));
        commands.execute("bot stop"); check(actions.getLast().equals("stop"));
        check(commands.execute("bot info").equals("Snapshot"));
        int actionCount = actions.size();
        check(commands.execute("  BOT INVENTORY  ").equals("minecraft:oak_log x16"));
        check(actions.size() == actionCount && bot.state() == BotState.STOPPED);
        check(commands.execute("bot help").contains("bot mine"));
        check(commands.execute("bot move forward 2").contains("Baritone"));
        check(commands.execute("bad command").startsWith("Unknown command"));
        check(commands.execute("bot task list").contains("wood"));
        check(commands.execute("bot task check").equals("Snapshot | minecraft:oak_log x16"));
        check(commands.execute("bot task inventory").equals("minecraft:oak_log x16"));
        check(commands.execute("bot task wood 16").contains("bot start"));
        check(actions.size() == actionCount && bot.state() == BotState.STOPPED);
        commands.execute("bot start");
        check(commands.execute("  BOT TASK WOOD  8 ").equals("accepted"));
        check(actions.getLast().equals("task wood 8"));
        commands.execute("bot task cobblestone"); check(actions.getLast().equals("task cobblestone 32"));
        commands.execute("bot task cobblestone 16"); check(actions.getLast().equals("task cobblestone 16"));
        count=actions.size(); check(commands.execute("bot task stone").contains("Đá cuội là cobblestone")); check(actions.size()==count);
        commands.execute("bot task birch"); check(actions.getLast().equals("task birch 16"));
        commands.execute("bot task spruce"); check(actions.getLast().equals("task spruce 16"));
        commands.execute("bot task coal 4"); check(actions.getLast().equals("task coal 4"));
        commands.execute("bot task iron"); check(actions.getLast().equals("task iron 8"));
        count = actions.size();
        for (String invalid : new String[]{"bot task unknown", "bot task check 16", "bot task wood 0", "bot task wood 2305", "bot task wood -1", "bot task wood 1.5", "bot task wood 16 extra"}) {
            commands.execute(invalid); check(actions.size() == count);
        }
        commands.execute("bot pause");
        count = actions.size(); commands.execute("bot task wood");
        check(actions.size() == count && bot.state() == BotState.PAUSED);
        commands.execute("bot stop");
        MiningQuota quota = new MiningQuota(5, 16);
        check(quota.targetTotal() == 21);
        check(quota.collected(5) == 0 && !quota.complete(16));
        check(quota.collected(20) == 15 && !quota.complete(20));
        check(quota.collected(21) == 16 && quota.complete(21));
        check(quota.collected(23) == 18 && quota.complete(23));
        check(quota.collected(0) == 0 && !quota.complete(0));
        check(new MiningQuota(21, 8).targetTotal() == 29);
        System.out.println("All core, Baritone command routing and lifecycle checks passed.");
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Core/Baritone routing check failed"); }
}
