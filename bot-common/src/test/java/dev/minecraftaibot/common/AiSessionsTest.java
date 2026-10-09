package dev.minecraftaibot.common;

import com.google.gson.*;
import dev.minecraftaibot.common.ai.AiSessions;
import dev.minecraftaibot.common.ai.AiCredentials;
import java.nio.file.*;
import java.util.*;

public final class AiSessionsTest {
    private static void checkRetries() throws Exception {
        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        AiSessions.Transport recovering = AiSessions.withRetries((m, k, r) -> {
            if (++calls[0] < 3) throw new AiSessions.ApiFailure(503);
            return new JsonObject();
        }, notices::add, delays::add);
        recovering.generate("test", "fake-key", new JsonObject());
        check(calls[0] == 3 && delays.equals(List.of(2000L, 4000L)) && notices.size() == 2);
        for (int status : new int[]{503, 403, 429, 400, 404}) {
            calls[0] = 0;
            AiSessions.Transport failing = AiSessions.withRetries((m, k, r) -> {
                calls[0]++; throw new AiSessions.ApiFailure(status);
            }, message -> {}, ms -> {});
            try { failing.generate("test", "fake-key", new JsonObject()); throw new AssertionError("Expected HTTP failure"); }
            catch (AiSessions.ApiFailure expected) { check(calls[0] == (status == 503 ? 3 : 1)); }
        }
        calls[0] = 0;
        try {
            AiSessions.withRetries((m, k, r) -> { calls[0]++; throw new AiSessions.ApiFailure(503); },
                    message -> {}, ms -> { throw new InterruptedException(); }).generate("test", "fake-key", new JsonObject());
            throw new AssertionError("Expected interruption");
        } catch (InterruptedException expected) { check(calls[0] == 1); }
        System.out.println("All bounded retry, recovery, non-retryable error and interruption checks passed.");
    }
    public static void main(String[] args) throws Exception {
        I18nTest.run();
        InventoryLayoutTest.run();
        ChestMemoryTest.run();
        ChestMemoriesTest.run();
        AgentLoopTest.run();
        LocalTaskTest.run();
        ProductionPlanTest.run();
        SurvivalPolicyTest.run();
        JumpPlacementTest.run();
        PlacementRecoveryTest.run();
        MultiAiTest.run();
        checkRetries();
        checkCredentials();
        checkKeyCommand();
        Path directory = Files.createTempDirectory("ai-session-check");
        List<JsonObject> requests = new ArrayList<>();
        AiSessions.Transport fake = (model, key, request) -> {
            requests.add(request.deepCopy());
            return JsonParser.parseString("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"{\\\"action\\\":\\\"inventory\\\",\\\"args\\\":{},\\\"message\\\":\\\"Xem túi đồ\\\"}\"}]}}]}").getAsJsonObject();
        };
        try {
            AiSessions first = new AiSessions(directory, "PROMPT V1", fake);
            check(!first.initialized("main", "test-model", "1", "test-key-A"));
            first.ask("main", "test-model", "1", "test-key-A", "Lấy gỗ");
            check(first.initialized("main", "test-model", "1", "test-key-A"));
            check(requests.getLast().getAsJsonArray("contents").size() == 1);
            AiSessions restart = new AiSessions(directory, "PROMPT V2", fake);
            restart.ask("main", "test-model", "1", "test-key-A", "Tiếp tục");
            check(requests.getLast().getAsJsonArray("contents").size() == 3);
            check(requests.getLast().getAsJsonObject("systemInstruction").getAsJsonArray("parts").get(0)
                    .getAsJsonObject().get("text").getAsString().equals("PROMPT V1"));
            restart.ask("main", "test-model", "1", "test-key-B", "Phiên mới");
            check(requests.getLast().getAsJsonArray("contents").size() == 1);
            check(requests.getLast().getAsJsonObject("systemInstruction").getAsJsonArray("parts").get(0)
                    .getAsJsonObject().get("text").getAsString().equals("PROMPT V2"));
            restart.ask("main", "other-model", "1", "test-key-A", "Model mới");
            restart.ask("other-profile", "test-model", "1", "test-key-A", "Profile mới");
            restart.ask("main", "test-model", "2", "test-key-A", "Revision mới");
            try (var files = Files.list(directory)) { check(files.count() == 5); }
            AiSessions failing = new AiSessions(directory, "FAILURE PROMPT", (model, key, request) -> { throw new java.io.IOException("simulated failure"); });
            try { failing.ask("failed", "test-model", "1", "test-key-A", "Test"); throw new AssertionError(); }
            catch (java.io.IOException expected) { }
            check(!failing.initialized("failed", "test-model", "1", "test-key-A"));
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    String data = Files.readString(file);
                    check(!data.contains("test-key-A") && !data.contains("test-key-B"));
                    JsonObject session = JsonParser.parseString(data).getAsJsonObject();
                    if (session.get("profile").getAsString().equals("failed")) {
                        check(!session.get("initialized").getAsBoolean());
                        check(session.getAsJsonArray("history").isEmpty());
                    }
                }
            }
            for (String bad : new String[]{
                    "{\"action\":\"shell\",\"args\":{},\"message\":\"x\"}",
                    "{\"action\":\"mine\",\"args\":{\"block\":\"oak_log\",\"quantity\":0},\"message\":\"x\"}",
                    "{\"action\":\"goto\",\"args\":{\"x\":1.5,\"y\":64,\"z\":2},\"message\":\"x\"}",
                    "{\"action\":\"stop\",\"args\":{\"command\":\"extra\"},\"message\":\"x\"}"}) {
                try { AiSessions.validate(JsonParser.parseString(bad).getAsJsonObject()); throw new AssertionError(); }
                catch (java.io.IOException expected) { }
            }
            CommandDispatcher dispatcher = new CommandDispatcher(new BotCore(), () -> "info", c -> "action",
                    () -> "inventory", c -> c);
            check(dispatcher.execute("bot ai ask Lấy Gỗ Sồi").equals("ask Lấy Gỗ Sồi"));
            System.out.println("All AI initialization, restart, credential/model/profile rotation, failed-call and output validation checks passed.");
        } finally {
            try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(directory);
        }
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("AI session check failed"); }
    private static void checkCredentials() throws Exception {
        Path directory = Files.createTempDirectory("ai-credentials-check");
        Path file = directory.resolve("secrets.properties");
        try {
            check(AiCredentials.load(directory, null).source() == AiCredentials.Source.NONE);
            AiCredentials.ensureTemplate(directory);
            check(AiCredentials.load(directory, "  ").source() == AiCredentials.Source.NONE);
            Files.writeString(file, "gemini_api_key=synthetic-file-key\n");
            AiCredentials.ensureTemplate(directory);
            var fromFile = AiCredentials.load(directory, null);
            check(fromFile.value().equals("synthetic-file-key"));
            check(fromFile.source() == AiCredentials.Source.FILE);
            check(!fromFile.toString().contains("synthetic-file-key"));
            var fromEnvironment = AiCredentials.load(directory, " synthetic-env-key ");
            check(fromEnvironment.value().equals("synthetic-env-key"));
            check(fromEnvironment.source() == AiCredentials.Source.ENVIRONMENT);
            Files.writeString(file, "gemini_api_key=synthetic-replacement-key\n");
            check(AiCredentials.load(directory, "").value().equals("synthetic-replacement-key"));
            Files.writeString(file, "gemini_api_key=synthetic-old-key\nother_setting=kept\n");
            AiCredentials.store(directory, "SyNtHeTiC-New_Key123");
            check(AiCredentials.load(directory, null).value().equals("SyNtHeTiC-New_Key123"));
            check(Files.readString(file).contains("other_setting=kept"));
            AiCredentials.store(directory, "AQ.Synthetic-Key_123");
            check(AiCredentials.load(directory, null).value().equals("AQ.Synthetic-Key_123"));
            for (String invalid : new String[]{"", "short", "synthetic key", "synthetic\nkey", "\"synthetic-key\""}) {
                try { AiCredentials.store(directory, invalid); throw new AssertionError("Invalid key accepted"); }
                catch (java.io.IOException expected) { }
                check(AiCredentials.load(directory, null).value().equals("AQ.Synthetic-Key_123"));
            }
            Files.writeString(file, "gemini_api_key=" + "\\uZZZZ");
            check(AiCredentials.load(directory, "synthetic-env-key").source() == AiCredentials.Source.ENVIRONMENT);
            try { AiCredentials.load(directory, null); throw new AssertionError("Malformed secrets accepted"); }
            catch (java.io.IOException expected) { check(!expected.getMessage().contains("uZZZZ")); }
            System.out.println("All credential file fallback, environment priority, reload, no-overwrite and redaction checks passed.");
        } finally { Files.deleteIfExists(file); Files.delete(directory); }
    }
    private static void checkKeyCommand() {
        var logger = java.util.logging.Logger.getLogger(CommandDispatcher.class.getName());
        var previousLevel = logger.getLevel();
        List<String> logs = new ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        capture.setLevel(java.util.logging.Level.ALL);
        logger.addHandler(capture); logger.setLevel(java.util.logging.Level.ALL);
        try {
            String[] received = {null};
            CommandDispatcher commands = new CommandDispatcher(new BotCore(), () -> "info", c -> "action",
                    () -> "inventory", c -> "ai", key -> { received[0] = key; return "Saved key."; });
            String result = commands.execute("bot get API SyNtHeTiC-New_Key123");
            check(received[0].equals("SyNtHeTiC-New_Key123"));
            check(!result.contains(received[0]));
            check(commands.execute("bot get API").contains("hidden key input"));
            commands.execute("bot get unknown synthetic-sensitive-token");
            check(logs.isEmpty());
            System.out.println("All console key routing, case preservation and no-key-logging checks passed.");
        } finally { logger.removeHandler(capture); logger.setLevel(previousLevel); }
    }
}
