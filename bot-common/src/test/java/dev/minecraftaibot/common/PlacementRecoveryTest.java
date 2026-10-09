package dev.minecraftaibot.common;

import java.util.*;

public final class PlacementRecoveryTest {
    public static void run() {
        var origin=new PlacementRecovery.Cell(10,64,-10);
        var candidates=PlacementRecovery.candidates(origin);
        check(candidates.size()==12 && new HashSet<>(candidates).size()==12);
        int previous=0;
        for(var cell:candidates) {
            int x=cell.x()-origin.x(),z=cell.z()-origin.z(),distance=x*x+z*z;
            check(cell.y()==origin.y() && distance>=1 && distance<=4 && distance>=previous);previous=distance;
        }
        var recovery=new PlacementRecovery();
        var safe=new PlacementRecovery.Cell(12,64,-10);
        var first=recovery.choose(origin,cell->cell.equals(safe)).orElseThrow();
        check(first.equals(safe) && recovery.moves()==1);
        var second=recovery.choose(first,cell->true).orElseThrow();
        check(!second.equals(origin) && !second.equals(first) && recovery.moves()==2);
        check(recovery.choose(second,cell->true).isEmpty() && recovery.moves()==2);
        recovery=new PlacementRecovery();
        check(recovery.choose(origin,cell->false).isEmpty() && recovery.moves()==0);
        check(recovery.choose(origin,cell->true).isPresent() && recovery.moves()==1);
        System.out.println("All short placement relocation distance, closest-first, valid-position filtering, no-revisit and two-move limit checks passed.");
    }
    private static void check(boolean condition) {if(!condition)throw new AssertionError("Placement relocation check failed");}
}
