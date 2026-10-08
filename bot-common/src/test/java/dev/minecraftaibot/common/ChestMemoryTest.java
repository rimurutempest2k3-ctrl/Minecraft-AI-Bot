package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ChestMemoryTest {
    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("chest-memory-check"); Path file = directory.resolve("memory.json");
        try {
            var a = new ChestMemory.Position(1,64,2); var b = new ChestMemory.Position(2,64,2);
            ChestMemory memory = new ChestMemory(file);
            String id = memory.observe("world-A", "overworld", List.of(a), slots(27,16));
            check(id.equals(memory.observe("world-A", "overworld", List.of(a), slots(27,8))));
            check(memory.find(id).getAsJsonObject("items").get("minecraft:oak_log").getAsInt() == 8);
            String second = memory.observe("world-A", "overworld", List.of(b), slots(27,4));
            check(!id.equals(second));
            String merged = memory.observe("world-A", "overworld", List.of(b,a), slots(54,32));
            check(merged.equals(id) && memory.find(second).get("id").getAsString().equals(id));
            check(id.equals(memory.observe("world-A", "overworld", List.of(a,b), slots(54,16))));
            check(memory.find(id).getAsJsonArray("positions").size() == 2);
            String otherWorld = memory.observe("world-B", "overworld", List.of(a), slots(27,1));
            String otherDimension = memory.observe("world-A", "nether", List.of(a), slots(27,1));
            check(Set.of(id, second, otherWorld, otherDimension).size() == 4);
            memory = new ChestMemory(file);
            check(memory.observe("world-A", "overworld", List.of(b,a), slots(54,64)).equals(id));
            check(memory.find(second).getAsJsonObject("items").get("minecraft:oak_log").getAsInt() == 64);
            check(!memory.summary("world-A", "overworld").contains(otherWorld));
            check(memory.observe("world-A", "overworld", List.of(a), slots(27,2)).equals(id));
            String split = memory.observe("world-A", "overworld", List.of(b), slots(27,3));
            check(!split.equals(id) && !split.equals(second)); // Retired alias is never reused.
            check(memory.find(id).getAsJsonArray("positions").size() == 1);
            String before = Files.readString(file);
            try { memory.observe("world-A", "overworld", List.of(a,b), slots(27,1)); throw new AssertionError(); }
            catch (IOException expected) { check(Files.readString(file).equals(before)); }
            Files.writeString(file, "{broken");
            try { new ChestMemory(file); throw new AssertionError(); }
            catch (IOException expected) { check(Files.readString(file).equals("{broken")); }
            JsonObject database = JsonParser.parseString(before).getAsJsonObject();
            database.getAsJsonArray("chests").add(database.getAsJsonArray("chests").get(0).deepCopy());
            Files.writeString(file, database.toString());
            try { new ChestMemory(file); throw new AssertionError(); }
            catch (IOException expected) { check(Files.readString(file).equals(database.toString())); }
            System.out.println("All chest memory persistence, unique IDs, double merge/split, scope isolation and corruption protection checks passed.");
        } finally { Files.deleteIfExists(file); Files.delete(directory); }
    }
    private static JsonArray slots(int size, int count) {
        JsonArray result = new JsonArray();
        for (int index=0; index<size; index++) {
            JsonObject value = new JsonObject(); value.addProperty("slot", index);
            if (index == 0) { value.addProperty("item", "minecraft:oak_log"); value.addProperty("count", count); }
            result.add(value);
        }
        return result;
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Chest memory check failed"); }
}
