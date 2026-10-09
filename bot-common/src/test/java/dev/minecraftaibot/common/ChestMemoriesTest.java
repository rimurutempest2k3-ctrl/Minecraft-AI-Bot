package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ChestMemoriesTest {
    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("server-chests-check");
        try {
            Path legacy = directory.resolve("chest-memory.json");
            ChestMemory old = new ChestMemory(legacy);
            String a = "server:play.example.net:25565", b = "server:play.example.net:25566";
            var p = new ChestMemory.Position(1,64,1); var q = new ChestMemory.Position(2,64,1);
            String id = old.observe(a,"overworld",List.of(p),slots(27,16));
            String alias = old.observe(a,"overworld",List.of(q),slots(27,2));
            old.observe(a,"overworld",List.of(p,q),slots(54,18));
            String other = old.observe(b,"overworld",List.of(p),slots(27,4));
            String original = Files.readString(legacy);
            ChestMemories store = new ChestMemories(legacy);
            check(!store.fileFor(a).equals(store.fileFor(b)));
            check(store.fileFor(a).getFileName().toString().startsWith("play.example.net_25565--"));
            check(store.find(a, alias).get("id").getAsString().equals(id));
            check(store.find(a, other) == null && store.find(b, id) == null);
            check(store.find(b, other) != null);
            check(!Files.readString(store.fileFor(a)).contains(other));
            check(!Files.readString(store.fileFor(b)).contains(id));
            check(original.equals(Files.readString(legacy)));
            store.observe(a,"overworld",List.of(p,q),slots(54,7));
            store = new ChestMemories(legacy);
            check(store.find(a,id).getAsJsonObject("items").get("minecraft:oak_log").getAsInt() == 7);
            check(!store.fileFor("server:a:b").equals(store.fileFor("server:a_b")));
            check(!store.fileFor("local:C:/saves/world").equals(store.fileFor("local:D:/saves/world")));
            check(store.fileFor("server:../../CON:25565").normalize().getParent().equals(directory.resolve("chest-memory")));
            Files.writeString(store.fileFor(a), "{broken");
            store = new ChestMemories(legacy);
            try { store.find(a,id); throw new AssertionError(); }
            catch (IOException expected) { check(Files.readString(store.fileFor(a)).equals("{broken")); }
            check(store.find(b,other) != null);
            System.out.println("All per-server file isolation, address/port naming, legacy ID/alias migration, restart and corruption checks passed.");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static JsonArray slots(int size, int count) {
        JsonArray result = new JsonArray();
        for (int i=0;i<size;i++) {
            JsonObject slot = new JsonObject(); slot.addProperty("slot",i);
            if (i==0) { slot.addProperty("item","minecraft:oak_log"); slot.addProperty("count",count); }
            result.add(slot);
        }
        return result;
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Per-server chest memory check failed"); }
}
