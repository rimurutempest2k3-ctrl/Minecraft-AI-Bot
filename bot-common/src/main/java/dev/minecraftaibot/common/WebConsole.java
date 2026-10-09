package dev.minecraftaibot.common;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Loopback-only browser console. Network workers never access Minecraft directly. */
public final class WebConsole implements AutoCloseable {
    public enum Channel { ACTIVITY, RESPONSE, AI, CHAT, ERROR }
    private record Event(long id,Channel channel,String text) {}
    private final HttpServer server;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Function<String,String> dispatch;
    private final Gson json=new Gson();
    private final ArrayDeque<Event> events=new ArrayDeque<>();
    private final Semaphore streams=new Semaphore(4),commands=new Semaphore(1);
    private final String session;
    private final Path endpoint;
    private long sequence;
    private volatile boolean closed;

    public WebConsole(Path endpoint,int port,Function<String,String> dispatch) throws IOException {
        this.endpoint=endpoint;this.dispatch=dispatch;
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);session=HexFormat.of().formatHex(random);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);
        try {
            server.setExecutor(workers);server.createContext("/",this::handle);
            Files.createDirectories(endpoint.toAbsolutePath().getParent());
            Files.writeString(endpoint,url()+"\n",StandardCharsets.UTF_8);
            server.start();
        } catch(IOException|RuntimeException failure) {server.stop(0);workers.shutdownNow();throw failure;}
    }
    public String url() {return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void publish(String message) {
        publish(message,message!=null && (message.matches("(?s)^(?:Survival: |Local: )?Cannot .*" ) || message.startsWith("AI: ") && message.contains("HTTP")
                || message.contains("Cannot process") || message.contains("Action ended;")
                || message.contains("Cannot save") || message.startsWith("AI task stopped:"))?Channel.ERROR:Channel.ACTIVITY);
    }
    public void publish(String message,Channel channel) {
        if(message==null) return;
        synchronized(events) {
            if(closed) return;
            events.addLast(new Event(++sequence,channel,message.substring(0,Math.min(message.length(),32768))));
            while(events.size()>500) events.removeFirst();
            events.notifyAll();
        }
    }
    private boolean authenticated(HttpExchange exchange) {
        var cookies=exchange.getRequestHeaders().getFirst("Cookie");
        return cookies!=null && Arrays.stream(cookies.split(";")).anyMatch(c->c.trim().equals("bot_session="+session));
    }
    private void handle(HttpExchange exchange) throws IOException {
        try(exchange) {
            var headers=exchange.getRequestHeaders();
            if(!("127.0.0.1:"+server.getAddress().getPort()).equals(headers.getFirst("Host"))) {reply(exchange,403,"Invalid address.");return;}
            String site=headers.getFirst("Sec-Fetch-Site");
            if(site!=null && !site.equals("none") && !site.equals("same-origin")) {reply(exchange,403,"Only direct access from the local page is allowed.");return;}
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            if(method.equals("GET") && (path.equals("/") || path.equals("/app.js") || path.equals("/i18n.js"))) {
                if(path.equals("/")) exchange.getResponseHeaders().add("Set-Cookie","bot_session="+session+"; HttpOnly; SameSite=Strict; Path=/");
                try(var asset=WebConsole.class.getResourceAsStream(path.equals("/")?"/web/index.html":"/web"+path)) {
                    if(asset==null) {reply(exchange,404,"Interface not found.");return;}
                    byte[] body=asset.readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type",path.equals("/")?"text/html; charset=utf-8":"text/javascript; charset=utf-8");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
                }
            }
            if(!authenticated(exchange)) {reply(exchange,401,"Open the main page to connect.");return;}
            if(method.equals("GET") && (path.equals("/lang.vi") || path.equals("/lang.en"))) {
                byte[] body=I18n.catalogJson(path.substring(6)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
            }
            if(method.equals("GET") && path.equals("/events")) {stream(exchange);return;}
            if(method.equals("GET") && path.equals("/data")) {
                try {
                    var snapshot=JsonParser.parseString(dispatch.apply("@web snapshot")).getAsJsonObject();
                    byte[] body=json.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } catch(RuntimeException failure) {reply(exchange,503,"Bot data not available yet.");}
                return;
            }
            if(method.equals("GET") && path.equals("/status")) {
                try {reply(exchange,200,dispatch.apply("bot info"));}
                catch(RuntimeException failure) {reply(exchange,503,"Game state not available yet.");}
                return;
            }
            if(method.equals("POST") && path.equals("/tasks/import")) {
                if(!url().equals(headers.getFirst("Origin")) || !"1".equals(headers.getFirst("X-Bot-Request"))
                        || !"application/json".equals(headers.getFirst("Content-Type"))) {reply(exchange,403,"Invalid request origin.");return;}
                byte[] body=exchange.getRequestBody().readNBytes(262145);
                if(body.length>262144) {reply(exchange,413,"Task file exceeds 256 KB");return;}
                String filename;
                try {filename=URLDecoder.decode(Objects.requireNonNull(headers.getFirst("X-Bot-Filename")),StandardCharsets.UTF_8);}
                catch(RuntimeException invalid) {reply(exchange,400,"Invalid task filename.");return;}
                if(!commands.tryAcquire()) {reply(exchange,429,"Previous command is processing; this command was not sent.");return;}
                try {
                    TaskPresets.Workflow imported;
                    try {imported=TaskPresets.importWorkflow(filename,new String(body,StandardCharsets.UTF_8));}
                    catch(IllegalArgumentException invalid) {reply(exchange,400,invalid.getMessage());return;}
                    catch(IOException failure) {reply(exchange,503,"Cannot save task file. Check the task directory.");return;}
                    String message="Task imported: "+imported.filename()+". Select it and click Run when ready.";
                    publish(message,Channel.RESPONSE);
                    byte[] response=json.toJson(Map.of("filename",imported.filename(),"message",message)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                    exchange.sendResponseHeaders(201,response.length);exchange.getResponseBody().write(response);
                } finally {commands.release();}
                return;
            }
            if(method.equals("POST") && path.equals("/command")) {
                if(!url().equals(headers.getFirst("Origin")) || !"1".equals(headers.getFirst("X-Bot-Request"))
                        || !"application/json".equals(headers.getFirst("Content-Type"))) {reply(exchange,403,"Invalid request origin.");return;}
                byte[] body=exchange.getRequestBody().readNBytes(4097);
                if(body.length>4096) {reply(exchange,413,"Command too long.");return;}
                String command;
                try {
                    var root=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    if(!root.keySet().equals(Set.of("command")) || !root.get("command").isJsonPrimitive() || !root.get("command").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                    command=root.get("command").getAsString().trim();
                    if(command.isEmpty() || command.startsWith("@web") || command.contains("\n") || command.contains("\r")) throw new IllegalArgumentException();
                } catch(RuntimeException failure) {reply(exchange,400,"Invalid command.");return;}
                if(!commands.tryAcquire()) {reply(exchange,429,"Previous command is processing; this command was not sent.");return;}
                try {
                    String result;
                    try {result=dispatch.apply(command);}
                    catch(RuntimeException failure) {reply(exchange,503,"Command incomplete; check state before resubmitting.");return;}
                    publish(result,Channel.RESPONSE);reply(exchange,200,result);
                }
                finally {commands.release();}
                return;
            }
            reply(exchange,404,"Path not found.");
        } catch(RuntimeException failure) {
            // Never echo a submitted command or credentials into diagnostic logs.
            try {reply(exchange,500,"Command incomplete; check state before resubmitting.");} catch(IOException ignored) {}
        }
    }
    private void reply(HttpExchange exchange,int code,String message) throws IOException {
        byte[] body=json.toJson(Map.of("message",message==null?"":message)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);
    }
    private void stream(HttpExchange exchange) throws IOException {
        if(!streams.tryAcquire()) {reply(exchange,429,"Maximum 4 log streams are open.");return;}
        try {
            long cursor=0;
            try {cursor=Long.parseLong(exchange.getRequestHeaders().getFirst("Last-Event-ID"));} catch(RuntimeException ignored) {}
            synchronized(events) {cursor=Math.min(Math.max(0,cursor),sequence);}
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200,0);
            var writer=new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(),StandardCharsets.UTF_8));
            writer.write(": connected\n\n");writer.flush();
            while(!closed) {
                List<Event> batch=new ArrayList<>();
                synchronized(events) {
                    for(var event:events) if(event.id()>cursor) batch.add(event);
                    if(batch.isEmpty()) try {events.wait(1000);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();break;}
                }
                for(var event:batch) {writer.write("id: "+event.id()+"\ndata: "+json.toJson(event)+"\n\n");cursor=event.id();}
                if(batch.isEmpty()) writer.write(": heartbeat\n\n");
                writer.flush();
            }
        } finally {streams.release();}
    }
    @Override public void close() throws IOException {
        closed=true;synchronized(events) {events.notifyAll();}
        server.stop(0);workers.shutdownNow();Files.deleteIfExists(endpoint);
    }
}
