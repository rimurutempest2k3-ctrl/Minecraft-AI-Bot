# Cấu trúc hệ thống (bản phác thảo)

Đây là cách dự định chia code để dễ làm từng phần. Chưa phải cấu trúc package Java cuối cùng.

## Luồng xử lý

```text
Người dùng
  |-- Lệnh cố định
  |-- Dashboard: nhập nhiệm vụ bằng tiếng Việt
  |
  v
Kiểm tra yêu cầu / AI phân tích nếu cần
  |
  v
Hàng đợi nhiệm vụ
  |
  v
Task Manager
  |-- Đọc trạng thái Minecraft
  |-- Gọi Skill phù hợp
  |-- Kiểm tra kết quả
  |-- Thử lại hoặc báo lỗi
  |
  v
Minecraft client (Fabric / Baritone)
```

AI Gateway, trí nhớ và log sẽ nối vào luồng này, nhưng không cần xây tất cả cùng lúc.

## 1. Đọc trạng thái game

Phần này lấy tọa độ, máu, thức ăn, inventory, dimension và các thông tin thế giới mà client thực sự nhìn thấy. Những dữ liệu này là căn cứ để kiểm tra nhiệm vụ, không dựa vào lời AI nói rằng “đã xong”.

Không gọi API trong game tick. Những việc liên quan mạng hoặc xử lý lâu phải chạy bất đồng bộ để tránh đứng game.

## 2. Task Manager

Giữ một nhiệm vụ đang chạy và một hàng đợi. Nó quyết định bước tiếp theo, chuyển trạng thái, lưu tiến độ và gửi lệnh xuống Skill.

Task Manager không tự đi tìm đường hay bấm vào ô inventory. Nếu cần di chuyển thì gọi Skill di chuyển; nếu cần lấy đồ thì gọi Skill tương tác rương.

## 3. Skill

Mỗi Skill cần có đầu vào rõ ràng, điều kiện để chạy, cách biết đã xong và cách hủy giữa chừng.

Các Skill ưu tiên:
- `MOVE_TO`: đi tới tọa độ, dùng Baritone nếu bản đang dùng tương thích.
- `LOOK_AT`: hướng nhìn vào mục tiêu.
- `BREAK_BLOCK`: phá block có thể tiếp cận.
- `PICKUP_ITEM`: nhặt vật phẩm rồi kiểm tra inventory.

Sau đó mới thêm `PLACE_BLOCK`, chế tạo, mở rương và chuyển đồ. Không nên coi một Skill là hoàn thành chỉ vì đã gửi lệnh: phải kiểm tra trạng thái Minecraft sau thao tác.

Tại một thời điểm chỉ có một Skill được điều khiển nhân vật theo cách có thể xung đột với Skill khác.

## 4. Trí nhớ

Ban đầu chỉ cần lưu nhiệm vụ, bước đang chạy và một số dữ liệu quan trọng. Về sau thêm vị trí nhà, rương, khu vực đã khám phá và lần cuối nhìn thấy chúng.

Sau khi bot chết hoặc kết nối lại, cần kiểm tra trạng thái mới rồi mới tiếp tục. Không được mặc định mọi block hoặc vật phẩm vẫn còn nguyên.

## 5. AI và dashboard

Dashboard sẽ có hai đường:
- **Manual Task:** nhập lệnh cố định, không cần AI.
- **AI Task Gateway:** nhập yêu cầu tự nhiên; backend gửi trạng thái liên quan lên API để tạo kế hoạch có cấu trúc.

Kế hoạch AI trả về phải được kiểm tra: đúng định dạng, đúng tham số, chỉ dùng Skill có thật và không vượt quyền. Người dùng có thể xem, sửa hoặc duyệt kế hoạch trước khi chạy. API key giữ ở backend, không đưa vào JavaScript của trình duyệt.

FAST THINK kiểm tra tiến độ theo chu kỳ. DEEP THINK đánh giá khi hoàn thành hoặc gặp lỗi khó. Bản đầu không cho AI tự quyết định nhiệm vụ tiếp theo mà không có người dùng.

## 6. Xây nhà

`BaseBuilder` đọc một blueprint, tính vật liệu, kiểm tra vị trí xây, thực hiện từng phần và đối chiếu các block đã đặt. Cần xử lý việc đứng đúng tầm với, đặt block theo hướng và tránh phá công trình của người khác.

Một file .schem chỉ là dữ liệu công trình, không có nghĩa bot tự xây được. Phần đặt block trong Survival vẫn phải tự làm.

## 7. Log và xử lý lỗi

Mỗi task có ID riêng. Log nên ghi thời gian, bước đang chạy, Skill, vị trí và lý do thất bại. Cần xuất được báo cáo lỗi gọn, không chứa bí mật đăng nhập.

Các lỗi cần phân biệt ngay từ đầu: không tìm thấy tài nguyên, không có đường đi, bị kẹt, ngoài tầm với, inventory đầy, timeout và mất kết nối.

## Thứ tự viết code

Đọc trạng thái game → Task Manager tối thiểu → Skill di chuyển → Skill đào/nhặt → kiểm tra hoàn thành → lưu trạng thái. Chỉ sau đó mới nối dashboard, AI và BaseBuilder.
