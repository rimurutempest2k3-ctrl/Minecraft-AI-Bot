package dev.minecraftaibot.common;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class I18nTest {
    public static void run() throws Exception {
        Path config=Files.createTempDirectory("bot-language-test");I18n.initialize(config);
        check(Files.exists(config.resolve("languages/lang.vi")) && Files.exists(config.resolve("languages/lang.en")));
        var vi=JsonParser.parseString(I18n.catalogJson("vi")).getAsJsonObject().getAsJsonObject("messages");
        var en=JsonParser.parseString(I18n.catalogJson("en")).getAsJsonObject().getAsJsonObject("messages");
        check(vi.keySet().equals(en.keySet()) && en.size()>1000);
        check(I18n.language().equals("en"));
        check(en.keySet().stream().allMatch(key->en.get(key).getAsString().equals(key)));
        I18n.command("vi");
        check(I18n.text("Bot started.").equals("Bot đã khởi động."));
        check(I18n.text("Chest memory").equals("Bộ nhớ rương"));
        var commands=new CommandDispatcher(new BotCore());
        check(commands.execute("bot language en").equals("Language saved: en"));
        check(I18n.text("Chest memory").equals("Chest memory"));
        check(I18n.text("Collected enough minecraft:cobblestone x8").equals("Collected enough minecraft:cobblestone x8"));
        check(I18n.text("Missing ingredients x3, fuel x2.").equals("Missing ingredients x3, fuel x2."));
        check(I18n.text("bot workflow run furnace.json").equals("bot workflow run furnace.json"));
        String payload="{\"action\":\"done\",\"message\":\"Survival\"}";
        check(I18n.text("AI suggestion [groq] (not executed automatically): "+payload).endsWith(payload));
        I18n.initialize(config);check(I18n.language().equals("en"));
        commands.execute("bot language fr");check(I18n.language().equals("en"));
        // JSON escapes, literal replacement characters and markup must remain text.
        Path file=config.resolve("languages/lang.en");var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        root.getAsJsonObject("messages").addProperty("Chest memory","Chest $1 \\ <script>literal</script>");
        Files.writeString(file,new Gson().toJson(root),StandardCharsets.UTF_8);
        commands.execute("bot language reload");check(I18n.text("Chest memory").equals("Chest $1 \\ <script>literal</script>"));
        Files.writeString(file,"broken",StandardCharsets.UTF_8);
        check(commands.execute("bot language reload").startsWith("Cannot load language files"));
        check(I18n.language().equals("en") && I18n.text("Chest memory").contains("Chest $1"));
        check(Files.readString(file).equals("broken"));
        // Restore a valid catalog and the default language for unrelated checks.
        Files.writeString(file,I18n.catalogJson("en"),StandardCharsets.UTF_8);
        commands.execute("bot language en");I18n.initialize(config);
        System.out.println("All language key parity, display-only translation, command/ID/AI payload preservation, persistence, custom catalog and invalid reload checks passed.");
    }
    private static void check(boolean value) {if(!value)throw new AssertionError("Language check failed");}
}
