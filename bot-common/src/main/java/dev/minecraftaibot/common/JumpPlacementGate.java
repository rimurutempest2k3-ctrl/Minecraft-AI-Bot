package dev.minecraftaibot.common;

/** A single jump attempt: place only after the body clears the one-block target. */
public final class JumpPlacementGate {
    public enum Decision { WAIT, PLACE, ABORT }
    private int ticks;
    private boolean terminal;
    public Decision tick(boolean sameColumn, boolean spaceClear, double feetY, int targetY, boolean onGround) {
        if(terminal) return Decision.ABORT;
        ticks++;
        if(!sameColumn || !spaceClear || ticks>40 || ticks>5 && onGround) { terminal=true;return Decision.ABORT; }
        if(feetY>=targetY+1.001) { terminal=true;return Decision.PLACE; }
        return Decision.WAIT;
    }
}
