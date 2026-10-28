package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A compact editable dock, horizontal at the bottom or a centered vertical carousel at either side. */
final class PersonalDock extends FrameLayout {
    static final String PLACEMENT = "dock_placement";
    static final String APPS = "personal_dock_apps";
    private final Activity activity;
    private final Config config;
    private final boolean side;
    private final boolean right;
    private final boolean motion;
    private final float density;
    private final int tileWidth;
    private final List<View> cards = new ArrayList<>();
    private LinearLayout row;
    private TextView title;
    private ScrollView vertical;
    private HorizontalScrollView horizontal;
    private int selected;

    PersonalDock(Activity activity, Config config, int placement) {
        super(activity);
        this.activity = activity;
        this.config = config;
        side = placement >= 2;
        right = placement == 3;
        motion = config.animations && !config.reducedMotion;
        density = getResources().getDisplayMetrics().density;
        tileWidth = dp(180 * new TileLayout(activity).scale / 100f);
        setClipChildren(false);
        setClipToPadding(false);
        render();
    }

    static int placement(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(Config.SETTINGS_NAME, 0);
        return TileLayout.bounded(prefs.getInt(PLACEMENT, Config.dockPlacement(activity)), 0, 3, 0);
    }

    private int dp(float value) { return Math.round(value * density); }

    private static SharedPreferences prefs(Activity activity) {
        return activity.getSharedPreferences(Config.SETTINGS_NAME, 0);
    }

