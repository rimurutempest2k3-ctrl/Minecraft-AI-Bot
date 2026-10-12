# Task chains

Choose a `.taskbot` file on the web and run it. The content is JSON. Steps execute in order, and the next step starts only after its result is checked. Existing `mine`, `craft`, `smelt` and `food` steps remain supported.

The editor displays fixed nodes on a grid connected by straight lines. Each node has an action selector at the top and its own matching settings below. Click Save step in each modified node before saving or downloading the whole chain. Open Add a step below the canvas to create another node. Scroll horizontally to reach later steps; moving nodes up/down changes execution order. A chain supports up to 100 steps, and the library supports up to 100 files.

To create a file without writing JSON, open **Tasks & progress → Create task**. Enter a title, filename and mode; add each step with its item ID, quantity or menu/chest details. Edit, duplicate or move steps up/down to arrange the sequence. Save the step being edited before saving the task. **Save to task library** validates the complete file through the mod, adds it to the library and never starts it automatically or replaces an existing file. Choose a new filename if it already exists. **Download .taskbot** exports the draft for sharing; the mod still validates it when imported. Saved draft steps and task details are kept in this browser. Custom recipe definitions still require editing the task file directly.

Auto eating and defense against mobs within 4 blocks temporarily suspend the chain. They do not start autonomous hunting, looting or retreat. The chain resumes after the intervention ends and the crafting grid/cursor is empty. You can toggle nearby defense with `bot survival combat on/off`.

A failed step becomes `BLOCKED`, preserving completed steps and collection baselines. Resolve its reported cause, recover any crafting/furnace/cursor items, close menus and use **Resume saved task**, or `bot workflow resume`. The bot checks current inventory again. It does not blindly repeat a failed transfer. `bot stop` cancels the chain. Progress currently lasts only for the same game session/player/world; restart or respawn does not restore the chain automatically.

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

Both chest actions always transfer the specified quantity, independently of the task's `ensure`/`collect` mode. Counts range from 1 to 64 per step. Coordinates must be integers and refer to a chest in the current dimension. Ordinary and double chests are supported; either half of a double chest can be targeted. No nearby chest is chosen automatically. The chest must exist and be accessible, with enough items and destination space. Locks, server protection and incompatible menus can block the step.

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

## Tiếng Việt

Chọn file `.taskbot` và khởi chạy trên web. Các bước chạy theo thứ tự và kiểm tra kết quả trước khi chuyển tiếp. Tự ăn và đánh trả trong 4 khối chỉ ngắt tạm thời; không tự đi săn, nhặt đồ hay tìm rương.

Để tự soạn, vào **Nhiệm vụ & tiến độ → Tạo nhiệm vụ**. Nhập tên, tên file, chế độ rồi thêm các bước bằng biểu mẫu. Có thể sửa, nhân bản và đưa bước lên/xuống. Lưu bước đang sửa trước khi lưu nhiệm vụ. **Lưu vào thư viện nhiệm vụ** sẽ kiểm tra toàn bộ file, không tự chạy và không ghi đè file đã có. **Tải file .taskbot** xuất bản nháp để chia sẻ. Các bước đã thêm và thông tin nhiệm vụ được giữ trong trình duyệt. Công thức chế tạo riêng vẫn cần sửa trong file.

Khi bước bị lỗi, tiến độ được giữ ở `BLOCKED`. Xử lý nguyên nhân, thu lại đồ trong lưới chế tạo/lò/con trỏ, đóng giao diện rồi bấm **Tiếp tục nhiệm vụ đã lưu** hoặc nhập `bot workflow resume`. Tiến độ hiện chỉ giữ trong cùng phiên game, người chơi và thế giới.

Trong ví dụ trên, đổi tọa độ hai rương. `chest_take` lấy đồ, `chest_put` cất đồ; mỗi bước chuyển đúng số lượng yêu cầu từ 1–64, không phụ thuộc mode. Không tự tìm rương khác khi rương chỉ định không dùng được. Nếu bị ngắt khi đồ còn trên con trỏ, cất lại vào túi bot và kiểm tra số lượng trước khi tiếp tục.

Các bước `chat`, `command`, `menu_open`, `menu_click` dùng `count: 1`. Lệnh server không có dấu `/` đầu dòng. Ô menu đếm từ 0, mỗi hàng 9 ô. Đổi tên menu, số ô và ID vật phẩm đúng với server của bạn. Bot chỉ bấm trái một lần khi tên menu/ID khớp và con trỏ trống, rồi chờ menu mới (`nextTitle`) hoặc menu đóng (`closed: true`). Lỗi thì giữ bước, không tự lặp tin nhắn/lệnh hay bấm lại. Hiện chưa xử lý xác nhận qua chat, menu chỉ đổi nội dung mà không đổi màn hình, hoặc chuyển sang server khác giữa chuỗi.
