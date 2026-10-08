package dev.minecraftaibot.common;

import java.util.*;
import dev.minecraftaibot.common.ChestTransferPlan.Stack;
import static dev.minecraftaibot.common.StonePreparation.Next.*;

public final class LocalStoneTest {
    public static void run() {
        check(next(false,0,0,0,false,false,false)==COLLECT_LOG);
        check(StonePreparation.logsNeeded(s(false,0,0,0,false,false,false))==3);
        check(StonePreparation.logsNeeded(s(false,0,0,0,false,true,false))==2);
        check(next(true,0,0,0,false,false,false)==MINE_STONE);
        check(next(false,3,0,0,false,false,false)==MAKE_PLANKS);
        check(next(false,0,12,0,false,false,false)==MAKE_TABLE);
        check(next(false,0,8,0,true,false,false)==MAKE_STICKS);
        check(next(false,0,6,4,true,false,false)==PLACE_TABLE);
        check(next(false,0,5,0,false,true,false)==MAKE_STICKS);
        check(next(false,0,3,2,false,true,false)==OPEN_TABLE);
        check(next(false,0,3,2,false,true,true)==MAKE_PICKAXE);
        // Execute the graph with empty inventory, confirming minimal materials and one table.
        int logs=0,planks=0,sticks=0,tables=0,craftedTables=0;boolean opened=false,pick=false;
        for(int step=0;step<30;step++) {
            var state=s(pick,logs,planks,sticks,tables>0,false,opened);
            switch(StonePreparation.next(state)) {
                case COLLECT_LOG -> logs+=StonePreparation.logsNeeded(state);
                case MAKE_PLANKS -> {logs--;planks+=4;}
                case MAKE_TABLE -> {planks-=4;tables++;craftedTables++;}
                case MAKE_STICKS -> {planks-=2;sticks+=4;}
                case PLACE_TABLE -> {tables--;opened=true;}
                case OPEN_TABLE -> opened=true;
                case MAKE_PICKAXE -> {planks-=3;sticks-=2;pick=true;}
                case MINE_STONE -> {check(pick && craftedTables==1 && planks==3 && sticks==2);step=30;}
            }
            check(logs>=0 && planks>=0 && sticks>=0);
        }
        check(pick && craftedTables==1);
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
        System.out.println("All local stone prerequisite, existing tool/table skip, minimal wood, mixed plank recipe, exact crafting consumption and preflight checks passed.");
    }
    private static StonePreparation.Supplies s(boolean pick,int logs,int planks,int sticks,boolean table,boolean near,boolean open) {return new StonePreparation.Supplies(pick,logs,planks,sticks,table,near,open);}
    private static StonePreparation.Next next(boolean pick,int logs,int planks,int sticks,boolean table,boolean near,boolean open) {return StonePreparation.next(s(pick,logs,planks,sticks,table,near,open));}
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
