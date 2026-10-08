package dev.minecraftaibot.common;

import java.util.*;
import java.util.function.Predicate;

/** Short relocations, bounded to two moves and without revisiting failed positions. */
public final class PlacementRecovery {
    public record Cell(int x,int y,int z) {}
    private final Set<Cell> visited=new HashSet<>();
    private int moves;
    public int moves() { return moves; }
    public Optional<Cell> choose(Cell origin,Predicate<Cell> safe) {
        visited.add(origin);
        if(moves>=2) return Optional.empty();
        for(Cell candidate:candidates(origin)) if(!visited.contains(candidate) && safe.test(candidate)) {
            visited.add(candidate);moves++;return Optional.of(candidate);
        }
        return Optional.empty();
    }
    public static List<Cell> candidates(Cell origin) {
        List<Cell> result=new ArrayList<>();
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
            int d=x*x+z*z;if(d>0 && d<=4) result.add(new Cell(origin.x()+x,origin.y(),origin.z()+z));
        }
        result.sort(Comparator.comparingInt(c->{int x=c.x()-origin.x(),z=c.z()-origin.z();return x*x+z*z;}));
        return List.copyOf(result);
    }
}
