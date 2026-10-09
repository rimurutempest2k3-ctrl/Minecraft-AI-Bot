package dev.minecraftaibot.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class Logging {
    private Logging() {}

    public static void initialize(Path directory) throws IOException {
        Files.createDirectories(directory);
        Logger root = Logger.getLogger("dev.minecraftaibot.common");
        root.setUseParentHandlers(false);
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
            handler.close();
        }

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                return "[%1$tF %1$tT] [%2$s] [%3$s] %4$s%n".formatted(
                        record.getMillis(),
                        record.getLevel().getName(),
                        record.getLoggerName(),
                        formatMessage(record));
            }
        };

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.ALL);
        console.setFormatter(formatter);

        FileHandler file = new FileHandler(directory.resolve("bot-%g.log").toString(),
                1024 * 1024, 3, true);
        file.setLevel(Level.ALL);
        file.setFormatter(formatter);

        root.setLevel(Level.ALL);
        root.addHandler(console);
        root.addHandler(file);
    }

    public static void close() {
        Logger logger = Logger.getLogger("dev.minecraftaibot.common");
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
            handler.close();
        }
    }
}
