package org.deltavault.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class SecureViews {
    static final int INK = Color.rgb(20, 44, 40), ACCENT = Color.rgb(0, 107, 95), MUTED = Color.rgb(80, 102, 96);
    private final Activity activity;
    SecureViews(Activity activity) { this.activity = activity; }
    int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    LinearLayout column() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        return box;
    }
    TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0, dp(6), 0, dp(6)); text.setSaveEnabled(false);
        return text;
    }
    TextView title(String value) {
        TextView text = text(value, 26, INK);
        text.setTypeface(null, Typeface.BOLD);
        return text;
    }
    Button button(String value, Runnable action) {
        Button button = new Button(activity);
        button.setText(value); button.setAllCaps(false); button.setTextColor(ACCENT);
        button.setOnClickListener(view -> action.run());
        button.setSaveEnabled(false);
        return button;
    }
    EditText input(String hint, boolean password) {
        EditText input = new EditText(activity) {
            @Override public boolean onTextContextMenuItem(int id) {
                if (id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText) return false;
                return super.onTextContextMenuItem(id);
            }
        };
        input.setHint(hint); input.setTextColor(INK); input.setTextSize(16);
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        secure(input);
        return input;
    }
    static void secure(EditText input) {
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(input.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            public boolean onCreateActionMode(ActionMode mode, Menu menu) { return false; }
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            public void onDestroyActionMode(ActionMode mode) { }
        });
        input.setLongClickable(false);
    }
    GradientDrawable card() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE); shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), Color.rgb(216, 229, 224));
        return shape;
    }
}
