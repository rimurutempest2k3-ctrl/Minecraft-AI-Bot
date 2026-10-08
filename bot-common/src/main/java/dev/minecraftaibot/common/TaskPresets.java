package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** JSON catalog and ordered prerequisite interpreter shared by all local mining tasks. */
public final class TaskPresets {
    private TaskPresets() {}
    private static Path file;
    private static final Set<String> FIELDS=Set.of("pickaxe","logs","planks","sticks","table_available","nearby_table","table_open","planks_needed","logs_needed");
    public record Task(String label,String goal,String resultItem,int quantity,String preparation) {}
    public record Cell(int row,int column,String ingredient) {}
    public record Recipe(String output,int count,int width,List<Cell> cells) {}
    public record Step(String action,String recipe) {}
    public record Condition(String field,String op,String value) {
        boolean matches(Map<String,Integer> s) {int a=s.getOrDefault(field,0),b=number(value,s);return op.equals("lt")?a<b:a>=b;}
    }
    public record Rule(List<Condition> when,Step step) {}
    public record Budget(List<Condition> when,int planks) {}
    public record Preparation(int planksPerLog,List<Budget> budget,List<Rule> rules) {
        public Map<String,Integer> evaluate(Map<String,Integer> observed) {
            Map<String,Integer> s=new HashMap<>(observed);int need=0;
            for(Budget b:budget) if(b.when.stream().allMatch(c->c.matches(s))) need=Math.addExact(need,b.planks);
            s.put("planks_needed",need);s.put("logs_needed",Math.max(1,(Math.max(0,need-s.getOrDefault("planks",0))+planksPerLog-1)/planksPerLog));return Map.copyOf(s);
        }
        public Step next(Map<String,Integer> observed) {
            var s=evaluate(observed);for(Rule r:rules) if(r.when.stream().allMatch(c->c.matches(s))) return r.step;
            throw invalid("Không có bước phù hợp");
        }
    }
    public record Catalog(int version,Map<String,Task> tasks,Map<String,Recipe> recipes,Map<String,Preparation> preparations) {
        public Task require(String id) {var task=tasks.get(id);if(task==null) throw invalid("Không có nhiệm vụ: "+id);return task;}
    }
    public static synchronized void initialize(Path path) throws IOException {
        installDefault(path,"/tasks/local-tasks.json");
        file=path;
        installDefault(path.resolveSibling("minecraft-recipes-26.2.json"),"/tasks/minecraft-recipes-26.2.json");
        installDefault(path.resolveSibling("furnace-rules-26.2.json"),"/tasks/furnace-rules-26.2.json");
    }
    private static void installDefault(Path path,String resource) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        if(!Files.exists(path)) try(InputStream in=TaskPresets.class.getResourceAsStream(resource)) {
            if(in==null) throw new IOException("Missing task catalog");Files.copy(in,path);
        }
    }
    public static synchronized Catalog catalog() {
        try {
            if(file!=null) {if(Files.size(file)>262144) throw invalid("File quá lớn");return parse(Files.readString(file,StandardCharsets.UTF_8));}
            try(InputStream in=TaskPresets.class.getResourceAsStream("/tasks/local-tasks.json")) {
                if(in==null) throw invalid("Thiếu file mặc định");return parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        } catch(IOException e) {throw invalid("Không đọc được local-tasks.json");}
    }
    public static Catalog parse(String json) {
        try {
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();validateKeys(root,Set.of("version","tasks","recipes","preparations"));
            Catalog c=new Gson().fromJson(root,Catalog.class);
            for(var e:root.getAsJsonObject("recipes").entrySet()) for(var cell:e.getValue().getAsJsonObject().getAsJsonArray("cells"))
                validateKeys(cell.getAsJsonObject(),Set.of("row","column","ingredient"));
            for(var e:root.getAsJsonObject("preparations").entrySet()) {
                JsonObject p=e.getValue().getAsJsonObject();validateKeys(p,Set.of("planksPerLog","budget","rules"));
                for(var b:p.getAsJsonArray("budget")) {validateKeys(b.getAsJsonObject(),Set.of("when","planks"));validateConditions(b.getAsJsonObject());}
                for(var r:p.getAsJsonArray("rules")) {validateKeys(r.getAsJsonObject(),Set.of("when","step"));validateConditions(r.getAsJsonObject());validateKeys(r.getAsJsonObject().getAsJsonObject("step"),Set.of("action","recipe"));}
            }
            if(c.version!=1 || c.tasks.isEmpty() || c.tasks.size()>100 || c.recipes.size()>100 || c.preparations.size()>100) throw invalid("Phiên bản/số định nghĩa không hợp lệ");
            for(var e:c.recipes.entrySet()) {
                name(e.getKey());Recipe r=e.getValue();validateKeys(root.getAsJsonObject("recipes").getAsJsonObject(e.getKey()),Set.of("output","count","width","cells"));
                if(!r.output.equals("$log_planks")) item(r.output);if(r.count<1 || r.count>64 || r.width<2 || r.width>3 || r.cells.isEmpty()) throw invalid("Công thức không hợp lệ");
                Set<Integer> cells=new HashSet<>();for(Cell cell:r.cells) {
                    if(cell.row<0 || cell.row>=r.width || cell.column<0 || cell.column>=r.width || !cells.add(cell.row*r.width+cell.column)) throw invalid("Ô công thức ngoài lưới/trùng nhau");
                    if(!Set.of("$log","#minecraft:planks").contains(cell.ingredient)) item(cell.ingredient);
                }
            }
            for(var e:c.preparations.entrySet()) {
                name(e.getKey());Preparation p=e.getValue();if(p.planksPerLog<1 || p.planksPerLog>64 || p.rules.isEmpty() || p.rules.size()>100 || p.budget.size()>100) throw invalid("Chuỗi chuẩn bị không hợp lệ");
                for(Budget b:p.budget) {conditions(b.when);if(b.planks<0 || b.planks>2304 || b.when.stream().anyMatch(x->x.value.startsWith("$") || Set.of("planks_needed","logs_needed").contains(x.field))) throw invalid("Ngân sách nguyên liệu không hợp lệ");}
                for(Rule r:p.rules) {
                    conditions(r.when);Step step=r.step;if(!Set.of("mine_goal","collect_logs","craft","place_table","open_table").contains(step.action)) throw invalid("Hành động chưa hỗ trợ: "+step.action);
                    if(step.action.equals("craft") ? step.recipe==null || !c.recipes.containsKey(step.recipe):step.recipe!=null) throw invalid("Tham chiếu công thức không hợp lệ");
                }
                if(!p.rules.getLast().when.isEmpty()) throw invalid("Quy tắc cuối phải có when=[]");
            }
            for(var e:c.tasks.entrySet()) {
                name(e.getKey());if(Set.of("check","list","inventory","stone").contains(e.getKey())) throw invalid("ID dành riêng");Task t=e.getValue();item(t.goal);item(t.resultItem);
                if(t.label==null || t.label.isBlank() || t.label.length()>160 || t.quantity<1 || t.quantity>2304 || t.preparation!=null && !c.preparations.containsKey(t.preparation)) throw invalid("Nhiệm vụ/tham chiếu chuẩn bị không hợp lệ");
                validateKeys(root.getAsJsonObject("tasks").getAsJsonObject(e.getKey()),Set.of("label","goal","resultItem","quantity","preparation"));
            }
            exactNumbers(root);
            return c;
        } catch(IllegalArgumentException e) {throw invalid(e.getMessage());}
        catch(RuntimeException e) {throw invalid("Sai cấu trúc local-tasks.json");}
    }
    private static void exactNumbers(JsonElement e) {
        if(e.isJsonObject()) for(var field:e.getAsJsonObject().entrySet()) {
            if(Set.of("version","row","column","width","count","quantity","planks","planksPerLog").contains(field.getKey()) && field.getValue().isJsonPrimitive() && !field.getValue().getAsString().matches("[0-9]+")) throw invalid("Giá trị phải là số nguyên: "+field.getKey());exactNumbers(field.getValue());
        } else if(e.isJsonArray()) for(var child:e.getAsJsonArray()) exactNumbers(child);
    }
    private static void validateKeys(JsonObject obj,Set<String> allowed) {for(String key:obj.keySet()) if(!allowed.contains(key)) throw invalid("Thuộc tính chưa hỗ trợ: "+key);}
    private static void validateConditions(JsonObject obj) {for(var c:obj.getAsJsonArray("when")) validateKeys(c.getAsJsonObject(),Set.of("field","op","value"));}
    private static void conditions(List<Condition> list) {
        if(list.size()>20) throw invalid("Quá nhiều điều kiện");for(Condition c:list) {
            if(!FIELDS.contains(c.field) || !Set.of("lt","gte").contains(c.op)) throw invalid("Điều kiện chưa hỗ trợ");
            if(c.value.startsWith("$")) {if(!FIELDS.contains(c.value.substring(1))) throw invalid("Biến không tồn tại");}else number(c.value,Map.of());
        }
    }
    private static int number(String value,Map<String,Integer> state) {if(value.startsWith("$"))return state.getOrDefault(value.substring(1),0);int n;try{n=Integer.parseInt(value);}catch(NumberFormatException e){throw invalid("Ngưỡng không hợp lệ");}if(n<0 || n>2304)throw invalid("Ngưỡng ngoài giới hạn");return n;}
    private static void name(String id) {if(!id.matches("[a-z][a-z0-9_]{0,39}"))throw invalid("ID không hợp lệ");}
    private static void item(String id) {if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw invalid("Mã block/vật phẩm không hợp lệ");}
    private static IllegalArgumentException invalid(String text) {return new IllegalArgumentException("Nhiệm vụ JSON: "+text);}
    public static String list() {
        try {StringJoiner names=new StringJoiner(", ");catalog().tasks.forEach((id,t)->names.add(id+" ["+t.quantity+"] ("+t.label+")"));return "Nhiệm vụ JSON: check, inventory, "+names+". Dùng bot task <tên> [số lượng 1-2304].";}
        catch(IllegalArgumentException e){return e.getMessage();}
    }
    public static String miningCommand(String input) {
        String[] parts=input.split(" ");if(parts[0].equals("stone"))throw new IllegalArgumentException("Đá cuội là cobblestone. Dùng bot task cobblestone [số lượng]. Nhiệm vụ thu stone bằng nung/Silk Touch chưa hỗ trợ.");
        if(parts.length>2)throw invalid("Sai cú pháp");Task t=catalog().require(parts[0]);int quantity=t.quantity;
        if(parts.length==2)try{quantity=Integer.parseInt(parts[1]);}catch(NumberFormatException e){throw invalid("Số lượng phải là số nguyên");}
        if(quantity<1 || quantity>2304)throw invalid("Số lượng phải là 1-2304");return "bot mine "+t.goal+" "+quantity;
    }
}
