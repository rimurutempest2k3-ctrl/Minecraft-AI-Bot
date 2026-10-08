package minecraftaibot.client;

import dev.minecraftaibot.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput;
import java.util.*;
import java.util.function.Predicate;

/** Validated vanilla PICKUP clicks; the server supplies and consumes the recipe result. */
final class CraftingController {
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
        if (!(client.player.containerMenu instanceof AbstractCraftingMenu current)) throw new IllegalArgumentException("Cần menu chế tạo 2x2 hoặc bàn 3x3.");
        if(!current.getCarried().isEmpty()) throw new IllegalArgumentException("Hãy cất vật phẩm trên con trỏ trước.");
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
            if(entry.getKey()<0 || entry.getKey()>=grid.size()) throw new IllegalArgumentException("Công thức cần bàn chế tạo 3x3.");
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
        if(client.player.containerMenu!=menu) { fail("Menu chế tạo đã thay đổi."); return; }
        ticks++;
        if(!matches()) { if(ticks>40) fail("Nguyên liệu/túi đồ bị thay đổi hoặc server sửa dữ liệu."); return; }
        if(index==steps.size()) {
            if(++settle>=20) steps=null;
            return;
        }
        if(ticks<4) return;
        var step=steps.get(index);
        if(step.slot()==resultIndex) {
            ItemStack output=menu.getResultSlot().getItem();
            if(!ItemStack.isSameItemSameComponents(output,result) || output.getCount()!=result.getCount()) {
                if(ticks>60) fail("Server không trả sản phẩm đúng công thức; chưa lấy sản phẩm.");
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
}
