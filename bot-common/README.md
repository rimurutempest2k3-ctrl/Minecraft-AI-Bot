# Bot Common — Console Prototype

This module is intentionally independent of Minecraft, Fabric, and rendering. It provides a small lifecycle controller, command dispatcher, and rotating console/file logging.

Requirements: JDK 21 and Gradle with Java 21 support.

From the repository root:

```sh
gradle :bot-common:run --console=plain
```

Try `bot help`, `bot start`, `bot status`, `bot pause`, `bot resume`, `bot stop`, then `exit`.

Logs are written to the process working directory under `logs/` and rotated at approximately 1 MiB per file (three files). These files are excluded from Git.

This is **not a Minecraft client mod yet**. The next step is a Fabric adapter that reuses this core, plus an external command transport for remote control. The current terminal reads stdin directly and must not be exposed to the network.

The test class `BotCoreTest` is a simple Java main, not a Gradle test suite yet.
