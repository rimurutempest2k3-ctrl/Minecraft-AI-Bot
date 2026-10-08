# Task Manager V1

The first version has one job: take a specific user request, execute it using supported skills, and report a verified result. It does not need to invent new goals.

## Task states

```text
QUEUED -> PREPARING -> RUNNING -> COMPLETED
                        |  ^
                        v  |
                       PAUSED

Unrecoverable errors lead to FAILED.
The user can CANCEL a task before it reaches a terminal state.
```

- `QUEUED`: waiting for its turn.
- `PREPARING`: checking prerequisites.
- `RUNNING`: executing steps.
- `PAUSED`: temporarily stopped with progress retained.
- `COMPLETED`: the result has been verified.
- `FAILED`: the task cannot be completed; an error is recorded.
- `CANCELLED`: stopped by the user.

COMPLETED, FAILED, and CANCELLED are terminal states. A retry after termination creates a new execution record so logs remain traceable.

## Example task

```json
{
  "taskId": "task_001",
  "type": "COLLECT_ITEM",
  "parameters": {
    "item": "minecraft:oak_log",
    "count": 16
  },
  "status": "QUEUED",
  "retryCount": 0,
  "maxRetries": 3
}
```

This is a draft format. The implementation will also need timestamps, timeouts, and checkpoint information.

## How COLLECT_ITEM should work

1. Count matching items already in the inventory.
2. If the count is at least 16, finish immediately. The default V1 rule is **have at least 16 items**, not necessarily collect 16 new ones.
3. Otherwise, look for an accessible source of the item.
4. Navigate, harvest or break the appropriate block, and collect drops.
5. Read the inventory again. Continue only within the configured search and time limits.

If no trees are found, the bot should not wander forever. If navigation gets stuck, it may retry with a new path a limited number of times before reporting failure.

## Initial error codes

| Code | Meaning |
| --- | --- |
| `RESOURCE_NOT_FOUND` | Nothing suitable found within the search area |
| `PATHFINDING_FAILED` | No viable route |
| `PLAYER_STUCK` | No movement or task progress |
| `OUT_OF_REACH` | Target cannot be interacted with |
| `INVENTORY_FULL` | No room for items |
| `TIMEOUT` | Operation exceeded its time limit |
| `SKILL_UNAVAILABLE` | Required capability is not implemented |
| `DISCONNECTED` | Connection to the server was lost |

Not every failure is retryable. Missing skills or denied permissions should not trigger an endless retry loop.

## Pause, cancel, and recovery

Pausing stops new actions and asks the current skill to stop safely. Cancellation also releases control of the player.

After restarting the game, load the saved checkpoint but do not immediately repeat the last action. Check the current inventory, position, dimension, and target first.

## Where AI fits

Fixed commands go straight to the Task Manager. Natural-language requests are turned into proposed plans, which are validated against supported skills and parameters.

FAST THINK may recommend continuing, adjusting, aborting, or escalating to DEEP THINK. DEEP THINK can review results or difficult errors. In V1, neither automatically assigns new tasks.

## Tests to run first

- Request 16 oak logs with 0, 8, and 16 already in inventory.
- Reject an invalid item ID or count.
- Pause while navigating, then resume.
- Cancel during mining and confirm that actions stop.
- Block the path deliberately and check that retries are bounded.
- Restart midway through a task and revalidate before continuing.
- Reject an AI plan that names an unsupported skill.
- Simulate an API timeout and confirm the game stays responsive.

Task Manager V1 is ready for expansion only when these cases behave predictably.
