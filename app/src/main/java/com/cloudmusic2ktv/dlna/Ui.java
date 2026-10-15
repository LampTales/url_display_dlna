package com.cloudmusic2ktv.dlna;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small programmatic UI helpers shared by the two screens. */
final class Ui {
    static final int RED = Color.rgb(205, 35, 45);
    static final int BLACK = Color.rgb(28, 28, 30);
    static final int MUTED = Color.rgb(105, 105, 110);
    static final int SURFACE = Color.WHITE;
    static final int BACKGROUND = Color.rgb(248, 248, 248);
    static final int BORDER = Color.rgb(225, 225, 228);

    private Ui() { }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(int color, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    static TextView label(Context context, String text, float size, int color) {
        TextView view = new TextView(context); view.setText(text); view.setTextSize(size);
        view.setTextColor(color); return view;
    }

    static TextView heading(Context context, String text, float size) {
        TextView view = label(context, text, size, BLACK);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }

    static Button button(Context context, String text, View.OnClickListener listener, boolean primary) {
        Button button = new Button(context); button.setText(text); button.setAllCaps(false);
        button.setTextSize(15); button.setTextColor(primary ? Color.WHITE : BLACK);
        button.setGravity(Gravity.CENTER); button.setMinHeight(dp(context, 46));
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        button.setBackground(background(primary ? RED : SURFACE, dp(context, 8), primary ? RED : BORDER, dp(context, 1)));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setOnClickListener(listener); return button;
    }

    static TextView listRow(Context context, String text, View.OnClickListener listener) {
        TextView row = label(context, text, 15, BLACK);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(context, 48));
        row.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        row.setBackgroundColor(SURFACE);
        row.setOnClickListener(listener);
        return row;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16); card.setPadding(pad, pad, pad, pad);
        card.setBackground(background(SURFACE, dp(context, 16), BORDER, dp(context, 1)));
        return card;
    }

    static void margin(View view, int left, int top, int right, int bottom) {
        if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
            params.setMargins(left, top, right, bottom); view.setLayoutParams(params);
        }
    }
}
