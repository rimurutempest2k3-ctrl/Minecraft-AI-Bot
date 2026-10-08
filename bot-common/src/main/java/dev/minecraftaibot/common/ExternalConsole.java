package dev.minecraftaibot.common;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class ExternalConsole {
    private ExternalConsole() {}

    public static void main(String[] args) {
        Path endpoint = Path.of(args.length == 0 ? "run/bot-console.properties" : args[0]);
        try {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(endpoint, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            int port = Integer.parseInt(properties.getProperty("port"));
            String token = properties.getProperty("token");
            if (token == null || token.isBlank()) throw new IOException("Missing console token");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(15000);
                BufferedReader server = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), true);
                writer.println(token);
                String greeting = server.readLine();
                System.out.println(greeting);
                if (greeting == null || !greeting.startsWith("Connected")) return;
                socket.setSoTimeout(0);
                BlockingQueue<String> replies = new LinkedBlockingQueue<>();
                Thread receiver = new Thread(() -> {
                    try {
                        String line;
                        while ((line = server.readLine()) != null) {
                            if (line.startsWith("EVENT ")) {
                                System.out.print("\n" + line.substring(6) + "\nbot> ");
                                System.out.flush();
                            } else replies.add(line);
                        }
                    } catch (IOException failure) {
                        // Closing the socket also wakes any command awaiting a reply.
                    } finally { replies.add("\u0000"); }
                }, "bot-console-receiver");
                receiver.setDaemon(true);
                receiver.start();
                BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                Console terminal = System.console();
                String command;
                while (true) {
                    if (terminal != null) command = terminal.readLine("bot> ");
                    else { System.out.print("bot> "); command = keyboard.readLine(); }
                    if (command == null) break;
                    if (command.trim().matches("(?i)^bot\\s+get\\s+api(?:\\s+(?:openai|groq|gemini))?$")) {
                        String normalized=command.trim().toLowerCase(java.util.Locale.ROOT);
                        String provider = normalized.endsWith("openai")?"openai":normalized.endsWith("groq") ? "groq" : "gemini";
                        if (terminal == null) {
                            System.out.println("Terminal này không hỗ trợ nhập key ẩn. Dùng secrets.properties hoặc bot get API " + provider + " <key>.");
                            continue;
                        }
                        char[] secret = terminal.readPassword(provider + " API key (ẩn): ");
                        if (secret == null) continue;
                        command = "bot get API " + provider + " " + new String(secret);
                        Arrays.fill(secret, '\0');
                    }
                    writer.println(command);
                    String response;
                    try { response = replies.take(); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Console interrupted", failure);
                    }
                    if (response.equals("\u0000")) throw new IOException("Minecraft client disconnected");
                    System.out.println(response);
                    if ("exit".equalsIgnoreCase(command.trim())) break;
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Cannot connect: " + failure.getMessage());
            System.err.println("Start the Minecraft client first. Endpoint: " + endpoint.toAbsolutePath());
            System.exit(1);
        }
    }
}
