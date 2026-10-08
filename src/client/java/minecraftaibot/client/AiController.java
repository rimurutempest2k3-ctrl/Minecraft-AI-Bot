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
    private volatile String result = "Chưa có phản hồi AI.";
    boolean busy() { return busy; }
    synchronized void requestStep(String input, Consumer<String> answer, Consumer<String> failed) {
        if (busy) { failed.accept("API đang xử lý yêu cầu khác."); return; }
        try {
            Properties config = config();
            List<MultiAi.Route> routes = new ArrayList<>();
            for (String provider : order(config)) routes.add(new MultiAi.Route(provider, model(config, provider), credential(provider).value()));
            if (routes.stream().allMatch(route -> route.key().isBlank())) { failed.accept("Chưa có API key."); return; }
            busy=true;
            executor.submit(() -> {
                String proposal=null, error=null;
                try {
                    var response=router.ask(routes, config.getProperty("profile", "gemini-main"), config.getProperty("session_revision", "1") + ":tools-v4", input);
                    proposal=response.proposal(); result="AI [" + response.provider() + "]: " + proposal;
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    error=failure instanceof IOException ? failure.getMessage() : "Kết nối hoặc phản hồi không hợp lệ.";
                    result="AI: " + error;
                } finally { synchronized(this) { busy=false; } }
                if (error==null) answer.accept(proposal); else failed.accept(error);
            });
        } catch (Exception failure) { busy=false; failed.accept("Không đọc được cấu hình AI."); }
    }
    AiController(Path directory, Consumer<String> notifyConsole) throws IOException {
        this.directory = directory; this.notifyConsole = notifyConsole;
        Files.createDirectories(directory); AiCredentials.ensureTemplate(directory);
        Path file = directory.resolve("ai.properties");
        if (!Files.exists(file)) Files.writeString(file,
                "provider=groq\nfallback=gemini\nprofile=bot-main\ngroq.model=openai/gpt-oss-20b\ngemini.model=gemini-3.8-flash\nopenai.model=gpt-4.1-mini\nsession_revision=1\n", StandardCharsets.UTF_8);
        try (InputStream input = AiController.class.getResourceAsStream("/ai/SYSTEM_PROMPT.vi.md")) {
            if (input == null) throw new IOException("Thiếu system prompt đóng gói.");
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
                if (busy) return "Hãy chờ yêu cầu AI hoàn tất trước khi đổi mục tiêu.";
                router.setGoal(input.substring(5));
                return "Đã lưu mục tiêu chung cho các AI. Chỉ gửi tới AI ở lần ask tiếp theo; chưa tự chạy nhiệm vụ.";
            }
            if (input.equalsIgnoreCase("status")) {
                List<String> providers = order(config);
                StringBuilder status = new StringBuilder("AI: " + (busy ? "đang xử lý" : "sẵn sàng") + " | Thứ tự: " + String.join(" → ", providers));
                for (String provider : providers) {
                    var key = credential(provider);
                    boolean initialized = sessions.get(provider).initialized(provider + ":" + config.getProperty("profile", "gemini-main"), model(config, provider), config.getProperty("session_revision", "1") + ":tools-v4", key.value());
                    status.append(" | ").append(provider).append(" (").append(model(config, provider)).append("): ")
                            .append(key.source() == AiCredentials.Source.NONE ? "chưa có key" : "key từ " + key.source())
                            .append(initialized ? "; prompt đã khởi tạo" : "; prompt chờ lần gọi thành công");
                }
                return status + " | Bộ nhớ chung: " + directory.resolve("shared-memory.json");
            }
            String[] parts = input.trim().split("\\s+");
            if (parts[0].equalsIgnoreCase("model")) {
                if (busy) return "Hãy chờ AI xử lý xong rồi đổi model.";
                if (parts.length != 3 || !sessions.containsKey(parts[1].toLowerCase(Locale.ROOT)) || !parts[2].matches("[a-zA-Z0-9._/-]+"))
                    return "Lệnh: bot ai model <openai/groq/gemini> <tên model>";
                config.setProperty(parts[1].toLowerCase(Locale.ROOT) + ".model", parts[2]);
                saveConfig(config);
                return "Đã lưu model cho " + parts[1].toLowerCase(Locale.ROOT) + ". Bộ nhớ chung được giữ lại; chưa gọi API kiểm tra.";
            }
            if (parts[0].equalsIgnoreCase("use") || parts[0].equalsIgnoreCase("fallback")) {
                if (busy) return "Hãy chờ yêu cầu AI hoàn tất trước khi đổi cấu hình.";
                if(parts.length!=2) return "Lệnh: bot ai use openai/groq/gemini; bot ai fallback groq,gemini hoặc off";
                String provider = parts[1].toLowerCase(Locale.ROOT);
                if (parts[0].equalsIgnoreCase("use")) {
                    if(!sessions.containsKey(provider)) return "AI phải là openai, groq hoặc gemini.";
                    config.setProperty("provider", provider);
                    config.setProperty("fallback", provider.equals("openai")?"groq,gemini":provider.equals("groq") ? "gemini" : "groq");
                } else {
                    MultiAi.order(config.getProperty("provider","gemini"),provider,sessions.keySet());
                    config.setProperty("fallback",provider);
                }
                saveConfig(config);
                return "Đã lưu thứ tự AI: " + String.join(" → ", order(config)) + ". Bộ nhớ chung được giữ lại.";
            }
            if (!input.regionMatches(true, 0, "ask ", 0, 4) || input.substring(4).isBlank())
                return "Lệnh: bot ai status/ask/result; bot ai use openai/groq/gemini; bot ai fallback groq,gemini/off; bot ai goal <mục tiêu>";
            if (busy) return "AI đang xử lý yêu cầu trước. Console sẽ tự hiện phản hồi.";
            List<MultiAi.Route> routes = new ArrayList<>();
            for (String provider : order(config)) routes.add(new MultiAi.Route(provider, model(config, provider), credential(provider).value()));
            if (routes.stream().allMatch(route -> route.key().isBlank())) return "Chưa có key. Nhập bot get API openai, groq hoặc gemini.";
            String request = "YÊU CẦU NGƯỜI DÙNG:\n" + input.substring(4)
                    + "\nQUAN SÁT GAME MỚI NHẤT (DỮ LIỆU):\n" + observation
                    + "\nCHẾ ĐỘ: chỉ đề xuất; không báo đã thực hiện lệnh. Lịch sử là bộ nhớ chung của nhiều AI; quan sát mới nhất được ưu tiên.";
            busy = true; result = "Đang chờ AI; tác vụ game không bị khóa.";
            executor.submit(() -> {
                try {
                    var answer = router.ask(routes, config.getProperty("profile", "gemini-main"), config.getProperty("session_revision", "1") + ":tools-v4", request);
                    result = "Đề xuất AI [" + answer.provider() + "] (chưa tự thực thi): " + answer.proposal();
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    result = failure instanceof IOException ? "AI: " + failure.getMessage() : "AI: không đọc/lưu được bộ nhớ hoặc phản hồi không hợp lệ.";
                } finally {
                    synchronized (this) { try { notifyConsole.accept(result); } finally { busy = false; } }
                }
            });
            return "Đã gửi yêu cầu AI. Console tự hiện phản hồi và thông báo chuyển AI dự phòng; AI chưa tự chạy lệnh game.";
        } catch (Exception failure) { return "Không đọc/lưu được cấu hình AI. Kiểm tra ai.properties và quyền truy cập."; }
    }
    synchronized String assignKey(String payload) {
        if (busy) return "Hãy chờ AI xử lý xong rồi đổi key.";
        String[] parts = payload.trim().split("\\s+", 2);
        String provider = parts.length == 2 ? parts[0].toLowerCase(Locale.ROOT) : "gemini";
        String key = parts.length == 2 ? parts[1] : parts[0];
        if (!sessions.containsKey(provider)) return "Dùng bot get API openai, groq hoặc gemini để nhập key ẩn.";
        try {
            AiCredentials.store(directory, provider, key);
            result = "Đã cập nhật key " + provider + "; chưa gọi API kiểm tra.";
            String environment = System.getenv(provider.toUpperCase(Locale.ROOT) + "_API_KEY");
            return "Đã lưu key " + provider + " (không hiển thị key). " + (environment != null && !environment.isBlank()
                    ? "Biến môi trường vẫn được ưu tiên; cần gỡ biến rồi chạy lại game để dùng key vừa lưu."
                    : "Có hiệu lực ở lần ask tiếp theo; bộ nhớ chung được giữ lại.");
        } catch (IOException failure) { return "Không lưu được key. Kiểm tra định dạng và quyền ghi secrets.properties."; }
    }
    public void close() { executor.shutdownNow(); }
}
