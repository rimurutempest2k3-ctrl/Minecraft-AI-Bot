# Hợp đồng kết nối AI — bản v2

Prompt: SYSTEM_PROMPT.vi.md. Đã nối với Gemini và Groq, chuyển AI dự phòng và bộ nhớ chung local.
Đã có kiểm tra cấu trúc và vòng thực thi. Công tắc `bot ai auto on/off/status` kiểm soát việc thực thi: ON cho phép ask/run thực hiện, OFF chỉ cho ask đề xuất.
Vòng chạy trên client thread, API nền, một hành động tại một thời điểm, nhận kết quả
và quan sát mới trước lượt tiếp. Hủy dùng generation để loại phản hồi muộn; chết,
đổi level/player hoặc rời RUNNING hủy nhiệm vụ. Giới hạn cố định 20 lượt/10 phút,
3 phút mỗi hành động và 5 giây wait. Chưa có bộ kiểm chứng mọi mục tiêu ngôn ngữ
tự nhiên: done vẫn là kết luận AI, hiển thị rõ để đối chiếu game.

## Ánh xạ đề xuất

| action | args | Lệnh bot hiện có |
| --- | --- | --- |
| info | {} | bot info |
| inventory | {} | bot inventory |
| status | {} | bot status |
| start | {} | bot start |
| goto | x, y, z: số nguyên | bot goto X Y Z |
| mine | block: mã block; quantity: số nguyên 1–2304 | bot mine BLOCK QUANTITY |
| chest_open | id: CHEST-UUID | bot chest open ID |
| chest_take/chest_put | item: mã vật phẩm; quantity: 1–2304 | bot chest take/put ITEM QUANTITY |
| chest_memory/chest_list/chest_close | {} | bot chest memory/list/close |
| pause | {} | bot pause |
| resume | {} | bot resume |
| stop | {} | bot stop |
| wait | {} | Không gửi lệnh; chờ bộ theo dõi tác vụ |
| ask/message/done | {} | Hiển thị message; không gửi lệnh game |

Trong run, start/pause/resume/stop từ AI kết thúc vòng để người dùng xử lý;
không tự thay đổi trạng thái BotCore. Trong ask, mọi action chỉ là đề xuất.

## Trách nhiệm của chương trình

- Cấu hình structured output/schema của API; kiểm tra JSON lại tại máy local.
- Từ chối action/args lạ, sai kiểu, tọa độ ngoài giới hạn, quantity ngoài phạm vi.
- Ghép lệnh từ tham số đã kiểm tra; không thực thi chuỗi lệnh tự do do AI tạo.
- Không dùng message làm lệnh. Không gửi JSON xuống console trực tiếp.
- Gắn task_id/request_id tại ứng dụng và ngăn thực thi trùng; AI không tự quyết định id.
- Chỉ một tác vụ thay đổi thế giới được thực hiện tại một thời điểm.
- API chạy ở luồng nền; đọc game và gọi Baritone trên luồng client hiện có.
- Người dùng vẫn dùng được stop/pause nếu API lỗi, chậm hoặc hết hạn mức.
- wait chờ 5 giây; hành động game đang bận không gọi thêm API. Giới hạn lượt/thời
  gian hiện cố định trong mã; retry API tối đa hai lần cho lỗi tạm thời mỗi provider.
- Chưa có bộ phân tích tiêu chí tổng quát để kiểm chứng done độc lập với AI.
- Lưu hội thoại ngắn, mục tiêu, log tác vụ và sự kiện đã xác minh ở local.
- Quan sát nên chuyển thành JSON có timestamp, world/session id, inventory, player,
  trạng thái BotCore và trạng thái tác vụ riêng. RUNNING không phải task_status.
- Quan sát hiện gửi dạng văn bản; JSON timestamp và bộ kiểm chứng mục tiêu còn cần bổ sung.

## Nếu dùng function calling

Giữ nội dung vai trò và quy tắc trong prompt, khai báo các action dưới dạng công cụ
có schema tương ứng. Thay phần đầu ra JSON bằng quy tắc gọi đúng một công cụ mỗi lượt.
Không bật đồng thời hai giao thức đầu ra. Mã local vẫn xác thực mọi lời gọi công cụ.
Payload/lịch sử riêng của nhà cung cấp được giữ riêng với bộ nhớ mục tiêu chung;
không chuyển nguyên chữ ký thought/tool-call giữa các nhà cung cấp.
