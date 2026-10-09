package dev.minecraftaibot.common;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Shared display catalogs. Commands, game IDs, internal state and AI data stay untranslated. */
public final class I18n {
    private I18n() {}
    private record Catalog(Map<String,String> messages,Pattern matcher) {}
    private record State(String language,Map<String,Catalog> catalogs) {}
    private static volatile State state=new State("en",bundled());
    private static Path config;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Map<String,Catalog> bundled() {
        var result=new HashMap<String,Catalog>();
        for(String language:List.of("vi","en"))try(var in=I18n.class.getResourceAsStream("/languages/lang."+language)) {
            if(in==null)throw new IOException("Missing lang."+language);
            result.put(language,parse(new String(in.readAllBytes(),StandardCharsets.UTF_8),language));
        } catch(IOException e) {throw new IllegalStateException("Missing language resources",e);}
        return Map.copyOf(result);
    }
    private static Catalog parse(String source,String language) {
        var root=JsonParser.parseString(source).getAsJsonObject();
        if(!root.keySet().equals(Set.of("version","language","messages")) || !root.get("version").toString().equals("1")
                || !root.get("language").getAsString().equals(language))throw new IllegalArgumentException("Invalid language catalog");
        var messages=new HashMap<String,String>();
        for(var entry:root.getAsJsonObject("messages").entrySet()) {
            if(entry.getKey().isEmpty() || entry.getKey().length()>8192 || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString() || entry.getValue().getAsString().length()>8192)
                throw new IllegalArgumentException("Invalid language entry");
            messages.put(entry.getKey(),entry.getValue().getAsString());
        }
        if(messages.size()>5000)throw new IllegalArgumentException("Too many language entries");
        String alternatives=messages.entrySet().stream().filter(e->!e.getKey().equals(e.getValue()))
                .map(Map.Entry::getKey).sorted(Comparator.comparingInt(String::length).reversed().thenComparing(s->s))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return new Catalog(Map.copyOf(messages),Pattern.compile(alternatives.isEmpty()?"(?!)":alternatives));
    }
    public static synchronized void initialize(Path directory) throws IOException {
        var catalogs=new HashMap<>(bundled());Path languages=directory.resolve("languages");Files.createDirectories(languages);
        for(String language:List.of("vi","en")) {
            Path file=languages.resolve("lang."+language);
            if(!Files.exists(file))try(var in=I18n.class.getResourceAsStream("/languages/lang."+language)){Files.copy(in,file);}
            if(Files.size(file)>1048576)throw new IOException("Language file exceeds 1 MB");
            try {
                var messages=new HashMap<>(catalogs.get(language).messages());
                messages.putAll(parse(Files.readString(file,StandardCharsets.UTF_8),language).messages());
                catalogs.put(language,parse(JSON.toJson(Map.of("version",1,"language",language,"messages",messages)),language));
            }
            catch(RuntimeException failure) {throw new IOException("Invalid lang."+language,failure);}
        }
        if(!catalogs.get("vi").messages().keySet().equals(catalogs.get("en").messages().keySet()))throw new IOException("Language keys do not match");
        String selected="en";Path settings=directory.resolve("language.properties");
        if(Files.exists(settings)) {
            if(Files.size(settings)>4096)throw new IOException("Language settings too large");
            var properties=new Properties();try(var reader=Files.newBufferedReader(settings,StandardCharsets.UTF_8)){properties.load(reader);}
            selected=properties.getProperty("language","en");
            if(!Set.of("vi","en").contains(selected))throw new IOException("Invalid language selection");
        }
        config=directory;state=new State(selected,Map.copyOf(catalogs));
    }
    public static String language() {return state.language();}
    public static synchronized String command(String input) {
        if(input.equals("status"))return "Language: "+language();
        if(input.equals("reload")) {
            try {if(config!=null)initialize(config);return "Language files reloaded.";}
            catch(IOException e){return "Cannot load language files; current configuration preserved.";}
        }
        if(!Set.of("vi","en").contains(input))return "Commands: bot language vi/en/status/reload.";
        try {
            if(config!=null) {
                Path target=config.resolve("language.properties"),temporary=Files.createTempFile(config,"language-",".tmp");
                try {
                    Files.writeString(temporary,"language="+input+"\n",StandardCharsets.UTF_8);
                    try {Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                    catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
                } finally {Files.deleteIfExists(temporary);}
            }
            state=new State(input,state.catalogs());return "Language saved: "+input;
        } catch(IOException e){return "Cannot save language; current configuration preserved.";}
    }
    public static String text(String original) {
        if(original==null)return "";var current=state;var catalog=current.catalogs().get(current.language());
        // AI payloads are data, not display labels; never translate inside their JSON.
        int payload=original.indexOf('{');
        if(payload>=0)return text(original.substring(0,payload))+original.substring(payload);
        return catalog.matcher().matcher(original).replaceAll(m->java.util.regex.Matcher.quoteReplacement(catalog.messages().get(m.group())));
    }
    public static String catalogJson(String language) {
        var catalog=state.catalogs().get(language);if(catalog==null)throw new IllegalArgumentException("Invalid language");
        return JSON.toJson(Map.of("version",1,"language",language,"messages",catalog.messages()));
    }
}
