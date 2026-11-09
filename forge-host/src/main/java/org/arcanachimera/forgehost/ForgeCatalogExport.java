package org.arcanachimera.forgehost;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.item.PaperCard;
import forge.model.FModel;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Writes Forge's cards that Arcana's own card library lacks, in Arcana's library-line shape, so Arcana's deck import,
 * deck editor search, deck checks and card details know every card Forge can play (owner, Nov 8: "severely hobbled
 * by the Argentum restrictions"). Read only by Arcana's catalog (DeckSupport) and picture lookup, never by Argentum's
 * engine, so the lines carry no Argentum card script. Run at APK build time; nothing is committed.
 *
 * Usage: ForgeCatalogExport <forge-gui dir> <out dir> <arcana index.tsv>...
 * Writes <out>/forge-cards.jsonl and <out>/forge-cards.index.tsv (name, then "card" or "basic").
 */
public final class ForgeCatalogExport {
    public static void main(String[] args) throws Exception {
        ArcanaForgeHost.lazyCards = false;
        ArcanaForgeHost.initForge(args[0], null);
        Path out = Path.of(args[1]);
        Set<String> known = new HashSet<>();
        java.util.Map<String, String> images = new java.util.HashMap<>(); // name -> Scryfall picture (scryfall_images.py)
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("images=")) {
                Path f = Path.of(args[i].substring(7));
                if (Files.exists(f)) for (String line : Files.readAllLines(f)) {
                    String[] kv = line.split("\t", 2);
                    if (kv.length == 2) images.putIfAbsent(kv[0].toLowerCase(Locale.ROOT), kv[1].trim());
                }
                continue;
            }
            for (String line : Files.readAllLines(Path.of(args[i]))) {
                String name = line.split("\t", 2)[0].trim();
                if (!name.isEmpty()) { known.add(name.toLowerCase(Locale.ROOT)); known.add(front(name).toLowerCase(Locale.ROOT)); }
            }
        }
        int pictured = 0;
        Predicate<PaperCard> commanderLegal = null;
        try { commanderLegal = FModel.getFormats().get("Commander").getFilterRules(); } catch (Exception ignored) { }

        int written = 0, skipped = 0;
        Set<String> seen = new HashSet<>();
        try (BufferedWriter jsonl = Files.newBufferedWriter(out.resolve("forge-cards.jsonl"));
             BufferedWriter index = Files.newBufferedWriter(out.resolve("forge-cards.index.tsv"))) {
            for (PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
                String name = pc.getName();
                String key = name.toLowerCase(Locale.ROOT);
                if (known.contains(key) || known.contains(front(name).toLowerCase(Locale.ROOT)) || !seen.add(key)) { skipped++; continue; }
                CardRules rules = pc.getRules();
                ICardFace main = rules.getMainPart();
                StringBuilder b = new StringBuilder("{\"name\":").append(JSONObject.quote(name)); // name first: Arcana's picture lookup expects it
                b.append(",\"manaCost\":").append(JSONObject.quote(cost(main.getManaCost() == null ? "" : main.getManaCost().toString())));
                b.append(",\"typeLine\":").append(JSONObject.quote(typeLine(rules)));
                b.append(",\"oracleText\":").append(JSONObject.quote(rules.getOracleText() == null ? "" : rules.getOracleText().replace("\\n", "\n")));
                if (main.getPower() != null && main.getToughness() != null)
                    b.append(",\"creatureStats\":{\"power\":").append(stat(main.getPower())).append(",\"toughness\":").append(stat(main.getToughness())).append('}');
                if (pc.getEdition() != null) b.append(",\"setCode\":").append(JSONObject.quote(pc.getEdition()));
                String img = images.get(key);
                if (img == null) img = images.get(front(name).toLowerCase(Locale.ROOT));
                b.append(",\"metadata\":{\"collectorNumber\":").append(JSONObject.quote(String.valueOf(pc.getCollectorNumber())))
                        .append(",\"rarity\":\"").append(rarity(pc)).append('"');
                // Arcana's picture lookup reads "imageUri" from the card's metadata block (CardImages.bundledImage)
                if (img != null) { b.append(",\"imageUri\":").append(JSONObject.quote(img)); pictured++; }
                b.append('}');
                if (commanderLegal != null) b.append(",\"legalFormats\":[").append(commanderLegal.test(pc) ? "\"COMMANDER\"" : "").append(']');
                b.append(",\"colorIdentityOverride\":[").append(colors(rules.getColorIdentity())).append(']');
                if (rules.getOtherPart() != null && rules.getOtherPart().getName() != null)
                    b.append(",\"backFace\":{\"name\":").append(JSONObject.quote(rules.getOtherPart().getName())).append('}');
                b.append(",\"source\":\"forge\"}");
                jsonl.write(b.toString()); jsonl.newLine();
                index.write(name + "\t" + (rules.getType().isBasicLand() ? "basic" : "card")); index.newLine();
                written++;
            }
        }
        System.out.println("FORGE CATALOG: " + written + " Forge cards added to Arcana's library (" + skipped + " already in it), "
                + pictured + " with pictures (" + images.size() + " Scryfall names)");
    }

    private static String front(String name) { int i = name.indexOf(" // "); return i < 0 ? name : name.substring(0, i); }

    /** Forge's "2 G G" or "{2}{G}{G}" → Arcana's "{2}{G}{G}". */
    static String cost(String forge) {
        String s = forge.trim();
        if (s.isEmpty() || s.equals("no cost")) return "";
        if (s.contains("{")) return s.replace(" ", "");
        StringBuilder b = new StringBuilder();
        for (String part : s.split("\\s+")) if (!part.isEmpty()) b.append('{').append(part).append('}');
        return b.toString();
    }

    private static String typeLine(CardRules rules) {
        String t = rules.getMainPart().getType().toString().replace(" - ", " — ");
        if (rules.getOtherPart() != null && rules.getOtherPart().getType() != null)
            t += " // " + rules.getOtherPart().getType().toString().replace(" - ", " — ");
        return t;
    }

    private static String stat(String v) { return v.matches("-?\\d+") ? v : JSONObject.quote(v); }

    private static String rarity(PaperCard pc) {
        String r = pc.getRarity() == null ? "COMMON" : pc.getRarity().name();
        return switch (r) {
            case "MythicRare", "MYTHIC_RARE", "Mythic" -> "MYTHIC";
            case "Rare", "RARE" -> "RARE";
            case "Uncommon", "UNCOMMON" -> "UNCOMMON";
            default -> "COMMON";
        };
    }

    private static String colors(ColorSet c) {
        StringBuilder b = new StringBuilder();
        if (c.hasWhite()) b.append("\"WHITE\",");
        if (c.hasBlue()) b.append("\"BLUE\",");
        if (c.hasBlack()) b.append("\"BLACK\",");
        if (c.hasRed()) b.append("\"RED\",");
        if (c.hasGreen()) b.append("\"GREEN\",");
        return b.length() == 0 ? "" : b.substring(0, b.length() - 1);
    }
}
