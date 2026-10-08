# Hướng dẫn Minecraft AI Bot — Fabric 26.2 + Baritone

Phiên bản: Minecraft 26.2, Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2,
Baritone Fabric API 1.19.0. Baritone phụ trách tìm đường, di chuyển và đào block.
BotCore quản lý trạng thái; console gửi lệnh cho bộ điều phối trong client.
Phần tự điều khiển di chuyển bằng thời gian đã được thay bằng Baritone.

## Chạy trong IntelliJ

1. Nhập `exit` ở console cũ và thoát Minecraft bằng Quit Game.
2. Reload All Gradle Projects. Đặt Gradle JVM thành JDK 25.
3. Chạy tác vụ build, rồi runClient.
4. Vào thế giới chơi đơn và đóng menu/túi đồ.
5. Nhấn F3+P để tắt tự tạm dừng khi chuyển cửa sổ, nếu đang bật.
6. Trong tab Terminal riêng tại thư mục dự án, chạy:

```powershell
.\bot-console.bat
```

PowerShell cần tiền tố `.\`. Nếu JAVA_HOME đang trỏ tới Java cũ:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
.\bot-console.bat
```

## Lệnh

| Lệnh | Chức năng |
| --- | --- |
| bot help | Xem danh sách lệnh |
| bot start | Cho phép giao tác vụ |
| bot status | Xem trạng thái BotCore |
| bot info | Tọa độ, chiều không gian, máu, độ đói, vật phẩm và trạng thái Baritone |
| bot inventory | Xem mã và tổng số lượng từng loại vật phẩm, tổng vật phẩm và số ô trống |
| bot goto X Y Z | Giao Baritone đi tới tọa độ nguyên tuyệt đối |
| bot mine minecraft:oak_log 16 | Thu thêm 16 vật phẩm từ loại block chỉ định rồi tự dừng đào |
| bot pause | Tạm dừng tác vụ Baritone, giữ tác vụ để tiếp tục |
| bot resume | Tiếp tục tác vụ đã tạm dừng |
| bot stop | Hủy tác vụ Baritone và dừng BotCore |
| exit | Đóng console; không tự hủy tác vụ đang chạy |

`bot start` chỉ bật trạng thái điều phối, chưa tự giao tác vụ.
Lệnh goto/mine mới thay thế tác vụ cũ. Để hủy trước khi đóng console, dùng bot stop.
Lệnh mine bắt buộc có số lượng nguyên từ 1 đến 2304; không còn chế độ đào vô hạn.
Số lượng là vật phẩm thu thêm so với túi đồ lúc nhận lệnh, không phải số block phá.
Ví dụ đã có 5 gỗ, `bot mine minecraft:oak_log 16` đặt mục tiêu tổng 21 gỗ.
`bot info` hiển thị tiến độ thu thêm và kết quả hoàn thành hoặc dừng khi chưa đủ.
Vật phẩm rơi nhiều từ một block có thể làm số thu được vượt mục tiêu.
Nếu dùng/vứt vật phẩm đang được đếm, bot sẽ cần bù lại để đạt tổng mục tiêu.
Thử trong Survival và giữ chỗ trống trong túi đồ để có thể nhặt vật phẩm.
Rời thế giới, thay đổi người chơi/thế giới hoặc chết sẽ hủy tác vụ do console giao.
Pause giữ tác vụ; stop hủy tác vụ, start sau đó không tự tiếp tục tác vụ cũ.
Baritone xử lý tốc độ đi/chạy và chọn đường theo cấu hình của nó.

## Thử đi tới tọa độ

Nhập bot start, rồi bot info. Chọn điểm gần trong khu vực thử nghiệm.
Ví dụ nếu đang ở X=22, Y=68, Z=6, có thể thử:

```text
bot goto 25 68 6
```

Thay tọa độ ví dụ theo thế giới của bạn. Sau đó dùng bot info để xem vị trí.
Thử bot pause, bot resume và bot stop trong một tác vụ xa hơn.
Baritone có thể phá/đặt block để tìm đường tùy cấu hình mặc định;
chọn thế giới thử nghiệm khi kiểm tra. Đào bằng bot mine sẽ thay đổi block trong thế giới.

## Thư viện và đóng gói

`libs/baritone-api-fabric-1.19.0.jar` là bản chính thức có API công khai.
Gradle đưa nó vào môi trường phát triển. Không cài thêm bản standalone song song.
Khi chạy mod ngoài IntelliJ, đặt cả JAR mod của chúng ta và JAR Baritone này
vào thư mục mods của Fabric (cùng Fabric API).
Bản mod của chúng ta khai báo cần Baritone 1.19.0; không nhúng lại JAR Baritone.

Nguồn: https://github.com/cabaletta/baritone/releases/tag/v1.19.0
SHA-256: eca6e2fdf43c6657fe9fea10a8f9a7572d78dc91ad389b998b808bd28fa5b5d1
Baritone dùng giấy phép LGPL-3.0; xem THIRD_PARTY.md.

## Console và nhật ký

Một console kết nối tại một thời điểm. Nếu console mới hết thời gian chờ,
nhập exit ở console cũ trước. Tệp kết nối: run/bot-console.properties.
Nhật ký core: run/logs/minecraft-ai-bot/bot-0.log.
Nhật ký tác vụ Baritone/client: run/logs/latest.log.

Các kiểm tra tự động xác nhận cú pháp, điều phối lệnh và chuyển trạng thái.
Chuyển động và đào trong thế giới cần được kiểm tra trực tiếp sau khi nạp bản mới.
Module kết nối Gemini/Groq, chuyển AI dự phòng, khởi tạo prompt riêng và bộ nhớ chung đã được thêm. Xem ai/AI_SETUP.vi.md.

Nhiệm vụ cơ bản có sẵn: `bot task list`. Xem TASKS.vi.md để chạy và chọn số lượng.

Lấy đồ từ rương gần bot: `bot chest`. Xem CHESTS.vi.md để mở rương, đọc đồ và lấy đúng số lượng.
Phản hồi AI hiện là đề xuất có kiểm tra cấu trúc, chưa tự thực thi lệnh game.

## Xem túi đồ

Nhập `bot inventory` tại dấu `bot>`; không cần bot start.
Lệnh đọc túi đồ chính và thanh nhanh (hotbar), gộp các stack cùng mã vật phẩm.
Ví dụ hai stack oak_log lần lượt 10 và 6 sẽ hiển thị `minecraft:oak_log x16`.
Số ô trống chỉ tính túi đồ chính/thanh nhanh, chưa tính áo giáp hoặc tay phụ.
Vật phẩm trong rương, shulker box hoặc vật phẩm đang giữ bằng con trỏ không được tính.
Danh sách hiện dùng mã vật phẩm ổn định để thuận tiện cho bộ điều phối AI sau này.
Ở menu hoặc chưa vào thế giới, lệnh báo chưa vào thế giới.
