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
    private enum Phase { PLAN, WOOD, APPROACH, OPEN, PLACE, CRAFT, GOAL, HUNT, SMELT }
    private final BotCore bot;
    private final BaritoneController movement;
    private final Consumer<String> notify;
    private final ProductionController crafting=new ProductionController();
    private final ProductionController.BlockPlacement placement;
    private final ProductionController.FurnaceRun furnace;
    private Animal prey;
    private Vec3 huntOrigin,lastKill;
    private final Set<UUID> skippedPrey=new HashSet<>();
    private long nextHuntPath,preySince,killUntil;
    private int huntAligned;
    private boolean huntAttack;
    private Boolean huntBreak,huntPlace;
    private Integer shallowMinimum;
    private static final Map<String,String> MEATS=Map.of("minecraft:beef","minecraft:cooked_beef","minecraft:porkchop","minecraft:cooked_porkchop",
            "minecraft:mutton","minecraft:cooked_mutton","minecraft:chicken","minecraft:cooked_chicken");
    private boolean active;
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
    private int supplyIndex;
    private boolean automatic;
    private final Set<String> automaticWorlds=new HashSet<>();
    private String automaticScope(Minecraft client) {
        return client.getSingleplayerServer()!=null?"local:"+client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                :client.getCurrentServer()!=null?"server:"+client.getCurrentServer().ip.toLowerCase(Locale.ROOT):"session:"+System.identityHashCode(client.getConnection());
    }
    String starterCommand(String input) {
        if(input.equals("auto on") || input.equals("auto off")) {
            automatic=input.endsWith("on");
            if(automatic && !active) automaticWorlds.remove(automaticScope(Minecraft.getInstance()));
            return "Auto preparation on world entry: "+(automatic?"ON":"OFF")+". Once per world per session; failed steps are not retried automatically.";
        }
        if(input.equals("status")) return status;
        boolean foodOnly=input.equals("food") || input.startsWith("food ");int foodTarget=8;
        if(foodOnly && !input.equals("food")) {
            try {foodTarget=Integer.parseInt(input.substring(5));if(foodTarget<1 || foodTarget>64)throw new NumberFormatException();}
            catch(NumberFormatException e){return "Command: bot starter food [food portions 1-64].";}
        }
        if(!input.equals("start") && !foodOnly) return "Commands: bot starter start/status; bot starter food [1-64]; bot starter auto on/off.";
        if(active) return "Local task active. Use bot stop first.";
        TaskPresets.Workflow workflow;
        try { workflow=TaskPresets.workflow(foodOnly?"food.json":"starter-kit.json"); } catch(IllegalArgumentException e) {return e.getMessage();}
        TaskPresets.Starter plan=workflow.plan();
        if(foodOnly && input.startsWith("food ")) {
            int target=foodTarget;
            plan=new TaskPresets.Starter(plan.steps().stream().map(s->"food".equals(s.role())?new TaskPresets.Supply(s.label(),s.item(),target,s.action(),s.role()):s).toList(),plan.recipes());
        }
        return startWorkflow(new TaskPresets.Workflow(workflow.filename(),workflow.title(),workflow.description(),plan,null,workflow.goal()));
    }
    void validateWorkflow(TaskPresets.Workflow workflow) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null || !client.player.isAlive()) throw new IllegalArgumentException("Enter the world and respawn first.");
        for(var step:workflow.plan().steps()) {
            requiredItem(step.item());
            if(step.action().equals("mine")) {
                String goal=step.item().equals("minecraft:cobblestone")?"minecraft:stone":step.item().equals("minecraft:coal")?"minecraft:coal_ore":step.item();
                if(BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(goal)).filter(b->!b.defaultBlockState().isAir()).isEmpty())
                    throw new IllegalArgumentException("Mining step has no valid block: "+step.item());
            }
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
        starter=workflow.plan();workflowFile=workflow.filename();workflowTitle=workflow.title();workflowState="RUNNING";
        initiallySatisfied=new boolean[starter.steps().size()];
        for(int i=0;i<initiallySatisfied.length;i++)initiallySatisfied[i]=supplyCount(client,starter.steps().get(i))>=starter.steps().get(i).count();
        boolean finished=workflow.goal()!=null && workflow.goal().satisfied(supplyCount(client,starter.steps().getLast()));
        if(finished)Arrays.fill(initiallySatisfied,true);
        supplyIndex=finished?starter.steps().size():0;clock=batches=0;started=System.nanoTime();active=true;
        change(Phase.PLAN,finished?"Goal already satisfied for "+workflowTitle+"; skipping all preparation steps."
                :"Running "+workflowTitle+" ("+workflowFile+"); checked existing items, only collecting what is missing.");return status;
    }
    com.google.gson.JsonObject starterSnapshot(Minecraft client) {
        var data=new com.google.gson.JsonObject();data.addProperty("automatic",automatic);data.addProperty("active",active && starter!=null);
        data.addProperty("status",status);data.addProperty("step",supplyIndex);
        data.addProperty("file",workflowFile);data.addProperty("title",workflowTitle);data.addProperty("state",workflowState);
        data.addProperty("phase",phase==null?"":phase.name());
        var steps=new com.google.gson.JsonArray();
        int index=0;
        if(starter!=null) for(var step:starter.steps()) {
            var row=new com.google.gson.JsonObject();row.addProperty("label",step.label());row.addProperty("item",step.item());row.addProperty("target",step.count());
            row.addProperty("action",step.action());
            row.addProperty("state",index<supplyIndex?(initiallySatisfied[index]?"SKIPPED":"COMPLETED"):index==supplyIndex && workflowState.equals("CANCELLED")?"STOPPED":index==supplyIndex && active?"RUNNING":"WAITING");index++;
            row.addProperty("present",client.player==null?0:supplyCount(client,step));steps.add(row);
        }
        data.add("steps",steps);return data;
    }
    private int supplyCount(Minecraft client,TaskPresets.Supply step) {
        if(step.item().equals("minecraft:furnace") && furnace.nearby(client,"furnace")) return 1;
        return client.player.getInventory().getNonEquipmentItems().stream().filter(s->{
            if(s.isEmpty()) return false;
            if("logs".equals(step.role())) return logType(s)!=null;
            if("food".equals(step.role())) {
                var food=s.get(net.minecraft.core.component.DataComponents.FOOD);
                var use=s.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
                return food!=null && food.nutrition()>=4 && use!=null && use.onConsumeEffects().isEmpty();
            }
            if(step.role()!=null) {
                String id=BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                return id.endsWith("_"+step.role()) && (id.contains("stone_") || id.contains("iron_") || id.contains("diamond_") || id.contains("netherite_"))
                        && (!step.role().equals("pickaxe") || !BaritoneController.silkTouch(s))
                        && (!s.isDamageableItem() || s.getMaxDamage()-s.getDamageValue()>10);
            }
            return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(step.item());
        }).mapToInt(ItemStack::getCount).sum();
    }
    private static final List<String> WOODS=List.of("oak","birch","spruce","jungle","acacia","dark_oak","mangrove","cherry","pale_oak");
    LocalTaskRunner(BotCore bot,BaritoneController movement,ProductionController.BlockPlacement placement,ProductionController.FurnaceRun furnace,Consumer<String> notify) { this.bot=bot;this.movement=movement;this.placement=placement;this.furnace=furnace;this.notify=notify; }
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
    private int count(Item item) { return count(s->s.is(item)); }
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
        if(client.player!=owner || client.level!=world || !owner.isAlive() || bot.state()!=BotState.RUNNING) { cancel("Local stopped: world/player changed, player died or bot state changed.");return; }
        if(System.nanoTime()-started>(starter==null?900_000_000_000L:1_800_000_000_000L)
                || System.nanoTime()-phaseStarted>(phase==Phase.SMELT?900_000_000_000L:180_000_000_000L)) { cancel("Local stopped: current step timed out.");return; }
        if(client.isPaused() || client.gui.overlay()!=null) return;
        try {
            if(phase==Phase.HUNT) {huntTick(client);return;}
            if(phase==Phase.SMELT) {
                if(furnace.busy()) {status="Food preparation: "+furnace.status();return;}
                if(count(craftedItem)<=craftedBefore) {cancel("Food cooking unsuccessful: "+furnace.status());return;}
                change(Phase.PLAN,"Food cooked and collected; checking remaining needs.");return;
            }
            if(phase==Phase.CRAFT) {
                crafting.tick(client);
                if(!crafting.busy()) {
                    if(crafting.failure()!=null) { cancel("Crafting stopped: "+crafting.failure());return; }
                    if(count(craftedItem)<=craftedBefore) { cancel("Output not found in inventory; stopped.");return; }
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
                    cancel("Baritone stopped before collecting enough "+definition.resultItem()+": "+collected+"/"+quantity+". "+movement.status());
                }
                return;
            }
            if(phase==Phase.WOOD) {
                if(count(s->logType(s)!=null)-initialWood>=requestedWood) { movement.stop();change(Phase.PLAN,"Enough wood for tools collected."); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) cancel("Not enough wood collected yet. "+movement.status());
                return;
            }
            if(phase==Phase.PLACE) {
                if(placement.busy()) return;
                placedAt=placement.target();
                if(placedAt!=null && world.getBlockState(placedAt).is(Blocks.CRAFTING_TABLE)) { owner.getInventory().setSelectedSlot(oldSelected);table=placedAt;change(Phase.PLAN,"Crafting table placed."); }
                else if(!placement.busy()) cancel(placement.status());
                return;
            }
            if(phase==Phase.OPEN) {
                if(owner.containerMenu instanceof CraftingMenu) change(Phase.PLAN,"Crafting table opened.");
                else if(System.nanoTime()-phaseStarted>5_000_000_000L) cancel("Could not open crafting table; check server permissions.");
                return;
            }
            if(phase==Phase.APPROACH) {
                if(!world.getBlockState(table).is(Blocks.CRAFTING_TABLE)) { cancel("Target crafting table disappeared.");return; }
                BlockHitResult hit=reachable(table);
                if(hit!=null) { movement.stop();openTable(client,hit); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) cancel("Crafting table approach stopped before reaching the table.");
                return;
            }
            if(++clock%10!=0) return;
            if(!(owner.containerMenu instanceof InventoryMenu) && !(owner.containerMenu instanceof CraftingMenu)) { cancel("Another menu is open; local task stopped.");return; }
            if(!owner.containerMenu.getCarried().isEmpty()) { cancel("Cursor contains an item; store it first.");return; }
            plan(client);
        } catch(Exception failure) { cancel("Local stopped: "+(failure instanceof IllegalArgumentException ? failure.getMessage():"Cannot access game state.")); }
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
                if(preparation!=null && !select(client,pickaxe())) throw new IllegalArgumentException("Could not move pickaxe to hotbar.");
                String response=movement.execute("mine "+definition.goal()+" "+(quantity-Math.max(0,resultCount()-initialResult)));
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
        while(supplyIndex<starter.steps().size() && supplyCount(client,starter.steps().get(supplyIndex))>=starter.steps().get(supplyIndex).count()) supplyIndex++;
        if(supplyIndex==starter.steps().size()) {
            closeForMovement();finish("Task "+workflowTitle+" completed. Checked "+supplyIndex+" steps; no AI calls.");return true;
        }
        var step=starter.steps().get(supplyIndex);
        if(step.action().equals("food")) {planFood(client,step);return true;}
        if(step.action().equals("craft")) {prepareRecipe(client,starter.recipes().values().stream().filter(r->r.output().equals(step.item())).findFirst().orElseThrow(),new HashSet<>());return true;}
        String item="logs".equals(step.role())?"minecraft:"+nearestWood()+"_log":step.item();
        configureMine(item,step.count()-supplyCount(client,step));return false;
    }
    private void configureMine(String item,int missing) {
        String goal=item.equals("minecraft:coal")?"minecraft:coal_ore":item;
        definition=new TaskPresets.Task("Survival preparation",goal,item,missing,null);
        preparation=item.equals("minecraft:cobblestone")?catalog.preparations().get("wooden_pickaxe"):null;
        initialResult=resultCount();quantity=missing;
        if(item.equals("minecraft:cobblestone") && starter!=null && starter.steps().get(supplyIndex).action().equals("food") && shallowMinimum==null) {
            shallowMinimum=baritone.api.BaritoneAPI.getSettings().minYLevelWhileMining.value;
            baritone.api.BaritoneAPI.getSettings().minYLevelWhileMining.value=Math.max(shallowMinimum,owner.blockPosition().getY()-3);
        }
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
        if(!active) return;active=false;if(starter!=null)workflowState="CANCELLED";releaseHunting(Minecraft.getInstance());restoreShallow();movement.stop();furnace.cancel(Minecraft.getInstance(),"Local task smelting canceled.");placement.cancel("Local task table placement canceled.");crafting.cancel(Minecraft.getInstance());
        if(owner!=null && Minecraft.getInstance().player==owner) owner.getInventory().setSelectedSlot(oldSelected);
        status=reason+" If items remain on the crafting grid/cursor, store them; items will not be dropped automatically.";LOG.warn("{}",status);notify.accept(status);
    }
    private void finish(String message) { active=false;if(starter!=null)workflowState="COMPLETED";releaseHunting(Minecraft.getInstance());restoreShallow();movement.stop();owner.getInventory().setSelectedSlot(oldSelected);status=message;LOG.info("{}",status);notify.accept(status); }

    private void restoreShallow() {
        if(shallowMinimum!=null) {baritone.api.BaritoneAPI.getSettings().minYLevelWhileMining.value=shallowMinimum;shallowMinimum=null;}
    }
    private int rawMeat() {return count(s->MEATS.containsKey(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));}
    private void planFood(Minecraft client,TaskPresets.Supply step) {
        int missing=step.count()-supplyCount(client,step);
        if(missing<=0) return;
        String raw=MEATS.keySet().stream().filter(id->count(requiredItem(id))>0).max(Comparator.comparingInt(id->count(requiredItem(id)))).orElse(null);
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
            closeForMovement();craftedItem=requiredItem(MEATS.get(raw));craftedBefore=count(craftedItem);
            String response=furnace.start(client,"furnace",raw,batch,BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString());
            if(!furnace.busy()) throw new IllegalArgumentException(response);
            change(Phase.SMELT,"Cook food before going underground: "+response);return;
        }
        if(huntWeapon()<0) {prepareRecipe(client,starter.recipes().get("wooden_sword"),new HashSet<>());return;}
        closeForMovement();movement.stop();restoreShallow();
        prey=null;lastKill=null;skippedPrey.clear();huntOrigin=owner.position();nextHuntPath=killUntil=0;huntAligned=0;
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
        if(owner.position().distanceToSqr(huntOrigin)>48*48) {cancel("Hunting stopped: reached the 48-block limit; no unlimited chasing.");return;}
        if(!world.getEntitiesOfClass(Mob.class,owner.getBoundingBox().inflate(12),m->m instanceof Enemy && m.isAlive()).isEmpty()) {
            cancel("Hunting stopped: hostile mob nearby; survival takes priority.");return;
        }
        if(rawMeat()>0) {
            releaseHunting(client);movement.stop();change(Phase.PLAN,"Meat collected; preparing wood and cooking immediately.");return;
        }
        if(inventory().stream().noneMatch(ItemStack::isEmpty)) {cancel("Hunting stopped: inventory full, needs space for meat; items are not dropped automatically.");return;}
        if(client.gui.screen()!=null || owner.containerMenu!=owner.inventoryMenu || owner.isUsingItem()) return;
        if(prey!=null && !prey.isAlive()) {lastKill=prey.position();killUntil=now+8_000_000_000L;prey=null;nextHuntPath=0;}
        if(lastKill!=null && now<killUntil) {
            var drop=world.getEntitiesOfClass(ItemEntity.class,new AABB(lastKill,lastKill).inflate(6),e->e.isAlive()
                    && MEATS.containsKey(BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString())).stream()
                    .min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
            if(drop!=null)huntPath(drop.blockPosition(),now);return;
        }
        if(lastKill!=null) {cancel("Hunting stopped: meat not collected after kill. Check drops/server permissions before continuing.");return;}
        if(prey==null || !huntable(prey) || now-preySince>25_000_000_000L) {
            if(prey!=null) skippedPrey.add(prey.getUUID());movement.stop();
            prey=world.getEntitiesOfClass(Animal.class,owner.getBoundingBox().inflate(48),a->huntable(a) && !skippedPrey.contains(a.getUUID()))
                    .stream().min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
            if(prey==null) {cancel("No suitable adult animal in loaded chunks (48 blocks). Bring meat/food or move elsewhere and rerun.");return;}
            preySince=now;huntAligned=0;nextHuntPath=0;
            notify.accept("Local: hunt "+BuiltInRegistries.ENTITY_TYPE.getKey(prey.getType())+"; stops when enough food is collected.");
        }
        if(owner.distanceTo(prey)>2.5 || !owner.hasLineOfSight(prey)) {huntAligned=0;huntPath(prey.blockPosition(),now);return;}
        movement.stop();nextHuntPath=0;
        int slot=huntWeapon();if(slot<0 || !select(client,slot)) {cancel("No hunting weapon with enough durability.");return;}
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
    private void releaseHuntAttack(Minecraft client) {if(huntAttack) {client.options.keyAttack.consumeClick();huntAttack=false;}}
    private void releaseHunting(Minecraft client) {
        releaseHuntAttack(client);prey=null;
        if(huntBreak!=null) {var settings=baritone.api.BaritoneAPI.getSettings();settings.allowBreak.value=huntBreak;settings.allowPlace.value=huntPlace;huntBreak=huntPlace=null;}
    }
}
