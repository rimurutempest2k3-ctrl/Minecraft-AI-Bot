package minecraftaibot.client;

import dev.minecraftaibot.common.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** One owner for food, melee/bow defence and bounded escape. No API requests or attacks on players. */
final class SurvivalController {
    private enum Mode { WATCH, EAT, DEFEND, RANGED, RETREAT, CREEPER, PILLAR, EQUIP, CLEAN, LOOT }
    private record Seen(LivingEntity entity, SurvivalPolicy.Threat threat) {}
    private static final Set<String> DEFENDABLE=Set.of("minecraft:zombie","minecraft:husk","minecraft:drowned",
            "minecraft:skeleton","minecraft:stray","minecraft:bogged","minecraft:spider",
            "minecraft:cave_spider","minecraft:silverfish","minecraft:endermite","minecraft:slime");
    private static final Set<String> RANGED=Set.of("minecraft:skeleton","minecraft:stray","minecraft:bogged");
    private static final Set<String> DUELABLE_DANGEROUS=Set.of("minecraft:witch","minecraft:vindicator","minecraft:evoker",
            "minecraft:piglin_brute","minecraft:blaze","minecraft:magma_cube");
    private final BotCore bot;
    private final BaritoneController movement;
    private final BooleanSupplier interrupt;
    private final Consumer<String> notify;
    private final BooleanSupplier idleForEquipment;
    private boolean autoEquipment=true,alerts=true;
    private long nextEquipment,nextAlert;
    private Set<String> previousAlerts=Set.of();
    private EquipmentMaintenance maintenance;
    private SurvivalPolicy.Trash trash=new SurvivalPolicy.Trash(1,List.of());
    private boolean autoTrash;
    private long nextTrash;
    private TrashCleanup cleanup;
    private SurvivalPolicy.Loot loot=SurvivalPolicy.defaultLoot();
    private boolean autoLoot=true,observedDead;
    private LocalPlayer lifePlayer;
    private ClientLevel lifeWorld;
    private Set<String> lastInventory=Set.of(),recoveryItems=Set.of();
    private BlockPos deathPosition;
    private String deathScope="";
    private long recoveryUntil;
    private int recoveryReturns,recoveryEmptyScans;
    private long nextRecoveryNotice;
    private static final int RECOVERY_RADIUS=64;
    private String recoveryScope(Minecraft client) {
        String server=client.getSingleplayerServer()!=null?"local:"+client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                :client.getCurrentServer()!=null?"server:"+client.getCurrentServer().ip.toLowerCase(Locale.ROOT):"session:"+System.identityHashCode(client.getConnection());
        return server+"|"+client.level.dimension().identifier();
    }
    private boolean recovering(Minecraft client) {
        return deathPosition!=null && System.nanoTime()<recoveryUntil && client.level!=null && deathScope.equals(recoveryScope(client));
    }
    private void clearRecovery() {recoveryItems=Set.of();deathPosition=null;deathScope="";recoveryUntil=0;recoveryReturns=recoveryEmptyScans=0;}
    private int pickupRadius(Minecraft client) {return recovering(client)?Math.max(RECOVERY_RADIUS,loot.radius()):loot.radius();}
    private boolean recoverySafe(Minecraft client) {
        return !escapingBiome && SurvivalPolicy.recoveryOpportunity(client.player.getHealth(),client.player.getMaxHealth(),recentDamage,cached.stream().map(Seen::threat).toList());
    }
    private boolean unsafeRecoveryAt(BlockPos pos) {
        Vec3 point=Vec3.atCenterOf(pos);
        return cached.stream().anyMatch(s->s.entity.isAlive() && s.entity.position().distanceToSqr(point)<=Math.pow(s.threat.dangerous()?12:s.threat.ranged()?10:3.5,2));
    }
    private final Map<UUID,Long> skippedDrops=new HashMap<>();
    private long nextLoot,nextSurvivalAttempt,nextPillar;
    private static final long LOOT_SCAN_INTERVAL=2_000_000_000L;
    private ItemPickup pickup;
    private SurvivalPolicy.Settings settings=SurvivalPolicy.defaults();
    private SurvivalPolicy.Biomes biomes=SurvivalPolicy.defaultBiomes();
    private boolean escapingBiome;
    private Path config;
    private boolean enabled,combat=true,holdingUse;
    private boolean attackQueued;
    private boolean rangedEnabled=true,drawingBow;
    private int bowTicks,bowSamples,steadyBowSamples;
    private Vec3 bowLastPosition,bowVelocity=Vec3.ZERO;
    private long nextShot,bowStarted,lastDamage,damageWindow;
    private float lastHealth=Float.NaN,recentDamage;
    private boolean shieldEnabled=true,holdingShield;
    private int shieldSource=-1,shieldLowerTicks;
    private int strikeWindow;
    private boolean emergencyEating;
    private long nextGoldenFood;
    private ItemStack oldOffhand=ItemStack.EMPTY;
    private double escapeRadius=8;
    private double minimumEscapeRadius=8;
    private long nextCreeperShield;
    private int shieldFailures;
    private SurvivalPolicy.Assessment assessment=new SurvivalPolicy.Assessment(SurvivalPolicy.Danger.CLEAR,SurvivalPolicy.Action.IDLE,8,"Not assessed yet.");
    private String previousRisk="";
    private boolean escapeReduced;
    private Vec3 progressPosition;
    private long progressAt,escapeStarted,nextGoal;
    private List<LivingEntity> escapeMobs=List.of();
    private BlockPos[] escapeOrigins=new BlockPos[0];
    private EmergencyPillar pillar;
    private Mode mode=Mode.WATCH;
    private LocalPlayer owner;
    private LivingEntity aimedMob;
    private int alignedTicks;
    private ClientLevel world;
    private int selected,quiet,foodBefore,hungerBefore,foodCountBefore,weaponDelay;
    private Boolean previousBreak,previousPlace;
    private Boolean previousAvoidance;
    private Integer previousAvoidanceRadius;
    private Double previousAvoidanceCost;
    private ItemStack eating=ItemStack.EMPTY;
    private long started,until,nextFood;
    private String status="Survival OFF.";
    private int scanTicks;
    private List<Seen> cached=List.of();
    private LocalPlayer scannedPlayer;
    private ClientLevel scannedWorld;

