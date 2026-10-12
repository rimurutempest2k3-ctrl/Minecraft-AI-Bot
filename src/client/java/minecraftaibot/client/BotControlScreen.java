package minecraftaibot.client;

import dev.minecraftaibot.common.I18n;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Function;
import java.util.function.Supplier;

/** Small in-game controller; all actions run on the client thread. */
final class BotControlScreen extends Screen {
    private final Function<String,String> command;
    private final Supplier<String> status;
    private String response="";
    BotControlScreen(Function<String,String> command,Supplier<String> status) {
        super(Component.literal("Minecraft AI Bot"));this.command=command;this.status=status;
    }
    @Override protected void init() {
        int x=width/2-110,y=Math.max(45,height/2-55);
        String[][] actions={{"Start bot","start"},{"Pause bot","pause"},{"Resume bot","resume"},{"Stop bot","stop"}};
        for(int i=0;i<actions.length;i++) {
            String action=actions[i][1];
            addRenderableWidget(Button.builder(Component.literal(I18n.text(actions[i][0])),button->{
                if(action.equals("start") || action.equals("resume"))onClose();
                response=I18n.text(command.apply(action));
            }).bounds(x+(i%2)*115,y+(i/2)*29,105,24).build());
        }
        addRenderableWidget(Button.builder(Component.literal(I18n.text("Close")),button->onClose()).bounds(x,y+68,220,24).build());
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        int y=Math.max(45,height/2-55);
        graphics.centeredText(font,title,width/2,y-28,0xFFFFFFFF);
        graphics.textWithWordWrap(font,Component.literal(I18n.text(status.get())),Math.max(10,width/2-150),y+105,Math.min(300,width-20),0xFFBBD9C4);
        if(!response.isEmpty())graphics.textWithWordWrap(font,Component.literal(response),Math.max(10,width/2-150),y+140,Math.min(300,width-20),0xFFFFFFFF);
    }
}
