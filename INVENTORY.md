# Inventory layout

Edit `config/minecraft-ai-bot/inventory-layout.json` in the game directory
(`run/config/` in IntelliJ). Then run `bot survival equipment reload`.
Enable Survival and Equipment & hotbar sorting on the web to use it.

## How the bot reads it

1. Load the file at startup; create the bundled default only if the file is missing.
2. Check version 1, all nine unique slots, selectors and numeric weights.
3. Match inventory items against each slot's selectors and calculate their scores.
4. Process slots 1 through 9 when safe and idle, keeping earlier filled slots first.
5. Swap a better item into the destination and keep the displaced item in inventory.
   Items are never dropped by the sorter. Equal scores keep the current item.

Bad reloads keep the previous layout and leave the file untouched. Reload is refused
while a survival action is active. Slots use visible numbers 1–9, not Java indices.

## Fields

- `reserved: true`: the hotbar sorter leaves that slot alone, including as a source.
- `items`: exact item IDs; `tags`: item tag IDs without `#`. Either can match.
- `food`: `none`, `regular`, `golden` or `any`; food selectors also match by OR.
  Regular food must be edible, have positive nutrition and no consumption effects;
  the bot also excludes food taking over five seconds to eat.
- `materials`: item ID prefixes and their quality ranks, such as `minecraft:diamond_`.
- `preferredItems`: exact item quality overrides for this slot.
- `enchantments`: enchantment IDs and multipliers for their levels.
- `weights`: importance of `quality`, `enchantments`, `durability`, `count`,
  `nutrition` and `saturation`. Omitted weights are zero; values must be 0–1000.

Score = quality × weight + enchantments × weight + durability × weight
+ count × weight + nutrition × weight + saturation × weight.
Enchantment points are capped at 10, count at 64 and durability is a ratio 0–1.
Quality defaults to 1; matching material prefixes use the highest rank, unless
the item has a preferredItems override. Nearly broken tools are rejected.
Higher scores win; slots with overlapping selectors prioritize the earlier slot.

To put a water bucket in slot 6, replace its reserved entry with:

```json
{
  "slot": 6,
  "items": ["minecraft:water_bucket"],
  "weights": { "count": 1 }
}
```

This only arranges the bucket; it does not implement a water landing maneuver.
Armor/offhand equipment, survival priorities and safe transfer logic remain in code.
This file arranges the hotbar, not all 27 main inventory slots.

## Tiếng Việt

Sửa `config/minecraft-ai-bot/inventory-layout.json`, rồi chạy
`bot survival equipment reload`. Bật Sinh tồn và Trang bị & xếp thanh nhanh trên web.
Bot đọc file, kiểm tra chín ô, lọc vật phẩm theo ID/tag/loại thức ăn, chấm điểm và
xếp từ ô 1 đến ô 9 khi an toàn. Đồ cũ được giữ trong túi, không vứt xuống đất.

`reserved` giữ nguyên ô; `items` và `tags` xác định đồ được nhận. `materials` là
điểm chất liệu, `preferredItems` ưu tiên vật phẩm cụ thể, `enchantments` ưu tiên
phù phép, `weights` quyết định mức quan trọng của từng tiêu chí. Điểm cao hơn được
chọn; điểm bằng nhau giữ đồ hiện tại. File lỗi khi nạp lại sẽ giữ bố cục cũ.

Ví dụ trên chuyển ô 6 sang xô nước. Chỉ sắp xếp xô, chưa có kỹ năng đáp đất bằng
nước. Giáp, tay phụ và thao tác chuyển đồ vẫn do code xử lý.
