# TaskFlow Bot

<p align="center"><img src="assets/minecraft-ai-bot-logo.png" alt="Minecraft AI Bot logo" width="320"></p>

A Minecraft Java client automation mod focused on reliable, ordered task chains. Create tasks visually on the local web dashboard or share `.taskbot` files to gather items, craft, smelt, travel, transfer chest items and interact with server menus. AI is optional; local tasks run without an API key.

Version **1.2.0** develops the regular Fabric client as a task automation tool. Nearby defense and auto eating support assigned tasks. The console-only track is archived and is no longer developed.

Press **F8** in game to open bot controls: Start, Pause, Resume and Stop. The shortcut can be reassigned in Minecraft Controls. Pause keeps local task progress; Stop cancels it. Web tasks also support **Repeat continuously**.

## Requirements

- Minecraft Java 1.21.11 and Java 21+.
- Fabric Loader 0.19.3+, Fabric API for 1.21.11.
- [Download Baritone API Fabric 1.17.0](https://github.com/cabaletta/baritone/releases/download/v1.17.0/baritone-api-fabric-1.17.0.jar) for Minecraft 1.21.11, installed separately.


Use `baritone-api-fabric-1.17.0.jar`. Remove older Baritone JARs from the active profile's `mods/` folder before installing it; keep only one Baritone version.

## Install and run

1. Create a Fabric 1.21.11 profile in your launcher and locate its game directory.
2. Copy the bot JAR, Fabric API and Baritone into its `mods/` folder.
3. The mod creates missing default configuration and task files on startup.
   The ZIP includes an editable copy too; preserve customized files when upgrading.
4. Start Minecraft and enter a world.
5. Open the address in `bot-web-url.txt` in your browser.
   It is usually `http://127.0.0.1:8765`.
6. Start the bot on the web, select a task and click Run.

Task files are in `config/minecraft-ai-bot/tasks/`. Use Browse task file on the web to import `.taskbot` or legacy `.json` files (up to 256 KB). Imports are validated, saved as `.taskbot` and never overwrite existing files or start automatically. Rename a file before importing if its name already exists. The content remains JSON; only task files use `.taskbot`, while recipes and other configuration keep `.json`. Edit or add tasks there,
then reload the task list on the web. Local tasks do not require an AI key.
Use Settings to select English/Vietnamese and configure optional AI providers.
Keep API keys in the game's `config/minecraft-ai-bot/secrets.properties` private.

Tasks support `"mode": "ensure"` to replenish missing stock, or `"mode": "collect"` to gather an additional quantity. Older files default to `ensure`. See [Task modes](https://github.com/rimurutempest2k3-ctrl/Minecraft-AI-Bot/blob/main/task-pack/TASK-MODES.md).

The task pack contains only `example.taskbot`, a minimal private-notification example. The earlier experimental tasks are no longer distributed. Create your own tasks on the web or edit the example.

The optional Windows console uses `bot-console.bat` and `tools/` from the release
package, placed in the game directory. Run `.\bot-console.bat` in PowerShell.
Java must be available through PATH or JAVA_HOME. The web does not require this console.

Task chains run only when assigned. Auto eating and defense within 4 blocks may interrupt a chain temporarily; its steps are retained. Autonomous hunting, looting, retreat and world-entry preparation do not run in the background. Hunting/cooking still works as an explicit task step.

If a step fails, its progress is held as `BLOCKED`. Resolve the cause, recover items from the crafting grid/cursor or furnace, close menus, then use `bot workflow resume`. Resume rechecks inventory; completed steps and collect baselines remain intact. Progress is currently held only in the same game session/player/world; restarting Minecraft does not restore it. See [Task chains and chest steps](task-pack/TASK-CHAINS.md).

## Basic commands

| Command | Purpose |
| --- | --- |
| `bot help` | List available commands |
| `bot start` | Start the bot |
| `bot info` | View player and bot status |
| `bot inventory` | View inventory |
| `bot workflow list` | List task files |
| `bot workflow run example.taskbot` | Run the example task |
| `bot stop` | Stop the bot and cancel its current task |

## Build from source

Use JDK 25 to run the current Gradle/Loom build; the mod and shared core target Java 21. Download `baritone-api-fabric-1.17.0.jar` from
[Baritone v1.17.0](https://github.com/cabaletta/baritone/releases/tag/v1.17.0)
and place it in `libs/` (create the folder if needed).
Run `gradlew.bat releaseZip` on Windows or `./gradlew releaseZip`
on macOS/Linux. The runtime package is written to `build/releases/`.
For IntelliJ testing, run `gradlew.bat runClient`; its game directory is `run/`.
Place task files under `run/config/minecraft-ai-bot/tasks/`.

Author: **rimurutempest2k3-ctrl**.
[Source](https://github.com/rimurutempest2k3-ctrl/Minecraft-AI-Bot) ·
[Credits](CREDITS.md) · [Third-party software](THIRD_PARTY.md).
Licensed under [MIT](LICENSE). Forks and redistribution are welcome; retain the copyright and license notices. Previously published CC0 material remains available under CC0; see [Third-party software](THIRD_PARTY.md).

## Hướng dẫn tiếng Việt

Mod tập trung tự động hóa chuỗi nhiệm vụ: tạo trên web hoặc chia sẻ file `.taskbot`
để lấy vật phẩm, chế tạo, nung, di chuyển, lấy/cất đồ trong rương và thao tác menu server.
AI là tùy chọn; nhiệm vụ local không cần API key. Bản 1.2.0 tiếp tục hướng Fabric client;
bản console-only đã lưu trữ và không phát triển tiếp.

1. Cài Minecraft 1.21.11 với Fabric Loader 0.19.3+, Java 21+, Fabric API và [Baritone API Fabric 1.17.0](https://github.com/cabaletta/baritone/releases/download/v1.17.0/baritone-api-fabric-1.17.0.jar).
2. Chép các JAR vào `mods/` của đúng thư mục game trong launcher.
3. Mod tự tạo cấu hình và nhiệm vụ mặc định còn thiếu khi khởi động. ZIP cũng có bản để chỉnh sửa; giữ cấu hình riêng khi nâng cấp.
4. Vào thế giới, mở địa chỉ trong `bot-web-url.txt`, bật bot và chọn nhiệm vụ trên web.

Nhiệm vụ ở `config/minecraft-ai-bot/tasks/`. Bấm Duyệt file nhiệm vụ trên web để nhập `.taskbot` hoặc `.json` cũ (tối đa 256 KB); file được kiểm tra, lưu thành `.taskbot`, không ghi đè và không tự chạy. Nội dung vẫn là JSON; công thức và cấu hình khác giữ đuôi `.json`. Chọn file, xem trước rồi bấm Khởi chạy.
Nhiệm vụ local không cần API. Đổi ngôn ngữ và lưu API key trong Cài đặt.
Có thể dùng các lệnh trong bảng trên. Dùng `bot stop` để hủy nhiệm vụ.

Thêm `"mode": "ensure"` để bổ sung cho đủ, hoặc `"mode": "collect"` để thu thêm số lượng yêu cầu. File cũ mặc định `ensure`. Gói chỉ chứa `example.taskbot` để minh họa thông báo riêng. Các nhiệm vụ thử nghiệm cũ không còn được phân phối; tạo task trên web hoặc chỉnh sửa ví dụ.

Console là tùy chọn: đặt `bot-console.bat` và `tools/` vào thư mục game, rồi chạy
`.\bot-console.bat`. Bản đã đóng gói không cần IntelliJ hay Gradle.
Để build từ mã nguồn, dùng JDK 25, đặt `baritone-api-fabric-1.17.0.jar` vào
`libs/`, rồi chạy `.\gradlew.bat releaseZip`.

Giấy phép [MIT](LICENSE) cho phép fork và phân phối lại khi giữ thông báo bản quyền và giấy phép. Phần đã công bố theo CC0 vẫn giữ quyền sử dụng CC0.

Nếu gặp lỗi phiên bản Baritone: chuyển JAR Baritone cũ ra khỏi `mods/` của hồ sơ đang chạy, rồi cài đúng `baritone-api-fabric-1.17.0.jar`. Chỉ giữ một bản Baritone.
