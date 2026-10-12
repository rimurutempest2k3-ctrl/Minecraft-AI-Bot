package minecraftaibot.client;

import dev.minecraftaibot.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.function.*;

/** JSON local task executor, using vanilla crafting and Baritone only. */
final class LocalTaskRunner {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("minecraft-ai-bot");
    private enum Phase { PLAN, WOOD, APPROACH, OPEN, PLACE, CRAFT, GOAL, HUNT, SMELT, FOOD_CROP, CHEST_OPEN, CHEST_TRANSFER, MENU_OPEN, MENU_CONFIRM, MENU_READY, TRAVEL, LOOP_WAIT }
    private final ChestController chest;
    private final BotCore bot;
    private final BaritoneController movement;
    private final Consumer<String> notify;
    private final ProductionController crafting=new ProductionController();
    private final ProductionController.BlockPlacement placement;
    private final ProductionController.FurnaceRun furnace;
    private int foodFallbackStep=-1,foodFallbackStage;
    private final Set<BlockPos> checkedFoodSources=new HashSet<>();
    private BlockPos foodSource,foodFallbackOrigin;
    private Item cropItem;
    private boolean cropBroken;
    private int cropClicks;
    private long sourceUntil,nextCropClick;
    private List<BlockPos> cropCandidates=List.of();
    private final FoodPlants foodPlants=new FoodPlants();
    void plantMemoryTick(Minecraft client) {foodPlants.tick(client);}
    void closePlantMemory() {foodPlants.close();}
    private Animal prey;
    private Vec3 huntOrigin,lastKill;
    private Vec3 huntSearchOrigin;
    private BlockPos huntSearchTarget;
    private final Set<BlockPos> searchedFoodAreas=new HashSet<>();
    private int foodSearchMoves;
    private long foodSearchUntil;
    private long nextFoodAreaScan;
    private final Set<UUID> skippedPrey=new HashSet<>();
    private long nextHuntPath,preySince,killUntil;
    private int huntAligned;
    private boolean huntAttack;
    private Boolean huntBreak,huntPlace;
    private Integer shallowMinimum;
    private static final Map<String,String> MEATS=Map.of("minecraft:beef","minecraft:cooked_beef","minecraft:porkchop","minecraft:cooked_porkchop",
            "minecraft:mutton","minecraft:cooked_mutton","minecraft:chicken","minecraft:cooked_chicken");
    private boolean active;
    private boolean suspended,productionRecovery,manualHold;
    private long suspendedAt,safeSince;
    private String taskId=UUID.randomUUID().toString();
    private final Deque<com.google.gson.JsonObject> taskHistory=new ArrayDeque<>();
    boolean executing() {return active && !suspended;}
    void pauseManually() {
        if(!active)return;
        manualHold=true;suspendForSurvival();workflowState="SUSPENDED";
        status="Task manually paused; progress saved. Use Resume to continue.";notify.accept(status);
    }
    private void block(String reason) {
        if(!active || suspended)return;
        manualHold=true;suspendForSurvival();workflowState="BLOCKED";
        status=reason+" Task progress saved. Resolve the cause, recover crafting/furnace items, then use bot workflow resume.";
        LOG.warn("{}",status);notify.accept(status);
    }
    void suspendForSurvival() {
        if(!active || suspended)return;
        suspended=true;suspendedAt=System.nanoTime();safeSince=0;productionRecovery=phase==Phase.SMELT;
        releaseHunting(Minecraft.getInstance());restoreShallow();movement.stop();if(chest.busy())chest.cancel();
        furnace.cancel(Minecraft.getInstance(),"Smelting suspended; recover loaded items before resuming the task.");
        placement.cancel("Placement suspended for survival.");crafting.cancel(Minecraft.getInstance());
        workflowState=productionRecovery?"WAITING_RECOVERY":"SUSPENDED";
        status=productionRecovery?"Task saved; recover furnace contents, then use bot workflow resume.":"Task saved; survival has priority. Will resume when safe.";
        if(!manualHold)notify.accept(status);
    }
    String resumeSaved(Minecraft client,boolean safe,boolean manual) {
        if(!active || !suspended)return "No suspended task.";
        if(manualHold && !manual)return status;
        if(!safe || client.player!=owner || client.level!=world || !owner.isAlive() || bot.state()!=BotState.RUNNING
                || client.gui.screen()!=null || client.isPaused() || owner.containerMenu!=owner.inventoryMenu || !owner.containerMenu.getCarried().isEmpty()
                || owner.inventoryMenu.getInputGridSlots().stream().anyMatch(s->!s.getItem().isEmpty())) {safeSince=0;return "Task saved; waiting for safety and empty crafting grid/cursor.";}
        if(productionRecovery && !manual)return "Task saved; recover furnace contents, then use bot workflow resume.";
        long now=System.nanoTime();if(safeSince==0)safeSince=now;
        if(!manual && now-safeSince<3_000_000_000L)return status;
        reconcileChest(client);
        started=manualHold?now:started+now-suspendedAt;suspended=false;productionRecovery=false;manualHold=false;workflowState="RUNNING";clock=0;
        change(Phase.PLAN,"Resuming saved task at the current step; checking actual inventory.");return status;
    }
    void resumeTick(Minecraft client,boolean safe) {if(suspended)resumeSaved(client,safe,false);}
    com.google.gson.JsonArray taskTabsSnapshot(Minecraft client) {
        var tabs=new com.google.gson.JsonArray();for(var past:taskHistory)tabs.add(past.deepCopy());
        if(starter!=null)tabs.add(starterSnapshot(client));return tabs;
    }
    private TaskPresets.Catalog catalog;
    private TaskPresets.Task definition;
    private TaskPresets.Preparation preparation;
    private LocalPlayer owner;
    private ClientLevel world;
    private Phase phase;
    private int quantity, initialResult, initialWood, requestedWood, craftedBefore, oldSelected, clock, batches;
    private long started, phaseStarted;
    private BlockPos table, placedAt;
    private Item craftedItem;
    private String status="No local task.";
    private TaskPresets.Starter starter;
    private String workflowFile="",workflowTitle="",workflowState="IDLE";
    private boolean[] initiallySatisfied=new boolean[0];
    private String workflowMode="ensure";
    private long loopCycle=1;
    private int[] supplyBaselines=new int[0];
    private int supplyIndex;
    private boolean[] controlDone=new boolean[0],controlSent=new boolean[0];
    private Object previousMenu;
    private int[] chestProgress=new int[0];
    private int chestBefore;
    private boolean pendingChest;
    private void reconcileChest(Minecraft client) {
        if(!pendingChest)return;
        var step=starter.steps().get(supplyIndex);int delta=supplyCount(client,step)-chestBefore;
        chestProgress[supplyIndex]+=Math.max(0,step.action().equals("chest_put")?-delta:delta);pendingChest=false;
    }
    private boolean automatic;
    private final Set<String> automaticWorlds=new HashSet<>();
    private String automaticScope(Minecraft client) {
        return client.getSingleplayerServer()!=null?"local:"+client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                :client.getCurrentServer()!=null?"server:"+client.getCurrentServer().ip.toLowerCase(Locale.ROOT):"session:"+System.identityHashCode(client.getConnection());
    }
    String starterCommand(String input) {
        if(input.equals("auto on") || input.equals("auto off")) {automatic=false;return "Task execution is manual: select a task file and run it.";}
        if(input.equals("status")) return status;
        boolean foodOnly=input.equals("food") || input.startsWith("food ");int foodTarget=8;
        if(foodOnly && !input.equals("food")) {
            try {foodTarget=Integer.parseInt(input.substring(5));if(foodTarget<1 || foodTarget>64)throw new NumberFormatException();}
            catch(NumberFormatException e){return "Command: bot starter food [food portions 1-64].";}
        }
        if(!input.equals("start") && !foodOnly) return "Commands: bot starter start/status; bot starter food [1-64]; bot starter auto on/off.";
        if(active) return "Local task active. Use bot stop first.";
        TaskPresets.Workflow workflow;
        try { workflow=TaskPresets.workflow(foodOnly?"food.taskbot":"starter-kit.taskbot"); } catch(IllegalArgumentException e) {return e.getMessage();}
        TaskPresets.Starter plan=workflow.plan();
        if(foodOnly && input.startsWith("food ")) {
            int target=foodTarget;
            plan=new TaskPresets.Starter(plan.steps().stream().map(s->"food".equals(s.role())?new TaskPresets.Supply(s.label(),s.item(),target,s.action(),s.role()):s).toList(),plan.recipes());
        }
        return startWorkflow(new TaskPresets.Workflow(workflow.filename(),workflow.title(),workflow.description(),plan,null,workflow.goal(),workflow.mode()));
    }
    void validateWorkflow(TaskPresets.Workflow workflow) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null || !client.player.isAlive()) throw new IllegalArgumentException("Enter the world and respawn first.");
        for(var step:workflow.plan().steps()) {
            if(step.item()!=null)requiredItem(step.item());
            if(step.menu()!=null && step.menu().expectedItem()!=null)requiredItem(step.menu().expectedItem());
            if(step.action().equals("mine")) {
                String goal=TaskPresets.miningBlock(step.item());
                if(BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(goal)).filter(b->!b.defaultBlockState().isAir()).isEmpty())
                    throw new IllegalArgumentException("Mining step has no valid block: "+step.item());
            }
            if(step.input()!=null)requiredItem(step.input());
            if(step.skipIf()!=null)for(String id:step.skipIf())requiredItem(id);
        }
        for(var recipe:workflow.plan().recipes().values()) {
            if(!recipe.output().equals("$log_planks"))requiredItem(recipe.output());
            for(var cell:recipe.cells()) if(!cell.ingredient().startsWith("$") && !cell.ingredient().startsWith("#"))requiredItem(cell.ingredient());
        }
        TaskPresets.catalog();
    }
    String startWorkflow(TaskPresets.Workflow workflow) {
        Minecraft client=Minecraft.getInstance();
        if(active || movement.busy() || furnace.busy() || placement.busy())return "Previous task has not finished stopping.";
        try {validateWorkflow(workflow);}catch(IllegalArgumentException e){return e.getMessage();}
        if(bot.state()!=BotState.RUNNING || client.isPaused() || client.gui.screen()!=null || client.gui.overlay()!=null
                || client.player.containerMenu!=client.player.inventoryMenu || !client.player.containerMenu.getCarried().isEmpty())return "Close menus, store cursor items and let the game run first.";
        catalog=TaskPresets.catalog();owner=client.player;world=client.level;oldSelected=owner.getInventory().getSelectedSlot();
        if(starter!=null) {taskHistory.addLast(starterSnapshot(client));while(taskHistory.size()>20)taskHistory.removeFirst();}
        taskId=UUID.randomUUID().toString();suspended=false;productionRecovery=false;manualHold=false;
        starter=workflow.plan();workflowFile=workflow.filename();workflowTitle=workflow.title();workflowState="RUNNING";
        workflowMode=workflow.mode();loopCycle=1;supplyBaselines=new int[starter.steps().size()];Arrays.fill(supplyBaselines,-1);
        controlDone=new boolean[starter.steps().size()];controlSent=new boolean[starter.steps().size()];
        chestProgress=new int[starter.steps().size()];pendingChest=false;
        initiallySatisfied=new boolean[starter.steps().size()];
        for(int i=0;i<initiallySatisfied.length;i++)initiallySatisfied[i]=!workflowMode.equals("collect") && !TaskPresets.controlAction(starter.steps().get(i).action()) && !starter.steps().get(i).action().startsWith("chest_") && supplyCount(client,starter.steps().get(i))>=starter.steps().get(i).count();
        boolean finished=workflowMode.equals("ensure") && workflow.goal()!=null && workflow.goal().satisfied(supplyCount(client,starter.steps().getLast()));
        if(finished)Arrays.fill(initiallySatisfied,true);
        supplyIndex=finished?starter.steps().size():0;clock=batches=0;started=System.nanoTime();active=true;foodFallbackStep=-1;
        change(Phase.PLAN,finished?"Goal already satisfied for "+workflowTitle+"; skipping all preparation steps."
                :"Running "+workflowTitle+" ("+workflowFile+") | Mode: "+workflowMode+".");return status;
    }
    com.google.gson.JsonObject starterSnapshot(Minecraft client) {
        var data=new com.google.gson.JsonObject();data.addProperty("automatic",automatic);data.addProperty("active",active && starter!=null);
        data.addProperty("status",status);data.addProperty("step",supplyIndex);
        data.addProperty("file",workflowFile);data.addProperty("title",workflowTitle);data.addProperty("state",workflowState);
        data.addProperty("mode",workflowMode);
        data.addProperty("cycle",loopCycle);
        data.addProperty("id",taskId);data.addProperty("priority",30);data.addProperty("suspended",suspended);
        data.addProperty("phase",phase==null?"":phase.name());
        var steps=new com.google.gson.JsonArray();
        int index=0;
        if(starter!=null) for(var step:starter.steps()) {
            var row=new com.google.gson.JsonObject();row.addProperty("label",step.label());row.addProperty("item",step.item());row.addProperty("target",step.count());
            row.addProperty("action",step.action());
            row.addProperty("state",index<supplyIndex?(initiallySatisfied[index]?"SKIPPED":"COMPLETED"):index==supplyIndex && workflowState.equals("CANCELLED")?"STOPPED":index==supplyIndex && suspended?workflowState:index==supplyIndex && active?"RUNNING":"WAITING");
            row.addProperty("present",client.player==null?0:supplyProgress(client,index));index++;steps.add(row);
        }
        data.add("steps",steps);return data;
    }
    private int supplyCount(Minecraft client,TaskPresets.Supply step) {
        if(TaskPresets.controlAction(step.action()))return 0;
        if(!workflowMode.equals("collect") && step.skipIf()!=null && step.skipIf().stream().allMatch(id->ownedRequirement(client,id))) return step.count();
        if(!workflowMode.equals("collect") && step.item().equals("minecraft:furnace") && furnace.nearby(client,"furnace")) return 1;
        return ownedItems(client).stream().filter(s->{
            if(s.isEmpty()) return false;
            if("logs".equals(step.role())) return logType(s)!=null;
            if("food".equals(step.role())) {
                var food=s.get(net.minecraft.core.component.DataComponents.FOOD);
                var use=s.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
                return food!=null && food.nutrition()>=4 && use!=null && use.onConsumeEffects().isEmpty();
            }
            if(step.role()!=null) {
                String id=BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                return id.endsWith("_"+step.role()) && TaskPresets.toolTier(id)>=TaskPresets.toolTier(step.item())
                        && (!step.role().equals("pickaxe") || !BaritoneController.silkTouch(s))
                        && (!s.isDamageableItem() || s.getMaxDamage()-s.getDamageValue()>10);
            }
            return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(step.item());
        }).mapToInt(ItemStack::getCount).sum();
    }
    private int supplyProgress(Minecraft client,int index) {
        var step=starter.steps().get(index);
        if(TaskPresets.controlAction(step.action()))return controlDone[index]?1:0;
        if(step.action().equals("chest_take") || step.action().equals("chest_put"))return chestProgress[index];
        if(workflowMode.equals("collect") && supplyBaselines[index]<0)return 0;
        return TaskPresets.supplyProgress(workflowMode,supplyCount(client,starter.steps().get(index)),Math.max(0,supplyBaselines[index]));
    }
    private List<ItemStack> ownedItems(Minecraft client) {
        var items=new ArrayList<>(client.player.getInventory().getNonEquipmentItems());
        for(var slot:List.of(net.minecraft.world.entity.EquipmentSlot.HEAD,net.minecraft.world.entity.EquipmentSlot.CHEST,net.minecraft.world.entity.EquipmentSlot.LEGS,net.minecraft.world.entity.EquipmentSlot.FEET,net.minecraft.world.entity.EquipmentSlot.OFFHAND))
            items.add(client.player.getItemBySlot(slot));
        return items;
    }
    private int ownedCount(Minecraft client,String id) {return ownedItems(client).stream().filter(s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id)).mapToInt(ItemStack::getCount).sum();}
    private boolean ownedRequirement(Minecraft client,String id) {
        if(id.endsWith("_pickaxe") || id.endsWith("_sword") || id.endsWith("_axe")) {
            String suffix=id.substring(id.lastIndexOf('_'));
            return ownedItems(client).stream().anyMatch(s->!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().endsWith(suffix)
                    && TaskPresets.toolTier(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())>=TaskPresets.toolTier(id)
                    && (!s.isDamageableItem() || s.getMaxDamage()-s.getDamageValue()>10)
                    && (!suffix.equals("_pickaxe") || !BaritoneController.silkTouch(s)));
        }
        return ownedCount(client,id)>0;
    }
    private static final List<String> WOODS=List.of("oak","birch","spruce","jungle","acacia","dark_oak","mangrove","cherry","pale_oak");
    LocalTaskRunner(BotCore bot,BaritoneController movement,ProductionController.BlockPlacement placement,ProductionController.FurnaceRun furnace,ChestController chest,Consumer<String> notify) { this.chest=chest;this.bot=bot;this.movement=movement;this.placement=placement;this.furnace=furnace;this.notify=notify; }
    boolean busy() { return active; }
    String status() { return status; }
    String start(String taskName,int quantity) {
        return start(taskName,quantity,false);
    }
    String start(String taskName,int quantity,boolean total) {
        Minecraft client=Minecraft.getInstance();
        if(active || movement.busy()) return "Task active. Use bot stop first.";
        starter=null;supplyIndex=0;workflowState="IDLE";workflowFile=workflowTitle="";
        if(client.player==null || client.level==null || !client.player.isAlive() || bot.state()!=BotState.RUNNING) return "Enter the world and type bot start first.";
        if(client.player.containerMenu!=client.player.inventoryMenu || client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused()) return "Close menus and use F3+P so the game keeps running when switching to the console.";
        if(!client.player.containerMenu.getCarried().isEmpty()) return "Store the item on the cursor first.";
        try {
            catalog=TaskPresets.catalog();definition=catalog.require(taskName);preparation=definition.preparation()==null?null:catalog.preparations().get(definition.preparation());
            requiredItem(definition.resultItem());
            String block=definition.goal().equals("minecraft:cobblestone")?"minecraft:stone":definition.goal();
            if(BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(block)).filter(b->!b.defaultBlockState().isAir()).isEmpty())
                throw new IllegalArgumentException("Unknown JSON block: "+definition.goal());
            for(var recipe:catalog.recipes().values()) {
                if(!recipe.output().equals("$log_planks")) requiredItem(recipe.output());
                for(var cell:recipe.cells()) if(!cell.ingredient().startsWith("$") && !cell.ingredient().startsWith("#")) requiredItem(cell.ingredient());
            }
        }
        catch(IllegalArgumentException invalid) {return invalid.getMessage();}
        if(quantity<1 || quantity>2304) return "Quantity must be 1-2304.";
        owner=client.player;world=client.level;initialResult=resultCount();
        this.quantity=ProductionPlan.additionalQuantity(quantity,initialResult,total);
        if(this.quantity==0) return status="Already have enough "+quantity+" "+definition.resultItem()+"; no further collection needed.";
        oldSelected=owner.getInventory().getSelectedSlot();started=System.nanoTime();active=true;batches=0;clock=0;
        change(Phase.PLAN,"Local JSON: "+definition.label()+"; collect another "+this.quantity+" "+definition.resultItem()+". No API calls.");return status;
    }
    private void change(Phase value,String message) { phase=value;phaseStarted=System.nanoTime();status=message;LOG.info("Local phase {}: {}",value,message);notify.accept(message); }
    private List<ItemStack> inventory() { return owner.getInventory().getNonEquipmentItems(); }
    private int count(Predicate<ItemStack> filter) { return inventory().stream().filter(filter).mapToInt(ItemStack::getCount).sum(); }
    private int count(Item item) { return ownedItems(Minecraft.getInstance()).stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private int resultCount() { return count(s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(definition.resultItem())); }
    private int pickaxe() {
        for(int i=0;i<inventory().size();i++) {
            ItemStack s=inventory().get(i);
            if(s.is(ItemTags.PICKAXES) && !BaritoneController.silkTouch(s) && (!s.isDamageableItem() || s.getMaxDamage()-s.getDamageValue()>2)) return i;
        }
        return -1;
    }
    private String logType(ItemStack stack) {
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for(String wood:WOODS) if(id.equals("minecraft:"+wood+"_log") || id.equals("minecraft:"+wood+"_wood")
                || id.equals("minecraft:stripped_"+wood+"_log") || id.equals("minecraft:stripped_"+wood+"_wood")) return wood;
        return null;
    }
    private BlockPos findTable(Minecraft client) {
        BlockPos origin=owner.blockPosition(),best=null;double distance=Double.MAX_VALUE;
        for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-12,-4,-12),origin.offset(12,4,12))) {
            if(!world.hasChunkAt(pos) || !world.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double d=owner.position().distanceToSqr(Vec3.atCenterOf(pos));
            if(d<distance) { best=pos.immutable();distance=d; }
        }
        return best;
    }
    private BlockHitResult reachable(BlockPos pos) {
        Vec3 eye=owner.getEyePosition();
        BlockHitResult hit=world.clip(new ClipContext(eye,Vec3.atCenterOf(pos),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && eye.distanceToSqr(hit.getLocation())<=Math.pow(owner.blockInteractionRange(),2) ? hit:null;
    }
    void autoTick(Minecraft client,boolean idle) {
        if(automatic && client.level!=null && !automaticWorlds.contains(automaticScope(client)) && client.player!=null && client.player.isAlive()
                && bot.state()==BotState.RUNNING && idle && !active && !movement.busy() && client.gui.screen()==null && !client.isPaused()) {
            automaticWorlds.add(automaticScope(client));notify.accept(starterCommand("start"));
        }
    }
    void tick(Minecraft client) {
        releaseHuntAttack(client);
        if(!active) return;
        if(suspended)return;
        if(client.gui.screen() instanceof BotControlScreen)return;
        if(client.player!=owner || client.level!=world || !owner.isAlive() || bot.state()!=BotState.RUNNING) { block("Local stopped: world/player changed, player died or bot state changed.");return; }
        if(phase==Phase.LOOP_WAIT) {
            if(client.isPaused() || client.gui.screen()!=null || client.gui.overlay()!=null)return;
            if(System.nanoTime()-phaseStarted>=5_000_000_000L) {
                Arrays.fill(supplyBaselines,-1);Arrays.fill(controlDone,false);Arrays.fill(controlSent,false);Arrays.fill(chestProgress,0);Arrays.fill(initiallySatisfied,false);
                pendingChest=false;supplyIndex=0;clock=batches=0;foodFallbackStep=-1;loopCycle++;started=System.nanoTime();
                for(int i=0;i<initiallySatisfied.length;i++)initiallySatisfied[i]=!TaskPresets.controlAction(starter.steps().get(i).action()) && !starter.steps().get(i).action().startsWith("chest_") && supplyCount(client,starter.steps().get(i))>=starter.steps().get(i).count();
                change(Phase.PLAN,"Starting task round "+loopCycle+".");
            }
            return;
        }
        if(phase==Phase.HUNT && System.nanoTime()-phaseStarted>180_000_000_000L) beginFoodFallback(client,"Animal search timed out.");
        if(System.nanoTime()-started>(starter==null?900_000_000_000L:1_800_000_000_000L)
                || System.nanoTime()-phaseStarted>(phase==Phase.SMELT?900_000_000_000L:180_000_000_000L)) { block("Local stopped: current step timed out.");return; }
        if(client.isPaused() || client.gui.overlay()!=null) return;
        try {
            if(phase==Phase.TRAVEL) {
                var target=starter.steps().get(supplyIndex).target();
                if(owner.blockPosition().equals(new BlockPos(target.x(),target.y(),target.z()))) {movement.stop();controlDone[supplyIndex]=true;change(Phase.PLAN,"Destination reached; checking next step.");}
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy())block("Travel stopped before reaching the destination; check the path and resume.");
                return;
            }
            if(phase==Phase.MENU_READY) {
                if(matchesMenu(client,starter.steps().get(supplyIndex).menu().title()))change(Phase.PLAN,"Expected menu is ready; checking the requested slot.");
                else if(System.nanoTime()-phaseStarted>5_000_000_000L)block("Expected menu did not open; no click sent.");
                return;
            }
            if(phase==Phase.MENU_OPEN || phase==Phase.MENU_CONFIRM) {
                var menu=starter.steps().get(supplyIndex).menu();
                boolean confirmed=phase==Phase.MENU_OPEN?matchesMenu(client,menu.title()):Boolean.TRUE.equals(menu.closed())
                        ?owner.containerMenu==owner.inventoryMenu && client.gui.screen()==null
                        :owner.containerMenu!=previousMenu && matchesMenu(client,menu.nextTitle());
                if(confirmed && owner.containerMenu.getCarried().isEmpty()) {controlDone[supplyIndex]=true;change(Phase.PLAN,"Menu action confirmed; checking next step.");return;}
                if(System.nanoTime()-phaseStarted>5_000_000_000L)block("Expected menu result was not confirmed; action will not be sent again automatically.");
                return;
            }
            if(phase==Phase.CHEST_OPEN) {
                if(chest.busy())return;
                if(!(owner.containerMenu instanceof ChestMenu)) {block(chest.status());return;}
                var step=starter.steps().get(supplyIndex);
                chestBefore=supplyCount(client,step);pendingChest=true;
                String response=chest.execute((step.action().equals("chest_put")?"put ":"take ")+step.item()+" "+(step.count()-supplyProgress(client,supplyIndex)));
                if(!chest.busy()) {pendingChest=false;block(response);return;}change(Phase.CHEST_TRANSFER,response);return;
            }
            if(phase==Phase.CHEST_TRANSFER) {
                if(chest.busy())return;
                var step=starter.steps().get(supplyIndex);
                if(owner.containerMenu.getCarried().isEmpty())reconcileChest(client);
                if(!chest.transferConfirmed() || supplyProgress(client,supplyIndex)<step.count()) {block(chest.status());return;}
                closeForMovement();change(Phase.PLAN,"Chest transfer confirmed; checking next step.");return;
            }
            if(phase==Phase.HUNT) {huntTick(client);return;}
            if(phase==Phase.FOOD_CROP) {foodCropTick(client);return;}
            if(phase==Phase.SMELT) {
                if(furnace.busy()) {status="Smelting: "+furnace.status();return;}
                if(count(craftedItem)<=craftedBefore) {block("Smelting unsuccessful: "+furnace.status());return;}
                change(Phase.PLAN,"Smelted output collected; checking remaining needs.");return;
            }
            if(phase==Phase.CRAFT) {
                crafting.tick(client);
                if(!crafting.busy()) {
                    if(crafting.failure()!=null) { block("Crafting stopped: "+crafting.failure());return; }
                    if(count(craftedItem)<=craftedBefore) { block("Output not found in inventory; stopped.");return; }
                    change(Phase.PLAN,"Crafted: "+BuiltInRegistries.ITEM.getKey(craftedItem)+". Checking the next step.");
                }
                return;
            }
            if(phase==Phase.GOAL) {
                int collected=Math.max(0,resultCount()-initialResult);
                if(collected>=quantity) {
                    restoreShallow();
                    if(starter!=null) {movement.stop();change(Phase.PLAN,"Collected enough "+definition.resultItem()+"; checking next step.");}
                    else finish("Local completed: collected another "+collected+"/"+quantity+" "+definition.resultItem()+".");return;
                }
                if(preparation!=null && pickaxe()<0) { movement.stop();change(Phase.PLAN,"Pickaxe exhausted; preparing a new one for the remaining items.");return; }
                if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) {
                    block("Baritone stopped before collecting enough "+definition.resultItem()+": "+collected+"/"+quantity+". "+movement.status());
                }
                return;
            }
            if(phase==Phase.WOOD) {
                if(count(s->logType(s)!=null)-initialWood>=requestedWood) { movement.stop();change(Phase.PLAN,"Enough wood for tools collected."); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) block("Not enough wood collected yet. "+movement.status());
                return;
            }
            if(phase==Phase.PLACE) {
                if(placement.busy()) return;
                placedAt=placement.target();
                if(placedAt!=null && world.getBlockState(placedAt).is(Blocks.CRAFTING_TABLE)) { owner.getInventory().setSelectedSlot(oldSelected);table=placedAt;change(Phase.PLAN,"Crafting table placed."); }
                else if(!placement.busy()) block(placement.status());
                return;
            }
            if(phase==Phase.OPEN) {
                if(owner.containerMenu instanceof CraftingMenu) change(Phase.PLAN,"Crafting table opened.");
                else if(System.nanoTime()-phaseStarted>5_000_000_000L) block("Could not open crafting table; check server permissions.");
                return;
            }
            if(phase==Phase.APPROACH) {
                if(!world.getBlockState(table).is(Blocks.CRAFTING_TABLE)) { block("Target crafting table disappeared.");return; }
                BlockHitResult hit=reachable(table);
                if(hit!=null) { movement.stop();openTable(client,hit); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) block("Crafting table approach stopped before reaching the table.");
                return;
            }
            if(++clock%10!=0) return;
            if(starter!=null && supplyIndex<starter.steps().size() && TaskPresets.controlAction(starter.steps().get(supplyIndex).action())) {plan(client);return;}
            if(!(owner.containerMenu instanceof InventoryMenu) && !(owner.containerMenu instanceof CraftingMenu)) { block("Another menu is open; local task stopped.");return; }
            if(!owner.containerMenu.getCarried().isEmpty()) { block("Cursor contains an item; store it first.");return; }
            plan(client);
        } catch(Exception failure) { block("Local stopped: "+(failure instanceof IllegalArgumentException ? failure.getMessage():"Cannot access game state.")); }
    }
    private void plan(Minecraft client) {
        if(starter!=null && planStarter(client)) return;
        if(resultCount()-initialResult>=quantity) { finish("Enough "+definition.resultItem()+" requests.");return; }
        boolean tableOpen=owner.containerMenu instanceof CraftingMenu;
        if(!tableOpen) table=findTable(client);
        Map<String,Integer> observed=Map.of("pickaxe",pickaxe()>=0?1:0,"logs",count(s->logType(s)!=null),
                "planks",count(s->s.is(ItemTags.PLANKS)),"sticks",count(Items.STICK),
                "table_available",count(Items.CRAFTING_TABLE)>0 || table!=null || tableOpen?1:0,
                "nearby_table",table!=null?1:0,"table_open",tableOpen?1:0);
        TaskPresets.Step step=preparation==null?new TaskPresets.Step("mine_goal",null):preparation.next(observed);
        switch(step.action()) {
            case "mine_goal" -> {
                closeForMovement();
                selectMiningTool(client);
                String response=movement.execute("gather "+definition.resultItem()+" "+(quantity-Math.max(0,resultCount()-initialResult)));
                if(!movement.busy()) throw new IllegalArgumentException(response);
                change(Phase.GOAL,"Local JSON: "+response);
            }
            case "collect_logs" -> {
                closeForMovement();requestedWood=preparation.evaluate(observed).get("logs_needed");initialWood=observed.get("logs");
                String wood=nearestWood();String response=movement.execute("mine minecraft:"+wood+"_log "+requestedWood);
                if(!movement.busy()) throw new IllegalArgumentException(response);
                change(Phase.WOOD,"Local JSON: collect another "+requestedWood+" wood "+wood+" to craft tools.");
            }
            case "craft" -> craftRecipe(client,catalog.recipes().get(step.recipe()));
            case "place_table" -> placeTable(client);
            case "open_table" -> {
                if(table==null) throw new IllegalArgumentException("No crafting table found to open.");
                closeForMovement();BlockHitResult hit=reachable(table);
                if(hit!=null) openTable(client,hit);
                else {movement.approachBlock(table,"crafting table");change(Phase.APPROACH,"Local: approaching table at "+table.toShortString());}
            }
            default -> throw new IllegalArgumentException("Unsupported JSON action.");
        }
    }
    /** Returns false only when the existing mining prerequisite interpreter should run. */
    private boolean planStarter(Minecraft client) {
        while(supplyIndex<starter.steps().size()) {
            if(supplyBaselines[supplyIndex]<0)supplyBaselines[supplyIndex]=supplyCount(client,starter.steps().get(supplyIndex));
            if(supplyProgress(client,supplyIndex)<starter.steps().get(supplyIndex).count())break;
            supplyIndex++;
        }
        if(supplyIndex==starter.steps().size()) {
            if(workflowMode.equals("loop")) {closeForMovement();movement.stop();change(Phase.LOOP_WAIT,"Task round "+loopCycle+" completed; repeating in five seconds. bot stop to cancel.");return true;}
            closeForMovement();finish("Task "+workflowTitle+" completed. Checked "+supplyIndex+" steps; no AI calls.");return true;
        }
        var step=starter.steps().get(supplyIndex);
        if(TaskPresets.controlAction(step.action())) {controlStep(client,step);return true;}
        if(step.action().equals("chest_take") || step.action().equals("chest_put")) {
            closeForMovement();var target=step.chest();
            String response=chest.execute("open "+target.x()+" "+target.y()+" "+target.z());
            if(!chest.busy())throw new IllegalArgumentException(response);
            change(Phase.CHEST_OPEN,response);return true;
        }
        if(step.action().equals("food")) {planFood(client,step);return true;}
        if(step.action().equals("smelt")) {planSmelt(client,step);return true;}
        if(step.action().equals("craft")) {prepareRecipe(client,starter.recipes().values().stream().filter(r->r.output().equals(step.item())).findFirst().orElseThrow(),new HashSet<>());return true;}
        String item="logs".equals(step.role())?"minecraft:"+nearestWood()+"_log":step.item();
        configureMine(item,step.count()-supplyProgress(client,supplyIndex));return false;
    }
    private boolean matchesMenu(Minecraft client,String title) {
        return owner.containerMenu instanceof ChestMenu && client.gui.screen()!=null && client.gui.screen().getTitle().getString().equals(title);
    }
    private void controlStep(Minecraft client,TaskPresets.Supply step) {
        movement.stop();
        if(!owner.containerMenu.getCarried().isEmpty())throw new IllegalArgumentException("Menu cursor is not empty; store the item first.");
        if(step.action().equals("goto")) {
            var target=step.target();
            if(target.y()<world.getMinY() || target.y()>world.getMaxY())throw new IllegalArgumentException("Coordinates outside world bounds.");
            if(owner.blockPosition().equals(new BlockPos(target.x(),target.y(),target.z()))) {controlDone[supplyIndex]=true;change(Phase.PLAN,"Destination reached; checking next step.");return;}
            closeForMovement();String response=movement.execute("goto "+target.x()+" "+target.y()+" "+target.z());
            if(!response.startsWith("Assigned Baritone task:"))throw new IllegalArgumentException(response);
            change(Phase.TRAVEL,"Traveling to "+target.x()+", "+target.y()+", "+target.z());return;
        }
        if(step.action().equals("chat") || step.action().equals("command")) {
            if(client.getConnection()==null)throw new IllegalArgumentException("No server connection.");
            if(step.action().equals("chat"))owner.connection.sendChat(step.text());else owner.connection.sendCommand(step.text());
            controlDone[supplyIndex]=true;change(Phase.PLAN,"Message/command sent once; checking next step.");return;
        }
        if(controlSent[supplyIndex]) {change(step.action().equals("menu_open")?Phase.MENU_OPEN:Phase.MENU_CONFIRM,"Waiting for the previously requested menu result; not repeating the action.");return;}
        if(step.action().equals("menu_open")) {
            closeForMovement();int slot=-1;for(int i=0;i<inventory().size();i++)if(inventory().get(i).is(requiredItem(step.item()))) {slot=i;break;}
            if(slot<0 || !select(client,slot))throw new IllegalArgumentException("Menu-opening item is not available.");
            controlSent[supplyIndex]=true;client.gameMode.useItem(owner,InteractionHand.MAIN_HAND);change(Phase.MENU_OPEN,"Waiting for the expected server menu.");return;
        }
        var menu=step.menu();
        if(!owner.containerMenu.getCarried().isEmpty())throw new IllegalArgumentException("Menu cursor is not empty; store the item first.");
        if(!matchesMenu(client,menu.title())) {change(Phase.MENU_READY,"Waiting for the expected menu before clicking.");return;}
        var chestMenu=(ChestMenu)owner.containerMenu;
        if(menu.slot()>=chestMenu.getRowCount()*9 || !chestMenu.getSlot(menu.slot()).getItem().is(requiredItem(menu.expectedItem())))throw new IllegalArgumentException("Menu slot or item does not match the task.");
        previousMenu=owner.containerMenu;controlSent[supplyIndex]=true;
        client.gameMode.handleContainerInput(chestMenu.containerId,menu.slot(),0,ContainerInput.PICKUP,owner);
        change(Phase.MENU_CONFIRM,"Menu clicked once; waiting for the expected result.");
    }
    private void configureMine(String item,int missing) {
        String goal=TaskPresets.miningBlock(item);
        definition=new TaskPresets.Task("Survival preparation",goal,item,missing,null);
        preparation=item.equals("minecraft:cobblestone")?catalog.preparations().get("wooden_pickaxe"):null;
        initialResult=resultCount();quantity=missing;
        if(item.equals("minecraft:cobblestone") && starter!=null && starter.steps().get(supplyIndex).action().equals("food") && shallowMinimum==null) {
            shallowMinimum=baritone.api.BaritoneAPI.getSettings().minYLevelWhileMining.value;
            baritone.api.BaritoneAPI.getSettings().minYLevelWhileMining.value=Math.max(shallowMinimum,owner.blockPosition().getY()-3);
        }
    }
    private void planSmelt(Minecraft client,TaskPresets.Supply step) {
        int missing=step.count()-supplyProgress(client,supplyIndex);
        var rules=ProductionController.FurnaceLogic.rules();
        var matches=rules.getAsJsonObject("acceptedInputs").getAsJsonObject("furnace").getAsJsonArray(step.input());
        if(matches==null || matches.size()!=1)throw new IllegalArgumentException("No unique furnace recipe for "+step.input());
        var recipe=rules.getAsJsonObject("recipes").getAsJsonObject(matches.get(0).getAsString());
        var result=recipe.getAsJsonObject("result");
        if(!result.get("id").getAsString().equals(step.item()) || result.has("count") && result.get("count").getAsInt()!=1)
            throw new IllegalArgumentException("Task smelting output does not match furnace rules.");
        if(count(requiredItem(step.input()))<missing) {configureMine(step.input(),missing-count(requiredItem(step.input())));planMining(client);return;}
        if(count(Items.FURNACE)==0 && !furnace.nearby(client,"furnace")) {prepareRecipe(client,starter.recipes().get("furnace"),new HashSet<>());return;}
        int cook=recipe.has("cookingtime")?recipe.get("cookingtime").getAsInt():200;
        String fuel=null;
        for(var stack:inventory()) {
            int burn=world.fuelValues().burnDuration(stack);
            if(burn>0 && (stack.is(Items.COAL) || stack.is(Items.CHARCOAL) || stack.is(ItemTags.PLANKS) || stack.is(Items.STICK))
                    && !stack.is(requiredItem(step.input())) && count(stack.getItem())>=new ProductionPlan.Smelt(missing,1,cook,burn).fuelCount()) {fuel=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();break;}
        }
        if(fuel==null) {configureMine("minecraft:coal",new ProductionPlan.Smelt(missing,1,cook,1600).fuelCount());planMining(client);return;}
        closeForMovement();movement.stop();craftedItem=requiredItem(step.item());craftedBefore=count(craftedItem);
        String response=furnace.start(client,"furnace",step.input(),missing,fuel);
        if(!furnace.busy())throw new IllegalArgumentException(response);
        change(Phase.SMELT,response);
    }
    private void prepareRecipe(Minecraft client,TaskPresets.Recipe recipe,Set<String> visiting) {
        if(!visiting.add(recipe.output()) || visiting.size()>12) throw new IllegalArgumentException("Starter recipe contains a cycle.");
        Map<String,Integer> needed=new LinkedHashMap<>();for(var cell:recipe.cells()) needed.merge(cell.ingredient(),1,Integer::sum);
        for(var entry:needed.entrySet()) {
            String ingredient=entry.getKey();int available=ingredient.equals("#minecraft:planks")?count(s->s.is(ItemTags.PLANKS))
                    :ingredient.equals("$log")?count(s->logType(s)!=null):count(requiredItem(ingredient));
            if(available>=entry.getValue()) continue;
            String output=ingredient.equals("#minecraft:planks")?"$log_planks":ingredient;
            var sub=starter.recipes().values().stream().filter(r->r.output().equals(output)).findFirst();
            if(sub.isPresent()) {prepareRecipe(client,sub.get(),visiting);return;}
            String item=ingredient.equals("$log")?"minecraft:"+nearestWood()+"_log":ingredient;
            configureMine(item,entry.getValue()-available);
            // Wheat is obtained from hay, then unpacked using the wheat recipe.
            planMining(client);return;
        }
        if(recipe.width()>2 && !(owner.containerMenu instanceof CraftingMenu)) {
            table=findTable(client);
            if(table!=null) {closeForMovement();var hit=reachable(table);if(hit!=null)openTable(client,hit);else {movement.approachBlock(table,"crafting table");change(Phase.APPROACH,"Local: approaching crafting table.");}return;}
            if(count(Items.CRAFTING_TABLE)==0) {prepareRecipe(client,starter.recipes().get("table"),visiting);return;}
            placeTable(client);return;
        }
        craftRecipe(client,recipe);
    }
    private void selectMiningTool(Minecraft client) {
        var state=BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(TaskPresets.miningBlock(definition.resultItem()))).defaultBlockState();
        var tools=TaskPresets.miningTools(definition.resultItem());
        int best=-1;float speed=-1;
        for(int i=0;i<inventory().size();i++) {
            var stack=inventory().get(i);if(!BaritoneController.suitableForGather(stack,definition.resultItem()))continue;
            float candidate=stack.getDestroySpeed(state);if(candidate>speed){speed=candidate;best=i;}
        }
        if(best<0 || !select(client,best))throw new IllegalArgumentException("Missing suitable mining tool for "+definition.resultItem()+". Required special tools: "+tools);
    }
    private void planMining(Minecraft client) {
        // Execute the existing preparation rules without recursively entering the starter planner.
        var saved=starter;starter=null;
        try {plan(client);} finally {starter=saved;}
    }
    private void craftRecipe(Minecraft client,TaskPresets.Recipe recipe) {
        int width=((AbstractCraftingMenu)owner.containerMenu).getGridWidth();
        if(width<recipe.width()) throw new IllegalArgumentException("JSON recipe requires a 3x3 crafting table; add an opening step first.");
        ItemStack log=recipe.output().equals("$log_planks") || recipe.cells().stream().anyMatch(c->c.ingredient().equals("$log"))
                ?inventory().stream().filter(s->logType(s)!=null).findFirst().orElseThrow(()->new IllegalArgumentException("Missing wood for recipe.")):null;
        Map<Integer,Predicate<ItemStack>> ingredients=new LinkedHashMap<>();
        for(TaskPresets.Cell cell:recipe.cells()) {
            Predicate<ItemStack> ingredient;
            if(cell.ingredient().equals("$log")) ingredient=s->s.is(log.getItem());
            else if(cell.ingredient().equals("#minecraft:planks")) ingredient=s->s.is(ItemTags.PLANKS);
            else {Item item=requiredItem(cell.ingredient());ingredient=s->s.is(item);}
            ingredients.put(cell.row()*width+cell.column(),ingredient);
        }
        String output=recipe.output().equals("$log_planks")?"minecraft:"+logType(log)+"_planks":recipe.output();
        craft(client,ingredients,new ItemStack(requiredItem(output),recipe.count()));
    }
    private Item requiredItem(String id) {
        return BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(id))
                .filter(i->i!=Items.AIR).orElseThrow(()->new IllegalArgumentException("JSON item not found: "+id));
    }
    private void craft(Minecraft client,Map<Integer,Predicate<ItemStack>> recipe,ItemStack output) {
        if(++batches>200) throw new IllegalArgumentException("Task exceeded its crafting step limit.");
        craftedItem=output.getItem();craftedBefore=count(craftedItem);movement.stop();crafting.start(client,recipe,output);
        change(Phase.CRAFT,"Local: craft "+BuiltInRegistries.ITEM.getKey(craftedItem)+" x"+output.getCount());
    }
    private void closeForMovement() {
        if(owner.containerMenu instanceof AbstractCraftingMenu menu && (menu.getInputGridSlots().stream().anyMatch(s->!s.getItem().isEmpty()) || !menu.getCarried().isEmpty()))
            throw new IllegalArgumentException("Crafting grid/cursor not empty; store ingredients first.");
        owner.closeContainer();
    }
    private boolean select(Minecraft client,int source) {
        if(source<0) return false;
        if(source<9) { owner.getInventory().setSelectedSlot(source);return true; }
        if(owner.containerMenu!=owner.inventoryMenu || !owner.containerMenu.getCarried().isEmpty()) return false;
        int selected=owner.getInventory().getSelectedSlot();ItemStack expected=inventory().get(source).copy();
        client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,source,selected,ContainerInput.SWAP,owner);
        return ItemStack.isSameItemSameComponents(inventory().get(selected),expected) && inventory().get(selected).getCount()==expected.getCount();
    }
    private void openTable(Minecraft client,BlockHitResult hit) {
        if(owner.isSecondaryUseActive()) throw new IllegalArgumentException("Release the sneak key to open the crafting table.");
        // Vanilla TryEmptyHandInteraction invokes useWithoutItem only for MAIN_HAND.
        // Prefer an empty hotbar slot; the vanilla table also opens with an occupied main hand.
        for(int i=0;i<9;i++) if(inventory().get(i).isEmpty()) {owner.getInventory().setSelectedSlot(i);break;}
        var result=client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,hit);
        LOG.info("Open crafting table at {} with MAIN_HAND: {}",hit.getBlockPos().toShortString(),result);
        change(Phase.OPEN,"Local: opening crafting table with main hand.");
    }
    private void placeTable(Minecraft client) {
        closeForMovement();
        String message=placement.start(client,null);placedAt=placement.target();change(Phase.PLACE,"Local: "+message);
    }
    private String nearestWood() {
        BlockPos origin=owner.blockPosition();String result="oak";double best=Double.MAX_VALUE;
        for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-12,-4,-12),origin.offset(12,8,12))) {
            if(!world.hasChunkAt(pos)) continue;
            String id=BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString();
            for(String wood:WOODS) if(id.equals("minecraft:"+wood+"_log")) {
                double d=owner.position().distanceToSqr(Vec3.atCenterOf(pos));if(d<best) {best=d;result=wood;}break;
            }
        }
        return result;
    }
    void cancel(String reason) {
        if(!active) return;active=false;suspended=false;productionRecovery=false;manualHold=false;if(starter!=null)workflowState="CANCELLED";releaseHunting(Minecraft.getInstance());restoreShallow();movement.stop();furnace.cancel(Minecraft.getInstance(),"Local task smelting canceled.");placement.cancel("Local task table placement canceled.");crafting.cancel(Minecraft.getInstance());
        if(owner!=null && Minecraft.getInstance().player==owner) owner.getInventory().setSelectedSlot(oldSelected);
        status=reason+" If items remain on the crafting grid/cursor, store them; items will not be dropped automatically.";LOG.warn("{}",status);notify.accept(status);
    }
    private void finish(String message) { active=false;if(starter!=null)workflowState="COMPLETED";releaseHunting(Minecraft.getInstance());restoreShallow();movement.stop();owner.getInventory().setSelectedSlot(oldSelected);status=message;LOG.info("{}",status);notify.accept(status); }

    private void restoreShallow() {
        if(shallowMinimum!=null) {baritone.api.BaritoneAPI.getSettings().minYLevelWhileMining.value=shallowMinimum;shallowMinimum=null;}
    }
    private int rawMeat() {return count(s->MEATS.containsKey(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));}
    private void planFood(Minecraft client,TaskPresets.Supply step) {
        if(foodFallbackStep!=supplyIndex) {foodFallbackStep=supplyIndex;foodFallbackStage=0;checkedFoodSources.clear();foodSource=null;}
        int missing=step.count()-supplyProgress(client,supplyIndex);
        if(missing<=0) return;
        String raw=MEATS.keySet().stream().filter(id->count(requiredItem(id))>0).max(Comparator.comparingInt(id->count(requiredItem(id)))).orElse(null);
        if(raw==null && count(Items.POTATO)>0)raw="minecraft:potato";
        // Cook existing meat immediately instead of requiring the entire hunting quota first.
        if(raw!=null) {
            if(count(Items.FURNACE)==0 && !furnace.nearby(client,"furnace")) {prepareRecipe(client,starter.recipes().get("furnace"),new HashSet<>());return;}
            int batch=TaskPresets.foodBatch(missing,count(requiredItem(raw)));
            int cook=ProductionController.FurnaceLogic.cookingTicks("furnace",raw);
            ItemStack fuel=null;
            for(var stack:inventory()) if(stack.is(ItemTags.PLANKS) && world.fuelValues().burnDuration(stack)>0) {
                int needed=new ProductionPlan.Smelt(batch,1,cook,world.fuelValues().burnDuration(stack)).fuelCount();
                if(count(stack.getItem())>=needed) {fuel=stack;break;}
            }
            if(fuel==null) {prepareRecipe(client,starter.recipes().get("planks"),new HashSet<>());return;}
            closeForMovement();craftedItem=raw.equals("minecraft:potato")?Items.BAKED_POTATO:requiredItem(MEATS.get(raw));craftedBefore=count(craftedItem);
            String response=furnace.start(client,"furnace",raw,batch,BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString());
            if(!furnace.busy()) throw new IllegalArgumentException(response);
            change(Phase.SMELT,"Cook food before going underground: "+response);return;
        }
        if(count(Items.WHEAT)>=3) {
            var bread=starter.recipes().values().stream().filter(r->r.output().equals("minecraft:bread")).findFirst().orElseThrow(()->new IllegalArgumentException("No bread recipe in task recipes."));
            prepareRecipe(client,bread,new HashSet<>());return;
        }
        if(foodFallbackStage>0) {nextFoodSource(client);return;}
        if(huntWeapon()<0) {prepareRecipe(client,starter.recipes().get("wooden_sword"),new HashSet<>());return;}
        closeForMovement();movement.stop();restoreShallow();
        prey=null;lastKill=null;skippedPrey.clear();huntOrigin=owner.position();nextHuntPath=killUntil=0;huntAligned=0;
        huntSearchOrigin=huntOrigin;huntSearchTarget=null;searchedFoodAreas.clear();searchedFoodAreas.add(owner.blockPosition());foodSearchMoves=0;nextFoodAreaScan=0;
        var settings=baritone.api.BaritoneAPI.getSettings();huntBreak=settings.allowBreak.value;huntPlace=settings.allowPlace.value;
        settings.allowBreak.value=false;settings.allowPlace.value=false;
        change(Phase.HUNT,"Hunt nearby adult cows/pigs/sheep/chickens for meat; no babies or named animals.");
    }
    private int huntWeapon() {
        int best=-1;double score=-1;
        for(int i=0;i<inventory().size();i++) {
            var stack=inventory().get(i);if(!stack.is(ItemTags.SWORDS) && !stack.is(ItemTags.AXES))continue;
            if(stack.isDamageableItem() && stack.getMaxDamage()-stack.getDamageValue()<=5)continue;
            String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            double quality=id.contains("netherite_")?5:id.contains("diamond_")?4:id.contains("iron_")?3:id.contains("stone_")?2:1;
            if(stack.is(ItemTags.AXES))quality-=0.25;
            if(quality>score){best=i;score=quality;}
        }
        return best;
    }
    private boolean huntable(Animal mob) {
        return mob.isAlive() && !mob.isRemoved() && TaskPresets.huntable(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(),mob.isBaby(),mob.hasCustomName())
                && !mob.isInWater() && mob.position().distanceToSqr(huntOrigin)<=48*48 && Math.abs(mob.getY()-huntOrigin.y)<=6;
    }
    private void huntPath(BlockPos target,long now) {
        if(now<nextHuntPath)return;nextHuntPath=now+2_000_000_000L;
        movement.stop();String response=movement.execute("goto "+target.getX()+" "+target.getY()+" "+target.getZ());
        if(!movement.busy()) throw new IllegalArgumentException(response);
    }
    private void huntTick(Minecraft client) {
        long now=System.nanoTime();
        if(owner.position().distanceToSqr(huntSearchOrigin)>160*160) {beginFoodFallback(client,"Animal search reached its exploration limit.");return;}
        if(rawMeat()>0) {
            releaseHunting(client);movement.stop();change(Phase.PLAN,"Meat collected; preparing wood and cooking immediately.");return;
        }
        if(inventory().stream().noneMatch(ItemStack::isEmpty)) {block("Hunting stopped: inventory full, needs space for meat; items are not dropped automatically.");return;}
        if(client.gui.screen()!=null || owner.containerMenu!=owner.inventoryMenu || owner.isUsingItem()) return;
        if(prey!=null && !prey.isAlive()) {lastKill=prey.position();killUntil=now+8_000_000_000L;prey=null;nextHuntPath=0;}
        if(lastKill!=null && now<killUntil) {
            var drop=world.getEntitiesOfClass(ItemEntity.class,new AABB(lastKill,lastKill).inflate(6),e->e.isAlive()
                    && MEATS.containsKey(BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString())).stream()
                    .min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
            if(drop!=null)huntPath(drop.blockPosition(),now);return;
        }
        if(lastKill!=null) {block("Hunting stopped: meat not collected after kill. Check drops/server permissions before continuing.");return;}
        if(huntSearchTarget!=null) {
            huntOrigin=owner.position();
            if(now<nextFoodAreaScan && owner.blockPosition().distSqr(huntSearchTarget)>9 && now<foodSearchUntil && movement.busy())return;
            nextFoodAreaScan=now+1_000_000_000L;
            boolean found=!world.getEntitiesOfClass(Animal.class,owner.getBoundingBox().inflate(48),this::huntable).isEmpty();
            if(found || owner.blockPosition().distSqr(huntSearchTarget)<=9 || now>=foodSearchUntil || !movement.busy()) {
                movement.stop();huntSearchTarget=null;nextHuntPath=0;
            } else return;
        }
        if(prey==null || !huntable(prey) || now-preySince>25_000_000_000L) {
            if(prey!=null) skippedPrey.add(prey.getUUID());movement.stop();
            prey=world.getEntitiesOfClass(Animal.class,owner.getBoundingBox().inflate(48),a->huntable(a) && !skippedPrey.contains(a.getUUID()))
                    .stream().min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
            if(prey==null) {searchFoodArea(now);return;}
            preySince=now;huntAligned=0;nextHuntPath=0;
            notify.accept("Local: hunt "+BuiltInRegistries.ENTITY_TYPE.getKey(prey.getType())+"; stops when enough food is collected.");
        }
        if(owner.distanceTo(prey)>2.5 || !owner.hasLineOfSight(prey)) {huntAligned=0;huntPath(prey.blockPosition(),now);return;}
        movement.stop();nextHuntPath=0;
        int slot=huntWeapon();if(slot<0 || !select(client,slot)) {block("No hunting weapon with enough durability.");return;}
        if(client.getCameraEntity()!=owner || client.options.keyAttack.isUnbound() || client.options.keyAttack.isDown())return;
        Vec3 delta=prey.getBoundingBox().getCenter().subtract(owner.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90,pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        float ye=Mth.wrapDegrees(yaw-owner.getYRot()),pe=pitch-owner.getXRot();
        owner.setYRot(owner.getYRot()+Mth.clamp(ye,-15f,15f));owner.setXRot(Mth.clamp(owner.getXRot()+Mth.clamp(pe,-10f,10f),-90f,90f));
        huntAligned=Math.abs(ye)<=2 && Math.abs(pe)<=2?huntAligned+1:0;
        if(huntAligned>=2 && owner.getAttackStrengthScale(0)>=0.9f && owner.isWithinAttackRange(owner.getMainHandItem(),prey.getBoundingBox(),0)
                && client.crosshairPickEntity==prey && client.hitResult instanceof EntityHitResult hit && hit.getEntity()==prey && huntable(prey)) {
            KeyMapping.click(InputConstants.getKey(client.options.keyAttack.saveString()));huntAttack=true;huntAligned=0;
        }
    }
    private void searchFoodArea(long now) {
        if(!TaskPresets.foodSearchAllowed(foodSearchMoves,owner.position().distanceToSqr(huntSearchOrigin))) {beginFoodFallback(Minecraft.getInstance(),"No animals found after eight search areas.");return;}
        var origin=owner.blockPosition();
        int[][] offsets={{32,0},{23,23},{0,32},{-23,23},{-32,0},{-23,-23},{0,-32},{23,-23}};
        for(int i=0;i<offsets.length;i++) {
            int[] offset=offsets[(i+foodSearchMoves)%offsets.length];
            for(int dy=0;dy<=6;dy++)for(int sign:dy==0?new int[]{1}:new int[]{1,-1}) {
                BlockPos pos=origin.offset(offset[0],dy*sign,offset[1]);
                if(!world.hasChunkAt(pos) || !TaskPresets.foodSearchAllowed(foodSearchMoves,Vec3.atBottomCenterOf(pos).distanceToSqr(huntSearchOrigin))
                        || searchedFoodAreas.stream().anyMatch(p->p.distSqr(pos)<16*16))continue;
                if(!world.getBlockState(pos).isAir() || !world.getBlockState(pos.above()).isAir()
                        || !world.getBlockState(pos.below()).isCollisionShapeFullBlock(world,pos.below())
                        || !world.getFluidState(pos.below()).isEmpty() || world.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK)
                        || world.getBlockState(pos.below()).is(Blocks.CACTUS)
                        || world.getBiome(pos).unwrapKey().map(k->k.identifier().toString().equals("minecraft:deep_dark")).orElse(false))continue;
                if(!world.getEntitiesOfClass(Mob.class,new AABB(pos).inflate(12),m->m instanceof Enemy && m.isAlive()).isEmpty())continue;
                searchedFoodAreas.add(pos);foodSearchMoves++;huntSearchTarget=pos;foodSearchUntil=now+25_000_000_000L;
                nextHuntPath=0;huntPath(pos,now);
                notify.accept("Local: no animals nearby; searching another area ("+foodSearchMoves+"/8).");return;
            }
        }
        beginFoodFallback(Minecraft.getInstance(),"No safe loaded route to another animal search area.");
    }
    private void releaseHuntAttack(Minecraft client) {if(huntAttack) {client.options.keyAttack.consumeClick();huntAttack=false;}}
    private void beginFoodFallback(Minecraft client,String reason) {
        releaseHunting(client);movement.stop();foodFallbackStage=1;foodFallbackOrigin=owner.blockPosition();
        change(Phase.PLAN,reason+" Checking remembered and nearby mature crops.");
    }
    private boolean safeFoodSource(BlockPos pos) {
        return world.hasChunkAt(pos) && SurvivalPolicy.foodSourceWithinRange(pos.distSqr(owner.blockPosition()))
                && SurvivalPolicy.foodSourceWithinRange(pos.distSqr(foodFallbackOrigin))
                && !world.getBiome(pos).unwrapKey().map(k->k.identifier().toString().equals("minecraft:deep_dark")).orElse(false)
                && world.getEntitiesOfClass(Mob.class,new AABB(pos).inflate(12),m->m instanceof Enemy && m.isAlive()).isEmpty();
    }
    private void nextFoodSource(Minecraft client) {
        closeForMovement();
        if(foodFallbackStage==1) {
            foodFallbackStage=2;
            var origin=owner.blockPosition();
            var crops=new HashSet<BlockPos>(foodPlants.positions(client));
            for(var pos:crops)if(world.hasChunkAt(pos))foodPlants.observe(client,pos);
            for(var pos:BlockPos.betweenClosed(origin.offset(-32,-6,-32),origin.offset(32,6,32)))
                if(world.hasChunkAt(pos) && matureFoodCrop(pos)) {crops.add(pos.immutable());foodPlants.observe(client,pos);}
            cropCandidates=crops.stream().filter(p->p.distSqr(origin)<=100*100 && world.hasChunkAt(p) && matureFoodCrop(p)).sorted(Comparator.comparingDouble(p->p.distSqr(origin))).limit(64).toList();
            notify.accept("Local: checking remembered and nearby mature wheat and potatoes.");
        }
        foodSource=cropCandidates.stream().filter(p->!checkedFoodSources.contains(p) && world.hasChunkAt(p) && matureFoodCrop(p) && safeFoodSource(p)).findFirst().orElse(null);
        if(foodSource==null) {
            block("Food sources exhausted: animals and mature crops could not supply enough food. Bring food or move to another area, then resume the saved task.");return;
        }
        foodPlants.observe(client,foodSource);
        checkedFoodSources.add(foodSource);cropItem=world.getBlockState(foodSource).is(Blocks.WHEAT)?Items.WHEAT:Items.POTATO;
        cropBroken=false;cropClicks=0;nextCropClick=0;sourceUntil=System.nanoTime()+25_000_000_000L;
        var settings=baritone.api.BaritoneAPI.getSettings();huntBreak=settings.allowBreak.value;huntPlace=settings.allowPlace.value;settings.allowBreak.value=false;settings.allowPlace.value=false;
        movement.approachBlock(foodSource,"mature food crop");change(Phase.FOOD_CROP,"Local: approaching a mature food crop.");
    }
    private boolean matureFoodCrop(BlockPos pos) {
        var state=world.getBlockState(pos);
        return (state.is(Blocks.WHEAT) || state.is(Blocks.POTATOES)) && ((net.minecraft.world.level.block.CropBlock)state.getBlock()).isMaxAge(state);
    }
    private void foodCropTick(Minecraft client) {
        long now=System.nanoTime();
        if(now>sourceUntil) {releaseHunting(client);movement.stop();change(Phase.PLAN,"Crop harvest timed out; checking another source.");return;}
        if(client.gui.screen()!=null || owner.containerMenu!=owner.inventoryMenu || owner.isUsingItem())return;
        if(cropBroken) {
            var drop=world.getEntitiesOfClass(ItemEntity.class,new AABB(foodSource).inflate(6),e->e.isAlive() && e.getItem().is(cropItem)).stream().min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
            if(drop!=null) {huntPath(drop.blockPosition(),now);return;}
            if(now<nextCropClick)return;
            releaseHunting(client);movement.stop();change(Phase.PLAN,"Crop harvest finished; checking collected ingredients.");return;
        }
        if(!matureFoodCrop(foodSource)) {
            foodPlants.observe(client,foodSource);
            cropBroken=true;movement.stop();sourceUntil=Math.min(sourceUntil,now+8_000_000_000L);nextCropClick=now+2_000_000_000L;return;
        }
        if(client.getCameraEntity()!=owner || client.options.keyAttack.isUnbound() || client.options.keyAttack.isDown())return;
        Vec3 delta=Vec3.atCenterOf(foodSource).subtract(owner.getEyePosition());
        float yaw=(float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90,pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        owner.setYRot(owner.getYRot()+Mth.clamp(Mth.wrapDegrees(yaw-owner.getYRot()),-15f,15f));owner.setXRot(Mth.clamp(owner.getXRot()+Mth.clamp(pitch-owner.getXRot(),-10f,10f),-90f,90f));
        var hit=reachable(foodSource);
        if(hit!=null) {
            movement.stop();
            if(now>=nextCropClick && client.hitResult instanceof BlockHitResult pointed && pointed.getBlockPos().equals(foodSource)) {
                if(cropClicks++>=3) {releaseHunting(client);change(Phase.PLAN,"Crop harvest was not confirmed; skipping this crop.");return;}
                KeyMapping.click(InputConstants.getKey(client.options.keyAttack.saveString()));huntAttack=true;nextCropClick=now+1_000_000_000L;
            }
        } else if(now>=nextHuntPath) {movement.approachBlock(foodSource,"mature food crop");nextHuntPath=now+2_000_000_000L;}
    }
    private final class FoodPlants {
        private final java.util.concurrent.ExecutorService writer=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"bot-food-plants");t.setDaemon(true);return t;});
        private FoodPlantMemory memory;
        private String scope="";
        private BlockPos scanOrigin;
        private int scan;
        private long nextScan,nextSave;
        private boolean dirty;
        private void load(Minecraft client) {
            String current=automaticScope(client);
            if(current.equals(scope))return;
            flush();scope=current;memory=null;scanOrigin=null;scan=0;nextScan=0;
            var config=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot");
            var name=new ChestMemories(config.resolve("chest-memory.json")).fileFor(scope).getFileName();
            try {memory=new FoodPlantMemory(config.resolve("food-plants").resolve(name),scope);}
            catch(java.io.IOException e) {notify.accept("Cannot read food plant memory; existing file preserved.");}
        }
        void observe(Minecraft client,BlockPos pos) {
            if(client.level==null || client.player==null || !client.level.hasChunkAt(pos))return;
            load(client);if(memory==null)return;
            var state=client.level.getBlockState(pos);String food=null;boolean ready=false;
            if(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
                ready=crop.isMaxAge(state);
                if(state.is(Blocks.WHEAT))food="minecraft:bread";
                else if(state.is(Blocks.POTATOES))food="minecraft:baked_potato";
                else if(state.is(Blocks.CARROTS))food="minecraft:carrot";
                else if(state.is(Blocks.BEETROOTS))food="minecraft:beetroot";
            } else if(state.is(Blocks.SWEET_BERRY_BUSH)) {food="minecraft:sweet_berries";ready=state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE)>=2;}
            else if(state.is(Blocks.COCOA)) {food="minecraft:cocoa_beans";ready=state.getValue(net.minecraft.world.level.block.CocoaBlock.AGE)>=2;}
            else if(state.is(Blocks.MELON)) {food="minecraft:melon_slice";ready=true;}
            else if(state.is(Blocks.PUMPKIN)) {food="minecraft:pumpkin";ready=true;}
            else if(state.is(Blocks.OAK_LOG) || state.is(Blocks.DARK_OAK_LOG))food="minecraft:apple";
            String dimension=client.level.dimension().identifier().toString();
            if(food==null)dirty|=memory.remove(dimension,pos.getX(),pos.getY(),pos.getZ());
            else dirty|=memory.observe(new FoodPlantMemory.Plant(dimension,pos.getX(),pos.getY(),pos.getZ(),BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),food,ready,System.currentTimeMillis()));
        }
        List<BlockPos> positions(Minecraft client) {
            load(client);if(memory==null)return List.of();
            return memory.plants(client.level.dimension().identifier().toString()).stream().filter(p->p.block().equals("minecraft:wheat") || p.block().equals("minecraft:potatoes"))
                    .map(p->new BlockPos(p.x(),p.y(),p.z())).toList();
        }
        void tick(Minecraft client) {
            if(client.level==null || client.player==null) {flush();return;}
            load(client);if(memory==null || client.isPaused())return;
            long now=System.nanoTime();
            if(scanOrigin==null && now>=nextScan) {scanOrigin=client.player.blockPosition();scan=0;}
            if(scanOrigin!=null)for(int i=0;i<512 && scanOrigin!=null;i++,scan++) {
                int x=scan%33-16,y=(scan/33)%13-6,z=scan/(33*13)-16;
                if(z>16) {scanOrigin=null;nextScan=now+2_000_000_000L;break;}
                observe(client,scanOrigin.offset(x,y,z));
            }
            if(now>=nextSave) {flush();nextSave=now+2_000_000_000L;}
        }
        private void flush() {
            if(!dirty || memory==null)return;
            dirty=false;var captured=memory;
            writer.submit(()->{try {captured.save();}catch(java.io.IOException e) {LOG.warn("Could not save food plant memory",e);}});
        }
        void close() {flush();writer.shutdown();}
    }
    private void releaseHunting(Minecraft client) {
        releaseHuntAttack(client);prey=null;
        if(huntBreak!=null) {var settings=baritone.api.BaritoneAPI.getSettings();settings.allowBreak.value=huntBreak;settings.allowPlace.value=huntPlace;huntBreak=huntPlace=null;}
    }
}
