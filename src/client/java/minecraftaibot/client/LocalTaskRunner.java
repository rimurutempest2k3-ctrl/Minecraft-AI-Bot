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
import java.util.*;
import java.util.function.*;

/** JSON local task executor, using vanilla crafting and Baritone only. */
final class LocalTaskRunner {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("minecraft-ai-bot");
    private enum Phase { PLAN, WOOD, APPROACH, OPEN, PLACE, CRAFT, GOAL }
    private final BotCore bot;
    private final BaritoneController movement;
    private final Consumer<String> notify;
    private final ProductionController crafting=new ProductionController();
    private final ProductionController.TablePlacement placement;
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
    private String status="Chưa có nhiệm vụ local.";
    private static final List<String> WOODS=List.of("oak","birch","spruce","jungle","acacia","dark_oak","mangrove","cherry","pale_oak");
    LocalTaskRunner(BotCore bot,BaritoneController movement,ProductionController.TablePlacement placement,Consumer<String> notify) { this.bot=bot;this.movement=movement;this.placement=placement;this.notify=notify; }
    boolean busy() { return active; }
    String status() { return status; }
    String start(String taskName,int quantity) {
        Minecraft client=Minecraft.getInstance();
        if(active || movement.busy()) return "Đang có tác vụ. Dùng bot stop trước.";
        if(client.player==null || client.level==null || !client.player.isAlive() || bot.state()!=BotState.RUNNING) return "Hãy vào thế giới và nhập bot start trước.";
        if(client.player.containerMenu!=client.player.inventoryMenu || client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused()) return "Đóng menu và dùng F3+P để game tiếp tục chạy khi chuyển console.";
        if(!client.player.containerMenu.getCarried().isEmpty()) return "Hãy cất vật phẩm ở con trỏ trước.";
        try {
            catalog=TaskPresets.catalog();definition=catalog.require(taskName);preparation=definition.preparation()==null?null:catalog.preparations().get(definition.preparation());
            requiredItem(definition.resultItem());
            String block=definition.goal().equals("minecraft:cobblestone")?"minecraft:stone":definition.goal();
            if(BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(block)).filter(b->!b.defaultBlockState().isAir()).isEmpty())
                throw new IllegalArgumentException("Block JSON không tồn tại: "+definition.goal());
            for(var recipe:catalog.recipes().values()) {
                if(!recipe.output().equals("$log_planks")) requiredItem(recipe.output());
                for(var cell:recipe.cells()) if(!cell.ingredient().startsWith("$") && !cell.ingredient().startsWith("#")) requiredItem(cell.ingredient());
            }
        }
        catch(IllegalArgumentException invalid) {return invalid.getMessage();}
        if(quantity<1 || quantity>2304) return "Số lượng phải là 1-2304.";
        owner=client.player;world=client.level;this.quantity=quantity;initialResult=resultCount();
        oldSelected=owner.getInventory().getSelectedSlot();started=System.nanoTime();active=true;batches=0;clock=0;
        change(Phase.PLAN,"Local JSON: "+definition.label()+"; thu thêm "+quantity+" "+definition.resultItem()+". Không gọi API.");return status;
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
    void tick(Minecraft client) {
        if(!active) return;
        if(client.player!=owner || client.level!=world || !owner.isAlive() || bot.state()!=BotState.RUNNING) { cancel("Local dừng: đổi thế giới/nhân vật, chết hoặc trạng thái bot thay đổi.");return; }
        if(System.nanoTime()-started>900_000_000_000L || System.nanoTime()-phaseStarted>180_000_000_000L) { cancel("Local dừng: quá thời gian chờ bước hiện tại.");return; }
        if(client.isPaused() || client.gui.overlay()!=null) return;
        try {
            if(phase==Phase.CRAFT) {
                crafting.tick(client);
                if(!crafting.busy()) {
                    if(crafting.failure()!=null) { cancel("Chế tạo dừng: "+crafting.failure());return; }
                    if(count(craftedItem)<=craftedBefore) { cancel("Chưa thấy sản phẩm trong túi đồ; đã dừng.");return; }
                    change(Phase.PLAN,"Đã chế tạo: "+BuiltInRegistries.ITEM.getKey(craftedItem)+". Kiểm tra bước tiếp.");
                }
                return;
            }
            if(phase==Phase.GOAL) {
                int collected=Math.max(0,resultCount()-initialResult);
                if(collected>=quantity) { finish("Local hoàn thành: đã thu thêm "+collected+"/"+quantity+" "+definition.resultItem()+".");return; }
                if(preparation!=null && pickaxe()<0) { movement.stop();change(Phase.PLAN,"Cúp đã hết độ bền; chuẩn bị cúp mới cho phần còn thiếu.");return; }
                if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) {
                    cancel("Baritone dừng khi chưa đủ "+definition.resultItem()+": "+collected+"/"+quantity+". "+movement.status());
                }
                return;
            }
            if(phase==Phase.WOOD) {
                if(count(s->logType(s)!=null)-initialWood>=requestedWood) { movement.stop();change(Phase.PLAN,"Đã đủ gỗ cần cho công cụ."); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) cancel("Chưa thu đủ gỗ. "+movement.status());
                return;
            }
            if(phase==Phase.PLACE) {
                if(placement.busy()) return;
                placedAt=placement.target();
                if(placedAt!=null && world.getBlockState(placedAt).is(Blocks.CRAFTING_TABLE)) { owner.getInventory().setSelectedSlot(oldSelected);table=placedAt;change(Phase.PLAN,"Đã đặt bàn chế tạo."); }
                else if(!placement.busy()) cancel(placement.status());
                return;
            }
            if(phase==Phase.OPEN) {
                if(owner.containerMenu instanceof CraftingMenu) change(Phase.PLAN,"Đã mở bàn chế tạo.");
                else if(System.nanoTime()-phaseStarted>5_000_000_000L) cancel("Không mở được bàn chế tạo; kiểm tra quyền server.");
                return;
            }
            if(phase==Phase.APPROACH) {
                if(!world.getBlockState(table).is(Blocks.CRAFTING_TABLE)) { cancel("Bàn chế tạo mục tiêu đã biến mất.");return; }
                BlockHitResult hit=reachable(table);
                if(hit!=null) { movement.stop();openTable(client,hit); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) cancel("Đường tới bàn chế tạo đã dừng nhưng chưa chạm tới bàn.");
                return;
            }
            if(++clock%10!=0) return;
            if(!(owner.containerMenu instanceof InventoryMenu) && !(owner.containerMenu instanceof CraftingMenu)) { cancel("Menu khác đang mở; đã dừng nhiệm vụ local.");return; }
            if(!owner.containerMenu.getCarried().isEmpty()) { cancel("Có vật phẩm trên con trỏ; hãy cất trước.");return; }
            plan(client);
        } catch(Exception failure) { cancel("Local dừng: "+(failure instanceof IllegalArgumentException ? failure.getMessage():"Không thao tác được trạng thái game.")); }
    }
    private void plan(Minecraft client) {
        if(resultCount()-initialResult>=quantity) { finish("Đã đủ "+definition.resultItem()+" yêu cầu.");return; }
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
                if(preparation!=null && !select(client,pickaxe())) throw new IllegalArgumentException("Không chuyển được cúp lên thanh nhanh.");
                String response=movement.execute("mine "+definition.goal()+" "+(quantity-Math.max(0,resultCount()-initialResult)));
                if(!movement.busy()) throw new IllegalArgumentException(response);
                change(Phase.GOAL,"Local JSON: "+response);
            }
            case "collect_logs" -> {
                closeForMovement();requestedWood=preparation.evaluate(observed).get("logs_needed");initialWood=observed.get("logs");
                String wood=nearestWood();String response=movement.execute("mine minecraft:"+wood+"_log "+requestedWood);
                if(!movement.busy()) throw new IllegalArgumentException(response);
                change(Phase.WOOD,"Local JSON: thu thêm "+requestedWood+" gỗ "+wood+" để chế công cụ.");
            }
            case "craft" -> craftRecipe(client,catalog.recipes().get(step.recipe()));
            case "place_table" -> placeTable(client);
            case "open_table" -> {
                if(table==null) throw new IllegalArgumentException("Không thấy bàn chế tạo để mở.");
                closeForMovement();BlockHitResult hit=reachable(table);
                if(hit!=null) openTable(client,hit);
                else {movement.approachBlock(table,"bàn chế tạo");change(Phase.APPROACH,"Local: đi tới bàn tại "+table.toShortString());}
            }
            default -> throw new IllegalArgumentException("Hành động JSON chưa được hỗ trợ.");
        }
    }
    private void craftRecipe(Minecraft client,TaskPresets.Recipe recipe) {
        int width=((AbstractCraftingMenu)owner.containerMenu).getGridWidth();
        if(width<recipe.width()) throw new IllegalArgumentException("Công thức JSON cần bàn chế tạo 3x3; hãy thêm bước mở bàn trước.");
        ItemStack log=recipe.output().equals("$log_planks") || recipe.cells().stream().anyMatch(c->c.ingredient().equals("$log"))
                ?inventory().stream().filter(s->logType(s)!=null).findFirst().orElseThrow(()->new IllegalArgumentException("Thiếu gỗ cho công thức.")):null;
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
                .filter(i->i!=Items.AIR).orElseThrow(()->new IllegalArgumentException("Vật phẩm JSON không tồn tại: "+id));
    }
    private void craft(Minecraft client,Map<Integer,Predicate<ItemStack>> recipe,ItemStack output) {
        if(++batches>200) throw new IllegalArgumentException("Quá số bước chế tạo của nhiệm vụ.");
        craftedItem=output.getItem();craftedBefore=count(craftedItem);movement.stop();crafting.start(client,recipe,output);
        change(Phase.CRAFT,"Local: chế "+BuiltInRegistries.ITEM.getKey(craftedItem)+" x"+output.getCount());
    }
    private void closeForMovement() {
        if(owner.containerMenu instanceof AbstractCraftingMenu menu && (menu.getInputGridSlots().stream().anyMatch(s->!s.getItem().isEmpty()) || !menu.getCarried().isEmpty()))
            throw new IllegalArgumentException("Lưới chế tạo/con trỏ chưa trống; hãy cất nguyên liệu trước.");
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
        if(owner.isSecondaryUseActive()) throw new IllegalArgumentException("Hãy thả phím cúi người để mở bàn chế tạo.");
        // Vanilla TryEmptyHandInteraction invokes useWithoutItem only for MAIN_HAND.
        // Prefer an empty hotbar slot; the vanilla table also opens with an occupied main hand.
        for(int i=0;i<9;i++) if(inventory().get(i).isEmpty()) {owner.getInventory().setSelectedSlot(i);break;}
        var result=client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,hit);
        LOG.info("Open crafting table at {} with MAIN_HAND: {}",hit.getBlockPos().toShortString(),result);
        change(Phase.OPEN,"Local: đang mở bàn chế tạo bằng tay chính.");
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
        if(!active) return;active=false;movement.stop();placement.cancel("Đã hủy đặt bàn của nhiệm vụ local.");crafting.cancel(Minecraft.getInstance());
        if(owner!=null && Minecraft.getInstance().player==owner) owner.getInventory().setSelectedSlot(oldSelected);
        status=reason+" Nếu còn nguyên liệu ở lưới chế tạo/con trỏ, hãy cất lại; không tự thả đồ.";LOG.warn("{}",status);notify.accept(status);
    }
    private void finish(String message) { active=false;movement.stop();owner.getInventory().setSelectedSlot(oldSelected);status=message;LOG.info("{}",status);notify.accept(status); }
}
