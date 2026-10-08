# Bot Common — Lõi bot và console

Module này cung cấp BotCore để quản lý trạng thái, CommandDispatcher để xử lý lệnh,
Logging để ghi nhật ký, cùng ConsoleServer và ExternalConsole để kết nối console
ngoài game với core trong Fabric client. Module không phụ thuộc trực tiếp vào Minecraft.

Bản tích hợp này dùng JDK 25. Xem [hướng dẫn chạy đầy đủ](../INTEGRATION.md).

## Console điều khiển Minecraft

Sau khi build và mở Minecraft bằng runClient, tại thư mục gốc dự án chạy:

```powershell
.\bot-console.bat
```

Thử `bot help`, `bot start`, `bot status`, `bot pause`, `bot resume`, `bot stop`.
Lệnh `exit` đóng console và giữ game chạy.

## Chạy thử core độc lập

Để thử trạng thái core mà không mở Minecraft, chạy từ thư mục gốc:

```powershell
.\gradlew.bat :bot-common:run --console=plain
```

Chế độ này tạo core riêng, không điều khiển core trong Minecraft.
Lệnh `exit` kết thúc chương trình và dừng core thử nghiệm.
Nhật ký nằm trong thư mục `logs/` của tiến trình, luân phiên ba tệp khoảng 1 MiB.

## Kiểm tra

BotCoreTest và ConsoleTransportTest dùng hàm main thay vì JUnit.
Tác vụ Gradle `check` chạy chúng qua `coreCheck` và `transportCheck`.
