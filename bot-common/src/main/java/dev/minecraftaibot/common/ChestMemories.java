package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One database per server address (including port) or local save path. */
public final class ChestMemories {
    private final Path legacyFile, directory;
    private final Map<String, ChestMemory> loaded = new HashMap<>();
    public ChestMemories(Path legacyFile) {
        this.legacyFile = legacyFile;
        this.directory = legacyFile.resolveSibling("chest-memory");
    }
    public Path fileFor(String world) {
        String label = world.startsWith("server:") ? world.substring(7) : world;
        if (world.startsWith("local:")) {
            String[] parts = world.substring(6).replace('\\', '/').split("/");
            label = "local-" + parts[parts.length - 1];
        }
        label = label.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (label.length() > 60) label = label.substring(0, 60);
        if (label.isBlank()) label = "server";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(world.getBytes(StandardCharsets.UTF_8)));
            return directory.resolve(label + "--" + hash + ".json");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized ChestMemory memory(String world) throws IOException {
        ChestMemory existing = loaded.get(world);
        if (existing != null) return existing;
        Path file = fileFor(world);
        boolean migrate = !Files.exists(file) && Files.exists(legacyFile);
        ChestMemory result = new ChestMemory(file);
        if (migrate) result.importWorld(new ChestMemory(legacyFile), world);
        loaded.put(world, result);
        return result;
    }
    public String observe(String world, String dimension, List<ChestMemory.Position> positions, JsonArray slots) throws IOException {
        return memory(world).observe(world, dimension, positions, slots);
    }
    public JsonObject find(String world, String id) throws IOException { return memory(world).find(id); }
    public String summary(String world, String dimension) throws IOException { return memory(world).summary(world, dimension); }
    public JsonArray snapshot(String world,String dimension) throws IOException {return memory(world).snapshot(world,dimension);}
}
