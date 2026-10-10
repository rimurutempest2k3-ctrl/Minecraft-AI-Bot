package dev.minecraftaibot.common;

import java.util.*;
import java.util.function.Predicate;

/** Short relocations, bounded to two moves and without revisiting failed positions. */
public final class PlacementRecovery {
    public static boolean canClear(boolean natural,boolean protectedBlock,boolean occupied,boolean fluidNearby,boolean fallingAbove) {
        return natural && !protectedBlock && !occupied && !fluidNearby && !fallingAbove;
    }
    public record Cell(int x,int y,int z) {}
    private final Set<Cell> visited=new HashSet<>();
    private int moves;
    public int moves() { return moves; }
    public Optional<Cell> choose(Cell origin,Predicate<Cell> safe) {
        return choose(origin,safe,2,0);
    }
    public Optional<Cell> choose(Cell origin,Predicate<Cell> safe,int radius,int vertical) {
        visited.add(origin);
        if(moves>=2) return Optional.empty();
        for(Cell candidate:candidates(origin,radius,vertical)) if(!visited.contains(candidate) && safe.test(candidate)) {
            visited.add(candidate);moves++;return Optional.of(candidate);
        }
        return Optional.empty();
    }
    public static List<Cell> candidates(Cell origin) {
        return candidates(origin,2,0);
    }
    public static List<Cell> candidates(Cell origin,int radius,int vertical) {
        if(radius<1 || radius>8 || vertical<0 || vertical>3)throw new IllegalArgumentException("Invalid placement search bounds");
        List<Cell> result=new ArrayList<>();
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) for(int y=-vertical;y<=vertical;y++) {
            int d=x*x+z*z;if(d>0 && d<=radius*radius) result.add(new Cell(origin.x()+x,origin.y()+y,origin.z()+z));
        }
        result.sort(Comparator.comparingInt(c->{int x=c.x()-origin.x(),z=c.z()-origin.z(),y=c.y()-origin.y();return x*x+z*z+y*y;}));
        return List.copyOf(result);
    }
}
