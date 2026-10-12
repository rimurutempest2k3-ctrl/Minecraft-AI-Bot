# Task chains

Choose a `.taskbot` file on the web and run it. The content is JSON. Steps execute in order, and the next step starts only after its result is checked. Existing `mine`, `craft`, `smelt` and `food` steps remain supported.

The bundled task recipe library includes all 1,056 vanilla shaped/shapeless crafting grids from Minecraft 26.2. Ingredient tags, alternative ingredients and output components are preserved. Recipes in a task take precedence over your external `core-data/task-recipes.json`, which takes precedence over bundled defaults. Missing entries in an older external file are supplied by the bundled library without overwriting that file. Recipe files support up to 2 MB and 2,048 entries; task files still have a 256 KB limit. The executor prefers recipes whose ingredients are already available and gathers mineable shortages where possible. Missing non-mineable ingredients may require an explicit hunting/smelting step or manual supply. The full raw catalog also contains smithing, stonecutting and special game recipes; these are not ordinary grid-crafting actions. Recipes with returned containers and server datapack changes still require in-game verification.

For maintainers, run `node release-tools/export-task-recipes.cjs` after updating `minecraft-recipes-26.2.json` to regenerate both shipped copies of `task-recipes.json`. The exporter keeps compatibility/custom entries and replaces only generated `vanilla_` entries.

Storage chests and production blocks (crafting tables, furnaces, blast furnaces and smokers) are opened only after rotating the player camera and confirming the actual crosshair hits the block within interaction range. In **Settings → Mod & interface → Block opening method**, choose **Right-click key** (default) to activate Minecraft's bound use key once, or **Minecraft interaction** to use the normal client interaction API after aiming. The choice is saved in `config/minecraft-ai-bot/interaction.json`. Console commands: `bot interaction mouse` and `bot interaction minecraft`. Wait for active actions to finish before changing it. This does not add support for new storage menu types or guarantee compatibility with every server rule.

With survival support enabled, a visible or ignited creeper within 4 blocks temporarily interrupts the task. The bot holds its shield and walks backwards while checking collisions, solid ground and nearby mobs; it stops backing up if the corridor is unsafe. It continues defending until no eligible creeper remains within 8 blocks, then resumes the saved task. Hidden creepers that have not started igniting do not interrupt the task. If burning on solid ground with a water bucket in inventory, the bot first attempts to place water at its feet. Water placement is skipped where water evaporates, including the Nether. Failed attempts have a cooldown; water is not automatically retrieved. Both behaviours still need in-game verification.

The editor displays fixed nodes on a grid connected by straight lines. Each node has an action selector at the top and its own matching settings below. Click Save step in each modified node before saving or downloading the whole chain. Open Add a step below the canvas to create another node. For item IDs, furnace inputs and expected menu items, open Choose from item list and search by item name or ID; choose a result and click Use selected item. The bundled core-data/items-26.2.json contains all 1,537 vanilla item definitions from Minecraft 26.2. It is served without querying the game registry. Names follow the web language; search matches English, Vietnamese and IDs and ignores case, accents and underscores. Mod or server-specific IDs can still be entered manually. Only the first 80 matches are shown; narrow the search if needed. Manual IDs remain supported, and the mod validates whether the chosen item is suitable for the task. Use the − / + / Reset zoom controls (40–200%). Wheel zoom is disabled. The canvas fits node height and does not reserve extra space for a pointer anchor. Zoom is saved in this browser and does not alter exported task steps. Hold Shift and roll the mouse wheel over the canvas to scroll left/right and reach later steps; moving nodes up/down changes execution order. A chain supports up to 100 steps, and the library supports up to 100 files.

To create a file without writing JSON, open **Tasks & progress → Create task**. Enter a title, filename and mode; add each step with its item ID, quantity or menu/chest details. Edit, duplicate or move steps up/down to arrange the sequence. Save the step being edited before saving the task. **Save to task library** validates the complete file through the mod, adds it to the library and never starts it automatically or replaces an existing file. Choose a new filename if it already exists. **Download .taskbot** exports the draft for sharing; the mod still validates it when imported. Saved draft steps and task details are kept in this browser. Custom recipe definitions still require editing the task file directly.

