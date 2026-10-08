# Nhiệm vụ local từ JSON

Các nhiệm vụ này chạy trực tiếp bằng lệnh bot/Baritone hiện có, không gọi API AI.
Xem danh sách: `bot task list`.

| Lệnh | Nhiệm vụ | Số lượng mặc định |
| --- | --- | --- |
| bot task check | Xem trạng thái game và túi đồ | — |
| bot task inventory | Xem túi đồ | — |
| bot task wood [số lượng] | Thu gỗ sồi, tìm oak_log | 16 |
| bot task birch [số lượng] | Thu gỗ bạch dương, tìm birch_log | 16 |
| bot task spruce [số lượng] | Thu gỗ vân sam, tìm spruce_log | 16 |
| bot task cobblestone [số lượng] | Local: chuẩn bị cúp rồi thu đá cuội | 32 |
| bot task coal [số lượng] | Đào coal_ore | 16 |
| bot task iron [số lượng] | Đào iron_ore | 8 |

Ví dụ:

```text
bot task check
bot start
bot task wood 16
bot info
```

Số lượng là **thu thêm** kể từ khi bắt đầu nhiệm vụ, không phải tổng có trong túi đồ.
Giới hạn 1–2304 giống bot mine. Với coal/iron, bộ đếm dùng vật phẩm rơi của block
qua bộ lọc Baritone hiện có; công cụ có Silk Touch có thể làm thay đổi loại vật phẩm.
Bot dừng đào khi đủ số lượng theo cơ chế đã kiểm tra trước đó.

Phải vào thế giới và nhập bot start trước khi chạy nhiệm vụ đào. Riêng cobblestone đã có chuỗi
local: kiểm tra cúp → thu gỗ thiếu → chế ván/bàn/que/cúp gỗ → đào. Bàn gần hoặc bàn/cúp
đã có được dùng lại. Xem LOCAL_TASKS.vi.md. Cobblestone chỉ tính đá cuội thu thêm, không cộng stone; bỏ qua cúp có Silk Touch.
Coal/iron chưa tự chế công cụ; iron chỉ nhắm iron_ore,
coal chỉ nhắm coal_ore, chưa gộp biến thể deepslate. wood chỉ là gỗ sồi, không phải mọi loại gỗ.

Chạy nhiệm vụ đào mới sẽ thay thế nhiệm vụ Baritone đang chạy; không có hàng đợi nhiều
nhiệm vụ. Khi chuỗi cobblestone đang chạy, dùng bot stop trước khi giao tác vụ khác. Pause/stop
hủy chuỗi cobblestone; resume không khôi phục chuỗi này. Khi PAUSED, nhiệm vụ mẫu không tự
bỏ qua tạm dừng. bot task check/inventory chỉ đọc thông tin, không làm đổi tác vụ đang chạy.

Với yêu cầu cần AI lập kế hoạch, bật bot ai auto on rồi dùng bot ai ask hoặc bot ai run. Khi auto OFF, ask chỉ đề xuất.
Chuỗi cobblestone hoạt động khi không có API. Các nhiệm vụ mẫu không được
coi là đã hoàn thành chỉ vì AI báo done; kiểm tra tiến độ và kết quả bằng bot info/inventory.

Đá cuội: minecraft:cobblestone. Đá nguyên khối: minecraft:stone (cần Silk Touch hoặc nung đá cuội). Nhiệm vụ sản xuất stone chưa hỗ trợ; tên nhiệm vụ chuẩn là bot task cobblestone.

## File định nghĩa và thêm nhiệm vụ

Khi chạy từ IntelliJ, bot dùng `run/config/minecraft-ai-bot/local-tasks.json`.
Khi chạy mod đã xuất, bot dùng `config/minecraft-ai-bot/local-tasks.json` trong thư mục game.
Lần đầu bot chép mẫu từ tài nguyên `tasks/local-tasks.json` trong mod; không ghi đè file đã có.
Các lần khởi động sau vẫn giữ file người dùng sửa. JSON được đọc lại khi nhận nhiệm vụ mới;
nhiệm vụ đang chạy giữ bản đã đọc để không đổi kế hoạch giữa chừng.

File có ba nhóm:

