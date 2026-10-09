package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Data-only hotbar layout. Slot numbers in the file are 1..9. */
public record InventoryLayout(List<Rule> slots,Map<String,Double> materials) {
    public record Facts(String id,Set<String> tags,boolean regularFood,boolean goldenFood,
                        double nutrition,double saturation,int remaining,int maximum,int count,Map<String,Integer> enchantments) {}
    public record Rule(int slot,boolean reserved,Set<String> items,Set<String> tags,String food,
                       Map<String,Double> preferredItems,Map<String,Double> enchantments,Map<String,Double> weights) {
        public double score(Facts item,Map<String,Double> materials) {
            if(reserved || item.count()<1 || item.maximum()>0 && item.remaining()<=Math.max(5,item.maximum()/50))return -1;
            boolean matches=items.contains(item.id()) || tags.stream().anyMatch(item.tags()::contains)
                    || food.equals("regular") && item.regularFood() || food.equals("golden") && item.goldenFood()
                    || food.equals("any") && (item.regularFood() || item.goldenFood());
            if(!matches)return -1;
            double quality=preferredItems.getOrDefault(item.id(),materials.entrySet().stream()
                    .filter(e->item.id().startsWith(e.getKey())).mapToDouble(Map.Entry::getValue).max().orElse(1));
            double enchantment=0;
            for(var e:item.enchantments().entrySet())enchantment+=e.getValue()*enchantments.getOrDefault(e.getKey(),0d);
            return quality*weights.getOrDefault("quality",0d)+Math.min(10,enchantment)*weights.getOrDefault("enchantments",0d)
                    +(item.maximum()>0?Math.max(0,Math.min(1,(double)item.remaining()/item.maximum())):0)*weights.getOrDefault("durability",0d)
                    +Math.min(64,item.count())*weights.getOrDefault("count",0d)
                    +item.nutrition()*weights.getOrDefault("nutrition",0d)+item.saturation()*weights.getOrDefault("saturation",0d);
        }
    }
    public static InventoryLayout defaults() {
        try(var input=InventoryLayout.class.getResourceAsStream("/inventory-layout.json")) {
            if(input==null)throw new IOException("Missing inventory layout resource");
            return parse(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }catch(IOException failure){throw new IllegalStateException(failure);}
    }
    public static InventoryLayout load(Path file) throws IOException {
        if(!Files.exists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try(var input=InventoryLayout.class.getResourceAsStream("/inventory-layout.json")){Files.copy(input,file);}
        }
        if(Files.size(file)>65536)throw new IOException("Inventory layout exceeds 64 KB");
        return parse(Files.readString(file));
    }
    public static InventoryLayout parse(String source) {
        var root=JsonParser.parseString(source).getAsJsonObject();
        fields(root,Set.of("version","materials","slots"));
        if(!root.get("version").toString().equals("1"))throw new IllegalArgumentException("Unsupported layout version");
        var materials=numbers(root.getAsJsonObject("materials"),false);
        var rules=new ArrayList<Rule>();var seen=new HashSet<Integer>();
        for(var value:root.getAsJsonArray("slots")) {
            var row=value.getAsJsonObject();fields(row,Set.of("slot","reserved","items","tags","food","preferredItems","enchantments","weights"));
            if(!row.has("slot") || !row.get("slot").toString().matches("[1-9]"))throw new IllegalArgumentException("Slot must be 1..9");
            int slot=row.get("slot").getAsInt();if(!seen.add(slot))throw new IllegalArgumentException("Duplicate slot");
            boolean reserved=false;
            if(row.has("reserved")){if(!row.get("reserved").isJsonPrimitive() || !row.get("reserved").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("reserved must be boolean");reserved=row.get("reserved").getAsBoolean();}
            String food=row.has("food")?row.get("food").getAsString():"none";
            if(!Set.of("none","regular","golden","any").contains(food))throw new IllegalArgumentException("Invalid food selector");
            var items=ids(row,"items");var tags=ids(row,"tags");
            if(!reserved && items.isEmpty() && tags.isEmpty() && food.equals("none"))throw new IllegalArgumentException("Slot has no selector");
            var weights=numbers(row.has("weights")?row.getAsJsonObject("weights"):new JsonObject(),true);
            if(!reserved && (weights.isEmpty() || weights.values().stream().allMatch(n->n==0)))throw new IllegalArgumentException("Slot has no scoring weights");
            rules.add(new Rule(slot,reserved,items,tags,food,numbers(row.has("preferredItems")?row.getAsJsonObject("preferredItems"):new JsonObject(),false),
                    numbers(row.has("enchantments")?row.getAsJsonObject("enchantments"):new JsonObject(),false),weights));
        }
        if(seen.size()!=9)throw new IllegalArgumentException("Define all nine hotbar slots");
        rules.sort(Comparator.comparingInt(Rule::slot));return new InventoryLayout(List.copyOf(rules),materials);
    }
    private static void fields(JsonObject value,Set<String> allowed) {
        if(!allowed.containsAll(value.keySet()))throw new IllegalArgumentException("Unknown layout field");
    }
    private static Set<String> ids(JsonObject row,String key) {
        if(!row.has(key))return Set.of();var result=new HashSet<String>();
        for(var value:row.getAsJsonArray(key)){String id=value.getAsString();checkId(id);if(!result.add(id))throw new IllegalArgumentException("Duplicate selector");}
        if(result.size()>64)throw new IllegalArgumentException("Too many selectors");return Set.copyOf(result);
    }
    private static void checkId(String id) {if(id.length()>128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid namespaced ID");}
    private static Map<String,Double> numbers(JsonObject object,boolean weights) {
        if(object.size()>64)throw new IllegalArgumentException("Too many scoring entries");var result=new HashMap<String,Double>();
        for(var e:object.entrySet()) {
            if(weights){if(!Set.of("quality","enchantments","durability","count","nutrition","saturation").contains(e.getKey()))throw new IllegalArgumentException("Unknown scoring weight");}
            else checkId(e.getKey());
            if(!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Score must be numeric");
            double number=e.getValue().getAsDouble();if(!Double.isFinite(number) || number<0 || number>1000)throw new IllegalArgumentException("Score outside 0..1000");
            result.put(e.getKey(),number);
        }return Map.copyOf(result);
    }
}
