# Kế hoạch phát triển

Đây là thứ tự dự định làm, không phải lịch phát hành cố định. Hiện mới có repository và tài liệu, chưa có bản mod chạy được.

## Trước khi viết code

- [ ] Chọn chính xác phiên bản Minecraft Java.
- [ ] Kiểm tra bản Fabric, Java và Baritone phù hợp.
- [ ] Chuẩn bị IntelliJ IDEA, Gradle và môi trường chạy thử trên Windows.
- [ ] Tạo dự án Fabric tối thiểu, thiết lập .gitignore để tránh đẩy file build và dữ liệu riêng tư.

**Mốc đạt được:** mod khởi động trong client thử nghiệm và ghi được log.

## Bước 1 — Bot biết nhận việc

- [ ] Đọc vị trí, máu, thức ăn và inventory.
- [ ] Có Task Manager với queue, pause, resume, cancel.
- [ ] Gọi được Skill di chuyển.
- [ ] Thử phá block và nhặt vật phẩm trong Survival.
- [ ] Giao nhiệm vụ lấy 16 khúc gỗ và kiểm tra kết quả.
- [ ] Log đủ để tìm nguyên nhân nếu nhiệm vụ thất bại.

**Mốc đạt được:** bot làm xong một nhiệm vụ thu thập đơn giản, hoặc báo lỗi đúng thay vì bị treo.

## Bước 2 — Sinh tồn cơ bản

- [ ] Xử lý nguy hiểm trước mắt bằng logic tại máy.
- [ ] Chế tạo và quản lý inventory.
- [ ] Biết mở rương, lấy và cất vật phẩm.
- [ ] Lưu vị trí nhà, rương và tiến độ.
- [ ] Tạo hệ thống xây nhà theo blueprint.
- [ ] Xây thử nhà 7×7 có giường, rương, bàn chế tạo, lò nung và đuốc.

**Mốc đạt được:** bot tự chuẩn bị vật liệu và dựng được chỗ ở cơ bản trong Survival, không cần Creative.

## Bước 3 — Thêm AI

- [ ] Làm backend và Web Dashboard.
- [ ] Có ô nhập nhiệm vụ bằng ngôn ngữ tự nhiên.
- [ ] AI trả về kế hoạch; người dùng xem và duyệt trước khi chạy.
- [ ] Thêm FAST THINK, DEEP THINK và giới hạn chi phí API.
- [ ] Cho phép chỉnh sửa kế hoạch đang đề xuất.
- [ ] Xử lý trường hợp API lỗi hoặc hết ngân sách.

**Mốc đạt được:** người dùng giao một nhiệm vụ nhiều bước từ dashboard và bot thực hiện được bằng các Skill đã có.

## Bước 4 — Ổn định rồi mới mở rộng

- [ ] Kiểm thử việc mất kết nối, chết và khởi động lại.
- [ ] Thêm các mẫu nhà, cách quản lý kho và kỹ năng sinh tồn.
- [ ] Tự động build và chạy kiểm thử trên GitHub.
- [ ] Viết hướng dẫn cài đặt, giới hạn hiện tại và cách xuất log.
- [ ] Xem xét giấy phép trước khi công khai mã nguồn.

Chưa cần đặt mốc v1.0 hay ngày phát hành. Ưu tiên hiện tại vẫn là làm cho một nhiệm vụ nhỏ chạy ổn định.
