package forge.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.badlogic.gdx.Gdx;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.screens.match.MatchController;
import forge.screens.match.MatchScreen;
import forge.screens.match.views.VCardDisplayArea.CardAreaPanel;
import forge.screens.match.views.VPrompt;
import forge.util.ThreadUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Disposable FrankenArcana table. Lives in a temporary upstream Forge Android
 * checkout, never in Arcana. It uses Forge's actual MatchController and GameView,
 * then presents real, viewer-masked match information in an Arcana-style layout.
 *
 * Real interaction: controller.selectCard / controller.selectButtonOk / Cancel.
 * An accessible "FORGE CONTROLS" fallback keeps the donor's complete prompts
 * available for targets, mana payments, mulligans and unusual choices.
 *
 * This is deliberately not an image, screenshot, fake game, or Arcana source
 * mirror. Match state is read ONLY on Forge's LibGDX game thread.
 */
public final class ArcanaTableOverlay extends FrameLayout {
    private static final int INK = Color.rgb(16, 22, 21);       // Arcana darkTableColors
    private static final int PANEL = Color.rgb(25, 34, 32);
    private static final int RAISED = Color.rgb(38, 51, 47);
    private static final int MINT = Color.rgb(137, 227, 176);
    private static final int WHITE = Color.rgb(234, 241, 237);
    private static final int MUTED = Color.rgb(164, 177, 171);
    private static final int GOLD = Color.rgb(255, 209, 102);
    private static final int RED = Color.rgb(233, 151, 139);

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout body;
    private final Button restore;
    private volatile boolean running = true;
    private boolean showHand = false;
    private boolean minimized = false;
    private Snapshot last;
    private String lastFingerprint = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (Gdx.app == null) {
                main.postDelayed(this, 500);
                return;
            }
            // Forge's trackable GameView is NOT safe to walk from Android's UI thread.
            Gdx.app.postRunnable(() -> {
                Snapshot snapshot;
                try {
                    snapshot = fromRealForgeGame();
                } catch (Throwable t) {
                    // Never fabricate state if Forge rejects a snapshot; keep its native UI usable.
                    android.util.Log.e("FrankenArcana", "Live Forge state read failed", t);
                    snapshot = null;
                }
                final Snapshot result = snapshot;
                main.post(() -> {
                    if (!running) return;
                    if (result == null) {
                        if (getVisibility() != GONE) setVisibility(GONE);
                        last = null;
                        lastFingerprint = "";
                    } else {
                        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
                        last = result;
                        String key = result.fingerprint() + ":" + showHand + ":" + minimized;
                        if (!key.equals(lastFingerprint)) {
                            lastFingerprint = key;
                            render(result);
                        }
                    }
                });
            });
            main.postDelayed(this, 500);
        }
    };

    private static final class Card {
        final int id;
        final String name;
        final boolean tapped;
        final boolean commander;
        Card(int id, String name, boolean tapped, boolean commander) {
            this.id = id;
            this.name = name;
            this.tapped = tapped;
            this.commander = commander;
        }
    }

    private static final class Seat {
        final String name;
        final int life;
        final int handCount;
        final boolean human;
        final boolean active;
        final boolean priority;
        final List<Card> board;
        final List<Card> commanders;
        final List<Card> hand;
        Seat(String name, int life, int handCount, boolean human, boolean active,
             boolean priority, List<Card> board, List<Card> commanders, List<Card> hand) {
            this.name = name;
            this.life = life;
            this.handCount = handCount;
            this.human = human;
            this.active = active;
            this.priority = priority;
            this.board = board;
            this.commanders = commanders;
            this.hand = hand;
        }
    }

    private static final class Snapshot {
        final int turn;
        final String phase;
        final String winner;
        final int stack;
        final boolean over;
        final List<Seat> seats;
        final String ok;
        final String cancel;
        final boolean okEnabled;
        final boolean cancelEnabled;
        Snapshot(int turn, String phase, String winner, int stack, boolean over,
                 List<Seat> seats, String ok, String cancel,
                 boolean okEnabled, boolean cancelEnabled) {
            this.turn = turn;
            this.phase = phase;
            this.winner = winner;
            this.stack = stack;
            this.over = over;
            this.seats = seats;
            this.ok = ok;
            this.cancel = cancel;
            this.okEnabled = okEnabled;
            this.cancelEnabled = cancelEnabled;
        }
        String fingerprint() {
            StringBuilder b = new StringBuilder().append(turn).append(':')
                    .append(phase).append(':').append(stack).append(':')
                    .append(over).append(':').append(winner).append(':')
                    .append(ok).append(':').append(cancel).append(':')
                    .append(okEnabled).append(':').append(cancelEnabled);
            for (Seat p : seats) {
                b.append('|').append(p.name).append('/').append(p.life)
                 .append('/').append(p.active).append('/').append(p.priority)
                 .append('/').append(p.handCount);
                for (Card c : p.board) b.append('/').append(c.id).append(c.tapped ? 'T' : 'U');
                for (Card c : p.commanders) b.append('/').append(c.id).append('C');
                if (p.human) for (Card c : p.hand) b.append('/').append(c.id);
            }
            return b.toString();
        }
    }

    private static Card sanitize(CardView c, PlayerView viewer) {
        if (c == null) return null;
        // Never reveal a face-down card or hidden opponent card through this overlay.
        boolean shown = !c.isFaceDown() && c.canBeShownTo(viewer);
        String name = shown ? c.getName() : "FACE DOWN";
        if (name == null || name.isEmpty()) name = "UNKNOWN CARD";
        return new Card(c.getId(), name, c.isTapped(), c.isCommander());
    }

    private static List<Card> convert(Iterable<CardView> cards, PlayerView viewer) {
        List<Card> result = new ArrayList<>();
        if (cards != null) for (CardView card : cards) {
            Card c = sanitize(card, viewer);
            if (c != null) result.add(c);
        }
        return result;
    }

    private static Snapshot fromRealForgeGame() {
        // Never cover Forge's deck-selection/lobby screens with an old match.
        if (!(forge.Forge.getCurrentScreen() instanceof MatchScreen)) return null;
        MatchController controller = MatchController.instance;
        if (controller == null) return null;
        GameView game = controller.getGameView();
        if (game == null || game.getPlayers() == null || game.getPlayers().isEmpty()) return null;

        PlayerView viewer = null;
        for (PlayerView player : game.getPlayers()) {
            if (!player.isAI()) { viewer = player; break; }
        }
        // An AI-vs-AI spectator game does not qualify as the human-interaction trial.
        if (viewer == null) return null;

        PlayerView current = game.getPlayerTurn();
        List<Seat> seats = new ArrayList<>();
        for (PlayerView p : game.getPlayers()) {
            boolean human = p == viewer;
            seats.add(new Seat(
                    p.getLobbyPlayerName(), p.getLife(), p.getHand().size(),
                    human, p == current, p.getHasPriority(),
                    convert(p.getBattlefield(), viewer),
                    convert(p.getCommand(), viewer),
                    human ? convert(p.getHand(), viewer) : Collections.emptyList()
            ));
        }

        String ok = "OK", cancel = "PASS";
        boolean okEnabled = false, cancelEnabled = false;
        MatchScreen screen = MatchController.getView();
        if (screen != null) {
            VPrompt prompt = screen.getActivePrompt();
            if (prompt != null) {
                if (prompt.getBtnOk() != null) {
                    ok = prompt.getBtnOk().getText();
                    okEnabled = prompt.getBtnOk().isEnabled();
                }
                if (prompt.getBtnCancel() != null) {
                    cancel = prompt.getBtnCancel().getText();
                    cancelEnabled = prompt.getBtnCancel().isEnabled();
                }
            }
        }
        return new Snapshot(game.getTurn(),
                game.getPhase() == null ? "WAITING" : game.getPhase().nameForUi,
                game.isGameOver() ? game.getWinningPlayerName() : "",
                game.getStack() == null ? 0 : game.getStack().size(),
                game.isGameOver(), seats, ok, cancel, okEnabled, cancelEnabled);
    }

    public static void install(Activity activity) {
        if (activity.getWindow().getDecorView().findViewWithTag("chimera-live-table") != null) return;
        ArcanaTableOverlay overlay = new ArcanaTableOverlay(activity);
        overlay.setTag("chimera-live-table");
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.start();
    }

    private ArcanaTableOverlay(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Color.TRANSPARENT);
        setVisibility(GONE);
        setFocusable(false);

        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(INK);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        restore = new Button(activity);
        restore.setText("ARCANA TABLE");
        restore.setAllCaps(false);
        restore.setTextColor(INK);
        restore.setTextSize(11);
        restore.setBackground(shape(MINT, 10));
        restore.setOnClickListener(v -> {
            minimized = false;
            if (last != null) render(last);
        });
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(130), dp(46),
                Gravity.TOP | Gravity.END);
        p.topMargin = dp(20);
        p.rightMargin = dp(10);
        addView(restore, p);
        restore.setVisibility(GONE);
    }

    private void start() {
        main.post(refresh);
    }

    @Override protected void onDetachedFromWindow() {
        running = false;
        main.removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value == null ? "" : value);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private void add(LinearLayout parent, View view, int padTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(padTop);
        parent.addView(view, params);
    }

    private Button button(String value, int background, int foreground, Runnable action) {
        Button b = new Button(activity);
        b.setText(value == null || value.trim().isEmpty() ? "WAITING" : value);
        b.setTextSize(12);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(background, 9));
        b.setMinHeight(dp(46));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void onForgeThread(Runnable action) {
        if (Gdx.app != null) Gdx.app.postRunnable(() -> {
            try { action.run(); } catch (Throwable t) {
                android.util.Log.e("FrankenArcana", "Real Forge action rejected", t);
            }
        });
    }

    private void selectCard(int id) {
        // Native Forge card taps cross from LibGDX's render thread into
        // ThreadUtil's game pool. Calling selectCard directly on the render
        // thread (as the original Chimera overlay did) silently fails for
        // decisions that wait for the game-thread input.
        onForgeThread(() -> {
            MatchController controller = MatchController.instance;
            if (controller == null) return;
            GameView game = controller.getGameView();
            if (game == null || game.isGameOver()) return;
            PlayerView viewer = null;
            for (PlayerView player : game.getPlayers()) {
                if (!player.isAI()) { viewer = player; break; }
            }
            if (viewer == null) return;

            CardView selected = null;
            for (CardView card : viewer.getHand()) {
                if (card.getId() == id && card.canBeShownTo(viewer)) {
                    selected = card;
                    break;
                }
            }
            if (selected == null) for (CardView card : viewer.getCommand()) {
                if (card.getId() == id) {
                    selected = card;
                    break;
                }
            }
            if (selected == null) {
                for (PlayerView player : game.getPlayers()) {
                    for (CardView card : player.getBattlefield()) {
                        if (card.getId() == id) {
                            selected = card;
                            break;
                        }
                    }
                    if (selected != null) break;
                }
            }
            if (selected == null) return;

            // Use the same card selection and stacked/attached-card handling
            // as Forge's VCardDisplayArea.CardAreaPanel.tap().
            final CardAreaPanel nativePanel = CardAreaPanel.get(selected);
            ThreadUtil.invokeInGameThread(() -> {
                try {
                    if (!nativePanel.selectCard(false)) {
                        android.util.Log.i("FrankenArcana",
                                "Card tap did not produce a legal action: " + id);
                        main.post(() -> Toast.makeText(activity,
                                "No action for that card right now. Use FORGE CONTROLS for more choices.",
                                Toast.LENGTH_SHORT).show());
                    }
                } catch (Throwable t) {
                    android.util.Log.e("FrankenArcana",
                            "Native Forge card selection failed", t);
                    main.post(() -> Toast.makeText(activity,
                            "Card action failed. Try FORGE CONTROLS.",
                            Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    private void realPrompt(boolean ok) {
        // Trigger the *actual* FButton rather than bypassing it: Forge's
        // Cancel button can mean Undo or End Turn, depending on the prompt.
        onForgeThread(() -> {
            MatchController controller = MatchController.instance;
            if (controller == null) return;
            GameView game = controller.getGameView();
            if (game == null || game.isGameOver()) return;
            MatchScreen screen = MatchController.getView();
            if (screen == null) return;
            VPrompt prompt = screen.getActivePrompt();
            if (prompt == null) return;
            boolean handled = ok
                    ? prompt.getBtnOk() != null && prompt.getBtnOk().trigger()
                    : prompt.getBtnCancel() != null && prompt.getBtnCancel().trigger();
            if (!handled) {
                main.post(() -> Toast.makeText(activity,
                        "That action is not available yet.",
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private View cardChip(Card card, int width) {
        LinearLayout pane = new LinearLayout(activity);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pane.setBackground(shape(card.tapped ? RAISED : PANEL, 8));
        pane.setPadding(dp(5), dp(8), dp(5), dp(7));
        TextView name = label(card.name, 11, WHITE, true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(3);
        pane.addView(name, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(84)));
        TextView note = label(card.tapped ? "TAPPED" : card.commander ? "COMMANDER" : "CARD",
                9, card.tapped ? GOLD : MINT, true);
        note.setGravity(Gravity.CENTER);
        pane.addView(note);
        pane.setOnClickListener(v -> selectCard(card.id));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), dp(122));
        lp.rightMargin = dp(6);
        pane.setLayoutParams(lp);
        return pane;
    }

    private void cardRow(LinearLayout parent, List<Card> cards, String emptyText) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(8));
        if (cards.isEmpty()) row.addView(label(emptyText, 12, MUTED, false));
        else for (Card card : cards) row.addView(cardChip(card, 90));
        scroll.addView(row);
        add(parent, scroll, 3);
    }

    private void section(LinearLayout parent, String title, List<Card> cards) {
        add(parent, label(title, 10, MINT, true), 16);
        cardRow(parent, cards, "No permanents");
    }

    private void render(Snapshot s) {
        body.removeAllViews();
        if (minimized) {
            body.setVisibility(GONE);
            restore.setVisibility(VISIBLE);
            return;
        }
        restore.setVisibility(GONE);
        body.setVisibility(VISIBLE);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(14), dp(12), dp(12));
        top.setBackgroundColor(PANEL);
        add(body, top, 0);
        TextView brand = label("ARCANA TABLE   ·   FORGE EXPERIMENT", 15, MINT, true);
        add(top, brand, 0);
        String phase = s.phase.toUpperCase(Locale.US);
        add(top, label("TURN " + s.turn + "   /   " + phase + "   /   STACK " + s.stack,
                12, GOLD, true), 8);
        LinearLayout tracker = new LinearLayout(activity);
        String[] names = {"BEGIN", "MAIN 1", "COMBAT", "MAIN 2", "END"};
        int current = phase.contains("COMBAT") || phase.contains("ATTACK") || phase.contains("BLOCK")
                ? 2 : phase.contains("POSTCOMBAT") ? 3 :
                phase.contains("PRECOMBAT") || phase.contains("MAIN") ? 1 :
                phase.contains("END") || phase.contains("CLEANUP") ? 4 : 0;
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView t = label(names[i], 9, index == current ? INK : MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(shape(index == current ? MINT : RAISED, 5));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(28), 1);
            p.rightMargin = dp(3);
            tracker.addView(t, p);
        }
        add(top, tracker, 9);

        ScrollView scrollingBoard = new ScrollView(activity);
        scrollingBoard.setFillViewport(true);
        LinearLayout game = new LinearLayout(activity);
        game.setOrientation(LinearLayout.VERTICAL);
        game.setPadding(dp(12), dp(10), dp(12), dp(16));
        scrollingBoard.addView(game);
        body.addView(scrollingBoard, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1));

        for (Seat seat : s.seats) {
            if (seat.human) continue;
            add(game, label((seat.active ? "▶ " : "") + seat.name + "   ♥ " + seat.life
                    + "   HAND " + seat.handCount + (seat.priority ? "  ·  PRIORITY" : ""),
                    14, seat.active ? GOLD : WHITE, true), 5);
            if (!seat.commanders.isEmpty()) cardRow(game, seat.commanders, "");
            section(game, "OPPONENT BATTLEFIELD", seat.board);
        }

        Seat human = null;
        for (Seat seat : s.seats) if (seat.human) { human = seat; break; }
        if (human != null) {
            add(game, label("YOU  ·  " + human.name + "   ♥ " + human.life
                    + (human.priority ? "    YOUR PRIORITY" : ""),
                    16, MINT, true), 18);
            if (!human.commanders.isEmpty()) cardRow(game, human.commanders, "");
            section(game, "YOUR BATTLEFIELD", human.board);
            if (showHand) {
                add(game, label("YOUR HAND   (" + human.handCount + ")", 12, GOLD, true), 12);
                cardRow(game, human.hand, "Your hand is empty");
            }
        }
        if (s.over) {
            add(game, label("GAME OVER  ·  " + s.winner, 17, GOLD, true), 20);
        }

        LinearLayout footer = new LinearLayout(activity);
        footer.setPadding(dp(8), dp(8), dp(8), dp(14));
        footer.setBackgroundColor(PANEL);
        footer.setOrientation(LinearLayout.VERTICAL);
        add(body, footer, 0);

        LinearLayout promptRow = new LinearLayout(activity);
        Button ok = button(s.ok, MINT, INK, () -> realPrompt(true));
        ok.setEnabled(s.okEnabled && !s.over);
        Button cancel = button(s.cancel, RAISED, WHITE, () -> realPrompt(false));
        cancel.setEnabled(s.cancelEnabled && !s.over);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(48), 1);
        left.rightMargin = dp(5);
        promptRow.addView(ok, left);
        promptRow.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        add(footer, promptRow, 0);

        LinearLayout nav = new LinearLayout(activity);
        Button handButton = button("HAND " + (human == null ? "" : human.handCount),
                RAISED, WHITE, () -> { showHand = true; render(s); });
        Button tableButton = button("TABLE", RAISED, WHITE,
                () -> { showHand = false; render(s); });
        Button forgeButton = button("FORGE CONTROLS", RAISED, GOLD,
                () -> { minimized = true; render(s); });
        for (Button b : new Button[]{handButton, tableButton, forgeButton}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
            p.rightMargin = dp(4);
            nav.addView(b, p);
        }
        add(footer, nav, 7);
    }
}