Auto eating and defense against mobs within 4 blocks temporarily suspend the chain. They do not start autonomous hunting, looting or retreat. The chain resumes after the intervention ends and the crafting grid/cursor is empty. You can toggle nearby defense with `bot survival combat on/off`.

A failed step becomes `BLOCKED`, preserving completed steps and collection baselines. Resolve its reported cause, recover any crafting/furnace/cursor items, close menus and use **Resume saved task**, or `bot workflow resume`. The bot checks current inventory again. It does not blindly repeat a failed transfer. `bot stop` cancels the chain. Progress currently lasts only for the same game session/player/world; restart or respawn does not restore the chain automatically.

The main action selector groups chest take/store, chat/command, and menu open/click. Choose the operation inside each node. The exported JSON keeps the existing action IDs. Use Go to coordinates to travel to a block position in the current dimension: {"label":"Go home","action":"goto","count":1,"target":{"x":10,"y":64,"z":-20}}. No item is required. The bot checks actual arrival before advancing; a stopped path or the normal three-minute step timeout blocks the task for manual resume.

Set `"mode":"loop"` or select **Repeat continuously** to repeat the entire chain until stopped. Each round ensures the required current stock: already enough skips to the next step, shortages collect only the missing quantity. After the final step it waits five seconds and starts again, resetting step markers, chest transfer counters and one-shot command/menu confirmations. Chest transfers still move their explicitly requested quantity each round. The progress tab shows the round number. A failed step blocks rather than restarting or skipping it. Resume continues that step. Normal step and round timeouts still apply; the overall timer resets for each new round. `bot stop` cancels both execution and the pending repeat.

In game, **F8** opens Start / Pause / Resume / Stop controls. Pause saves local task progress and requires manual Resume; furnace contents may need recovering before continuing. Start and Resume close the GUI so movement can proceed. Stop cancels the task and loop. Nearby defense/auto eating remain separate features.

**Get items** searches the 1,045 vanilla item definitions with block loot sources in the bundled Minecraft 26.2 catalog. Other actions retain the complete item list. Block-source mapping supports drop IDs such as lapis lazuli, redstone, clay balls and books, plus normal/deepslate ores. The executor counts the requested item exactly. It checks harvesting tools, durability, Silk Touch and shears before starting; if the selected tool changes to an unsuitable one, gathering stops for manual recovery. Some drops still require mature plants, loot conditions or random chance; if the world cannot supply them, the normal blocked/timeout behavior applies. The catalog does not add mob hunting, crafting or furnace recipes to the mining action.

## Explicit chest deliveries

Save the following as `delivery.taskbot`, replace both chest coordinates, import it on the web and run it:

```json
{
  "version": 1,
  "mode": "collect",
  "title": "Deliver cobblestone",
  "description": "Move 16 cobblestone between two specified chests.",
  "steps": [
    {
      "label": "Take 16 cobblestone",
      "action": "chest_take",
      "item": "minecraft:cobblestone",
      "count": 16,
      "chest": { "x": 10, "y": 64, "z": 20 }
    },
    {
      "label": "Store 16 cobblestone",
      "action": "chest_put",
      "item": "minecraft:cobblestone",
      "count": 16,
      "chest": { "x": 30, "y": 64, "z": 20 }
    }
  ]
}
```

Both chest actions always transfer the specified quantity, independently of the task's `ensure`/`collect` mode. Counts range from 1 to 64 per step. Coordinates must be integers and refer to a chest in the current dimension. Ordinary and double chests are supported; either half of a double chest can be targeted. For an explicit coordinate, the bot first travels within 10 blocks, waits for the area to load, and prioritizes the exact chest. If that block is not a chest, it waits up to five seconds and selects the nearest loaded ordinary/trapped chest within 10 blocks of the requested position. The actual chosen position is logged. Travel/opening still has a 120-second timeout; failures preserve task progress. The chest must exist and be accessible, with enough items and destination space. Locks, server protection and incompatible menus can block the step.

