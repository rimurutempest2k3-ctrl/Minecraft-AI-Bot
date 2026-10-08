# Nhiệm vụ cơ bản có sẵn

Các nhiệm vụ này chạy trực tiếp bằng lệnh bot/Baritone hiện có, không gọi API AI.
Xem danh sách: `bot task list`.

| Lệnh | Nhiệm vụ | Số lượng mặc định |
| --- | --- | --- |
| bot task check | Xem trạng thái game và túi đồ | — |
| bot task inventory | Xem túi đồ | — |
| bot task wood [số lượng] | Thu gỗ sồi, tìm oak_log | 16 |
| bot task birch [số lượng] | Thu gỗ bạch dương, tìm birch_log | 16 |
| bot task stone [số lượng] | Local: chuẩn bị cúp rồi đào đá | 32 |
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
Giới hạn 1–2304 giống bot mine. Với stone/coal/iron, bộ đếm dùng vật phẩm rơi của block
qua bộ lọc Baritone hiện có; công cụ có Silk Touch có thể làm thay đổi loại vật phẩm.
Bot dừng đào khi đủ số lượng theo cơ chế đã kiểm tra trước đó.

Phải vào thế giới và nhập bot start trước khi chạy nhiệm vụ đào. Riêng stone đã có chuỗi
local: kiểm tra cúp → thu gỗ thiếu → chế ván/bàn/que/cúp gỗ → đào. Bàn gần hoặc bàn/cúp
đã có được dùng lại. Xem LOCAL_TASKS.vi.md. Stone tính đá cuội + stone thu thêm.
Coal/iron chưa tự chế công cụ; iron chỉ nhắm iron_ore,
coal chỉ nhắm coal_ore, chưa gộp biến thể deepslate. wood chỉ là gỗ sồi, không phải mọi loại gỗ.

Chạy nhiệm vụ đào mới sẽ thay thế nhiệm vụ Baritone đang chạy; không có hàng đợi nhiều
nhiệm vụ. Khi chuỗi stone đang chạy, dùng bot stop trước khi giao tác vụ khác. Pause/stop
hủy chuỗi stone; resume không khôi phục chuỗi này. Khi PAUSED, nhiệm vụ mẫu không tự
bỏ qua tạm dừng. bot task check/inventory chỉ đọc thông tin, không làm đổi tác vụ đang chạy.

Với yêu cầu cần AI lập kế hoạch, dùng bot ai run; bot ai ask vẫn chỉ đề xuất.
Chuỗi stone hoạt động khi không có API. Các nhiệm vụ mẫu không được
coi là đã hoàn thành chỉ vì AI báo done; kiểm tra tiến độ và kết quả bằng bot info/inventory.
