package minecraftaibot.client;

import dev.minecraftaibot.common.ai.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class AiController implements AutoCloseable {
    private final Path directory;
    private final Map<String, AiSessions> sessions;
    private final MultiAi router;
    private final Consumer<String> notifyConsole;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "minecraft-ai-api"); thread.setDaemon(true); return thread;
    });
    private volatile boolean busy;
    private volatile String result = "No AI response.";
    boolean busy() { return busy; }
    synchronized com.google.gson.JsonObject webSettings() {
        var result=new com.google.gson.JsonObject();
        try {
            var config=config();result.addProperty("provider",config.getProperty("provider","groq"));
            result.addProperty("fallback",config.getProperty("fallback","gemini"));
            var providers=new com.google.gson.JsonObject();
            for(String provider:java.util.List.of("groq","gemini","openai")) {
                var entry=new com.google.gson.JsonObject();var credential=credential(provider);
                entry.addProperty("configured",credential.source()!=AiCredentials.Source.NONE);
                entry.addProperty("source",credential.source().name());entry.addProperty("model",model(config,provider));
                providers.add(provider,entry);
            }
            result.add("providers",providers);
        } catch(IOException failure) {result.addProperty("error","Cannot read AI configuration.");}
        return result;
    }
    synchronized void requestStep(String input, Consumer<String> answer, Consumer<String> failed) {
        if (busy) { failed.accept("API is processing another request."); return; }
        try {
            Properties config = config();
            List<MultiAi.Route> routes = new ArrayList<>();
            for (String provider : order(config)) routes.add(new MultiAi.Route(provider, model(config, provider), credential(provider).value()));
            if (routes.stream().allMatch(route -> route.key().isBlank())) { failed.accept("No API key configured."); return; }
            busy=true;
            executor.submit(() -> {
                String proposal=null, error=null;
                try {
                    var response=router.ask(routes, config.getProperty("profile", "gemini-main"), config.getProperty("session_revision", "1") + ":tools-v5", input);
                    proposal=response.proposal(); result="AI [" + response.provider() + "]: " + proposal;
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    error=failure instanceof IOException ? failure.getMessage() : "Invalid connection or response.";
                    result="AI: " + error;
                } finally { synchronized(this) { busy=false; } }
                if (error==null) answer.accept(proposal); else failed.accept(error);
            });
        } catch (Exception failure) { busy=false; failed.accept("Cannot read AI configuration."); }
    }
    AiController(Path directory, Consumer<String> notifyConsole) throws IOException {
        this.directory = directory; this.notifyConsole = notifyConsole;
        Files.createDirectories(directory); AiCredentials.ensureTemplate(directory);
        Path file = directory.resolve("ai.properties");
        if (!Files.exists(file)) Files.writeString(file,
                "provider=groq\nfallback=gemini\nprofile=bot-main\ngroq.model=openai/gpt-oss-20b\ngemini.model=gemini-3.8-flash\nopenai.model=gpt-4.1-mini\nsession_revision=1\n", StandardCharsets.UTF_8);
        try (InputStream input = AiController.class.getResourceAsStream("/ai/SYSTEM_PROMPT.vi.md")) {
            if (input == null) throw new IOException("Bundled system prompt missing.");
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            sessions = Map.of("gemini", new AiSessions(directory.resolve("sessions"), prompt, AiSessions.gemini(notifyConsole)),
                    "groq", new AiSessions(directory.resolve("sessions"), prompt, AiTransports.create(notifyConsole)),
                    "openai", new AiSessions(directory.resolve("sessions"), prompt, AiTransports.OpenAI.create(notifyConsole)));
            router = new MultiAi(directory.resolve("shared-memory.json"), sessions, notifyConsole);
        }
    }
    private Properties config() throws IOException {
        Properties value = new Properties();
        try (Reader reader = Files.newBufferedReader(directory.resolve("ai.properties"), StandardCharsets.UTF_8)) { value.load(reader); }
        return value;
    }
    private static String model(Properties config, String provider) {
        if(provider.equals("openai")) return config.getProperty("openai.model","gpt-4.1-mini");
        return config.getProperty(provider + ".model", provider.equals("groq") ? "openai/gpt-oss-20b" : config.getProperty("model", "gemini-3.8-flash"));
    }
    private AiCredentials.Credential credential(String provider) throws IOException {
        return AiCredentials.load(directory, provider, System.getenv(provider.toUpperCase(Locale.ROOT) + "_API_KEY"));
    }
    private List<String> order(Properties config) throws IOException {
        String primary = config.getProperty("provider", "gemini").trim().toLowerCase(Locale.ROOT);
        String fallback = config.getProperty("fallback", primary.equals("groq") ? "gemini" : "groq").trim().toLowerCase(Locale.ROOT);
        return MultiAi.order(primary,fallback,sessions.keySet());
    }
    private void saveConfig(Properties value) throws IOException {
        Path file = directory.resolve("ai.properties");
        Path temporary = Files.createTempFile(directory, "ai-config-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { value.store(writer, "Minecraft AI Bot providers"); }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    synchronized String execute(String input, String observation) {
        if (input.equalsIgnoreCase("result")) return result;
        try {
            Properties config = config();
            if (input.regionMatches(true, 0, "goal ", 0, 5) && !input.substring(5).isBlank()) {
                if (busy) return "Wait for the AI request to finish before changing the goal.";
                router.setGoal(input.substring(5));
                return "Saved the shared AI goal. Sent on the next ask call; no task started automatically.";
            }
            if (input.equalsIgnoreCase("status")) {
                List<String> providers = order(config);
                StringBuilder status = new StringBuilder("AI: " + (busy ? "processing" : "ready") + " | Order: " + String.join(" → ", providers));
                for (String provider : providers) {
                    var key = credential(provider);
                    boolean initialized = sessions.get(provider).initialized(provider + ":" + config.getProperty("profile", "gemini-main"), model(config, provider), config.getProperty("session_revision", "1") + ":tools-v5", key.value());
                    status.append(" | ").append(provider).append(" (").append(model(config, provider)).append("): ")
                            .append(key.source() == AiCredentials.Source.NONE ? "no key" : "key from " + key.source())
                            .append(initialized ? "; prompt initialized" : "; prompt awaiting a successful call");
                }
                return status + " | Shared memory: " + directory.resolve("shared-memory.json");
            }
            String[] parts = input.trim().split("\\s+");
            if (parts[0].equalsIgnoreCase("model")) {
                if (busy) return "Wait for AI to finish before changing the model.";
                if (parts.length != 3 || !sessions.containsKey(parts[1].toLowerCase(Locale.ROOT)) || !parts[2].matches("[a-zA-Z0-9._/-]+"))
                    return "Command: bot ai model <openai/groq/gemini> <model name>";
                config.setProperty(parts[1].toLowerCase(Locale.ROOT) + ".model", parts[2]);
                saveConfig(config);
                return "Saved model for " + parts[1].toLowerCase(Locale.ROOT) + ". Shared memory is preserved; no API validation call made.";
            }
            if (parts[0].equalsIgnoreCase("use") || parts[0].equalsIgnoreCase("fallback")) {
                if (busy) return "Wait for the AI request to finish before changing configuration.";
                if(parts.length!=2) return "Commands: bot ai use openai/groq/gemini; bot ai fallback groq,gemini or off";
                String provider = parts[1].toLowerCase(Locale.ROOT);
                if (parts[0].equalsIgnoreCase("use")) {
                    if(!sessions.containsKey(provider)) return "AI provider must be openai, groq or gemini.";
                    config.setProperty("provider", provider);
                    config.setProperty("fallback", provider.equals("openai")?"groq,gemini":provider.equals("groq") ? "gemini" : "groq");
                } else {
                    MultiAi.order(config.getProperty("provider","gemini"),provider,sessions.keySet());
                    config.setProperty("fallback",provider);
                }
                saveConfig(config);
                return "Saved AI order: " + String.join(" → ", order(config)) + ". Shared memory is preserved.";
            }
            if (!input.regionMatches(true, 0, "ask ", 0, 4) || input.substring(4).isBlank())
                return "Commands: bot ai status/ask/result; bot ai use openai/groq/gemini; bot ai fallback groq,gemini/off; bot ai goal <goal>";
            if (busy) return "AI is processing the previous request. The console will show the response automatically.";
            List<MultiAi.Route> routes = new ArrayList<>();
            for (String provider : order(config)) routes.add(new MultiAi.Route(provider, model(config, provider), credential(provider).value()));
            if (routes.stream().allMatch(route -> route.key().isBlank())) return "No key. Enter bot get API openai, groq or gemini.";
            String request = "USER REQUEST:\n" + input.substring(4)
                    + "\nLATEST GAME OBSERVATION (DATA):\n" + observation
                    + "\nMODE: suggestions only; do not claim commands were executed. History is shared across AI providers; prioritize the latest observation.";
            busy = true; result = "Waiting for AI; game actions are not locked.";
            executor.submit(() -> {
                try {
                    var answer = router.ask(routes, config.getProperty("profile", "gemini-main"), config.getProperty("session_revision", "1") + ":tools-v5", request);
                    result = "AI suggestion [" + answer.provider() + "] (not executed automatically): " + answer.proposal();
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    result = failure instanceof IOException ? "AI: " + failure.getMessage() : "AI: cannot read/save memory, or response is invalid.";
                } finally {
                    synchronized (this) { try { notifyConsole.accept(result); } finally { busy = false; } }
                }
            });
            return "AI request sent. Console shows the response and fallback changes automatically; AI has not executed game commands.";
        } catch (Exception failure) { return "Cannot read/save AI configuration. Check ai.properties and permissions."; }
    }
    synchronized String assignKey(String payload) {
        if (busy) return "Wait for AI to finish before changing the key.";
        String[] parts = payload.trim().split("\\s+", 2);
        String provider = parts.length == 2 ? parts[0].toLowerCase(Locale.ROOT) : "gemini";
        String key = parts.length == 2 ? parts[1] : parts[0];
        if (!sessions.containsKey(provider)) return "Use bot get API openai, groq or gemini for hidden key input.";
        try {
            AiCredentials.store(directory, provider, key);
            result = "Updated key " + provider + "; no API validation call made.";
            String environment = System.getenv(provider.toUpperCase(Locale.ROOT) + "_API_KEY");
            return "Saved key " + provider + " (key not displayed). " + (environment != null && !environment.isBlank()
                    ? "Environment variables take priority; remove the variable and restart the game to use the saved key."
                    : "Applies to the next ask call; shared memory is preserved.");
        } catch (IOException failure) { return "Could not save key. Check format and write permissions for secrets.properties."; }
    }
    public void close() { executor.shutdownNow(); }
}
