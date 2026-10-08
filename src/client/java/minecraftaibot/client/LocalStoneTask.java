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

/** Local stone prerequisite task, using vanilla crafting and Baritone only. */
final class LocalStoneTask {
    private enum Phase { PLAN, WOOD, APPROACH, OPEN, PLACE, CRAFT, STONE }
    private final BotCore bot;
    private final BaritoneController movement;
    private final Consumer<String> notify;
    private final CraftingController crafting=new CraftingController();
    private boolean active;
    private LocalPlayer owner;
    private ClientLevel world;
    private Phase phase;
    private int quantity, initialStone, initialWood, requestedWood, craftedBefore, oldSelected, clock, batches;
    private long started, phaseStarted;
    private BlockPos table, placedAt;
    private Item craftedItem;
    private String status="Chưa có nhiệm vụ local.";
    private static final List<String> WOODS=List.of("oak","birch","spruce","jungle","acacia","dark_oak","mangrove","cherry","pale_oak");
    LocalStoneTask(BotCore bot,BaritoneController movement,Consumer<String> notify) { this.bot=bot;this.movement=movement;this.notify=notify; }
    boolean busy() { return active; }
    String status() { return status; }
    String start(int quantity) {
        Minecraft client=Minecraft.getInstance();
        if(active || movement.busy()) return "Đang có tác vụ. Dùng bot stop trước.";
        if(client.player==null || client.level==null || !client.player.isAlive() || bot.state()!=BotState.RUNNING) return "Hãy vào thế giới và nhập bot start trước.";
        if(client.player.containerMenu!=client.player.inventoryMenu || client.gui.screen()!=null || client.gui.overlay()!=null || client.isPaused()) return "Đóng menu và dùng F3+P để game tiếp tục chạy khi chuyển console.";
        if(!client.player.containerMenu.getCarried().isEmpty()) return "Hãy cất vật phẩm ở con trỏ trước.";
        owner=client.player;world=client.level;this.quantity=quantity;initialStone=stoneCount();
        oldSelected=owner.getInventory().getSelectedSlot();started=System.nanoTime();active=true;batches=0;clock=0;
        change(Phase.PLAN,"Local: chuẩn bị cúp để thu thêm " + quantity + " đá cuội/đá. Không gọi API.");return status;
    }
    private void change(Phase value,String message) { phase=value;phaseStarted=System.nanoTime();status=message;notify.accept(message); }
    private List<ItemStack> inventory() { return owner.getInventory().getNonEquipmentItems(); }
    private int count(Predicate<ItemStack> filter) { return inventory().stream().filter(filter).mapToInt(ItemStack::getCount).sum(); }
    private int count(Item item) { return count(s->s.is(item)); }
    private int stoneCount() { return count(s->s.is(Items.COBBLESTONE) || s.is(Items.STONE)); }
    private int pickaxe() {
        for(int i=0;i<inventory().size();i++) {
            ItemStack s=inventory().get(i);
            if(s.is(ItemTags.PICKAXES) && (!s.isDamageableItem() || s.getMaxDamage()-s.getDamageValue()>2)) return i;
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
            if(phase==Phase.STONE) {
                int collected=Math.max(0,stoneCount()-initialStone);
                if(collected>=quantity) { finish("Local hoàn thành: đã thu thêm "+collected+"/"+quantity+" đá cuội/đá.");return; }
                if(pickaxe()<0) { movement.stop();change(Phase.PLAN,"Cúp đã hết độ bền; chuẩn bị cúp mới cho phần còn thiếu.");return; }
                if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) {
                    cancel("Baritone dừng khi chưa đủ đá: "+collected+"/"+quantity+". "+movement.status());
                }
                return;
            }
            if(phase==Phase.WOOD) {
                if(count(s->logType(s)!=null)-initialWood>=requestedWood) { movement.stop();change(Phase.PLAN,"Đã đủ gỗ cần cho công cụ."); }
                else if(System.nanoTime()-phaseStarted>2_000_000_000L && !movement.busy()) cancel("Chưa thu đủ gỗ. "+movement.status());
                return;
            }
            if(phase==Phase.PLACE) {
                if(world.getBlockState(placedAt).is(Blocks.CRAFTING_TABLE)) { owner.getInventory().setSelectedSlot(oldSelected);table=placedAt;change(Phase.PLAN,"Đã đặt bàn chế tạo."); }
                else if(System.nanoTime()-phaseStarted>5_000_000_000L) cancel("Server chưa xác nhận bàn chế tạo được đặt; kiểm tra quyền xây dựng.");
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
        if(stoneCount()-initialStone>=quantity) { finish("Đã đủ số đá yêu cầu.");return; }
        boolean tableOpen=owner.containerMenu instanceof CraftingMenu;
        if(!tableOpen) table=findTable(client);
        var supplies=new StonePreparation.Supplies(pickaxe()>=0,count(s->logType(s)!=null),count(s->s.is(ItemTags.PLANKS)),count(Items.STICK),count(Items.CRAFTING_TABLE)>0,table!=null,tableOpen);
        switch(StonePreparation.next(supplies)) {
            case MINE_STONE -> {
                closeForMovement();
                int pick=pickaxe();
                if(!select(client,pick)) throw new IllegalArgumentException("Không chuyển được cúp lên thanh nhanh.");
                String response=movement.execute("mine minecraft:stone "+(quantity-Math.max(0,stoneCount()-initialStone)));
                if(!movement.busy()) throw new IllegalArgumentException(response);
                change(Phase.STONE,"Local: "+response);
            }
            case COLLECT_LOG -> {
                closeForMovement();
                requestedWood=StonePreparation.logsNeeded(supplies);initialWood=supplies.logs();
                String wood=nearestWood();
                String response=movement.execute("mine minecraft:"+wood+"_log "+requestedWood);
                if(!movement.busy()) throw new IllegalArgumentException(response);
                change(Phase.WOOD,"Local: thu thêm "+requestedWood+" gỗ "+wood+" để chế công cụ.");
            }
            case MAKE_PLANKS -> {
                ItemStack log=inventory().stream().filter(s->logType(s)!=null).findFirst().orElseThrow();
                Item planks=BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:"+logType(log)+"_planks"));
                craft(client,Map.of(0,(Predicate<ItemStack>)s->s.is(log.getItem())),new ItemStack(planks,4));
            }
            case MAKE_TABLE -> craft(client,plankRecipe(0,1,width(),width()+1),new ItemStack(Items.CRAFTING_TABLE));
            case MAKE_STICKS -> craft(client,plankRecipe(0,width()),new ItemStack(Items.STICK,4));
            case MAKE_PICKAXE -> {
                Map<Integer,Predicate<ItemStack>> recipe=new LinkedHashMap<>(plankRecipe(0,1,2));
                recipe.put(4,s->s.is(Items.STICK));recipe.put(7,s->s.is(Items.STICK));
                craft(client,recipe,new ItemStack(Items.WOODEN_PICKAXE));
            }
            case PLACE_TABLE -> placeTable(client);
            case OPEN_TABLE -> {
                closeForMovement();BlockHitResult hit=reachable(table);
                if(hit!=null) openTable(client,hit);
                else { movement.approachBlock(table,"bàn chế tạo");change(Phase.APPROACH,"Local: đi tới bàn chế tạo sẵn có tại "+table.toShortString()); }
            }
        }
    }
    private int width() { return ((AbstractCraftingMenu)owner.containerMenu).getGridWidth(); }
    private Map<Integer,Predicate<ItemStack>> plankRecipe(int...cells) { Map<Integer,Predicate<ItemStack>> result=new LinkedHashMap<>();for(int cell:cells) result.put(cell,s->s.is(ItemTags.PLANKS));return result; }
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
        InteractionHand hand=owner.getOffhandItem().isEmpty()?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        if(hand==InteractionHand.MAIN_HAND && !owner.getMainHandItem().isEmpty()) {
            int empty=-1;for(int i=0;i<9;i++) if(inventory().get(i).isEmpty()) { empty=i;break; }
            if(empty<0) throw new IllegalArgumentException("Cần một tay trống hoặc ô trống trên thanh nhanh để mở bàn.");
            owner.getInventory().setSelectedSlot(empty);
        }
        client.gameMode.useItemOn(owner,hand,hit);change(Phase.OPEN,"Local: đang mở bàn chế tạo.");
    }
    private void placeTable(Minecraft client) {
        closeForMovement();
        int item=-1;for(int i=0;i<inventory().size();i++) if(inventory().get(i).is(Items.CRAFTING_TABLE)) { item=i;break; }
        if(!select(client,item)) throw new IllegalArgumentException("Không chuyển được bàn chế tạo lên thanh nhanh.");
        BlockPos origin=owner.blockPosition();
        for(BlockPos target:BlockPos.betweenClosed(origin.offset(-2,-1,-2),origin.offset(2,0,2))) {
            if(!world.getBlockState(target).isAir() || owner.getBoundingBox().intersects(new AABB(target))) continue;
            BlockPos support=target.below();
            if(!world.getBlockState(support).isCollisionShapeFullBlock(world,support) || world.getBlockState(support).getBlock() instanceof net.minecraft.world.level.block.EntityBlock) continue;
            Vec3 top=new Vec3(support.getX()+0.5,support.getY()+1,support.getZ()+0.5);
            BlockHitResult ray=world.clip(new ClipContext(owner.getEyePosition(),top,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,owner));
            if(ray.getType()!=HitResult.Type.BLOCK || !ray.getBlockPos().equals(support) || ray.getDirection()!=Direction.UP
                    || owner.getEyePosition().distanceToSqr(top)>Math.pow(owner.blockInteractionRange(),2)) continue;
            placedAt=target.immutable();client.gameMode.useItemOn(owner,InteractionHand.MAIN_HAND,new BlockHitResult(top,Direction.UP,support,false));
            change(Phase.PLACE,"Local: đang đặt bàn chế tạo ở "+target.toShortString());return;
        }
        throw new IllegalArgumentException("Chưa có chỗ đất trống, bằng phẳng trong tầm để đặt bàn chế tạo.");
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
        if(!active) return;active=false;movement.stop();crafting.cancel(Minecraft.getInstance());
        if(owner!=null && Minecraft.getInstance().player==owner) owner.getInventory().setSelectedSlot(oldSelected);
        status=reason+" Nếu còn nguyên liệu ở lưới chế tạo/con trỏ, hãy cất lại; không tự thả đồ.";notify.accept(status);
    }
    private void finish(String message) { active=false;movement.stop();owner.getInventory().setSelectedSlot(oldSelected);status=message;notify.accept(status); }
}