    static List<String> apps(Activity activity, Config config) {
        String saved = prefs(activity).getString(APPS, null);
        if (saved == null) return new ArrayList<>(config.dockApps);
        List<String> apps = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(saved);
            for (int i = 0; i < values.length(); i++) {
                String pkg = values.optString(i, "");
                if (!pkg.isEmpty() && !apps.contains(pkg)) apps.add(pkg);
            }
        } catch (Exception ignored) { return new ArrayList<>(config.dockApps); }
        return apps;
    }

    private static void save(Activity activity, List<String> apps) {
        prefs(activity).edit().putString(APPS, new JSONArray(apps).toString()).apply();
    }

    /** Selection is committed only on Save; removing a shortcut never uninstalls an app. */
    static void manage(Activity activity, Config config, Runnable changed) {
        Map<String, String> available = new LinkedHashMap<>();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> resolved = activity.getPackageManager().queryIntentActivities(intent, 0);
        resolved.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.loadLabel(activity.getPackageManager()).toString(), b.loadLabel(activity.getPackageManager()).toString()));
        for (ResolveInfo app : resolved) {
            String pkg = app.activityInfo.packageName;
            if (!pkg.equals(activity.getPackageName()) && !config.hiddenApps.contains(pkg))
                available.put(pkg, app.loadLabel(activity.getPackageManager()).toString());
        }
        List<String> keys = new ArrayList<>(available.keySet());
        List<String> chosen = apps(activity, config);
        boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = chosen.contains(keys.get(i));
        new AlertDialog.Builder(activity).setTitle(R.string.manage_dock_apps)
                .setMultiChoiceItems(available.values().toArray(new String[0]), checked, (dialog, which, value) -> {
                    if (value && !chosen.contains(keys.get(which))) chosen.add(keys.get(which));
                    else if (!value) chosen.remove(keys.get(which));
                }).setPositiveButton(R.string.dock_save, (dialog, which) -> {
                    save(activity, chosen);
                    changed.run();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? 0x553D454E : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(focused ? 2 : 0), focused ? 0xEEFFFFFF : Color.TRANSPARENT);
        return drawable;
    }

    void refresh() { render(); }

    private void render() {
        removeAllViews();
        cards.clear();
        row = new LinearLayout(activity);
        row.setOrientation(side ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(side ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        if (side) {
            vertical = new ScrollView(activity);
            vertical.setFillViewport(false);
            vertical.setVerticalScrollBarEnabled(false);
            vertical.setClipToPadding(false);
            vertical.addView(row, new ScrollView.LayoutParams(-1, -2));
            addView(vertical, new LayoutParams(-1, -1));
            vertical.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int padding = Math.max(dp(24), (b - t) / 2 - dp(60));
                if (row.getPaddingTop() != padding) row.setPadding(dp(26), padding, dp(26), padding);
                center();
            });
        } else {
            horizontal = new HorizontalScrollView(activity);
            horizontal.setHorizontalScrollBarEnabled(false);
            horizontal.setClipToPadding(false);
            row.setPadding(dp(20), dp(16), dp(20), dp(12));
            row.setMinimumWidth(getResources().getDisplayMetrics().widthPixels - dp(96));
            GradientDrawable band = new GradientDrawable();
            band.setColor(0xEE141416);
            band.setCornerRadius(dp(28));
            row.setBackground(band);
            horizontal.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
            LinearLayout shelf = new LinearLayout(activity);
            shelf.setOrientation(LinearLayout.VERTICAL);
            shelf.setClipChildren(false);
            title = new TextView(activity);
            title.setTextColor(Color.WHITE);
            title.setTextSize(42);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setShadowLayer(dp(8), 0, dp(2), 0xAA000000);
            try { title.setTypeface(UiFont.get(activity, R.string.font_regular)); } catch (Exception ignored) { }
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
            titleParams.setMargins(dp(8), 0, dp(8), dp(24));
            shelf.addView(title, titleParams);
            shelf.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
            addView(shelf, new LayoutParams(-1, -2));
        }
        for (String pkg : apps(activity, config)) {
            Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            try {
                CharSequence label = activity.getPackageManager().getApplicationLabel(
                        activity.getPackageManager().getApplicationInfo(pkg, 0));
                LinearLayout card = card(label);
                ImageView image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setClipToOutline(true);
                image.setBackground(background(false));
                android.graphics.drawable.Drawable banner = activity.getPackageManager().getApplicationBanner(pkg);
                image.setImageDrawable(banner != null ? banner : activity.getPackageManager().getApplicationIcon(pkg));
                card.addView(image, 0, new LinearLayout.LayoutParams(tileWidth, Math.round(tileWidth * 0.56f)));
                final Intent target = launch;
                card.setOnClickListener(v -> {
                    try { activity.startActivity(target); }
                    catch (Exception unavailable) { Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show(); }
                });
                card.setOnLongClickListener(v -> { menu(pkg); return true; });
                card.setTag(pkg);
                addCard(card);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        }
        LinearLayout add = card(getResources().getString(R.string.manage_dock_apps));
        TextView plus = new TextView(activity);
        plus.setText("+");
        plus.setTextSize(32);
        plus.setTextColor(Color.WHITE);
        plus.setGravity(Gravity.CENTER);
        add.addView(plus, 0, new LinearLayout.LayoutParams(tileWidth, Math.round(tileWidth * 0.56f)));
        add.setOnClickListener(v -> manage(activity, config, this::render));
        addCard(add);
        selected = Math.max(0, Math.min(selected, cards.size() - 1));
        post(() -> focus(selected));
    }

    private LinearLayout card(CharSequence label) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(background(false));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setContentDescription(label);
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        if (!side) text.setVisibility(View.GONE);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(text, new LinearLayout.LayoutParams(tileWidth, dp(22)));
        return card;
    }

    private void addCard(LinearLayout card) {
        int index = cards.size();
        cards.add(card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(side ? 0 : 8), dp(side ? 10 : 0), dp(side ? 0 : 8), dp(side ? 10 : 0));
        row.addView(card, params);
        card.setAlpha(side ? 0.6f : 1f);
        card.setScaleX(side ? 0.78f : 1f);
        card.setScaleY(side ? 0.78f : 1f);
        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? (side ? 1.12f : 1.06f) : (side ? 0.78f : 1f);
            v.animate().cancel();
            v.animate().scaleX(scale).scaleY(scale).alpha(focused ? 1f : side ? 0.6f : 1f)
                    .setDuration(motion ? 160 : 0).start();
            v.setBackground(background(focused));
            if (focused) {
                GradientDrawable frost = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x55FFFFFF, 0x08FFFFFF});
                frost.setCornerRadius(dp(14));
                v.setForeground(frost);
            } else v.setForeground(null);
            if (focused) {
                selected = index;
                if (title != null) title.setText(v.getContentDescription());
                post(this::center);
            }
        });
        card.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (v.getTag() instanceof String) menu((String) v.getTag());
                    else manage(activity, config, this::render);
                }
                return true;
            }
            boolean previous = code == (side ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_LEFT);
            boolean next = code == (side ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_RIGHT);
            if (previous || next) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (!side && ((previous && selected == 0) || (next && selected == cards.size() - 1))) return true;
                    focus(Math.floorMod(selected + (next ? 1 : -1), cards.size()));
                }
                return true;
            }
            if (side && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && (right ? code == KeyEvent.KEYCODE_DPAD_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT))
                    activity.findViewById(R.id.btnSettings).requestFocus();
                return true;
            }
            if (!side && code == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN)
                    activity.findViewById(R.id.btnSettings).requestFocus();
                return true;
            }
            if (!side && code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return false;
        });
    }

    void focusCurrent() { focus(selected); }

    void focus(int index) {
        if (!cards.isEmpty()) cards.get(Math.max(0, Math.min(index, cards.size() - 1))).requestFocus();
    }

    private void center() {
        if (cards.isEmpty()) return;
        View card = cards.get(Math.min(selected, cards.size() - 1));
        if (side) {
            int y = Math.max(0, card.getTop() + card.getHeight() / 2 - vertical.getHeight() / 2);
            if (motion) vertical.smoothScrollTo(0, y); else vertical.scrollTo(0, y);
        } else if (card.hasFocus()) {
            int x = Math.max(0, card.getLeft() + card.getWidth() / 2 - horizontal.getWidth() / 2);
            if (motion) horizontal.smoothScrollTo(x, 0); else horizontal.scrollTo(x, 0);
        }
    }

    private void menu(String pkg) {
        new AlertDialog.Builder(activity).setTitle(R.string.manage_dock_apps)
                .setItems(new String[]{getResources().getString(R.string.dock_remove),
                                getResources().getString(R.string.dock_earlier), getResources().getString(R.string.dock_later),
                                getResources().getString(R.string.dock_add)}, (dialog, which) -> {
                    if (which == 3) { manage(activity, config, this::render); return; }
                    List<String> apps = apps(activity, config);
                    int index = apps.indexOf(pkg);
                    if (index < 0) return;
                    if (which == 0) apps.remove(index);
                    else {
                        int target = Math.max(0, Math.min(apps.size() - 1, index + (which == 1 ? -1 : 1)));
                        java.util.Collections.swap(apps, index, target);
                        selected = target;
                    }
                    save(activity, apps);
                    render();
                }).show();
    }
}
