package dev.minecraftaibot.common;

import java.util.*;
import dev.minecraftaibot.common.ChestTransferPlan.Stack;
import dev.minecraftaibot.common.ChestTransferPlan.Step;

/** One vanilla recipe at a time. No shift-click, creative give, or direct inventory mutation. */
public final class CraftingPlan {
    public static List<Step> create(List<Stack> initial, List<Integer> inventory, List<Integer> grid,
                                   Map<Integer,Set<String>> ingredients, int resultSlot, Stack result) {
        if (grid.stream().anyMatch(i->initial.get(i).count()>0)) throw new IllegalArgumentException("Crafting cells must be empty before starting.");
        if (!grid.containsAll(ingredients.keySet()) || ingredients.isEmpty()) throw new IllegalArgumentException("Recipe does not match the crafting grid.");
        List<Stack> slots=new ArrayList<>(initial); List<Step> steps=new ArrayList<>();
        for(var entry:ingredients.entrySet()) {
            int source=inventory.stream().filter(i->slots.get(i).count()>0 && entry.getValue().contains(slots.get(i).kind())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Missing ingredients; crafting not clicked."));
            Stack stack=slots.get(source); slots.set(source,Stack.empty()); add(steps,source,0,slots,stack);
            slots.set(entry.getKey(),new Stack(stack.kind(),1,stack.max()));
            Stack rest=stack.count()==1?Stack.empty():new Stack(stack.kind(),stack.count()-1,stack.max());
            add(steps,entry.getKey(),1,slots,rest);
            if(rest.count()>0) { slots.set(source,rest); add(steps,source,0,slots,Stack.empty()); }
        }
        int destination=inventory.stream().filter(i->{Stack s=slots.get(i);return s.count()==0 || s.kind().equals(result.kind()) && s.count()+result.count()<=s.max();}).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Insufficient output space; crafting not clicked."));
        for(int index:grid) slots.set(index,Stack.empty());
        slots.set(resultSlot,Stack.empty()); add(steps,resultSlot,0,slots,result);
        Stack before=slots.get(destination); slots.set(destination,new Stack(result.kind(),before.count()+result.count(),result.max()));
        add(steps,destination,0,slots,Stack.empty());
        return List.copyOf(steps);
    }
    private static void add(List<Step> steps,int slot,int button,List<Stack> slots,Stack cursor) { steps.add(new Step(slot,button,List.copyOf(slots),cursor)); }
}
