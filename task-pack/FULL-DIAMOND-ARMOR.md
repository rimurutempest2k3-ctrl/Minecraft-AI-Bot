# Full diamond armor

Use `config/minecraft-ai-bot/tasks/full-diamond-armor.taskbot` with the updated 1.0.1 JAR. Earlier downloads of 1.0.1 do not support the new `smelt`, `skipIf` and `mode` fields.

On the web, open Tasks, browse/import the file, preview it and click Run. Keep survival assistance, automatic armor and shield equipment enabled. Importing the file does not start it.

The workflow prepares food, wood, cobblestone, stone tools and a furnace. It gathers missing raw iron and fuel, smelts up to 30 iron ingots, then prepares an iron pickaxe, sword, shield and armor. It crafts torches and replenishes food before collecting diamonds in batches of 8, 5, 7 and 4 for the chestplate, helmet, leggings and boots.

`count` is the amount required **at that step**, not an amount to collect again. Inventory and worn equipment are counted. `role: "pickaxe"` accepts a usable pickaxe of the requested tier or better, excluding Silk Touch. `skipIf` skips a step only when **all** listed items are owned. Each diamond batch names its resulting armor piece so existing armor avoids that batch. The task contains its crafting recipes; the engine still uses its shared mining and furnace rules.

`smelt` names the required output in `item` and the ingredient in `input`. The runner checks the furnace recipe, collects missing ingredients and fuel, reuses or places a furnace and verifies the output. It will not silently substitute a different result.

This is a resource workflow, not a guarantee of obtaining diamonds in every world. Hunting needs suitable animals nearby, mining needs reachable terrain and inventory space, and server permissions must allow the actions. Existing task time limits and survival interruptions remain active. Test in a spare world first; the complete in-game run has not yet been verified.

## Tiếng Việt

Trong web, vào **Nhiệm vụ → Duyệt file nhiệm vụ**, chọn `full-diamond-armor.taskbot`, xem các bước rồi bấm **Khởi chạy**. Tải lại JAR 1.0.1 đã cập nhật; file tải trước đợt cập nhật này chưa đọc được các trường mới `smelt`, `skipIf` và `mode`.

Chuỗi chuẩn bị thức ăn, gỗ, đá cuội, công cụ đá và lò. Bot thu phần sắt thô còn thiếu, nung đủ tối đa 30 thỏi sắt để làm cúp, kiếm, khiên và giáp sắt. Sau khi làm đuốc và bổ sung thức ăn, bot đào kim cương rồi chế áo, mũ, quần và giày: **24 kim cương** nếu chưa có món nào.

Số lượng là mức cần có tại từng bước. Đồ đang mặc cũng được tính. Cúp tốt hơn được tận dụng; mỗi đợt đào kim cương sẽ bỏ qua khi đã có món giáp tương ứng. Bật sinh tồn, tự mặc giáp và tự trang bị khiên để bot dùng trang bị vừa chế.

Task sẽ dừng nếu không tìm được tài nguyên, hết chỗ hoặc thao tác thất bại. Giới hạn thời gian và ưu tiên sinh tồn vẫn áp dụng. Đã kiểm tra tự động và build; chưa thử hoàn tất cả chuỗi trong game.
