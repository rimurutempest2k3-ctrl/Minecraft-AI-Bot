package dev.minecraftaibot.common;

import static dev.minecraftaibot.common.SurvivalPolicy.Action.*;
import java.nio.file.Files;
import java.util.List;

public final class SurvivalPolicyTest {
    public static void run() throws Exception {
        check(SurvivalPolicy.emergencyFood(9,20,20));
        check(!SurvivalPolicy.emergencyFood(10,20,20));
        check(SurvivalPolicy.emergencyFood(19,40,20));
        check(SurvivalPolicy.emergencyFood(20,20,6));
        check(!SurvivalPolicy.emergencyFood(20,20,7));
        check(!SurvivalPolicy.emergencyFood(5,0,20));
        check(SurvivalPolicy.lootPriority(true,true,true,true,true)==0);
        check(SurvivalPolicy.lootPriority(false,true,false,false,false)==100);
        check(SurvivalPolicy.lootPriority(false,false,true,false,false)>SurvivalPolicy.lootPriority(false,false,false,true,false));
        check(SurvivalPolicy.lootPriority(false,false,false,true,false)>SurvivalPolicy.lootPriority(false,false,false,false,true));
        check(SurvivalPolicy.lootPriority(false,false,false,false,false)==0);
        check(SurvivalPolicy.lootPriority(false,true,true,false,false)>SurvivalPolicy.lootPriority(false,true,false,true,false));
        check(SurvivalPolicy.lootPriority(false,true,false,true,false)>SurvivalPolicy.lootPriority(false,true,false,false,true));
        check(SurvivalPolicy.recoveryOpportunity(20,20,0,List.of(new SurvivalPolicy.Threat(6,false,false,false,true,false))));
        check(SurvivalPolicy.recoveryOpportunity(20,20,0,List.of(new SurvivalPolicy.Threat(18,false,false,false,true,true))));
        check(!SurvivalPolicy.recoveryOpportunity(20,20,0,List.of(new SurvivalPolicy.Threat(3,false,false,false,true,false))));
        check(!SurvivalPolicy.recoveryOpportunity(20,20,0,List.of(new SurvivalPolicy.Threat(9,false,false,false,true,true))));
        check(!SurvivalPolicy.recoveryOpportunity(20,20,0,List.of(new SurvivalPolicy.Threat(20,true,true,false,false,false))));
        check(!SurvivalPolicy.recoveryOpportunity(20,20,0,List.of(new SurvivalPolicy.Threat(8,true,false,false,false,false))));
        check(!SurvivalPolicy.recoveryOpportunity(9,20,0,List.of()));
        check(!SurvivalPolicy.recoveryOpportunity(20,20,4,List.of()));
        check(SurvivalPolicy.hotbarScore(4,0,100,100,1)>SurvivalPolicy.hotbarScore(3,0,100,100,1));
        check(SurvivalPolicy.hotbarScore(4,3,100,100,1)>SurvivalPolicy.hotbarScore(4,0,100,100,1));
        check(SurvivalPolicy.hotbarScore(4,0,100,100,1)>SurvivalPolicy.hotbarScore(4,0,20,100,1));
        check(SurvivalPolicy.hotbarScore(5,10,3,100,1)<0);
        check(SurvivalPolicy.hotbarScore(1,0,0,0,64)>SurvivalPolicy.hotbarScore(1,0,0,0,1));
        check(SurvivalPolicy.hotbarScore(1,0,0,0,0)<0);
        // Recovery accepts even a previously unknown material; normal ignored loot stays excluded.
        check(SurvivalPolicy.lootPriority(false,true,false,false,false)>0);
        check(SurvivalPolicy.lootPriority(true,false,false,false,true)==0);
        var lootFile=Files.createTempFile("bot-loot-", ".json");
        try {
            Files.delete(lootFile);
            check(SurvivalPolicy.loadLoot(lootFile).radius()==32);
            Files.writeString(lootFile,"{\"version\":1,\"radius\":64,\"timeoutSeconds\":60,\"allowItems\":[],\"ignoreItems\":[]}");
            check(SurvivalPolicy.loadLoot(lootFile).radius()==64);
            for(String invalid:List.of(
                    "{\"version\":1,\"radius\":99,\"timeoutSeconds\":15,\"allowItems\":[],\"ignoreItems\":[]}",
                    "{\"version\":1,\"radius\":12,\"timeoutSeconds\":0,\"allowItems\":[],\"ignoreItems\":[]}",
                    "{\"version\":1,\"radius\":12,\"timeoutSeconds\":15,\"allowItems\":[\"*\"],\"ignoreItems\":[]}",
                    "{\"version\":1,\"radius\":12,\"timeoutSeconds\":15,\"allowItems\":[123],\"ignoreItems\":[]}")) {
                Files.writeString(lootFile,invalid);boolean rejected=false;
                try {SurvivalPolicy.loadLoot(lootFile);} catch(RuntimeException expected) {rejected=true;}
                check(rejected);
            }
        } finally {Files.deleteIfExists(lootFile);}
        check(SurvivalPolicy.shouldEquipShield(false,false,0,100));
        check(SurvivalPolicy.shouldEquipShield(true,false,3,100));
        check(!SurvivalPolicy.shouldEquipShield(true,false,100,200));
        check(!SurvivalPolicy.shouldEquipShield(false,true,0,100));
        check(!SurvivalPolicy.shouldEquipShield(false,false,0,3));
        var trashFile=Files.createTempFile("bot-trash-", ".json");
        try {
            Files.writeString(trashFile,"{\"version\":1,\"rules\":[{\"item\":\"minecraft:dirt\",\"keep\":64}]}");
            var filter=SurvivalPolicy.loadTrash(trashFile);
            check(filter.excess("minecraft:dirt",80)==16);
            check(filter.excess("minecraft:dirt",40)==0);
            check(filter.excess("minecraft:diamond",80)==0);
            for(String invalid:List.of(
                    "{\"version\":1,\"rules\":[{\"item\":\"minecraft:dirt\",\"keep\":-1}]}",
                    "{\"version\":1,\"rules\":[{\"item\":\"minecraft:dirt\",\"keep\":0.5}]}",
                    "{\"version\":1,\"rules\":[{\"item\":\"minecraft:dirt\",\"keep\":0},{\"item\":\"minecraft:dirt\",\"keep\":1}]}",
                    "{\"version\":1,\"rules\":[{\"item\":\"*\",\"keep\":0}]}",
                    "{\"version\":2,\"rules\":[]}")) {
                Files.writeString(trashFile,invalid);
                boolean rejected=false;
                try {SurvivalPolicy.loadTrash(trashFile);} catch(RuntimeException expected) {rejected=true;}
                check(rejected);
            }
            Files.delete(trashFile);
            check(SurvivalPolicy.loadTrash(trashFile).excess("minecraft:leaf_litter",9)==9);
        } finally {Files.deleteIfExists(trashFile);}
        check(SurvivalPolicy.resumeCombat(DEFEND,3,3,false));
        check(SurvivalPolicy.resumeCombat(RANGED,3,3,false));
        check(!SurvivalPolicy.resumeCombat(RETREAT,10,10,false));
        check(!SurvivalPolicy.resumeCombat(DEFEND,2.99,3,false));
        check(!SurvivalPolicy.resumeCombat(DEFEND,3,2.99,false));
        check(!SurvivalPolicy.resumeCombat(RANGED,10,10,true));
        // Visible ordinary mobs must not deadlock armour preparation; creeper/critical danger still preempts it.
        check(SurvivalPolicy.equipmentOpportunity(20,20,false,List.of(new SurvivalPolicy.Threat(2,false,false,false,true,false))));
        check(SurvivalPolicy.equipmentOpportunity(20,20,false,List.of(new SurvivalPolicy.Threat(16,false,false,false,true,true))));
        check(!SurvivalPolicy.equipmentOpportunity(7,20,false,List.of()));
        check(!SurvivalPolicy.equipmentOpportunity(20,20,true,List.of()));
        check(!SurvivalPolicy.equipmentOpportunity(20,20,false,List.of(new SurvivalPolicy.Threat(6,true,true,false,false,false))));
        check(!SurvivalPolicy.equipmentOpportunity(20,20,false,List.of(new SurvivalPolicy.Threat(4,true,false,false,false,false))));
        var settings=SurvivalPolicy.defaults();
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of())==IDLE);
        check(SurvivalPolicy.decide(settings,20,16,true,true,List.of())==EAT);
        check(SurvivalPolicy.decide(settings,10,19,true,true,List.of())==EAT);
        check(SurvivalPolicy.slimeWeight(1,false)==0);
        check(SurvivalPolicy.slimeWeight(2,false)==0.5);
        check(SurvivalPolicy.slimeWeight(4,false)==1.5);
        check(SurvivalPolicy.slimeWeight(1,true)>0);
        var weak=new SurvivalPolicy.Threat(2,false,false,false,true,false,0.5);
        check(SurvivalPolicy.assess(settings,20,20,20,20,64,true,true,true,List.of(weak,weak,weak,weak,weak)).action()==DEFEND);
        check(SurvivalPolicy.assess(settings,20,20,20,20,64,true,true,true,List.of(weak,weak,weak,weak,weak,weak)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,0,0,true,true,true,List.of(weak,weak,weak)).action()==RETREAT);
        var distant=new SurvivalPolicy.Threat(16,false,false,false,true,true);
        check(SurvivalPolicy.bowOpportunity(true,true,20,20,20,List.of(distant)));
        check(!SurvivalPolicy.bowOpportunity(false,true,20,20,20,List.of(distant)));
        check(!SurvivalPolicy.bowOpportunity(true,false,20,20,20,List.of(distant)));
        check(!SurvivalPolicy.bowOpportunity(true,true,9,20,20,List.of(distant)));
        check(!SurvivalPolicy.bowOpportunity(true,true,20,20,6,List.of(distant)));
        check(!SurvivalPolicy.bowOpportunity(true,true,20,20,20,List.of(distant,distant)));
        check(!SurvivalPolicy.bowOpportunity(true,true,20,20,20,List.of(distant,new SurvivalPolicy.Threat(8,false,false,false,true,false))));
        check(!SurvivalPolicy.bowOpportunity(true,true,20,20,20,List.of(new SurvivalPolicy.Threat(9,true,true,false,false,false))));
        check(!SurvivalPolicy.bowOpportunity(true,true,20,20,20,List.of(new SurvivalPolicy.Threat(25,false,false,false,true,false))));
        check(!SurvivalPolicy.bowOpportunity(true,true,20,20,20,List.of(new SurvivalPolicy.Threat(16,false,false,false,true,false))));
        for(double range:List.of(9d,16d,24d)) for(double height:List.of(-5d,0d,5d)) for(double motion:List.of(-0.2,0d,0.2)) for(boolean radial:List.of(false,true)) {
            var offset=new SurvivalPolicy.BowPoint(range,height,0);
            var velocity=new SurvivalPolicy.BowPoint(radial?motion:0,0,radial?0:motion);
            var inherited=new SurvivalPolicy.BowPoint(0.1,0,0);
            var aim=SurvivalPolicy.predictBow(offset,velocity,inherited);
            check(aim!=null && aim.flightTicks()>0 && aim.flightTicks()<12);
            // Independently advance a full-draw vanilla arrow; the intercept must match a moving target.
            double yaw=Math.toRadians(aim.yaw()),pitch=Math.toRadians(aim.pitch());
            double vx=-Math.sin(yaw)*Math.cos(pitch)*3+inherited.x(),vy=-Math.sin(pitch)*3,vz=Math.cos(yaw)*Math.cos(pitch)*3;
            double x=0,y=0,z=0,time=aim.flightTicks();
            for(int tick=0;tick<(int)time;tick++) {x+=vx;y+=vy;z+=vz;vx*=0.99f;vy=vy*0.99f-0.05;vz*=0.99f;}
            double part=time-(int)time;x+=vx*part;y+=vy*part;z+=vz*part;
            check(Math.abs(x-range-velocity.x()*(time+1))<0.00001 && Math.abs(y-height)<0.00001 && Math.abs(z-velocity.z()*(time+1))<0.00001);
            var endpoint=aim.path().getLast();
            check(Math.abs(endpoint.x()-x)<0.00001 && Math.abs(endpoint.y()-y)<0.00001 && Math.abs(endpoint.z()-z)<0.00001);
            check(aim.path().size()<=22 && aim.path().getFirst().length()==0);
            if(!radial && motion>0) check(aim.yaw()>-90);else if(!radial && motion<0) check(aim.yaw()<-90);
        }
        var zero=new SurvivalPolicy.BowPoint(0,0,0);
        var level=SurvivalPolicy.predictBow(new SurvivalPolicy.BowPoint(16,0,0),zero,zero);
        check(level.pitch()<0 && level.pitch()>-10);
        check(SurvivalPolicy.predictBow(new SurvivalPolicy.BowPoint(16,4,0),zero,zero).pitch()<level.pitch());
        check(SurvivalPolicy.predictBow(new SurvivalPolicy.BowPoint(16,-4,0),zero,zero).pitch()>level.pitch());
        check(SurvivalPolicy.predictBow(zero,zero,zero)==null);
        check(SurvivalPolicy.predictBow(new SurvivalPolicy.BowPoint(Double.NaN,0,0),zero,zero)==null);
        check(SurvivalPolicy.predictBow(new SurvivalPolicy.BowPoint(16,0,0),new SurvivalPolicy.BowPoint(1,0,0),zero)==null);
        check(SurvivalPolicy.predictBow(new SurvivalPolicy.BowPoint(100,0,0),zero,zero)==null);
        check(SurvivalPolicy.bowPath(0,0,zero,Double.NaN).isEmpty());
        check(SurvivalPolicy.releaseBow(20,20,3,true));
        check(!SurvivalPolicy.releaseBow(20,19,3,true));
        check(!SurvivalPolicy.releaseBow(19,20,3,true));
        check(!SurvivalPolicy.releaseBow(20,20,2,true));
        check(!SurvivalPolicy.releaseBow(20,20,3,false));
        var zombie=new SurvivalPolicy.Threat(2,false,false,false,true,false);
        var creeper=new SurvivalPolicy.Threat(3,true,true,true,false,false);
        var boss=new SurvivalPolicy.Threat(7,true,false,false,false,false);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(zombie))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,16,true,true,List.of(zombie))==RETREAT);
        check(SurvivalPolicy.decide(settings,8,20,true,true,List.of(zombie))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,false,true,List.of(zombie))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,false,List.of(zombie))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(zombie,zombie))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(zombie,creeper))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,0,true,true,List.of(creeper))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(boss))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,0,true,true,List.of(new SurvivalPolicy.Threat(14,false,false,false,true,false)))==IDLE);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(9,false,false,false,true,true)))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(4,false,false,false,true,true)))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(15,false,false,false,true,true)))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(17,false,false,false,true,true)))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(20.01,false,false,false,true,true)))==IDLE);
        check(SurvivalPolicy.decide(settings,8,20,true,true,List.of(new SurvivalPolicy.Threat(2,false,false,false,true,true)))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(2,false,false,false,true,true)))==RETREAT);
        var witch=new SurvivalPolicy.Threat(2,true,false,false,true,true);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(witch))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(7,false,false,false,true,false)))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(zombie,new SurvivalPolicy.Threat(7,false,false,false,true,false)))==RETREAT);
        check(SurvivalPolicy.decide(settings,8,20,true,true,List.of(witch))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,false,true,List.of(witch))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,false,List.of(witch))==RETREAT);
        check(SurvivalPolicy.decide(settings,20,20,true,true,List.of(new SurvivalPolicy.Threat(2,true,false,true,true,false)))==RETREAT);
        check(SurvivalPolicy.foodScore(4,2,16)>SurvivalPolicy.foodScore(1,0,16));
        var origin=new SurvivalPolicy.PositionXZ(0,0);
        check(SurvivalPolicy.outsideEscapeRadius(origin,List.of()));
        check(SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(8,0))));
        check(!SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(7.99,0))));
        check(!SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(10,0),new SurvivalPolicy.PositionXZ(0,2))));
        check(SurvivalPolicy.preferredEscapeRadius(1)==8 && SurvivalPolicy.preferredEscapeRadius(2)==16);
        check(SurvivalPolicy.smallerEscapeRadius(16)==12 && SurvivalPolicy.smallerEscapeRadius(12)==8);
        check(!SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(15.99,0)),16));
        check(SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(16,0)),16));
        check(!SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(19.99,0)),20));
        check(SurvivalPolicy.outsideEscapeRadius(origin,List.of(new SurvivalPolicy.PositionXZ(20,0)),20));
        var one=SurvivalPolicy.assess(settings,20,20,20,8,8,true,true,true,List.of(zombie));
        check(one.action()==DEFEND && one.level()==SurvivalPolicy.Danger.MODERATE);
        var two=SurvivalPolicy.assess(settings,20,20,20,8,8,true,true,true,List.of(zombie,zombie));
        check(two.action()==DEFEND && two.level()==SurvivalPolicy.Danger.MANAGEABLE);
        check(SurvivalPolicy.assess(settings,20,20,20,8,0,true,true,true,List.of(zombie,zombie)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,0,64,true,true,true,List.of(zombie,zombie)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,15,20,20,20,64,true,true,true,List.of(zombie,zombie)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,6,8,0,true,true,true,List.of(zombie)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,6,8,16,true,true,true,List.of(zombie)).action()==RETREAT);
        var three=SurvivalPolicy.assess(settings,20,20,20,20,64,true,true,true,List.of(zombie,zombie,zombie));
        check(three.action()==RETREAT && three.level()==SurvivalPolicy.Danger.DANGEROUS);
        var archer=new SurvivalPolicy.Threat(18,false,false,false,true,true);
        var mixed=SurvivalPolicy.assess(settings,20,20,20,20,64,true,true,true,List.of(zombie,zombie,archer));
        check(mixed.action()==RETREAT && mixed.escapeRadius()==20);
        var diamond=new SurvivalPolicy.Gear(20,8,4,true,4);
        var iron=new SurvivalPolicy.Gear(15,0,4,true,3);
        var naked=new SurvivalPolicy.Gear(0,0,0,true,4);
        var crowd=List.of(zombie,zombie,zombie,zombie,zombie,archer);
        check(SurvivalPolicy.assess(settings,20,20,20,32,diamond,true,true,crowd).action()==DEFEND);
        check(SurvivalPolicy.assess(settings,20,20,20,0,diamond,true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,16,diamond,true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,naked,true,true,List.of(zombie)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,iron,true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,diamond,true,false,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,17,20,20,64,diamond,true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,12,64,diamond,true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,new SurvivalPolicy.Gear(20,8,4,false,4),true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,new SurvivalPolicy.Gear(20,8,4,true,1),true,true,crowd).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,new SurvivalPolicy.Gear(17,6,3,true,4),true,true,crowd).action()==RETREAT);
        var line=List.of(zombie,new SurvivalPolicy.Threat(6,false,false,false,true,false),new SurvivalPolicy.Threat(10,false,false,false,true,false));
        check(SurvivalPolicy.assess(settings,20,20,20,16,iron,true,true,line).action()==DEFEND);
        check(SurvivalPolicy.assess(settings,20,20,20,16,iron,true,true,List.of(zombie,zombie,zombie)).action()==RETREAT);
        check(SurvivalPolicy.pressure(line.getLast())<SurvivalPolicy.pressure(zombie));
        check(SurvivalPolicy.assess(settings,20,20,20,64,diamond,true,true,List.of(zombie,boss)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,diamond,true,true,List.of(zombie,creeper)).level()==SurvivalPolicy.Danger.CREEPER_ALERT);
        check(SurvivalPolicy.assess(settings,20,20,20,64,diamond,false,true,crowd).action()==RETREAT);
        var closeCreeper=new SurvivalPolicy.Threat(8,true,true,true,false,false);
        var alert=SurvivalPolicy.assess(settings,8,20,1,0,0,false,false,true,List.of(zombie,closeCreeper));
        check(alert.action()==SurvivalPolicy.Action.CREEPER_SHIELD && alert.level()==SurvivalPolicy.Danger.CREEPER_ALERT);
        check(SurvivalPolicy.assess(settings,20,20,20,20,64,true,true,false,List.of(closeCreeper)).action()==RETREAT);
        check(SurvivalPolicy.assess(settings,20,20,20,20,64,true,true,true,List.of(new SurvivalPolicy.Threat(8.01,true,true,false,false,false))).action()==RETREAT);
        check(SurvivalPolicy.shouldShield(true,true,true,true,false,3,false));
        check(SurvivalPolicy.shouldShield(true,true,true,true,false,7,true));
        check(!SurvivalPolicy.shouldShield(true,true,true,true,true,2,false));
        check(!SurvivalPolicy.shouldShield(true,true,false,true,false,2,false));
        check(!SurvivalPolicy.shouldShield(true,true,true,false,false,2,false));
        check(!SurvivalPolicy.shouldShield(true,false,true,true,false,2,false));
        check(!SurvivalPolicy.shouldShield(false,true,true,true,false,2,false));
        check(!SurvivalPolicy.shouldShield(true,true,true,true,false,5,false));
        var ironArmor=new SurvivalPolicy.ArmorRating(6,0,0,0,200,240,false,false);
        var diamondArmor=new SurvivalPolicy.ArmorRating(8,2,0,0,400,528,false,false);
        var wornDiamond=new SurvivalPolicy.ArmorRating(8,2,0,0,3,528,false,false);
        check(SurvivalPolicy.shouldEquipArmor(null,ironArmor));
        check(SurvivalPolicy.shouldEquipArmor(ironArmor,diamondArmor));
        check(!SurvivalPolicy.shouldEquipArmor(diamondArmor,ironArmor));
        check(SurvivalPolicy.shouldEquipArmor(wornDiamond,ironArmor));
        check(!SurvivalPolicy.shouldEquipArmor(ironArmor,wornDiamond));
        check(!SurvivalPolicy.shouldEquipArmor(diamondArmor,diamondArmor));
        check(!SurvivalPolicy.shouldEquipArmor(new SurvivalPolicy.ArmorRating(6,0,0,0,200,240,true,false),diamondArmor));
        check(!SurvivalPolicy.shouldEquipArmor(null,new SurvivalPolicy.ArmorRating(8,2,0,0,400,528,true,false)));
        check(!SurvivalPolicy.shouldEquipArmor(new SurvivalPolicy.ArmorRating(0,0,0,0,100,432,false,true),diamondArmor));
        check(!SurvivalPolicy.shouldEquipArmor(null,new SurvivalPolicy.ArmorRating(0,0,0,0,100,432,false,true)));
        check(SurvivalPolicy.shouldEquipArmor(new SurvivalPolicy.ArmorRating(3,0,0,0,100,200,false,false),
                new SurvivalPolicy.ArmorRating(3,0,4,0,100,200,false,false)));
        var pillar=new SurvivalPolicy.PillarProgress();
        check(!pillar.confirm(0,false,true) && !pillar.confirm(0,true,false) && pillar.placed()==0);
        check(pillar.confirm(0,true,true) && pillar.placed()==1);
        check(!pillar.confirm(0,true,true) && !pillar.confirm(2,true,true) && pillar.placed()==1);
        check(pillar.confirm(1,true,true) && !pillar.complete());
        check(pillar.confirm(2,true,true) && pillar.complete() && !pillar.confirm(3,true,true));
        var folder=Files.createTempDirectory("survival-test");var file=folder.resolve("survival.json");
        check(SurvivalPolicy.load(file).dangerousMobs().contains("minecraft:creeper"));
        String original=Files.readString(file);
        Files.writeString(file,original.replace("minecraft:warden","example:dangerous_boss"));
        check(SurvivalPolicy.load(file).dangerousMobs().contains("example:dangerous_boss"));
        for(String bad:List.of(original.replace("\"eatBelow\": 16","\"eatBelow\": 20"),original.replace("\"scanRadius\": 16","\"scanRadius\": 1.5"),
                original.replace("minecraft:warden","minecraft:warden;exit"),original.replace("\"version\": 1","\"version\": 2"))) {
            Files.writeString(file,bad);
            try {SurvivalPolicy.load(file);throw new AssertionError("Bad settings accepted");} catch(IllegalArgumentException expected) {}
        }
        Files.writeString(file,original);check(SurvivalPolicy.load(file).equals(settings));
        var biomeFile=folder.resolve("danger-biomes.json");
        var biomes=SurvivalPolicy.loadBiomes(biomeFile);
        check(biomes.avoids("minecraft:deep_dark") && !biomes.avoids("minecraft:plains"));
        String biomeText=Files.readString(biomeFile);
        Files.writeString(biomeFile,biomeText.replace("minecraft:deep_dark","example:dangerous_biome"));
        check(SurvivalPolicy.loadBiomes(biomeFile).avoids("example:dangerous_biome"));
        for(String bad:List.of(biomeText.replace("\"lookAhead\": 8","\"lookAhead\": 0"),biomeText.replace("\"lookAhead\": 8","\"lookAhead\": 2.5"),
                biomeText.replace("minecraft:deep_dark","minecraft:deep_dark;exit"),biomeText.replace("\"version\": 1","\"version\": 2"))) {
            Files.writeString(biomeFile,bad);
            try {SurvivalPolicy.loadBiomes(biomeFile);throw new AssertionError("Bad biome settings accepted");} catch(IllegalArgumentException expected) {}
        }
        System.out.println("All recovery priority with distant hostiles, expanded loot radius, food/gear/distance combat capacity, diamond set versus 5 zombies + skeleton, bow moving-target intercept, drag/gravity, inherited velocity, ranged-only targeting, survival priority, eight-block escape distance, confirmed three-block pillar, melee/ranged decisions, food scoring and config validation checks passed.");
    }
    private static void check(boolean value) {if(!value) throw new AssertionError("Survival policy check failed");}
}
