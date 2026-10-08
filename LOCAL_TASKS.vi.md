# Chuỗi điều kiện local: chuẩn bị công cụ trước khi thu đá cuội

Không cần API key hay AI. Trong IntelliJ, chạy lại Minecraft Client rồi mở console:

```text
bot start
bot task cobblestone 16
bot info
bot inventory
```

`bot task cobblestone` mặc định 32. `bot mine cobblestone 16` hoặc
`bot mine minecraft:cobblestone 16` cũng dùng quy trình này. Số lượng là **thu thêm**;
đá cuội cũ trong túi không tính. Bot đào block minecraft:stone bằng cúp không có Silk Touch.
Chỉ minecraft:cobblestone được tính vào tiến độ; minecraft:stone không được tính.
Cúp có Silk Touch được bỏ qua; nếu chỉ có loại đó, bot chuẩn bị cúp gỗ. Baritone không tự đổi công cụ trong lượt thu đá cuội.
bot task stone sẽ hướng dẫn dùng cobblestone. bot mine minecraft:stone vẫn là lệnh đào block thô, không bảo đảm thu vật phẩm stone.

## Danh sách điều kiện

| Mục tiêu/bước | Kiểm tra trước | Nếu thiếu |
| --- | --- | --- |
| Thu đá cuội | Có cúp không Silk Touch, còn hơn 2 độ bền | Chuẩn bị cúp gỗ |
| Cúp gỗ | 3 ván + 2 que + bàn chế tạo mở | Chuẩn bị nguyên liệu/bàn |
| Que | Đã có ít nhất 2 que | 2 ván xếp dọc tạo 4 que |
| Bàn chế tạo | Đang mở, đã có trong túi hoặc có bàn gần | 4 ván tạo 1 bàn |
| Ván | Đủ cho các bước còn thiếu | 1 khúc gỗ tạo 4 ván |
| Gỗ | Gỗ phù hợp đang có trong túi | Baritone lấy lượng còn thiếu |

Ví dụ túi trống, không có bàn gần: lấy 3 khúc gỗ → 12 ván → bàn chế tạo
→ 4 que → đặt/mở bàn → cúp gỗ → thu đá cuội. Dư 3 ván và 2 que, không vứt đi.
Nếu bàn gần hoặc bàn trong túi đã có, chỉ cần 2 khúc gỗ khi chưa có que/ván.
Nếu cúp đã có, đi đào luôn, không làm thêm bàn hay cúp. Cúp sắp hỏng được bỏ qua;
nếu hết cúp giữa lúc đào, bot dừng và chuẩn bị cúp mới cho phần đá cuội còn thiếu.

## Bàn và nguyên liệu

Có thể đặt riêng một bàn từ túi đồ bằng console:

```text
bot start
bot place crafting_table
bot place crafting_table 10 64 -20
```

Lệnh đầu ưu tiên ô không khí gần nhất có mặt đất đặc, nhìn và chạm tới được.
Nếu không có ô phù hợp gần, bot thử nhảy rồi đặt bàn vào ô dưới chân mình.
Chỉ thử khi đứng yên trên nền đặc, ô chân trống, đủ khoảng trống để nhảy và có bàn
trong túi. Đợi chân cao hơn đỉnh ô mục tiêu mới đặt, không đặt xuyên nhân vật.
Không nhảy/đặt được dưới chân thì thả phím nhảy, đợi đứng vững rồi tìm chỗ khác
cách 1–2 block theo phương ngang và dùng Baritone đi tới. Ưu tiên chỗ gần nhất,
cùng độ cao, nền đặc, đủ chỗ đứng và hành lang không có va chạm; tránh nền magma.
Tại chỗ mới, tìm ô đặt gần hoặc thử nhảy đặt dưới chân lại. Tối đa 2 lần đổi chỗ,
không quay lại chỗ đứng đã thử. Mỗi bước đi chờ tối đa 15 giây, toàn bước đặt bàn
tối đa 60 giây. Hết lượt hoặc không có chỗ phù hợp thì dừng và báo lý do.
Không tự đào block cản chỉ để tìm chỗ đứng. Stop/pause, mở menu, chết hoặc đổi
thế giới hủy cả bước đi và thả phím nhảy. Sau khi đặt, nhân vật có thể đứng trên bàn.
Nếu bàn từ lần đặt trước hiện ra muộn, ưu tiên dùng bàn đó, không tiếp tục đặt bàn
ở chỗ mới. Quy trình local lấy lại tọa độ bàn cuối cùng sau khi đổi chỗ.

Lệnh có tọa độ đặt bàn vào chính
ô x/y/z đó, không phải tọa độ block đất bên dưới; ô phải nhìn/chạm tới theo tầm
Minecraft. Tọa độ chỉ định không tự đổi sang chỗ khác và không tự đi tới tọa độ xa. Phải có bàn trong túi, không tự chế bằng lệnh
place. Nếu đang đi/đào/chế tạo, dùng stop rồi start trước. Tọa độ đã có bàn thì
báo có sẵn, không dùng thêm bàn. Dùng bot info xem kết quả đặt; stop/pause hủy chờ,
không phá bàn đã đặt. Chỉ hỗ trợ crafting_table, chưa đặt mọi loại block.

