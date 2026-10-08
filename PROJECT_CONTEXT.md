# Minecraft AI Bot — Project Notes

This file keeps track of decisions made during development. It should be enough to get back up to speed after a break, a change of machine, or a handoff.

## What we're building

The long-term goal is a Minecraft Java client bot that can survive on its own: gather resources, craft items, manage storage, build shelter, and react to danger. An LLM can help with planning, but ordinary movement and interactions should run locally without an API call for every action.

The first version will **not** play autonomously. It will accept tasks from the user, carry them out, and report what happened. Autonomous goal selection can come later, once task execution is dependable.

The likely starting point is a Fabric client mod developed on Windows. Baritone is a candidate for pathfinding. Meteor is optional, not a dependency we have committed to. The Minecraft, Java, Fabric, and Baritone versions still need to be checked together before implementation.

## Decisions so far

- Run one main task at a time; keep other tasks in a queue.
- Support pause, resume, cancel, and progress tracking.
- Accept both fixed commands (useful for testing) and natural-language requests through a web dashboard.
- Let the LLM propose plans and review progress. Local code handles navigation, mining, placement, inventory actions, and completion checks.
- FAST THINK periodically reviews long-running tasks. Ten minutes is an initial idea, not a fixed interval.
- DEEP THINK runs after task completion or a difficult failure. In the first version, it may suggest the next task but must not enqueue one on its own.
- Immediate safety reactions, such as avoiding lava or a dangerous fall, cannot depend on an API response.
- Keep useful task logs without exposing API keys, session tokens, or credentials.

## Survival and base building

After basic resource gathering works, add a builder that uses prepared blueprints. The first target is a small 7×7 house with a bed, chest, crafting table, furnace, and lighting.

The builder will need to calculate materials, find a suitable site, place blocks within normal Survival constraints, and save progress. Once finished, the bot should remember the location of useful facilities.

Stored world and chest information is only a record of what the bot last observed. It must be checked again when the bot returns, especially on multiplayer servers.

## First practical test

Give the bot a task to obtain 16 oak logs. It should check its inventory, locate trees, navigate, break blocks, collect drops, and verify the final count. If it cannot find a tree or gets stuck, it should stop with a meaningful error rather than run indefinitely.

That end-to-end loop matters more than connecting the AI early.

## Still open

- Exact Minecraft, Java, Fabric, and Baritone versions.
- JSON versus SQLite for persistence.
- Dashboard backend and secure access from a phone.
- Blueprint import format and placement order.
- API cost limits and the final FAST THINK interval.

The repository currently contains planning documents, not a working bot.
