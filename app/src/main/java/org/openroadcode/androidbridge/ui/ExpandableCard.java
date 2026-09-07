package org.openroadcode.androidbridge.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact service summary that reveals the existing detailed card on demand. */
public final class ExpandableCard {
    private final LinearLayout root;
    private final LinearLayout body;
    private final TextView indicator;
    private boolean expanded;

    public ExpandableCard(
            Context context,
            String title,
            String subtitle,
            int accent,
            View detailView,
            boolean initiallyExpanded) {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(
                UiTheme.dp(context, 12), UiTheme.dp(context, 10),
                UiTheme.dp(context, 10), UiTheme.dp(context, 10));
        header.setBackground(UiTheme.rounded(context, UiTheme.SURFACE, UiTheme.BORDER, 12));
        header.setClickable(true);
        header.setFocusable(true);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading = UiTheme.text(context, title, 14, accent);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(.08f);
        labels.addView(heading);

        TextView detail = UiTheme.text(context, subtitle, 11, UiTheme.MUTED);
        detail.setPadding(0, UiTheme.dp(context, 2), 0, 0);
        labels.addView(detail);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        indicator = UiTheme.text(context, "›", 25, UiTheme.SILVER);
        indicator.setGravity(Gravity.CENTER);
        header.addView(indicator, new LinearLayout.LayoutParams(
                UiTheme.dp(context, 34), UiTheme.dp(context, 40)));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.setMargins(0, UiTheme.dp(context, 6), 0, 0);
        body.addView(detailView, detailParams);

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));
        header.setOnClickListener(v -> setExpanded(!expanded));
        setExpanded(initiallyExpanded);
    }

    public View view() {
        return root;
    }

    public void setExpanded(boolean value) {
        expanded = value;
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        indicator.setText(expanded ? "⌄" : "›");
    }

    public boolean isExpanded() {
        return expanded;
    }
}
