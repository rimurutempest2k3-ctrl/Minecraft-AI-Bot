package minecraftaibot.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import dev.minecraftaibot.common.*;
import dev.minecraftaibot.common.ai.AgentLoop;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinecraftAIBotClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("minecraft-ai-bot");
    private final BotCore bot = new BotCore();
    private final BaritoneController movement = new BaritoneController(bot);
    private final ChestController.MemoryObserver chestMemory = new ChestController.MemoryObserver(this::notifyConsole);
    private final ChestController chest = new ChestController(bot, movement, chestMemory, this::notifyConsole);
    private final ProductionController.BlockPlacement placement = new ProductionController.BlockPlacement(movement,this::notifyConsole);
    private final ProductionController.FurnaceRun furnace = new ProductionController.FurnaceRun(bot, movement, placement, this::notifyConsole);
    private final LocalTaskRunner localTasks = new LocalTaskRunner(bot, movement, placement, furnace, this::notifyConsole);
    private final SurvivalController survival = new SurvivalController(bot,movement,this::interruptForSurvival,this::notifyConsole,this::idleForEquipment);
    private final CommandDispatcher commands = new CommandDispatcher(bot, this::describeGame,
            this::executeAction, this::describeInventory, this::askAi,
            this::assignAiKey, this::executeChest);
    private String executeChest(String input) {
        if(queuedWorkflow!=null && !java.util.Set.of("status","memory","list").contains(input) && !input.startsWith("show "))return "Switching JSON task; wait for startup or use bot stop.";
        if(survival.busy() && !java.util.Set.of("status","memory","list").contains(input) && !input.startsWith("show "))
            return "Survival action active. Use bot stop first.";
        if(furnace.busy() && !java.util.Set.of("status","memory","list").contains(input) && !input.startsWith("show "))
            return "Local smelting active. Use bot stop before interacting with chests.";
        if(placement.busy()) return "Waiting for crafting table placement. Wait for the result or use bot stop.";
        if(localTasks.busy() && !(input.equals("status") || input.equals("memory") || input.startsWith("show ")))
            return "Local task running. Use bot stop before interacting with chests.";
        return chest.execute(input);
    }
    private boolean idleForEquipment() {
        return queuedWorkflow==null && !movement.busy() && !chest.busy() && !placement.busy() && !localTasks.busy() && !furnace.busy()
                && (ai==null || !ai.busy()) && (agent==null || !agent.active());
    }
    private volatile ConsoleServer console;
    private volatile WebConsole web;
    private AiController ai;
    private AgentLoop agent;
    private int agentTicks;
    private TaskPresets.Workflow queuedWorkflow;
    private net.minecraft.client.player.LocalPlayer queuedPlayer;
    private net.minecraft.client.multiplayer.ClientLevel queuedWorld;
    private int queuedTicks;
    private net.minecraft.client.multiplayer.ClientLevel agentWorld;
    private net.minecraft.client.player.LocalPlayer agentPlayer;
    private boolean agentValid(Minecraft client) {
        return client.level != null && client.level == agentWorld && client.player != null && client.player == agentPlayer
                && client.player.isAlive() && bot.state() == BotState.RUNNING;
    }
    private String observation() {
        Minecraft client = Minecraft.getInstance();
        return describeGame() + "\n" + describeInventory() + "\nOpen chest: " + chest.execute("list")
                + "\nCHEST MEMORY FOR CURRENT SERVER/DIMENSION (last contents; reopen to verify): "
                + (client.level == null ? "Not in a world." : chestMemory.summary(client))
                + "\nAvailable local tasks: "+commands.execute("bot task list");
    }
    private void stopAgentGame() { queuedWorkflow=null;if (chest.busy()) chest.cancel(); furnace.cancel(Minecraft.getInstance(),"Current smelting task canceled."); localTasks.cancel("Current local task canceled."); placement.cancel("Block placement step canceled."); movement.stop(); }
    private boolean canCloseMenu(Minecraft client) {
        var player=client.player;var stored=new java.util.ArrayList<>(player.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList());
        var returning=new java.util.ArrayList<ItemStack>();returning.add(player.containerMenu.getCarried());
        if(player.containerMenu instanceof net.minecraft.world.inventory.AbstractCraftingMenu menu)
            menu.getInputGridSlots().forEach(slot->returning.add(slot.getItem()));
        for(var item:returning) {
            int left=item.getCount();if(left==0)continue;
            for(var stack:stored) if(!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack,item)) {
                int moved=Math.min(left,Math.max(0,stack.getMaxStackSize()-stack.getCount()));stack.grow(moved);left-=moved;
            }
            for(int i=0;i<stored.size() && left>0;i++) if(stored.get(i).isEmpty()) {
                int moved=Math.min(left,item.getMaxStackSize());
                stored.set(i,item.copyWithCount(moved));left-=moved;
            }
            if(left>0)return false;
        }
        return true;
    }
    private String runWorkflow(String filename) {
        TaskPresets.Workflow workflow;
        try {workflow=TaskPresets.workflow(filename);localTasks.validateWorkflow(workflow);}catch(RuntimeException e){return "Cannot run task: "+e.getMessage();}
        Minecraft client=Minecraft.getInstance();
        if(agent!=null && agent.active()) agent.cancel("AI canceled to run a local task file.");
        survival.cancel(client);stopAgentGame();
        if(!canCloseMenu(client))return "Previous work stopped; inventory lacks space for ingredients/cursor. Store items and rerun the file.";
        client.player.closeContainer();bot.start();
        queuedWorkflow=workflow;queuedPlayer=client.player;queuedWorld=client.level;queuedTicks=0;
        return "Previous task stopped. Switching to "+workflow.title()+" ("+filename+").";
    }
    private void tickQueuedWorkflow(Minecraft client) {
        if(queuedWorkflow==null)return;
        if(client.player!=queuedPlayer || client.level!=queuedWorld || !queuedPlayer.isAlive() || bot.state()!=BotState.RUNNING) {queuedWorkflow=null;notifyConsole("Task switch canceled because game state changed.");return;}
        if(++queuedTicks<5)return;
        boolean ready=client.gui.screen()==null && client.gui.overlay()==null && !client.isPaused()
                && client.player.containerMenu==client.player.inventoryMenu && client.player.containerMenu.getCarried().isEmpty()
                && client.player.inventoryMenu.getInputGridSlots().stream().allMatch(s->s.getItem().isEmpty());
        if(!ready) {if(queuedTicks>=100) {queuedWorkflow=null;notifyConsole("Could not switch task: menu/ingredients not stored, or game paused. Close the menu and run again.");}return;}
        var workflow=queuedWorkflow;queuedWorkflow=null;notifyConsole(localTasks.startWorkflow(workflow));
    }
    private boolean interruptForSurvival() {
        if(agent!=null && agent.active()) agent.cancel("AI task canceled to prioritize survival.");
        else stopAgentGame();
        Minecraft client=Minecraft.getInstance();
        if(!client.player.containerMenu.getCarried().isEmpty()) return false;
        if(client.player.containerMenu instanceof net.minecraft.world.inventory.AbstractCraftingMenu menu
                && menu.getInputGridSlots().stream().anyMatch(slot->!slot.getItem().isEmpty())) return false;
        client.player.closeContainer();
        return client.player.containerMenu==client.player.inventoryMenu;
    }
    private String consoleCommand(String input) {
        if(input.startsWith("bot web baritone ")) {
            if(survival.busy() || localTasks.busy() || furnace.busy() || placement.busy()) return "Wait for the current action to finish before changing Baritone settings.";
            return movement.webSetting(input.substring(17));
        }
        String normalized=input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        // Display preferences must never interrupt an AI/local/survival task.
        if(normalized.equals("bot language") || normalized.startsWith("bot language "))return commands.execute(input);
        if(normalized.startsWith("bot workflow run "))return runWorkflow(normalized.substring(17));
        if(normalized.equals("bot workflow list") || normalized.equals("bot workflow reload")) {
            try {return "File JSON: "+TaskPresets.workflows().stream().map(w->w.filename()+(w.error()==null?"":" (error)")).collect(Collectors.joining(", "));}
            catch(RuntimeException e){return e.getMessage();}
        }
        if(normalized.equals("bot help") || normalized.equals("bot task list"))
            return commands.execute(input)+"\nLocal files: bot workflow list/reload; bot workflow run <name.json>. Preparation: bot starter start/status; bot starter food [1-64]; bot starter auto on/off.";
        if(normalized.startsWith("bot starter ")) {
            String option=normalized.substring(12);
            if(option.equals("start") || option.equals("food") || option.startsWith("food ")) {
                if(!idleForEquipment() || survival.busy()) return "Task or survival action active; wait or use bot stop.";
            }
            return localTasks.starterCommand(option);
        }
        if(normalized.equals("bot trash") || normalized.startsWith("bot trash "))
            return survival.trashCommand(normalized.equals("bot trash")?"status":normalized.substring(10));
        if(normalized.equals("bot loot") || normalized.startsWith("bot loot "))
            return survival.lootCommand(normalized.equals("bot loot")?"status":normalized.substring(9));
        if(normalized.equals("bot survival") || normalized.startsWith("bot survival "))
            return survival.command(normalized.equals("bot survival")?"":normalized.substring(13));
        boolean readOnly=java.util.Set.of("bot info", "bot inventory", "bot status", "bot help", "bot task list", "bot task check", "bot task inventory", "bot ai status", "bot ai result", "bot chest memory", "bot chest list", "bot chest status").contains(normalized)
                || normalized.startsWith("bot chest show ") || normalized.equals("bot furnace")
                || normalized.equals("bot furnace status") || normalized.startsWith("bot furnace check ") || normalized.startsWith("bot furnace plan ");
        boolean aiControl=normalized.equals("bot ai auto") || normalized.startsWith("bot ai auto ")
                || normalized.startsWith("bot ai run ") || (agent != null && agent.autoEnabled() && normalized.startsWith("bot ai ask "))
                || normalized.equals("bot ai cancel");
        if (agent != null && agent.active() && !readOnly && !aiControl)
            agent.cancel("AI task interrupted by a manual command.");
        if(localTasks.busy() && !readOnly && (normalized.startsWith("bot mine ") || normalized.startsWith("bot goto ") || normalized.startsWith("bot task ")))
            return "Local task running. Use bot stop before assigning another task.";
        return commands.execute(input);
    }
    private void notifyConsole(String message) {
        ConsoleServer connected = console;
        if (connected != null) connected.notifyConsole(message);
        WebConsole browser=web;
        if(browser!=null) browser.publish(message);
    }
    private void notifyAiConsole(String message) {
        ConsoleServer connected=console;if(connected!=null) connected.notifyConsole(message);
        WebConsole browser=web;
        if(browser!=null) {
            boolean progress=message.startsWith("AI: Asking") || message.contains("automatic retry") || message.startsWith("Executing:");
            boolean error=message.startsWith("AI task stopped:") || !progress && message.startsWith("AI:") && message.contains("HTTP");
            browser.publish(message,progress?WebConsole.Channel.ACTIVITY:error?WebConsole.Channel.ERROR:WebConsole.Channel.AI);
        }
    }
    private String executeAction(String input) {
        if(input.startsWith("goto ")) {
            String[] parts=input.split(" ");
            if(survival.avoidsDestination(Minecraft.getInstance(),new net.minecraft.core.BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]))))
                return "Target belongs to a biome in danger-biomes.json; movement rejected.";
        }
        if(input.equals("furnace status")) return furnace.status();
        if(input.equals("furnace") || input.startsWith("furnace check ") || input.startsWith("furnace plan "))
            return ProductionController.FurnaceLogic.query(Minecraft.getInstance(),input.equals("furnace")?"":input.substring(8));
        if(queuedWorkflow!=null) {
            if(input.equals("stop") || input.equals("pause"))queuedWorkflow=null;
            else return "Switching JSON task; wait for startup or use bot stop.";
        }
        if (input.equals("stop") || input.equals("pause")) { survival.cancel(Minecraft.getInstance()); if (chest.busy()) chest.cancel(); furnace.cancel(Minecraft.getInstance(),"Smelting batch canceled by bot "+input+"."); localTasks.cancel("Local workflow canceled by bot " + input + "."); placement.cancel("Pending block placement canceled."); }
        else if(survival.busy()) return "Survival action active. Use bot stop before assigning another task.";
        else if(furnace.busy()) return "Local smelting active. Use bot stop before assigning another task.";
        else if (chest.busy()) return "Chest action active. Use bot stop before assigning movement/mining.";
        else if(localTasks.busy()) return "Local workflow running. Use bot stop first.";
        else if(placement.busy()) return "Waiting for table placement. Wait for the result or use bot stop.";
        if(input.startsWith("furnace run ")) {
            String[] parts=input.split(" ");
            return furnace.start(Minecraft.getInstance(),parts[2],parts[3],Integer.parseInt(parts[4]),parts[5]);
        }
        if(input.startsWith("place ")) {
            if(movement.busy()) return "Moving/mining. Use bot stop then bot start before placing a table.";
            String[] parts=input.split(" ");
            net.minecraft.core.BlockPos target=parts.length==5 ? new net.minecraft.core.BlockPos(Integer.parseInt(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4])):null;
            try { return placement.start(Minecraft.getInstance(),target); }
            catch(IllegalArgumentException failure) { return failure.getMessage(); }
        }
        if(input.startsWith("task ")) {
            String[] parts=input.split(" ");return localTasks.start(parts[1],Integer.parseInt(parts[2]),parts.length==4 && parts[3].equals("total"));
        }
        if(input.startsWith("mine ")) {
            String[] parts=input.split(" ");
            if(parts[1].equals("cobblestone") || parts[1].equals("minecraft:cobblestone")) return localTasks.start("cobblestone",Integer.parseInt(parts[2]));
        }
        return movement.execute(input);
    }

    private String assignAiKey(String key) {
        return ai == null ? "AI module could not initialize." : ai.assignKey(key);
    }

    private String askAi(String input) {
        if (ai == null) return "AI module could not initialize; see game logs.";
        if(agent==null) return "AI executor could not initialize.";
        Minecraft client = Minecraft.getInstance();
        if(input.equalsIgnoreCase("auto") || input.equalsIgnoreCase("auto status"))
            return "AI execution: "+(agent.autoEnabled()?"ON":"OFF")+". Command: bot ai auto on/off. Defaults to OFF each game session.";
        if(input.equalsIgnoreCase("auto on")) return agent.setAuto(true);
        if(input.equalsIgnoreCase("auto off")) return agent.setAuto(false);
        if(input.regionMatches(true,0,"auto ",0,5)) return "Commands: bot ai auto on/off/status.";
        if (input.equalsIgnoreCase("cancel")) {
            agent.cancel("AI task canceled; pending responses will not execute."); return agent.status();
        }
        if (input.equalsIgnoreCase("status")) return "AI execution: "+(agent.autoEnabled()?"ON":"OFF")+" | "+agent.status() + "\n" + ai.execute(input, "");
        if(agent.autoEnabled() && input.regionMatches(true,0,"ask ",0,4) && !input.substring(4).isBlank())
            input="run "+input.substring(4);
        if (input.regionMatches(true,0,"run ",0,4) && !input.substring(4).isBlank()) {
            if(!agent.autoEnabled()) return "AI execution is OFF. Enable bot ai auto on first; bot ai ask currently only suggests actions.";
            if (agent.active()) return agent.status() + ". Use bot ai cancel first.";
            if (queuedWorkflow!=null || ai.busy() || movement.busy() || chest.busy() || localTasks.busy() || placement.busy() || furnace.busy() || survival.busy()) return "AI request or game task active; wait or use bot stop first.";
            if (client.player == null || client.level == null || !client.player.isAlive()) return "Enter the world first.";
            if (bot.state() != BotState.RUNNING) return "Enter bot start before using bot ai run.";
            agentWorld=client.level; agentPlayer=client.player;
            return agent.start(input.substring(4));
        }
        if (input.isBlank()) return "Commands: bot ai auto on/off/status; bot ai ask/run <task>; bot ai cancel/status. With auto OFF, ask only suggests actions.";
        return ai.execute(input, observation());
    }

    private String describeInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "Not in a world. Enter the game before inspecting inventory.";
        }
        var slots = client.player.getInventory().getNonEquipmentItems();
        var counts = new TreeMap<String, Integer>();
        int empty = 0;
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) {
                empty++;
            } else {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
                total += stack.getCount();
            }
        }
        String items = counts.isEmpty() ? "Empty inventory" : counts.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining("; "));
        return "Inventory + hotbar: " + slots.size() + " slots | Empty: " + empty + "/" + slots.size()
                + " | Total items: " + total + " | " + items;
    }

    // Called by the dispatcher on Minecraft's client thread, like all console commands.
    private com.google.gson.JsonObject webItem(ItemStack stack) {
        var item=new com.google.gson.JsonObject();item.addProperty("count",stack.getCount());
        if(!stack.isEmpty()) {
            item.addProperty("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.addProperty("name",stack.getHoverName().getString());
            if(stack.isDamageableItem()) item.addProperty("durability",(stack.getMaxDamage()-stack.getDamageValue())+"/"+stack.getMaxDamage());
        }
        return item;
    }
    private String webSnapshot() {
        Minecraft client=Minecraft.getInstance();var data=new com.google.gson.JsonObject();var gson=new com.google.gson.Gson();
        data.addProperty("inWorld",client.player!=null && client.level!=null);data.addProperty("state",bot.state().name());
        data.addProperty("language",I18n.language());
        var features=new java.util.TreeMap<>(survival.webFeatures());features.put("ai",agent!=null && agent.autoEnabled());
        data.add("features",gson.toJsonTree(features));data.add("baritone",movement.webSettings());
        data.add("starter",localTasks.starterSnapshot(client));
        var library=new com.google.gson.JsonObject();
        try {
            library.addProperty("directory",TaskPresets.workflowDirectory().toString());
            var files=new com.google.gson.JsonArray();for(var entry:TaskPresets.workflows()) {
                var file=new com.google.gson.JsonObject();file.addProperty("filename",entry.filename());file.addProperty("title",entry.title());file.addProperty("description",entry.description());
                if(entry.error()!=null)file.addProperty("error",entry.error());
                else {
                    var steps=new com.google.gson.JsonArray();for(var step:entry.plan().steps()) {
                        var row=gson.toJsonTree(step).getAsJsonObject();
                        var recipe=entry.plan().recipes().values().stream().filter(r->r.output().equals(step.item())).findFirst();
                        var ingredients=new java.util.TreeMap<String,Integer>();
                        recipe.ifPresent(r->r.cells().forEach(cell->ingredients.merge(cell.ingredient(),1,Integer::sum)));
                        row.add("ingredients",gson.toJsonTree(ingredients));steps.add(row);
                    }
                    file.add("steps",steps);
                }
                files.add(file);
            }
            library.add("files",files);
        }
        catch(RuntimeException e){library.addProperty("error",e.getMessage());}
        data.add("workflows",library);
        data.add("ai",ai==null?new com.google.gson.JsonObject():ai.webSettings());data.add("chests",chestMemory.webSnapshot(client));
        var tasks=new com.google.gson.JsonObject();tasks.addProperty("local",localTasks.status());tasks.addProperty("movement",movement.status());
        tasks.addProperty("furnace",furnace.status());tasks.addProperty("placement",placement.status());
        tasks.addProperty("chest",chest.status());tasks.addProperty("survival",survival.status()+" | "+survival.riskStatus());
        tasks.addProperty("ai",agent==null?"AI not ready":agent.status());data.add("tasks",tasks);
        data.add("progress",movement.webProgress());
        if(client.player!=null && client.level!=null) {
            var player=client.player;data.addProperty("name",player.getName().getString());
            data.addProperty("server",client.getCurrentServer()!=null?client.getCurrentServer().name:"Single-player world");
            data.addProperty("dimension",client.level.dimension().identifier().toString());
            data.addProperty("health",player.getHealth());data.addProperty("maxHealth",player.getMaxHealth());
            data.addProperty("hunger",player.getFoodData().getFoodLevel());
            data.addProperty("coordinates",String.format(Locale.ROOT,"%.2f · %.2f · %.2f",player.getX(),player.getY(),player.getZ()));
            data.add("held",webItem(player.getMainHandItem()));data.add("offhand",webItem(player.getOffhandItem()));
            var inventory=new com.google.gson.JsonArray();for(var stack:player.getInventory().getNonEquipmentItems()) inventory.add(webItem(stack));
            data.add("inventory",inventory);var equipment=new com.google.gson.JsonObject();
            for(var slot:java.util.List.of(net.minecraft.world.entity.EquipmentSlot.HEAD,net.minecraft.world.entity.EquipmentSlot.CHEST,net.minecraft.world.entity.EquipmentSlot.LEGS,net.minecraft.world.entity.EquipmentSlot.FEET))
                equipment.add(slot.name(),webItem(player.getItemBySlot(slot)));
            data.add("equipment",equipment);
        }
        return gson.toJson(data);
    }
    private String describeGame() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "Not in a world. Open a single-player world or connect to a server.";
        }
        var player = client.player;
        ItemStack held = player.getMainHandItem();
        String item = held.isEmpty() ? "Empty hand"
                : held.getHoverName().getString() + " x" + held.getCount();
        return String.format(Locale.ROOT,
                "In a world | Bot: %s | Coordinates: X=%.2f Y=%.2f Z=%.2f"
                        + " | Dimension: %s | Health: %.1f/%.1f | Hunger: %d/20 | Holding: %s | Baritone: %s",
                bot.state(), player.getX(), player.getY(), player.getZ(),
                client.level.dimension().identifier(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), item, movement.status()) + " | Chest: " + chest.status() + " | Local: " + localTasks.status() + " | Smelting: "+furnace.status()+" | Block placement: " + placement.status()+" | "+survival.status();
    }

	@Override
	public void onInitializeClient() {
        try {I18n.initialize(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot"));}
        catch(java.io.IOException failure) {LOG.error("Could not initialize languages; bundled catalogs remain available",failure);}
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register((message,signed,sender,params,time)->{
            WebConsole browser=web;if(browser!=null) browser.publish(message.getString(),WebConsole.Channel.CHAT);
        });
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message,overlay)->{
            WebConsole browser=web;if(browser!=null) browser.publish(message.getString(),overlay?WebConsole.Channel.ACTIVITY:WebConsole.Channel.CHAT);
        });
        survival.initialize(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/survival.json"));
        try { TaskPresets.initialize(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/local-tasks.json")); }
        catch(java.io.IOException failure) {LOG.error("Could not initialize local task definitions",failure);}
        try {
            chestMemory.initialize(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/chest-memory.json"));
        } catch (java.io.IOException failure) { LOG.error("Could not initialize chest memory", failure); }
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (player == client.player && world == client.level) chestMemory.interaction(client, hit.getBlockPos());
            return net.minecraft.world.InteractionResult.PASS;
        });
        try {
            ai = new AiController(FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot"), this::notifyAiConsole);
            agent = new AgentLoop((request, answer, failure) -> ai.requestStep(request,
                    proposal -> Minecraft.getInstance().execute(() -> {
                        if (!agentValid(Minecraft.getInstance())) agent.cancel("Task stopped because game state changed.");
                        answer.accept(proposal);
                    }), error -> Minecraft.getInstance().execute(() -> failure.accept(error))),
                    commands::execute, this::stopAgentGame, this::notifyAiConsole, () -> System.nanoTime()/1_000_000);
        } catch (java.io.IOException failure) {
            LOG.error("Could not initialize AI module", failure);
        }
        ClientTickEvents.START_CLIENT_TICK.register(survival::tick);
        ClientTickEvents.START_CLIENT_TICK.register(movement::tick);
        ClientTickEvents.START_CLIENT_TICK.register(chest::tick);
        ClientTickEvents.START_CLIENT_TICK.register(placement::tick);
        ClientTickEvents.START_CLIENT_TICK.register(localTasks::tick);
        ClientTickEvents.START_CLIENT_TICK.register(furnace::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> chestMemory.tick(client, chest.busy()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickQueuedWorkflow(client);
            localTasks.autoTick(client,idleForEquipment() && !survival.busy());
            if (agent != null && agent.active() && ++agentTicks % 20 == 0) agent.tick(agentValid(client), movement.busy() || chest.busy() || localTasks.busy() || furnace.busy() || placement.busy(), this::observation);
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Path gameDirectory = FabricLoader.getInstance().getGameDir();
            try {
                Logging.initialize(gameDirectory.resolve("logs/minecraft-ai-bot"));
                java.util.function.Function<String,String> dispatch=input -> {
                    CompletableFuture<String> result = new CompletableFuture<>();
                    client.execute(() -> {
                        if(result.isCancelled()) return;
                        try {
                            String response = input.equals("@web snapshot")?webSnapshot():consoleCommand(input);
                            result.complete(response);
                        } catch (RuntimeException failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                    try {
                        return result.get(10, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        result.cancel(false);
                        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                        throw new IllegalStateException("Client command could not complete", failure);
                    }
                };
                console = new ConsoleServer(gameDirectory.resolve("bot-console.properties"),dispatch,message->{WebConsole browser=web;if(browser!=null) browser.publish(message,WebConsole.Channel.RESPONSE);});
                try {
                    try {web=new WebConsole(gameDirectory.resolve("bot-web-url.txt"),8765,dispatch);}
                    catch(java.net.BindException occupied) {web=new WebConsole(gameDirectory.resolve("bot-web-url.txt"),0,dispatch);}
                    LOG.info("Bot web console: {}",web.url());
                    notifyConsole("Web console local: "+web.url());
                } catch(Exception|LinkageError failure) {LOG.error("Could not start local web console; terminal remains available",failure);}
            } catch (Exception failure) {
                LOG.error("Could not start bot console", failure);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {if(web!=null) web.close();} catch(Exception failure) {LOG.warn("Could not close bot web console",failure);}
            try {
                if (console != null) console.close();
            } catch (Exception failure) {
                LOG.warn("Could not close bot console", failure);
            } finally {
                survival.cancel(client);
                if (agent != null) agent.cancel("Game closed; AI task canceled.");
                localTasks.cancel("Game closed; local task canceled.");
                furnace.cancel(client,"Game closed; smelting batch canceled.");
                placement.cancel("Game closed; pending table placement canceled.");
                if (ai != null) ai.close();
                chestMemory.close();
                movement.stop();
                bot.stop();
                Logging.close();
            }
        });
	}
}
