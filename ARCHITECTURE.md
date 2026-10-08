# Architecture — Minecraft AI Bot

**Status:** Proposed architecture, not yet implemented.

## Core data flow

```text
User (manual commands / Web Dashboard)
                |
       Task Input / AI Task Gateway
                |
      Plan Validator + Permission Checks
                |
          Task Queue (one active)
                |
            Task Manager <----> Persistent Task/World/Storage Memory
                |
          Skill Registry / Executor
          |          |           |
     Baritone    Client APIs   BaseBuilder
          |          |           |
          +---- Minecraft client/server
                |
         Observations + Event Logger
                |
       Validator / Retry / Reflex Layer

AI Task Gateway -> Context Builder -> Budget Manager -> LLM
                                       |
                              validated plan proposal
```

## Module responsibilities

### Bot Core
Lifecycle, tick-safe scheduling, module wiring, connection handling and shutdown. Never block the Minecraft client thread waiting for network/API calls.

### Game State Reader
Read-only snapshots of player position, health, hunger, inventory, world and visible entities/blocks. Snapshot format must include timestamps and dimension.

### Task Manager
Single active task, pending queue, task state transitions, cancellation, progress tracking, persistence and skill dispatch. Task Manager never invents missing skills.

### Skill Registry and Executor
Typed skill contracts with preconditions, parameters, execution, cancellation, postconditions and error codes. Minecraft actions must be legal in Survival, within reach, and checked against server-observed state. Pathfinding may delegate to compatible Baritone.

### Validator and Retry Controller
Validate incoming task schema, skill availability and parameters before execution. Check postconditions against observations. Bounded retries with backoff; distinguish retryable from permanent failures. Timeouts and no-progress watchdogs prevent infinite loops.

### Reasoning Router
- FAST THINK: timer-based lightweight review during long tasks; result enum CONTINUE/ADJUST/ABORT/ESCALATE_TO_DEEP.
- DEEP THINK: after task completion or significant failure; in user-directed V1, propose next task only, do not enqueue autonomously.
- All AI output is untrusted proposed data: schema validate and authorize before acting.
- API calls are asynchronous and budget constrained.

### AI Task Gateway
Backend endpoint accepting natural-language tasks, building a minimal context snapshot, calling AI, validating returned structured plans, presenting a preview, and optionally queueing only after configured approval. Store API key on backend, not in browser JS. Authenticate dashboard endpoints and apply request limits.

### Memory
Persist tasks and execution checkpoints, last-observed world locations and storage inventories with last-seen timestamps. Revalidate chest contents, block state and surroundings after reconnection; do not assume multiplayer world data is unchanged.

### BaseBuilder
Read normalized blueprint, compute bill of materials, select safe build site, prepare terrain with authorization, plan placement order, build incrementally, verify every block, record checkpoints and register facilities in memory. Begin with a 7x7 starter house.

### Safety Controller
Local immediate responses to lava, fall hazards, hostile mobs and low health. May interrupt ordinary tasks. Never wait for an AI response to avoid immediate danger. Respect server permissions and other players' property.

### Logging / Debug
Structured logs with timestamp, severity, module, taskId, stepId, actionId and requestId; log rotation, redaction, stack traces and debug-report export. Do not store secrets.

## Proposed initial interfaces (conceptual)

```text
submitTask(TaskSpec) -> TaskId
getTask(TaskId) -> TaskSnapshot
pauseTask(TaskId)
resumeTask(TaskId)
cancelTask(TaskId)
Skill.execute(SkillRequest, CancellationToken) -> SkillResult
Validator.check(TaskSpec, GameSnapshot) -> ValidationResult
```

## Constraints
- Only one world-mutating skill should own player controls at a time.
- API failure must not freeze Minecraft; task should continue safely or pause.
- All user and AI task input passes the same validation layer.
- Do not depend on Meteor until version, licensing and conflicts are verified.
- No requirement for operator/creative privileges.
