package org.openroadcode.androidbridge.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiTheme {
    public static final int BG = Color.rgb(6, 16, 24);
    public static final int SURFACE = Color.rgb(11, 24, 33);
    public static final int SURFACE_RAISED = Color.rgb(16, 34, 46);
    public static final int BORDER = Color.rgb(36, 64, 79);
    public static final int TEXT = Color.rgb(243, 247, 249);
    public static final int MUTED = Color.rgb(147, 164, 174);
    public static final int SILVER = Color.rgb(184, 194, 200);
    public static final int BLUE = Color.rgb(22, 139, 209);
    public static final int GREEN = Color.rgb(132, 206, 31);
    public static final int RED = Color.rgb(241, 90, 22);

    private UiTheme() {}

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    public static GradientDrawable rounded(Context context, int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 16), dp(context, 12), dp(context, 16));
        card.setBackground(rounded(context, SURFACE, BORDER, 14));
        return card;
    }

    public static TextView text(Context context, String value, float sizeSp, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        return text;
    }

    public static Button actionButton(
            Context context, String label, int color, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setLetterSpacing(.08f);
        button.setAllCaps(false);
        setButtonColor(context, button, color);
        button.setOnClickListener(listener);
        return button;
    }

    public static void setButtonColor(Context context, Button button, int color) {
        button.setBackground(rounded(context, color, color, 9));
    }
}
