package dev.minecraftaibot.common;

/** Fixed local dependency graph; counts are current inventory, never AI suggestions. */
public final class StonePreparation {
    public enum Next { MINE_STONE, COLLECT_LOG, MAKE_PLANKS, MAKE_TABLE, PLACE_TABLE, OPEN_TABLE, MAKE_STICKS, MAKE_PICKAXE }
    public record Supplies(boolean pickaxe, int logs, int planks, int sticks, boolean tableItem, boolean nearbyTable, boolean tableOpen) {}
    public static Next next(Supplies s) {
        if (s.pickaxe()) return Next.MINE_STONE;
        boolean table=s.tableItem() || s.nearbyTable() || s.tableOpen();
        int planksNeeded=3 + (s.sticks() >= 2 ? 0 : 2) + (table ? 0 : 4);
        if (s.planks() < planksNeeded) return s.logs() > 0 ? Next.MAKE_PLANKS : Next.COLLECT_LOG;
        if (!table) return Next.MAKE_TABLE;
        if (s.sticks() < 2) return Next.MAKE_STICKS;
        if (!s.tableOpen()) return s.nearbyTable() ? Next.OPEN_TABLE : Next.PLACE_TABLE;
        return Next.MAKE_PICKAXE;
    }
    public static int logsNeeded(Supplies s) {
        int planks=3 + (s.sticks() >= 2 ? 0 : 2) + (s.tableItem() || s.nearbyTable() || s.tableOpen() ? 0 : 4);
        return Math.max(1, (Math.max(0,planks-s.planks())+3)/4);
    }
}
