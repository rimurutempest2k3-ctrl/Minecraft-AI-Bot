# Minecraft AI Bot — Project Context

> Living design record. Status: planning, no implementation claimed.
> Last updated: 2026-10-08.

## Vision
Build a Minecraft Java client bot that first executes user-assigned tasks reliably, then gradually gains autonomous survival, planning, memory and base-building abilities. Develop on Windows. The exact Minecraft, Fabric, Java, Baritone and optional Meteor versions remain **TBD** until compatibility is verified.

## Agreed decisions
- **V1 is user-directed**, not self-directed: user submits tasks; bot executes and reports results.
- Start with **one active task**, plus a queue of pending tasks.
- Local deterministic code performs movement, interaction, validation and emergency reflexes; LLMs plan at a higher level, not per game tick or click.
- Manual structured commands must work without an AI API. Add natural-language task entry through a backend AI Task Gateway.
- **FAST THINK**: periodic plan/progress review (initial proposal: ~10 minutes, configurable), may CONTINUE, ADJUST, ABORT or ESCALATE_TO_DEEP.
- **DEEP THINK**: after task completion or significant failure, analyze outcomes and propose next steps. **In V1 it must not automatically assign a new task**.
- Pause, resume, cancel, timeout, bounded retry, safe logout and emergency stop are essential.
- Preserve logs and progress across restarts; revalidate actual game state before resuming.
- AI budget limits and redacted diagnostics are required.

## Planned components
- Fabric client integration and game-state reader
- Bot Core / lifecycle and local safety controller
- Task Manager, Task Queue, Task Validator, Retry Controller
- Skill Registry and executors (Baritone for navigation where compatible; native Minecraft interaction for inventories and world actions)
- World / storage / player / task memory
- Reasoning Router, Context Builder, API Budget Manager
- AI Task Gateway backend and Web Dashboard
- BaseBuilder using reusable survival-compatible blueprints
- Structured logging and debug-report export

## First vertical slice
1. Launch a Fabric client mod and read position, health, hunger and inventory.
2. Accept a deterministic task such as COLLECT_ITEM(minecraft:oak_log, 16).
3. Navigate, break blocks, collect drops and verify inventory count.
4. Report COMPLETED, FAILED or CANCELLED with diagnostic logs.
5. Only after this works, integrate the AI planner and dashboard.

## Survival building
Start with a compact 7x7 starter-house blueprint: bed, crafting table, furnace, chest and lighting. The blueprint is not itself a building engine. Builder must gather materials, survey land, place blocks legally within reach, check server confirmation, persist progress and protect other players' structures. Store completed facility coordinates in World Memory.

## Repository and security
Keep repository private during early development. Never commit API keys, Minecraft authentication/session data, secrets or private logs. Use environment variables or protected local configuration. Respect dependency licenses and server rules. GitHub issues, pull requests and automated tests can support collaboration.

## Open decisions
- Exact Minecraft/Fabric/Java versions and compatible Baritone release.
- Skill APIs, initial task schema, serialization and persistence choice (JSON vs SQLite).
- Web backend technology, authentication and remote-access model.
- Blueprint format (.schem importer and/or normalized JSON), build ordering and world protection.
- Precise FAST THINK interval, token/cost limits and permissions for task revisions.
