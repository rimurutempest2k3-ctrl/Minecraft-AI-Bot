# Task modes

Place `"mode": "ensure"` or `"mode": "collect"` near the start of a `.taskbot` file. Older files without `mode` default to `ensure`.

| Mode | Meaning of `count: 16` with 10 items already owned |
| --- | --- |
| `ensure` | Reach 16 items total: gather 6 more. |
| `collect` | Add 16 items: reach 26 at the current step. |

The mode applies to mining, crafting, cooking food and smelting. `collect` snapshots existing stock **when entering each step**, so materials consumed by earlier steps do not create a stale baseline. Progress is a net inventory increase, including worn equipment where relevant. Items consumed or lost during a step must be replaced before its target is met; crafted stacks can exceed the target by a recipe's batch size.

In `ensure`, existing equipment and `skipIf` conditions can skip steps, and a satisfied final `goal` can skip the whole workflow. In `collect`, existing items do not skip steps: `skipIf` and the already-owned `goal` shortcut are ignored, including for tools and armor. A nearby furnace can still be reused for smelting, but a `craft` step requesting more furnaces crafts additional blocks.

Use `ensure` for equipment preparation and restocking. Use `collect` for gathering an additional batch. The example `collect-wood.taskbot` requests 16 additional logs. Starting a task again in `collect` creates fresh baselines and requests another batch.

The web preview shows the mode. Execution progress shows current stock for `ensure` and net additions for `collect`. Use the updated 1.0.1 JAR; downloads from before this update do not support these fields.

## Tiếng Việt

Thêm một dòng ở đầu file:

```json
"mode": "ensure"
```

- `ensure`: **bổ sung cho đủ**. Có 10 gỗ, yêu cầu 16 → đào thêm 6. Tận dụng đồ đã có và các điều kiện bỏ qua.
- `collect`: **thu thêm số lượng yêu cầu**. Có 10 gỗ, yêu cầu 16 → đào thêm 16. Không bỏ qua chỉ vì đã có đồ, kể cả công cụ và giáp.

Mốc đếm của `collect` được chốt khi bắt đầu từng bước. Đồ bị dùng hoặc mất trong bước đó sẽ làm giảm tiến độ; công thức tạo theo bó có thể cho dư một ít. Khởi chạy lại task sẽ yêu cầu một đợt mới.

File cũ không ghi `mode` vẫn chạy như `ensure`. Task giáp kim cương dùng `ensure` để tránh chế lại đồ đã có. File mẫu `collect-wood.taskbot` dùng `collect` để thu thêm 16 gỗ.