- `tasks`: tên, mô tả, `goal` (block/mục tiêu đào), `resultItem` (vật phẩm được tính), `quantity` mặc định và `preparation` tùy chọn.
- `recipes`: vật phẩm đầu ra, lượng đầu ra, lưới 2x2/3x3 và nguyên liệu từng ô. Hàng/cột tính từ 0. Công thức 2x2 được ánh xạ đúng khi menu đang là 3x3.
- `preparations`: ngân sách ván khi còn thiếu công cụ/bàn/que; danh sách quy tắc chọn bước, xét từ trên xuống. Sau mỗi bước, bot kiểm tra lại túi đồ và chọn quy tắc phù hợp đầu tiên.

Ví dụ thêm một mục vào `tasks`, giữ dấu phẩy hợp lệ giữa các mục:

```json
"dark_oak": {
  "label": "Thu gỗ sồi sẫm",
  "goal": "minecraft:dark_oak_log",
  "resultItem": "minecraft:dark_oak_log",
  "quantity": 16
}
```

Sau khi lưu file, chạy `bot task list`, rồi `bot task dark_oak 16`. Không cần sửa Java/build lại.
Muốn nhiệm vụ tái dùng chuỗi chuẩn bị cúp hiện tại, thêm `"preparation": "wooden_pickaxe"`.
Chuỗi này chỉ chuẩn bị cúp gỗ khi thiếu cúp thông thường, không đảm bảo cấp công cụ phù hợp với mọi loại quặng.
Coal/iron mặc định vẫn yêu cầu công cụ sẵn có; iron cần cúp đá trở lên.

## Điều kiện và bước hỗ trợ

`when` là danh sách điều kiện cùng phải đúng (AND). `when: []` luôn đúng; quy tắc cuối là bước dự phòng.
Mỗi điều kiện có `field`, `op` (`lt`: nhỏ hơn, `gte`: lớn hơn hoặc bằng), `value` (số nguyên hoặc biến `$field`).
Các biến: `pickaxe`, `logs`, `planks`, `sticks`, `table_available`, `nearby_table`, `table_open`,
`planks_needed`, `logs_needed`. Trạng thái đúng/sai là 1/0. Cúp usable phải không Silk Touch và còn hơn 2 độ bền.

Các bước được bộ thực thi hiểu:

| action | Ý nghĩa |
| --- | --- |
| mine_goal | Đi đào mục tiêu của nhiệm vụ đến khi thu đủ vật phẩm |
| collect_logs | Thu số gỗ còn thiếu tính từ ngân sách ván |
| craft | Chế theo `recipe` được tham chiếu trong nhóm recipes |
| place_table | Đặt bàn, có bước nhảy/đổi chỗ 1–2 block đã triển khai |
| open_table | Dùng bàn đã có gần đó, tự đi tới nếu chưa chạm tới |

Nguyên liệu công thức hỗ trợ mã vật phẩm chính xác, `#minecraft:planks` và `$log` (loại gỗ đã chọn).
Đầu ra `$log_planks` chọn ván cùng loại gỗ. Các công thức phải khớp công thức thật của Minecraft;
bot vẫn chờ sản phẩm do game xác nhận, không tự tạo vật phẩm bằng JSON.
Khi thay công thức/yield, cập nhật ngân sách `budget` và `planksPerLog` tương ứng.

Hiện bộ thực thi hỗ trợ nhiệm vụ đào và chuỗi chuẩn bị bằng các bước trên, chưa hỗ trợ nung, ăn, chiến đấu
hay chuỗi lấy/cất rương từ JSON. Những thao tác mới vẫn cần thêm một bộ xử lý dùng chung; sau đó các nhiệm vụ
khác có thể tái dùng bằng dữ liệu. JSON không chạy Java, terminal hay lệnh tùy ý.

File sai cấu trúc, hành động lạ, tham chiếu thiếu hoặc số lượng sai sẽ bị từ chối trước khi bắt đầu nhiệm vụ;
file được giữ nguyên để sửa. Chuỗi thiếu điều kiện có thể dừng khi thiếu nguyên liệu/menu. Giới hạn thời gian,
200 lượt chế tạo và lệnh bot stop vẫn áp dụng, tránh quy tắc lặp vô hạn.
## Danh mục toàn bộ công thức vanilla

