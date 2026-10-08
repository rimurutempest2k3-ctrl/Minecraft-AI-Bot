# Bản sao lưu dự án IntelliJ — 08/10/2026

Bản này lưu mã nguồn Fabric Minecraft 26.2 và bot-common sau khi tích hợp chuỗi
local chuẩn bị cúp gỗ trước khi đào đá. Nhánh bắt đầu từ lịch sử dev hiện có.
Các tài liệu thiết kế cũ vẫn giữ; cách chạy hiện tại theo tài liệu bên dưới.

Đã có console ngoài game, Baritone đi/đào theo số lượng, túi đồ, lấy/cất rương,
bộ nhớ rương riêng từng server, Groq/Gemini dự phòng, vòng AI thực thi từng bước
và quy trình đào đá local với công thức bàn/que/cúp gỗ.

Không lưu API key, file kết nối console, lịch sử AI, dữ liệu rương, thế giới,
log, cấu hình IntelliJ hoặc build/cache. JAR Baritone 1.19.0 và Gradle Wrapper
được lưu để khôi phục phụ thuộc. Xem THIRD_PARTY.md về nguồn và giấy phép.

## Khôi phục

1. Tải/clone nhánh này vào thư mục mới, mở thư mục gốc bằng IntelliJ.
2. Dùng JDK 25; đợi Gradle sync, chạy build rồi runClient.
3. Mở Terminal ở thư mục gốc và chạy `.\bot-console.bat`.
4. Vào thế giới sinh tồn, dùng F3+P để tắt tự tạm dừng khi chuyển cửa sổ.

```text
bot start
bot task stone 16
```

Lệnh này không cần API. Nếu dùng AI, gán lại key bằng `bot get API groq` hoặc
`bot get API gemini` trên máy chạy game; không lưu key trong repo.

Hướng dẫn: LOCAL_TASKS.vi.md, TASKS.vi.md, CHESTS.vi.md và ai/AI_SETUP.vi.md.
Build và kiểm thử giả lập đã đạt trên JDK 25. Chuỗi chế tạo local đầy đủ chưa
được xác nhận trong game; kiểm tra console và inventory khi thử lần đầu.
