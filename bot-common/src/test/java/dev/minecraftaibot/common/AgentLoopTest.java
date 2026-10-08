package dev.minecraftaibot.common;

import com.google.gson.*;
import dev.minecraftaibot.common.ai.*;
import java.util.*;
import java.util.function.Consumer;

public final class AgentLoopTest {
    public static void run() throws Exception {
        long[] time={0}; int[] stopped={0}; List<String> commands=new ArrayList<>(), requests=new ArrayList<>();
        List<Consumer<String>> answers=new ArrayList<>(), failures=new ArrayList<>();
        AgentLoop loop=new AgentLoop((input,answer,failure)->{requests.add(input);answers.add(answer);failures.add(failure);},
                command->{commands.add(command);return "Đã nhận.";},()->stopped[0]++,message->{},()->time[0]);
        check(!loop.autoEnabled());loop.start("Chưa cho thực thi");loop.tick(true,false,"Quan sát");
        check(!loop.active() && requests.isEmpty() && commands.isEmpty());loop.setAuto(true);
        loop.start("Lấy tổng 16 gỗ"); loop.tick(true,false,"Túi có 5");
        loop.tick(true,false,"Túi có 5"); check(requests.size()==1);
        answers.getLast().accept(json("mine","{\"block\":\"minecraft:oak_log\",\"quantity\":11}"));
        check(commands.equals(List.of("bot mine minecraft:oak_log 11")));
        time[0]=2000; loop.tick(true,true,"Đang đào"); check(requests.size()==1);
        loop.tick(true,false,"Túi có 16"); check(requests.size()==2 && requests.getLast().contains("Túi có 16") && requests.getLast().contains("bot mine"));
        Consumer<String> stale=answers.getLast(); loop.cancel("Thủ công");
        loop.start("Mục tiêu mới"); stale.accept(json("mine","{\"block\":\"minecraft:oak_log\",\"quantity\":16}"));
        check(commands.size()==1); loop.tick(true,false,"Quan sát");
        answers.getLast().accept(json("done","{}")); check(!loop.active());
        loop.start("Thử lỗi"); loop.tick(true,false,"Quan sát"); failures.getLast().accept("Hết API"); check(!loop.active());
        loop.start("Đổi server"); loop.tick(true,false,"Quan sát"); Consumer<String> late=answers.getLast();
        loop.tick(false,false,"Server khác"); late.accept(json("inventory","{}")); check(commands.size()==1);
        loop.start("Thời gian"); loop.tick(true,false,"Quan sát");
        answers.getLast().accept(json("mine","{\"block\":\"minecraft:oak_log\",\"quantity\":1}"));
        time[0]+=180001; loop.tick(true,true,"Bị kẹt"); check(!loop.active());
        loop.start("Vòng lặp");
        for(int i=0;i<20;i++) { loop.tick(true,false,"Quan sát"); answers.getLast().accept(json("inventory","{}")); time[0]+=2000; }
        loop.tick(true,false,"Quan sát"); check(!loop.active());
        loop.start("API chậm"); loop.tick(true,false,"Quan sát"); time[0]+=600001; loop.tick(true,false,"Quan sát"); check(!loop.active());
        String id="CHEST-12345678-1234-1234-1234-123456789abc";
        check(AgentLoop.command(parse(json("chest_open","{\"id\":\""+id+"\"}"))).equals("bot chest open "+id));
        check(AgentLoop.command(parse(json("chest_take","{\"item\":\"minecraft:oak_log\",\"quantity\":11}"))).equals("bot chest take minecraft:oak_log 11"));
        for(String invalid:List.of(json("chest_open","{\"id\":\"evil; exit\"}"),json("chest_put","{\"item\":\"minecraft:oak_log\",\"quantity\":0}"),json("chest_take","{\"item\":\"x\\nexit\",\"quantity\":1}"))) {
            try { AgentLoop.command(parse(invalid)); throw new AssertionError(); } catch(java.io.IOException expected) {}
        }
        check(stopped[0]>=7);
        loop.start("Tắt trong lúc chờ API");loop.tick(true,false,"Quan sát");Consumer<String> pending=answers.getLast();
        int beforeCommands=commands.size(),beforeRequests=requests.size(),beforeStops=stopped[0];
        loop.setAuto(false);check(!loop.autoEnabled() && !loop.active() && stopped[0]==beforeStops+1);
        pending.accept(json("mine","{\"block\":\"minecraft:oak_log\",\"quantity\":16}"));
        loop.start("Không được chạy khi OFF");loop.tick(true,false,"Quan sát");
        check(commands.size()==beforeCommands && requests.size()==beforeRequests);
        loop.setAuto(true);loop.start("Bật lại phải là nhiệm vụ mới");pending.accept(json("inventory","{}"));
        check(commands.size()==beforeCommands);loop.tick(true,false,"Quan sát");answers.getLast().accept(json("inventory","{}"));
        check(commands.size()==beforeCommands+1);loop.setAuto(false);
        System.out.println("All AI loop sequencing, real result feedback, stale callbacks, cancellation, bounded turns/time and chest action validation checks passed.");
    }
    private static String json(String action,String args) { return "{\"action\":\""+action+"\",\"args\":"+args+",\"message\":\"Test\"}"; }
    private static JsonObject parse(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean condition) { if(!condition) throw new AssertionError("AI agent loop check failed"); }
}