File minecraft-recipes-26.2.json nằm cạnh local-tasks.json, được đóng gói trong mod và chép ra thư mục config ở lần khởi động đầu. File có 1.585 công thức và 224 nhóm vật phẩm lấy nguyên bản từ minecraft-common.jar của Minecraft Java 26.2, gồm chế tạo, nung, lò cao, hun khói, nấu lửa trại, cắt đá và bàn rèn. recipes là bản đồ ID → dữ liệu công thức gốc; itemTags là bản đồ nhóm → dữ liệu nhóm gốc. summary thống kê từng loại; gameLogicRecipes đánh dấu 64 công thức có xử lý riêng của game (màu, thành phần, sao chép, sửa đồ...).

Danh mục này không thay recipes trong local-tasks.json. Bộ thực thi hiện vẫn dùng công thức lưới đã khai báo trong nhiệm vụ; chưa tự động nung/rèn hay xử lý hết công thức đặc biệt. Công thức mod/datapack server cần lấy từ dữ liệu server khi bổ sung sau. File người dùng đã có sẽ được giữ nguyên.


## Quy tắc lò nung và nhiên liệu

Chế tạo/đặt bàn và kiểm tra lò được nhóm trong `ProductionController`. File `furnace-rules-26.2.json`
nằm cạnh `local-tasks.json`, có bản trong mod và được chép ra config nếu chưa có. Gồm:

- `machines`: loại công thức, thời gian nung mặc định và tỷ lệ tiêu hao nhiên liệu.
- `recipes`: 107 công thức smelting/blasting/smoking gốc của Minecraft 26.2.
- `acceptedInputs`: từng loại lò → mã vật phẩm → ID công thức phù hợp.
- `fuels`: 280 vật phẩm cháy được, thời gian cháy ở lò thường và vật phẩm còn lại (ví dụ xô dung nham → xô rỗng).
- `fuelRules`: 65 bước thêm/loại nhiên liệu lấy từ FuelValues.vanillaBurnTimes, gồm loại bỏ nhóm gỗ không cháy.
- `slots`, `rejectionRules`: ô nguyên liệu 0, nhiên liệu 1, kết quả 2; không nạp vào ô kết quả.

Vật phẩm không có công thức phù hợp với loại lò bị từ chối làm nguyên liệu. Vật phẩm không phải nhiên liệu
bị từ chối để đốt. Không cần liệt kê từng vật phẩm bị cấm: kiểm tra thành viên của các tập hợp hợp lệ.
Một vật phẩm có thể vừa là nguyên liệu vừa là nhiên liệu, ví dụ oak_log. Xô rỗng không cháy dù menu vanilla
có thể cho nó nằm ở ô nhiên liệu. Công cụ/nhiên liệu khác chỉ được phân loại đúng theo game, chưa tự chọn đốt đồ.

Lệnh đọc, không thay đổi game:

```text
bot furnace check furnace minecraft:cobblestone
bot furnace check blast_furnace minecraft:cobblestone
bot furnace check furnace minecraft:oak_log
bot furnace check furnace minecraft:crimson_planks
bot furnace plan furnace minecraft:cobblestone 32 minecraft:coal
bot furnace plan blast_furnace minecraft:raw_iron 16 minecraft:coal
```

`check` báo có thể làm nguyên liệu/nhiên liệu hay không; `plan` cho sản phẩm vanilla và ước lượng nhiên liệu.
Khi ở trong game, đầu vào/nhiên liệu được đối chiếu recipeAccess/fuelValues hiện tại, ưu tiên dữ liệu runtime
so với JSON. Khi chưa vào thế giới, kết quả là tra dữ liệu vanilla và được ghi rõ. Kế hoạch sản phẩm/thời gian
vẫn là vanilla; datapack có thể thay đổi công thức nên không coi ước lượng là xác nhận sản phẩm thật.

Ví dụ 32 cobblestone → 32 stone cần khoảng 4 coal nếu nung liên tục từ lò nguội. Lò cao/smoker nhanh gấp đôi
nhưng cũng đốt nhiên liệu nhanh gấp đôi; không được tính thành 16 sản phẩm/coal. Cần tính riêng cookingtime
của công thức khi nó ghi thời gian khác mặc định. Không tính lửa còn lại, dừng giữa chừng hoặc ô kết quả đầy.

Bước này triển khai phân loại, điều kiện nạp và kế hoạch nung. Chưa tự mở lò, nạp nguyên liệu/nhiên liệu,
chờ nung hay lấy sản phẩm; chưa có action nung trong JSON nhiệm vụ/AI.
