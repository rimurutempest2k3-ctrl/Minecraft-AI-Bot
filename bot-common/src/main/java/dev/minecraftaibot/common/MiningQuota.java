package dev.minecraftaibot.common;

/** A net inventory increase measured from the moment a mining task is issued. */
public record MiningQuota(int initial, int requested) {
    public MiningQuota {
        if (initial < 0 || requested < 1 || requested > 2304)
            throw new IllegalArgumentException("Invalid mining quantity");
        Math.addExact(initial, requested);
    }
    public int targetTotal() { return initial + requested; }
    public int collected(int current) { return Math.max(0, current - initial); }
    public boolean complete(int current) { return current >= targetTotal(); }
}
