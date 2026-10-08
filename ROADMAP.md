# Development Roadmap — Minecraft AI Bot

**Status:** Planning targets, not completed features or promised release dates.

## Milestone 0 — Project setup
- [ ] Verify exact target Minecraft, Fabric, Java and Baritone versions.
- [ ] Set up Windows development environment, Gradle and Fabric project.
- [ ] Add .gitignore, dependency/license review, CI build and basic tests.
- [ ] Keep secrets out of Git and document setup steps.

**Exit criterion:** clean build and launch in development client.

## Milestone 1 — v0.1.0 Core Prototype
- [ ] Read game snapshots (health, hunger, position, inventory).
- [ ] Task Manager: queue, states, pause/resume/cancel, timeout and retry.
- [ ] Structured logging and task IDs.
- [ ] Basic movement, break-block and pickup skills.
- [ ] End-to-end COLLECT_ITEM task with observed result validation.

**Exit criterion:** collect a specified resource in Survival and produce a reproducible log.

## Milestone 2 — v0.2.0 Survival Prototype
- [ ] Basic safety reflexes and recovery.
- [ ] Crafting, inventory management, storage interaction.
- [ ] Persist/revalidate world and storage memory.
- [ ] BaseBuilder: 7x7 starter-house blueprint, bill of materials, terrain checks, legal placement, checkpointing.
- [ ] Bed, chest, furnace, crafting table and lighting registered in memory.

**Exit criterion:** build and use a basic survival shelter without Creative/operator privileges.

## Milestone 3 — v0.3.0 AI Integration
- [ ] Backend AI Task Gateway with authenticated dashboard.
- [ ] Natural-language task -> structured plan -> validation -> user preview.
- [ ] FAST THINK timer and DEEP THINK after task completion.
- [ ] Budget controls, request timeouts and redacted AI logs.
- [ ] Task plan revision with explicit approval.

**Exit criterion:** user assigns a supported multi-step task via dashboard and receives a validated completion report.

## Milestone 4 — Toward v1.0 Stable Release
- [ ] Resilient task recovery after disconnect/restart.
- [ ] More skills, expanded blueprints and survival workflows.
- [ ] Automated tests, documentation and debug-report export.
- [ ] Performance profiling, permissions and server-rule checks.
- [ ] Decide whether and when to open-source under a suitable license.

**Exit criterion:** defined regression suite passes and supported tasks run reliably under documented conditions.

## Current next action
While away from Windows: finalize V1 skill contracts and acceptance tests. Do not lock Minecraft/Java version or claim compatibility before checking upstream releases.
