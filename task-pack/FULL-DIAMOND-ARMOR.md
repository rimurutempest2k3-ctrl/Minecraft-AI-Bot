# Full diamond equipment

Run `config/minecraft-ai-bot/tasks/full-diamond-armor.taskbot` with the updated 1.0.1 task runner. The filename is retained for compatibility; it now produces armor and tools.

The task uses `"mode": "collect"`. It prepares food, wood, stone tools, a furnace and iron equipment, then gathers **35 additional diamonds** and crafts one new helmet, chestplate, leggings, boots, pickaxe, axe, shovel, sword and hoe. The five tools also need nine sticks, prepared through the embedded recipes as needed.

Each step measures additions from the stock present when it begins. Existing items do not skip steps; restarting the task requests another batch. Crafting may produce extra items when recipes output stacks. Leave enough inventory space for additional preparation equipment and the new set.

On the web, open Tasks, reload the folder, select the file, preview the steps and click Run. If updating a launcher installation, replace the task file in the active game's config directory first. Web import preserves existing files, so use a different filename when importing another copy.

Keep survival assistance, automatic armor and shield equipment enabled. A nearby furnace can be reused for smelting. Mining needs a usable pickaxe of the required tier, reachable resources and server permissions. Task timeouts and survival interruptions remain active. Automated checks pass; the complete in-game workflow has not yet been verified.

## Tiếng Việt

Task `full-diamond-armor.taskbot` đã gồm **bốn món giáp và năm công cụ kim cương**: cúp, rìu, xẻng, kiếm, cuốc. Tổng nguyên liệu cho phần kim cương là **35 kim cương + 9 que**.

Đầu file ghi `"mode": "collect"`: bot thu thêm nguyên liệu và chế một bộ mới, kể cả đã có đồ tương ứng. Cả các bước chuẩn bị cũng dùng chế độ này. Khởi chạy lại sẽ yêu cầu thêm một đợt mới; cần chừa chỗ trong túi cho đồ mới.

Trong web, vào **Nhiệm vụ → Đọc lại thư mục**, chọn file rồi **Khởi chạy**. Nếu chơi bằng launcher, thay file trong thư mục config của đúng hồ sơ game trước. Khi nhập trên web, đổi tên nếu file đã tồn tại vì chức năng nhập không ghi đè.

Đã kiểm tra tự động và build; chưa thử hoàn tất toàn bộ chuỗi trong game.