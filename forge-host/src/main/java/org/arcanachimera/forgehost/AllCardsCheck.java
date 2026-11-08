package org.arcanachimera.forgehost;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Owner priority 1 (2026-11-08): every card goes to Forge, so check that Forge can build every card it ships.
 * Loads each unique card in Forge's card database into a real game object (abilities, triggers, statics) and
 * reports any that throw. Also looks up the card names passed as arguments (e.g. a deck's cards) and reports
 * which Forge doesn't have. Prints ALLCARDS lines; exits 1 if any card fails to build or a named card is missing.
 */
public final class AllCardsCheck {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        ArcanaForgeHost.lazyCards = false;
        ArcanaForgeHost.initForge(args[0], null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"Check A", "Check B"}) players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi(n, new HashSet<>())));
        Game game = new Match(new GameRules(GameType.Constructed), players, "all cards").createGame();
        Player owner = game.getPlayers().get(0);

        int total = 0, built = 0, abilities = 0, aiAvoids = 0;
        Map<String, String> failed = new TreeMap<>();
        for (PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
            total++;
            try {
                Card c = CardFactory.getCard(pc, owner, game);
                abilities += c.getSpellAbilities().size() + c.getTriggers().size() + c.getStaticAbilities().size();
                c.getOracleText(); c.getType().toString();
                built++;
                if (pc.getRules().getAiHints().getRemAIDecks()) aiAvoids++;
            } catch (Throwable t) {
                failed.put(pc.getName(), t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        System.out.println("ALLCARDS built " + built + "/" + total + " unique cards (" + abilities + " abilities/triggers/statics) in "
                + (System.currentTimeMillis() - t0) / 1000 + " s; " + failed.size() + " failed; " + aiAvoids + " marked by Forge as cards its AI won't put in decks (still playable by you)");
        failed.entrySet().stream().limit(200).forEach(e -> System.out.println("ALLCARDS FAILED " + e.getKey() + " -> " + e.getValue()));

        List<String> missing = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String name = args[i].trim();
            if (name.isEmpty()) continue;
            boolean found = StaticData.instance().getCommonCards().getCard(name) != null
                    || StaticData.instance().getVariantCards().getCard(name) != null;
            System.out.println("ALLCARDS LOOKUP " + name + " -> " + (found ? "in Forge" : "NOT in Forge"));
            if (!found) missing.add(name);
        }
        boolean ok = failed.isEmpty() && missing.isEmpty();
        System.out.println("ALLCARDS RESULT " + (ok ? "PASS" : "FAIL") + (missing.isEmpty() ? "" : " missing " + missing));
        System.exit(ok ? 0 : 1);
    }
}
