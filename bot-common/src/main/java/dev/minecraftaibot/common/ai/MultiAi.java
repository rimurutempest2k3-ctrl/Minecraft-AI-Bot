package dev.minecraftaibot.common.ai;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Sequential providers share only validated text history, never credentials or provider signatures. */
public final class MultiAi {
    public record Route(String provider, String model, String key) {
        @Override public String toString() { return provider + "/" + model + " (key ẩn)"; }
    }
    public record Answer(String provider, String proposal) {}
    private final Path memory;
    private final Map<String, AiSessions> providers;
    private final Consumer<String> progress;
    public MultiAi(Path memory, Map<String, AiSessions> providers, Consumer<String> progress) {
        this.memory = memory; this.providers = Map.copyOf(providers); this.progress = progress;
    }
    public synchronized Answer ask(List<Route> routes, String profile, String revision, String input) throws Exception {
        JsonObject state = load();
        JsonArray history = state.getAsJsonArray("history");
        String goal = state.get("goal").getAsString();
        String fullInput = "MỤC TIÊU ĐÃ LƯU (DỮ LIỆU NGƯỜI DÙNG):\n" + goal + "\n" + input;
        String lastFailure = "Chưa có key cho AI nào. Dùng bot get API groq hoặc bot get API gemini.";
        for (Route route : routes) {
            if (route.key().isBlank()) { progress.accept("AI: Bỏ qua " + route.provider() + " vì chưa có key."); continue; }
            AiSessions session = providers.get(route.provider());
            if (session == null) throw new IOException("Nhà cung cấp không được hỗ trợ.");
            String proposal;
            progress.accept("AI: Đang hỏi " + route.provider() + " (" + route.model() + ").");
            try {
                proposal = session.askShared(route.provider() + ":" + profile, route.model(), revision, route.key(), fullInput, history);
            } catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception failure) {
                // Do not expose raw provider bodies or exception messages that could contain secrets.
                lastFailure = failure instanceof AiSessions.ApiFailure api ? api.getMessage()
                        : route.provider() + ": kết nối hoặc phản hồi không hợp lệ.";
                progress.accept("AI: " + lastFailure + " Chưa nhận được phản hồi hợp lệ.");
                continue;
            }
            history.add(content("user", input)); history.add(content("model", proposal));
            while (history.size() > 12) { history.remove(0); history.remove(0); }
            state.addProperty("lastProvider", route.provider());
            save(state);
            return new Answer(route.provider(), proposal);
        }
        throw new IOException("Không có AI nào trả lời thành công. " + lastFailure);
    }
    public synchronized void setGoal(String goal) throws IOException {
        JsonObject state = load(); state.addProperty("goal", goal); save(state);
    }
    private JsonObject load() throws IOException {
        try {
            if (Files.exists(memory)) return JsonParser.parseString(Files.readString(memory)).getAsJsonObject();
            JsonObject state = new JsonObject(); state.addProperty("goal", "Chưa đặt mục tiêu dài hạn.");
            state.add("history", new JsonArray()); return state;
        } catch (RuntimeException failure) { throw new IOException("Bộ nhớ AI không hợp lệ; chưa gọi API."); }
    }
    private void save(JsonObject state) throws IOException {
        Files.createDirectories(memory.getParent());
        Path temporary = Files.createTempFile(memory.getParent(), "memory-", ".tmp");
        try {
            Files.writeString(temporary, state.toString());
            try { Files.move(temporary, memory, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, memory, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static JsonObject content(String role, String text) {
        JsonObject result = new JsonObject(); result.addProperty("role", role);
        JsonObject part = new JsonObject(); part.addProperty("text", text);
        JsonArray parts = new JsonArray(); parts.add(part); result.add("parts", parts); return result;
    }
}