    SurvivalController(BotCore bot,BaritoneController movement,BooleanSupplier interrupt,Consumer<String> notify,BooleanSupplier idleForEquipment) {
        this.bot=bot;this.movement=movement;this.interrupt=interrupt;this.notify=notify;this.idleForEquipment=idleForEquipment;
    }
    void initialize(Path config) {this.config=config;}
    String lootCommand(String input) {
        if(input.equals("off")) {autoLoot=false;if(mode==Mode.LOOT) cancel(Minecraft.getInstance());return "Proactive looting disabled.";}
        if(input.equals("status")) return "Loot "+(autoLoot?"ON":"OFF")+" | Radius "+loot.radius()+"; death recovery "+RECOVERY_RADIUS+" | bot loot list/on/off/reload. Requires survival ON and bot RUNNING.";
        if(!Set.of("on","reload","list").contains(input)) return "Commands: bot loot list/on/off/status/reload.";
        if(busy() && !input.equals("list")) return "Wait for survival actions to finish before loading configuration.";
        try {
            var next=SurvivalPolicy.loadLoot(config.resolveSibling("loot-items.json"));
            var filter=SurvivalPolicy.loadTrash(config.resolveSibling("trash-items.json"));
            loot=next;trash=filter;
        } catch(Exception failure) {return "Could not load loot-items.json/trash-items.json; keeping current configuration.";}
        if(input.equals("on")) {autoLoot=true;nextLoot=0;return "Enabled proactive useful loot pickup when safe and idle.";}
        if(input.equals("reload")) return "Loaded loot-items.json and trash filter.";
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null) return "Enter a world first.";
        var lines=new ArrayList<String>();
        for(var drop:drops(client)) {
            var stack=drop.getItem();int priority=pickupPriority(client,stack);
            lines.add(itemId(stack)+" x"+stack.getCount()+" | "+String.format(Locale.ROOT,"%.1f",drop.distanceTo(client.player))
                    +" block | "+(priority==0?"skipped":!canFit(client,stack)?"not enough inventory space":drop.hasPickUpDelay()?"waiting for pickup":"preferred "+priority));
            if(lines.size()>=32) break;
        }
        return lines.isEmpty()?"No drops found within "+loot.radius()+" block.":String.join("\n",lines);
    }
    private String itemId(ItemStack stack) {return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}
    private List<ItemEntity> drops(Minecraft client) {
        return client.level.getEntitiesOfClass(ItemEntity.class,client.player.getBoundingBox().inflate(pickupRadius(client)),
                e->!e.isRemoved() && !e.getItem().isEmpty() && e.distanceToSqr(client.player)<=(double)pickupRadius(client)*pickupRadius(client));
    }
    private int pickupPriority(Minecraft client,ItemStack stack) {return pickupPriority(client,stack,false);}
    private int pickupPriority(Minecraft client,ItemStack stack,boolean recoverAll) {
        String id=itemId(stack);
        boolean ignored=!recoverAll && (loot.ignoreItems().contains(id) || trash.rules().stream().anyMatch(r->r.item().equals(id) && trashCount(client,id)>=r.keep()));
        var consumable=stack.get(DataComponents.CONSUMABLE);
        boolean food=stack.has(DataComponents.FOOD) && consumable!=null && (consumable.onConsumeEffects().isEmpty() || goldenFood(stack));
        boolean equipment=stack.isDamageableItem() || stack.has(DataComponents.EQUIPPABLE) || stack.is(Items.TOTEM_OF_UNDYING);
        return SurvivalPolicy.lootPriority(ignored,recoverAll || recovering(client) && recoveryItems.contains(id),equipment,food,
                loot.allowItems().contains(id) || stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS));
    }
    private boolean canFit(Minecraft client,ItemStack stack) {
        int capacity=0;
        for(int i=9;i<=44;i++) {
            var current=client.player.inventoryMenu.getSlot(i).getItem();
            if(current.isEmpty()) capacity+=Math.min(64,stack.getMaxStackSize());
            else if(ItemStack.isSameItemSameComponents(current,stack)) capacity+=Math.max(0,Math.min(64,current.getMaxStackSize())-current.getCount());
        }
        return capacity>0; // Picking part of a stack is useful too; never discard items to make space.
    }
    private int countStack(Minecraft client,ItemStack stack) {
        int total=0;
        for(int i=5;i<=45;i++) {var s=client.player.inventoryMenu.getSlot(i).getItem();if(ItemStack.isSameItemSameComponents(s,stack)) total+=s.getCount();}
        return total;
    }
    private Optional<ItemEntity> pickupTarget(Minecraft client,boolean recoveryOnly) {
        return drops(client).stream().filter(e->!e.hasPickUpDelay() && !skippedDrops.containsKey(e.getUUID())
                && pickupPriority(client,e.getItem(),recoveryOnly)>0 && canFit(client,e.getItem())
                && (!recoveryOnly || e.blockPosition().distSqr(deathPosition)<=32*32 && !unsafeRecoveryAt(e.blockPosition()))
                && client.level.hasChunkAt(e.blockPosition()) && !avoidsDestination(client,e.blockPosition())
                && !hazardousAt(client,e.blockPosition()) && !hazardousAt(client,e.blockPosition().below()))
                .sorted(Comparator.<ItemEntity>comparingInt(e->pickupPriority(client,e.getItem(),recoveryOnly)).reversed()
                        .thenComparingDouble(e->e.distanceToSqr(client.player))).findFirst();
    }
    private void tryPickup(Minecraft client) {tryPickup(client,false);}
    private void tryPickup(Minecraft client,boolean recoveryOnly) {
        nextLoot=System.nanoTime()+LOOT_SCAN_INTERVAL;
        skippedDrops.entrySet().removeIf(e->e.getValue()<System.nanoTime());
        var target=pickupTarget(client,recoveryOnly);
        boolean returnToDeath=recoveryOnly && target.isEmpty() && recoveryReturns<2 && deathPosition!=null
                && client.player.blockPosition().distSqr(deathPosition)>6*6 && client.player.blockPosition().distSqr(deathPosition)<=RECOVERY_RADIUS*RECOVERY_RADIUS
                && client.level.hasChunkAt(deathPosition) && !avoidsDestination(client,deathPosition) && !unsafeRecoveryAt(deathPosition)
                && !hazardousAt(client,deathPosition) && !hazardousAt(client,deathPosition.below());
        if(recoveryOnly && target.isEmpty() && !returnToDeath) {
            var pending=drops(client).stream().filter(e->e.blockPosition().distSqr(deathPosition)<=32*32).toList();
            if(pending.isEmpty() && client.player.blockPosition().distSqr(deathPosition)<=6*6) {
                nextLoot=System.nanoTime()+250_000_000L;
                if(++recoveryEmptyScans>=3) {clearRecovery();nextEquipment=0;announce("No loaded drops left near the death location; recovery finished.");}
            } else {
                recoveryEmptyScans=0;
                if(!pending.isEmpty() && pending.stream().noneMatch(e->canFit(client,e.getItem())) && System.nanoTime()>=nextRecoveryNotice) {
                    nextRecoveryNotice=System.nanoTime()+10_000_000_000L;
                    announce("Inventory full; preserving items, cannot collect all remaining drops yet.");
                }
            }
            return;
        }
        if(target.isEmpty() && !returnToDeath) return;
        if(recoveryOnly) {recoveryEmptyScans=0;nextLoot=System.nanoTime()+250_000_000L;}
        if(busy()) cancel(client);
        if(!claim(client)) return;
        pickup=target.isPresent()?new ItemPickup(target.get(),client,recoveryOnly):new ItemPickup(deathPosition);
        mode=Mode.LOOT;
        if(returnToDeath) {recoveryReturns++;announce("Respawn: returning to the drop area within "+RECOVERY_RADIUS+" block.");}
        else if(recoveryOnly) announce("Respawn: prioritizing recovery of "+itemId(target.get().getItem())+".");
        pickup.route(client);
    }
    private boolean hazardousAt(Minecraft client,BlockPos pos) {
        if(!client.level.hasChunkAt(pos)) return true;
        var state=client.level.getBlockState(pos);
        return !client.level.getFluidState(pos).isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW);
    }
    private final class ItemPickup {
        final ItemEntity target;
        final ItemStack expected;
        final int initial;
        final long deadline;
        final boolean recovery;
        BlockPos goal;
        long reroute;
        ItemPickup(ItemEntity target,Minecraft client,boolean recovery) {
            this.recovery=recovery;this.target=target;expected=target.getItem().copy();initial=countStack(client,expected);
            deadline=recovery?Math.min(recoveryUntil,System.nanoTime()+45_000_000_000L):System.nanoTime()+loot.timeoutSeconds()*1_000_000_000L;
        }
        ItemPickup(BlockPos location) {
            recovery=true;target=null;expected=ItemStack.EMPTY;initial=0;goal=location;
            deadline=Math.min(recoveryUntil,System.nanoTime()+45_000_000_000L);
        }
        void route(Minecraft client) {
            if(target!=null) goal=target.blockPosition();reroute=System.nanoTime()+1_000_000_000L;
            movement.execute("goto "+goal.getX()+" "+goal.getY()+" "+goal.getZ());
        }
        void tick(Minecraft client) {
            if(recovery && (!recovering(client) || !recoverySafe(client) || movement.pathCrosses(pos->unsafeRecoveryAt(pos) || avoidsDestination(client,pos) || hazardousAt(client,pos),12))) {
                finish(client,"Pausing item recovery to avoid danger; will retry within the recovery window.");return;
            }
            if(target==null) {
                if(System.nanoTime()>=nextLoot) {
                    nextLoot=System.nanoTime()+250_000_000L;
                    var found=pickupTarget(client,true);
                    if(found.isPresent()) {movement.stop();pickup=new ItemPickup(found.get(),client,true);pickup.route(client);return;}
                }
                if(System.nanoTime()>deadline || owner.blockPosition().distSqr(goal)<=4*4)
                    finish(client,"Death area checked; waiting for the next drop scan.");
                return;
            }
            if(countStack(client,expected)>initial) {
                nextEquipment=0;nextLoot=recovery?0:System.nanoTime()+LOOT_SCAN_INTERVAL;
                finish(client,"Inventory increased by "+itemId(expected)+"; checking equipment before the next loot pass.");return;
            }
            if(!recovery && !cached.isEmpty() || escapingBiome) {finish(client,"Stopping looting to handle danger.");return;}
            if(target.isRemoved() || System.nanoTime()>deadline || target.distanceToSqr(owner)>(double)pickupRadius(client)*pickupRadius(client)
                    || !ItemStack.isSameItemSameComponents(target.getItem(),expected) || !canFit(client,target.getItem())
                    || pickupPriority(client,target.getItem(),recovery)==0 || avoidsDestination(client,target.blockPosition())
                    || movement.pathCrosses(pos->avoidsDestination(client,pos),biomes.lookAhead())
                    || hazardousAt(client,target.blockPosition()) || hazardousAt(client,target.blockPosition().below())) {
                if(skippedDrops.size()<256) skippedDrops.put(target.getUUID(),System.nanoTime()+60_000_000_000L);
                finish(client,"Stop collecting "+itemId(expected)+": inventory receipt not confirmed, or target is no longer suitable.");return;
            }
            if(System.nanoTime()>=reroute && !goal.equals(target.blockPosition())) route(client);
        }
    }
    private void observeLife(Minecraft client) {
        if(client.player==null || client.level==null) {
            if(client.level==null) {lifePlayer=null;lifeWorld=null;lastInventory=Set.of();clearRecovery();observedDead=false;skippedDrops.clear();}
            return;
        }
        if(!client.player.isAlive()) {
            if(!observedDead) {
                recoveryItems=lastInventory;observedDead=true;
                deathPosition=client.player.blockPosition().immutable();deathScope=recoveryScope(client);
                recoveryUntil=System.nanoTime()+180_000_000_000L;recoveryReturns=recoveryEmptyScans=0;nextRecoveryNotice=0;
                notify.accept("Survival: player died; canceling old actions and preserving bot state to continue after respawn.");
            }
            return;
        }
        if(observedDead || lifePlayer!=client.player || lifeWorld!=client.level) {
            cancel(client);
            if(deathPosition!=null && !deathScope.equals(recoveryScope(client))) clearRecovery();
            lifePlayer=client.player;lifeWorld=client.level;observedDead=false;
            nextEquipment=nextFood=nextLoot=nextAlert=nextSurvivalAttempt=nextPillar=nextGoldenFood=0;
            lastHealth=Float.NaN;recentDamage=0;lastDamage=damageWindow=0;
            cached=List.of();scanTicks=5;skippedDrops.clear();previousAlerts=Set.of();
        }
        if(deathPosition!=null && System.nanoTime()>=recoveryUntil) clearRecovery();
        var ids=new HashSet<String>();
        for(int i=5;i<=45;i++) {var s=client.player.inventoryMenu.getSlot(i).getItem();if(!s.isEmpty()) ids.add(itemId(s));}
        lastInventory=Set.copyOf(ids);
    }
    String trashCommand(String input) {
        var client=Minecraft.getInstance();
        if(input.equals("auto off")) {autoTrash=false;if(mode==Mode.CLEAN) cancel(client);return "Auto trash cleanup disabled.";}
        if(busy()) return "Survival action active; wait or use bot stop first.";
        try {trash=SurvivalPolicy.loadTrash(config.resolveSibling("trash-items.json"));}
        catch(Exception failure) {return "Could not load trash-items.json; no items discarded.";}
        if(input.equals("reload")) return "Loaded "+trash.rules().size()+" rules from trash-items.json.";
        if(input.equals("auto on")) {autoTrash=true;nextTrash=0;return "Auto cleanup ON when at most 4 inventory slots remain, survival ON and bot RUNNING; only when safe and idle.";}
        if(input.equals("status")) return "Auto cleanup "+(autoTrash?"ON":"OFF")+" | "+trash.rules().size()+" rules. Commands: bot trash list/clean/reload/auto on/auto off.";
        if(client.player==null) return "Enter a world first.";
        if(input.equals("list")) {
            var lines=new ArrayList<String>();
            for(var r:trash.rules()) {
                int total=trashCount(client,r.item()),eligible=0;
                for(int i=9;i<=35;i++) {var stack=client.player.inventoryMenu.getSlot(i).getItem();if(trashEligible(stack,r.item())) eligible+=stack.getCount();}
                lines.add(r.item()+": have "+total+", keep >="+r.keep()+", may discard "+Math.min(eligible,trash.excess(r.item(),total)));
            }
            return String.join(" | ",lines)+". Preserving hotbar, armor, offhand, named/enchanted items and special items.";
        }
        if(!input.equals("clean")) return "Commands: bot trash status/list/clean/reload/auto on/auto off.";
        if(!enabled || bot.state()!=BotState.RUNNING || !safeMaintenance(client) || !scan(client).isEmpty()
                || avoidsDestination(client,client.player.blockPosition())) return "Cleanup requires bot start, bot survival on, no mobs, no open menu and no other task.";
        return beginCleanup(client)?"Discarding filtered items; bot stop cancels the remainder.":"No eligible trash items in inventory.";
    }
    private boolean safeMaintenance(Minecraft client) {
        return client.player!=null && client.level!=null && client.gameMode!=null && !client.isPaused()
                && client.gui.overlay()==null && client.player.isAlive() && !client.player.isCreative() && !client.player.isSpectator()
                && idleForEquipment.getAsBoolean() && client.gui.screen()==null && client.player.containerMenu==client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty() && !client.player.isUsingItem() && client.player.onGround()
                && java.util.stream.IntStream.rangeClosed(1,4).allMatch(i->client.player.inventoryMenu.getSlot(i).getItem().isEmpty());
    }
    private boolean trashEligible(ItemStack stack,String id) {
        if(stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) return false;
        // Only plain block/material stacks. Never discard equipment, food or containers.
        return !stack.has(DataComponents.CUSTOM_NAME) && !stack.isEnchanted() && !stack.isDamageableItem()
                && !stack.has(DataComponents.EQUIPPABLE) && !stack.has(DataComponents.FOOD)
                && !stack.has(DataComponents.CONTAINER) && !stack.has(DataComponents.BUNDLE_CONTENTS)
                && !stack.has(DataComponents.CUSTOM_DATA) && !stack.is(Items.TOTEM_OF_UNDYING);
    }
    private int trashCount(Minecraft client,String id) {
        int total=0;
        for(int i=9;i<=44;i++) {var s=client.player.inventoryMenu.getSlot(i).getItem();if(!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id)) total+=s.getCount();}
        return total;
    }
    private boolean beginCleanup(Minecraft client) {
        var plan=new TrashCleanup();
        if(!plan.find(client) || !claim(client)) return false;
        cleanup=plan;mode=Mode.CLEAN;nextTrash=System.nanoTime()+120_000_000_000L;plan.drop(client);return true;
    }
    private final class TrashCleanup {
        int slot,expected,confirmed,waitingTicks,operations,dropped;
        ItemStack before;
        long deadline,totalDeadline=System.nanoTime()+30_000_000_000L;
        boolean find(Minecraft client) {
            for(var rule:trash.rules()) {
                int excess=trash.excess(rule.item(),trashCount(client,rule.item()));
                if(excess<=0) continue;
                for(int i=9;i<=35;i++) {
                    var s=client.player.inventoryMenu.getSlot(i).getItem();
                    if(trashEligible(s,rule.item())) {slot=i;before=s.copy();expected=s.getCount()-(excess>=s.getCount()?s.getCount():1);return true;}
                }
            }
            return false;
        }
        void drop(Minecraft client) {
            // One vanilla interaction, then wait for observed inventory change; never retry blindly.
            client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,slot,expected==0?1:0,ContainerInput.THROW,owner);
            deadline=System.nanoTime()+2_000_000_000L;confirmed=waitingTicks=0;operations++;
        }
        void tick(Minecraft client) {
            if(!cached.isEmpty() || escapingBiome || owner.isUsingItem() || !owner.onGround()
                    || !owner.inventoryMenu.getCarried().isEmpty()
                    || java.util.stream.IntStream.rangeClosed(1,4).anyMatch(i->!owner.inventoryMenu.getSlot(i).getItem().isEmpty())) {
                finish(client,"Cleanup stopped because safety conditions changed.");return;
            }
            var s=owner.inventoryMenu.getSlot(slot).getItem();
            boolean matches=expected==0?s.isEmpty():s.getCount()==expected && ItemStack.isSameItemSameComponents(s,before);
            if(matches) confirmed++;else confirmed=0;
            if(++waitingTicks>=5 && confirmed>=3) {
                dropped+=before.getCount()-expected;
                if(operations>=128 || System.nanoTime()>totalDeadline || !find(client)) {finish(client,"Cleanup: inventory decreased by "+dropped+" items. Dropped items may be picked up again if nearby.");return;}
                drop(client);
            } else if(System.nanoTime()>deadline) {
                autoTrash=false;finish(client,"Inventory change after discard not confirmed; stopped and disabled auto cleanup, without retrying the action.");
            }
        }
    }
    boolean busy() {return mode!=Mode.WATCH;}
    String status() {return "Survival "+(enabled?"ON":"OFF")+" | Combat "+(combat?"ON":"OFF")+" | Cung "+(rangedEnabled?"ON":"OFF")+" | Shield "+(shieldEnabled?"ON":"OFF")+" | Auto equipment "+(autoEquipment?"ON":"OFF")+" | "+status;}
    java.util.Map<String,Boolean> webFeatures() {return java.util.Map.of("survival",enabled,"combat",combat,"ranged",rangedEnabled,"shield",shieldEnabled,
            "equipment",autoEquipment,"loot",autoLoot,"trash",autoTrash,"alerts",alerts);}
    String riskStatus() {
        String level=switch(assessment.level()) {
            case CLEAR -> "Safe";
            case MODERATE -> "Moderate";
            case MANAGEABLE -> "Combat possible";
            case DANGEROUS -> "Dangerous";
            case CREEPER_ALERT -> "Creeper alarm";
        };
        return level+" | "+assessment.reason();
    }
    String command(String input) {
        switch(input) {
            case "", "status": return status()+". Commands: bot survival on/off/status/reload; bot survival combat on/off; bot survival shield on/off; bot survival ranged on/off.";
            case "off": enabled=false;cancel(Minecraft.getInstance());status="Survival disabled.";return status();
            case "on", "reload":
                if(busy()) return "Survival action active; disable before loading configuration.";
                try {
                    var nextSettings=SurvivalPolicy.load(config);
                    var nextBiomes=SurvivalPolicy.loadBiomes(config.resolveSibling("danger-biomes.json"));
                    var nextLootPolicy=SurvivalPolicy.loadLoot(config.resolveSibling("loot-items.json"));
                    var nextTrashPolicy=SurvivalPolicy.loadTrash(config.resolveSibling("trash-items.json"));
                    settings=nextSettings;biomes=nextBiomes;loot=nextLootPolicy;trash=nextTrashPolicy;scanTicks=5;
                    if(input.equals("on")) enabled=true;
                    status="Loaded survival.json and danger-biomes.json; monitoring while bot RUNNING.";return status();
                }
                catch(Exception failure) {return "Could not load survival.json/danger-biomes.json; current configuration preserved. Check structure and limits.";}
            case "combat on": combat=true;return status();
            case "combat off": combat=false;if(mode==Mode.DEFEND || mode==Mode.RANGED) cancel(Minecraft.getInstance());return status();
            case "ranged on": rangedEnabled=true;return status();
            case "ranged off": rangedEnabled=false;if(mode==Mode.RANGED) cancel(Minecraft.getInstance());return status();
            case "ranged status": return "Bow combat "+(rangedEnabled?"ON":"OFF");
            case "shield on": shieldEnabled=true;nextEquipment=0;return status();
            case "shield off": shieldEnabled=false;releaseShield(Minecraft.getInstance());return status();
            case "equipment on": autoEquipment=true;nextEquipment=0;return "Enabled auto armor equipment and hotbar sorting when appropriate.";
            case "equipment off": autoEquipment=false;if(mode==Mode.EQUIP) cancel(Minecraft.getInstance());return "Auto armor equipment disabled.";
            case "equipment status": return "Auto equip "+(autoEquipment?"ON":"OFF")+" | Alerts "+(alerts?"ON":"OFF");
            case "alerts on": alerts=true;previousAlerts=Set.of();nextAlert=0;return "Enabled missing survival item alerts.";
            case "alerts off": alerts=false;return "Missing survival item alerts disabled.";
            default: return "Commands: bot survival on/off/status/reload; bot survival combat on/off; bot survival shield on/off; bot survival ranged on/off.";
        }
    }
    private void announce(String text) {
        status=text;notify.accept("Survival: "+text);
        java.util.logging.Logger.getLogger("dev.minecraftaibot.common.Survival").info(text);
    }
    private String biomeId(ClientLevel level,BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key->key.identifier().toString()).orElse("");
    }
    boolean avoidsDestination(Minecraft client,BlockPos pos) {
        return enabled && client.level!=null && client.level.hasChunkAt(pos) && biomes.avoids(biomeId(client.level,pos));
    }
    private List<Seen> scan(Minecraft client) {
        var player=client.player;
        List<Seen> threats=new ArrayList<>();
        int radius=Math.max(24,settings.scanRadius());
        for(LivingEntity mob:client.level.getEntitiesOfClass(LivingEntity.class,player.getBoundingBox().inflate(radius),e->e!=player && e.isAlive() && !e.isSpectator())) {
            String id=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
            double weight=mob instanceof AbstractCubeMob cube?SurvivalPolicy.slimeWeight(cube.getSize(),mob instanceof MagmaCube):mob.isBaby()?1.5:1;
            if(weight==0) continue;
            boolean creeper=mob instanceof Creeper;
            boolean ordinary=DEFENDABLE.contains(id);
            boolean unknownHostile=mob instanceof Enemy && !ordinary;
            boolean dangerous=creeper || settings.dangerousMobs().contains(id) || unknownHostile;
            boolean attacker=mob instanceof Mob && player.getLastAttacker()==mob;
            if(!dangerous && !ordinary && !attacker) continue;
            double distance=player.distanceTo(mob);
            if(distance>(RANGED.contains(id) || rangedEnabled?radius:settings.scanRadius())) continue;
            if(!player.hasLineOfSight(mob) && !(creeper && distance<=8)) continue;
            boolean ignited=creeper && (((Creeper)mob).isIgnited() || ((Creeper)mob).getSwelling(1)>0);
            threats.add(new Seen(mob,new SurvivalPolicy.Threat(distance,dangerous || attacker && !ordinary,creeper,ignited,
                    ordinary && !dangerous || DUELABLE_DANGEROUS.contains(id),RANGED.contains(id),weight)));
        }
        threats.sort(Comparator.comparingDouble(s->s.threat.distance()));return threats;
    }
    private int foodSlot(Minecraft client) {
        return foodSlot(client,true);
    }
    private int foodSlot(Minecraft client,boolean usableNow) {
        var items=client.player.getInventory().getNonEquipmentItems();int best=-1;double score=-Double.MAX_VALUE;
        for(int i=0;i<items.size();i++) {
            if((i<9?36+i:i)==shieldSource) continue;
            ItemStack stack=items.get(i);
            var food=stack.get(DataComponents.FOOD);var use=stack.get(DataComponents.CONSUMABLE);
            boolean golden=goldenFood(stack);
            if(food==null || use==null || food.nutrition()<=0 || !use.onConsumeEffects().isEmpty() && !golden
                    || use.consumeSeconds()>5 || usableNow && !use.canConsume(client.player,stack)) continue;
            if(golden && usableNow && (!SurvivalPolicy.emergencyFood(client.player.getHealth(),client.player.getMaxHealth(),client.player.getFoodData().getFoodLevel())
                    || System.nanoTime()<nextGoldenFood)) continue;
            double value=SurvivalPolicy.foodScore(food.nutrition(),food.saturation(),client.player.getFoodData().getFoodLevel());
            if(golden) value+=client.player.getHealth()<client.player.getMaxHealth()*0.5?(stack.is(Items.GOLDEN_APPLE)?1000:900):-100;
            if(value>score) {score=value;best=i;}
        }
        return best;
    }
    private boolean goldenFood(ItemStack stack) {return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);}
    private int foodReserve(Minecraft client) {
        long nutrition=0;
        for(var stack:client.player.getInventory().getNonEquipmentItems()) {
            var food=stack.get(DataComponents.FOOD);var use=stack.get(DataComponents.CONSUMABLE);
            if(food!=null && use!=null && food.nutrition()>0 && use.consumeSeconds()<=5
                    && (use.onConsumeEffects().isEmpty() || goldenFood(stack))) nutrition+=(long)food.nutrition()*stack.getCount();
        }
        return (int)Math.min(4096,nutrition);
    }
    private boolean usableShield(Minecraft client) {
        if(!shieldEnabled || System.nanoTime()<nextCreeperShield) return false;
        var items=new ArrayList<>(client.player.getInventory().getNonEquipmentItems());items.add(client.player.getOffhandItem());
        return items.stream().anyMatch(s->s.is(Items.SHIELD) && s.getMaxDamage()-s.getDamageValue()>3 && !client.player.getCooldowns().isOnCooldown(s));
    }
    private void shieldCreeper(Minecraft client,List<Seen> seen) {
        var nearest=seen.stream().filter(s->s.threat.creeper() && s.entity.isAlive() && !s.entity.isRemoved())
                .min(Comparator.comparingDouble(s->owner.distanceTo(s.entity))).orElse(null);
        if(nearest==null) {scanTicks=5;return;}
        Vec3 delta=nearest.entity.getBoundingBox().getCenter().subtract(owner.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90;
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        owner.setYRot(owner.getYRot()+Mth.clamp(Mth.wrapDegrees(yaw-owner.getYRot()),-15f,15f));
        owner.setXRot(Mth.clamp(owner.getXRot()+Mth.clamp(pitch-owner.getXRot(),-10f,10f),-90f,90f));
        releaseAttack(client);
        if(raiseShield(client)) shieldFailures=0;
        else if(++shieldFailures>=20) {
            nextCreeperShield=System.nanoTime()+2_000_000_000L;
            endAction(client,"Cannot keep shield up against creeper; retreating.");
        }
    }
    private int weaponSlot(Minecraft client) {
        var items=client.player.getInventory().getNonEquipmentItems();int best=-1,score=-1;
        List<String> materials=List.of("wooden","golden","stone","iron","diamond","netherite");
        for(int i=0;i<items.size();i++) {
            if((i<9?36+i:i)==shieldSource) continue;
            ItemStack stack=items.get(i);
            if(!(stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) || stack.isDamageableItem()
                    && stack.getMaxDamage()-stack.getDamageValue()<=Math.max(5,stack.getMaxDamage()/50)) continue;
            String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();int value=stack.is(ItemTags.SWORDS)?10:0;
            for(int m=0;m<materials.size();m++) if(id.startsWith("minecraft:"+materials.get(m)+"_")) value+=m;
            if(value>score) {score=value;best=i;}
        }
        return best;
    }
    private SurvivalPolicy.Gear combatGear(Minecraft client) {
        int pieces=0;boolean healthy=true;
        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)) {
            var rating=armorRating(client.player.getItemBySlot(slot),slot);
            if(rating!=null && rating.defense()>0 && !rating.utility()) {pieces++;healthy &= rating.healthy();}
        }
        int weapon=weaponSlot(client);double quality=0;
        if(weapon>=0) {
            var stack=client.player.getInventory().getNonEquipmentItems().get(weapon);
            if(stack.getMaxDamage()-stack.getDamageValue()>Math.max(5,stack.getMaxDamage()/50)) {
                String id=itemId(stack);
                quality=id.contains("netherite_")?5:id.contains("diamond_")?4:id.contains("iron_")?3:id.contains("stone_")?2:1;
                if(stack.is(ItemTags.AXES)) quality=Math.max(1,quality-1); // Slower attacks are less suited to a group.
            }
        }
        return new SurvivalPolicy.Gear(Math.max(0,Math.min(40,client.player.getAttributeValue(Attributes.ARMOR))),
                Math.max(0,Math.min(40,client.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS))),pieces,healthy,quality);
    }
    private boolean claim(Minecraft client) {
        if(busy()) return true;
        owner=client.player;world=client.level;selected=owner.getInventory().getSelectedSlot();
        if(!interrupt.getAsBoolean()) {
            nextSurvivalAttempt=System.nanoTime()+1_000_000_000L;
            if(System.nanoTime()>=nextAlert) {announce("Cannot safely clear menu/cursor; preserving items and not starting a new action.");nextAlert=System.nanoTime()+10_000_000_000L;}
            return false;
        }
        if(owner.isUsingItem()) client.gameMode.releaseUsingItem(owner);
        previousBreak=baritone.api.BaritoneAPI.getSettings().allowBreak.value;
        previousPlace=baritone.api.BaritoneAPI.getSettings().allowPlace.value;
        baritone.api.BaritoneAPI.getSettings().allowBreak.value=false;
        baritone.api.BaritoneAPI.getSettings().allowPlace.value=false;
        started=System.nanoTime();quiet=0;return true;
    }
    private boolean select(Minecraft client,int slot) {
        if(slot<0 || owner.containerMenu!=owner.inventoryMenu || !owner.containerMenu.getCarried().isEmpty()) return false;
        if(slot<9) {owner.getInventory().setSelectedSlot(slot);return true;}
        int hotbar=owner.getInventory().getSelectedSlot();ItemStack expected=owner.getInventory().getNonEquipmentItems().get(slot).copy();
        client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,slot,hotbar,ContainerInput.SWAP,owner);
        return ItemStack.isSameItemSameComponents(owner.getMainHandItem(),expected) && owner.getMainHandItem().getCount()==expected.getCount();
    }
    private int countFood() {
        return owner.getInventory().getNonEquipmentItems().stream().filter(s->ItemStack.isSameItemSameComponents(s,eating)).mapToInt(ItemStack::getCount).sum();
    }
    private void eat(Minecraft client,int slot) {
        releaseAttack(client);strikeWindow=shieldLowerTicks=0;
        releaseShield(client);
        restoreShield(client);slot=foodSlot(client);
        movement.stop();if(!select(client,slot)) {endAction(client,"Could not move food to hotbar.");return;}
        var food=owner.getMainHandItem().get(DataComponents.FOOD);
        var use=owner.getMainHandItem().get(DataComponents.CONSUMABLE);
        if(food==null || use==null || !use.onConsumeEffects().isEmpty() && !goldenFood(owner.getMainHandItem()) || !use.canConsume(owner,owner.getMainHandItem())) {
            finish(client,"Food or hunger changed; not eating yet.");nextFood=System.nanoTime()+10_000_000_000L;return;
        }
        eating=owner.getMainHandItem().copyWithCount(1);hungerBefore=owner.getFoodData().getFoodLevel();foodCountBefore=countFood();foodBefore=0;
        mode=Mode.EAT;until=System.nanoTime()+8_000_000_000L;
        client.gameMode.useItem(owner,InteractionHand.MAIN_HAND);
        if(!owner.isUsingItem()) {finish(client,"Could not start eating; skipped.");nextFood=System.nanoTime()+10_000_000_000L;return;}
        holdingUse=true;client.options.keyUse.setDown(true);
        announce("Eating "+BuiltInRegistries.ITEM.getKey(eating.getItem())+"; previous task interrupted.");
    }
    private void tickEating(Minecraft client) {
        if(countFood()<foodCountBefore) {
            releaseFood(client);
            if(++foodBefore>=3) {
                boolean golden=goldenFood(eating);
                if(golden) nextGoldenFood=System.nanoTime()+(eating.is(Items.ENCHANTED_GOLDEN_APPLE)?30_000_000_000L:8_000_000_000L);
                finish(client,"Food count decreased after eating; hunger "+owner.getFoodData().getFoodLevel()+"/20.");
                nextFood=System.nanoTime()+1_000_000_000L;
            }
            return;
        }
        if(System.nanoTime()>until || !ItemStack.isSameItemSameComponents(owner.getMainHandItem(),eating)) {
            releaseFood(client);finish(client,"Eating not confirmed; waiting before retrying.");nextFood=System.nanoTime()+10_000_000_000L;
        }
    }
    private void releaseFood(Minecraft client) {
        if(!holdingUse) return;
        holdingUse=false;client.options.keyUse.setDown(false);
        if(client.player==owner && owner.isUsingItem() && client.gameMode!=null) client.gameMode.releaseUsingItem(owner);
    }
    void tick(Minecraft client) {
        releaseAttack(client);
        observeLife(client);
        if(!enabled || bot.state()!=BotState.RUNNING || client.player==null || client.level==null || client.gameMode==null
                || !client.player.isAlive() || client.player.isSpectator() || client.player.isCreative()) {cancel(client);return;}
        if(busy() && (client.player!=owner || client.level!=world)) {cancel(client);return;}
        if(client.isPaused() || client.gui.overlay()!=null) {cancel(client);return;}
        try {
            if(++scanTicks>=5 || scannedPlayer!=client.player || scannedWorld!=client.level) {
                cached=scan(client);scanTicks=0;scannedPlayer=client.player;scannedWorld=client.level;
            }
            var seen=cached;
            passiveAlerts(client);
            var gear=combatGear(client);int reserve=foodReserve(client);
            assessment=SurvivalPolicy.assess(settings,client.player.getHealth(),client.player.getMaxHealth(),client.player.getFoodData().getFoodLevel(),
                    reserve,gear,combat,usableShield(client),seen.stream().map(Seen::threat).toList());
            long now=System.nanoTime();
            if(now-damageWindow>2_000_000_000L) {damageWindow=now;recentDamage=0;}
            float health=client.player.getHealth();
            if(Float.isFinite(lastHealth) && health<lastHealth) {recentDamage+=lastHealth-health;lastDamage=now;}
            lastHealth=health;
            if(assessment.level()!=SurvivalPolicy.Danger.CREEPER_ALERT && !seen.isEmpty()) {
                if(recentDamage>=4) assessment=new SurvivalPolicy.Assessment(SurvivalPolicy.Danger.DANGEROUS,SurvivalPolicy.Action.RETREAT,
                        seen.stream().anyMatch(v->v.threat.ranged())?20:16,"Heavy damage within 2 seconds: retreat instead of continuing to fight.");
                else if(reserve>=4 && gear.capacity()>0 && SurvivalPolicy.bowOpportunity(combat && rangedEnabled,bowSlot(client)>=0 && hasArrows(client),health,client.player.getMaxHealth(),
                        client.player.getFoodData().getFoodLevel(),seen.stream().map(Seen::threat).toList()))
                    assessment=new SurvivalPolicy.Assessment(SurvivalPolicy.Danger.MODERATE,SurvivalPolicy.Action.RANGED,20,"One distant target: use bow when shot path is clear.");
            }
            String risk=riskStatus();if(!risk.equals(previousRisk)) {previousRisk=risk;notify.accept("Danger: "+risk);}
            var decision=assessment.action();
            escapingBiome=avoidsDestination(client,client.player.blockPosition());
            if(busy() && (owner.containerMenu!=owner.inventoryMenu || client.gui.screen()!=null)) {endAction(client,"Menu changed during survival actions.");return;}
            if(decision==SurvivalPolicy.Action.CREEPER_SHIELD) {
                if(mode!=Mode.CREEPER) {
                    if(busy()) cancel(client);
                    if(!claim(client)) return;
                    movement.stop();mode=Mode.CREEPER;shieldFailures=0;
                    announce("Creeper alarm: stop attacking/eating, face the nearest creeper and hold shield within 8 blocks.");
                }
                quiet=0;shieldCreeper(client,seen);return;
            }
            if(mode==Mode.CREEPER) {
                if(seen.stream().noneMatch(s->s.threat.creeper()) && ++quiet<10) return;
                cancel(client);
            }
            boolean creeperAlert=assessment.level()==SurvivalPolicy.Danger.CREEPER_ALERT;
            if(mode==Mode.RANGED && decision!=SurvivalPolicy.Action.RANGED) cancel(client);
            if(mode==Mode.EAT && emergencyEating && !creeperAlert) {tickEating(client);return;}
            boolean urgentFood=!creeperAlert && SurvivalPolicy.emergencyFood(client.player.getHealth(),client.player.getMaxHealth(),client.player.getFoodData().getFoodLevel());
            if(mode!=Mode.EAT && urgentFood && System.nanoTime()>=nextFood && foodSlot(client)>=0
                    && !client.player.isPassenger() && client.player.containerMenu.getCarried().isEmpty()) {
                cancel(client);
                if(claim(client)) {eat(client,foodSlot(client));emergencyEating=mode==Mode.EAT;}
                else nextFood=System.nanoTime()+1_000_000_000L;
                return;
            }
            if(mode==Mode.EAT) {
                if(urgentFood || decision!=SurvivalPolicy.Action.RETREAT && decision!=SurvivalPolicy.Action.DEFEND && decision!=SurvivalPolicy.Action.RANGED) {tickEating(client);return;}
                cancel(client);
            }
            if(mode==Mode.EQUIP) {
                if(!SurvivalPolicy.equipmentOpportunity(health,client.player.getMaxHealth(),urgentFood,seen.stream().map(Seen::threat).toList()) || recentDamage>=4) cancel(client);
                else {maintenance.tick(client);return;}
            }
            // Armour is needed before assessing whether a fight can be won. Do not wait for
            // every distant hostile to disappear, or for an escape path to finish first.
            if((autoEquipment || shieldEnabled) && mode!=Mode.EAT && mode!=Mode.PILLAR && now>=nextEquipment
                    && recentDamage<4 && SurvivalPolicy.equipmentOpportunity(health,client.player.getMaxHealth(),urgentFood,seen.stream().map(Seen::threat).toList())
                    && client.gui.screen()==null && client.player.containerMenu==client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty() && client.player.onGround() && !client.player.isPassenger()
                    && java.util.stream.IntStream.rangeClosed(1,4).allMatch(i->client.player.inventoryMenu.getSlot(i).getItem().isEmpty())) {
                var plan=new EquipmentMaintenance();
                if(plan.find(client)) {
                    if(busy()) {
                        cancel(client);
                        plan=new EquipmentMaintenance();
                        if(!plan.find(client)) {nextEquipment=now+2_000_000_000L;return;}
                    }
                    nextEquipment=now+2_000_000_000L;
                    if(claim(client)) {maintenance=plan;mode=Mode.EQUIP;maintenance.start(client);return;}
                } else nextEquipment=now+2_000_000_000L;
            }
            if(mode==Mode.CLEAN) {
                if(decision==SurvivalPolicy.Action.RETREAT || decision==SurvivalPolicy.Action.DEFEND || decision==SurvivalPolicy.Action.RANGED || escapingBiome) cancel(client);
                else {cleanup.tick(client);return;}
            }
            if(mode==Mode.LOOT) {
                if(pickup.recovery?!recovering(client) || !recoverySafe(client):decision==SurvivalPolicy.Action.RETREAT || decision==SurvivalPolicy.Action.DEFEND || decision==SurvivalPolicy.Action.RANGED || escapingBiome) cancel(client);
                else {pickup.tick(client);return;}
            }
            if(autoLoot && recovering(client) && mode!=Mode.PILLAR && now>=nextLoot && recoverySafe(client)
                    && client.gui.screen()==null && client.player.containerMenu==client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty() && !client.player.isPassenger()) {
                tryPickup(client,true);
                if(mode==Mode.LOOT) return;
            }
            if(mode==Mode.PILLAR) {pillar.tick(client);return;}
            if(!escapingBiome && movement.pathCrosses(pos->avoidsDestination(client,pos),biomes.lookAhead())) {
                if(mode==Mode.RETREAT && !escapeMobs.isEmpty()) beginPillar(client,"Retreat route is about to enter a forbidden biome.");
                else if(claim(client)) endAction(client,"Route is about to enter a forbidden biome; choose another path or goal.");
                return;
            }
            if(mode==Mode.RETREAT && SurvivalPolicy.resumeCombat(decision,(now-escapeStarted)/1e9,(now-lastDamage)/1e9,escapingBiome)) {
                cancel(client);
                if(!claim(client)) return;
                mode=decision==SurvivalPolicy.Action.RANGED?Mode.RANGED:Mode.DEFEND;bowStarted=now;
                announce("Mob group is within combat capacity; switching to aim and fight.");
                if(mode==Mode.RANGED) shootBow(client,seen.getFirst().entity());else defend(client,seen.getFirst().entity());return;
            }
            if(mode==Mode.RETREAT) {retreat(client,seen);return;}
            if(!combat && !outsideRadius(seen.stream().map(Seen::entity).toList())) decision=SurvivalPolicy.Action.RETREAT;
            if(escapingBiome) decision=SurvivalPolicy.Action.RETREAT;
            if((decision==SurvivalPolicy.Action.RETREAT || decision==SurvivalPolicy.Action.DEFEND || decision==SurvivalPolicy.Action.RANGED) && System.nanoTime()<nextSurvivalAttempt) return;
            if(busy() && System.nanoTime()-started>30_000_000_000L) {endAction(client,"Danger lasted over 30 seconds; stopped for you to handle.");return;}
            if(decision==SurvivalPolicy.Action.RETREAT) {
                if(!claim(client)) return;
                if(mode!=Mode.RETREAT) {
                    releaseShield(client);movement.stop();mode=Mode.RETREAT;progressPosition=owner.position();progressAt=escapeStarted=System.nanoTime();nextGoal=0;escapeOrigins=new BlockPos[0];escapeMobs=List.of();
                    minimumEscapeRadius=seen.stream().anyMatch(s->s.threat.ranged())?20:8;
                    escapeRadius=Math.max(minimumEscapeRadius,assessment.escapeRadius());escapeReduced=false;
                    var pathSettings=baritone.api.BaritoneAPI.getSettings();
                    previousAvoidance=pathSettings.avoidance.value;previousAvoidanceRadius=pathSettings.mobAvoidanceRadius.value;previousAvoidanceCost=pathSettings.mobAvoidanceCoefficient.value;
                    pathSettings.avoidance.value=true;pathSettings.mobAvoidanceRadius.value=8;pathSettings.mobAvoidanceCoefficient.value=Math.max(5,previousAvoidanceCost);
                    announce(escapingBiome?"Searching for a route out of biome "+biomeId(world,owner.blockPosition()):"Baritone searching for a retreat at least this far from mobs: "+escapeRadius+" block.");
                }
                quiet=0;
                retreat(client,seen);
            } else if(decision==SurvivalPolicy.Action.RANGED) {
                if(!claim(client)) return;
                if(mode!=Mode.RANGED) {releaseShield(client);movement.stop();mode=Mode.RANGED;aimedMob=null;alignedTicks=0;bowStarted=now;announce("Aim at distant target and draw bow; do not chase.");}
                quiet=0;shootBow(client,seen.getFirst().entity());
            } else if(decision==SurvivalPolicy.Action.DEFEND) {
                if(!claim(client)) return;
                if(mode!=Mode.DEFEND) {movement.stop();mode=Mode.DEFEND;weaponDelay=0;aimedMob=null;alignedTicks=0;announce("Fight an eligible mob group; activate attack when in range, without chasing.");}
                quiet=0;defend(client,seen.getFirst().entity());
            } else if(busy()) {
                releaseShield(client);
                if(++quiet>=40) finish(client,"Nearby danger ended; old task canceled, assign it again based on progress.");
            } else if(decision==SurvivalPolicy.Action.EAT && System.nanoTime()>=nextFood && !client.player.isUsingItem() && !client.player.isPassenger()) {
                int food=foodSlot(client);
                if(food<0) {nextFood=System.nanoTime()+10_000_000_000L;announce("Hungry but no suitable food; will not eat food with harmful effects automatically.");return;}
                if(claim(client)) eat(client,foodSlot(client));
            } else if(autoTrash && seen.isEmpty() && System.nanoTime()>=nextTrash && safeMaintenance(client)
                    && java.util.stream.IntStream.rangeClosed(9,44).filter(i->client.player.inventoryMenu.getSlot(i).getItem().isEmpty()).count()<=4) {
                nextTrash=System.nanoTime()+120_000_000_000L;
                beginCleanup(client);
            } else if((autoEquipment || shieldEnabled) && seen.isEmpty() && System.nanoTime()>=nextEquipment && idleForEquipment.getAsBoolean()
                    && client.gui.screen()==null && client.player.containerMenu==client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty()
                    && java.util.stream.IntStream.rangeClosed(1,4).allMatch(i->client.player.inventoryMenu.getSlot(i).getItem().isEmpty())
                    && !client.player.isUsingItem() && client.player.onGround()) {
                nextEquipment=System.nanoTime()+2_000_000_000L;
                var plan=new EquipmentMaintenance();
                if(plan.find(client) && claim(client)) {maintenance=plan;mode=Mode.EQUIP;maintenance.start(client);}
                else if(autoLoot && System.nanoTime()>=nextLoot && safeMaintenance(client)) tryPickup(client);
            } else if(autoLoot && seen.isEmpty() && System.nanoTime()>=nextLoot && safeMaintenance(client)) {
                tryPickup(client);
            }
        } catch(RuntimeException failure) {endAction(client,"Could not process survival state; action stopped.");}
    }
    private int bowSlot(Minecraft client) {
        var items=client.player.getInventory().getNonEquipmentItems();
        for(int i=0;i<items.size();i++) if((i<9?36+i:i)!=shieldSource && items.get(i).is(Items.BOW)
                && items.get(i).getMaxDamage()-items.get(i).getDamageValue()>3) return i;
        return -1;
    }
    private boolean hasArrows(Minecraft client) {
        return client.player.getInventory().getNonEquipmentItems().stream().anyMatch(s->!s.isEmpty() && s.is(ItemTags.ARROWS))
                || client.player.getOffhandItem().is(ItemTags.ARROWS);
    }
    /** Switching away cancels server-side bow use without sending RELEASE_USE_ITEM (which would fire). */
    private void abortBow(Minecraft client) {
        if(!drawingBow) return;
        drawingBow=false;bowTicks=0;client.options.keyUse.setDown(false);
        if(client.player==owner) {
            int other=(owner.getInventory().getSelectedSlot()+1)%9;
            owner.getInventory().setSelectedSlot(other);
            owner.connection.send(new ServerboundSetCarriedItemPacket(other));
            owner.stopUsingItem();
        }
    }
    private static SurvivalPolicy.BowPoint bowPoint(Vec3 v) {return new SurvivalPolicy.BowPoint(v.x,v.y,v.z);}
    private boolean clearArrowPath(List<SurvivalPolicy.BowPoint> path,Vec3 launch,LivingEntity target) {
        Vec3 previous=launch;
        for(int i=1;i<path.size();i++) {
            var relative=path.get(i);Vec3 point=launch.add(relative.x(),relative.y(),relative.z());
            // The air solver cannot predict water drag; reject water/lava as well as solid blocks.
            if(world.clip(new ClipContext(previous,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,owner)).getType()!=HitResult.Type.MISS) return false;
            for(var entity:world.getEntities(owner,new AABB(previous,point).inflate(0.4),e->e!=target && e.isAlive() && e.isPickable()))
                if(entity.getBoundingBox().inflate(0.4).contains(previous) || entity.getBoundingBox().inflate(0.4).clip(previous,point).isPresent()) return false;
            previous=point;
        }
        return true;
    }
    private void shootBow(Minecraft client,LivingEntity mob) {
        if(!RANGED.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString()) || !mob.isAlive() || mob.isRemoved()
                || client.getCameraEntity()!=owner || owner.isPassenger() || owner.isInWater()
                || owner.distanceTo(mob)<=8 || owner.distanceTo(mob)>24 || !owner.hasLineOfSight(mob) || !hasArrows(client)) {
            abortBow(client);endAction(client,"Target or ammunition no longer suitable for shooting.");return;
        }
        if(aimedMob!=mob) {
            abortBow(client);aimedMob=mob;alignedTicks=0;bowStarted=System.nanoTime();
            bowLastPosition=mob.position();bowVelocity=Vec3.ZERO;bowSamples=steadyBowSamples=0;
        }
        if(System.nanoTime()-bowStarted>5_000_000_000L) {abortBow(client);endAction(client,"No reliable shot path; stopping bow draw.");return;}
        Vec3 measured=mob.position().subtract(bowLastPosition);bowLastPosition=mob.position();
        if(measured.length()>0.6) {abortBow(client);bowVelocity=Vec3.ZERO;bowSamples=steadyBowSamples=0;return;}
        // Client position samples work even when a remote entity's delta movement is stale.
        measured=new Vec3(measured.x,0,measured.z);
        steadyBowSamples=measured.subtract(bowVelocity).length()>0.15?0:steadyBowSamples+1;
        bowVelocity=bowSamples==0?measured:bowVelocity.scale(0.5).add(measured.scale(0.5));bowSamples++;
        if(!mob.onGround()) {abortBow(client);steadyBowSamples=0;return;}
        Vec3 launch=owner.getEyePosition().add(0,-0.10000000149011612,0);
        Vec3 inherited=owner.getKnownMovement();
        if(owner.onGround()) inherited=new Vec3(inherited.x,0,inherited.z);
        var aim=SurvivalPolicy.predictBow(bowPoint(mob.getBoundingBox().getCenter().subtract(launch)),bowPoint(bowVelocity),bowPoint(inherited));
        if(aim==null) {abortBow(client);return;}
        float ye=Mth.wrapDegrees((float)aim.yaw()-owner.getYRot()),pe=(float)aim.pitch()-owner.getXRot();
        owner.setYRot(owner.getYRot()+Mth.clamp(ye,-15f,15f));
        owner.setXRot(Mth.clamp(owner.getXRot()+Mth.clamp(pe,-10f,10f),-90f,90f));
        // Measure the remaining error after turning: a steadily moving target needs tracking,
        // not three ticks of an unchanged angle. Large turns still wait for tracking to settle.
        float remainingYaw=Mth.wrapDegrees((float)aim.yaw()-owner.getYRot());
        float remainingPitch=(float)aim.pitch()-owner.getXRot();
        alignedTicks=Math.abs(remainingYaw)<=0.35 && Math.abs(remainingPitch)<=0.35
                && Math.abs(ye)<=3 && Math.abs(pe)<=3?alignedTicks+1:0;
        if(!clearArrowPath(aim.path(),launch,mob)) {abortBow(client);return;}
        releaseShield(client);
        if(!drawingBow) {
            if(System.nanoTime()<nextShot || alignedTicks<3 || bowSamples<3 || steadyBowSamples<3 || owner.isUsingItem()) return;
            int slot=bowSlot(client);
            if(slot<0 || !select(client,slot)) {endAction(client,"Cannot equip bow.");return;}
            client.gameMode.useItem(owner,InteractionHand.MAIN_HAND);
            if(!owner.isUsingItem() || !owner.getUseItem().is(Items.BOW)) return;
            drawingBow=true;bowTicks=0;client.options.keyUse.setDown(true);
        }
        if(!owner.isUsingItem() || !owner.getUseItem().is(Items.BOW)) {abortBow(client);return;}
        if(SurvivalPolicy.releaseBow(++bowTicks,owner.getTicksUsingItem(),alignedTicks,steadyBowSamples>=3)) {
            var live=scan(client).stream().map(Seen::threat).toList();
            if(!SurvivalPolicy.bowOpportunity(combat && rangedEnabled,hasArrows(client),owner.getHealth(),owner.getMaxHealth(),owner.getFoodData().getFoodLevel(),live)) {
                abortBow(client);scanTicks=5;return;
            }
            // Validate the actual camera trajectory, not only the ideal aiming solution.
            var actual=SurvivalPolicy.bowPath(owner.getYRot(),owner.getXRot(),bowPoint(inherited),aim.flightTicks());
            var last=actual.getLast();Vec3 impact=launch.add(last.x(),last.y(),last.z());
            if(!mob.getBoundingBox().move(bowVelocity.scale(aim.flightTicks()+1)).inflate(0.05).contains(impact)
                    || !clearArrowPath(actual,launch,mob)) {abortBow(client);return;}
            drawingBow=false;client.options.keyUse.setDown(false);client.gameMode.releaseUsingItem(owner);
            nextShot=System.nanoTime()+750_000_000L;bowStarted=System.nanoTime();bowTicks=0;
        }
    }
    private void defend(Minecraft client,LivingEntity mob) {
        if(shieldLowerTicks>0) shieldLowerTicks--;
        if(!mob.isAlive() || mob.isRemoved() || client.getCameraEntity()!=owner || owner.isUsingItem() && !holdingShield || owner.isPassenger()) {
            releaseShield(client);
            aimedMob=null;alignedTicks=0;return;
        }
        int slot=weaponSlot(client);
        int previousSlot=owner.getInventory().getSelectedSlot();
        var previousWeapon=owner.getMainHandItem().getItem();
        if(slot<0 || !select(client,slot)) {endAction(client,"No usable weapon left for combat.");return;}
        if(previousSlot!=owner.getInventory().getSelectedSlot() || previousWeapon!=owner.getMainHandItem().getItem()) {
            weaponDelay=0;alignedTicks=0;
        }
        if(aimedMob!=mob) {aimedMob=mob;alignedTicks=0;weaponDelay=0;strikeWindow=0;}
        if(!owner.hasLineOfSight(mob)) {
            releaseShield(client);
            alignedTicks=0;return;
        }
        // Turn the actual player camera. Leave time for normal client ticks to send rotation
        // and refresh vanilla picking before using the normal melee interaction.
        // Torso stays inside the hit box even when a nearby mob's head moves past the camera.
        Vec3 delta=mob.getBoundingBox().getCenter().subtract(owner.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90;
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        float yawError=Mth.wrapDegrees(yaw-owner.getYRot());
        float pitchError=pitch-owner.getXRot();
        owner.setYRot(owner.getYRot()+Mth.clamp(yawError,-15f,15f));
        owner.setXRot(Mth.clamp(owner.getXRot()+Mth.clamp(pitchError,-10f,10f),-90f,90f));
        if(Math.abs(yawError)>2 || Math.abs(pitchError)>2) alignedTicks=0;
        else alignedTicks++;
        weaponDelay++;
        boolean opportunity=alignedTicks>=2 && weaponDelay>=3 && owner.getAttackStrengthScale(0)>=0.9f
                && owner.isWithinAttackRange(owner.getMainHandItem(),mob.getBoundingBox(),0);
        boolean ready=opportunity && client.crosshairPickEntity==mob
                && client.hitResult instanceof EntityHitResult hit && hit.getEntity()==mob;
        boolean ranged=RANGED.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        if(strikeWindow>0) {
            strikeWindow--;releaseShield(client);
            if(!opportunity || shieldLowerTicks>0 || !ready) return;
        } else if(opportunity) {
            // Latch a short attack window instead of re-raising the shield on a stale pick result.
            strikeWindow=8;releaseShield(client);shieldLowerTicks=2;return;
        } else {
        if(SurvivalPolicy.shouldShield(shieldEnabled,true,alignedTicks>=2,true,ready,owner.distanceTo(mob),ranged)) raiseShield(client);
        else if(holdingShield) {releaseShield(client);shieldLowerTicks=2;}
            return;
        }
        if(client.options.keyAttack.isUnbound()) {endAction(client,"Attack key is unbound in Minecraft settings.");return;}
        if(client.options.keyAttack.isDown()) return;
        KeyMapping.click(InputConstants.getKey(client.options.keyAttack.saveString()));
        attackQueued=true;weaponDelay=0;strikeWindow=0;
    }
    private void releaseAttack(Minecraft client) {
        if(attackQueued) {client.options.keyAttack.consumeClick();attackQueued=false;}
    }
    private SurvivalPolicy.ArmorRating armorRating(ItemStack stack,EquipmentSlot slot) {
        if(stack.isEmpty()) return null;
        boolean binding=stack.getEnchantments().keySet().stream().anyMatch(e->e.is(Enchantments.BINDING_CURSE));
        var attributes=stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,ItemAttributeModifiers.EMPTY);
        double armor=attributes.compute(Attributes.ARMOR,0,slot),toughness=attributes.compute(Attributes.ARMOR_TOUGHNESS,0,slot);
        int protection=0,special=0;
        for(var entry:stack.getEnchantments().entrySet()) {
            if(entry.getKey().is(Enchantments.PROTECTION)) protection+=entry.getIntValue();
            else if(entry.getKey().is(Enchantments.FIRE_PROTECTION) || entry.getKey().is(Enchantments.BLAST_PROTECTION)
                    || entry.getKey().is(Enchantments.PROJECTILE_PROTECTION)) special+=entry.getIntValue();
        }
        boolean utility=stack.is(Items.ELYTRA) || armor<=0;
        return new SurvivalPolicy.ArmorRating(armor,toughness,protection,special,
                stack.getMaxDamage()-stack.getDamageValue(),stack.getMaxDamage(),binding,utility);
    }
    private double hotbarScore(ItemStack stack,int role) {
        if(stack.isEmpty() || role==5) return -1;
        int tier=itemId(stack).contains("netherite_")?5:itemId(stack).contains("diamond_")?4:itemId(stack).contains("iron_")?3
                :itemId(stack).contains("stone_")?2:1;
        int enchantments=0;
        boolean suitable=switch(role) {
            case 0 -> stack.is(ItemTags.SWORDS);
            case 1 -> stack.is(ItemTags.AXES);
            case 2 -> stack.is(ItemTags.PICKAXES);
            case 3 -> stack.is(Items.BOW);
            case 4 -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.DIRT)
                    || stack.is(Items.STONE) || stack.is(Items.NETHERRACK) || stack.is(Items.ANDESITE)
                    || stack.is(Items.DIORITE) || stack.is(Items.GRANITE) || stack.is(ItemTags.PLANKS);
            case 6 -> stack.has(DataComponents.FOOD) && !goldenFood(stack) && stack.has(DataComponents.CONSUMABLE)
                    && stack.get(DataComponents.CONSUMABLE).onConsumeEffects().isEmpty();
            case 7 -> goldenFood(stack);
            case 8 -> stack.is(ItemTags.ARROWS);
            default -> false;
        };
        if(!suitable) return -1;
        for(var enchantment:stack.getEnchantments().entrySet()) {
            var key=enchantment.getKey();
            if((role==0 && (key.is(Enchantments.SHARPNESS) || key.is(Enchantments.SMITE)))
                    || (role==1 || role==2) && key.is(Enchantments.EFFICIENCY)
                    || role==3 && (key.is(Enchantments.POWER) || key.is(Enchantments.INFINITY))
                    || key.is(Enchantments.UNBREAKING) || key.is(Enchantments.MENDING)) enchantments+=enchantment.getIntValue();
        }
        if(role==3) tier=1;
        if(role==4) tier=stack.is(Items.COBBLESTONE)?2:1;
        if(role==6) {
            var food=stack.get(DataComponents.FOOD);
            if(food.nutrition()<=0 || stack.get(DataComponents.CONSUMABLE).consumeSeconds()>5) return -1;
            return food.nutrition()*100+food.saturation()*10+stack.getCount();
        }
        if(role==7) tier=stack.is(Items.ENCHANTED_GOLDEN_APPLE)?2:1;
        if(role==8) tier=stack.is(Items.ARROW)?2:1;
        return SurvivalPolicy.hotbarScore(tier,enchantments,stack.getMaxDamage()-stack.getDamageValue(),stack.getMaxDamage(),stack.getCount());
    }
    private final class EquipmentMaintenance {
        private int source,armorSlot,staging,stable,ticks;
        private ItemStack candidate,previous,staged;
        private boolean hotbar;
        private long deadline;
        private boolean find(Minecraft client) {
            var menu=client.player.inventoryMenu;
            var offhand=client.player.getOffhandItem();
            if(shieldEnabled) {
                int best=-1,remaining=3;
                for(int j=9;j<=44;j++) {
                    var item=menu.getSlot(j).getItem();
                    int durability=item.getMaxDamage()-item.getDamageValue();
                    if(item.is(Items.SHIELD) && item.getCount()==1 && menu.getSlot(45).mayPlace(item)
                            && SurvivalPolicy.shouldEquipShield(offhand.is(Items.SHIELD),offhand.is(Items.TOTEM_OF_UNDYING),
                                    offhand.getMaxDamage()-offhand.getDamageValue(),durability) && durability>remaining) {
                        best=j;remaining=durability;
                    }
                }
                if(best>=0) {
                    source=best;armorSlot=45;candidate=menu.getSlot(best).getItem().copy();previous=offhand.copy();return true;
                }
            }
            if(!autoEquipment) return false;
            EquipmentSlot[] slots={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
            for(int i=0;i<slots.length;i++) {
                int destination=5+i;var current=menu.getSlot(destination).getItem();
                var currentRating=armorRating(current,slots[i]);int best=-1;double score=Double.NEGATIVE_INFINITY;
                // Keep leather boots' powder-snow protection while standing in powder snow.
                if(current.is(Items.LEATHER_BOOTS) && (client.level.getBlockState(client.player.blockPosition()).is(Blocks.POWDER_SNOW)
                        || client.level.getBlockState(client.player.blockPosition().below()).is(Blocks.POWDER_SNOW))) continue;
                for(int j=9;j<45;j++) {
                    var item=menu.getSlot(j).getItem();var equippable=item.get(DataComponents.EQUIPPABLE);
                    if(item.getCount()!=1 || equippable==null || equippable.slot()!=slots[i] || !menu.getSlot(destination).mayPlace(item)) continue;
                    var rating=armorRating(item,slots[i]);
                    if(SurvivalPolicy.shouldEquipArmor(currentRating,rating) && rating.score()>score) {score=rating.score();best=j;}
                }
                if(best>=0) {
                    source=best;armorSlot=destination;candidate=menu.getSlot(best).getItem().copy();previous=current.copy();return true;
                }
            }
            return findHotbar(client);
        }
        private boolean findHotbar(Minecraft client) {
            if(busy() || !idleForEquipment.getAsBoolean() || recovering(client)
                    || cached.stream().anyMatch(e->e.threat.distance()<=(e.threat.ranged()?20:8))) return false;
            var menu=client.player.inventoryMenu;
            for(int index=0;index<9;index++) {
                int destination=36+index;var current=menu.getSlot(destination).getItem();
                int best=-1;double bestScore=hotbarScore(current,index);
                for(int slot=9;slot<=44;slot++) {
                    double score=hotbarScore(menu.getSlot(slot).getItem(),index);
                    if(score>=0 && score>bestScore+0.01) {best=slot;bestScore=score;}
                }
                if(best<0 && !current.isEmpty() && hotbarScore(current,index)<0) {
                    for(int slot=9;slot<=35;slot++) if(menu.getSlot(slot).getItem().isEmpty()) {best=slot;break;}
                }
                if(best>=0 && best!=destination) {
                    hotbar=true;source=best;armorSlot=destination;candidate=menu.getSlot(best).getItem().copy();previous=current.copy();return true;
                }
            }
            return false;
        }
        private boolean matches(ItemStack actual,ItemStack expected) {
            return ItemStack.isSameItemSameComponents(actual,expected) && actual.getCount()==expected.getCount();
        }
        private void swap(Minecraft client,int slot,int hotbar) {
            client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,slot,hotbar,ContainerInput.SWAP,owner);
        }
        private void start(Minecraft client) {
            var menu=owner.inventoryMenu;
            if(!matches(menu.getSlot(source).getItem(),candidate) || !matches(menu.getSlot(armorSlot).getItem(),previous)) {
                finish(client,"Inventory changed; skipping auto equipment.");return;
            }
            if(hotbar) {
                swap(client,source,armorSlot-36);
                deadline=System.nanoTime()+2_000_000_000L;return;
            }
            if(armorSlot==45) {
                // Inventory-menu slot 45 is the offhand; SWAP button 40 targets it directly.
                swap(client,source,40);
                deadline=System.nanoTime()+2_000_000_000L;return;
            }
            int hotbar=source>=36?source-36:owner.getInventory().getSelectedSlot();staging=36+hotbar;
            staged=menu.getSlot(staging).getItem().copy();
            if(source<36) {
                swap(client,source,hotbar);
                if(!matches(menu.getSlot(staging).getItem(),candidate)) {fail(client);return;}
            }
            swap(client,armorSlot,hotbar);
            if(source<36) {
                // Return the staged tool to its original hotbar slot, using swaps with no carried cursor.
                if(!matches(menu.getSlot(source).getItem(),staged)) {fail(client);return;}
                swap(client,source,hotbar);
            }
            deadline=System.nanoTime()+2_000_000_000L;
        }
        private void fail(Minecraft client) {
            nextEquipment=System.nanoTime()+60_000_000_000L;
            finish(client,"Auto equipment not confirmed; keeping items in inventory and waiting before retrying.");
        }
        private void tick(Minecraft client) {
            if(System.nanoTime()>deadline) {fail(client);return;}
            if(++ticks<5) return;
            var menu=owner.inventoryMenu;
            boolean correct=matches(menu.getSlot(armorSlot).getItem(),candidate) && matches(menu.getSlot(source).getItem(),previous)
                    && (hotbar || armorSlot==45 || source>=36 || matches(menu.getSlot(staging).getItem(),staged));
            stable=correct?stable+1:0;
            if(stable>=3) {
                finish(client,hotbar?"Sorted hotbar slot "+(armorSlot-35)+"; old items stay in inventory.":armorSlot==45?"Shield auto equipped in offhand (client state); previous offhand item stays in the shield's old slot.":"Auto equipped "+BuiltInRegistries.ITEM.getKey(candidate.getItem())+" (client state); old armor stays in inventory.");
                nextEquipment=0; // Recheck the remaining pieces immediately after this confirmed swap.
            }
        }
    }
    private void passiveAlerts(Minecraft client) {
        if(!alerts || System.nanoTime()<nextAlert) return;
        nextAlert=System.nanoTime()+10_000_000_000L;
        var items=client.player.getInventory().getNonEquipmentItems();var issues=new LinkedHashSet<String>();
        if(foodSlot(client,false)<0) issues.add("No safe food.");
        if(shieldEnabled && !client.player.getOffhandItem().is(Items.SHIELD) && items.stream().noneMatch(s->s.is(Items.SHIELD))) issues.add("No shield.");
        Set<Block> blocks=Set.of(Blocks.COBBLESTONE,Blocks.DIRT,Blocks.COBBLED_DEEPSLATE,Blocks.NETHERRACK,Blocks.STONE,Blocks.ANDESITE,Blocks.DIORITE,Blocks.GRANITE);
        int count=items.stream().filter(s->blocks.stream().anyMatch(b->s.is(b.asItem()))).mapToInt(ItemStack::getCount).sum();
        if(count<3) issues.add("Missing 3 blocks for an emergency pillar.");
        for(int i=5;i<=8;i++) {
            var stack=client.player.inventoryMenu.getSlot(i).getItem();
            if(stack.isDamageableItem() && stack.getMaxDamage()-stack.getDamageValue()<=Math.max(5,stack.getMaxDamage()/50))
                issues.add("Armor nearly broken: "+BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        if(client.player.getHealth()<settings.combatMinHealth()) issues.add("Low health; avoid combat.");
        var hand=client.player.getMainHandItem();
        if(hand.isDamageableItem() && hand.getMaxDamage()-hand.getDamageValue()<=3)
            issues.add("Held item nearly broken: "+BuiltInRegistries.ITEM.getKey(hand.getItem()));
        var offhand=client.player.getOffhandItem();
        if(offhand.is(Items.SHIELD) && offhand.getMaxDamage()-offhand.getDamageValue()<=3) issues.add("Offhand shield nearly broken.");
        for(String issue:issues) if(!previousAlerts.contains(issue)) notify.accept("Preparation: "+issue);
        previousAlerts=Set.copyOf(issues);
    }
    private boolean raiseShield(Minecraft client) {
        if(!shieldEnabled || holdingUse || owner==null || client.player!=owner || owner.containerMenu!=owner.inventoryMenu) return false;
        if(holdingShield) {
            if(owner.isUsingItem() && owner.getUsedItemHand()==InteractionHand.OFF_HAND && owner.getUseItem().is(Items.SHIELD)) return true;
            releaseShield(client);
        }
        if(owner.isUsingItem()) return false;
        if(!owner.getOffhandItem().is(Items.SHIELD) && shieldSource<0) {
            var items=owner.getInventory().getNonEquipmentItems();
            for(int i=0;i<items.size();i++) if(items.get(i).is(Items.SHIELD) && items.get(i).getMaxDamage()-items.get(i).getDamageValue()>3) {
                shieldSource=i<9?36+i:i;oldOffhand=owner.getOffhandItem().copy();
                client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,shieldSource,40,ContainerInput.SWAP,owner);
                break;
            }
        }
        var shield=owner.getOffhandItem();
        if(!shield.is(Items.SHIELD) || shield.getMaxDamage()-shield.getDamageValue()<=3 || owner.getCooldowns().isOnCooldown(shield)) return false;
        client.gameMode.useItem(owner,InteractionHand.OFF_HAND);
        if(!owner.isUsingItem() || owner.getUsedItemHand()!=InteractionHand.OFF_HAND || !owner.getUseItem().is(Items.SHIELD)) return false;
        holdingShield=true;client.options.keyUse.setDown(true);return true;
    }
    private void releaseShield(Minecraft client) {
        if(!holdingShield) return;
        holdingShield=false;client.options.keyUse.setDown(false);
        if(client.player==owner && owner.isUsingItem() && owner.getUsedItemHand()==InteractionHand.OFF_HAND && client.gameMode!=null)
            client.gameMode.releaseUsingItem(owner);
    }
    private void restoreShield(Minecraft client) {
        if(shieldSource<0) return;
        if(client.player==owner && client.level==world && client.gameMode!=null && owner.containerMenu==owner.inventoryMenu
                && owner.containerMenu.getCarried().isEmpty()) {
            var source=owner.inventoryMenu.getSlot(shieldSource).getItem();
            if(ItemStack.isSameItemSameComponents(source,oldOffhand) && source.getCount()==oldOffhand.getCount()
                    && (owner.getOffhandItem().isEmpty() || owner.getOffhandItem().is(Items.SHIELD))) {
                client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,shieldSource,40,ContainerInput.SWAP,owner);
            } else notify.accept("Survival: inventory changed; keeping current shield/offhand positions to avoid swapping the wrong item.");
        }
        shieldSource=-1;oldOffhand=ItemStack.EMPTY;
    }
    private void coverWhilePlanning(Minecraft client,List<Seen> seen) {
        if(movement.hasEscapePath() || seen.isEmpty()) {releaseShield(client);return;}
        var threat=seen.getFirst();
        if(!SurvivalPolicy.shouldShield(shieldEnabled,true,true,true,false,owner.distanceTo(threat.entity),threat.threat.ranged())
                || !owner.hasLineOfSight(threat.entity)) {releaseShield(client);return;}
        Vec3 delta=threat.entity.getEyePosition().subtract(owner.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90;
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        float yawError=Mth.wrapDegrees(yaw-owner.getYRot()),pitchError=pitch-owner.getXRot();
        owner.setYRot(owner.getYRot()+Mth.clamp(yawError,-15f,15f));
        owner.setXRot(Mth.clamp(owner.getXRot()+Mth.clamp(pitchError,-10f,10f),-90f,90f));
        if(Math.abs(yawError)<2 && Math.abs(pitchError)<2) raiseShield(client);else releaseShield(client);
    }
    private boolean hazardous(BlockPos pos) {
        if(!world.hasChunkAt(pos)) return true;
        var state=world.getBlockState(pos);
        return !world.getFluidState(pos).isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW);
    }
    private boolean safe(Vec3 point) {
        var box=owner.getBoundingBox().move(point.subtract(owner.position()));
        if(!world.noCollision(owner,box)) return false;
        // Check the full footprint, not only its center: a corner over a ledge is unsafe.
        for(double x:new double[]{box.minX+0.01,box.maxX-0.01}) for(double z:new double[]{box.minZ+0.01,box.maxZ-0.01}) {
            var feet=BlockPos.containing(x,box.minY+0.05,z);
            var head=BlockPos.containing(x,box.maxY-0.05,z);
            var floor=BlockPos.containing(x,box.minY-0.05,z);
            if(hazardous(feet) || hazardous(head) || hazardous(floor)
                    || !world.getBlockState(floor).isCollisionShapeFullBlock(world,floor)) return false;
        }
        return true;
    }
    private boolean outsideRadius(List<LivingEntity> mobs) {
        return outsideRadius(mobs,8);
    }
    private boolean outsideRadius(List<LivingEntity> mobs,double radius) {
        var player=Minecraft.getInstance().player;
        return SurvivalPolicy.outsideEscapeRadius(new SurvivalPolicy.PositionXZ(player.getX(),player.getZ()),
                mobs.stream().filter(e->e.isAlive() && !e.isRemoved()).map(e->new SurvivalPolicy.PositionXZ(e.getX(),e.getZ())).toList(),radius);
    }
    private void retreat(Minecraft client,List<Seen> seen) {
        long now=System.nanoTime();
        var tracked=new LinkedHashSet<>(escapeMobs);
        seen.stream().map(Seen::entity).forEach(tracked::add);
        escapeMobs=tracked.stream().filter(e->e.isAlive() && !e.isRemoved()).limit(128).toList();
        if(escapeMobs.stream().anyMatch(e->RANGED.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) && minimumEscapeRadius<20) {
            minimumEscapeRadius=20;escapeRadius=Math.max(20,escapeRadius);nextGoal=0;escapeOrigins=new BlockPos[0];
            announce("Ranged mobs present; maintain a retreat target of at least 20 blocks.");
        }
        if(escapeMobs.size()>1 && escapeRadius<16 && !escapeReduced) {
            escapeRadius=16;nextGoal=0;escapeOrigins=new BlockPos[0];
            announce("Multiple mobs; prefer a route reaching a distance of 16 blocks.");
        }
        if(!escapingBiome && outsideRadius(escapeMobs,escapeRadius)) {
            releaseShield(client);
            if(++quiet>=10) finish(client,"Now at least this far from tracked mobs: "+escapeRadius+" blocks; stopping retreat.");
            return;
        }
        quiet=0;
        if(now-started>30_000_000_000L) {beginPillar(client,"Retreat lasted over 30 seconds.");return;}
        if(owner.position().distanceToSqr(progressPosition)>=0.09) {progressPosition=owner.position();progressAt=now;}
        if(now-progressAt>3_000_000_000L) {retryEscapeOrPillar(client,"Baritone has not moved for 3 seconds.");return;}
        if(now-escapeStarted>1_500_000_000L && !movement.hasEscapePath() && !movement.searching()) {
            retryEscapeOrPillar(client,"Baritone has not found an escape route.");return;
        }
        coverWhilePlanning(client,seen);
        if(now<nextGoal) return;
        nextGoal=now+1_000_000_000L;
        if(!escapeMobs.isEmpty()) {
            BlockPos[] origins=escapeMobs.stream().map(e->e.blockPosition().immutable()).toArray(BlockPos[]::new);
            boolean changed=origins.length!=escapeOrigins.length;
            if(!changed) for(int i=0;i<origins.length;i++) if(origins[i].distSqr(escapeOrigins[i])>=4) {changed=true;break;}
            if(changed || !movement.hasEscapePath() && !movement.searching()) {movement.runAway(origins,escapeRadius);escapeOrigins=origins;}
        } else if(escapingBiome && !movement.busy()) {
            BlockPos origin=owner.blockPosition();
            for(int radius=2;radius<=16;radius+=2) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                if(dx==0 && dz==0) continue;
                BlockPos pos=origin.offset(dx*radius,0,dz*radius);
                if(!avoidsDestination(client,pos) && safe(Vec3.atBottomCenterOf(pos))) {
                    movement.execute("goto "+pos.getX()+" "+pos.getY()+" "+pos.getZ());return;
                }
            }
            endAction(client,"No position outside the forbidden biome found in loaded chunks.");
        }
    }
    private void retryEscapeOrPillar(Minecraft client,String reason) {
        if(minimumEscapeRadius>=20) {retryRangedEscape(client,reason);return;}
        if(escapeRadius>8) {
            releaseShield(client);movement.stop();escapeRadius=SurvivalPolicy.smallerEscapeRadius(escapeRadius);escapeReduced=true;
            progressPosition=owner.position();progressAt=escapeStarted=System.nanoTime();nextGoal=0;escapeOrigins=new BlockPos[0];
            announce(reason+" Trying a route at a distance from mobs of "+escapeRadius+" blocks before building a pillar.");
        } else beginPillar(client,reason);
    }
    private void beginPillar(Minecraft client,String reason) {
        if(minimumEscapeRadius>=20) {retryRangedEscape(client,reason);return;}
        if(System.nanoTime()<nextPillar) {coverWhilePlanning(client,cached);return;}
        releaseShield(client);
        movement.stop();
        if(escapeMobs.isEmpty()) {endAction(client,reason+" No mob requires a defensive pillar.");return;}
        pillar=new EmergencyPillar();mode=Mode.PILLAR;
        announce(reason+" Trying to jump and place 3 blocks underfoot.");
        pillar.tick(client);
    }
    private void retryRangedEscape(Minecraft client,String reason) {
        releaseShield(client);movement.stop();
        if(System.nanoTime()-started>30_000_000_000L) {endAction(client,reason+" No route reaching 20 blocks found; a 3-block pillar is not safe against ranged mobs.");return;}
        progressPosition=owner.position();progressAt=escapeStarted=System.nanoTime();nextGoal=0;escapeOrigins=new BlockPos[0];
        announce(reason+" Finding another path while keeping a target distance of 20 blocks.");
    }
    private final class EmergencyPillar {
        private final List<Block> materials=List.of(Blocks.COBBLESTONE,Blocks.DIRT,Blocks.COBBLED_DEEPSLATE,
                Blocks.NETHERRACK,Blocks.STONE,Blocks.ANDESITE,Blocks.DIORITE,Blocks.GRANITE);
        private final SurvivalPolicy.PillarProgress progress=new SurvivalPolicy.PillarProgress();
        private BlockPos base,target;
        private Block block;
        private JumpPlacementGate jump;
        private boolean holdingJump,waitingConfirmation;
        private int before,stable;
        private long deadline=System.nanoTime()+2_000_000_000L;
        private int slot() {
            var items=owner.getInventory().getNonEquipmentItems();
            for(Block material:materials) for(int i=0;i<items.size();i++) if(items.get(i).is(material.asItem())) return i;
            return -1;
        }
        private int count(Block material) {return owner.getInventory().getNonEquipmentItems().stream().filter(s->s.is(material.asItem())).mapToInt(ItemStack::getCount).sum();}
        private void release(Minecraft client) {if(holdingJump) {client.options.keyJump.setDown(false);holdingJump=false;}}
        private void fail(Minecraft client,String reason) {
            nextPillar=System.nanoTime()+20_000_000_000L;
            endAction(client,reason+" Confirmed pillar height: "+progress.placed()+"/3 block.");
        }
        private void tick(Minecraft client) {
            if(System.nanoTime()>deadline) {fail(client,"Jump/placement timed out; not repeating the placement.");return;}
            if(base==null) {
                if(!owner.onGround() || owner.getDeltaMovement().horizontalDistanceSqr()>0.0004) return;
                base=owner.blockPosition().immutable();
                int available=materials.stream().mapToInt(this::count).sum();
                if(available<3 || Math.abs(owner.getBoundingBox().minY-base.getY())>0.02
                        || hazardous(base.below()) || !world.getBlockState(base.below()).isCollisionShapeFullBlock(world,base.below())
                        || world.getBlockState(base.below()).getBlock() instanceof EntityBlock
                        || base.getY()+5>=world.getMaxY() || !world.noCollision(owner,owner.getBoundingBox().expandTowards(0,3.3,0))) {
                    fail(client,"Missing 3 suitable blocks, unsuitable support, or insufficient overhead clearance.");return;
                }
                for(int i=0;i<5;i++) if(hazardous(base.above(i)) || !world.getBlockState(base.above(i)).isAir()) {
                    fail(client,"Planned pillar column contains liquid, hazards or obstacles.");return;
                }
            }
            if(owner.blockPosition().getX()!=base.getX() || owner.blockPosition().getZ()!=base.getZ()) {
                fail(client,"Player moved off the planned column, possibly from knockback.");return;
            }
            if(waitingConfirmation) {
                boolean landed=owner.onGround() && Math.abs(owner.getBoundingBox().minY-(target.getY()+1))<0.05;
                boolean seen=world.getBlockState(target).is(block) && count(block)==before-1;
                if(seen && landed) stable++;else stable=0;
                if(stable>=3 && progress.confirm(progress.placed(),seen,landed)) {
                    waitingConfirmation=false;target=null;deadline=System.nanoTime()+2_000_000_000L;
                    announce("Standing on pillar "+progress.placed()+"/3 block.");
                    if(progress.complete()) {
                        nextPillar=System.nanoTime()+30_000_000_000L;
                        endAction(client,"Built a 3-block pillar; continuing survival monitoring.");return;
                    }
                }
                return;
            }
            if(jump==null) {
                if(!owner.onGround() || owner.getDeltaMovement().horizontalDistanceSqr()>0.0004) return;
                owner.setXRot(Math.min(90,owner.getXRot()+30));
                if(owner.getXRot()<89) return;
                target=base.above(progress.placed());
                if(Math.abs(owner.getBoundingBox().minY-target.getY())>0.05 || !world.getBlockState(target).isAir()
                        || !world.noCollision(owner,owner.getBoundingBox().expandTowards(0,1.3,0))) {
                    fail(client,"Not enough clearance to jump-place the next block.");return;
                }
                int source=slot();
                if(source<0 || !select(client,source)) {fail(client,"Cannot move building material to hotbar.");return;}
                block=((net.minecraft.world.item.BlockItem)owner.getMainHandItem().getItem()).getBlock();before=count(block);
                jump=new JumpPlacementGate();holdingJump=true;client.options.keyJump.setDown(true);
                deadline=System.nanoTime()+2_000_000_000L;return;
            }
            if(!owner.onGround()) release(client);
            var decision=jump.tick(true,world.getBlockState(target).isAir(),owner.getBoundingBox().minY,target.getY(),owner.onGround());
            if(decision==JumpPlacementGate.Decision.ABORT) {fail(client,"Jump too low or placement cell changed.");return;}
            if(decision!=JumpPlacementGate.Decision.PLACE) return;
            release(client);jump=null;
            if(owner.getBoundingBox().intersects(new AABB(target))) {fail(client,"Player has not left the placement cell.");return;}
            BlockPos support=target.below();
            Vec3 point=new Vec3(owner.getX(),support.getY()+0.999,owner.getZ());
            BlockHitResult hit=world.clip(new ClipContext(owner.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
            if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getDirection()!=Direction.UP
                    || owner.getEyePosition().distanceToSqr(hit.getLocation())>Math.pow(owner.blockInteractionRange(),2)) {
                fail(client,"Cannot aim at/reach the top face of the pillar support block.");return;
            }
            client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,hit);owner.swing(InteractionHand.MAIN_HAND);
            waitingConfirmation=true;stable=0;deadline=System.nanoTime()+3_000_000_000L;
        }
    }
    private void endAction(Minecraft client,String reason) {
        cancel(client);nextSurvivalAttempt=System.nanoTime()+1_000_000_000L;
        nextFood=Math.max(nextFood,nextSurvivalAttempt);
        announce(reason+" Action ended; survival remains active, without automatic PAUSED state.");
    }
    private void finish(Minecraft client,String message) {cancel(client);announce(message);}
    void cancel(Minecraft client) {
        abortBow(client);
        releaseAttack(client);
        releaseShield(client);
        restoreShield(client);shieldLowerTicks=0;
        maintenance=null;
        cleanup=null;
        pickup=null;
        emergencyEating=false;strikeWindow=0;
        releaseFood(client);
        if(busy()) {
            movement.stop();if(client.player==owner) owner.getInventory().setSelectedSlot(selected);
        }
        if(pillar!=null) {pillar.release(client);pillar=null;}
        progressPosition=null;escapeMobs=List.of();escapeOrigins=new BlockPos[0];
        bowLastPosition=null;bowVelocity=Vec3.ZERO;bowSamples=steadyBowSamples=0;
        mode=Mode.WATCH;owner=null;world=null;quiet=0;aimedMob=null;alignedTicks=0;weaponDelay=0;
        if(previousBreak!=null) {baritone.api.BaritoneAPI.getSettings().allowBreak.value=previousBreak;previousBreak=null;}
        if(previousPlace!=null) {baritone.api.BaritoneAPI.getSettings().allowPlace.value=previousPlace;previousPlace=null;}
        if(previousAvoidance!=null) {
            var pathSettings=baritone.api.BaritoneAPI.getSettings();
            pathSettings.avoidance.value=previousAvoidance;pathSettings.mobAvoidanceRadius.value=previousAvoidanceRadius;pathSettings.mobAvoidanceCoefficient.value=previousAvoidanceCost;
            previousAvoidance=null;previousAvoidanceRadius=null;previousAvoidanceCost=null;
        }
    }
}
