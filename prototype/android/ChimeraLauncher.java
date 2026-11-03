package forge.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Independent Chimera-owned launcher placed into a temporary Forge Android build.
 * This is a donor-engine *device spike*, not Arcana's UI and not a working bridge.
 *
 * The actual game is always upstream Forge's own Android Main activity.
 * Never display fabricated cards, turns, state, or AI outcomes here.
 */
public final class ChimeraLauncher extends Activity {
    private static final int BG = Color.rgb(13, 18, 30);
    private static final int PANEL = Color.rgb(25, 35, 53);
    private static final int AQUA = Color.rgb(99, 226, 196);
    private static final int WHITE = Color.rgb(245, 247, 252);
    private static final int MUTED = Color.rgb(158, 174, 197);

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        parent.addView(child, p);
    }

    private void startForge() {
        Intent start = new Intent(this, forge.app.Main.class);
        start.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(start);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(24), dp(32), dp(24), dp(28));
        scroller.addView(layout);

        TextView eyebrow = text("CHIMERA  /  EXPERIMENTAL ANDROID BUILD", 12, AQUA, true);
        add(layout, eyebrow, 0);
        TextView title = text("FRANKEN\nARCANA", 44, WHITE, true);
        title.setLineSpacing(0f, 0.90f);
        add(layout, title, 12);
        TextView intro = text("One body. A borrowed Forge brain.\nA disposable experiment, not your Arcana app.", 16, MUTED, false);
        add(layout, intro, 12);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        panel.setBackground(bg(PANEL, 16));
        add(layout, panel, 28);
        add(panel, text("REAL GAME ENGINE", 13, AQUA, true), 0);
        add(panel, text("Forge's rules and AI run locally. Choose a human-versus-AI game. The Arcana-style table automatically appears when the match starts.", 16, WHITE, false), 10);
        add(panel, text("First goal: make one move, then watch Forge's AI respond.", 14, MUTED, false), 12);

        Button enter = new Button(this);
        enter.setText("START COMMANDER TRIAL  →");
        enter.setTextColor(BG);
        enter.setTextSize(16f);
        enter.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        enter.setAllCaps(false);
        enter.setBackground(bg(AQUA, 14));
        enter.setMinHeight(dp(58));
        enter.setOnClickListener(v -> startForge());
        add(layout, enter, 28);

        TextView warning = text(
                "DEVICE SPIKE 01  •  This first build uses Forge's native gameplay screen. "
                + "The custom Arcana-style game-table adapter is NOT connected yet. "
                + "No connection to Arcana–Argentum or its services.",
                12, MUTED, false);
        add(layout, warning, 22);

        setContentView(scroller);
    }
}
