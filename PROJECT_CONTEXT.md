# Ghi chú dự án Minecraft AI Bot

Tài liệu này để lưu lại những gì đã bàn và những quyết định đã chọn. Sau này nếu đổi máy, đổi IDE hoặc phải nhờ người khác sửa lỗi thì chỉ cần đọc file này trước.

## Mình đang làm gì?

Mục tiêu cuối cùng là một bot Minecraft Java có thể tự sinh tồn: tìm tài nguyên, chế tạo, cất đồ, xây chỗ ở và tự xử lý các tình huống thường gặp. Bot có thể dùng AI để lên kế hoạch, nhưng không nên hỏi AI mỗi khi cần đi vài block hoặc mở một cái rương.

Trước mắt **chưa làm bot tự chơi hoàn toàn**. Bản đầu sẽ nhận nhiệm vụ từ người dùng, thực hiện rồi báo kết quả. Khi phần này chạy ổn mới mở rộng sang tự đặt mục tiêu.

Dự kiến viết dưới dạng mod phía client bằng Fabric, phát triển trên Windows. Có thể dùng Baritone để tìm đường. Meteor chỉ là lựa chọn bổ sung, chưa quyết định tích hợp. Phiên bản Minecraft, Java và các thư viện sẽ chốt sau khi kiểm tra tương thích.

## Những điều đã thống nhất

- Một thời điểm chỉ chạy một nhiệm vụ chính. Các nhiệm vụ khác nằm trong hàng đợi.
- Nhiệm vụ có thể tạm dừng, tiếp tục hoặc hủy. Bot cần biết mình đang làm đến đâu.
- Có hai cách giao việc: lệnh cố định để dễ thử lỗi, và ô nhập ngôn ngữ tự nhiên trên dashboard.
- AI chỉ lên kế hoạch hoặc đánh giá tiến độ. Việc di chuyển, đào, đặt block và kiểm tra kết quả do code xử lý.
- FAST THINK là lần kiểm tra nhanh khi nhiệm vụ kéo dài (đang tính khoảng 10 phút/lần, chưa chốt).
- DEEP THINK dùng khi hoàn thành nhiệm vụ hoặc gặp lỗi khó. Ở bản đầu, AI chỉ đề xuất việc tiếp theo chứ không tự thêm nhiệm vụ.
- Những phản ứng khẩn cấp như tránh dung nham, nguy cơ rơi hoặc quái tấn công phải chạy tại máy, không đợi API.
- Phải có log đủ rõ để biết bot lỗi ở bước nào. Không ghi API key, token đăng nhập hoặc thông tin nhạy cảm vào log.

## Phần muốn làm sau khi bot biết sinh tồn cơ bản

Bot sẽ có một hệ thống xây nhà từ bản thiết kế có sẵn. Mẫu đầu tiên dự kiến là nhà 7×7, có giường, rương, bàn chế tạo, lò nung và ánh sáng. Bot cần tự tính vật liệu, tìm chỗ xây, đặt block đúng cách và lưu tiến độ.

Khi xây xong, bot nhớ vị trí các tiện nghi để quay về sử dụng. Trí nhớ về rương và thế giới chỉ là thông tin quan sát được lần cuối; mỗi lần quay lại phải kiểm tra vì server có thể đã thay đổi.

## Việc cần chứng minh đầu tiên

Cho bot nhận yêu cầu lấy 16 khúc gỗ sồi. Bot kiểm tra túi đồ, tìm cây, di chuyển, chặt, nhặt và xác nhận đủ số lượng. Nếu không tìm được cây hoặc bị kẹt thì dừng có lý do, không chạy mãi.

Làm được vòng này mới nên bắt đầu nối AI vào hệ thống nhiệm vụ.

## Chưa quyết định

- Phiên bản Minecraft/Fabric/Java và bản Baritone tương thích.
- Dùng JSON hay SQLite để lưu trạng thái.
- Backend dashboard dùng công nghệ gì và truy cập từ điện thoại như thế nào cho an toàn.
- Cách đọc blueprint .schem, thứ tự xây và xử lý địa hình.
- Mức chi phí API cho mỗi nhiệm vụ.

Repository hiện dùng để lưu thiết kế. Chưa có code bot hoạt động.
