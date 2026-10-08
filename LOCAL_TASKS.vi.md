# Chuỗi điều kiện local: chuẩn bị công cụ trước khi đào đá

Không cần API key hay AI. Trong IntelliJ, chạy lại Minecraft Client rồi mở console:

```text
bot start
bot task stone 16
bot info
bot inventory
```

`bot task stone` mặc định 32. `bot mine stone 16` hoặc
`bot mine minecraft:stone 16` cũng dùng quy trình này. Số lượng là **thu thêm**;
đá cũ trong túi không tính. Đá thường rơi đá cuội khi đào bằng cúp thường.
Cúp Silk Touch có thể cho stone; tiến độ local tính tổng cobblestone + stone.

## Danh sách điều kiện

| Mục tiêu/bước | Kiểm tra trước | Nếu thiếu |
| --- | --- | --- |
| Đào đá | Có cúp thuộc tag pickaxes còn hơn 2 độ bền | Chuẩn bị cúp gỗ |
| Cúp gỗ | 3 ván + 2 que + bàn chế tạo mở | Chuẩn bị nguyên liệu/bàn |
| Que | Đã có ít nhất 2 que | 2 ván xếp dọc tạo 4 que |
| Bàn chế tạo | Đang mở, đã có trong túi hoặc có bàn gần | 4 ván tạo 1 bàn |
| Ván | Đủ cho các bước còn thiếu | 1 khúc gỗ tạo 4 ván |
| Gỗ | Gỗ phù hợp đang có trong túi | Baritone lấy lượng còn thiếu |

Ví dụ túi trống, không có bàn gần: lấy 3 khúc gỗ → 12 ván → bàn chế tạo
→ 4 que → đặt/mở bàn → cúp gỗ → đào đá. Dư 3 ván và 2 que, không vứt đi.
Nếu bàn gần hoặc bàn trong túi đã có, chỉ cần 2 khúc gỗ khi chưa có que/ván.
Nếu cúp đã có, đi đào luôn, không làm thêm bàn hay cúp. Cúp sắp hỏng được bỏ qua;
nếu hết cúp giữa lúc đào, bot dừng và chuẩn bị cúp mới cho phần đá còn thiếu.

## Bàn và nguyên liệu

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

Đã build và kiểm thử giả lập đồ thị điều kiện, bỏ qua cúp/bàn đã có, lượng gỗ tối thiểu,
click công thức, dùng ván khác loại, lượng nguyên liệu tiêu hao và từ chối khi thiếu
nguyên liệu/chỗ nhận. Chưa kiểm tra chuỗi hoàn chỉnh trên game/server thực tế.
