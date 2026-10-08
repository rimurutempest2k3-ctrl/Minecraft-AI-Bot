package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Location identity is scoped to world and dimension. Merged IDs remain aliases, never reused. */
public final class ChestMemory {
    public record Position(int x, int y, int z) {}
    private final Path file;
    private JsonObject data;
    public ChestMemory(Path file) throws IOException {
        this.file = file;
        try {
            if (Files.exists(file)) {
                data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (data.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException();
                Set<String> ids = new HashSet<>();
                for (JsonElement element : entries()) {
                    JsonObject entry = element.getAsJsonObject();
                    if (!ids.add(entry.get("id").getAsString()) || entry.getAsJsonArray("positions").isEmpty()) throw new IllegalArgumentException();
                    entry.get("world").getAsString(); entry.get("dimension").getAsString(); entry.getAsJsonArray("slots").size();
                }
            } else {
                data = new JsonObject(); data.addProperty("schemaVersion", 1); data.add("chests", new JsonArray());
            }
        } catch (RuntimeException invalid) { throw new IOException("File bộ nhớ rương không hợp lệ; giữ nguyên file, không ghi đè."); }
    }
    private JsonArray entries() { return data.getAsJsonArray("chests"); }
    /** Copy an entire scope, including retired aliases, without changing IDs or observations. */
    synchronized void importWorld(ChestMemory source, String world) throws IOException {
        JsonObject previous = data.deepCopy();
        for (JsonElement element : source.entries()) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("world").getAsString().equals(world)) entries().add(entry.deepCopy());
        }
        if (entries().isEmpty()) return;
        try { save(); } catch (IOException failure) { data = previous; throw failure; }
    }
    public synchronized String observe(String world, String dimension, List<Position> positions, JsonArray slots) throws IOException {
        List<Position> sorted = positions.stream().distinct().sorted(Comparator.comparingInt(Position::x).thenComparingInt(Position::y).thenComparingInt(Position::z)).toList();
        if (sorted.isEmpty() || sorted.size() > 2 || slots.size() != sorted.size() * 27) throw new IOException("Tọa độ và kích thước rương không khớp; chưa lưu.");
        JsonObject previous = data.deepCopy();
        JsonArray coordinates = new Gson().toJsonTree(sorted).getAsJsonArray();
        List<JsonObject> matches = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonElement element : entries()) {
            JsonObject entry = element.getAsJsonObject(); ids.add(entry.get("id").getAsString());
            if (entry.has("mergedInto") || !entry.get("world").getAsString().equals(world) || !entry.get("dimension").getAsString().equals(dimension)) continue;
            boolean overlap = false;
            for (JsonElement position : entry.getAsJsonArray("positions")) if (coordinates.contains(position)) overlap = true;
            if (overlap) matches.add(entry);
        }
        JsonObject entry;
        String now = Instant.now().toString();
        if (matches.isEmpty()) {
            entry = new JsonObject(); String id;
            do { id = "CHEST-" + UUID.randomUUID(); } while (ids.contains(id));
            entry.addProperty("id", id); entry.addProperty("firstSeen", now); entries().add(entry);
        } else entry = matches.getFirst();
        String id = entry.get("id").getAsString();
        for (JsonObject alias : matches) if (alias != entry) { alias.addProperty("mergedInto", id); alias.addProperty("lastSeen", now); }
        entry.addProperty("world", world); entry.addProperty("dimension", dimension);
        entry.add("positions", coordinates); entry.addProperty("type", sorted.size() == 2 ? "double" : "single");
        entry.addProperty("slotCount", slots.size()); entry.add("slots", slots.deepCopy());
        JsonObject totals = new JsonObject();
        for (JsonElement element : slots) {
            JsonObject slot = element.getAsJsonObject();
            if (!slot.has("item")) continue;
            String item = slot.get("item").getAsString();
            totals.addProperty(item, (totals.has(item) ? totals.get(item).getAsInt() : 0) + slot.get("count").getAsInt());
        }
        entry.add("items", totals); entry.addProperty("lastSeen", now); entry.addProperty("contentsStatus", "last_observed");
        try { save(); } catch (IOException failure) { data = previous; throw failure; }
        return id;
    }
    public synchronized JsonObject find(String id) {
        Set<String> visited = new HashSet<>();
        while (visited.add(id.toLowerCase(Locale.ROOT))) {
            JsonObject found = null;
            for (JsonElement element : entries()) if (element.getAsJsonObject().get("id").getAsString().equalsIgnoreCase(id)) { found = element.getAsJsonObject(); break; }
            if (found == null) return null;
            if (!found.has("mergedInto")) return found.deepCopy();
            id = found.get("mergedInto").getAsString();
        }
        return null;
    }
    public synchronized String summary(String world, String dimension) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : entries()) {
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("mergedInto") && entry.get("world").getAsString().equals(world) && entry.get("dimension").getAsString().equals(dimension))
                result.add(entry.get("id").getAsString() + " " + entry.get("type").getAsString() + " " + entry.get("positions") + " | Đồ lần cuối: " + entry.get("items"));
        }
        return result.isEmpty() ? "Chưa nhớ rương nào ở thế giới/chiều không gian này." : String.join("; ", result);
    }
    private void save() throws IOException {
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), "chests-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(data));
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
