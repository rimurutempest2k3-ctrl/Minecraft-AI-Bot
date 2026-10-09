# Full diamond equipment

Run `config/minecraft-ai-bot/tasks/full-diamond-armor.taskbot` with the updated 1.0.1 task runner. The filename is retained for compatibility; it now produces armor and tools.

The task uses `"mode": "collect"`. It prepares food, wood, stone tools, a furnace and iron equipment, then gathers **35 additional diamonds** and crafts one new helmet, chestplate, leggings, boots, pickaxe, axe, shovel, sword and hoe. The five tools also need nine sticks, prepared through the embedded recipes as needed.

Each step measures additions from the stock present when it begins. Existing items do not skip steps; restarting the task requests another batch. Crafting may produce extra items when recipes output stacks. Leave enough inventory space for additional preparation equipment and the new set.

On the web, open Tasks, reload the folder, select the file, preview the steps and click Run. If updating a launcher installation, replace the task file in the active game's config directory first. Web import preserves existing files, so use a different filename when importing another copy.

Keep survival assistance, automatic armor and shield equipment enabled. A nearby furnace can be reused for smelting. Mining needs a usable pickaxe of the required tier, reachable resources and server permissions. Task timeouts and survival interruptions remain active. Automated checks pass; the complete in-game workflow has not yet been verified.

Food search moves between loaded, safe areas when no suitable animals are nearby (up to eight moves and 160 blocks from the search origin). Raw meat is kept for cooking. Automatic eating uses safe food when injured; at hunger 6/20 or lower it allows raw food, with rotten flesh as a fallback. Suitable food may be retrieved from chests within 100 blocks when no hostile mobs are nearby. After animal search is exhausted, the task checks up to eight chest approaches, then up to 64 mature wheat/potato crops within 32 blocks in loaded terrain. Wheat is crafted into bread using task recipes; potatoes are cooked using furnace rules. If food is still missing, the task stops with a message while enabled survival assistance continues. Run the task again after supplying food or changing area.

## Tiếng Việt

Task `full-diamond-armor.taskbot` đã gồm **bốn món giáp và năm công cụ kim cương**: cúp, rìu, xẻng, kiếm, cuốc. Tổng nguyên liệu cho phần kim cương là **35 kim cương + 9 que**.

Đầu file ghi `"mode": "collect"`: bot thu thêm nguyên liệu và chế một bộ mới, kể cả đã có đồ tương ứng. Cả các bước chuẩn bị cũng dùng chế độ này. Khởi chạy lại sẽ yêu cầu thêm một đợt mới; cần chừa chỗ trong túi cho đồ mới.

Trong web, vào **Nhiệm vụ → Đọc lại thư mục**, chọn file rồi **Khởi chạy**. Nếu chơi bằng launcher, thay file trong thư mục config của đúng hồ sơ game trước. Khi nhập trên web, đổi tên nếu file đã tồn tại vì chức năng nhập không ghi đè.

Đã kiểm tra tự động và build; chưa thử hoàn tất toàn bộ chuỗi trong game.
Khi không thấy thú phù hợp, bot di chuyển tìm ở các khu vực đã tải và an toàn, tối đa tám lần và cách điểm bắt đầu 160 khối. Thịt sống được giữ để nấu. Bot ăn thức ăn an toàn khi mất máu; khi đói còn 6/20 mới dùng thịt sống và dùng thịt thối sau cùng. Có thể lấy thức ăn phù hợp từ rương trong 100 khối khi không có quái ở gần.

Nếu tìm thú thất bại, nhiệm vụ thử rương trong 100 khối (tối đa tám lần tiếp cận), rồi tìm tối đa 64 cây lúa mì/khoai tây đã chín trong 32 khối ở địa hình đã tải. Bot chế bánh mì theo công thức của task hoặc nấu khoai theo dữ liệu lò nung. Nếu vẫn thiếu, nhiệm vụ báo lý do và dừng, bộ sinh tồn đang bật tiếp tục hoạt động. Mang thêm thức ăn hoặc chuyển khu vực rồi chạy lại task.
