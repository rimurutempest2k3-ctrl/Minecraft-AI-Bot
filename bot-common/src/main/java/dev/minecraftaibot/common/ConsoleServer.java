package dev.minecraftaibot.common;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A local terminal controls the same BotCore owned by the Fabric client. */
public final class ConsoleServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ConsoleServer.class.getName());
    private final ServerSocket server;
    private final Path endpoint;
    private final String token;
    private final Function<String, String> dispatch;
    private final java.util.function.Consumer<String> onReply;
    private volatile Socket active;
    private volatile PrintWriter notifications;
    private volatile boolean closed;

    public ConsoleServer(Path endpoint, Function<String, String> dispatch) throws IOException {
        this(endpoint,dispatch,message->{});
    }
    public ConsoleServer(Path endpoint, Function<String,String> dispatch,java.util.function.Consumer<String> onReply) throws IOException {
        this.onReply=onReply;
        this.endpoint = endpoint.toAbsolutePath();
        this.dispatch = dispatch;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = HexFormat.of().formatHex(secret);
        server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            Files.createDirectories(this.endpoint.getParent());
            Properties properties = new Properties();
            properties.setProperty("port", Integer.toString(server.getLocalPort()));
            properties.setProperty("token", token);
            try (Writer writer = Files.newBufferedWriter(this.endpoint, StandardCharsets.UTF_8)) {
                properties.store(writer, "Minecraft AI Bot local console");
            }
        } catch (IOException failure) {
            server.close();
            throw failure;
        }
        Thread thread = new Thread(this::serve, "minecraft-ai-bot-console");
        thread.setDaemon(true);
        thread.start();
        LOG.info("External console ready: " + this.endpoint);
    }

    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                active = socket;
                if (closed) break;
                socket.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), true);
                if (!token.equals(readLine(reader))) {
                    writer.println(I18n.text("Authentication failed."));
                    continue;
                }
                writer.println(I18n.text("Connected to Minecraft AI Bot. Type bot help; exit closes this console."));
                notifications = writer;
                socket.setSoTimeout(0);
                String line;
                while (!closed && (line = readLine(reader)) != null) {
                    if ("exit".equalsIgnoreCase(line.trim())) {
                        writer.println(I18n.text("Console disconnected."));
                        break;
                    }
                    try {
                        String result = dispatch.apply(line);
                        writer.println(I18n.text(result).replace('\n', ' ').replace('\r', ' '));
                        onReply.accept(result==null?"":result);
                    } catch (RuntimeException failure) {
                        LOG.log(Level.WARNING, "Console command failed", failure);
                        writer.println(I18n.text("Command failed. See bot logs."));
                    }
                    if (writer.checkError()) break;
                }
            } catch (IOException failure) {
                if (!closed) LOG.log(Level.FINE, "Console connection closed", failure);
            } finally {
                notifications = null;
                active = null;
            }
        }
    }

    /** Unsolicited messages are framed separately from command replies. */
    public void notifyConsole(String message) {
        PrintWriter writer = notifications;
        if (writer != null) writer.println("EVENT " + I18n.text(message).replace('\n', ' ').replace('\r', ' '));
    }

    static String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') return line.toString();
            if (character != '\r') line.append((char) character);
            if (line.length() > 4096) throw new IOException("Console input too long");
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            Socket socket = active;
            if (socket != null) socket.close();
        } finally {
            try {
                server.close();
            } finally {
                Files.deleteIfExists(endpoint);
            }
        }
    }
}
