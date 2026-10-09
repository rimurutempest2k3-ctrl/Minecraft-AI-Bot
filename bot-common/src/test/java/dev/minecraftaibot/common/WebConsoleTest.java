package dev.minecraftaibot.common;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

public final class WebConsoleTest {
    public static void run() throws Exception {
        Path directory=Files.createTempDirectory("bot-web-test"),endpoint=directory.resolve("url.txt");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        TaskPresets.initialize(Files.createTempDirectory("bot-web-task-import").resolve("local-tasks.json"));
        try(var server=new WebConsole(endpoint,0,command->{calls.incrementAndGet();if(command.equals("fail")) throw new IllegalStateException("private diagnostic");return "Đã nhận: "+command;})) {
            String url=server.url();
            check(Files.readString(endpoint).trim().equals(url));
            var noAuth=client.send(HttpRequest.newBuilder(URI.create(url+"/status")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(noAuth.statusCode()==401 && calls.get()==0);
            var crossSite=client.send(HttpRequest.newBuilder(URI.create(url)).header("Sec-Fetch-Site","cross-site").GET().build(),HttpResponse.BodyHandlers.ofString());
            check(crossSite.statusCode()==403);
            var page=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(page.statusCode()==200 && page.body().contains("Minecraft AI Bot"));
            String cookie=page.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            for(String lang:java.util.List.of("vi","en")) {
                var denied=client.send(HttpRequest.newBuilder(URI.create(url+"/lang."+lang)).GET().build(),HttpResponse.BodyHandlers.ofString());
                check(denied.statusCode()==401);
                var catalog=client.send(HttpRequest.newBuilder(URI.create(url+"/lang."+lang)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                check(catalog.statusCode()==200);
                check(com.google.gson.JsonParser.parseString(catalog.body()).getAsJsonObject().get("language").getAsString().equals(lang));
            }
            var script=client.send(HttpRequest.newBuilder(URI.create(url+"/app.js")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(script.statusCode()==200 && script.body().contains("EventSource"));
            var status=client.send(HttpRequest.newBuilder(URI.create(url+"/status")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(status.statusCode()==200 && calls.get()==1);
            var badOrigin=client.send(HttpRequest.newBuilder(URI.create(url+"/command")).header("Cookie",cookie).header("Origin","http://example.com")
                    .header("Content-Type","application/json").header("X-Bot-Request","1").POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"bot start\"}")).build(),HttpResponse.BodyHandlers.ofString());
            check(badOrigin.statusCode()==403 && calls.get()==1);
            var good=client.send(command(url,cookie,"{\"command\":\"bot start\"}"),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(good.statusCode()==200 && good.body().contains("bot start") && calls.get()==2);
            var invalid=client.send(command(url,cookie,"{\"command\":123}"),HttpResponse.BodyHandlers.ofString());
            check(invalid.statusCode()==400 && calls.get()==2);
            var large=client.send(command(url,cookie,"x".repeat(4097)),HttpResponse.BodyHandlers.ofString());
            check(large.statusCode()==413 && calls.get()==2);
            String task="{\"version\":1,\"title\":\"Imported task\",\"steps\":[{\"label\":\"Wood\",\"item\":\"minecraft:oak_log\",\"count\":4,\"action\":\"mine\"}]}";
            var deniedImport=client.send(importTask(url,"","test.json",task),HttpResponse.BodyHandlers.ofString());
            check(deniedImport.statusCode()==401);
            var imported=client.send(importTask(url,cookie,"test.json",task),HttpResponse.BodyHandlers.ofString());
            check(imported.statusCode()==201 && imported.body().contains("test.taskbot") && calls.get()==2);
            check(TaskPresets.workflow("test.taskbot").plan().steps().getFirst().count()==4);
            var duplicate=client.send(importTask(url,cookie,"test.taskbot",task.replace("4","8")),HttpResponse.BodyHandlers.ofString());
            check(duplicate.statusCode()==400 && TaskPresets.workflow("test.taskbot").plan().steps().getFirst().count()==4);
            for(String name:java.util.List.of("../escaped.taskbot","bad.exe")) {
                var unsafe=client.send(importTask(url,cookie,name,task),HttpResponse.BodyHandlers.ofString());check(unsafe.statusCode()==400);
            }
            var badTask=client.send(importTask(url,cookie,"invalid.taskbot",task.replace("mine","shell")),HttpResponse.BodyHandlers.ofString());
            check(badTask.statusCode()==400 && !Files.exists(TaskPresets.workflowDirectory().resolve("invalid.taskbot")));
            var hugeTask=client.send(importTask(url,cookie,"huge.taskbot"," ".repeat(262145)),HttpResponse.BodyHandlers.ofString());
            check(hugeTask.statusCode()==413);
            var crossImport=client.send(HttpRequest.newBuilder(URI.create(url+"/tasks/import")).header("Cookie",cookie)
                    .header("Origin","http://example.com").header("Content-Type","application/json").header("X-Bot-Request","1")
                    .header("X-Bot-Filename","cross.taskbot").POST(HttpRequest.BodyPublishers.ofString(task)).build(),HttpResponse.BodyHandlers.ofString());
            check(crossImport.statusCode()==403 && !Files.exists(TaskPresets.workflowDirectory().resolve("cross.taskbot")));
            var stream=client.send(HttpRequest.newBuilder(URI.create(url+"/events")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            check(stream.statusCode()==200);
            try(var input=stream.body();var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));var worker=Executors.newVirtualThreadPerTaskExecutor()) {
                var observed=worker.submit(()->{
                    var received=new java.util.HashMap<String,String>();String line;
                    while((line=reader.readLine())!=null) if(line.startsWith("data: ")) {
                        var event=com.google.gson.JsonParser.parseString(line.substring(6)).getAsJsonObject();
                        String message=event.get("text").getAsString();
                        if(message.startsWith("Kiểm tra kênh")) received.put(message,event.get("channel").getAsString());
                        if(received.size()==4) return received;
                    }
                    return received;
                });
                server.publish("Kiểm tra kênh hoạt động");
                server.publish("Kiểm tra kênh chat",WebConsole.Channel.CHAT);
                server.publish("Kiểm tra kênh AI",WebConsole.Channel.AI);
                server.publish("Kiểm tra kênh lỗi",WebConsole.Channel.ERROR);
                var received=observed.get(3,TimeUnit.SECONDS);
                check(received.get("Kiểm tra kênh hoạt động").equals("ACTIVITY"));
                check(received.get("Kiểm tra kênh chat").equals("CHAT"));
                check(received.get("Kiểm tra kênh AI").equals("AI"));
                check(received.get("Kiểm tra kênh lỗi").equals("ERROR"));
            }
            check(calls.get()==2); // Reading/reconnecting logs never repeats an action.
            var failure=client.send(command(url,cookie,"{\"command\":\"fail\"}"),HttpResponse.BodyHandlers.ofString());
            check(failure.statusCode()==503 && !failure.body().contains("private diagnostic"));
        } finally {check(!Files.exists(endpoint));Files.deleteIfExists(directory);}
        try(var server=new WebConsole(endpoint,0,command->{
            if(!command.equals("@web snapshot")) throw new AssertionError("Unexpected snapshot command");
            return "{\"name\":\"Bot kiểm tra\",\"features\":{\"survival\":true},\"ai\":{\"providers\":{\"groq\":{\"configured\":true}}},\"inventory\":[],\"chests\":{\"entries\":[]}}";
        })) {
            String url=server.url();
            var noAuth=client.send(HttpRequest.newBuilder(URI.create(url+"/data")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(noAuth.statusCode()==401);
            var page=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
            String cookie=page.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            var data=client.send(HttpRequest.newBuilder(URI.create(url+"/data")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(data.statusCode()==200 && data.body().contains("Bot kiểm tra") && !data.body().contains("message"));
            check(com.google.gson.JsonParser.parseString(data.body()).getAsJsonObject().getAsJsonObject("features").get("survival").getAsBoolean());
            var internal=client.send(command(url,cookie,"{\"command\":\"@web snapshot\"}"),HttpResponse.BodyHandlers.ofString());
            check(internal.statusCode()==400);
        } finally {Files.deleteIfExists(endpoint);}
        System.out.println("All web console authentication, origin, commands, structured snapshots, UTF-8, SSE and lifecycle checks passed.");
    }
    private static HttpRequest command(String url,String cookie,String body) {
        return HttpRequest.newBuilder(URI.create(url+"/command")).header("Cookie",cookie).header("Origin",url)
                .header("Content-Type","application/json").header("X-Bot-Request","1")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static HttpRequest importTask(String url,String cookie,String filename,String body) {
        return HttpRequest.newBuilder(URI.create(url+"/tasks/import")).header("Cookie",cookie).header("Origin",url)
                .header("Content-Type","application/json").header("X-Bot-Request","1")
                .header("X-Bot-Filename",java.net.URLEncoder.encode(filename,StandardCharsets.UTF_8))
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
    }
    private static void check(boolean condition) {if(!condition) throw new AssertionError("Web console check failed");}
}
