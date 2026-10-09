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
                    "# Enter the Gemini API key after =. Do not share this file.\n"
                    + "# GEMINI_API_KEY environment variable takes priority when set.\n"
                    + "gemini_api_key=\ngroq_api_key=\nopenai_api_key=\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
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
            throw new IOException("Cannot read secrets.properties; check format or read permissions.");
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
            throw new IOException("Key must be a continuous string without brackets, quotes or spaces.");
        Files.createDirectories(directory);
        Path file = directory.resolve("secrets.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            catch (IOException | IllegalArgumentException failure) { throw new IOException("Cannot read current key file."); }
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
        if (!java.util.Set.of("gemini","groq","openai").contains(provider)) throw new IOException("Only gemini, groq and openai are supported.");
    }
}