Quy trình cobblestone gọi cùng bước đặt bàn (kể cả nhảy đặt dưới chân) tự động; không cần nhập place giữa nhiệm vụ.
Đợi block bàn hiện ổn định 20 tick trên client rồi mới mở/chế, mỗi lần đặt chờ tối đa 5 giây.

Tìm bàn trong chunk đã tải, cách vị trí hiện tại tối đa 12 block mỗi trục ngang
và 4 block theo chiều cao. Có bàn thì dùng Baritone đi tới, không chế thêm.
Bàn ở ngoài phạm vi này chưa được coi là bàn gần; chưa có bộ nhớ bàn chế tạo lâu dài.
Nếu bàn trong túi và không thấy bàn gần, đặt ở ô không khí trên mặt block đặc trong
tầm chạm, tránh vị trí nhân vật và block có block entity (rương/lò...). Không tự thu
hồi bàn sau nhiệm vụ. Server từ chối đặt/mở thì bot dừng và báo lỗi.

Hỗ trợ gỗ oak, birch, spruce, jungle, acacia, dark_oak, mangrove, cherry, pale_oak,
gồm log/wood và dạng stripped có trong túi. Chọn cây thuộc các loại này nhìn thấy
gần trong chunk đã tải; nếu chưa thấy loại cây nào thì giao Baritone tìm oak_log.
Chưa có chuỗi riêng cho tre, thân nấm hoặc công thức từ mod/datapack.
Ván thuộc tag planks được dùng chung trong công thức bàn/que/cúp.

Chế tạo dùng lưới 2x2 trong túi hoặc 3x3 của bàn, đặt chính xác từng nguyên liệu,
kiểm tra sản phẩm server trả về rồi lấy một lượt. Không dùng /give, sửa thẳng túi,
shift-click chế hàng loạt hay gọi API. Sau mỗi lượt kiểm tra lại túi đồ.
Menu bàn chế tạo vanilla sẽ hiện trong game. Đóng trước khi di chuyển/đào.
Mở bàn dùng tay chính, ưu tiên ô thanh nhanh trống; không dùng tay phụ dù tay phụ trống.
Nếu thanh nhanh đầy, bàn vanilla vẫn mở qua tay chính cầm đồ khi không cúi người.
Các bước local, kết quả mở bàn và lỗi được ghi thêm vào logs/latest.log để chẩn đoán.

## Dừng và kiểm tra

```text
bot stop
```

`bot pause` cũng hủy quy trình local; resume không khôi phục kế hoạch này.
Giao lại nhiệm vụ với số lượng còn thiếu sau khi kiểm tra inventory.
Không thao tác chuột/túi khi đang chế tạo. Thay menu, thiếu nguyên liệu/chỗ nhận,
sản phẩm sai, dữ liệu server thay đổi, chết hoặc đổi thế giới sẽ dừng. Nếu hủy giữa
công thức, bot cố cất đồ ở con trỏ vào túi; nguyên liệu còn trong lưới được giữ cho
người dùng cất, không tự click ra ngoài thả đồ. Cúp/bàn trong túi có thể được đổi
với vật phẩm ở ô đang chọn trên thanh nhanh để dùng chúng.

Giới hạn 3 phút mỗi bước, 15 phút toàn nhiệm vụ và 200 lượt chế tạo.
Quy trình khác như coal/iron hiện vẫn cần người dùng chuẩn bị công cụ phù hợp;
chưa tự chế cúp đá/cúp sắt, lò nung, hay luyện quặng.

Đã kiểm thử thêm cổng nhảy đặt: đủ độ cao, một lần đặt, đổi cột/block, nhảy thất bại
và giới hạn thời gian. Chưa xác nhận nhảy đặt dưới chân trong game thực tế.
Đã kiểm thử chọn chỗ đổi: khoảng cách 1–2 block, gần trước, lọc chỗ không phù hợp,
không quay lại và giới hạn hai lần. Cần thử cả đi tới chỗ mới và đặt lại trong game.
Đã build và kiểm thử giả lập đồ thị điều kiện, bỏ qua cúp/bàn đã có, lượng gỗ tối thiểu,
click công thức, dùng ván khác loại, lượng nguyên liệu tiêu hao và từ chối khi thiếu
nguyên liệu/chỗ nhận. Chưa kiểm tra chuỗi hoàn chỉnh trên game/server thực tế.

## Sắp xếp mã nguồn

Phần đặt bàn nằm trong ProductionController (lớp con TablePlacement); phần quan sát và lưu rương nằm trong ChestController (lớp con MemoryObserver). LocalTaskRunner giữ riêng vì điều phối toàn bộ nhiệm vụ thu đá cuội. Các lệnh console giữ nguyên.


## Bộ thực thi JSON

Chuỗi điều kiện và công thức trong hướng dẫn này nay nằm trong local-tasks.json; LocalTaskRunner thực thi các hành động chung. Không còn bộ StonePreparation hardcode. Xem TASKS.vi.md để sửa/thêm nhiệm vụ, công thức và điều kiện. Lệnh bot task spruce 16 là ví dụ nhiệm vụ mới được thêm hoàn toàn bằng JSON.
