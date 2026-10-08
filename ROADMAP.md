# Development Plan

This is an order of work, not a release schedule. The repository currently holds design notes; there is no working mod yet.

## Before coding

- [ ] Pick the target Minecraft Java version.
- [ ] Check compatible Fabric, Java, and Baritone releases.
- [ ] Set up IntelliJ IDEA, Gradle, and a Windows test environment.
- [ ] Create a minimal Fabric project and a suitable `.gitignore`.

**Done when:** the mod launches in a development client and produces a log entry.

## Step 1 — Execute a simple task

- [ ] Read player position, health, hunger, and inventory.
- [ ] Add a Task Manager with a queue, pause, resume, and cancel.
- [ ] Implement a movement skill.
- [ ] Test block breaking and item pickup in Survival.
- [ ] Request 16 oak logs and verify the inventory result.
- [ ] Record enough information to diagnose failures.

**Done when:** the bot either completes a simple collection task or reports a specific failure without hanging.

## Step 2 — Basic survival

- [ ] Handle immediate threats locally.
- [ ] Add crafting and inventory management.
- [ ] Open containers and transfer items.
- [ ] Persist base locations, storage, and task progress.
- [ ] Build a blueprint-based construction system.
- [ ] Test a 7×7 starter house with a bed, chest, crafting table, furnace, and lighting.

**Done when:** the bot can gather materials and build a usable starter shelter in Survival without Creative privileges.

## Step 3 — AI integration

- [ ] Build a backend and web dashboard.
- [ ] Accept natural-language task requests.
- [ ] Generate a plan, validate it, and let the user approve it.
- [ ] Add FAST THINK, DEEP THINK, and API spending limits.
- [ ] Support revisions to proposed plans.
- [ ] Handle API failures and exhausted budgets.

**Done when:** the user can submit a supported multi-step task from the dashboard and receive a verified result.

## Step 4 — Reliability and expansion

- [ ] Test disconnects, deaths, and restarts.
- [ ] Add more skills, storage workflows, and house blueprints.
- [ ] Set up automated builds and tests on GitHub.
- [ ] Document installation, limitations, and debug-report export.
- [ ] Review dependencies and choose a license before making the code public.

There is no need to set a v1.0 date yet. The immediate priority is getting one small task to work reliably.