Transfer progress records inventory changes during the transfer, not during travel or eating. If a transfer is interrupted with an item on the cursor, store it back in the player's inventory before resuming and verify the remaining quantity. Avoid changing the transferred item manually while recovering an interrupted transfer.

## Chat, commands and chest-style server menus

Use `chat` for messages and `command` for server commands, without the leading `/`. Both require `count: 1` and `text`, and are sent once. Sending confirms dispatch only, not that the server accepted the command. A following menu step waits up to 5 seconds for its expected screen. Nothing repeats automatically.

Example for a server whose `/menu` opens a chest-style screen titled `Games`:

```json
{
  "version": 1,
  "title": "Select server mode",
  "steps": [
    { "label": "Say hello", "action": "chat", "count": 1, "text": "Hello!" },
    { "label": "Open selector", "action": "command", "count": 1, "text": "menu" },
    {
      "label": "Select survival",
      "action": "menu_click",
      "count": 1,
      "menu": {
        "title": "Games",
        "slot": 13,
        "expectedItem": "minecraft:grass_block",
        "closed": true
      }
    }
  ]
}
```

For a selector opened by using a held item, replace the command step with:

```json
{
  "label": "Use selector compass",
  "action": "menu_open",
  "count": 1,
  "item": "minecraft:compass",
  "menu": { "title": "Games" }
}
```

Adapt titles, slots, item IDs and commands to your server. Menu slots start at **0**, in rows of nine, excluding the player's inventory. A click requires an exact title, matching item ID and empty cursor. Only a normal left click is currently supported. Use `nextTitle: "Confirmation"` instead of `closed: true` if another menu should appear; the next title must differ. The action waits for that result before continuing. A mismatch or timeout holds the chain as `BLOCKED`; resume waits again rather than repeating a sent click. Same-screen updates, chat confirmation parsing and reconnecting across server transfers are not yet supported. Do not use ordinary storage chests as server menus.

Imported tasks can send chat and server commands when you run them. Review their steps first; avoid putting passwords or API keys in shared task files.

Bot notifications appear in your Minecraft chat with the `[AI-bot]` prefix and are visible only on your client. Success messages are green, failures and blocked tasks are red, and other messages are white. To add your own notification, choose **Chat/command → Private notification** in the builder, or use a step with `"action": "local_chat"`, `"count": 1` and `"text": "Task finished"`. This step never sends a message or command to the server. The `chat` and `command` actions still send to the server.

## Tiếng Việt

Bộ đi kèm có đủ 1.056 công thức lưới vanilla 26.2. Công thức trong task ưu tiên trước file `core-data/task-recipes.json`, rồi mới tới bộ mặc định. File cũ thiếu công thức sẽ được bù khi đọc, không bị ghi đè. Giữ nhóm nguyên liệu, lựa chọn thay thế và dữ liệu thành phẩm. Nguyên liệu không đào được có thể cần bước săn/nung hoặc cung cấp trước. Bàn rèn, cắt đá và công thức đặc biệt không được thực thi bằng bước chế tạo lưới thông thường; công thức trả lại xô/chai và thay đổi riêng của server cần thử trong game.

Bot xoay camera và chờ tâm ngắm trúng block trong tầm tương tác trước khi mở rương đang được hỗ trợ, bàn chế tạo và các loại lò. Chọn tại **Cài đặt → Mod & giao diện → Cách mở block**: **Phím chuột phải** (mặc định) hoặc **Tương tác Minecraft**. Lưu trong `config/minecraft-ai-bot/interaction.json`; lệnh console là `bot interaction mouse` hoặc `bot interaction minecraft`. Chờ thao tác hiện tại kết thúc trước khi đổi. Cần thử tương thích trên từng server.

