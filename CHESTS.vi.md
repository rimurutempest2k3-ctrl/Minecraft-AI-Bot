# Lấy vật phẩm từ rương

## Bộ nhớ rương tự động

Mỗi lần mở rương bằng lệnh hoặc tương tác block bằng tay, mod liên kết menu với tọa độ
đã tương tác và tự lưu riêng từng server trong `config/minecraft-ai-bot/chest-memory/`.
Trong IntelliJ: `run/config/minecraft-ai-bot/chest-memory/`. File JSON dễ đọc, UTF-8,
ghi trên luồng riêng bằng file tạm và thay thế nguyên tử khi hệ thống hỗ trợ.
File bắt đầu xuất hiện sau lần quan sát rương thành công đầu tiên.

Tên file gồm địa chỉ/IP và port, rồi mã SHA-256 phân biệt, ví dụ
`play.example.net_25565--<mã phân biệt>.json`. Ký tự không dùng được trong tên file
Windows được đổi thành `_`. Dùng địa chỉ kết nối thay vì tên hiển thị vì tên hiển thị
có thể trùng hoặc bị đổi. Mã phân biệt tránh trùng sau khi đổi ký tự và phân biệt các
save chơi đơn cùng tên ở đường dẫn khác nhau. Save chơi đơn dùng `local-<tên save>--<mã>.json`.
Đổi server tự chọn đúng file; mọi dimension của một server nằm trong cùng file đó.

Nếu có file cũ `chest-memory.json`, lần đầu truy cập mỗi server sẽ sao chép riêng
các bản ghi thuộc server đó sang file mới, giữ ID, alias rương đôi và thời điểm quan sát.
File cũ được giữ nguyên làm bản lưu. File server đã tồn tại không nhập lại dữ liệu cũ,
tránh ghi đè nội dung mới. File hỏng được giữ nguyên và báo lỗi, không ghi đè.

Mỗi bản ghi có ID `CHEST-<UUID>`, world, dimension, positions, type, slotCount, firstSeen,
lastSeen, items (tổng theo mã vật phẩm), slots (từng ô) và stackData (dữ liệu vật phẩm,
kể cả thuộc tính khi bộ mã hóa Minecraft trả được). Ô trống vẫn có chỉ số slot.

Rương đơn có một tọa độ/27 ô. Rương đôi có hai tọa độ đã sắp xếp/54 ô và **một ID chung**,
không phụ thuộc bạn mở nửa nào. Chỉ gộp hai block thực sự nối nhau theo TYPE/hướng
ChestBlock; không coi hai rương đơn đứng gần nhau là rương đôi. Số ô menu phải khớp
số nửa rương mới lưu. Nội dung là toàn bộ menu đôi, không gán tùy ý từng ô cho từng nửa.

Mở lại cùng vị trí ở cùng thế giới/chiều không gian giữ ID và cập nhật nội dung. ID mới
được kiểm tra với toàn bộ ID đã lưu để không trùng; giữ qua khởi động lại. Khi hai rương
đơn đã nhớ được ghép, giữ một ID chính, bản ghi kia có mergedInto trỏ tới ID chính.
ID cũ không tái sử dụng. Khi tách đôi, một phần giữ ID chính, phần còn lại nhận ID mới
khi được quan sát. ID là danh tính **vị trí**, không phải UUID vật thể do Minecraft cấp:
phá và đặt lại ở cùng vị trí có thể tiếp tục bản ghi vị trí đó.

World của chơi đơn là đường dẫn thư mục save. Multiplayer dùng địa chỉ server gồm port;
dimension phân biệt overworld/nether/end. Đổi port LAN hoặc địa chỉ server tạo vùng nhớ
riêng. Client không biết server reset world ở cùng địa chỉ; khi server reset, cần giữ riêng
file JSON của server đó trước khi dùng lại. Không tự xóa các rương đã nhớ chỉ vì chunk chưa tải.

```text
bot chest memory
bot chest show <ID đầy đủ>
bot start
bot chest open <ID đầy đủ>
```

Thay phần `<ID đầy đủ>` bằng ID từ console, không giữ dấu ngoặc. Lệnh memory/show chỉ
đọc file đã nạp; open ID tìm bản ghi ở world/dimension hiện tại và dùng Baritone đi tới.
ID tham chiếu từ rương đã ghép cũng được nhận. Nội dung đã nhớ không thay thế việc mở
rương và kiểm tra lại trước take/put; rương có thể bị người khác thay đổi.

Mod đợi nội dung menu ổn định ngắn rồi lưu, cập nhật khi thay đổi và sau lấy/cất đồ.
Khi đóng, cố lưu ảnh nội dung cuối nếu con trỏ trống và không đang chuyển tự động.
contentsStatus=last_observed: chỉ là **nội dung quan sát lần cuối**, không phải thông tin
thời gian thực của rương đang đóng. Quan sát menu không có tương tác block xác định
được tọa độ sẽ báo lỗi thay vì đoán rương gần nhất. File hỏng hoặc ID trùng khiến bộ nhớ
không khởi tạo; file được giữ nguyên và ghi lỗi để tránh mất dữ liệu.

Đã kiểm tra lưu/đọc lại sau khởi động, ID không trùng giữa vị trí/world/dimension, gộp
hai nửa theo cả hai thứ tự, ghép/tách bản ghi và giữ file hỏng nguyên trạng. Liên kết
menu thực tế/mở bằng tay/rương đôi cần kiểm tra trong game.

## Thao tác lấy/cất

Chạy lại runClient và console sau khi cập nhật mod. Vào thế giới, đứng gần rương rồi dùng:

