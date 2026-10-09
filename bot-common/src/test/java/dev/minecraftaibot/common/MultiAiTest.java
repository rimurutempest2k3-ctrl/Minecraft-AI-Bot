package dev.minecraftaibot.common;

import com.google.gson.*;
import dev.minecraftaibot.common.ai.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class MultiAiTest {
    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("multi-ai-check");
        try {
            List<JsonObject> requests = new ArrayList<>(); int[] primary = {0}, fallback = {0};
            AiSessions groq = new AiSessions(directory.resolve("sessions"), "JSON PROMPT", (m,k,r) -> {
                primary[0]++; if (primary[0] == 1) throw new AiSessions.ApiFailure("Groq", 429);
                requests.add(r.deepCopy()); return valid();
            });
            AiSessions gemini = new AiSessions(directory.resolve("sessions"), "JSON PROMPT", (m,k,r) -> {
                fallback[0]++; requests.add(r.deepCopy()); return valid();
            });
            List<String> notices = new ArrayList<>();
            var providers = Map.of("groq", groq, "gemini", gemini);
            MultiAi router = new MultiAi(directory.resolve("memory.json"), providers, notices::add);
            List<MultiAi.Route> routes = List.of(new MultiAi.Route("groq", "llama-test", "fake-groq-secret"), new MultiAi.Route("gemini", "gemini-test", "fake-gemini-secret"));
            router.setGoal("Thu thêm 16 khối gỗ");
            check(router.ask(routes, "main", "1", "Bắt đầu").provider().equals("gemini"));
            check(primary[0] == 1 && fallback[0] == 1 && !notices.isEmpty());
            router = new MultiAi(directory.resolve("memory.json"), providers, notices::add);
            check(router.ask(routes, "main", "1", "Tiếp tục; đã có 8 gỗ").provider().equals("groq"));
            check(fallback[0] == 1); // No second call once the primary succeeds.
            JsonArray context = requests.getLast().getAsJsonArray("contents");
            check(context.size() == 3 && context.get(0).toString().contains("Bắt đầu"));
            check(context.get(2).toString().contains("16 khối gỗ") && context.get(2).toString().contains("8 gỗ"));
            for (int index = 0; index < 8; index++) router.ask(routes, "main", "1", "Quan sát " + index);
            JsonObject memory = JsonParser.parseString(Files.readString(directory.resolve("memory.json"))).getAsJsonObject();
            check(memory.getAsJsonArray("history").size() == 12 && memory.get("goal").getAsString().contains("16"));
            String stored = Files.readString(directory.resolve("memory.json"));
            check(!stored.contains("fake-groq-secret") && !stored.contains("fake-gemini-secret"));
            var bad = new AiSessions(directory.resolve("sessions"), "PROMPT", (m,k,r) -> { throw new IOException(k); });
            var failed = new MultiAi(directory.resolve("memory.json"), Map.of("groq", bad, "gemini", bad), notices::add);
            try { failed.ask(routes, "main", "1", "KHÔNG LƯU"); throw new AssertionError(); }
            catch (IOException expected) { check(!expected.getMessage().contains("fake-")); }
            check(stored.equals(Files.readString(directory.resolve("memory.json"))));
            check(notices.stream().noneMatch(message -> message.contains("fake-")));
            int previousFallback = fallback[0];
            var interrupted = new AiSessions(directory.resolve("sessions"), "PROMPT", (m,k,r) -> { throw new InterruptedException(); });
            try {
                new MultiAi(directory.resolve("memory.json"), Map.of("groq", interrupted, "gemini", gemini), notices::add).ask(routes, "main", "1", "Dừng");
                throw new AssertionError();
            } catch (InterruptedException expected) { check(fallback[0] == previousFallback); }
            check(stored.equals(Files.readString(directory.resolve("memory.json"))));
            int before = primary[0];
            check(router.ask(List.of(new MultiAi.Route("gemini", "test", ""), routes.get(0)), "main", "1", "Missing key").provider().equals("groq"));
            check(primary[0] == before + 1);
            var invalid = new AiSessions(directory.resolve("sessions"), "PROMPT", (m,k,r) -> AiTransports.response(JsonParser.parseString("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}").getAsJsonObject()));
            check(new MultiAi(directory.resolve("memory.json"), Map.of("groq", invalid, "gemini", gemini), notices::add).ask(routes, "main", "1", "JSON sai").provider().equals("gemini"));
            JsonObject groqBody = AiTransports.request("llama-test", requests.getLast());
            check(groqBody.getAsJsonObject("response_format").get("type").getAsString().equals("json_object"));
            check(groqBody.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString().equals("system"));
            check(!groqBody.toString().contains("thoughtSignature"));
            try { AiTransports.response(JsonParser.parseString("{\"choices\":[{\"finish_reason\":\"length\"}]}").getAsJsonObject()); throw new AssertionError(); }
            catch (IOException expected) { }
            AiCredentials.store(directory, "gemini", "fake-gemini-secret");
            AiCredentials.store(directory, "groq", "fake-groq-secret");
            check(AiCredentials.load(directory, "gemini", null).value().equals("fake-gemini-secret"));
            check(AiCredentials.load(directory, "groq", null).value().equals("fake-groq-secret"));
            check(AiCredentials.load(directory, "groq", "environment-groq").value().equals("environment-groq"));
            check(!routes.get(0).toString().contains("fake-groq-secret"));
            AiCredentials.store(directory,"openai","fake-openai-secret");
            check(AiCredentials.load(directory,"openai",null).value().equals("fake-openai-secret"));
            check(AiCredentials.load(directory,"openai","environment-openai").value().equals("environment-openai"));
            check(AiCredentials.load(directory,"gemini",null).value().equals("fake-gemini-secret"));
            check(MultiAi.order("openai","groq,gemini,groq",Set.of("openai","groq","gemini")).equals(List.of("openai","groq","gemini")));
            check(MultiAi.order("openai","off",Set.of("openai","groq","gemini")).equals(List.of("openai")));
            for(String badOrder:new String[]{"unknown","groq,","off,gemini"}) try {MultiAi.order("openai",badOrder,Set.of("openai","groq","gemini"));throw new AssertionError();}catch(IOException expected) {}
            JsonObject openaiBody=AiTransports.OpenAI.request("gpt-4.1-mini",requests.getLast());
            check(!openaiBody.get("store").getAsBoolean());
            check(openaiBody.getAsJsonObject("text").getAsJsonObject("format").get("type").getAsString().equals("json_object"));
            check(openaiBody.get("instructions").getAsString().contains("JSON"));
            check(openaiBody.getAsJsonArray("input").size()==requests.getLast().getAsJsonArray("contents").size());
            JsonObject response=JsonParser.parseString("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"action\\\":\\\"inventory\\\",\\\"args\\\":{},\\\"message\\\":\\\"Xem túi đồ\\\"}\"}]}]}").getAsJsonObject();
            check(AiTransports.OpenAI.response(response).has("candidates"));
            var incomplete=response.deepCopy();incomplete.addProperty("status","incomplete");
            try {AiTransports.OpenAI.response(incomplete);throw new AssertionError();}catch(IOException expected) {}
            var refused=response.deepCopy();refused.getAsJsonArray("output").get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().addProperty("type","refusal");
            try {AiTransports.OpenAI.response(refused);throw new AssertionError();}catch(IOException expected) {}
            AiSessions openai=new AiSessions(directory.resolve("sessions"),"JSON PROMPT",(m,k,r)-> {
                check(k.equals("fake-openai-secret"));check(!r.toString().contains("fake-groq-secret"));
                return AiTransports.OpenAI.response(response);
            });
            var thirdRouter=new MultiAi(directory.resolve("memory.json"),Map.of("groq",bad,"gemini",bad,"openai",openai),notices::add);
            var threeRoutes=List.of(routes.get(0),routes.get(1),new MultiAi.Route("openai","gpt-4.1-mini","fake-openai-secret"));
            check(thirdRouter.ask(threeRoutes,"main","1","Tiếp tục bằng AI thứ ba").provider().equals("openai"));
            check(!Files.readString(directory.resolve("memory.json")).contains("fake-openai-secret"));
            check(openai.initialized("openai:main","gpt-4.1-mini","1","fake-openai-secret"));
            System.out.println("All multi-provider fallback, shared memory, restart, goal retention, invalid response and credential isolation checks passed.");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
    private static JsonObject valid() throws IOException {
        return AiTransports.response(JsonParser.parseString("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"action\\\":\\\"inventory\\\",\\\"args\\\":{},\\\"message\\\":\\\"Xem túi đồ\\\"}\"}}]}").getAsJsonObject());
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Multi AI check failed"); }
}
