# Architecture Notes

This is a working outline of how the code might be split up. It is not a final Java package layout.

## Main flow

```text
User
  |-- Fixed command
  |-- Natural-language request from dashboard
  |
  v
Task input / AI planning when needed
  |
  v
Validation
  |
  v
Task queue
  |
  v
Task Manager
  |-- Read game state
  |-- Run a skill
  |-- Check the result
  |-- Retry or report failure
  |
  v
Minecraft client (Fabric / Baritone)
```

Memory, logging, and the AI Gateway fit around this flow. They do not all need to be built at once.

## Reading game state

This module collects the player's position, health, hunger, inventory, dimension, and information the client can actually observe. Task completion must be based on these observations, not on the AI claiming that something is finished.

Network calls and other slow work must not block the Minecraft client thread.

## Task Manager

The Task Manager owns the active task and pending queue. It advances steps, updates status, saves checkpoints, and dispatches skills.

It does not implement pathfinding or click inventory slots directly. Those responsibilities belong to skills.

## Skills

Each skill needs a clear input, preconditions, a way to detect completion, a timeout, and cancellation support.

Start with:

- `MOVE_TO`: navigate to a position, potentially through Baritone.
- `LOOK_AT`: face a target.
- `BREAK_BLOCK`: break a reachable block.
- `PICKUP_ITEM`: collect a dropped item and verify the inventory change.

Add `PLACE_BLOCK`, crafting, container access, and item transfers later. Issuing an action is not proof that the server accepted it; verify the resulting game state.

Only one skill should control conflicting player actions at a time.

## Memory

Initially, persist the task queue, active task, checkpoints, and a small amount of world information. Later, add bases, storage locations, explored areas, and last-seen timestamps.

After a death, disconnect, or restart, reload the checkpoint but verify the current inventory, dimension, position, and target before continuing.

## AI Gateway and dashboard

The dashboard will offer two entry points:

- **Manual Task:** fixed commands that do not require an AI API.
- **AI Task Gateway:** natural-language requests sent to a backend that returns a structured plan.

The plan must pass schema, skill availability, parameter, and permission checks before execution. Users should be able to review and revise it. Keep the API key on the backend, never in browser-side JavaScript.

FAST THINK periodically reviews progress. DEEP THINK reviews completed tasks or difficult failures. Neither should silently add a new task in the first user-directed version.

## BaseBuilder

The builder reads a blueprint, calculates required materials, checks the build site, places blocks in a workable order, and verifies the result. It needs to account for reach distance, block orientation, temporary access, and existing player structures.

A `.schem` file is building data, not a Survival building engine. We still need code that performs valid placement actions.

## Logging and failure handling

Give each task its own ID. Record timestamps, skill names, current step, location, and error details. Exportable debug reports should not contain authentication data.

Distinguish between missing resources, pathfinding failures, being stuck, out-of-reach targets, full inventory, timeouts, and disconnects.

## Implementation order

Game-state reader → minimal Task Manager → movement skill → mining and pickup → completion validation → persistence.

The dashboard, AI integration, and BaseBuilder can follow once that path works.
