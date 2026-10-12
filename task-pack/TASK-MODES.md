# Task modes

Place `"mode": "ensure"`, `"mode": "collect"` or `"mode": "loop"` near the start of a `.taskbot` file. Older files without `mode` default to `ensure`.

| Mode | Meaning of `count: 16` with 10 items already owned |
| --- | --- |
| `ensure` | Reach 16 items total: gather 6 more. |
| `collect` | Add 16 items: reach 26 at the current step. |
| `loop` | Reach 16 items total, finish the remaining steps, wait five seconds and repeat the chain. |

The mode applies to mining, crafting, cooking food and smelting. `collect` snapshots existing stock **when entering each step**, so materials consumed by earlier steps do not create a stale baseline. Progress is a net inventory increase, including worn equipment where relevant. Items consumed or lost during a step must be replaced before its target is met; crafted stacks can exceed the target by a recipe's batch size.

In `ensure`, existing equipment and `skipIf` conditions can skip steps, and a satisfied final `goal` can skip the whole workflow. In `collect`, existing items do not skip steps: `skipIf` and the already-owned `goal` shortcut are ignored, including for tools and armor. A nearby furnace can still be reused for smelting, but a `craft` step requesting more furnaces crafts additional blocks.

Use `ensure` for equipment preparation and restocking. Use `collect` for gathering an additional batch. The example `collect-wood.taskbot` requests 16 additional logs. Starting a task again in `collect` creates fresh baselines and requests another batch.

The web preview shows the mode. Execution progress shows current stock for `ensure`/`loop` and net additions for `collect`. In `loop`, each step advances when its required stock is met; chest transfers, messages and menu actions still execute their explicit quantities once per round. Use the 1.2.0 JAR for loop tasks and the in-game controls.

## Tiếng Việt

Thêm một dòng ở đầu file:

```json
"mode": "ensure"
```

- `ensure`: **bổ sung cho đủ**. Có 10 gỗ, yêu cầu 16 → đào thêm 6. Tận dụng đồ đã có và các điều kiện bỏ qua.
- `collect`: **thu thêm số lượng yêu cầu**. Có 10 gỗ, yêu cầu 16 → đào thêm 16. Không bỏ qua chỉ vì đã có đồ, kể cả công cụ và giáp.

Mốc đếm của `collect` được chốt khi bắt đầu từng bước. Đồ bị dùng hoặc mất trong bước đó sẽ làm giảm tiến độ; công thức tạo theo bó có thể cho dư một ít. Khởi chạy lại task sẽ yêu cầu một đợt mới.

File cũ không ghi `mode` vẫn chạy như `ensure`. Task bộ giáp và công cụ kim cương dùng `collect` để chế thêm một bộ mới. File mẫu `collect-wood.taskbot` dùng `collect` để thu thêm 16 gỗ.
