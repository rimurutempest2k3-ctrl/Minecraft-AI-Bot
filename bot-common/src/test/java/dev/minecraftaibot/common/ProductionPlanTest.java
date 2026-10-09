package dev.minecraftaibot.common;

import com.google.gson.JsonParser;
import dev.minecraftaibot.common.ai.AgentLoop;
import dev.minecraftaibot.common.ai.AiSessions;
import java.util.*;

public final class ProductionPlanTest {
    public static void run() throws Exception {
        for(int count=1;count<=64;count++) {
            var furnace=new ProductionPlan.Smelt(count,1,200,1600);
            var blast=new ProductionPlan.Smelt(count,1,100,800);
            check(furnace.fuelCount()==(count+7)/8 && blast.fuelCount()==furnace.fuelCount());
            check(furnace.shortages(count,furnace.fuelCount(),false).isEmpty());
            check(!furnace.shortages(count-1,furnace.fuelCount(),false).isEmpty());
            check(!furnace.shortages(count,furnace.fuelCount()-1,false).isEmpty());
            for(int cooked=0;cooked<=count;cooked++) {
                check(furnace.conserved(count-cooked,cooked,0));
                check(furnace.conserved(count-cooked,0,cooked));
                check(!furnace.conserved(count-cooked,cooked+1,0));
            }
            check(!furnace.conserved(-1,count+1,0));
            check(furnace.timeoutMillis()>=90000 && furnace.timeoutMillis()<=1800000);
        }
        var shared=new ProductionPlan.Smelt(8,1,200,300);
        check(!shared.shortages(8,8,true).isEmpty());
        check(shared.shortages(14,14,true).isEmpty());
        check(ProductionPlan.additionalQuantity(16,5,true)==11);
        check(ProductionPlan.additionalQuantity(16,17,true)==0);
        check(ProductionPlan.additionalQuantity(16,17,false)==16);
        java.util.Map<String,ProductionPlan.Task> defaults;
        try(var source=ProductionPlanTest.class.getResourceAsStream("/task-fixtures/production-tasks.json")) {
            defaults=ProductionPlan.parseTasks(new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        check(defaults.get("smelt_stone").input().equals("minecraft:cobblestone"));
        String json="{\"version\":1,\"tasks\":{\"smelt_custom\":{\"machine\":\"furnace\",\"input\":\"minecraft:sand\",\"fuel\":\"auto\",\"quantity\":8}}}";
        check(ProductionPlan.parseTasks(json).containsKey("smelt_custom"));
        for(String bad:List.of(json.replace("furnace","shell"),json.replace("\"quantity\":8","\"quantity\":1.5"),
                json.replace("minecraft:sand","minecraft:sand;exit"),json.replace("smelt_custom","list"))) {
            try {ProductionPlan.parseTasks(bad);throw new AssertionError();} catch(IllegalArgumentException expected) {}
        }
        var folder=java.nio.file.Files.createTempDirectory("production-task-test");
        var file=folder.resolve("production-tasks.json");
        ProductionPlan.initialize(file);check(java.nio.file.Files.exists(file));
        check(!ProductionPlan.tasks().isEmpty());
        java.nio.file.Files.writeString(file,json);ProductionPlan.initialize(file);
        check(ProductionPlan.tasks().containsKey("smelt_custom"));
        java.nio.file.Files.writeString(file,new com.google.gson.Gson().toJson(Map.of("version",1,"tasks",defaults)));
        check(ProductionPlan.tasks().containsKey("smelt_stone"));
        var bot=new BotCore();List<String> commands=new ArrayList<>();
        var dispatcher=new CommandDispatcher(bot,()->"",command->{commands.add(command);return command;});
        dispatcher.execute("bot furnace run furnace raw_iron 8 coal");check(commands.isEmpty());
        dispatcher.execute("bot start");commands.clear();
        check(dispatcher.execute("bot task smelt_stone 16").equals("furnace run furnace minecraft:cobblestone 16 auto"));
        check(dispatcher.execute("bot task cobblestone 16 total").equals("task cobblestone 16 total"));
        int before=commands.size();
        for(String invalid:List.of("bot furnace run furnace raw_iron 0 coal","bot furnace run furnace raw_iron 65 coal",
                "bot furnace run furnace raw_iron 1.5 coal","bot furnace run furnace raw_iron 8 coal;exit",
                "bot task smelt_stone 8 total")) dispatcher.execute(invalid);
        check(before==commands.size());
        var action=JsonParser.parseString("{\"action\":\"smelt\",\"args\":{\"machine\":\"furnace\",\"item\":\"minecraft:raw_iron\",\"quantity\":8,\"fuel\":\"auto\"},\"message\":\"Smelting sắt\"}").getAsJsonObject();
        check(AgentLoop.command(action).equals("bot furnace run furnace minecraft:raw_iron 8 auto"));
        action.getAsJsonObject("args").addProperty("quantity",65);
        try {AiSessions.validate(action);throw new AssertionError();} catch(java.io.IOException expected) {}
        // A fuel transfer must not include furnace input or result as sources/destinations.
        var slots=new ArrayList<ChestTransferPlan.Stack>(Collections.nCopies(39,ChestTransferPlan.Stack.empty()));
        slots.set(0,new ChestTransferPlan.Stack("ore",16,64));
        slots.set(2,new ChestTransferPlan.Stack("ingot",1,64));
        slots.set(3,new ChestTransferPlan.Stack("coal",20,64));
        var steps=ChestTransferPlan.transfer(slots,3,39,1,2,Set.of("coal"),2,true);
        var finalState=steps.getLast();
        check(finalState.slots().get(0).equals(slots.get(0)) && finalState.slots().get(2).equals(slots.get(2)));
        check(finalState.slots().get(1).count()==2 && finalState.slots().get(3).count()==18 && finalState.cursor().count()==0);
        try {ChestTransferPlan.transfer(slots,0,3,2,39,Set.of("ore"),1,true);throw new AssertionError();} catch(IllegalArgumentException expected) {}
        System.out.println("All furnace batch arithmetic, conservation, shortages, JSON reload, command validation and slot isolation checks passed.");
    }
    private static void check(boolean value) {if(!value) throw new AssertionError("Production check failed");}
}
