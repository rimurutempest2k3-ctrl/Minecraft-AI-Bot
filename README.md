# Minecraft AI Bot

<p align="center"><img src="assets/minecraft-ai-bot-logo.png" alt="Minecraft AI Bot logo" width="320"></p>

A Minecraft Java client mod with Baritone navigation, JSON tasks, survival
assistance, chest memory, optional AI control and a local web dashboard.

## Requirements

- Minecraft Java 26.2 and Java 25+.
- Fabric Loader 0.19.5+, Fabric API for 26.2.
- Baritone Fabric 1.19.0 for 26.2, installed separately.

## Install and run

1. Create a Fabric 26.2 profile in your launcher and locate its game directory.
2. Copy the bot JAR, Fabric API and Baritone into its `mods/` folder.
3. Copy the release package's `config/` folder into the game directory.
   Preserve your customized files when upgrading.
4. Start Minecraft and enter a world.
5. Open the address in `bot-web-url.txt` in your browser.
   It is usually `http://127.0.0.1:8765`.
6. Start the bot on the web, select a task and click Run.

Task files are in `config/minecraft-ai-bot/tasks/`. Edit or add JSON there,
then reload the task list on the web. Local tasks do not require an AI key.
Use Settings to select English/Vietnamese and configure optional AI providers.
Keep API keys in the game's `config/minecraft-ai-bot/secrets.properties` private.

The optional Windows console uses `bot-console.bat` and `tools/` from the release
package, placed in the game directory. Run `.\bot-console.bat` in PowerShell.
Java must be available through PATH or JAVA_HOME. The web does not require this console.

Hotbar layout: edit `config/minecraft-ai-bot/inventory-layout.json` and run `bot survival equipment reload`. See [Inventory layout](INVENTORY.md) for the fields and scoring rules.

## Basic commands

| Command | Purpose |
| --- | --- |
| `bot help` | List available commands |
| `bot start` | Start the bot |
| `bot info` | View player and bot status |
| `bot inventory` | View inventory |
| `bot workflow list` | List JSON tasks |
| `bot workflow run furnace.json` | Run the furnace task |
| `bot stop` | Stop the bot and cancel its current task |

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
The project license is in LICENSE.

## Hướng dẫn tiếng Việt

Mod hỗ trợ nhiệm vụ JSON, di chuyển/đào bằng Baritone, sinh tồn, bộ nhớ rương,
AI tùy chọn và điều khiển trên web local.

1. Cài Minecraft 26.2 với Fabric Loader 0.19.5+, Java 25+, Fabric API và Baritone 1.19.0.
2. Chép các JAR vào `mods/` của đúng thư mục game trong launcher.
3. Chép `config/` từ gói phát hành vào thư mục game; giữ cấu hình riêng khi nâng cấp.
4. Vào thế giới, mở địa chỉ trong `bot-web-url.txt`, bật bot và chọn nhiệm vụ trên web.

Nhiệm vụ ở `config/minecraft-ai-bot/tasks/`; sửa JSON rồi đọc lại danh sách trên web.
Nhiệm vụ local không cần API. Đổi ngôn ngữ và lưu API key trong Cài đặt.
Có thể dùng các lệnh trong bảng trên. Dùng `bot stop` để hủy nhiệm vụ.

Console là tùy chọn: đặt `bot-console.bat` và `tools/` vào thư mục game, rồi chạy
`.\bot-console.bat`. Bản đã đóng gói không cần IntelliJ hay Gradle.
Để build từ mã nguồn, dùng JDK 25, đặt `baritone-api-fabric-1.19.0.jar` vào
`libs/`, rồi chạy `.\gradlew.bat releaseZip`.
