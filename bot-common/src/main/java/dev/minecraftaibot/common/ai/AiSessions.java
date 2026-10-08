package dev.minecraftaibot.common.ai;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Prompt is snapshotted once per profile/model/credential/revision, not repeated in history. */
public final class AiSessions {
    @FunctionalInterface public interface Transport {
        JsonObject generate(String model, String key, JsonObject request) throws Exception;
    }
    private final Path directory;
    private final String defaultPrompt;
    private final Transport transport;
    public AiSessions(Path directory, String prompt, Transport transport) {
        this.directory = directory; this.defaultPrompt = prompt; this.transport = transport;
    }
    public synchronized String ask(String profile, String model, String revision, String key, String input) throws Exception {
        return askShared(profile, model, revision, key, input, null);
    }
    public synchronized String askShared(String profile, String model, String revision, String key, String input, JsonArray sharedHistory) throws Exception {
        if (key == null || key.isBlank()) throw new IOException("Chưa cấu hình Gemini API key.");
        if (!model.matches("[a-zA-Z0-9._/-]+")) throw new IOException("Tên mô hình không hợp lệ.");
        Files.createDirectories(directory);
        Path file = sessionFile(profile, model, revision, key);
        JsonObject session;
        if (Files.exists(file)) {
            session = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } else {
            session = new JsonObject();
            session.addProperty("provider", profile.startsWith("groq:") ? "groq" : "gemini");
            session.addProperty("profile", profile);
            session.addProperty("model", model);
            session.addProperty("revision", revision);
            session.addProperty("systemPrompt", defaultPrompt);
            session.addProperty("initialized", false);
            session.add("history", new JsonArray());
            save(file, session);
        }
        JsonArray contents = (sharedHistory == null ? session.getAsJsonArray("history") : sharedHistory).deepCopy();
        contents.add(content("user", input));
        JsonObject request = new JsonObject();
        JsonObject instruction = content("system", session.get("systemPrompt").getAsString());
        instruction.remove("role");
        request.add("systemInstruction", instruction);
        request.add("contents", contents);
        JsonObject generation = new JsonObject();
        generation.addProperty("responseMimeType", "application/json");
        generation.addProperty("maxOutputTokens", 2048);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();
        JsonObject action = new JsonObject(); action.addProperty("type", "STRING");
        JsonArray allowed = new JsonArray();
        for (String name : ACTIONS) allowed.add(name);
        action.add("enum", allowed); properties.add("action", action);
        JsonObject args = new JsonObject(); args.addProperty("type", "OBJECT");
        JsonObject argProperties = new JsonObject();
        for (String name : new String[]{"x", "y", "z", "quantity", "block"}) {
            JsonObject type = new JsonObject(); type.addProperty("type", name.equals("block") ? "STRING" : "INTEGER");
            argProperties.add(name, type);
        }
        args.add("properties", argProperties); properties.add("args", args);
        JsonObject message = new JsonObject(); message.addProperty("type", "STRING"); properties.add("message", message);
        schema.add("properties", properties);
        JsonArray required = new JsonArray(); for (String name : new String[]{"action", "args", "message"}) required.add(name);
        schema.add("required", required); generation.add("responseSchema", schema);
        request.add("generationConfig", generation);
        JsonObject response = transport.generate(model, key, request);
        JsonObject candidate = response.getAsJsonArray("candidates").get(0).getAsJsonObject();
        if (!"STOP".equals(candidate.get("finishReason").getAsString())) throw new IOException("AI chưa trả về phản hồi hoàn chỉnh.");
        JsonObject modelContent = candidate.getAsJsonObject("content");
        StringBuilder text = new StringBuilder();
        for (JsonElement part : modelContent.getAsJsonArray("parts")) {
            JsonObject value = part.getAsJsonObject();
            if (value.has("text") && !(value.has("thought") && value.get("thought").getAsBoolean())) text.append(value.get("text").getAsString());
        }
        JsonObject proposal = JsonParser.parseString(text.toString()).getAsJsonObject();
        validate(proposal);
        contents.add(modelContent.deepCopy()); // Preserve provider fields/signatures exactly.
        while (contents.size() > 12) { contents.remove(0); contents.remove(0); }
        session.add("history", contents);
        session.addProperty("initialized", true); // Only after a successful, validated response.
        save(file, session);
        return proposal.toString();
    }
    public boolean initialized(String profile, String model, String revision, String key) throws Exception {
        if (key == null || key.isBlank()) return false;
        Path file = sessionFile(profile, model, revision, key);
        return Files.exists(file) && JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .get("initialized").getAsBoolean();
    }
    private Path sessionFile(String profile, String model, String revision, String key) throws Exception {
        String identity = profile + "\n" + model + "\n" + revision + "\n" + key;
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        return directory.resolve(id + ".json");
    }
    private static final Set<String> ACTIONS = Set.of("info", "inventory", "status", "start", "goto", "mine", "pause", "resume", "stop", "wait", "ask", "done", "message", "chest_open", "chest_list", "chest_close", "chest_take", "chest_put", "chest_memory");
    public static void validate(JsonObject value) throws IOException {
        if (!value.keySet().equals(Set.of("action", "args", "message"))) throw new IOException("Sai cấu trúc phản hồi AI.");
        if (!value.get("action").isJsonPrimitive() || !value.getAsJsonPrimitive("action").isString()
                || !value.get("message").isJsonPrimitive() || !value.getAsJsonPrimitive("message").isString()) throw new IOException("Sai kiểu dữ liệu AI.");
        String action = value.get("action").getAsString();
        if (!ACTIONS.contains(action) || !value.get("args").isJsonObject()) throw new IOException("Hành động AI không hợp lệ.");
        JsonObject args = value.getAsJsonObject("args");
        Set<String> keys = switch (action) {
            case "goto" -> Set.of("x", "y", "z");
            case "mine" -> Set.of("block", "quantity");
            case "chest_open" -> Set.of("id");
            case "chest_take", "chest_put" -> Set.of("item", "quantity");
            default -> Set.of();
        };
        if (!args.keySet().equals(keys)) throw new IOException("Tham số AI không hợp lệ.");
        try {
            for (String key : keys) {
                if (key.equals("id")) {
                    if (!args.getAsJsonPrimitive(key).isString() || !args.get(key).getAsString().matches("(?i)CHEST-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new ArithmeticException();
                } else if (key.equals("block") || key.equals("item")) {
                    if (!args.getAsJsonPrimitive(key).isString() || !args.get(key).getAsString().matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) throw new ArithmeticException();
                } else {
                    if (!args.getAsJsonPrimitive(key).isNumber()) throw new ArithmeticException();
                    int number = args.get(key).getAsBigDecimal().intValueExact();
                    if (key.equals("quantity") && (number < 1 || number > 2304)) throw new ArithmeticException();
                    if ((key.equals("x") || key.equals("z")) && Math.abs((long)number) > 29999984) throw new ArithmeticException();
                }
            }
        } catch (RuntimeException failure) { throw new IOException("Giá trị tham số AI không hợp lệ."); }
    }
    private static JsonObject content(String role, String text) {
        JsonObject content = new JsonObject(); content.addProperty("role", role);
        JsonArray parts = new JsonArray(); JsonObject part = new JsonObject(); part.addProperty("text", text); parts.add(part);
        content.add("parts", parts); return content;
    }
    private static void save(Path file, JsonObject data) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, data.toString(), StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    public static Transport gemini() {
        return gemini(message -> {});
    }
    public static Transport gemini(java.util.function.Consumer<String> progress) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        Transport single = (model, key, body) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                    .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                    .header("x-goog-api-key", key).POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new ApiFailure(response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        };
        return withRetries(single, progress, Thread::sleep);
    }
    public static final class ApiFailure extends IOException {
        private final int status;
        public ApiFailure(int status) {
            this("Gemini", status);
        }
        public ApiFailure(String provider, int status) {
            super(provider + " HTTP " + status + ": " + switch (status) {
                case 503 -> "dịch vụ tạm quá tải hoặc không sẵn sàng. Hãy thử lại sau.";
                case 500, 502, 504 -> "lỗi tạm thời phía dịch vụ. Hãy thử lại sau.";
                case 429 -> "đã chạm giới hạn yêu cầu hoặc hạn mức. Hãy kiểm tra hạn mức và thử lại sau.";
                case 401, 403 -> "key hoặc quyền truy cập bị từ chối. Hãy kiểm tra cấu hình API.";
                case 404 -> "không tìm thấy mô hình hoặc endpoint. Hãy kiểm tra tên model.";
                case 400 -> "yêu cầu không hợp lệ. Hãy kiểm tra cấu hình key, model và dữ liệu gửi.";
                default -> "yêu cầu thất bại. Hãy kiểm tra cấu hình API.";
            });
            this.status = status;
        }
        public int status() { return status; }
    }
    @FunctionalInterface public interface RetryDelay { void sleep(long milliseconds) throws InterruptedException; }
    public static Transport withRetries(Transport single, java.util.function.Consumer<String> progress, RetryDelay delay) {
        return (model, key, body) -> {
            for (int attempt = 0; ; attempt++) {
                try { return single.generate(model, key, body); }
                catch (ApiFailure failure) {
                    if (attempt >= 2 || !Set.of(500, 502, 503, 504).contains(failure.status)) throw failure;
                    long milliseconds = 2000L << attempt;
                    progress.accept("AI: Dịch vụ gặp lỗi tạm thời HTTP " + failure.status
                            + "; tự thử lại " + (attempt + 1) + "/2 sau " + milliseconds / 1000 + " giây.");
                    delay.sleep(milliseconds);
                }
            }
        };
    }
}
