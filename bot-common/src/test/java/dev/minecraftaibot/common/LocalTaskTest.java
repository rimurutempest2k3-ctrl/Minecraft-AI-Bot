package dev.minecraftaibot.common;

import java.util.*;
import dev.minecraftaibot.common.ChestTransferPlan.Stack;
import com.google.gson.*;

public final class LocalTaskTest {
    public static void run() {
        TaskPresets.Catalog catalog=TaskPresets.catalog();var preparation=catalog.preparations().get("wooden_pickaxe");
        check(catalog.tasks().containsKey("spruce"));
        check(preparation.next(state(false,0,0,0,false,false,false)).action().equals("collect_logs"));
        check(preparation.evaluate(state(false,0,0,0,false,false,false)).get("logs_needed")==3);
        check(preparation.evaluate(state(false,0,0,0,false,true,false)).get("logs_needed")==2);
        check(preparation.next(state(true,0,0,0,false,false,false)).action().equals("mine_goal"));
        check(preparation.next(state(false,3,0,0,false,false,false)).recipe().equals("planks"));
        check(preparation.next(state(false,0,12,0,false,false,false)).recipe().equals("table"));
        check(preparation.next(state(false,0,8,0,true,false,false)).recipe().equals("sticks"));
        check(preparation.next(state(false,0,6,4,true,false,false)).action().equals("place_table"));
        check(preparation.next(state(false,0,3,2,false,true,false)).action().equals("open_table"));
        check(preparation.next(state(false,0,3,2,false,true,true)).recipe().equals("wooden_pickaxe"));
        int logs=0,planks=0,sticks=0,tables=0,craftedTables=0;boolean opened=false,pick=false;
        for(int i=0;i<30;i++) {
            var state=state(pick,logs,planks,sticks,tables>0,false,opened);var step=preparation.next(state);
            switch(step.action()) {
                case "collect_logs" -> logs+=preparation.evaluate(state).get("logs_needed");
                case "place_table" -> {tables--;opened=true;}
                case "open_table" -> opened=true;
                case "craft" -> {
                    var recipe=catalog.recipes().get(step.recipe());
                    for(var cell:recipe.cells()) switch(cell.ingredient()) {
                        case "$log" -> logs--;case "#minecraft:planks" -> planks--;case "minecraft:stick" -> sticks--;default -> throw new AssertionError();
                    }
                    switch(recipe.output()) {
                        case "$log_planks" -> planks+=recipe.count();case "minecraft:crafting_table" -> {tables+=recipe.count();craftedTables++;}
                        case "minecraft:stick" -> sticks+=recipe.count();case "minecraft:wooden_pickaxe" -> pick=true;default -> throw new AssertionError();
                    }
                }
                case "mine_goal" -> {check(pick && craftedTables==1 && planks==3 && sticks==2);i=30;}
                default -> throw new AssertionError();
            }
            check(logs>=0 && planks>=0 && sticks>=0);
        }
        check(pick && craftedTables==1);
        JsonObject edited=new Gson().toJsonTree(catalog).getAsJsonObject();
        edited.getAsJsonObject("tasks").add("extra_wood",edited.getAsJsonObject("tasks").get("spruce").deepCopy());
        edited.getAsJsonObject("tasks").getAsJsonObject("extra_wood").addProperty("quantity",7);
        check(TaskPresets.parse(edited.toString()).require("extra_wood").quantity()==7);
        JsonObject broken=edited.deepCopy();broken.getAsJsonObject("preparations").getAsJsonObject("wooden_pickaxe").getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("step").addProperty("action","shell");reject(broken);
        broken=edited.deepCopy();broken.getAsJsonObject("tasks").getAsJsonObject("extra_wood").addProperty("preparation","missing");reject(broken);
        broken=edited.deepCopy();broken.getAsJsonObject("recipes").getAsJsonObject("table").addProperty("count",1.5);reject(broken);
        broken=edited.deepCopy();broken.getAsJsonObject("recipes").getAsJsonObject("table").getAsJsonArray("cells").get(0).getAsJsonObject().addProperty("row",9);reject(broken);
        broken=edited.deepCopy();broken.getAsJsonObject("tasks").getAsJsonObject("extra_wood").addProperty("goal","minecraft:stone;stop");reject(broken);
        try {
            var directory=java.nio.file.Files.createTempDirectory("bot-tasks-test");directory.toFile().deleteOnExit();
            var file=directory.resolve("local-tasks.json");file.toFile().deleteOnExit();
            java.nio.file.Files.writeString(file,edited.toString());TaskPresets.initialize(file);
            var recipeFile=directory.resolve("minecraft-recipes-26.2.json");recipeFile.toFile().deleteOnExit();
            var furnaceFile=directory.resolve("furnace-rules-26.2.json");furnaceFile.toFile().deleteOnExit();
            var furnace=JsonParser.parseString(java.nio.file.Files.readString(furnaceFile)).getAsJsonObject();
            check(furnace.getAsJsonObject("acceptedInputs").getAsJsonObject("furnace").has("minecraft:cobblestone"));
            check(!furnace.getAsJsonObject("acceptedInputs").getAsJsonObject("blast_furnace").has("minecraft:cobblestone"));
            check(furnace.getAsJsonObject("acceptedInputs").getAsJsonObject("smoker").has("minecraft:beef"));
            check(!furnace.getAsJsonObject("acceptedInputs").getAsJsonObject("smoker").has("minecraft:raw_iron"));
            check(furnace.getAsJsonObject("fuels").getAsJsonObject("minecraft:coal").get("burnTicks").getAsInt()==1600);
            check(furnace.getAsJsonObject("fuels").getAsJsonObject("minecraft:lava_bucket").get("burnTicks").getAsInt()==20000);
            check(!furnace.getAsJsonObject("fuels").has("minecraft:crimson_planks"));
            check(!furnace.getAsJsonObject("fuels").has("minecraft:bucket"));
            check(!furnace.getAsJsonObject("slots").get("outputAllowsInsertion").getAsBoolean());
            // Blast furnace doubles both burn rate and cooking speed: coal still smelts eight items.
            check(1600 / furnace.getAsJsonObject("machines").getAsJsonObject("blast_furnace").get("fuelTickDivisor").getAsInt()
                    / furnace.getAsJsonObject("machines").getAsJsonObject("blast_furnace").get("defaultCookingTicks").getAsInt()==8);
            var allRecipes=JsonParser.parseString(java.nio.file.Files.readString(recipeFile)).getAsJsonObject();
            check(allRecipes.get("minecraftVersion").getAsString().equals("26.2"));
            check(allRecipes.getAsJsonObject("recipes").has("minecraft:wooden_pickaxe"));
            java.nio.file.Files.writeString(recipeFile,"user-edited-catalog");
            check(TaskPresets.miningCommand("extra_wood").equals("bot mine minecraft:spruce_log 7"));
            edited.getAsJsonObject("tasks").getAsJsonObject("extra_wood").addProperty("quantity",9);java.nio.file.Files.writeString(file,edited.toString());
            check(TaskPresets.miningCommand("extra_wood").equals("bot mine minecraft:spruce_log 9"));
            TaskPresets.initialize(file);check(TaskPresets.catalog().require("extra_wood").quantity()==9);
            check(java.nio.file.Files.readString(recipeFile).equals("user-edited-catalog"));
            java.nio.file.Files.writeString(file,"broken");try {TaskPresets.catalog();throw new AssertionError();}catch(IllegalArgumentException expected) {}
            check(java.nio.file.Files.readString(file).equals("broken"));
            java.nio.file.Files.writeString(file,edited.toString());
        } catch(java.io.IOException e) {throw new AssertionError(e);}
        // Recipe clicks: mixed plank stacks, exact consumption, single result, no item loss.
        verify(4,Map.of(1,Set.of("log")),new Stack("planks",4,64),List.of(new Stack("log",3,64)),Map.of("log",2,"planks",4));
        verify(4,Map.of(1,Set.of("oak","birch"),2,Set.of("oak","birch"),3,Set.of("oak","birch"),4,Set.of("oak","birch")),
                new Stack("table",1,64),List.of(new Stack("oak",2,64),new Stack("birch",4,64)),Map.of("birch",2,"table",1));
        verify(9,Map.of(1,Set.of("planks"),2,Set.of("planks"),3,Set.of("planks"),5,Set.of("stick"),8,Set.of("stick")),
                new Stack("pick",1,1),List.of(new Stack("planks",7,64),new Stack("stick",4,64)),Map.of("planks",4,"stick",2,"pick",1));
        List<Stack> empty=new ArrayList<>(Collections.nCopies(7,Stack.empty()));
        try { CraftingPlan.create(empty,List.of(5,6),List.of(1,2,3,4),Map.of(1,Set.of("log")),0,new Stack("planks",4,64));throw new AssertionError(); }
        catch(IllegalArgumentException expected) {}
        empty.set(1,new Stack("log",1,64));
        try { CraftingPlan.create(empty,List.of(5,6),List.of(1,2,3,4),Map.of(1,Set.of("log")),0,new Stack("planks",4,64));throw new AssertionError(); }
        catch(IllegalArgumentException expected) {}
        List<Stack> full=new ArrayList<>(Collections.nCopies(6,Stack.empty()));full.set(5,new Stack("log",64,64));
        try { CraftingPlan.create(full,List.of(5),List.of(1,2,3,4),Map.of(1,Set.of("log")),0,new Stack("planks",4,64));throw new AssertionError(); }
        catch(IllegalArgumentException expected) {check(full.get(5).count()==64);}
        System.out.println("All JSON task loading, custom task, live reload, malformed definition rejection, prerequisite skips, minimal wood, recipe consumption and capacity checks passed.");
    }
    private static Map<String,Integer> state(boolean pick,int logs,int planks,int sticks,boolean table,boolean nearby,boolean open) {
        return Map.of("pickaxe",pick?1:0,"logs",logs,"planks",planks,"sticks",sticks,"table_available",table || nearby || open?1:0,"nearby_table",nearby?1:0,"table_open",open?1:0);
    }
    private static void reject(JsonObject json) {try {TaskPresets.parse(json.toString());throw new AssertionError("Invalid JSON accepted");}catch(IllegalArgumentException expected) {}}
    private static void verify(int gridSize,Map<Integer,Set<String>> recipe,Stack result,List<Stack> inventory,Map<String,Integer> expected) {
        List<Stack> initial=new ArrayList<>(Collections.nCopies(gridSize+1,Stack.empty()));initial.addAll(inventory);initial.add(Stack.empty());
        List<Integer> grid=new ArrayList<>(),inv=new ArrayList<>();for(int i=1;i<=gridSize;i++)grid.add(i);for(int i=gridSize+1;i<initial.size();i++)inv.add(i);
        var steps=CraftingPlan.create(initial,inv,grid,recipe,0,result);
        List<Stack> actual=new ArrayList<>(initial);Stack cursor=Stack.empty();
        for(var step:steps) {
            int slot=step.slot();Stack source=actual.get(slot);
            if(slot==0) {
                check(cursor.count()==0);
                for(var ingredient:recipe.entrySet()) {check(actual.get(ingredient.getKey()).count()==1 && ingredient.getValue().contains(actual.get(ingredient.getKey()).kind()));actual.set(ingredient.getKey(),Stack.empty());}
                cursor=result;
            } else if(cursor.count()==0) {cursor=source;actual.set(slot,Stack.empty());}
            else {
                check(source.count()==0 || source.kind().equals(cursor.kind()));
                int amount=step.button()==1?1:cursor.count();actual.set(slot,new Stack(cursor.kind(),source.count()+amount,cursor.max()));
                cursor=cursor.count()==amount?Stack.empty():new Stack(cursor.kind(),cursor.count()-amount,cursor.max());
            }
            check(actual.equals(step.slots()) && cursor.equals(step.cursor()));
        }
        check(cursor.count()==0 && grid.stream().allMatch(i->actual.get(i).count()==0));
        Map<String,Integer> counts=new HashMap<>();for(int i:inv) {Stack stack=actual.get(i);if(stack.count()>0)counts.merge(stack.kind(),stack.count(),Integer::sum);}
        check(counts.equals(expected));
    }
    private static void check(boolean condition) {if(!condition)throw new AssertionError("Local stone/crafting check failed");}
}
