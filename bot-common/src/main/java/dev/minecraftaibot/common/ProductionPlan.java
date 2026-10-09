package dev.minecraftaibot.common;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure production arithmetic shared by the live runner and checks. No game access or clicks. */
public final class ProductionPlan {
    private ProductionPlan() {}
    public static int additionalQuantity(int requested,int present,boolean total) {
        if(requested<1 || requested>2304 || present<0) throw new IllegalArgumentException("Invalid target quantity.");
        return total?Math.max(0,requested-present):requested;
    }
    private static Path tasksFile;
    public record Task(String machine, String input, String fuel, int quantity) {}
    public static void initialize(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        TaskPresets.installDefault(path,"production-tasks.json");
        tasksFile=path;
    }
    public static Map<String,Task> tasks() {
        try {
            if(tasksFile==null) throw new IllegalArgumentException("Task directory not initialized.");
            if(Files.size(tasksFile)>65536) throw new IllegalArgumentException("Production task file is too large.");
            return parseTasks(Files.readString(tasksFile,StandardCharsets.UTF_8));
        } catch(IOException failure) {throw new IllegalArgumentException("Cannot read production-tasks.json.");}
    }
    public static Map<String,Task> parseTasks(String text) {
        try {
            var root=JsonParser.parseString(text).getAsJsonObject();
            if(!root.keySet().equals(Set.of("version","tasks")) || !root.get("version").toString().equals("1"))
                throw new IllegalArgumentException("Unsupported production task version.");
            var entries=root.getAsJsonObject("tasks");
            if(entries.size()>100) throw new IllegalArgumentException("Too many production tasks.");
            var tasks=new TreeMap<String,Task>();
            for(var entry:entries.entrySet()) {
                if(!entry.getKey().matches("smelt_[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Smelting task ID must start with smelt_.");
                var obj=entry.getValue().getAsJsonObject();
                if(!obj.keySet().equals(Set.of("machine","input","fuel","quantity"))) throw new IllegalArgumentException("Invalid smelting task fields.");
                Task t=new Gson().fromJson(obj,Task.class);
                if(!Set.of("furnace","smoker","blast_furnace").contains(t.machine)
                        || !t.input.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        || !(t.fuel.equals("auto") || t.fuel.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                        || !obj.get("quantity").toString().matches("[0-9]+") || t.quantity<1 || t.quantity>64)
                    throw new IllegalArgumentException("Invalid smelting task arguments.");
                tasks.put(entry.getKey(),t);
            }
            return Collections.unmodifiableMap(tasks);
        } catch(RuntimeException failure) {throw new IllegalArgumentException("production-tasks.json: invalid structure or arguments.");}
    }

    public record Smelt(int inputCount, int outputPerInput, int cookingTicks, int fuelTicks) {
        public Smelt {
            if (inputCount < 1 || inputCount > 64 || outputPerInput < 1 || outputPerInput > 64
                    || cookingTicks < 1 || cookingTicks > 72000 || fuelTicks < 1)
                throw new IllegalArgumentException("Invalid smelting batch or duration.");
        }
        public int outputCount() { return Math.multiplyExact(inputCount, outputPerInput); }
        public int fuelCount() {
            return Math.toIntExact(((long) inputCount * cookingTicks + fuelTicks - 1) / fuelTicks);
        }
        public long timeoutMillis() {
            return Math.min(1_800_000L, Math.max(90_000L, (long) inputCount * cookingTicks * 150 + 30_000L));
        }
        public String shortages(int inputs, int fuels, boolean sameItem) {
            if (sameItem) {
                int missing = Math.max(0, inputCount + fuelCount() - inputs);
                return missing == 0 ? "" : "Missing " + missing + " items shared between ingredients and fuel.";
            }
            int inputMissing = Math.max(0, inputCount - inputs), fuelMissing = Math.max(0, fuelCount() - fuels);
            if (inputMissing == 0 && fuelMissing == 0) return "";
            return "Missing ingredients x" + inputMissing + ", fuel x" + fuelMissing + ".";
        }
        /** Only evaluated with an empty cursor and no in-flight click. */
        public boolean conserved(int remainingInput, int outputInFurnace, int outputCollected) {
            return remainingInput >= 0 && remainingInput <= inputCount && outputInFurnace >= 0
                    && outputCollected >= 0 && (long) remainingInput * outputPerInput + outputInFurnace
                    + outputCollected == outputCount();
        }
    }
}
