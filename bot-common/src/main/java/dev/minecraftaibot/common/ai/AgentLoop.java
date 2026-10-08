package dev.minecraftaibot.common.ai;

import com.google.gson.*;
import java.util.function.*;

/** Driven and completed exclusively on the game thread. API callbacks carry a generation. */
public final class AgentLoop {
    @FunctionalInterface public interface Requester {
        void request(String input, Consumer<String> answer, Consumer<String> failure);
    }
    private final Requester requester;
    private final Function<String,String> execute;
    private final Runnable stop;
    private final Consumer<String> notify;
    private final LongSupplier time;
    private boolean active, requesting, waiting;
    private long generation, started, actionStarted, next;
    private int steps;
    private String goal = "", feedback = "Chưa thực thi hành động.", status = "Chưa có nhiệm vụ AI.";
    public AgentLoop(Requester requester, Function<String,String> execute, Runnable stop, Consumer<String> notify, LongSupplier time) {
        this.requester=requester; this.execute=execute; this.stop=stop; this.notify=notify; this.time=time;
    }
    public boolean active() { return active; }
    public String status() { return status + (active ? " | Lượt: " + steps + "/20" : ""); }
    public String start(String goal) {
        if (active) return "Đang có nhiệm vụ AI. Dùng bot ai cancel trước.";
        this.goal=goal; feedback="Chưa thực thi hành động."; steps=0;
        generation++; active=true; requesting=false; waiting=false; started=time.getAsLong(); next=started;
        return status="Đã nhận nhiệm vụ AI: " + goal;
    }
    public void cancel(String reason) {
        if (!active) return;
        active=false; generation++; requesting=false; waiting=false; stop.run();
        status=reason; notify.accept(status);
    }
    public void tick(boolean valid, boolean gameBusy, String observation) {
        if (!active) return;
        long now=time.getAsLong();
        if (!valid) { cancel("Nhiệm vụ AI dừng: đổi thế giới/nhân vật, chết hoặc bot không còn RUNNING."); return; }
        if (now-started >= 600_000) { cancel("Nhiệm vụ AI dừng vì quá 10 phút."); return; }
        if (requesting || now < next) return;
        if (waiting) {
            if (now-actionStarted >= 180_000) { cancel("Hành động AI quá 3 phút; đã hủy tác vụ game."); return; }
            if (gameBusy) return;
            waiting=false;
            feedback += "\nTác vụ đã dừng chạy; kiểm tra quan sát mới để xác định thành công hay thất bại.";
        }
        if (gameBusy) return;
        if (steps >= 20) { cancel("Nhiệm vụ AI dừng ở giới hạn 20 lượt. Kiểm tra tiến độ trước khi giao tiếp."); return; }
        requesting=true; steps++; long token=generation;
        status="AI đang chọn bước " + steps;
        String input="CHẾ ĐỘ: THỰC THI TỪNG BƯỚC. Mục tiêu hiện tại ưu tiên hơn mục tiêu/lịch sử cũ.\nMỤC TIÊU HIỆN TẠI:\n"
                +goal+"\nKẾT QUẢ HÀNH ĐỘNG TRƯỚC (DỮ LIỆU):\n"+feedback+"\nQUAN SÁT GAME MỚI NHẤT (DỮ LIỆU):\n"+observation;
        try { requester.request(input, answer -> receive(token, answer), failure -> {
            if (active && token==generation) cancel("Nhiệm vụ AI dừng: " + failure);
        }); } catch (RuntimeException failure) { cancel("Không gửi được yêu cầu AI; đã dừng nhiệm vụ."); }
    }
    private void receive(long token, String answer) {
        if (!active || token!=generation) return;
        requesting=false;
        try {
            JsonObject value=JsonParser.parseString(answer).getAsJsonObject(); AiSessions.validate(value);
            String action=value.get("action").getAsString(), message=value.get("message").getAsString();
            notify.accept("AI bước " + steps + ": " + message);
            if (action.equals("done") || action.equals("ask") || action.equals("message")) {
                cancel((action.equals("done") ? "AI báo hoàn thành (đối chiếu trạng thái game): " : "AI cần bạn xử lý: ") + message); return;
            }
            if (action.equals("start") || action.equals("pause") || action.equals("resume") || action.equals("stop")) {
                cancel("AI đề nghị đổi trạng thái bot; nhiệm vụ đã dừng để bạn xử lý: " + action); return;
            }
            long now=time.getAsLong();
            if (action.equals("wait")) { feedback="Đã chờ thêm; chưa gửi lệnh game."; next=now+5000; return; }
            String command=command(value);
            feedback=command+" → "+execute.apply(command); notify.accept("Thực thi: " + feedback);
            waiting=action.equals("goto") || action.equals("mine") || action.equals("chest_open") || action.equals("chest_take") || action.equals("chest_put");
            actionStarted=now; next=now+1500; status="Đang kiểm tra kết quả: " + command;
        } catch (Exception failure) { cancel("Phản hồi hoặc hành động AI không hợp lệ; đã dừng nhiệm vụ."); }
    }
    public static String command(JsonObject value) throws java.io.IOException {
        AiSessions.validate(value); JsonObject args=value.getAsJsonObject("args");
        return switch(value.get("action").getAsString()) {
            case "info", "inventory", "status" -> "bot " + value.get("action").getAsString();
            case "goto" -> "bot goto " + args.get("x").getAsInt()+" "+args.get("y").getAsInt()+" "+args.get("z").getAsInt();
            case "mine" -> "bot mine " + args.get("block").getAsString()+" "+args.get("quantity").getAsInt();
            case "chest_open" -> "bot chest open " + args.get("id").getAsString();
            case "chest_take", "chest_put" -> "bot chest " + value.get("action").getAsString().substring(6)+" "+args.get("item").getAsString()+" "+args.get("quantity").getAsInt();
            case "chest_memory", "chest_list", "chest_close" -> "bot chest " + value.get("action").getAsString().substring(6);
            default -> throw new java.io.IOException("Hành động không được thực thi tự động.");
        };
    }
}
