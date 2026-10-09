package dev.minecraftaibot.common;

import static dev.minecraftaibot.common.JumpPlacementGate.Decision.*;

public final class JumpPlacementTest {
    public static void run() {
        JumpPlacementGate gate=new JumpPlacementGate();
        check(gate.tick(true,true,64,64,true)==WAIT);
        check(gate.tick(true,true,64.42,64,false)==WAIT);
        check(gate.tick(true,true,64.75,64,false)==WAIT);
        check(gate.tick(true,true,65,64,false)==WAIT);
        check(gate.tick(true,true,65.01,64,false)==PLACE);
        check(gate.tick(true,true,65.2,64,false)==ABORT); // Never issue a second placement.
        gate=new JumpPlacementGate();check(gate.tick(false,true,65.1,64,false)==ABORT);
        gate=new JumpPlacementGate();check(gate.tick(true,false,65.1,64,false)==ABORT);
        gate=new JumpPlacementGate();for(int i=0;i<5;i++) check(gate.tick(true,true,64,64,true)==WAIT);
        check(gate.tick(true,true,64,64,true)==ABORT); // Jump key ignored / low ceiling / no jump.
        gate=new JumpPlacementGate();for(int i=0;i<40;i++) check(gate.tick(true,true,64.5,64,false)==WAIT);
        check(gate.tick(true,true,64.5,64,false)==ABORT);
        gate=new JumpPlacementGate();check(gate.tick(true,true,-62.99,-64,false)==PLACE);
        System.out.println("All underfoot jump placement clearance, one-shot, changed column/block, failed jump and timeout checks passed.");
    }
    private static void check(boolean condition) {if(!condition)throw new AssertionError("Jump placement gate check failed");}
}
