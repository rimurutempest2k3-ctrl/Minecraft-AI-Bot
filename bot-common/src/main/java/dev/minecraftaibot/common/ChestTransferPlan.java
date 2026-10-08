package dev.minecraftaibot.common;

import java.util.*;

/** Exact PICKUP clicks, preflighted before touching a live container. Kind includes components. */
public final class ChestTransferPlan {
    public record Stack(String kind, int count, int max) {
        public static Stack empty() { return new Stack("", 0, 64); }
    }
    public record Step(int slot, int button, List<Stack> slots, Stack cursor) {}
    public static List<Step> create(List<Stack> initial, int chestSlots, Set<String> kinds, int quantity) {
        return transfer(initial, 0, chestSlots, chestSlots, initial.size(), kinds, quantity, false);
    }
    public static List<Step> deposit(List<Stack> initial, int chestSlots, Set<String> kinds, int quantity) {
        return transfer(initial, chestSlots, initial.size(), 0, chestSlots, kinds, quantity, true);
    }
    private static List<Step> transfer(List<Stack> initial, int sourceStart, int sourceEnd, int destinationStart,
                                       int destinationEnd, Set<String> kinds, int quantity, boolean deposit) {
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("Số lượng phải là 1-2304.");
        if (initial.subList(sourceStart, sourceEnd).stream().filter(s -> kinds.contains(s.kind())).mapToInt(Stack::count).sum() < quantity)
            throw new IllegalArgumentException(deposit ? "Túi đồ không đủ vật phẩm; chưa cất gì." : "Rương không đủ vật phẩm; chưa lấy gì.");
        List<Stack> slots = new ArrayList<>(initial); List<Step> steps = new ArrayList<>();
        int remaining = quantity;
        for (int source = sourceStart; source < sourceEnd && remaining > 0; source++) {
            Stack stack = slots.get(source);
            if (!kinds.contains(stack.kind()) || stack.count() == 0) continue;
            int capacity = 0;
            for (int index = destinationStart; index < destinationEnd; index++) {
                Stack destination = slots.get(index);
                if (destination.count() == 0) capacity += stack.max();
                else if (destination.kind().equals(stack.kind())) capacity += Math.max(0, destination.max() - destination.count());
            }
            int amount = Math.min(remaining, Math.min(capacity, stack.count()));
            if (amount == 0) continue;
            Stack cursor = stack; slots.set(source, Stack.empty()); add(steps, source, 0, slots, cursor);
            int toPlace = amount;
            for (int index = destinationStart; index < destinationEnd && toPlace > 0; index++) {
                Stack destination = slots.get(index);
                if (destination.count() > 0 && !destination.kind().equals(stack.kind())) continue;
                int max = destination.count() == 0 ? stack.max() : destination.max();
                int place = Math.min(toPlace, max - destination.count());
                if (place <= 0) continue;
                if (place == Math.min(cursor.count(), max - destination.count())) {
                    destination = new Stack(stack.kind(), destination.count() + place, max);
                    slots.set(index, destination); cursor = remaining(cursor, place);
                    add(steps, index, 0, slots, cursor);
                } else {
                    for (int unit = 0; unit < place; unit++) {
                        destination = new Stack(stack.kind(), destination.count() + 1, max);
                        slots.set(index, destination); cursor = remaining(cursor, 1);
                        add(steps, index, 1, slots, cursor);
                    }
                }
                toPlace -= place;
            }
            if (cursor.count() > 0) {
                slots.set(source, cursor); cursor = Stack.empty(); add(steps, source, 0, slots, cursor);
            }
            remaining -= amount;
        }
        if (remaining > 0) throw new IllegalArgumentException(deposit ? "Rương không đủ chỗ cho số lượng yêu cầu; chưa cất gì." : "Túi đồ không đủ chỗ cho số lượng yêu cầu; chưa lấy gì.");
        return List.copyOf(steps);
    }
    private static Stack remaining(Stack stack, int amount) { return stack.count() == amount ? Stack.empty() : new Stack(stack.kind(), stack.count() - amount, stack.max()); }
    private static void add(List<Step> steps, int slot, int button, List<Stack> slots, Stack cursor) { steps.add(new Step(slot, button, List.copyOf(slots), cursor)); }
}
