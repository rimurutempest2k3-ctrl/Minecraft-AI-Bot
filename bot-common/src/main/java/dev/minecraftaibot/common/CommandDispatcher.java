package dev.minecraftaibot.common;

import java.util.Locale;
import java.util.logging.Logger;

public final class CommandDispatcher {
    private static final Logger LOG = Logger.getLogger(CommandDispatcher.class.getName());
    private final BotCore bot;

    public CommandDispatcher(BotCore bot) {
        this.bot = bot;
    }

    public String execute(String input) {
        if (input == null || input.isBlank()) return "";
        String command = input.trim().toLowerCase(Locale.ROOT);
        LOG.fine("Command received: " + command);

        return switch (command) {
            case "help", "bot help" -> "Commands: bot start, bot status, bot pause, bot resume, bot stop, bot help, exit";
            case "bot start" -> bot.start();
            case "bot status" -> "Bot state: " + bot.state();
            case "bot pause" -> bot.pause();
            case "bot resume" -> bot.resume();
            case "bot stop" -> bot.stop();
            default -> "Unknown command. Type: bot help";
        };
    }
}
