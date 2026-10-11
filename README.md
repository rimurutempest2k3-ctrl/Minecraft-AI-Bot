# Minecraft AI Bot — Console-only development

<p align="center"><img src="assets/minecraft-ai-bot-logo.png" alt="Minecraft AI Bot logo" width="320"></p>

A Minecraft Java client mod with Baritone navigation, ordered JSON tasks, nearby defense and auto eating, chest memory, optional AI control and a local web dashboard.

## Development tracks

- **Standard client:** the published [v1.0.0 release](https://github.com/rimurutempest2k3-ctrl/Minecraft-AI-Bot/releases/tag/v1.0.0), with the game window and local web dashboard; maintained through main/dev.
- **Console-only client:** this branch, `console-only`, version `1.0.0-console-dev`. It shares the bot engine, tasks and configuration format, but starts with the game window hidden and scene drawing disabled.

Install only one bot JAR in a Minecraft profile. Use separate game directories for testing the two tracks so settings, server/chest memory and task files stay independent.

## Requirements

- Minecraft Java 26.2 and Java 25+.
- Fabric Loader 0.19.5+, Fabric API for 26.2.
- Baritone Fabric 1.19.0 for 26.2, installed separately.

## Install and run

1. Create a Fabric 26.2 profile in your launcher and locate its game directory.
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

The task pack includes `full-diamond-armor.taskbot`: prepare food and tools, smelt iron, make iron equipment, then collect 35 additional diamonds and craft all four armor pieces plus a pickaxe, axe, shovel, sword and hoe. This task uses collect mode to create a new set. See [Diamond armor task](https://github.com/rimurutempest2k3-ctrl/Minecraft-AI-Bot/blob/main/task-pack/FULL-DIAMOND-ARMOR.md). The complete in-game workflow is still awaiting verification.

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
| `bot workflow run furnace.taskbot` | Run the furnace task |
| `bot stop` | Stop the bot and cancel its current task |

## Connect from the menu

With Minecraft open at the main or multiplayer menu, use the console or the
web console to run `bot server connect example.org:25565`. The default port is
25565. Use `bot server status` to inspect the connection, `bot server disconnect`
to leave the world, and `bot server reconnect` to retry the last server in this
session. Connection results appear automatically. After joining, run `bot start`.
Minecraft still handles account authentication and resource-pack/consent dialogs.

## Console-only mode

This branch starts in console-only mode by default. Run `gradlew.bat runConsoleClient`
in development. To launch with the game window instead, add
`-Dminecraftaibot.consoleOnly=false` to the launcher's Java/JVM arguments.
The game window is hidden after the console starts. Scene/UI drawing is skipped;
client ticks, networking, Baritone and the local console/web continue running.
The background loop is capped at 30 frames per second without changing the
server's tick rate. Loading overlays may still render to complete resource reloads.

Use `bot display console` to enter this mode during a normal session,
`bot display game` to restore the window, and `bot display status` to inspect it.
Use `bot client quit` to close Minecraft normally. Display mode is session-only;
this branch defaults to console-only on each launch. Existing graphics/focus settings
are restored when returning to the game window.

This is not a graphics-free headless client: Minecraft still initializes its
window, graphics backend and assets. It requires a working desktop/graphics
setup. Show the window when Minecraft requires a first-run, account, resource-pack
or consent dialog. Connecting and tasks use the same commands as the normal mod.

## Build from source

Use JDK 25. Download `baritone-api-fabric-1.19.0.jar` from
[Baritone v1.19.0](https://github.com/cabaletta/baritone/releases/tag/v1.19.0)
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

Dự án có hai hướng: bản thường từ Release 1.0.0 (main/dev), và bản console-only trên nhánh riêng (1.0.0-console-dev). Bản console mặc định ẩn cửa sổ. Dùng thư mục game riêng khi thử hai bản; mỗi hồ sơ chỉ cài một JAR bot.

Mod hỗ trợ nhiệm vụ JSON, di chuyển/đào bằng Baritone, sinh tồn, bộ nhớ rương,
AI tùy chọn và điều khiển trên web local.

1. Cài Minecraft 26.2 với Fabric Loader 0.19.5+, Java 25+, Fabric API và Baritone 1.19.0.
2. Chép các JAR vào `mods/` của đúng thư mục game trong launcher.
3. Mod tự tạo cấu hình và nhiệm vụ mặc định còn thiếu khi khởi động. ZIP cũng có bản để chỉnh sửa; giữ cấu hình riêng khi nâng cấp.
4. Vào thế giới, mở địa chỉ trong `bot-web-url.txt`, bật bot và chọn nhiệm vụ trên web.

Nhiệm vụ ở `config/minecraft-ai-bot/tasks/`. Bấm Duyệt file nhiệm vụ trên web để nhập `.taskbot` hoặc `.json` cũ (tối đa 256 KB); file được kiểm tra, lưu thành `.taskbot`, không ghi đè và không tự chạy. Nội dung vẫn là JSON; công thức và cấu hình khác giữ đuôi `.json`. Chọn file, xem trước rồi bấm Khởi chạy.
Nhiệm vụ local không cần API. Đổi ngôn ngữ và lưu API key trong Cài đặt.
Có thể dùng các lệnh trong bảng trên. Dùng `bot stop` để hủy nhiệm vụ.

Thêm `"mode": "ensure"` để bổ sung cho đủ, hoặc `"mode": "collect"` để thu thêm số lượng yêu cầu. File cũ mặc định `ensure`. Gói có task chế thêm đủ giáp và năm công cụ kim cương bằng chế độ collect, hỗ trợ nung sắt và thu 35 kim cương mới. Đã kiểm tra tự động; chuỗi giáp kim cương còn cần thử hoàn tất trong game.

Console là tùy chọn: đặt `bot-console.bat` và `tools/` vào thư mục game, rồi chạy
`.\bot-console.bat`. Bản đã đóng gói không cần IntelliJ hay Gradle.
Để build từ mã nguồn, dùng JDK 25, đặt `baritone-api-fabric-1.19.0.jar` vào
`libs/`, rồi chạy `.\gradlew.bat releaseZip`.

Giấy phép [MIT](LICENSE) cho phép fork và phân phối lại khi giữ thông báo bản quyền và giấy phép. Phần đã công bố theo CC0 vẫn giữ quyền sử dụng CC0.

Từ menu chính/chơi mạng, nhập `bot server connect example.org:25565` trên console hoặc web. Dùng `bot server status` xem trạng thái, `bot server disconnect` thoát, `bot server reconnect` kết nối lại server gần nhất trong phiên. Sau khi vào server, dùng `bot start`. Kết quả kết nối tự xuất hiện; các hộp thoại của Minecraft vẫn cần xử lý trong game.

Chế độ chỉ console: chạy `gradlew.bat runConsoleClient` trong IDE hoặc thêm `-Dminecraftaibot.consoleOnly=true` vào tham số JVM của launcher. Dùng `bot display console` để ẩn cửa sổ/tắt vẽ game, `bot display game` để hiện lại, `bot display status` để xem trạng thái và `bot client quit` để đóng game. Đây vẫn là Minecraft client cần đồ họa khi khởi tạo; các hộp thoại cần xác nhận phải xử lý khi hiện lại cửa sổ.
