# Task System V1 — Specification

**Status:** Design draft. Primary mode: user-directed tasks.

## Task lifecycle
QUEUED -> PREPARING -> RUNNING -> COMPLETED
RUNNING <-> PAUSED
QUEUED/PREPARING/RUNNING/PAUSED -> CANCELLED
PREPARING/RUNNING -> FAILED

Terminal states: COMPLETED, FAILED, CANCELLED. Retrying a terminal task creates a new execution record linked to the original.

## Task schema (example)
```json
{
  "taskId": "task_001",
  "type": "COLLECT_ITEM",
  "source": "USER",
  "priority": 50,
  "parameters": {
    "item": "minecraft:oak_log",
    "count": 16
  },
  "status": "QUEUED",
  "progress": {"current": 0, "target": 16},
  "retryCount": 0,
  "maxRetries": 3,
  "timeoutSeconds": 600
}
```
The schema is provisional. Validate item identifiers, bounds, world context and user permissions. Record creation/update timestamps, task version and checkpoint metadata in implementation.

## Queue
- Exactly one active task in V1; pending tasks wait in a queue.
- User can reorder/cancel pending tasks.
- Priority may influence queue order but must not silently preempt a running task.
- Only explicit user action or a defined safety policy may interrupt execution.
- Tasks created by AI must be approved and validated before enqueueing.

## First supported task
**COLLECT_ITEM(item, count)**
1. Read inventory and establish starting count.
2. Determine how many additional items are needed (do not accidentally count pre-existing items as newly gathered if the task contract requires newly collected resources).
3. Search for an accessible resource, navigate, break/harvest, collect drops.
4. Recheck inventory and world state after each attempt.
5. Complete only when the agreed postcondition is true.
6. If unreachable or resource not found after bounded search, return a specific error.

V1 default postcondition: player inventory contains at least `count` of the requested item. A future option may require collecting `count` **new** items or delivering them to a specified container.

## Initial skill contracts
- MOVE_TO(position, tolerance)
- LOOK_AT(target)
- BREAK_BLOCK(position)
- PICKUP_ITEM(itemEntity / region)
- PLACE_BLOCK(position, blockState) — planned
- OPEN_CONTAINER, TRANSFER_ITEM, CRAFT_ITEM — later

Each skill defines required context, preconditions, asynchronous completion, postconditions, timeout, cancellation and error codes.

## Error model
- RESOURCE_NOT_FOUND
- PATHFINDING_FAILED
- PLAYER_STUCK
- OUT_OF_REACH
- BLOCK_PROTECTED
- INVENTORY_FULL
- MISSING_MATERIALS
- TIMEOUT
- NO_PROGRESS
- DISCONNECTED
- INVALID_TASK
- SKILL_UNAVAILABLE

Retry only transient failures, with capped attempts and bounded search. Fail or pause for intervention when recovery is unsafe. Log every retry and state transition.

## Persistence / restart
Store task queue, active task checkpoint and last observations. On reconnect, load saved task into a recovery/pause state internally, revalidate player, inventory, dimension, targets and server state, then resume only if safe. Never blindly repeat irreversible actions.

## FAST/DEEP THINK integration
- FAST THINK: configurable periodic review (candidate: every 10 minutes), not per tick; may return CONTINUE, ADJUST, ABORT, ESCALATE_TO_DEEP.
- DEEP THINK: on completion or significant failure; V1 can explain results or suggest next tasks but cannot autonomously enqueue.
- No API is necessary for deterministic commands; all AI plans must pass the same schema validator.

## Observability
Log fields: timestamp, level, module, taskId, stepId, actionId, requestId, event, details (redacted). Capture task state and relevant game snapshot on failure.

## Acceptance tests (initial)
1. Valid COLLECT_ITEM transitions through expected states.
2. Invalid item/count is rejected before execution.
3. Pause stops issuing new world actions; resume continues safely.
4. Cancel releases player control and marks CANCELLED.
5. Skill timeout produces bounded retry then FAILED or PAUSED.
6. Progress is computed from observed inventory.
7. Restart restores checkpoint without duplicating actions.
8. AI plan requesting an unavailable skill is rejected.
9. API timeout does not block the client thread.
10. Only one world-mutating skill controls the player at a time.
