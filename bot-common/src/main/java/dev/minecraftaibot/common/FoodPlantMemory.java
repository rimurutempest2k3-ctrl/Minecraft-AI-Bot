package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Observations are scoped to one world file; unloaded positions are never removed. */
public final class FoodPlantMemory {
    public record Plant(String dimension,int x,int y,int z,String block,String food,boolean ready,long lastSeen) {}
    private final Map<String,Plant> plants=new LinkedHashMap<>();
    private final Path file;
    private final String world;
    private static String key(String dimension,int x,int y,int z) {return dimension+":"+x+":"+y+":"+z;}
    public FoodPlantMemory(Path file,String world) throws IOException {
        this.file=file;this.world=world;
        if(!Files.exists(file))return;
        try {
            if(Files.size(file)>4_000_000)throw new IllegalArgumentException();
            var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(root.get("schemaVersion").getAsInt()!=1 || !root.get("world").getAsString().equals(world))throw new IllegalArgumentException();
            for(var entry:root.getAsJsonArray("plants")) {
                var plant=new Gson().fromJson(entry,Plant.class);
                if(plant.dimension()==null || plant.block()==null || plant.food()==null || plants.size()>=8192)throw new IllegalArgumentException();
                plants.put(key(plant.dimension(),plant.x(),plant.y(),plant.z()),plant);
            }
        }catch(RuntimeException e) {throw new IOException("Invalid food plant memory; existing file preserved.",e);}
    }
    public synchronized boolean observe(Plant plant) {
        String key=key(plant.dimension(),plant.x(),plant.y(),plant.z());
        var old=plants.get(key);
        if(old!=null && old.block().equals(plant.block()) && old.ready()==plant.ready() && old.food().equals(plant.food()) && plant.lastSeen()-old.lastSeen()<60_000)return false;
        plants.put(key,plant);
        if(plants.size()>8192)plants.remove(plants.values().stream().min(Comparator.comparingLong(Plant::lastSeen)).map(p->key(p.dimension(),p.x(),p.y(),p.z())).orElseThrow());
        return true;
    }
    public synchronized boolean remove(String dimension,int x,int y,int z) {return plants.remove(key(dimension,x,y,z))!=null;}
    public synchronized List<Plant> plants(String dimension) {return plants.values().stream().filter(p->p.dimension().equals(dimension)).toList();}
    public synchronized void save() throws IOException {
        var root=new JsonObject();root.addProperty("schemaVersion",1);root.addProperty("world",world);root.add("plants",new Gson().toJsonTree(plants.values()));
        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"plants-",".tmp");
        try {
            Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally {Files.deleteIfExists(temporary);}
    }
}
