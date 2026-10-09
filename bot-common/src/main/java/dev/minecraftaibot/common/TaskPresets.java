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
    private record CachedWorkflow(String stamp,Workflow workflow) {}
    private static final Map<String,CachedWorkflow> workflowCache=new HashMap<>();
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
            throw invalid("No matching step");
        }
    }
    public record Catalog(int version,Map<String,Task> tasks,Map<String,Recipe> recipes,Map<String,Preparation> preparations) {
        public Task require(String id) {var task=tasks.get(id);if(task==null) throw invalid("Task not found: "+id);return task;}
    }
    public static synchronized void initialize(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        file=path;
        workflowCache.clear();
        for(String name:List.of("minecraft-recipes-26.2.json","furnace-rules-26.2.json","task-recipes.json","mining-rules.json"))
            installCoreData(path.toAbsolutePath().getParent(),name);
        ProductionPlan.initialize(path.resolveSibling("production-tasks.json"));
        Path directory=workflowDirectory();Files.createDirectories(directory);
    }
    /** Preserve user data when upgrading; bundled defaults are only for missing core data. */
    private static void installCoreData(Path config,String name) throws IOException {
        Path path=config.resolve("core-data").resolve(name);
        Files.createDirectories(path.getParent());
        if(Files.exists(path)) return;
        Path legacy=config.resolve(name);
        if(Files.exists(legacy)) {Files.copy(legacy,path);return;}
        if(name.equals("mining-rules.json") && Files.exists(config.resolve("local-tasks.json"))) {
            Path old=config.resolve("local-tasks.json");
            if(Files.size(old)>262144) throw new IOException("local-tasks.json exceeds 256 KB");
            try {
                var catalog=JsonParser.parseString(Files.readString(old,StandardCharsets.UTF_8)).getAsJsonObject();
                if(catalog.has("recipes") && catalog.has("preparations")) {
                    JsonObject rules=new JsonObject();rules.addProperty("version",1);
                    rules.add("recipes",catalog.get("recipes"));rules.add("preparations",catalog.get("preparations"));
                    Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(rules),StandardCharsets.UTF_8);return;
                }
            } catch(RuntimeException e) {throw new IOException("Could not migrate preparation rules from local-tasks.json",e);}
        }
        try(InputStream in=TaskPresets.class.getResourceAsStream("/core-data/"+name)) {
            if(in==null) throw new IOException("Missing core data: "+name);Files.copy(in,path);
        }
    }
    public record Supply(String label,String item,int count,String action,String role) {}
    public record Starter(List<Supply> steps,Map<String,Recipe> recipes) {}
    public record Goal(String item,int count) {
        public boolean satisfied(int present) {return present>=count;}
    }
    public record Workflow(String filename,String title,String description,Starter plan,String error,Goal goal) {
        public Workflow(String filename,String title,String description,Starter plan,String error) {this(filename,title,description,plan,error,null);}
    }
    public static Path workflowDirectory() {
        if(file==null) throw invalid("Task directory not initialized");
        return file.toAbsolutePath().getParent().resolve("tasks");
    }
    public static Workflow parseWorkflow(String filename,String json) {
        try {return parseWorkflowContent(filename,json);}
        catch(RuntimeException e) {throw invalid(filename+": "+e.getMessage());}
    }
    private static Workflow parseWorkflowContent(String filename,String json) {
        var root=JsonParser.parseString(json).getAsJsonObject();validateKeys(root,Set.of("version","title","description","steps","recipes","goal"));
        Goal goal=null;
        if(root.has("goal")) {
            var target=root.remove("goal").getAsJsonObject();validateKeys(target,Set.of("item","count"));exactNumbers(target);
            goal=new Gson().fromJson(target,Goal.class);item(goal.item());
            if(goal.count()<1 || goal.count()>64)throw invalid("Target quantity must be 1-64");
        }
        String title=root.has("title")?root.remove("title").getAsString():filename;
        String description=root.has("description")?root.remove("description").getAsString():"";
        if(title.isBlank() || title.length()>160 || description.length()>1000) throw invalid("Invalid task title/description");
        JsonObject recipes=sharedRecipes();
        if(root.has("recipes")) root.getAsJsonObject("recipes").entrySet().forEach(e->recipes.add(e.getKey(),e.getValue()));
        root.add("recipes",recipes);
        Starter plan=parseStarter(root.toString());
        var last=plan.steps().getLast();
        if(goal!=null && (!goal.item().equals(last.item()) || goal.count()!=last.count() || last.role()!=null))
            throw invalid("Goal must match the last step's item and count, without a role");
        return new Workflow(filename,title,description,plan,null,goal);
    }
    public static Workflow workflow(String filename) {
        if(filename==null || !filename.matches("[a-z0-9][a-z0-9_-]{0,63}\\.json")) throw invalid("Filename must use lowercase letters, digits, -/_ and end in .json");
        try {
            Path directory=workflowDirectory();Path path=directory.resolve(filename);
            if(!directory.toRealPath().startsWith(directory.getParent().toRealPath()) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().getParent().equals(directory.toRealPath())) throw invalid("File is outside the task directory");
            if(Files.size(path)>262144) throw invalid("Task file exceeds 256 KB");
            return parseWorkflow(filename,Files.readString(path,StandardCharsets.UTF_8));
        } catch(IOException e) {throw invalid("Cannot read file: "+filename);}
    }
    public static synchronized List<Workflow> workflows() {
        try(var paths=Files.list(workflowDirectory())) {
            var result=new ArrayList<Workflow>();
            Path sharedFile=file.resolveSibling("core-data").resolve("task-recipes.json");
            String shared=Files.exists(sharedFile)?Files.getLastModifiedTime(sharedFile).toString()+Files.size(sharedFile):"absent";
            for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".json")).sorted().limit(64).toList()) {
                String filename=path.getFileName().toString();
                try {
                    String stamp=shared+Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS)+Files.size(path);
                    var cached=workflowCache.get(filename);
                    Workflow loaded=cached!=null && cached.stamp().equals(stamp)?cached.workflow():workflow(filename);
                    workflowCache.put(filename,new CachedWorkflow(stamp,loaded));result.add(loaded);
                }
                catch(Exception e) {result.add(new Workflow(filename,filename,"",null,e.getMessage()));}
            }
            workflowCache.keySet().retainAll(result.stream().map(Workflow::filename).toList());
            return List.copyOf(result);
        } catch(IOException e) {throw invalid("Cannot read task directory");}
    }
    public static boolean huntable(String entity,boolean baby,boolean named) {
        return !baby && !named && Set.of("minecraft:cow","minecraft:pig","minecraft:sheep","minecraft:chicken").contains(entity);
    }
    public static int foodBatch(int missing,int available) {
        if(missing<0 || available<0) throw invalid("Negative food quantity");
        return Math.min(64,Math.min(missing,available));
    }
    public static Starter starter() {
        return workflow("starter-kit.json").plan();
    }
    /** Optional external recipe library. A workflow can also supply its own recipes. */
    private static JsonObject sharedRecipes() {
        if(file==null) throw invalid("Task directory not initialized");
        try {
            Path path=file.resolveSibling("core-data").resolve("task-recipes.json");
            if(!Files.exists(path)) return new JsonObject();
            if(Files.size(path)>262144) throw invalid("task-recipes.json exceeds 256 KB");
            var root=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
            validateKeys(root,Set.of("version","recipes"));
            if(!root.get("version").toString().equals("1")) throw invalid("Invalid recipe version");
            return root.getAsJsonObject("recipes").deepCopy();
        } catch(IOException | RuntimeException e) {throw invalid("task-recipes.json: "+e.getMessage());}
    }
    public static Starter parseStarter(String json) {
        try {
            var root=JsonParser.parseString(json).getAsJsonObject();
            validateKeys(root,Set.of("version","steps","recipes"));exactNumbers(root);
            if(root.get("version").getAsInt()!=1) throw invalid("Invalid starter version");
            Starter s=new Gson().fromJson(root,Starter.class);
            if(s.steps()==null || s.steps().isEmpty() || s.steps().size()>64) throw invalid("Invalid starter step count");
            // Reuse the existing strict recipe validator.
            var wrapper=JsonParser.parseString("{\"version\":1,\"tasks\":{\"kit\":{\"label\":\"kit\",\"goal\":\"minecraft:stone\",\"resultItem\":\"minecraft:cobblestone\",\"quantity\":1}},\"preparations\":{}}").getAsJsonObject();
            wrapper.add("recipes",root.get("recipes"));parse(wrapper.toString());
            for(var step:root.getAsJsonArray("steps")) validateKeys(step.getAsJsonObject(),Set.of("label","item","count","action","role"));
            for(Supply step:s.steps()) {
                item(step.item());
                if(step.label()==null || step.label().isBlank() || step.label().length()>160 || step.count()<1 || step.count()>64
                        || !Set.of("mine","craft","food").contains(step.action()) || step.role()!=null && !Set.of("logs","pickaxe","axe","sword","food").contains(step.role())) throw invalid("Invalid starter step");
                if(step.action().equals("food") && !"food".equals(step.role())) throw invalid("Hunt/cook steps require role food");
                if(step.action().equals("craft") && s.recipes().values().stream().noneMatch(r->r.output().equals(step.item()))) throw invalid("Missing starter recipe: "+step.item());
            }
            return s;
        } catch(RuntimeException e) {throw invalid("starter-kit.json: "+e.getMessage());}
    }
    public static synchronized Catalog catalog() {
        try {
            if(file==null) throw invalid("Task directory not initialized");
            if(Files.size(file)>262144) throw invalid("File too large");
            var root=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            Path rulesFile=file.resolveSibling("core-data").resolve("mining-rules.json");
            if(Files.size(rulesFile)>262144) throw invalid("mining-rules.json exceeds 256 KB");
            var rules=JsonParser.parseString(Files.readString(rulesFile,StandardCharsets.UTF_8)).getAsJsonObject();
            validateKeys(rules,Set.of("version","recipes","preparations"));
            if(!rules.get("version").toString().equals("1")) throw invalid("Invalid preparation rules version");
            root.add("recipes",rules.get("recipes"));root.add("preparations",rules.get("preparations"));
            return parse(root.toString());
        } catch(IOException e) {throw invalid("Cannot read local-tasks.json");}
        catch(RuntimeException e) {throw invalid("Catalog/preparation rules: "+e.getMessage());}
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
            if(c.version!=1 || c.tasks.isEmpty() || c.tasks.size()>100 || c.recipes.size()>100 || c.preparations.size()>100) throw invalid("Invalid version/definition count");
            for(var e:c.recipes.entrySet()) {
                name(e.getKey());Recipe r=e.getValue();validateKeys(root.getAsJsonObject("recipes").getAsJsonObject(e.getKey()),Set.of("output","count","width","cells"));
                if(!r.output.equals("$log_planks")) item(r.output);if(r.count<1 || r.count>64 || r.width<2 || r.width>3 || r.cells.isEmpty()) throw invalid("Invalid recipe");
                Set<Integer> cells=new HashSet<>();for(Cell cell:r.cells) {
                    if(cell.row<0 || cell.row>=r.width || cell.column<0 || cell.column>=r.width || !cells.add(cell.row*r.width+cell.column)) throw invalid("Recipe cell outside grid or duplicated");
                    if(!Set.of("$log","#minecraft:planks").contains(cell.ingredient)) item(cell.ingredient);
                }
            }
            for(var e:c.preparations.entrySet()) {
                name(e.getKey());Preparation p=e.getValue();if(p.planksPerLog<1 || p.planksPerLog>64 || p.rules.isEmpty() || p.rules.size()>100 || p.budget.size()>100) throw invalid("Invalid preparation chain");
                for(Budget b:p.budget) {conditions(b.when);if(b.planks<0 || b.planks>2304 || b.when.stream().anyMatch(x->x.value.startsWith("$") || Set.of("planks_needed","logs_needed").contains(x.field))) throw invalid("Invalid ingredient budget");}
                for(Rule r:p.rules) {
                    conditions(r.when);Step step=r.step;if(!Set.of("mine_goal","collect_logs","craft","place_table","open_table").contains(step.action)) throw invalid("Unsupported action: "+step.action);
                    if(step.action.equals("craft") ? step.recipe==null || !c.recipes.containsKey(step.recipe):step.recipe!=null) throw invalid("Invalid recipe reference");
                }
                if(!p.rules.getLast().when.isEmpty()) throw invalid("The last rule must have when=[]");
            }
            for(var e:c.tasks.entrySet()) {
                name(e.getKey());if(Set.of("check","list","inventory","stone").contains(e.getKey())) throw invalid("Reserved ID");Task t=e.getValue();item(t.goal);item(t.resultItem);
                if(t.label==null || t.label.isBlank() || t.label.length()>160 || t.quantity<1 || t.quantity>2304 || t.preparation!=null && !c.preparations.containsKey(t.preparation)) throw invalid("Invalid task/preparation reference");
                validateKeys(root.getAsJsonObject("tasks").getAsJsonObject(e.getKey()),Set.of("label","goal","resultItem","quantity","preparation"));
            }
            exactNumbers(root);
            return c;
        } catch(IllegalArgumentException e) {throw invalid(e.getMessage());}
        catch(RuntimeException e) {throw invalid("Invalid local-tasks.json structure");}
    }
    private static void exactNumbers(JsonElement e) {
        if(e.isJsonObject()) for(var field:e.getAsJsonObject().entrySet()) {
            if(Set.of("version","row","column","width","count","quantity","planks","planksPerLog").contains(field.getKey()) && field.getValue().isJsonPrimitive() && !field.getValue().getAsString().matches("[0-9]+")) throw invalid("Value must be an integer: "+field.getKey());exactNumbers(field.getValue());
        } else if(e.isJsonArray()) for(var child:e.getAsJsonArray()) exactNumbers(child);
    }
    private static void validateKeys(JsonObject obj,Set<String> allowed) {for(String key:obj.keySet()) if(!allowed.contains(key)) throw invalid("Unsupported property: "+key);}
    private static void validateConditions(JsonObject obj) {for(var c:obj.getAsJsonArray("when")) validateKeys(c.getAsJsonObject(),Set.of("field","op","value"));}
    private static void conditions(List<Condition> list) {
        if(list.size()>20) throw invalid("Too many conditions");for(Condition c:list) {
            if(!FIELDS.contains(c.field) || !Set.of("lt","gte").contains(c.op)) throw invalid("Unsupported condition");
            if(c.value.startsWith("$")) {if(!FIELDS.contains(c.value.substring(1))) throw invalid("Unknown variable");}else number(c.value,Map.of());
        }
    }
    private static int number(String value,Map<String,Integer> state) {if(value.startsWith("$"))return state.getOrDefault(value.substring(1),0);int n;try{n=Integer.parseInt(value);}catch(NumberFormatException e){throw invalid("Invalid threshold");}if(n<0 || n>2304)throw invalid("Threshold out of bounds");return n;}
    private static void name(String id) {if(!id.matches("[a-z][a-z0-9_]{0,39}"))throw invalid("Invalid ID");}
    private static void item(String id) {if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw invalid("Invalid block/item ID");}
    private static IllegalArgumentException invalid(String text) {return new IllegalArgumentException("JSON task: "+text);}
    public static String list() {
        try {StringJoiner names=new StringJoiner(", ");catalog().tasks.forEach((id,t)->names.add(id+" ["+t.quantity+"] ("+t.label+")"));return "JSON tasks: check, inventory, "+names+". Use bot task <name> [quantity 1-2304].";}
        catch(IllegalArgumentException e){return e.getMessage();}
    }
    public static String miningCommand(String input) {
        String[] parts=input.split(" ");if(parts[0].equals("stone"))throw new IllegalArgumentException("Cobblestone means minecraft:cobblestone. Use bot task cobblestone [quantity]. Collecting stone via smelting/Silk Touch is not supported yet.");
        if(parts.length>3 || parts.length==3 && !Set.of("total","additional").contains(parts[2]))throw invalid("Use <name> [quantity] [total|additional]");Task t=catalog().require(parts[0]);int quantity=t.quantity;
        if(parts.length>=2)try{quantity=Integer.parseInt(parts[1]);}catch(NumberFormatException e){throw invalid("Quantity must be an integer");}
        if(quantity<1 || quantity>2304)throw invalid("Quantity must be 1-2304");return "bot mine "+t.goal+" "+quantity;
    }
}
