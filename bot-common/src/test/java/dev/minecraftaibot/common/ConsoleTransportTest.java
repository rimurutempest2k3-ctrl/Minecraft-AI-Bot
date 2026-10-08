package dev.minecraftaibot.common;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

public final class ConsoleTransportTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("bot-console-test");
        Path endpoint = directory.resolve("console.properties");
        BotCore bot = new BotCore();
        CommandDispatcher commands = new CommandDispatcher(bot, () -> "Tọa độ: X=1.00 Y=64.00 Z=2.00 | Đang cầm: Tay không");
        try {
            try (ConsoleServer console = new ConsoleServer(endpoint, commands::execute)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(endpoint, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                int port = Integer.parseInt(properties.getProperty("port"));
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(3000);
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    writer.println("wrong-token");
                    check("Authentication failed.".equals(reader.readLine()));
                    check(bot.state() == BotState.STOPPED);
                }
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(3000);
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    writer.println(properties.getProperty("token"));
                    check(reader.readLine().startsWith("Connected"));
                    writer.println("bot status");
                    check("Bot state: STOPPED".equals(reader.readLine()));
                    console.notifyConsole("AI đã trả lời\nKiểm tra tự động");
                    check("EVENT AI đã trả lời Kiểm tra tự động".equals(reader.readLine()));
                    String[][] cases = {
                        {"bot info", "Tọa độ: X=1.00 Y=64.00 Z=2.00 | Đang cầm: Tay không"},
                        {"bot start", "Bot started."},
                        {"bot status", "Bot state: RUNNING"},
                        {"bot pause", "Bot paused."},
                        {"bot resume", "Bot resumed."},
                        {"exit", "Console disconnected."}
                    };
                    for (String[] test : cases) {
                        writer.println(test[0]);
                        check(test[1].equals(reader.readLine()));
                    }
                    check(bot.state() == BotState.RUNNING);
                }
            }
            check(!Files.exists(endpoint));
            System.out.println("All console transport checks passed.");
        } finally {
            Files.deleteIfExists(endpoint);
            Files.deleteIfExists(directory);
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Console transport check failed");
    }
}
