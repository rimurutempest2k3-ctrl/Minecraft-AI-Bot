# Console-only archive

Frozen on 2026-10-11 at the user's request. This branch preserves the final console-only implementation; it is no longer maintained. The main repository remains active for the standard client.

The archived client hides the Minecraft window and skips scene drawing, but still needs Minecraft, Java 25, Fabric, graphics initialization and Baritone 1.19.0. It is not a standalone headless Minecraft protocol client.

Use a separate game profile and install only one bot JAR. Download the archive release's JAR for the mod or ZIP for the console tools and task pack. Use `bot display game` to show the window temporarily. In development, `runConsoleClient` starts hidden and `runClient` shows the window.

The archive includes ordered tasks, chest deliveries, chat/command steps and guarded chest-style server menu actions. Real server testing remains necessary. See `task-pack/TASK-CHAINS.md` for formats and current limits.

## Tiếng Việt

Đã ngừng phát triển nhánh console-only ngày 2026-10-11. Mã nguồn và bản đóng gói được giữ để lưu trữ. Dự án tiếp tục với bản Minecraft có cửa sổ và web điều khiển. Không cài đồng thời hai JAR bot vào cùng profile.