Khi bật hỗ trợ sinh tồn, creeper nhìn thấy hoặc đang kích nổ trong 4 khối sẽ ngắt tạm nhiệm vụ. Bot giữ khiên và lùi nếu phía sau có nền an toàn; ra khỏi vùng 8 khối thì tiếp tục nhiệm vụ. Creeper bị che và chưa kích nổ không ngắt nhiệm vụ. Khi cháy trên nền chắc và có xô nước trong túi, bot thử đặt nước dưới chân; bỏ qua nơi nước bốc hơi như Nether. Có thời gian nghỉ khi thất bại, chưa tự thu nước lại. Hai cơ chế này cần thử thực tế trong game.

Thông báo bot hiển thị trong chat Minecraft với tiền tố `[AI-bot]`, chỉ bạn thấy. Thành công màu xanh, lỗi hoặc nhiệm vụ bị chặn màu đỏ; thông báo khác màu trắng. Để thêm thông báo vào nhiệm vụ, chọn **Chat/lệnh → Thông báo riêng**. Bước `local_chat` dùng `count: 1` và `text`, không gửi lên server. Các bước `chat` và `command` vẫn gửi lên server như trước.

Chọn file `.taskbot` và khởi chạy trên web. Các bước chạy theo thứ tự và kiểm tra kết quả trước khi chuyển tiếp. Tự ăn và đánh trả trong 4 khối chỉ ngắt tạm thời; không tự đi săn, nhặt đồ hay tìm rương.

Để tự soạn, vào **Nhiệm vụ & tiến độ → Tạo nhiệm vụ**. Nhập tên, tên file, chế độ rồi thêm các bước bằng biểu mẫu. Có thể sửa, nhân bản và đưa bước lên/xuống. Lưu bước đang sửa trước khi lưu nhiệm vụ. **Lưu vào thư viện nhiệm vụ** sẽ kiểm tra toàn bộ file, không tự chạy và không ghi đè file đã có. **Tải file .taskbot** xuất bản nháp để chia sẻ. Các bước đã thêm và thông tin nhiệm vụ được giữ trong trình duyệt. Công thức chế tạo riêng vẫn cần sửa trong file.

Khi bước bị lỗi, tiến độ được giữ ở `BLOCKED`. Xử lý nguyên nhân, thu lại đồ trong lưới chế tạo/lò/con trỏ, đóng giao diện rồi bấm **Tiếp tục nhiệm vụ đã lưu** hoặc nhập `bot workflow resume`. Tiến độ hiện chỉ giữ trong cùng phiên game, người chơi và thế giới.

Trong ví dụ trên, đổi tọa độ hai rương. `chest_take` lấy đồ, `chest_put` cất đồ; mỗi bước chuyển đúng số lượng yêu cầu từ 1–64, không phụ thuộc mode. Bot đi đến trong 10 khối quanh tọa độ, chờ tải vùng và ưu tiên rương đúng tọa độ. Nếu vị trí đó không có rương, sau tối đa 5 giây bot chọn rương gần nhất trong 10 khối quanh tọa độ và báo vị trí thực tế. Nếu rương đúng tọa độ có tồn tại nhưng bị khóa hoặc không mở được, bot giữ tiến độ, không đổi sang rương khác. Nếu bị ngắt khi đồ còn trên con trỏ, cất lại vào túi bot và kiểm tra số lượng trước khi tiếp tục.

Các bước `chat`, `command`, `menu_open`, `menu_click` dùng `count: 1`. Lệnh server không có dấu `/` đầu dòng. Ô menu đếm từ 0, mỗi hàng 9 ô. Đổi tên menu, số ô và ID vật phẩm đúng với server của bạn. Bot chỉ bấm trái một lần khi tên menu/ID khớp và con trỏ trống, rồi chờ menu mới (`nextTitle`) hoặc menu đóng (`closed: true`). Lỗi thì giữ bước, không tự lặp tin nhắn/lệnh hay bấm lại. Hiện chưa xử lý xác nhận qua chat, menu chỉ đổi nội dung mà không đổi màn hình, hoặc chuyển sang server khác giữa chuỗi.
