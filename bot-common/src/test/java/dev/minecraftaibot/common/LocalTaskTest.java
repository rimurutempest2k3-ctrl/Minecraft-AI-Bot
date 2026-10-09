package dev.minecraftaibot.common;

import java.util.*;
import dev.minecraftaibot.common.ChestTransferPlan.Stack;
import com.google.gson.*;

public final class LocalTaskTest {
    public static void run() {
        try {
            var empty=java.nio.file.Files.createTempDirectory("bot-no-embedded-tasks");
            TaskPresets.initialize(empty.resolve("local-tasks.json"));
            check(TaskPresets.workflows().isEmpty());
            check(!java.nio.file.Files.exists(empty.resolve("local-tasks.json")));
            check(!java.nio.file.Files.exists(empty.resolve("production-tasks.json")));
            check(!java.nio.file.Files.exists(empty.resolve("tasks/starter-kit.json")));
            try {TaskPresets.catalog();throw new AssertionError("Missing catalog fell back to embedded task");}catch(IllegalArgumentException expected) {}
            try {ProductionPlan.tasks();throw new AssertionError("Missing production tasks fell back");}catch(IllegalArgumentException expected) {}
            installFixtures(empty);
            TaskPresets.initialize(empty.resolve("local-tasks.json"));
        } catch(java.io.IOException e) {throw new AssertionError(e);}
        verifyCoreMigration();
        var starter=TaskPresets.starter();
        check(starter.steps().size()==10);
        verifyDiamondWorkflow();
        check(starter.steps().getFirst().action().equals("food"));
        check(starter.steps().getFirst().role().equals("food"));
        for(String mob:List.of("cow","pig","sheep","chicken")) {
            check(TaskPresets.huntable("minecraft:"+mob,false,false));
            check(!TaskPresets.huntable("minecraft:"+mob,true,false));
            check(!TaskPresets.huntable("minecraft:"+mob,false,true));
        }
        for(String mob:List.of("player","wolf","cat","horse","zombie","polar_bear")) check(!TaskPresets.huntable("minecraft:"+mob,false,false));
        check(TaskPresets.foodBatch(8,3)==3);check(TaskPresets.foodBatch(2,20)==2);check(TaskPresets.foodBatch(100,80)==64);check(TaskPresets.foodBatch(0,20)==0);
        for(int meat=1;meat<=64;meat++) {
            var plan=new ProductionPlan.Smelt(meat,1,200,300);
            check(plan.fuelCount()*300>=meat*200);check((plan.fuelCount()-1)*300<meat*200);
        }
        check(starter.recipes().get("bread").cells().stream().allMatch(c->c.row()==0 && c.ingredient().equals("minecraft:wheat")));
        check(starter.recipes().get("wheat").count()==9);
        check(starter.recipes().get("planks").cells().getFirst().ingredient().equals("$log"));
        check(starter.steps().stream().filter(s->"pickaxe".equals(s.role())).count()==1);
        // A starter file cannot inject arbitrary console commands or oversized targets.
        String starterJson=new Gson().toJson(starter);
        var starterRoot=JsonParser.parseString(starterJson).getAsJsonObject();starterRoot.addProperty("version",1);
        check(TaskPresets.parseStarter(starterRoot.toString()).steps().size()==10);
        starterRoot.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("action","console");
        try {TaskPresets.parseStarter(starterRoot.toString());throw new AssertionError("Unsafe action accepted");} catch(IllegalArgumentException expected) {}
        starterRoot.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("action","mine");
        starterRoot.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("count",65);
        try {TaskPresets.parseStarter(starterRoot.toString());throw new AssertionError("Oversized target accepted");} catch(IllegalArgumentException expected) {}
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
            installFixtures(directory);
            java.nio.file.Files.writeString(file,edited.toString());TaskPresets.initialize(file);
            var recipeFile=directory.resolve("core-data/minecraft-recipes-26.2.json");recipeFile.toFile().deleteOnExit();
            var furnaceFile=directory.resolve("core-data/furnace-rules-26.2.json");furnaceFile.toFile().deleteOnExit();
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
            var workflowFolder=TaskPresets.workflowDirectory();
            check(TaskPresets.workflows().stream().filter(w->w.error()==null).count()==5);
            var furnaceTask=TaskPresets.workflow("furnace.json");
            check(furnaceTask.goal().item().equals("minecraft:furnace"));
            check(!furnaceTask.goal().satisfied(0) && furnaceTask.goal().satisfied(1) && furnaceTask.goal().satisfied(3));
            check(furnaceTask.plan().steps().getFirst().item().equals("minecraft:cobblestone") && furnaceTask.plan().steps().getFirst().count()==8);
            check(furnaceTask.plan().steps().getLast().item().equals("minecraft:furnace"));
            var furnaceRecipe=furnaceTask.plan().recipes().get("furnace");
            check(furnaceRecipe.count()==1 && furnaceRecipe.width()==3 && furnaceRecipe.cells().size()==8);
            check(furnaceRecipe.cells().stream().allMatch(c->c.ingredient().equals("minecraft:cobblestone") && !(c.row()==1 && c.column()==1)));
            String furnaceJson=java.nio.file.Files.readString(workflowFolder.resolve("furnace.json"));
            var invalidCount=com.google.gson.JsonParser.parseString(furnaceJson).getAsJsonObject();
            invalidCount.getAsJsonObject("goal").addProperty("count",0);
            var invalidItem=com.google.gson.JsonParser.parseString(furnaceJson).getAsJsonObject();
            invalidItem.getAsJsonObject("goal").addProperty("item","minecraft:diamond");
            for(String bad:List.of(invalidCount.toString(),invalidItem.toString())) {
                try {TaskPresets.parseWorkflow("furnace.json",bad);throw new AssertionError("Invalid final goal accepted");}catch(IllegalArgumentException expected) {}
            }
            check(TaskPresets.workflow("food.json").plan().steps().getFirst().action().equals("food"));
            // Removing a task remains effective across engine initialization.
            java.nio.file.Files.delete(workflowFolder.resolve("wood.json"));TaskPresets.initialize(file);
            check(!java.nio.file.Files.exists(workflowFolder.resolve("wood.json")));
            // Shared recipes do not depend on the starter mission existing.
            java.nio.file.Files.delete(workflowFolder.resolve("starter-kit.json"));
            check(TaskPresets.workflow("food.json").plan().recipes().containsKey("furnace"));
            String custom="{\"version\":1,\"title\":\"Gỗ tùy chỉnh\",\"steps\":[{\"label\":\"Gỗ\",\"item\":\"minecraft:birch_log\",\"count\":7,\"action\":\"mine\"}]}";
            java.nio.file.Files.writeString(workflowFolder.resolve("custom.json"),custom);
            check(TaskPresets.workflow("custom.json").plan().steps().getFirst().count()==7);
            var imported=TaskPresets.importWorkflow("Collected-Wood.json",custom);
            check(imported.filename().equals("collected-wood.taskbot"));
            check(TaskPresets.workflow("collected-wood.taskbot").plan().steps().getFirst().count()==7);
            check(TaskPresets.workflows().stream().anyMatch(w->w.filename().equals("collected-wood.taskbot") && w.error()==null));
            // Compatibility aliases allow old command names to find renamed task files.
            check(TaskPresets.workflow("collected-wood.json").filename().equals("collected-wood.taskbot"));
            for(String unsafe:List.of("../outside.taskbot","C:\\outside.taskbot","bad.exe","bad.taskbot/extra","has space.taskbot")) {
                try {TaskPresets.importWorkflow(unsafe,custom);throw new AssertionError("Unsafe import filename accepted");}catch(IllegalArgumentException expected) {}
            }
            String saved=java.nio.file.Files.readString(workflowFolder.resolve("collected-wood.taskbot"));
            try {TaskPresets.importWorkflow("Collected-Wood.taskbot",custom.replace("7","12"));throw new AssertionError("Import overwrote task");}catch(IllegalArgumentException expected) {}
            check(java.nio.file.Files.readString(workflowFolder.resolve("collected-wood.taskbot")).equals(saved));
            for(String bad:List.of("{broken",custom.replace("\"mine\"","\"shell\"")," ".repeat(262145),"{\"version\":"+"[".repeat(33)+"1"+"]".repeat(33)+"}")) {
                try {TaskPresets.importWorkflow("rejected.taskbot",bad);throw new AssertionError("Invalid task imported");}catch(IllegalArgumentException expected) {}
                check(!java.nio.file.Files.exists(workflowFolder.resolve("rejected.taskbot")));
            }
            check(TaskPresets.workflows().stream().anyMatch(w->w.filename().equals("custom.json") && w.error()==null));
            java.nio.file.Files.writeString(workflowFolder.resolve("custom.json"),custom.replace("7","12"));
            check(TaskPresets.workflows().stream().filter(w->w.filename().equals("custom.json")).findFirst().orElseThrow().plan().steps().getFirst().count()==12);
            TaskPresets.initialize(file);check(TaskPresets.workflow("custom.json").plan().steps().getFirst().count()==12);
            for(String unsafe:List.of("../local-tasks.json","C:\\outside.json","custom.json/extra","custom;stop.json")) {
                try {TaskPresets.workflow(unsafe);throw new AssertionError("Path accepted");}catch(IllegalArgumentException expected) {}
            }
            java.nio.file.Files.writeString(workflowFolder.resolve("bad.json"),"{broken");
            check(TaskPresets.workflows().stream().anyMatch(w->w.filename().equals("bad.json") && w.error()!=null));
            check(TaskPresets.workflow("custom.json").plan().steps().getFirst().count()==12);
            java.nio.file.Files.delete(directory.resolve("core-data/task-recipes.json"));
            check(TaskPresets.workflow("custom.json").plan().steps().getFirst().count()==12);
            String needsRecipe=custom.replace("minecraft:birch_log","minecraft:wooden_pickaxe").replace("\"mine\"","\"craft\"");
            java.nio.file.Files.writeString(workflowFolder.resolve("needs-recipe.json"),needsRecipe);
            try {TaskPresets.workflow("needs-recipe.json");throw new AssertionError("Craft ran without recipe");}catch(IllegalArgumentException expected) {}
            java.nio.file.Files.writeString(workflowFolder.resolve("custom.json"),custom.replace("\"mine\"","\"shell\""));
            try {TaskPresets.workflow("custom.json");throw new AssertionError("Arbitrary code accepted");}catch(IllegalArgumentException expected) {}
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
    private static void verifyDiamondWorkflow() {
        try(var source=LocalTaskTest.class.getResourceAsStream("/task-fixtures/tasks/full-diamond-armor.taskbot")) {
            check(source!=null);
            String json=new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var workflow=TaskPresets.parseWorkflow("full-diamond-armor.taskbot",json);
            var steps=workflow.plan().steps();
            check(steps.stream().filter(s->s.item().equals("minecraft:diamond") && s.action().equals("mine")).mapToInt(TaskPresets.Supply::count).sum()==24);
            var smelt=steps.stream().filter(s->s.action().equals("smelt")).findFirst().orElseThrow();
            check(smelt.item().equals("minecraft:iron_ingot") && smelt.input().equals("minecraft:raw_iron") && smelt.count()==30);
            int cost=0;
            for(String part:List.of("helmet","chestplate","leggings","boots")) {
                String armor="minecraft:diamond_"+part;
                var recipe=workflow.plan().recipes().values().stream().filter(r->r.output().equals(armor)).findFirst().orElseThrow();
                cost+=recipe.cells().size();check(recipe.cells().stream().allMatch(c->c.ingredient().equals("minecraft:diamond")));
                check(steps.stream().anyMatch(s->s.item().equals(armor) && s.action().equals("craft")));
                check(steps.stream().anyMatch(s->s.item().equals("minecraft:diamond") && s.skipIf().equals(List.of(armor))));
            }
            check(cost==24);
            check(TaskPresets.miningBlock("minecraft:diamond").equals("minecraft:diamond_ore"));
            check(TaskPresets.miningBlock("minecraft:raw_iron").equals("minecraft:iron_ore"));
            check(TaskPresets.toolTier("minecraft:stone_pickaxe")<TaskPresets.toolTier("minecraft:iron_pickaxe"));
            var root=JsonParser.parseString(json).getAsJsonObject();
            var smelting=root.getAsJsonArray("steps").asList().stream().map(JsonElement::getAsJsonObject).filter(s->s.get("action").getAsString().equals("smelt")).findFirst().orElseThrow();
            smelting.remove("input");
            try {TaskPresets.parseWorkflow("bad.taskbot",root.toString());throw new AssertionError("Smelting without input accepted");}catch(IllegalArgumentException expected) {}
            smelting.addProperty("input","../../secrets.properties");
            try {TaskPresets.parseWorkflow("bad.taskbot",root.toString());throw new AssertionError("Invalid smelting input accepted");}catch(IllegalArgumentException expected) {}
        }catch(java.io.IOException e) {throw new AssertionError(e);}
    }
    static void installFixtures(java.nio.file.Path directory) throws java.io.IOException {
        for(String name:List.of("local-tasks.json","production-tasks.json","core-data/task-recipes.json","core-data/mining-rules.json","tasks/starter-kit.json","tasks/food.json","tasks/wood.json","tasks/cobblestone.json","tasks/furnace.json")) {
            var target=directory.resolve(name);java.nio.file.Files.createDirectories(target.getParent());
            String resource=name.startsWith("tasks/")?name.replace(".json",".taskbot"):name;
            try(var source=LocalTaskTest.class.getResourceAsStream("/task-fixtures/"+resource)) {
                if(source==null)throw new java.io.IOException("Missing test fixture: "+name);
                java.nio.file.Files.copy(source,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static void verifyCoreMigration() {
        try {
            var current=TaskPresets.catalog();
            var folder=java.nio.file.Files.createTempDirectory("bot-core-data-migration");
            var file=folder.resolve("local-tasks.json");
            java.nio.file.Files.writeString(file,new Gson().toJson(current));
            String custom="{\"version\":1,\"recipes\":{}}";
            java.nio.file.Files.writeString(folder.resolve("task-recipes.json"),custom);
            TaskPresets.initialize(file);
            check(java.nio.file.Files.readString(folder.resolve("core-data/task-recipes.json")).equals(custom));
            check(java.nio.file.Files.exists(folder.resolve("task-recipes.json")));
            check(TaskPresets.catalog().preparations().equals(current.preparations()));
            java.nio.file.Files.writeString(folder.resolve("task-recipes.json"),"changed legacy");
            TaskPresets.initialize(file);
            check(java.nio.file.Files.readString(folder.resolve("core-data/task-recipes.json")).equals(custom));
            // Reinstall test data for the rest of the suite; never silently replace live user data.
            installFixtures(folder);TaskPresets.initialize(file);
        } catch(java.io.IOException e) {throw new AssertionError(e);}
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
