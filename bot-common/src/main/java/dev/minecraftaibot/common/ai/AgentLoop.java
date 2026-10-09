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
    private boolean active, requesting, waiting, autoEnabled;
    private long generation, started, actionStarted, next;
    private long actionTimeout=180_000;
    private int steps;
    private String goal = "", feedback = "No action executed yet.", status = "No AI task.";
    public AgentLoop(Requester requester, Function<String,String> execute, Runnable stop, Consumer<String> notify, LongSupplier time) {
        this.requester=requester; this.execute=execute; this.stop=stop; this.notify=notify; this.time=time;
    }
    public boolean active() { return active; }
    public boolean autoEnabled() { return autoEnabled; }
    public String setAuto(boolean enabled) {
        autoEnabled=enabled;
        if(!enabled) cancel("AI execution disabled; AI task canceled and pending responses ignored.");
        return enabled ? "AI execution: ON. bot ai ask/run executes tasks; bot ai auto off disables it."
                : "AI execution: OFF. bot ai ask only suggests actions; bot ai run is locked.";
    }
    public String status() { return status + (active ? " | Turns: " + steps + "/20" : ""); }
    public String start(String goal) {
        if(!autoEnabled) return "AI execution is OFF. Enable bot ai auto on first.";
        if (active) return "AI task active. Use bot ai cancel first.";
        this.goal=goal; feedback="No action executed yet."; steps=0;
        generation++; active=true; requesting=false; waiting=false; started=time.getAsLong(); next=started;
        return status="AI task accepted: " + goal;
    }
    public void cancel(String reason) {
        if (!active) return;
        active=false; generation++; requesting=false; waiting=false; stop.run();
        status=reason; notify.accept(status);
    }
    public void tick(boolean valid, boolean gameBusy, String observation) {
        tick(valid,gameBusy,()->observation);
    }
    public void tick(boolean valid, boolean gameBusy, Supplier<String> observation) {
        if (!active) return;
        long now=time.getAsLong();
        if (!valid) { cancel("AI task stopped: world/player changed, player died or bot is no longer RUNNING."); return; }
        if (now-started >= 1_800_000) { cancel("AI task stopped after exceeding 30 minutes."); return; }
        if (requesting || now < next) return;
        if (waiting) {
            if (now-actionStarted >= actionTimeout) { cancel("AI action timed out; game action canceled."); return; }
            if (gameBusy) return;
            waiting=false;
            feedback += "\nThe action has stopped; check the latest observation to determine success or failure.";
        }
        if (gameBusy) return;
        if (steps >= 20) { cancel("AI task stopped at the 20-turn limit. Check progress before assigning another task."); return; }
        requesting=true; steps++; long token=generation;
        status="AI selecting step " + steps;
        try {
            String input="MODE: EXECUTE STEP BY STEP. The current goal takes priority over previous goals/history.\nCURRENT GOAL:\n"
                    +goal+"\nPREVIOUS ACTION RESULT (DATA):\n"+feedback+"\nLATEST GAME OBSERVATION (DATA):\n"+observation.get();
            requester.request(input, answer -> receive(token, answer), failure -> {
                if (active && token==generation) cancel("AI task stopped: " + failure);
            });
        } catch (RuntimeException failure) { cancel("Could not send AI request; task stopped."); }
    }
    private void receive(long token, String answer) {
        if (!active || token!=generation) return;
        requesting=false;
        try {
            JsonObject value=JsonParser.parseString(answer).getAsJsonObject(); AiSessions.validate(value);
            String action=value.get("action").getAsString(), message=value.get("message").getAsString();
            notify.accept("AI step " + steps + ": " + message);
            if (action.equals("done") || action.equals("ask") || action.equals("message")) {
                cancel((action.equals("done") ? "AI reports completion (verify game state): " : "AI needs your help: ") + message); return;
            }
            if (action.equals("start") || action.equals("pause") || action.equals("resume") || action.equals("stop")) {
                cancel("AI requested a bot state change; task stopped for you to handle: " + action); return;
            }
            long now=time.getAsLong();
            if (action.equals("wait")) { feedback="Waited longer; no game command sent yet."; next=now+5000; return; }
            String command=command(value);
            feedback=command+" → "+execute.apply(command); notify.accept("Executing: " + feedback);
            waiting=action.equals("goto") || action.equals("mine") || action.equals("chest_open") || action.equals("chest_take") || action.equals("chest_put") || action.equals("task") || action.equals("smelt");
            actionTimeout=action.equals("smelt") || action.equals("task")?900_000:180_000;
            actionStarted=now; next=now+1500; status="Checking result: " + command;
        } catch (Exception failure) { cancel("Invalid AI response or action; task stopped."); }
    }
    public static String command(JsonObject value) throws java.io.IOException {
        AiSessions.validate(value); JsonObject args=value.getAsJsonObject("args");
        return switch(value.get("action").getAsString()) {
            case "info", "inventory", "status" -> "bot " + value.get("action").getAsString();
            case "goto" -> "bot goto " + args.get("x").getAsInt()+" "+args.get("y").getAsInt()+" "+args.get("z").getAsInt();
            case "mine" -> "bot mine " + args.get("block").getAsString()+" "+args.get("quantity").getAsInt();
            case "task" -> "bot task "+args.get("task").getAsString()+" "+args.get("quantity").getAsInt()
                    +(args.get("task").getAsString().startsWith("smelt_") && args.get("mode").getAsString().equals("additional")?"":" "+args.get("mode").getAsString());
            case "smelt" -> "bot furnace run "+args.get("machine").getAsString()+" "+args.get("item").getAsString()+" "+args.get("quantity").getAsInt()+" "+args.get("fuel").getAsString();
            case "chest_open" -> "bot chest open " + args.get("id").getAsString();
            case "chest_take", "chest_put" -> "bot chest " + value.get("action").getAsString().substring(6)+" "+args.get("item").getAsString()+" "+args.get("quantity").getAsInt();
            case "chest_memory", "chest_list", "chest_close" -> "bot chest " + value.get("action").getAsString().substring(6);
            default -> throw new java.io.IOException("Action is not executed automatically.");
        };
    }
}
