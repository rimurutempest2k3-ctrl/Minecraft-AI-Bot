package dev.minecraftaibot.common;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Decisions are independent of Minecraft; the client controller owns all interaction. */
public final class SurvivalPolicy {
    private SurvivalPolicy() {}
    public static boolean emergencyFood(double health,double maximum,int hunger) {
        return maximum>0 && Double.isFinite(maximum) && Double.isFinite(health) && (health<maximum*0.5 || hunger<=6);
    }
    public static int lootPriority(boolean ignored,boolean recover,boolean equipment,boolean food,boolean material) {
        if(ignored) return 0;
        return recover?(equipment?160:food?140:100):equipment?80:food?60:material?40:0;
    }
    public static double hotbarScore(int quality,int enchantments,int remaining,int maximum,int count) {
        if(quality<1 || count<1 || maximum>0 && remaining<=Math.max(5,maximum/50)) return -1;
        return quality*100+Math.min(10,Math.max(0,enchantments))*15
                +(maximum>0?Math.max(0,Math.min(1,(double)remaining/maximum))*20:0)+Math.min(64,count);
    }
    public static boolean recoveryOpportunity(double health,double maximum,double recentDamage,List<Threat> threats) {
        return health>=Math.max(10,maximum*0.5) && recentDamage<4
                && threats.stream().noneMatch(t->t.creeper() || t.ignited()
                        || t.distance()<=(t.dangerous()?12:t.ranged()?10:3.5));
    }
    public record Loot(int version,int radius,int timeoutSeconds,Set<String> allowItems,Set<String> ignoreItems) {
        public Loot {
            if(version!=1 || radius<2 || radius>64 || timeoutSeconds<3 || timeoutSeconds>60
                    || allowItems==null || ignoreItems==null || allowItems.size()>128 || ignoreItems.size()>128)
                throw new IllegalArgumentException("Loot configuration out of bounds.");
            for(var set:List.of(allowItems,ignoreItems)) for(String id:set)
                if(id==null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid item ID.");
            allowItems=Set.copyOf(allowItems);ignoreItems=Set.copyOf(ignoreItems);
        }
    }
    public static Loot defaultLoot() {
        return new Loot(1,32,30,Set.of("minecraft:coal","minecraft:charcoal","minecraft:raw_iron","minecraft:iron_ingot",
                "minecraft:raw_gold","minecraft:gold_ingot","minecraft:diamond","minecraft:emerald","minecraft:redstone",
                "minecraft:lapis_lazuli","minecraft:cobblestone","minecraft:cobbled_deepslate","minecraft:stick",
                "minecraft:crafting_table","minecraft:furnace","minecraft:torch","minecraft:dirt"),Set.of());
    }
    public static Loot loadLoot(Path path) throws IOException {
        if(!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path,new Gson().newBuilder().setPrettyPrinting().create().toJson(defaultLoot())+"\n");
        }
        if(Files.size(path)>32768) throw new IllegalArgumentException("loot-items.json too large.");
        var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if(!root.keySet().equals(Set.of("version","radius","timeoutSeconds","allowItems","ignoreItems")))
            throw new IllegalArgumentException("Invalid loot structure.");
        for(String key:List.of("version","radius","timeoutSeconds"))
            if(!root.get(key).toString().matches("[0-9]+")) throw new IllegalArgumentException("Invalid loot value.");
        for(String key:List.of("allowItems","ignoreItems")) for(var id:root.getAsJsonArray(key))
            if(!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("ID must be a string.");
        return new Gson().fromJson(root,Loot.class);
    }
    public static boolean shouldEquipShield(boolean currentShield, boolean protectedOffhand, int currentRemaining, int candidateRemaining) {
        return !protectedOffhand && candidateRemaining>3 && (!currentShield || currentRemaining<=3);
    }
    public record TrashRule(String item, int keep) {
        public TrashRule {
            if(item==null || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || keep<0 || keep>4096)
                throw new IllegalArgumentException("Invalid trash rule.");
        }
    }
    public record Trash(int version, List<TrashRule> rules) {
        public Trash {
            if(version!=1 || rules==null || rules.size()>128) throw new IllegalArgumentException("Invalid trash configuration.");
            rules=List.copyOf(rules);
            if(rules.stream().map(TrashRule::item).distinct().count()!=rules.size()) throw new IllegalArgumentException("Duplicate trash ID.");
        }
        public int excess(String id,int total) {
            return rules.stream().filter(r->r.item().equals(id)).findFirst().map(r->Math.max(0,total-r.keep())).orElse(0);
        }
    }
    public static Trash loadTrash(Path path) throws IOException {
        if(!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path,"{\"version\":1,\"rules\":[{\"item\":\"minecraft:leaf_litter\",\"keep\":0}]}\n");
        }
        if(Files.size(path)>32768) throw new IllegalArgumentException("trash-items.json too large.");
        var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if(!root.keySet().equals(Set.of("version","rules")) || !root.get("version").toString().equals("1"))
            throw new IllegalArgumentException("Invalid trash structure.");
        var rules=new java.util.ArrayList<TrashRule>();
        for(var element:root.getAsJsonArray("rules")) {
            var r=element.getAsJsonObject();
            if(!r.keySet().equals(Set.of("item","keep")) || !r.get("item").isJsonPrimitive()
                    || !r.get("item").getAsJsonPrimitive().isString() || !r.get("keep").toString().matches("[0-9]+"))
                throw new IllegalArgumentException("Incorrect trash rule type.");
            rules.add(new TrashRule(r.get("item").getAsString(),r.get("keep").getAsInt()));
        }
        return new Trash(1,rules);
    }
    public enum Action { IDLE, EAT, DEFEND, RANGED, RETREAT, CREEPER_SHIELD }
    public enum Danger { CLEAR, MODERATE, MANAGEABLE, DANGEROUS, CREEPER_ALERT }
    public record Assessment(Danger level,Action action,double escapeRadius,String reason) {}
    public record Threat(double distance, boolean dangerous, boolean creeper, boolean ignited, boolean defendable, boolean ranged,double weight) {
        public Threat(double distance,boolean dangerous,boolean creeper,boolean ignited,boolean defendable,boolean ranged) {
            this(distance,dangerous,creeper,ignited,defendable,ranged,1);
        }
        public Threat {
            if(!Double.isFinite(distance) || distance<0 || !Double.isFinite(weight) || weight<0 || weight>16)
                throw new IllegalArgumentException("Invalid mob danger value.");
        }
    }
    public static double slimeWeight(int size,boolean magma) {return magma?Math.max(1.5,Math.min(4,size)):size<=1?0:size<=2?0.5:1.5;}
    public static boolean releaseBow(int controlledTicks,int usedTicks,int alignedTicks,boolean clear) {
        return controlledTicks>=20 && usedTicks>=20 && alignedTicks>=3 && clear;
    }
    public static boolean resumeCombat(Action decision,double retreatSeconds,double secondsSinceDamage,boolean avoidedBiome) {
        return !avoidedBiome && retreatSeconds>=3 && secondsSinceDamage>=3 && (decision==Action.DEFEND || decision==Action.RANGED);
    }
    public static boolean equipmentOpportunity(double health,double maximum,boolean emergencyFood,List<Threat> threats) {
        return !emergencyFood && health>=Math.max(8,maximum*0.5)
                && threats.stream().noneMatch(t->t.creeper() || t.ignited() || t.dangerous() && t.distance()<=8);
    }
    public static boolean bowOpportunity(boolean enabled,boolean ammunition,double health,double maximum,int hunger,List<Threat> threats) {
        return enabled && ammunition && health>=Math.max(14,maximum*0.7) && hunger>=10 && !threats.isEmpty()
                && threats.stream().allMatch(t->!t.creeper() && !t.ignited() && t.defendable() && !t.dangerous() && t.distance()>8)
                && threats.stream().filter(t->t.distance()<=24).count()==1
                && threats.stream().anyMatch(t->t.ranged() && t.distance()<=24);
    }
    public record BowPoint(double x,double y,double z) {
        public boolean finite() {return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);}
        public BowPoint add(BowPoint other) {return new BowPoint(x+other.x,y+other.y,z+other.z);}
        public BowPoint scale(double value) {return new BowPoint(x*value,y*value,z*value);}
        public double length() {return Math.sqrt(x*x+y*y+z*z);}
    }
    public record BowAim(double yaw,double pitch,double flightTicks,BowPoint impact,List<BowPoint> path) {}
    private static final double ARROW_DRAG=(double)0.99f,ARROW_GRAVITY=0.05,ARROW_SPEED=3;
    /** Vanilla: advance position, multiply velocity by drag, then subtract gravity, once per tick. */
    private static BowPoint flightFactors(double time) {
        int ticks=(int)time;double fraction=time-ticks,sum=0,drop=0,decay=1,fallVelocity=0;
        for(int i=0;i<ticks;i++) {sum+=decay;drop+=fallVelocity;decay*=ARROW_DRAG;fallVelocity=fallVelocity*ARROW_DRAG+ARROW_GRAVITY;}
        return new BowPoint(sum+decay*fraction,drop+fallVelocity*fraction,0);
    }
    private static BowPoint launchFor(BowPoint offset,BowPoint targetVelocity,BowPoint inherited,double time) {
        var factors=flightFactors(time);
        // One tick for the camera update/release to reach the server; recalculate during the draw.
        var intercept=offset.add(targetVelocity.scale(time+1));
        return new BowPoint(intercept.x/factors.x-inherited.x,(intercept.y+factors.y)/factors.x-inherited.y,intercept.z/factors.x-inherited.z);
    }
    /** Bounded low-arc intercept in air; target velocity is blocks per game tick. */
    public static BowAim predictBow(BowPoint offset,BowPoint targetVelocity,BowPoint inherited) {
        if(offset==null || targetVelocity==null || inherited==null || !offset.finite() || !targetVelocity.finite() || !inherited.finite()
                || Math.hypot(offset.x,offset.z)<1 || offset.length()>32 || Math.abs(offset.y)>16
                || targetVelocity.length()>0.6 || inherited.length()>0.6) return null;
        double low=0.25,high=low;
        boolean found=false;
        for(int step=1;step<=80;step++) {
            high=0.25+step*0.25;
            if(launchFor(offset,targetVelocity,inherited,high).length()<=ARROW_SPEED) {found=true;break;}
            low=high;
        }
        if(!found) return null;
        for(int iteration=0;iteration<24;iteration++) {
            double mid=(low+high)/2;
            if(launchFor(offset,targetVelocity,inherited,mid).length()>ARROW_SPEED) low=mid;else high=mid;
        }
        var launch=launchFor(offset,targetVelocity,inherited,high);
        double yaw=Math.toDegrees(Math.atan2(launch.z,launch.x))-90;
        double pitch=-Math.toDegrees(Math.atan2(launch.y,Math.hypot(launch.x,launch.z)));
        var path=bowPath(yaw,pitch,inherited,high);
        return new BowAim(yaw,pitch,high,offset.add(targetVelocity.scale(high+1)),path);
    }
    /** Relative positions at tick boundaries and the final fractional tick, for collision checks. */
    public static List<BowPoint> bowPath(double yaw,double pitch,BowPoint inherited,double time) {
        if(!Double.isFinite(yaw) || !Double.isFinite(pitch) || inherited==null || !inherited.finite()
                || !Double.isFinite(time) || time<=0 || time>21) return List.of();
        double yr=Math.toRadians(yaw),pr=Math.toRadians(pitch);
        var velocity=new BowPoint(-Math.sin(yr)*Math.cos(pr)*ARROW_SPEED,-Math.sin(pr)*ARROW_SPEED,Math.cos(yr)*Math.cos(pr)*ARROW_SPEED).add(inherited);
        var points=new java.util.ArrayList<BowPoint>();points.add(new BowPoint(0,0,0));
        for(int tick=1;tick<time;tick++) points.add(bowPosition(velocity,tick));
        points.add(bowPosition(velocity,time));return List.copyOf(points);
    }
    private static BowPoint bowPosition(BowPoint velocity,double time) {
        var factors=flightFactors(time);
        return new BowPoint(velocity.x*factors.x,velocity.y*factors.x-factors.y,velocity.z*factors.x);
    }
    public record Settings(int version, int eatBelow, double combatMinHealth, int scanRadius,
                           int avoidRadius, int hostileRadius, Set<String> dangerousMobs) {
        public Settings {
            if(version!=1 || eatBelow<1 || eatBelow>19 || !Double.isFinite(combatMinHealth) || combatMinHealth<6
                    || combatMinHealth>20 || scanRadius<12 || scanRadius>32 || avoidRadius<8 || avoidRadius>scanRadius
                    || hostileRadius<3 || hostileRadius>avoidRadius || dangerousMobs==null || dangerousMobs.size()>128)
                throw new IllegalArgumentException("Survival configuration out of bounds.");
            for(String id:dangerousMobs) if(id==null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid dangerous mob ID.");
            dangerousMobs=Set.copyOf(dangerousMobs);
        }
    }
    public static Settings defaults() {
        return new Settings(1,16,14,16,12,6,Set.of("minecraft:creeper","minecraft:warden","minecraft:wither",
                "minecraft:ender_dragon","minecraft:ravager","minecraft:evoker","minecraft:vindicator",
                "minecraft:witch","minecraft:piglin_brute","minecraft:blaze","minecraft:ghast",
                "minecraft:enderman","minecraft:magma_cube"));
    }
    public static Settings load(Path path) throws IOException {
        if(!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path,new Gson().newBuilder().setPrettyPrinting().create().toJson(defaults())+"\n");
        }
        if(Files.size(path)>32768) throw new IllegalArgumentException("survival.json too large.");
        try {
            var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(!root.keySet().equals(Set.of("version","eatBelow","combatMinHealth","scanRadius","avoidRadius","hostileRadius","dangerousMobs")))
                throw new IllegalArgumentException("Invalid survival configuration fields.");
            for(String key:List.of("version","eatBelow","scanRadius","avoidRadius","hostileRadius"))
                if(!root.get(key).toString().matches("[0-9]+")) throw new IllegalArgumentException("Configuration values must be integers.");
            if(!root.get("combatMinHealth").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Health must be numeric.");
            return new Gson().fromJson(root,Settings.class);
        } catch(RuntimeException failure) {throw new IllegalArgumentException("Invalid survival.json structure or values.");}
    }
    public static Action decide(Settings settings,double health,int hunger,boolean armed,boolean combat,List<Threat> threats) {
        return assess(settings,health,20,hunger,0,0,armed,combat,false,threats).action();
    }
    public record Gear(double armor,double toughness,int pieces,boolean healthy,double weapon) {
        public Gear {
            if(!Double.isFinite(armor) || !Double.isFinite(toughness) || !Double.isFinite(weapon)
                    || armor<0 || armor>40 || toughness<0 || toughness>40 || pieces<0 || pieces>4 || weapon<0 || weapon>6)
                throw new IllegalArgumentException("Equipment outside assessment limits.");
        }
        public boolean strong() {return healthy && pieces==4 && armor>=20 && toughness>=8 && weapon>=4;}
        public double capacity() {
            if(!healthy || armor<8 || weapon<1) return 0;
            return strong()?6.75:armor>=15 && pieces>=3 && weapon>=2?2.75:2.1;
        }
    }
    /** Distance weights approximate simultaneous pressure, not combat damage or guaranteed wins. */
    public static double pressure(Threat t) {
        return t.weight()*(t.ranged()?(t.distance()<=4?1.5:1.25):t.distance()<=4?1:t.distance()<=8?0.65:0.3);
    }
    public static Assessment assess(Settings settings,double health,double maxHealth,int hunger,double armor,int foodNutrition,
                                    boolean armed,boolean combat,boolean shield,List<Threat> threats) {
        return assess(settings,health,maxHealth,hunger,foodNutrition,
                new Gear(Math.max(0,Math.min(40,armor)),0,armor>=15?4:armor>0?2:0,true,armed?2:0),combat,shield,threats);
    }
    /** Food reserve is the first gate; armour, weapon, current health and distance determine capacity. */
    public static Assessment assess(Settings settings,double health,double maxHealth,int hunger,int foodNutrition,
                                    Gear gear,boolean combat,boolean shield,List<Threat> threats) {
        var creeper=threats.stream().filter(Threat::creeper).min(java.util.Comparator.comparingDouble(Threat::distance));
        if(creeper.isPresent()) {
            boolean close=creeper.get().distance()<=8;
            return new Assessment(Danger.CREEPER_ALERT,close && shield?Action.CREEPER_SHIELD:Action.RETREAT,
                    threats.stream().anyMatch(Threat::ranged)?20:16,
                    close?(shield?"Creeper within 8 blocks: face it and hold shield.":"Creeper within 8 blocks without a usable shield: retreat.")
                            :"Creeper beyond 8 blocks: proactively retreat.");
        }
        var near=threats.stream().filter(t->t.weight()>0 && t.distance()<=(t.ranged()?Math.max(20,settings.scanRadius()):12)).toList();
        if(near.isEmpty()) return new Assessment(Danger.CLEAR,!threats.isEmpty()?Action.IDLE:
                hunger<=settings.eatBelow() || health<settings.combatMinHealth() && hunger<20?Action.EAT:Action.IDLE,8,"No threat within intervention range.");
        long ranged=near.stream().filter(Threat::ranged).count();
        boolean mixed=ranged>0 && ranged<near.size();
        double radius=ranged>0?20:preferredEscapeRadius(near.size());
        int count=near.size(),requiredFood=count>=6?32:count>=4?24:count>=3?16:count>=2?8:4;
        int reserve=Math.max(0,Math.min(4096,foodNutrition));
        String reason=null;
        if(reserve<requiredFood || hunger<(count>=4?16:10)) reason="Not enough food reserves or low hunger: prioritize survival and retreat.";
        else if(gear.capacity()==0) reason="Insufficient armor/weapons, or equipment nearly broken: retreat.";
        else if(!combat || health<Math.max(settings.combatMinHealth(),maxHealth*(count>=4?0.9:count>=2?0.8:0.7)))
            reason="Not enough health for this mob group, or combat disabled: retreat.";
        else if(near.stream().anyMatch(t->!t.defendable() || t.ignited() || t.dangerous())) reason="Extremely dangerous mob present; good equipment does not override the alarm.";
        else if(count>8 || near.stream().mapToDouble(SurvivalPolicy::pressure).sum()>gear.capacity()) reason="Approaching mobs exceed armor and weapon capacity: retreat.";
        else if((mixed || near.stream().mapToDouble(SurvivalPolicy::pressure).sum()>=3) && (!gear.strong() || !shield)) reason="Large group or mixed ranged/melee mobs: requires full armor, strong weapon and shield.";
        else if(ranged==count && near.stream().noneMatch(t->t.distance()<=3)) reason="Only ranged mobs outside melee range: need a usable bow, or retreat.";
        if(reason!=null) return new Assessment(Danger.DANGEROUS,Action.RETREAT,radius,reason);
        return new Assessment(count==1?Danger.MODERATE:Danger.MANAGEABLE,Action.DEFEND,radius,
                gear.strong()?"Full armor, strong weapon and enough food: hold position and fight within capacity."
                        :"Enough food and equipment; mobs are not simultaneously closing beyond the threshold: fight the nearest target.");
    }
    public static double foodScore(int nutrition,float saturation,int hunger) {
        return Math.min(20-hunger,nutrition)+saturation-Math.max(0,nutrition-(20-hunger))*0.5;
    }
    public record ArmorRating(double defense,double toughness,int protection,int specialProtection,
                              int remaining,int maximum,boolean binding,boolean utility) {
        public boolean healthy() {return maximum<=0 || remaining>Math.max(5,maximum/50);}
        public double score() {
            return defense*100+toughness*10+protection*15+specialProtection*3
                    +(maximum<=0?1:Math.max(0,Math.min(1,(double)remaining/maximum)))*2;
        }
    }
    public static boolean shouldEquipArmor(ArmorRating current,ArmorRating candidate) {
        if(candidate==null || candidate.binding() || candidate.utility() || candidate.defense()<=0
                || !Double.isFinite(candidate.score()) || !candidate.healthy()) return false;
        if(current==null) return true;
        if(current.binding() || current.utility()) return false;
        return !current.healthy() || candidate.score()>current.score()+0.25;
    }
    public record PositionXZ(double x,double z) {}
    public static boolean outsideEscapeRadius(PositionXZ position,List<PositionXZ> threats) {
        return outsideEscapeRadius(position,threats,8);
    }
    public static boolean outsideEscapeRadius(PositionXZ position,List<PositionXZ> threats,double radius) {
        if(!Double.isFinite(radius) || radius<8 || radius>32) throw new IllegalArgumentException("Escape radius must be 8..32");
        return threats.stream().allMatch(t->Math.pow(position.x()-t.x(),2)+Math.pow(position.z()-t.z(),2)>=radius*radius);
    }
    public static double preferredEscapeRadius(int mobCount) {return mobCount>1?16:8;}
    public static double smallerEscapeRadius(double radius) {return radius>12?12:8;}
    public static boolean shouldShield(boolean enabled,boolean usable,boolean facing,boolean stationary,
                                       boolean readyToAttack,double distance,boolean ranged) {
        return enabled && usable && facing && stationary && !readyToAttack && distance<=(ranged?8:4);
    }
    /** Count only a newly confirmed block after landing; duplicate observations cannot advance it. */
    public static final class PillarProgress {
        private int placed;
        public int placed() {return placed;}
        public boolean confirm(int step,boolean blockSeen,boolean landed) {
            if(step!=placed || placed>=3 || !blockSeen || !landed) return false;
            placed++;return true;
        }
        public boolean complete() {return placed==3;}
    }
    public record Biomes(int version,int lookAhead,Set<String> avoidBiomes) {
        public Biomes {
            if(version!=1 || lookAhead<2 || lookAhead>32 || avoidBiomes==null || avoidBiomes.size()>128)
                throw new IllegalArgumentException("Biome configuration out of bounds.");
            for(String id:avoidBiomes) if(id==null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid biome ID.");
            avoidBiomes=Set.copyOf(avoidBiomes);
        }
        public boolean avoids(String id) {return avoidBiomes.contains(id);}
    }
    public static Biomes defaultBiomes() {
        return new Biomes(1,8,Set.of("minecraft:deep_dark"));
    }
    public static Biomes loadBiomes(Path path) throws IOException {
        if(!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path,new Gson().newBuilder().setPrettyPrinting().create().toJson(defaultBiomes())+"\n");
        }
        if(Files.size(path)>32768) throw new IllegalArgumentException("danger-biomes.json too large.");
        try {
            var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(!root.keySet().equals(Set.of("version","lookAhead","avoidBiomes"))
                    || !root.get("version").toString().matches("[0-9]+") || !root.get("lookAhead").toString().matches("[0-9]+"))
                throw new IllegalArgumentException("Invalid biome configuration fields.");
            return new Gson().fromJson(root,Biomes.class);
        } catch(RuntimeException failure) {throw new IllegalArgumentException("Invalid danger-biomes.json structure or values.");}
    }
}
