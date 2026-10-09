package minecraftaibot.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import net.minecraft.core.BlockPos;
import baritone.api.process.*;
import dev.minecraftaibot.common.BotCore;
import dev.minecraftaibot.common.MiningQuota;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.SettingsUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BaritoneController {
    private static final Logger LOG = LoggerFactory.getLogger("minecraft-ai-bot");
    private final BotCore bot;
    private IBaritone baritone;
    private PauseGate pause;
    private LocalPlayer owner;
    private ClientLevel world;
    private String task = "No active task";
    private MiningQuota quota;
    private BlockOptionalMetaLookup miningFilter;
    private boolean cobblestoneGoal;
    private Boolean previousAutoTool;

    static boolean silkTouch(net.minecraft.world.item.ItemStack stack) {
        return stack.getEnchantments().keySet().stream().anyMatch(e ->
                e.is(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH));
    }

    BaritoneController(BotCore bot) { this.bot = bot; }
    com.google.gson.JsonObject webSettings() {
        var s=BaritoneAPI.getSettings();var result=new com.google.gson.JsonObject();
        result.addProperty("allowBreak",s.allowBreak.value);result.addProperty("allowPlace",s.allowPlace.value);
        result.addProperty("avoidance",s.avoidance.value);result.addProperty("mobAvoidanceRadius",s.mobAvoidanceRadius.value);
        result.addProperty("autoTool",s.autoTool.value);return result;
    }
    String webSetting(String input) {
        if(busy()) return "Wait for Baritone to finish before changing settings.";
        String[] parts=input.split(" ");
        if(parts.length!=2) return "Invalid setting.";
        var s=BaritoneAPI.getSettings();
        if(parts[0].equals("mobAvoidanceRadius")) {
            int radius;try {radius=Integer.parseInt(parts[1]);} catch(NumberFormatException invalid) {return "Invalid radius.";}
            if(radius<1 || radius>32) return "Radius must be between 1 and 32.";
            s.mobAvoidanceRadius.value=radius;
        } else {
            if(!parts[1].equals("on") && !parts[1].equals("off")) return "Value must be on/off.";
            boolean value=parts[1].equals("on");
            switch(parts[0]) {
                case "allowBreak" -> s.allowBreak.value=value;
                case "allowPlace" -> s.allowPlace.value=value;
                case "avoidance" -> s.avoidance.value=value;
                case "autoTool" -> s.autoTool.value=value;
                default -> {return "Unsupported setting.";}
            }
        }
        SettingsUtil.save(s);
        return "Baritone settings applied; requested configuration save.";
    }
    void runAway(BlockPos[] threats,double distance) {
        Minecraft client=Minecraft.getInstance();engine();
        if(owner!=client.player || world!=client.level) stop();
        owner=client.player;world=client.level;task="Retreat to keep mobs at least this far away: "+distance+" block";
        // Extra margin for block-coordinate rounding; the survival controller checks actual distance.
        baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalRunAway(distance+2,threats));
    }
    boolean searching() {return baritone!=null && baritone.getPathingBehavior().getInProgress().isPresent();}
    boolean hasEscapePath() {return baritone!=null && baritone.getPathingBehavior().hasPath();}
    boolean pathCrosses(java.util.function.Predicate<BlockPos> forbidden,int lookAhead) {
        if(baritone==null) return false;
        var executor=baritone.getPathingBehavior().getCurrent();
        if(executor==null) return false;
        var positions=executor.getPath().positions();
        int start=Math.max(0,executor.getPosition());
        for(int i=start;i<Math.min(positions.size(),start+lookAhead+1);i++) if(forbidden.test(positions.get(i))) return true;
        return false;
    }
    void approachChest(BlockPos position) {
        approachBlock(position, "chest");
    }
    void approachBlock(BlockPos position, String label) {
        Minecraft client = Minecraft.getInstance();
        engine(); stop();
        owner = client.player; world = client.level;
        task = "Go to " + label + " " + position.toShortString();
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(position));
    }
    boolean besideChest(BlockPos position) {
        return owner != null && new GoalGetToBlock(position).isInGoal(owner.blockPosition());
    }
    private IBaritone engine() {
        if (baritone == null) {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            pause = new PauseGate();
            baritone.getPathingControlManager().registerProcess(pause);
        }
        return baritone;
    }
    String execute(String command) {
        if (command.equals("stop")) { stop(); return "Baritone task canceled."; }
        if (command.equals("pause")) {
            engine(); pause.active = true;
            baritone.getInputOverrideHandler().clearAllKeys();
            return "Baritone paused.";
        }
        if (command.equals("resume")) { if (pause != null) pause.active = false; return "Baritone resumed."; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "Not in a world.";
        if (!client.player.isAlive()) return "Player died.";
        if (client.gui.screen() != null || client.gui.overlay() != null || client.isPaused())
            return "Close menus and disable auto pause with F3+P first.";
        String[] parts = command.split(" ");
        if (parts[0].equals("goto")) {
            int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = Integer.parseInt(parts[3]);
            if (Math.abs((long)x) > 29999984 || Math.abs((long)z) > 29999984
                    || y < client.level.getMinY() || y > client.level.getMaxY())
                return "Coordinates outside world bounds.";
            engine(); stop();
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
        } else if (parts[0].equals("mine")) {
            boolean collectingCobblestone = parts[1].equals("cobblestone") || parts[1].equals("minecraft:cobblestone");
            Identifier id = Identifier.tryParse(collectingCobblestone ? "minecraft:stone" : parts[1]);
            var block = id == null ? java.util.Optional.<net.minecraft.world.level.block.Block>empty()
                    : BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty() || block.get().defaultBlockState().isAir()) return "Invalid block ID: " + parts[1];
            int quantity = Integer.parseInt(parts[2]);
            BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(block.get());
            MiningQuota nextQuota = new MiningQuota(collectingCobblestone
                    ? countCobblestone(client.player) : countItems(client.player, filter), quantity);
            engine(); stop();
            quota = nextQuota;
            miningFilter = filter;
            cobblestoneGoal = collectingCobblestone;
            if(cobblestoneGoal) {
                // Keep the ordinary pickaxe selected by the local prerequisite task.
                previousAutoTool = BaritoneAPI.getSettings().autoTool.value;
                BaritoneAPI.getSettings().autoTool.value = false;
                // Our exact item counter owns completion; Baritone's stone drop filter also accepts stone.
                baritone.getMineProcess().mine(filter);
            } else baritone.getMineProcess().mine(quota.targetTotal(), filter);
        } else throw new IllegalArgumentException("Unsupported action: " + command);
        owner = client.player;
        world = client.level;
        task = cobblestoneGoal ? "Collect cobblestone (minecraft:cobblestone) by mining minecraft:stone blocks" : command;
        LOG.info("Baritone task started: {}", task);
        return "Assigned Baritone task: " + task
                + (quota == null ? "" : " (collecting another " + quota.requested() + ", already have " + quota.initial()
                + ", total target " + quota.targetTotal() + ")")
                + ". bot pause to pause; bot stop to cancel.";
    }
    String status() {
        if (baritone == null) return "Baritone ready; no active task";
        if (owner == null) return task;
        String progress = quota != null && owner != null
                ? " | Collected: " + quota.collected(currentCount(owner)) + "/" + quota.requested() : "";
        return task + progress + (pause.active ? " (paused)" : baritone.getPathingBehavior().isPathing()
                ? " (moving)" : " (waiting/calculating a path, or finished)");
    }
    boolean busy() { return owner != null && (quota != null || baritone.getCustomGoalProcess().isActive() || baritone.getPathingBehavior().isPathing()); }
    com.google.gson.JsonObject webProgress() {
        var result=new com.google.gson.JsonObject();
        if(quota!=null && owner!=null) {
            result.addProperty("collected",quota.collected(currentCount(owner)));result.addProperty("target",quota.requested());
            result.addProperty("label",task);
        }
        return result;
    }
    void tick(Minecraft client) {
        if (owner != null && (client.player != owner || client.level != world || !owner.isAlive())) {
            // A dead/replaced player invalidates this route, not the user's RUNNING intent.
            stop();
        } else if (owner != null && quota != null) {
            if(cobblestoneGoal && silkTouch(owner.getMainHandItem())) {
                stop(); task="Cobblestone gathering stopped: held pickaxe has Silk Touch."; return;
            }
            int count = currentCount(owner);
            if (quota.complete(count)) {
                String completed = "Completed " + task + " | Collected: " + quota.collected(count) + "/" + quota.requested();
                stop();
                task = completed;
                LOG.info("Mining task completed: {}", completed);
            } else if (!baritone.getMineProcess().isActive()) {
                String incomplete = "Mining stopped before reaching requested quantity: " + task
                        + " | Collected: " + quota.collected(count) + "/" + quota.requested();
                stop(); task = incomplete; LOG.warn("{}", incomplete);
            }
        }
    }
    void stop() {
        if(previousAutoTool!=null) {BaritoneAPI.getSettings().autoTool.value=previousAutoTool;previousAutoTool=null;}
        if (baritone != null) {
            pause.active = false;
            baritone.getPathingBehavior().cancelEverything();
            baritone.getPathingBehavior().forceCancel();
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        owner = null; world = null; task = "No active task";
        quota = null; miningFilter = null; cobblestoneGoal=false;
    }
    private int currentCount(LocalPlayer player) {return cobblestoneGoal ? countCobblestone(player):countItems(player,miningFilter);}
    private static int countCobblestone(LocalPlayer player) {
        return player.getInventory().getNonEquipmentItems().stream()
                .filter(s->s.is(net.minecraft.world.item.Items.COBBLESTONE)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
    }
    private static int countItems(LocalPlayer player, BlockOptionalMetaLookup filter) {
        return player.getInventory().getNonEquipmentItems().stream()
                .filter(filter::has).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
    }
    private final class PauseGate implements IBaritoneProcess {
        boolean active;
        public boolean isActive() { return active; }
        public PathingCommand onTick(boolean failed, boolean safe) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        public boolean isTemporary() { return true; }
        public void onLostControl() { }
        public double priority() { return DEFAULT_PRIORITY + 1; }
        public String displayName0() { return "Minecraft AI Bot pause"; }
    }
}
