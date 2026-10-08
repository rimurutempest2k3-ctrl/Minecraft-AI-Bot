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
    private String task = "Chưa có tác vụ";
    private MiningQuota quota;
    private BlockOptionalMetaLookup miningFilter;
    private boolean cobblestoneGoal;
    private Boolean previousAutoTool;

    static boolean silkTouch(net.minecraft.world.item.ItemStack stack) {
        return stack.getEnchantments().keySet().stream().anyMatch(e ->
                e.is(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH));
    }

    BaritoneController(BotCore bot) { this.bot = bot; }
    void approachChest(BlockPos position) {
        approachBlock(position, "rương");
    }
    void approachBlock(BlockPos position, String label) {
        Minecraft client = Minecraft.getInstance();
        engine(); stop();
        owner = client.player; world = client.level;
        task = "Đi tới " + label + " " + position.toShortString();
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
        if (command.equals("stop")) { stop(); return "Đã hủy tác vụ Baritone."; }
        if (command.equals("pause")) {
            engine(); pause.active = true;
            baritone.getInputOverrideHandler().clearAllKeys();
            return "Đã tạm dừng Baritone.";
        }
        if (command.equals("resume")) { if (pause != null) pause.active = false; return "Đã tiếp tục Baritone."; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "Chưa vào thế giới.";
        if (!client.player.isAlive()) return "Nhân vật đã chết.";
        if (client.gui.screen() != null || client.gui.overlay() != null || client.isPaused())
            return "Đóng menu và tắt tự tạm dừng khi chuyển cửa sổ bằng F3+P trước.";
        String[] parts = command.split(" ");
        if (parts[0].equals("goto")) {
            int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = Integer.parseInt(parts[3]);
            if (Math.abs((long)x) > 29999984 || Math.abs((long)z) > 29999984
                    || y < client.level.getMinY() || y > client.level.getMaxY())
                return "Tọa độ nằm ngoài giới hạn thế giới.";
            engine(); stop();
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
        } else if (parts[0].equals("mine")) {
            boolean collectingCobblestone = parts[1].equals("cobblestone") || parts[1].equals("minecraft:cobblestone");
            Identifier id = Identifier.tryParse(collectingCobblestone ? "minecraft:stone" : parts[1]);
            var block = id == null ? java.util.Optional.<net.minecraft.world.level.block.Block>empty()
                    : BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty() || block.get().defaultBlockState().isAir()) return "Mã block không hợp lệ: " + parts[1];
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
        task = cobblestoneGoal ? "Thu đá cuội (minecraft:cobblestone) bằng cách đào block minecraft:stone" : command;
        LOG.info("Baritone task started: {}", task);
        return "Đã giao tác vụ cho Baritone: " + task
                + (quota == null ? "" : " (thu thêm " + quota.requested() + ", đã có " + quota.initial()
                + ", mục tiêu tổng " + quota.targetTotal() + ")")
                + ". bot pause để tạm dừng; bot stop để hủy.";
    }
    String status() {
        if (baritone == null) return "Baritone sẵn sàng; chưa có tác vụ";
        if (owner == null) return task;
        String progress = quota != null && owner != null
                ? " | Đã thu thêm: " + quota.collected(currentCount(owner)) + "/" + quota.requested() : "";
        return task + progress + (pause.active ? " (tạm dừng)" : baritone.getPathingBehavior().isPathing()
                ? " (đang di chuyển)" : " (đang chờ/tính đường hoặc đã hoàn tất)");
    }
    boolean busy() { return owner != null && (quota != null || baritone.getCustomGoalProcess().isActive() || baritone.getPathingBehavior().isPathing()); }
    void tick(Minecraft client) {
        if (owner != null && (client.player != owner || client.level != world || !owner.isAlive())) {
            stop(); bot.stop();
        } else if (owner != null && quota != null) {
            if(cobblestoneGoal && silkTouch(owner.getMainHandItem())) {
                stop(); task="Đã dừng thu đá cuội: cúp đang cầm có Silk Touch."; return;
            }
            int count = currentCount(owner);
            if (quota.complete(count)) {
                String completed = "Hoàn thành " + task + " | Đã thu thêm: " + quota.collected(count) + "/" + quota.requested();
                stop();
                task = completed;
                LOG.info("Mining task completed: {}", completed);
            } else if (!baritone.getMineProcess().isActive()) {
                String incomplete = "Tác vụ đào đã dừng trước khi đủ số lượng: " + task
                        + " | Đã thu thêm: " + quota.collected(count) + "/" + quota.requested();
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
        owner = null; world = null; task = "Chưa có tác vụ";
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
