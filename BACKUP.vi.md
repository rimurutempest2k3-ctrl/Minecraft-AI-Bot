# Bản sao lưu dự án IntelliJ — 09/10/2026

Bản sao lưu mới nhất của dự án Fabric Minecraft 26.2 và bot-common từ IntelliJ.
Nhánh này giữ lịch sử bản sao lưu trước. Các tài liệu thiết kế cũ vẫn giữ;
cách chạy hiện tại theo LOCAL_TASKS.vi.md, TASKS.vi.md, CHESTS.vi.md và ai/AI_SETUP.vi.md.

Đã có console ngoài game, Baritone đi/đào theo số lượng, túi đồ, lấy/cất rương,
bộ nhớ rương riêng từng server, nhiệm vụ local đọc JSON, chế tạo và đặt bàn,
danh mục công thức Minecraft 26.2, dữ liệu nguyên liệu/nhiên liệu lò,
Groq/Gemini/OpenAI dự phòng và công tắc thực thi AI.

Logic lò hiện chỉ kiểm tra và lập dự tính; chưa tự thao tác toàn bộ quá trình nung.
Danh mục công thức không đồng nghĩa mọi công thức đã có bộ thực thi.

Không lưu API key, file kết nối console, lịch sử AI, dữ liệu rương, thế giới,
log, cấu hình IntelliJ hoặc build/cache. JAR Baritone 1.19.0 và Gradle Wrapper
được lưu để khôi phục phụ thuộc. Xem THIRD_PARTY.md về nguồn và giấy phép.

## Khôi phục

1. Tải/clone nhánh này vào thư mục mới, mở thư mục gốc bằng IntelliJ.
2. Dùng JDK 25; đợi Gradle sync, chạy build rồi runClient.
3. Mở Terminal ở thư mục gốc và chạy `.\bot-console.bat`.
4. Vào thế giới sinh tồn; dùng F3+P để tắt tự tạm dừng khi chuyển cửa sổ.

```text
bot start
bot task cobblestone 16
```

Lệnh local không cần API. Nếu dùng AI, gán lại key bằng bot get API groq,
bot get API gemini hoặc bot get API openai trên máy chạy game.

```text
bot ai auto on
bot ai ask Thu thêm 16 đá cuội
bot ai auto status
bot ai auto off
```

Mỗi lần mở game, công tắc AI mặc định OFF. OFF hủy nhiệm vụ AI đang chạy;
ask chỉ đề xuất. ON cho phép ask/run thực thi từng bước qua bộ kiểm tra lệnh.
Build và kiểm thử giả lập đã đạt trên JDK 25; cần tiếp tục kiểm thử trong game
và trên nhiều server. Không có yêu cầu API thực tế trong bước sao lưu này.
