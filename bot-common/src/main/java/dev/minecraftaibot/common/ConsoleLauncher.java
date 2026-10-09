package dev.minecraftaibot.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConsoleLauncher {
    private static final Logger LOG = Logger.getLogger(ConsoleLauncher.class.getName());

    private ConsoleLauncher() {}

    public static void main(String[] args) throws IOException {
        Logging.initialize(Path.of("logs"));
        BotCore bot = new BotCore();
        CommandDispatcher dispatcher = new CommandDispatcher(bot);

        LOG.info("Minecraft AI Bot console initialized (standalone test mode)");
        System.out.println("Type 'bot help' for commands; 'exit' to quit.");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("exit".equalsIgnoreCase(line.trim())) break;
                try {
                    String result = dispatcher.execute(line);
                    if (!result.isBlank()) System.out.println(I18n.text(result));
                } catch (RuntimeException error) {
                    LOG.log(Level.SEVERE, "Command failed", error);
                }
            }
        } finally {
            bot.stop();
            LOG.info("Console closed");
        }
    }
}
