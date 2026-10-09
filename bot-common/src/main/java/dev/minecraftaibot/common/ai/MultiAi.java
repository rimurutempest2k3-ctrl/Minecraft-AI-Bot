package dev.minecraftaibot.common.ai;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Sequential providers share only validated text history, never credentials or provider signatures. */
public final class MultiAi {
    public record Route(String provider, String model, String key) {
        @Override public String toString() { return provider + "/" + model + " (hidden key)"; }
    }
    public record Answer(String provider, String proposal) {}
    public static List<String> order(String primary,String fallback,Set<String> supported) throws IOException {
        if(!supported.contains(primary)) throw new IOException("Invalid primary AI provider.");
        LinkedHashSet<String> order=new LinkedHashSet<>();order.add(primary);
        if(!fallback.equals("off")) for(String provider:fallback.split(",",-1)) {
            provider=provider.trim();if(!supported.contains(provider)) throw new IOException("Invalid fallback provider. Use openai, groq, gemini or off.");order.add(provider);
        }
        return List.copyOf(order);
    }
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
        String fullInput = "SAVED GOAL (USER DATA):\n" + goal + "\n" + input;
        String lastFailure = "No provider has a key. Use bot get API openai, groq or gemini.";
        for (Route route : routes) {
            if (route.key().isBlank()) { progress.accept("AI: Skipping " + route.provider() + " because no key is configured."); continue; }
            AiSessions session = providers.get(route.provider());
            if (session == null) throw new IOException("Unsupported provider.");
            String proposal;
            progress.accept("AI: Asking " + route.provider() + " (" + route.model() + ").");
            try {
                proposal = session.askShared(route.provider() + ":" + profile, route.model(), revision, route.key(), fullInput, history);
            } catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception failure) {
                // Do not expose raw provider bodies or exception messages that could contain secrets.
                lastFailure = failure instanceof AiSessions.ApiFailure api ? api.getMessage()
                        : route.provider() + ": invalid connection or response.";
                progress.accept("AI: " + lastFailure + " No valid response received yet.");
                continue;
            }
            history.add(content("user", input)); history.add(content("model", proposal));
            while (history.size() > 12) { history.remove(0); history.remove(0); }
            state.addProperty("lastProvider", route.provider());
            save(state);
            return new Answer(route.provider(), proposal);
        }
        throw new IOException("No AI provider responded successfully. " + lastFailure);
    }
    public synchronized void setGoal(String goal) throws IOException {
        JsonObject state = load(); state.addProperty("goal", goal); save(state);
    }
    private JsonObject load() throws IOException {
        try {
            if (Files.exists(memory)) return JsonParser.parseString(Files.readString(memory)).getAsJsonObject();
            JsonObject state = new JsonObject(); state.addProperty("goal", "No long-term goal set.");
            state.add("history", new JsonArray()); return state;
        } catch (RuntimeException failure) { throw new IOException("Invalid AI memory; no API call made."); }
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