```text
bot start
bot chest open
bot chest list
bot chest take minecraft:oak_log 16
bot chest put minecraft:oak_log 8
bot chest status
bot inventory
bot chest close
```

`open` chọn rương thường/rương bẫy gần nhất trong bán kính 64 block, từ các chunk đã tải.
Nếu rương chưa trong tầm tương tác, Baritone đi tới cạnh rương (GoalGetToBlock), rồi mod
tự mở khi có thể nhìn và chạm tới rương theo tầm tương tác của Minecraft. Rương đôi
được hỗ trợ qua menu 54 ô. Có thể chỉ định: `bot chest open 10 64 -20`; Baritone đi tới
tọa độ đó kể cả khi chunk mục tiêu chưa tải, sau đó kiểm tra lại block có phải rương.
Không tìm rương chưa tải nếu không biết tọa độ; không đọc xuyên thế giới hay tăng tầm mở.
Có thể tự mở rương bằng chuột rồi dùng list/take.

Chờ console báo "Đã mở rương" rồi dùng list/take/put. Quá 120 giây chưa tới/mở được
hoặc rương mục tiêu biến mất thì dừng; tới cạnh nhưng bị che khuất cũng báo lỗi.
bot stop/bot pause hủy cả bước đi tới rương; resume không tự khôi phục nhiệm vụ này.
Đóng các menu trước khi đi. Có menu khác mở trên đường thì dừng để người chơi xử lý.
Mod mở rương bằng tay chính, ưu tiên chọn ô thanh nhanh trống nếu có. Nếu thanh nhanh
đầy, rương vanilla vẫn mở được bằng tay chính đang cầm đồ. Không dùng tay phụ để mở
menu vì Minecraft 26.2 chỉ gọi bước useWithoutItem từ tay chính. Hãy thả phím cúi người.

Minecraft vẫn hiện màn hình rương tiêu chuẩn; thao tác điều khiển bằng console bên ngoài.
Tắt tự tạm dừng khi chuyển cửa sổ bằng F3+P. Không thao tác chuột/túi đồ khi mod đang chuyển.

`take` lấy **thêm đúng số lượng** yêu cầu vào 36 ô túi đồ/thanh nhanh, giới hạn 1–2304.
`put` cất **đúng số lượng** từ túi đồ/thanh nhanh vào rương đang mở. Ví dụ trên cất 8 gỗ
sồi. Không lấy từ ô giáp/tay phụ. Thiếu đồ trong túi hoặc rương thiếu chỗ thì không bắt đầu.
Chương trình kiểm tra đồ trong rương và sức chứa trước khi bấm. Thiếu đồ hoặc thiếu chỗ
thì không bắt đầu chuyển. Vật phẩm cùng mã nhưng khác thuộc tính được giữ riêng, không
ghép bừa stack. Không dùng shift-click toàn bộ stack để lấy vượt số lượng.

Mod dùng các thao tác PICKUP của Minecraft, không sửa thẳng túi đồ. Một thao tác mỗi
4 tick; sau thao tác cuối chờ thêm 20 tick và đối chiếu số lượng trong túi đồ trên client.
Đây chưa phải bảo đảm xác nhận server trên mọi máy chủ/plugin; hãy test trước ở thế giới
chơi đơn. Nếu server sửa trạng thái hoặc người chơi làm đổi menu/nội dung, mod dừng và báo lỗi.
Vật phẩm đã chuyển trước khi dừng vẫn ở túi đồ; không tự hoàn tác mọi chuyển đổi.

Mở/lấy đồ yêu cầu BotCore RUNNING và dừng tác vụ Baritone đang chạy để không đồng thời
đi/đào và thao tác rương. Khi thao tác rương đang chạy, không nhận tác vụ Baritone mới.
bot stop hoặc bot pause hủy việc chuyển; mod cố trả vật phẩm ở con trỏ về ô nguồn gốc
nếu ô đó còn nhận đủ, không click ra ngoài để thả đồ. Nếu không trả được, console nhắc
bạn tự cất vật phẩm trên con trỏ. Không tự tiếp tục thao tác rương sau bot resume.
Đổi nhân vật/thế giới, chết hoặc đóng menu sẽ dừng kế hoạch chuyển.

close từ chối khi đang chuyển hoặc còn vật phẩm trên con trỏ. Các menu ChestMenu tiêu
chuẩn 27/54 ô được đọc/chuyển; chưa hỗ trợ lò nung, hopper, shulker box hay rương xe mỏ
với menu khác. Không bypass khóa rương hoặc quyền máy chủ.

AI được cung cấp rương đang mở và danh sách rương đã nhớ của server/chiều hiện tại.
`bot ai ask` chỉ đề xuất; `bot ai run <nhiệm vụ>` có thể mở rương bằng ID, đọc,
lấy/cất và đóng rương qua vòng thực thi từng bước. Xem `ai/AI_SETUP.vi.md`.

Đã kiểm tra giả lập: lấy lẻ 1–128 món qua nhiều stack, ghép stack còn chỗ, tách thuộc tính,
vật phẩm không stack, trả phần dư từ con trỏ, bảo toàn tổng số lượng, từ chối thiếu đồ/chỗ,
kiểm tra tham số và trạng thái pause. Chưa xác nhận bằng thao tác trong game thực tế.
Đã biên dịch tích hợp GoalGetToBlock của Baritone 1.19.0; bước đi tới và mở rương xa
chưa được kiểm tra trong game thực tế. Lấy/cất đồ gần đã được người dùng kiểm tra thành công.
Đã kiểm tra thêm chiều cất đồ với số lượng 1–128, rương đầy, thiếu đồ trong túi,
vật phẩm không stack và các stack khác thuộc tính.
