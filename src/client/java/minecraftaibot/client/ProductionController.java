package minecraftaibot.client;

import dev.minecraftaibot.common.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Validated vanilla PICKUP clicks; the server supplies and consumes the recipe result. */
final class ProductionController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("minecraft-ai-bot");
    private AbstractCraftingMenu menu;
    private List<ChestTransferPlan.Step> steps;
    private List<ChestTransferPlan.Stack> expected;
    private ChestTransferPlan.Stack cursor=ChestTransferPlan.Stack.empty();
    private final List<ItemStack> kinds=new ArrayList<>();
    private ItemStack result;
    private int index, ticks, settle, resultIndex;
    private String failure;
    boolean busy() { return steps!=null; }
    String failure() { return failure; }
    void start(Minecraft client, Map<Integer,Predicate<ItemStack>> recipe, ItemStack product) {
        failure=null;
        if (!(client.player.containerMenu instanceof AbstractCraftingMenu current)) throw new IllegalArgumentException("Requires a 2x2 crafting menu or a 3x3 table.");
        if(!current.getCarried().isEmpty()) throw new IllegalArgumentException("Store the cursor item first.");
        menu=current; kinds.clear(); result=product.copy();
        List<Integer> inventory=new ArrayList<>(),grid=new ArrayList<>();
        for(int i=0;i<menu.slots.size();i++) {
            Slot slot=menu.getSlot(i);
            if(slot.container==client.player.getInventory() && slot.getContainerSlot()<36) inventory.add(i);
        }
        for(Slot slot:menu.getInputGridSlots()) grid.add(menu.slots.indexOf(slot));
        resultIndex=menu.slots.indexOf(menu.getResultSlot());
        List<ChestTransferPlan.Stack> initial=capture();
        var output=stack(product);
        Map<Integer,Set<String>> ingredients=new LinkedHashMap<>();
        for(var entry:recipe.entrySet()) {
            if(entry.getKey()<0 || entry.getKey()>=grid.size()) throw new IllegalArgumentException("Recipe requires a 3x3 crafting table.");
            Set<String> allowed=new HashSet<>();
            for(int source:inventory) if(entry.getValue().test(menu.getSlot(source).getItem())) allowed.add(stack(menu.getSlot(source).getItem()).kind());
            ingredients.put(grid.get(entry.getKey()),allowed);
        }
        steps=CraftingPlan.create(initial,inventory,grid,ingredients,resultIndex,output);
        expected=initial; cursor=ChestTransferPlan.Stack.empty(); index=0; ticks=0;settle=0;
    }
    private ChestTransferPlan.Stack stack(ItemStack value) {
        if(value.isEmpty()) return ChestTransferPlan.Stack.empty();
        int kind=0; while(kind<kinds.size() && !ItemStack.isSameItemSameComponents(kinds.get(kind),value)) kind++;
        if(kind==kinds.size()) kinds.add(value.copyWithCount(1));
        return new ChestTransferPlan.Stack(Integer.toString(kind),value.getCount(),value.getMaxStackSize());
    }
    private List<ChestTransferPlan.Stack> capture() { return menu.slots.stream().map(s->stack(s.getItem())).toList(); }
    private boolean matches() {
        List<ChestTransferPlan.Stack> actual=capture();
        for(int i=0;i<actual.size();i++) if(i!=resultIndex && !actual.get(i).equals(expected.get(i))) return false;
        return stack(menu.getCarried()).equals(cursor);
    }
    void tick(Minecraft client) {
        if(!busy()) return;
        if(client.player.containerMenu!=menu) { fail("Crafting menu changed."); return; }
        ticks++;
        if(!matches()) { if(ticks>40) fail("Ingredients/inventory changed, or server corrected data."); return; }
        if(index==steps.size()) {
            if(++settle>=20) steps=null;
            return;
        }
        if(ticks<4) return;
        var step=steps.get(index);
        if(step.slot()==resultIndex) {
            ItemStack output=menu.getResultSlot().getItem();
            if(!ItemStack.isSameItemSameComponents(output,result) || output.getCount()!=result.getCount()) {
                if(ticks>60) fail("Server did not return the expected recipe output; output not taken.");
                return;
            }
        }
        client.gameMode.handleContainerInput(menu.containerId,step.slot(),step.button(),ContainerInput.PICKUP,client.player);
        expected=step.slots(); cursor=step.cursor();index++;ticks=0;
    }
    private void fail(String reason) { failure=reason;steps=null; }
    void cancel(Minecraft client) {
        steps=null;
        if(menu==null || client.player==null || client.player.containerMenu!=menu || menu.getCarried().isEmpty()) return;
        ItemStack carried=menu.getCarried();
        for(int i=0;i<menu.slots.size();i++) {
            Slot slot=menu.getSlot(i);ItemStack existing=slot.getItem();
            if(slot.container==client.player.getInventory() && slot.getContainerSlot()<36 && slot.mayPlace(carried)
                    && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing,carried) && existing.getCount()+carried.getCount()<=slot.getMaxStackSize(carried))) {
                client.gameMode.handleContainerInput(menu.containerId,i,0,ContainerInput.PICKUP,client.player);return;
            }
        }
    }

    /** Nearby placement, jump fallback and short bounded relocations through Baritone. */
    static final class BlockPlacement {
        private Block block=Blocks.CRAFTING_TABLE;
        private enum Phase { JUMP, CONFIRM, LAND, MOVE, SETTLE }
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("minecraft-ai-bot");
        private final Consumer<String> notify;
        private final BaritoneController movement;
        private LocalPlayer owner;
        private ClientLevel world;
        private BlockPos target,destination;
        private int selected,stableTicks,settled;
        private long deadline,overallDeadline,phaseStarted;
        private JumpPlacementGate jump;
        private boolean holdingJump,automatic;
        private Phase phase;
        private PlacementRecovery recovery;
        private final Set<BlockPos> attempted=new LinkedHashSet<>();
        private String status="No production block placed.";
        BlockPlacement(BaritoneController movement,Consumer<String> notify) {this.movement=movement;this.notify=notify;}
        boolean busy() {return deadline!=0;}
        BlockPos target() {return target;}
        String status() {return status;}
        private String announce(String message) {status=message;LOG.info("{}",message);notify.accept(message);return message;}
        private void begin(Phase next,int seconds) {phase=next;phaseStarted=System.nanoTime();deadline=phaseStarted+seconds*1_000_000_000L;settled=0;}
        String start(Minecraft client,BlockPos requested) { return start(client,requested,Blocks.CRAFTING_TABLE); }
        String start(Minecraft client,BlockPos requested,Block requestedBlock) {
            if(busy()) throw new IllegalArgumentException("Waiting for production block placement.");
            if(client.player==null || client.level==null || client.gameMode==null || !client.player.isAlive()) throw new IllegalArgumentException("Enter the world first.");
            if(client.player.containerMenu!=client.player.inventoryMenu || client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused()) throw new IllegalArgumentException("Close menus and use F3+P before placing blocks.");
            if(!client.player.inventoryMenu.getCarried().isEmpty()) throw new IllegalArgumentException("Store the cursor item first.");
            if(!Set.of(Blocks.CRAFTING_TABLE,Blocks.FURNACE,Blocks.BLAST_FURNACE,Blocks.SMOKER).contains(requestedBlock)) throw new IllegalArgumentException("Unsupported production block.");
            block=requestedBlock;owner=client.player;world=client.level;target=null;destination=null;jump=null;automatic=requested==null;
            selected=owner.getInventory().getSelectedSlot();recovery=new PlacementRecovery();attempted.clear();overallDeadline=System.nanoTime()+60_000_000_000L;
            if(requested!=null && (Math.abs((long)requested.getX())>29999984 || Math.abs((long)requested.getZ())>29999984 || requested.getY()<world.getMinY() || requested.getY()>world.getMaxY())) throw new IllegalArgumentException("Coordinates outside world bounds.");
            if(requested!=null && world.hasChunkAt(requested) && world.getBlockState(requested).is(block)) {target=requested;return status="Production block already exists at "+requested.toShortString()+"; no additional placement.";}
            if(blockSlot()<0) throw new IllegalArgumentException("No production block in inventory. Craft it first.");
            begin(Phase.CONFIRM,5);
            try {
                if(automatic) attempt(client);
                else {
                    BlockHitResult hit=placementHit(requested);
                    if(hit==null) throw new IllegalArgumentException("Specified cell must be empty above solid support, visible and reachable.");
                    target=requested.immutable();selectBlock(client);place(client,hit);
                }
                return status;
            } catch(RuntimeException failure) {cancel("Could not start block placement.");throw failure;}
        }
        private int blockSlot() {
            var items=owner.getInventory().getNonEquipmentItems();for(int i=0;i<items.size();i++) if(items.get(i).is(block.asItem())) return i;return -1;
        }
        private void selectBlock(Minecraft client) {
            int source=blockSlot();if(source<0) throw new IllegalArgumentException("No production blocks left in inventory; not placing more.");
            if(source<9) owner.getInventory().setSelectedSlot(source);
            else {
                int slot=owner.getInventory().getSelectedSlot();ItemStack expected=owner.getInventory().getNonEquipmentItems().get(source).copy();
                client.gameMode.handleContainerInput(owner.inventoryMenu.containerId,source,slot,ContainerInput.SWAP,owner);
                if(!ItemStack.isSameItemSameComponents(owner.getMainHandItem(),expected) || owner.getMainHandItem().getCount()!=expected.getCount()) throw new IllegalArgumentException("Block has not moved to hotbar; check inventory.");
            }
        }
        private void attempt(Minecraft client) {
            target=null;stableTicks=0;BlockHitResult hit=null;BlockPos origin=owner.blockPosition();double best=Double.MAX_VALUE;
            for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-2,-1,-2),origin.offset(2,0,2))) {
                if(attempted.contains(pos)) continue;
                BlockHitResult candidate=placementHit(pos);double distance=owner.position().distanceToSqr(Vec3.atCenterOf(pos));
                if(candidate!=null && distance<best) {target=pos.immutable();hit=candidate;best=distance;}
            }
            if(target!=null) {selectBlock(client);place(client,hit);return;}
            if(!attempted.contains(origin) && canJumpPlace(origin)) {
                target=origin.immutable();selectBlock(client);jump=new JumpPlacementGate();begin(Phase.JUMP,5);
                holdingJump=true;client.options.keyJump.setDown(true);announce("No nearby placement spot; jumping to place a block underfoot at "+target.toShortString()+".");
            } else recover("No nearby placement cell and cannot jump-place underfoot yet.");
        }
        private void place(Minecraft client,BlockHitResult hit) {
            attempted.add(target.immutable());jump=null;releaseJump();stableTicks=0;begin(Phase.CONFIRM,5);
            client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,hit);announce("Placing production block at "+target.toShortString()+"; waiting for game state.");
        }
        private boolean canJumpPlace(BlockPos pos) {
            BlockPos support=pos.below();var state=world.getBlockState(support);
            return owner.onGround() && owner.getDeltaMovement().horizontalDistanceSqr()<0.0004 && world.getBlockState(pos).isAir()
                    && state.isCollisionShapeFullBlock(world,support) && !(state.getBlock() instanceof EntityBlock)
                    && Math.abs(owner.getBoundingBox().minY-pos.getY())<0.02 && world.noCollision(owner,owner.getBoundingBox().expandTowards(0,1.3,0));
        }
        private void releaseJump() {if(holdingJump) {Minecraft.getInstance().options.keyJump.setDown(false);holdingJump=false;}}
        private BlockHitResult placementHit(BlockPos pos) {
            if(!world.hasChunkAt(pos) || !world.getBlockState(pos).isAir() || owner.getBoundingBox().intersects(new AABB(pos))) return null;
            BlockPos support=pos.below();var state=world.getBlockState(support);
            if(!state.isCollisionShapeFullBlock(world,support) || state.getBlock() instanceof EntityBlock) return null;
            Vec3 inside=new Vec3(support.getX()+0.5,support.getY()+0.999,support.getZ()+0.5);
            BlockHitResult ray=world.clip(new ClipContext(owner.getEyePosition(),inside,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
            return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(support) && ray.getDirection()==Direction.UP
                    && owner.getEyePosition().distanceToSqr(ray.getLocation())<=Math.pow(owner.blockInteractionRange(),2)?ray:null;
        }
        private void recover(String reason) {
            if(target!=null) attempted.add(target.immutable());jump=null;releaseJump();
            if(!automatic || recovery.moves()>=2) {cancel(reason+" Stopped after reaching the relocation attempt limit.");return;}
            if(blockSlot()<0) {cancel(reason+" No blocks left in inventory; stopped.");return;}
            begin(Phase.LAND,3);announce(reason+" Wait until grounded, then find a spot 1–2 blocks away to retry.");
        }
        private boolean safeStanding(PlacementRecovery.Cell cell) {
            BlockPos pos=new BlockPos(cell.x(),cell.y(),cell.z());
            if(!world.hasChunkAt(pos) || !world.getBlockState(pos).isAir() || !world.getBlockState(pos.above()).isAir()) return false;
            var floor=world.getBlockState(pos.below());
            if(!floor.isCollisionShapeFullBlock(world,pos.below()) || floor.getBlock() instanceof EntityBlock || floor.is(Blocks.MAGMA_BLOCK)) return false;
            Vec3 delta=new Vec3(pos.getX()+0.5-owner.getX(),0,pos.getZ()+0.5-owner.getZ());
            return world.noCollision(owner,owner.getBoundingBox().expandTowards(delta));
        }
        private void relocate(Minecraft client) {
            BlockPos origin=owner.blockPosition();
            var chosen=recovery.choose(new PlacementRecovery.Cell(origin.getX(),origin.getY(),origin.getZ()),this::safeStanding);
            if(chosen.isEmpty()) {cancel("No suitable position 1–2 blocks away to retry placement.");return;}
            var cell=chosen.get();destination=new BlockPos(cell.x(),cell.y(),cell.z());movement.execute("goto "+cell.x()+" "+cell.y()+" "+cell.z());
            if(!movement.busy()) {cancel("Could not start approaching the placement position.");return;}
            begin(Phase.MOVE,15);announce("Block placement relocation attempt "+recovery.moves()+"/2: Baritone approaching "+destination.toShortString()+" (1–2 blocks away).");
        }
        void tick(Minecraft client) {
            if(!busy()) return;
            if(client.player!=owner || client.level!=world || !owner.isAlive()) {cancel("Block placement canceled because game state changed.");return;}
            if(client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused() || owner.containerMenu!=owner.inventoryMenu) {cancel("Block placement canceled because menu/pause state changed.");return;}
            long now=System.nanoTime();if(now>overallDeadline) {cancel("Block placement exceeded 60 seconds; stopped.");return;}
            // Earlier server confirmation takes priority over another placement at the new position.
            for(BlockPos pos:attempted) if(world.getBlockState(pos).is(block)) {
                if(!pos.equals(target)) {target=pos;stableTicks=0;}
                if(phase!=Phase.CONFIRM) {if(phase==Phase.MOVE) movement.stop();releaseJump();jump=null;begin(Phase.CONFIRM,5);}
                if(++stableTicks>=20) cancel("Production block placed at "+target.toShortString()+" (client state).");return;
            }
            stableTicks=0;
            try {
                switch(phase) {
                    case LAND,SETTLE -> {
                        if(owner.onGround() && owner.getDeltaMovement().horizontalDistanceSqr()<0.0004) {
                            if(++settled>=3) {if(phase==Phase.SETTLE) attempt(client);else relocate(client);}
                        } else settled=0;
                        if(busy() && (phase==Phase.LAND || phase==Phase.SETTLE) && now>deadline) cancel("Not grounded enough to relocate/place a block.");
                    }
                    case MOVE -> {
                        if(owner.blockPosition().equals(destination) && owner.onGround()) {movement.stop();begin(Phase.SETTLE,3);announce("Reached the new position; checking placement again.");}
                        else if(now>deadline || now-phaseStarted>2_000_000_000L && !movement.busy()) {movement.stop();recover("Cannot reach the new position.");}
                    }
                    case CONFIRM -> {if(now>deadline) recover("Placed block not yet visible at the attempted position.");}
                    case JUMP -> {
                        if(!owner.onGround()) releaseJump();BlockPos column=owner.blockPosition();
                        var decision=jump.tick(column.getX()==target.getX() && column.getZ()==target.getZ(),world.getBlockState(target).isAir(),owner.getBoundingBox().minY,target.getY(),owner.onGround());
                        if(decision==JumpPlacementGate.Decision.ABORT || now>deadline) {recover("Underfoot jump-placement not successful yet.");return;}
                        if(decision==JumpPlacementGate.Decision.PLACE) {
                            releaseJump();BlockHitResult hit=placementHit(target);
                            if(hit==null || !owner.getMainHandItem().is(block.asItem())) {recover("Could not place a block underfoot yet.");return;}
                            place(client,hit);
                        }
                    }
                }
            } catch(RuntimeException failure) {cancel("Cannot continue production block placement/relocation.");}
        }
        void cancel(String message) {
            if(!busy()) return;if(phase==Phase.MOVE) movement.stop();deadline=0;jump=null;releaseJump();
            if(Minecraft.getInstance().player==owner) owner.getInventory().setSelectedSlot(selected);announce(message);
        }
    }

    /** One bounded batch. Vanilla clicks only; keeps the menu open to observe every result. */
    static final class FurnaceRun {
        private enum Phase { PLACE, APPROACH, OPEN, INPUT, FUEL, COOK, TAKE }
        private final BotCore bot;
        private final BaritoneController movement;
        private final BlockPlacement placement;
        private final Consumer<String> notify;
        private LocalPlayer owner;
        private ClientLevel world;
        private AbstractFurnaceMenu menu;
        private Phase phase;
        private boolean active;
        private String status="No smelting batch.", machine;
        private ItemStack input, fuel, output;
        private Block block;
        private BlockPos target;
        private ProductionPlan.Smelt plan;
        private long deadline, phaseDeadline, mismatchSince, lastProgress;
        private int lastCooked;
        private int initialOutput, selected, clock, stepIndex, delay;
        private List<ChestTransferPlan.Step> steps;
        private List<ChestTransferPlan.Stack> expected;
        private ChestTransferPlan.Stack expectedCursor=ChestTransferPlan.Stack.empty();
        private final List<ItemStack> kinds=new ArrayList<>();

        FurnaceRun(BotCore bot,BaritoneController movement,BlockPlacement placement,Consumer<String> notify) {
            this.bot=bot;this.movement=movement;this.placement=placement;this.notify=notify;
        }
        boolean busy() { return active; }
        String status() { return status; }
        boolean nearby(Minecraft client,String type) {
            if(client.player==null || client.level==null) return false;
            var chunkSource=client.level.getChunkSource();var origin=client.player.blockPosition();
            var wanted=type.equals("smoker")?Blocks.SMOKER:Blocks.FURNACE;
            for(int x=(origin.getX()-32)>>4;x<=(origin.getX()+32)>>4;x++) for(int z=(origin.getZ()-32)>>4;z<=(origin.getZ()+32)>>4;z++) {
                var chunk=chunkSource.getChunk(x,z,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);if(chunk==null)continue;
                for(var pos:chunk.getBlockEntities().keySet()) if(client.player.position().distanceToSqr(Vec3.atCenterOf(pos))<=32*32 && client.level.getBlockState(pos).is(wanted)) return true;
            }
            return false;
        }
        private void announce(String text) { status=text;LOG.info("{}",text);notify.accept(text); }
        private int count(ItemStack item) {
            return owner.getInventory().getNonEquipmentItems().stream()
                    .filter(s->ItemStack.isSameItemSameComponents(s,item)).mapToInt(ItemStack::getCount).sum();
        }
        private void phase(Phase next,int seconds) {
            phase=next;phaseDeadline=System.nanoTime()+seconds*1_000_000_000L;clock=0;mismatchSince=0;
        }
        String start(Minecraft client,String type,String itemId,int quantity,String fuelId) {
            if(active || movement.busy() || placement.busy()) return "Task active; use bot stop first.";
            if(client.player==null || client.level==null || client.gameMode==null || !client.player.isAlive()
                    || bot.state()!=BotState.RUNNING) return "Enter the world and type bot start first.";
            if(client.isPaused() || client.gui.overlay()!=null || client.gui.screen()!=null
                    || client.player.containerMenu!=client.player.inventoryMenu) return "Close menus; use F3+P so the game keeps running when switching to the console.";
            if(!client.player.containerMenu.getCarried().isEmpty()) return "Store the cursor item before smelting.";
            if(quantity<1 || quantity>64) return "Each batch supports 1-64 ingredients.";
            owner=client.player;world=client.level;menu=null;steps=null;kinds.clear();
            try {
                machine=type;
                block=switch(type) {case "furnace" -> Blocks.FURNACE;case "blast_furnace" -> Blocks.BLAST_FURNACE;case "smoker" -> Blocks.SMOKER;default -> throw new IllegalArgumentException("Invalid furnace type.");};
                input=FurnaceLogic.item(FurnaceLogic.id(itemId));
                if(!FurnaceLogic.allowsInsertion(client,type,0,input)) return "Game rejects this ingredient in "+type+".";
                var root=FurnaceLogic.load();
                var matches=root.getAsJsonObject("acceptedInputs").getAsJsonObject(type).getAsJsonArray(FurnaceLogic.id(itemId));
                if(matches==null || matches.size()!=1) return "Could not identify a unique smelting recipe; furnace not loaded.";
                var recipe=root.getAsJsonObject("recipes").getAsJsonObject(matches.get(0).getAsString());
                var settings=root.getAsJsonObject("machines").getAsJsonObject(type);
                var result=recipe.getAsJsonObject("result");
                output=FurnaceLogic.item(result.get("id").getAsString());
                int perInput=result.has("count")?result.get("count").getAsInt():1;
                int cook=recipe.has("cookingtime")?recipe.get("cookingtime").getAsInt():settings.get("defaultCookingTicks").getAsInt();
                if(output.isEmpty()) return "Output in furnace data does not exist.";
                var candidates=fuelId.equals("auto")?List.of("minecraft:coal","minecraft:charcoal","minecraft:oak_planks","minecraft:birch_planks","minecraft:spruce_planks","minecraft:stick"):List.of(FurnaceLogic.id(fuelId));
                String shortage="No fallback fuel (coal/charcoal/planks/stick); specify another fuel if needed.";
                plan=null;
                for(String candidate:candidates) {
                    var selectedFuel=FurnaceLogic.item(candidate);
                    int burn=world.fuelValues().burnDuration(selectedFuel)/settings.get("fuelTickDivisor").getAsInt();
                    if(burn<=0) {shortage=candidate+" is not valid fuel.";continue;}
                    var candidatePlan=new ProductionPlan.Smelt(quantity,perInput,cook,burn);
                    String missing=candidatePlan.shortages(count(input),count(selectedFuel),ItemStack.isSameItemSameComponents(input,selectedFuel));
                    if(!missing.isEmpty()) {shortage=missing+" Requires "+itemId+" x"+quantity+" and "+candidate+" x"+candidatePlan.fuelCount()+".";continue;}
                    if(candidatePlan.fuelCount()>selectedFuel.getMaxStackSize()) {shortage="Fuel exceeds one slot; split the batch or change fuel.";continue;}
                    fuel=selectedFuel;plan=candidatePlan;break;
                }
                if(plan==null) return shortage+" Nothing has been loaded into the furnace.";
                if(plan.outputCount()>output.getMaxStackSize()) return "Output exceeds one stack; split the batch.";
                int capacity=owner.getInventory().getNonEquipmentItems().stream().mapToInt(s->s.isEmpty()?output.getMaxStackSize():ItemStack.isSameItemSameComponents(s,output)?Math.max(0,s.getMaxStackSize()-s.getCount()):0).sum();
                if(capacity<plan.outputCount()) return "Not enough inventory space for output; store items first.";
                target=findFurnace();selected=owner.getInventory().getSelectedSlot();initialOutput=count(output);
                deadline=System.nanoTime()+(plan.timeoutMillis()+180_000L)*1_000_000L;active=true;
                lastProgress=System.nanoTime();lastCooked=0;
                if(target==null) {
                    if(count(new ItemStack(block.asItem()))==0) {active=false;return "Cannot find "+type+" nearby; bring a furnace block for automatic placement.";}
                    phase(Phase.PLACE,65);placement.start(client,null,block);
                    announce("Local smelting: placing "+type+".");
                } else approach(client);
                return status;
            } catch(Exception failure) {
                String reason="Could not start smelting batch: "+(failure instanceof IllegalArgumentException?failure.getMessage():"check furnace rules data.");
                if(active) cancel(client,reason);else status=reason;
                return status;
            }
        }
        private BlockPos findFurnace() {
            BlockPos origin=owner.blockPosition(),best=null;double nearest=Double.MAX_VALUE;
            for(int x=(origin.getX()-32)>>4;x<=(origin.getX()+32)>>4;x++)
                for(int z=(origin.getZ()-32)>>4;z<=(origin.getZ()+32)>>4;z++) {
                    var chunk=world.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
                    if(chunk==null) continue;
                    for(BlockPos pos:chunk.getBlockEntities().keySet()) {
                        double distance=owner.position().distanceToSqr(Vec3.atCenterOf(pos));
                        if(distance<=32*32 && distance<nearest && world.getBlockState(pos).is(block)) {best=pos.immutable();nearest=distance;}
                    }
                }
            return best;
        }
        private BlockHitResult reachable() {
            if(!world.hasChunkAt(target) || !world.getBlockState(target).is(block)) return null;
            var hit=world.clip(new ClipContext(owner.getEyePosition(),Vec3.atCenterOf(target),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
            return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(target)
                    && owner.getEyePosition().distanceToSqr(hit.getLocation())<=Math.pow(owner.blockInteractionRange(),2)?hit:null;
        }
        private void approach(Minecraft client) {
            var hit=reachable();
            if(hit!=null) open(client,hit);
            else {movement.approachBlock(target,"furnace");phase(Phase.APPROACH,120);announce("Approaching "+machine+" at "+target.toShortString());}
        }
        private void open(Minecraft client,BlockHitResult hit) {
            if(owner.isSecondaryUseActive()) throw new IllegalArgumentException("Release the sneak key before opening furnace.");
            movement.stop();
            for(int i=0;i<9;i++) if(owner.getInventory().getNonEquipmentItems().get(i).isEmpty()) {owner.getInventory().setSelectedSlot(i);break;}
            client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,hit);
            phase(Phase.OPEN,8);announce("Opening furnace at "+target.toShortString());
        }
        private boolean correctMenu(AbstractFurnaceMenu value) {
            return switch(machine) {case "furnace" -> value instanceof FurnaceMenu;case "blast_furnace" -> value instanceof BlastFurnaceMenu;case "smoker" -> value instanceof SmokerMenu;default -> false;};
        }
        private ChestTransferPlan.Stack stack(ItemStack value) {
            if(value.isEmpty()) return ChestTransferPlan.Stack.empty();
            int kind=0;while(kind<kinds.size() && !ItemStack.isSameItemSameComponents(kinds.get(kind),value)) kind++;
            if(kind==kinds.size()) kinds.add(value.copyWithCount(1));
            return new ChestTransferPlan.Stack(Integer.toString(kind),value.getCount(),value.getMaxStackSize());
        }
        private List<ChestTransferPlan.Stack> capture() {return menu.slots.stream().map(s->stack(s.getItem())).toList();}
        private void transfer(ItemStack item,int quantity,int from,int end,int to,int toEnd) {
            if(!menu.getCarried().isEmpty()) throw new IllegalArgumentException("Cursor is holding an item.");
            for(int i=to;i<toEnd;i++) if(!menu.getSlot(i).mayPlace(item)) throw new IllegalArgumentException("Destination slot does not accept items.");
            expected=capture();expectedCursor=ChestTransferPlan.Stack.empty();
            steps=ChestTransferPlan.transfer(expected,from,end,to,toEnd,Set.of(stack(item).kind()),quantity,to<3);
            stepIndex=0;delay=0;mismatchSince=0;
        }
        private boolean transferTick(Minecraft client) {
            // Input/output/fuel can change while burning; inventory and cursor must match each click.
            boolean matches=stack(menu.getCarried()).equals(expectedCursor);
            for(int i=3;i<menu.slots.size();i++) matches &= stack(menu.getSlot(i).getItem()).equals(expected.get(i));
            if(phase==Phase.INPUT) matches &= stack(menu.getSlot(0).getItem()).equals(expected.get(0));
            if(!matches) {
                if(mismatchSince==0) mismatchSince=System.nanoTime();
                if(System.nanoTime()-mismatchSince>5_000_000_000L) throw new IllegalArgumentException("Inventory/cursor changed during transfer; stopped.");
                return false;
            }
            mismatchSince=0;
            if(++delay<6) return false;
            if(stepIndex==steps.size()) {steps=null;return true;}
            var step=steps.get(stepIndex++);
            if(step.slot()<3 && step.slot()!=2 && !FurnaceLogic.allowsInsertion(client,machine,step.slot(),menu.getCarried()))
                throw new IllegalArgumentException("Game does not allow insertion into the furnace slot.");
            if(!menu.getSlot(step.slot()).mayPickup(owner)) throw new IllegalArgumentException("Server restricts inventory slot actions.");
            client.gameMode.handleContainerInput(menu.containerId,step.slot(),step.button(),ContainerInput.PICKUP,owner);
            expected=step.slots();expectedCursor=step.cursor();delay=0;return false;
        }
        void tick(Minecraft client) {
            if(!active) return;
            if(client.player!=owner || client.level!=world || !owner.isAlive() || bot.state()!=BotState.RUNNING || client.gameMode==null) {
                cancel(client,"Smelting stopped because world/player/bot state changed.");return;
            }
            if(System.nanoTime()>deadline || System.nanoTime()>phaseDeadline) {cancel(client,"Smelting timed out; inspect furnace and remaining items.");return;}
            if(client.isPaused() || client.gui.overlay()!=null) return;
            try {
                if(phase==Phase.PLACE) {
                    if(placement.busy()) return;
                    target=placement.target();
                    if(target==null || !world.getBlockState(target).is(block)) throw new IllegalArgumentException(placement.status());
                    approach(client);return;
                }
                if(phase==Phase.APPROACH) {
                    if(owner.containerMenu!=owner.inventoryMenu || client.gui.screen()!=null) throw new IllegalArgumentException("Menu changed while approaching furnace.");
                    var hit=reachable();if(hit!=null) open(client,hit);
                    else if(++clock>40 && !movement.busy()) throw new IllegalArgumentException("Cannot reach furnace.");
                    return;
                }
                if(phase==Phase.OPEN) {
                    if(!(owner.containerMenu instanceof AbstractFurnaceMenu opened)) return;
                    if(!correctMenu(opened) || opened.slots.size()!=39) throw new IllegalArgumentException("Incorrect furnace menu type.");
                    for(int i=3;i<39;i++) if(opened.getSlot(i).container!=owner.getInventory()) throw new IllegalArgumentException("Nonstandard inventory menu.");
                    menu=opened;
                    if(++clock<20) return; // Allow initial server contents/data to arrive before checking ownership.
                    if(menu.isLit() || !menu.getCarried().isEmpty() || java.util.stream.IntStream.range(0,3).anyMatch(i->!menu.getSlot(i).getItem().isEmpty()))
                        throw new IllegalArgumentException("Furnace is burning or contains items; choose an empty furnace to avoid mixing batches.");
                    String missing=plan.shortages(count(input),count(fuel),ItemStack.isSameItemSameComponents(input,fuel));
                    if(!missing.isEmpty()) throw new IllegalArgumentException(missing);
                    phase(Phase.INPUT,60);transfer(input,plan.inputCount(),3,39,0,1);
                    announce("Load ingredients x"+plan.inputCount()+" into the furnace; do not interact with inventory while smelting.");return;
                }
                if(owner.containerMenu!=menu || !menu.stillValid(owner)) throw new IllegalArgumentException("Furnace closed/changed or out of interaction range.");
                if(steps!=null) {
                    if(!transferTick(client)) return;
                    if(phase==Phase.INPUT) {phase(Phase.FUEL,60);transfer(fuel,plan.fuelCount(),3,39,1,2);return;}
                    if(phase==Phase.FUEL) {
                        lastProgress=System.nanoTime();
                        announce("Batch fully loaded; smelting "+plan.inputCount()+" ingredients. bot furnace status to inspect; bot stop to cancel.");
                    }
                    phase(Phase.COOK,(int)Math.max(1,(deadline-System.nanoTime())/1_000_000_000L));
                }
                if(phase==Phase.COOK && ++clock%10==0) {
                    ItemStack remaining=menu.getSlot(0).getItem(),product=menu.getSlot(2).getItem();
                    if(!remaining.isEmpty() && !ItemStack.isSameItemSameComponents(remaining,input)
                            || !product.isEmpty() && !ItemStack.isSameItemSameComponents(product,output))
                        throw new IllegalArgumentException("Furnace input/output differs from expected; check server recipes.");
                    int collected=count(output)-initialOutput;
                    if(!plan.conserved(remaining.getCount(),product.getCount(),collected)) {
                        if(mismatchSince==0) mismatchSince=System.nanoTime();
                        if(System.nanoTime()-mismatchSince>5_000_000_000L) throw new IllegalArgumentException("Furnace/inventory counts changed outside the smelting batch.");
                        return;
                    }
                    mismatchSince=0;
                    int cooked=plan.inputCount()-remaining.getCount();
                    if(cooked!=lastCooked) {lastCooked=cooked;lastProgress=System.nanoTime();}
                    if(System.nanoTime()-lastProgress>Math.max(90_000L,plan.cookingTicks()*150L)*1_000_000L)
                        throw new IllegalArgumentException("Furnace not progressing; possibly no fire or server recipe differs from data.");
                    status="Smelting "+machine+" at "+target.toShortString()+": smelted "+cooked+"/"+plan.inputCount()+", received "+collected+"/"+plan.outputCount()+" outputs.";
                    if(collected==plan.outputCount()) {
                        active=false;owner.getInventory().setSelectedSlot(selected);
                        announce("Smelting completed: received "+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output.getItem())+" x"+collected+" into inventory. Furnace at "+target.toShortString()+"; remaining fuel/containers stay in the furnace.");
                        if(menu.getCarried().isEmpty()) owner.closeContainer();return;
                    }
                    // The whole output fits one stack. Wait for input exhaustion so a new result
                    // cannot appear between planning and clicking the output slot.
                    if(remaining.isEmpty() && !product.isEmpty()) {phase(Phase.TAKE,60);transfer(output,product.getCount(),2,3,3,39);}
                }
            } catch(RuntimeException failure) {cancel(client,"Smelting stopped: "+(failure instanceof IllegalArgumentException?failure.getMessage():"Cannot interact with furnace menu."));}
        }
        void cancel(Minecraft client,String reason) {
            if(active) {
                active=false;steps=null;movement.stop();placement.cancel("Furnace placement step canceled.");
                if(client.player==owner && client.level==world) {
                    owner.getInventory().setSelectedSlot(selected);
                    if(menu!=null && owner.containerMenu==menu && !menu.getCarried().isEmpty()) {
                        ItemStack carried=menu.getCarried();
                        for(int i=3;i<menu.slots.size();i++) {
                            Slot slot=menu.getSlot(i);ItemStack existing=slot.getItem();
                            if(slot.mayPlace(carried) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing,carried)
                                    && existing.getCount()+carried.getCount()<=slot.getMaxStackSize(carried))) {
                                client.gameMode.handleContainerInput(menu.containerId,i,0,ContainerInput.PICKUP,owner);break;
                            }
                        }
                    }
                }
                announce(reason+" Loaded items stay in the furnace; the fire may keep burning. Items are not dropped automatically.");
            }
        }
    }

    /** Read-only furnace eligibility and planning; live game rules take priority over vanilla JSON. */
    static final class FurnaceLogic {
        static int cookingTicks(String type,String input) {
            try {
                var root=load();var matches=root.getAsJsonObject("acceptedInputs").getAsJsonObject(type).getAsJsonArray(input);
                if(matches==null || matches.size()!=1) throw new IllegalArgumentException("Cannot determine cooking recipe for "+input);
                var recipe=root.getAsJsonObject("recipes").getAsJsonObject(matches.get(0).getAsString());
                return recipe.has("cookingtime")?recipe.get("cookingtime").getAsInt():root.getAsJsonObject("machines").getAsJsonObject(type).get("defaultCookingTicks").getAsInt();
            } catch(java.io.IOException e) {throw new IllegalArgumentException("Cannot read furnace rules.");}
        }
        private static com.google.gson.JsonObject load() throws java.io.IOException {
            var path=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minecraft-ai-bot/core-data/furnace-rules-26.2.json");
            if(java.nio.file.Files.size(path)>2_000_000) throw new IllegalArgumentException("Furnace rules file is too large.");
            var root=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            if(root.get("schemaVersion").getAsInt()!=1 || !root.get("minecraftVersion").getAsString().equals("26.2")) throw new IllegalArgumentException("Unsupported furnace rules version.");
            for(String machine:List.of("furnace","blast_furnace","smoker")) {
                var settings=root.getAsJsonObject("machines").getAsJsonObject(machine);
                if(settings.get("defaultCookingTicks").getAsInt()<1 || settings.get("fuelTickDivisor").getAsInt()<1) throw new IllegalArgumentException("Invalid cooking duration.");
                for(var entry:root.getAsJsonObject("acceptedInputs").getAsJsonObject(machine).entrySet()) for(var id:entry.getValue().getAsJsonArray()) {
                    var recipe=root.getAsJsonObject("recipes").getAsJsonObject(id.getAsString());
                    if(recipe==null || !recipe.get("type").getAsString().equals(settings.get("recipeType").getAsString())) throw new IllegalArgumentException("Invalid furnace recipe reference.");
                }
            }
            return root;
        }
        private static ItemStack item(String id) {
            var key=net.minecraft.resources.Identifier.tryParse(id);
            return new ItemStack(key==null?Items.AIR:net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR));
        }
        private static String id(String text) {return text.contains(":")?text:"minecraft:"+text;}
        private static boolean liveInput(Minecraft client,String machine,ItemStack stack) {
            var key=switch(machine) {
                case "furnace" -> net.minecraft.world.item.crafting.RecipePropertySet.FURNACE_INPUT;
                case "blast_furnace" -> net.minecraft.world.item.crafting.RecipePropertySet.BLAST_FURNACE_INPUT;
                case "smoker" -> net.minecraft.world.item.crafting.RecipePropertySet.SMOKER_INPUT;
                default -> throw new IllegalArgumentException("Furnace types: furnace, blast_furnace or smoker.");
            };
            return client.level.recipeAccess().propertySet(key).test(stack);
        }
        static boolean allowsInsertion(Minecraft client,String machine,int slot,ItemStack stack) {
            if(client.level==null || stack.isEmpty()) return false;
            if(slot==0) return liveInput(client,machine,stack);
            if(slot==1) return client.level.fuelValues().isFuel(stack) && client.level.fuelValues().burnDuration(stack)>0;
            return false; // The result slot, empty buckets and unknown slots are never bot insertion targets.
        }
        static String query(Minecraft client,String command) {
            String usage="Commands: bot furnace check <machine> <item>; bot furnace plan <machine> <ingredient> <quantity> <fuel>; bot furnace run <furnace|blast_furnace|smoker> <ingredient> <1-64> [fuel or auto]; bot furnace status.";
            if(command.isBlank()) return usage;
            String[] parts=command.split(" ");
            if(!(parts.length==3 && parts[0].equals("check") || parts.length==5 && parts[0].equals("plan"))) return usage;
            try {
                var root=load();String machine=parts[1],inputId=id(parts[2]);
                if(!List.of("furnace","blast_furnace","smoker").contains(machine)) return usage;
                ItemStack input=item(inputId);if(input.isEmpty()) return "Item ID not found: "+inputId;
                var matches=root.getAsJsonObject("acceptedInputs").getAsJsonObject(machine).getAsJsonArray(inputId);
                boolean inputAllowed=client.level!=null?allowsInsertion(client,machine,0,input):matches!=null;
                int burn=client.level!=null?client.level.fuelValues().burnDuration(input):fuelTicks(root,inputId);
                String source=client.level!=null?"Check against running game":"Look up vanilla 26.2 data (not in a world)";
                if(parts[0].equals("check")) {
                    String outputs=matches==null?"no vanilla recipe":matches.toString();
                    return source+" | "+machine+" | "+inputId+" | Input slot: "+(inputAllowed?"allowed":"not allowed")
                            +" | Fuel: "+(burn>0?"yes ("+burn+" ticks in a normal furnace)":"no")+" | Recipe: "+outputs+" | Output slot: no insertion.";
                }
                int quantity=Integer.parseInt(parts[3]);if(quantity<1 || quantity>2304) return "Quantity must be an integer from 1-2304.";
                String fuelId=id(parts[4]);ItemStack fuel=item(fuelId);if(fuel.isEmpty()) return "Fuel not found: "+fuelId;
                if(!inputAllowed) return "Cannot insert "+inputId+" into the input slot of "+machine+"; no matching recipe.";
                int fuelBurn=client.level!=null?client.level.fuelValues().burnDuration(fuel):fuelTicks(root,fuelId);
                if(fuelBurn<=0) return fuelId+" is not fuel; not loading into the fuel slot.";
                if(matches==null || matches.size()!=1) return "Game accepts the ingredient but a unique vanilla recipe could not be identified; output/fuel not estimated.";
                var recipe=root.getAsJsonObject("recipes").getAsJsonObject(matches.get(0).getAsString());
                var settings=root.getAsJsonObject("machines").getAsJsonObject(machine);
                int cook=recipe.has("cookingtime")?recipe.get("cookingtime").getAsInt():settings.get("defaultCookingTicks").getAsInt();
                int duration=fuelBurn/settings.get("fuelTickDivisor").getAsInt();if(cook<1 || duration<1) return "Invalid cooking/burning duration.";
                long needed=((long)quantity*cook+duration-1)/duration;
                var result=recipe.getAsJsonObject("result");int count=result.has("count")?result.get("count").getAsInt():1;
                return "Vanilla plan (not executed): "+inputId+" x"+quantity+" → "+result.get("id").getAsString()+" x"+((long)quantity*count)
                        +" | "+machine+" | Estimated "+fuelId+" x"+needed+" with continuous smelting, excluding remaining fire. Game/server recipes may differ; verify the actual output.";
            } catch(NumberFormatException bad) {return "Quantity must be an integer from 1-2304.";}
            catch(Exception failure) {return "Cannot read/validate furnace-rules-26.2.json; check file structure. Old file preserved.";}
        }
        private static int fuelTicks(com.google.gson.JsonObject root,String id) {
            var fuel=root.getAsJsonObject("fuels").getAsJsonObject(id);return fuel==null?0:Math.max(0,fuel.get("burnTicks").getAsInt());
        }
    }
}
