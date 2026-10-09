package dev.minecraftaibot.common;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class InventoryLayoutTest {
    private static void check(boolean value){if(!value)throw new AssertionError("Inventory layout check failed");}
    private static InventoryLayout.Facts sword(String id,int remaining,Map<String,Integer> enchantments) {
        return new InventoryLayout.Facts(id,Set.of("minecraft:swords"),false,false,0,0,remaining,100,1,enchantments);
    }
    public static void run() throws Exception {
        Path file=Files.createTempDirectory("inventory-layout-test").resolve("inventory-layout.json");
        var layout=InventoryLayout.load(file);check(layout.slots().size()==9 && layout.slots().get(5).reserved());
        var rule=layout.slots().getFirst();
        double iron=rule.score(sword("minecraft:iron_sword",100,Map.of()),layout.materials());
        check(rule.score(sword("minecraft:diamond_sword",100,Map.of()),layout.materials())>iron);
        check(rule.score(sword("minecraft:iron_sword",100,Map.of("minecraft:sharpness",3)),layout.materials())>iron);
        check(rule.score(sword("minecraft:diamond_sword",2,Map.of()),layout.materials())<0);
        check(rule.score(new InventoryLayout.Facts("minecraft:dirt",Set.of(),false,false,0,0,0,0,64,Map.of()),layout.materials())<0);
        String original=Files.readString(file);var custom=JsonParser.parseString(original).getAsJsonObject();
        var water=new JsonObject();water.addProperty("slot",6);var ids=new JsonArray();ids.add("minecraft:water_bucket");water.add("items",ids);
        var weights=new JsonObject();weights.addProperty("count",1);water.add("weights",weights);
        custom.getAsJsonArray("slots").set(5,water);Files.writeString(file,custom.toString());
        var changed=InventoryLayout.load(file);check(!changed.slots().get(5).reserved());
        check(changed.slots().get(5).score(new InventoryLayout.Facts("minecraft:water_bucket",Set.of(),false,false,0,0,0,0,1,Map.of()),changed.materials())==1);
        // Existing customized files survive another load.
        InventoryLayout.load(file);check(Files.readString(file).equals(custom.toString()));
        for(int scenario=0;scenario<5;scenario++) {
            var invalid=JsonParser.parseString(original).getAsJsonObject();
            switch(scenario) {
                case 0 -> invalid.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("slot",1);
                case 1 -> invalid.getAsJsonArray("slots").remove(0);
                case 2 -> invalid.getAsJsonArray("slots").get(0).getAsJsonObject().getAsJsonObject("weights").addProperty("quality",-1);
                case 3 -> invalid.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("unexpected",true);
                case 4 -> invalid.addProperty("version",2);
            }
            try {InventoryLayout.parse(invalid.toString());throw new AssertionError("Invalid layout accepted");}catch(IllegalArgumentException expected){}
        }
        Files.writeString(file,"broken");try{InventoryLayout.load(file);throw new AssertionError("Broken JSON accepted");}catch(RuntimeException expected){}
        check(Files.readString(file).equals("broken"));
        System.out.println("Inventory JSON layout, quality/enchantment ranking, worn-tool rejection, custom water slot, validation and file preservation checks passed.");
    }
}
