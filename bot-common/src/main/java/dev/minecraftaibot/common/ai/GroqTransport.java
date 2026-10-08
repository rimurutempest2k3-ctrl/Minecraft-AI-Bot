package dev.minecraftaibot.common.ai;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** Groq JSON-object mode; the shared local validator enforces the action contract. */
public final class GroqTransport {
    private GroqTransport() {}
    public static JsonObject request(String model, JsonObject canonical) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_completion_tokens", 2048);
        if (model.startsWith("openai/gpt-oss-")) body.addProperty("reasoning_effort", "low");
        JsonObject format = new JsonObject(); format.addProperty("type", "json_object");
        body.add("response_format", format);
        JsonArray messages = new JsonArray();
        messages.add(message("system", text(canonical.getAsJsonObject("systemInstruction"))));
        for (JsonElement entry : canonical.getAsJsonArray("contents")) {
            JsonObject content = entry.getAsJsonObject();
            messages.add(message(content.get("role").getAsString().equals("model") ? "assistant" : "user", text(content)));
        }
        body.add("messages", messages); return body;
    }
    private static JsonObject message(String role, String text) {
        JsonObject value = new JsonObject(); value.addProperty("role", role); value.addProperty("content", text); return value;
    }
    private static String text(JsonObject content) {
        StringBuilder value = new StringBuilder();
        for (JsonElement element : content.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            if (part.has("text") && !(part.has("thought") && part.get("thought").getAsBoolean())) value.append(part.get("text").getAsString());
        }
        return value.toString();
    }
    public static JsonObject response(JsonObject raw) throws IOException {
        try {
            JsonObject choice = raw.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (!"stop".equals(choice.get("finish_reason").getAsString())) throw new IOException("Groq chưa trả về phản hồi hoàn chỉnh.");
            JsonObject content = new JsonObject(); content.addProperty("role", "model");
            JsonArray parts = new JsonArray(); JsonObject part = new JsonObject();
            part.addProperty("text", choice.getAsJsonObject("message").get("content").getAsString()); parts.add(part); content.add("parts", parts);
            JsonObject candidate = new JsonObject(); candidate.addProperty("finishReason", "STOP"); candidate.add("content", content);
            JsonArray candidates = new JsonArray(); candidates.add(candidate);
            JsonObject result = new JsonObject(); result.add("candidates", candidates); return result;
        } catch (RuntimeException invalid) { throw new IOException("Groq trả dữ liệu không hợp lệ."); }
    }
    public static AiSessions.Transport create(Consumer<String> progress) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return AiSessions.withRetries((model, key, canonical) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(request(model, canonical).toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> reply = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (reply.statusCode() != 200) throw new AiSessions.ApiFailure("Groq", reply.statusCode());
            return response(JsonParser.parseString(reply.body()).getAsJsonObject());
        }, progress, Thread::sleep);
    }
}
