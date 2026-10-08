package dev.minecraftaibot.common;

public final class BotCoreTest {
    public static void main(String[] args) {
        BotCore bot = new BotCore();
        CommandDispatcher commands = new CommandDispatcher(bot);

        check(bot.state() == BotState.STOPPED);
        check(commands.execute("bot start").equals("Bot started."));
        check(bot.state() == BotState.RUNNING);
        check(commands.execute("bot pause").equals("Bot paused."));
        check(bot.state() == BotState.PAUSED);
        check(commands.execute("bot resume").equals("Bot resumed."));
        check(bot.state() == BotState.RUNNING);
        check(commands.execute("bot stop").equals("Bot stopped."));
        check(bot.state() == BotState.STOPPED);
        check(commands.execute("bad command").startsWith("Unknown command"));
        System.out.println("All BotCore checks passed.");
    }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("BotCore state test failed");
    }
}
