package dev.minecraftaibot.common;

import java.util.Map;

/** Basic tasks expand to existing commands; no API call or separate movement engine. */
public final class TaskPresets {
    private TaskPresets() {}
    private record Mining(String block, int quantity) {}
    private static final Map<String, Mining> MINING = Map.of(
            "wood", new Mining("minecraft:oak_log", 16),
            "birch", new Mining("minecraft:birch_log", 16),
            "stone", new Mining("minecraft:stone", 32),
            "coal", new Mining("minecraft:coal_ore", 16),
            "iron", new Mining("minecraft:iron_ore", 8));
    public static String list() {
        return "Nhiệm vụ mẫu: check (trạng thái + túi đồ), inventory (túi đồ), "
                + "wood [16] (gỗ sồi), birch [16] (gỗ bạch dương), stone [32] (local: tự chuẩn bị cúp rồi đào đá), "
                + "coal [16] (đào coal_ore), iron [8] (đào iron_ore). "
                + "Cú pháp: bot task <tên> [số lượng thu thêm 1-2304]. Dùng bot start trước nhiệm vụ đào.";
    }
    public static String miningCommand(String input) {
        String[] parts = input.split(" ");
        Mining preset = MINING.get(parts[0]);
        if (preset == null || parts.length > 2) throw new IllegalArgumentException("Không có nhiệm vụ hoặc sai cú pháp. Dùng bot task list.");
        int quantity = preset.quantity();
        if (parts.length == 2) {
            try { quantity = Integer.parseInt(parts[1]); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Số lượng phải là số nguyên 1-2304."); }
        }
        if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("Số lượng phải là số nguyên 1-2304.");
        return "bot mine " + preset.block() + " " + quantity;
    }
}
