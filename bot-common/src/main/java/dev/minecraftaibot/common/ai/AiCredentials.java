package dev.minecraftaibot.common.ai;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

public final class AiCredentials {
    private AiCredentials() { }
    public enum Source { ENVIRONMENT, FILE, NONE }
    public record Credential(String value, Source source) {
        @Override public String toString() { return "Credential[source=" + source + ", value=REDACTED]"; }
    }
    public static void ensureTemplate(Path directory) throws IOException {
        Files.createDirectories(directory);
        try {
            Files.writeString(directory.resolve("secrets.properties"),
                    "# Điền Gemini API key sau dấu =. Không chia sẻ tệp này.\n"
                    + "# Biến môi trường GEMINI_API_KEY được ưu tiên nếu đã đặt.\n"
                    + "gemini_api_key=\ngroq_api_key=\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) { /* Never overwrite user credentials. */ }
    }
    public static Credential load(Path directory, String environmentKey) throws IOException {
        return load(directory, "gemini", environmentKey);
    }
    public static Credential load(Path directory, String provider, String environmentKey) throws IOException {
        checkProvider(provider);
        if (environmentKey != null && !environmentKey.isBlank())
            return new Credential(environmentKey.trim(), Source.ENVIRONMENT);
        Path file = directory.resolve("secrets.properties");
        if (!Files.exists(file)) return new Credential("", Source.NONE);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IOException("Không đọc được secrets.properties; kiểm tra định dạng hoặc quyền đọc tệp.");
        }
        String key = properties.getProperty(provider + "_api_key", "").trim();
        return new Credential(key, key.isEmpty() ? Source.NONE : Source.FILE);
    }
    public static void store(Path directory, String key) throws IOException {
        store(directory, "gemini", key);
    }
    public static void store(Path directory, String provider, String key) throws IOException {
        checkProvider(provider);
        if (key == null || !key.matches("[A-Za-z0-9._-]{10,512}"))
            throw new IOException("Key phải là một chuỗi liền, không có dấu ngoặc, dấu nháy hoặc khoảng trắng.");
        Files.createDirectories(directory);
        Path file = directory.resolve("secrets.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            catch (IOException | IllegalArgumentException failure) { throw new IOException("Không đọc được tệp key hiện tại."); }
        }
        properties.setProperty(provider + "_api_key", key);
        Path temporary = Files.createTempFile(directory, "secrets-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Gemini API key - private local configuration");
            }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void checkProvider(String provider) throws IOException {
        if (!provider.equals("gemini") && !provider.equals("groq")) throw new IOException("Chỉ hỗ trợ gemini và groq.");
    }
}
