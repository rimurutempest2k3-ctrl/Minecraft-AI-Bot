package dev.minecraftaibot.common;

import java.util.*;

public final class ChestTransferTest {
    public static void run() {
        verify(List.of(s("wood",64), empty()), 1, Set.of("wood"), 16);
        verify(List.of(s("wood",8), s("wood",64), s("wood",60), empty()), 2, Set.of("wood"), 16);
        verify(List.of(s("A",32), s("B",32), s("A",63), empty(), empty()), 2, Set.of("A","B"), 40);
        verify(List.of(new ChestTransferPlan.Stack("tool",1,1), new ChestTransferPlan.Stack("tool",1,1), empty(), empty()), 2, Set.of("tool"), 2);
        for (int quantity = 1; quantity <= 128; quantity++) verify(List.of(s("wood",64), s("wood",64), s("wood",61), empty(), empty()), 2, Set.of("wood"), quantity);
        rejected(List.of(s("wood",15), empty()), 1, Set.of("wood"), 16);
        rejected(List.of(s("wood",64), s("other",64)), 1, Set.of("wood"), 1);
        rejected(List.of(s("wood",64), s("wood",60)), 1, Set.of("wood"), 5);
        rejected(List.of(s("wood",64), empty()), 1, Set.of("wood"), 0);
        for (int quantity = 1; quantity <= 128; quantity++) verify(List.of(s("wood",61), empty(), empty(), s("wood",64), s("wood",64)), 3, Set.of("wood"), quantity, true);
        verify(List.of(empty(), empty(), new ChestTransferPlan.Stack("tool",1,1), new ChestTransferPlan.Stack("tool",1,1)), 2, Set.of("tool"), 2, true);
        verify(List.of(s("A",63), empty(), empty(), s("A",32), s("B",32)), 3, Set.of("A","B"), 40, true);
        for (var slots : List.of(List.of(empty(), s("wood",15)), List.of(s("other",64), s("wood",64)), List.of(s("wood",60), s("wood",64)))) {
            try { ChestTransferPlan.deposit(slots, 1, Set.of("wood"), 16); throw new AssertionError("Expected deposit refusal"); }
            catch (IllegalArgumentException expected) { }
        }
        BotCore bot = new BotCore(); List<String> requests = new ArrayList<>();
        CommandDispatcher dispatcher = new CommandDispatcher(bot, () -> "", input -> "", () -> "", input -> "", input -> "", input -> { requests.add(input); return "ok"; });
        dispatcher.execute("bot chest take oak_log 16"); check(requests.isEmpty());
        dispatcher.execute("bot start");
        check(dispatcher.execute("bot chest open 1 64 -2").equals("ok")); check(requests.getLast().equals("open 1 64 -2"));
        dispatcher.execute("bot chest take minecraft:oak_log 16"); check(requests.getLast().equals("take minecraft:oak_log 16"));
        dispatcher.execute("bot chest put minecraft:oak_log 16"); check(requests.getLast().equals("put minecraft:oak_log 16"));
        dispatcher.execute("bot chest memory"); check(requests.getLast().equals("memory"));
        dispatcher.execute("bot chest show CHEST-test-id"); check(requests.getLast().equals("show chest-test-id"));
        dispatcher.execute("bot chest open CHEST-test-id"); check(requests.getLast().equals("open chest-test-id"));
        int before = requests.size();
        for (String command : new String[]{"bot chest take oak_log 0", "bot chest take oak_log 2305", "bot chest take oak_log 1.5", "bot chest take oak_log", "bot chest open 1 x 2", "bot chest take oak_log;stop 16", "bot chest put oak_log 0", "bot chest put oak_log 2305", "bot chest put oak_log 1.5", "bot chest put oak_log 16 extra"}) dispatcher.execute(command);
        check(requests.size() == before);
        dispatcher.execute("bot pause"); dispatcher.execute("bot chest take oak_log 16"); check(requests.size() == before);
        dispatcher.execute("bot chest put oak_log 16"); check(requests.size() == before);
        dispatcher.execute("bot chest list"); check(requests.getLast().equals("list"));
        System.out.println("All chest exact quantity, stack merge, components, cursor return, capacity and command validation checks passed.");
    }
    private static void verify(List<ChestTransferPlan.Stack> initial, int chest, Set<String> selected, int quantity) {
        verify(initial, chest, selected, quantity, false);
    }
    private static void verify(List<ChestTransferPlan.Stack> initial, int chest, Set<String> selected, int quantity, boolean deposit) {
        var slots = new ArrayList<>(initial); var cursor = empty();
        int before = inventory(slots, chest, selected);
        var plan = deposit ? ChestTransferPlan.deposit(initial, chest, selected, quantity) : ChestTransferPlan.create(initial, chest, selected, quantity);
        for (var step : plan) {
            check(step.slot() >= 0 && step.slot() < slots.size());
            var target = slots.get(step.slot());
            if (cursor.count() == 0) { check(step.button() == 0); cursor = target; target = empty(); }
            else {
                check(target.count() == 0 || target.kind().equals(cursor.kind()));
                int max = target.count() == 0 ? cursor.max() : target.max();
                int amount = step.button() == 1 ? 1 : Math.min(cursor.count(), max - target.count());
                check(amount > 0 && target.count() + amount <= max);
                target = new ChestTransferPlan.Stack(cursor.kind(), target.count() + amount, max);
                cursor = amount == cursor.count() ? empty() : new ChestTransferPlan.Stack(cursor.kind(), cursor.count() - amount, cursor.max());
            }
            slots.set(step.slot(), target);
            check(slots.equals(step.slots()) && cursor.equals(step.cursor()));
        }
        check(cursor.count() == 0 && (inventory(slots, chest, selected) - before) * (deposit ? -1 : 1) == quantity);
        check(initial.stream().mapToInt(ChestTransferPlan.Stack::count).sum() == slots.stream().mapToInt(ChestTransferPlan.Stack::count).sum());
    }
    private static int inventory(List<ChestTransferPlan.Stack> slots, int chest, Set<String> selected) { return slots.subList(chest, slots.size()).stream().filter(s -> selected.contains(s.kind())).mapToInt(ChestTransferPlan.Stack::count).sum(); }
    private static void rejected(List<ChestTransferPlan.Stack> slots, int chest, Set<String> kinds, int quantity) {
        try { ChestTransferPlan.create(slots, chest, kinds, quantity); throw new AssertionError("Expected refusal"); }
        catch (IllegalArgumentException expected) { }
    }
    private static ChestTransferPlan.Stack s(String kind, int count) { return new ChestTransferPlan.Stack(kind,count,64); }
    private static ChestTransferPlan.Stack empty() { return ChestTransferPlan.Stack.empty(); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Chest transfer check failed"); }
}
