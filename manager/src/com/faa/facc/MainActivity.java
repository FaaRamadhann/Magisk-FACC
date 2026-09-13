package com.faa.facc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FACC Manager — Faa App Cache Cleaner.
 * Style: Light Blue Sea, utility bersih. 3 tab: Home / Apps / Settings.
 *
 * Mode AMAN: hanya isi direktori cache (tanpa pm clear, tanpa hapus data).
 * Data & aksi via CLI module (root): su -c "facc --scan --json" dkk.
 * Tanpa root/module: tetap tampilkan daftar aplikasi (ukuran cache "-").
 */
public class MainActivity extends Activity {

    // ---------- Warna Light Blue Sea ----------
    private static final int SEA_BG = 0xFFEAF6FB;
    private static final int CARD_BG = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF0EA5C9;
    private static final int ACCENT_DARK = 0xFF0284C7;
    private static final int TEXT_DARK = 0xFF0B3040;
    private static final int TEXT_GRAY = 0xFF5B7B8A;
    private static final int DOT_GREEN = 0xFF22C55E;
    private static final int DARK_BG = 0xFF0B1C26;
    private static final int DARK_CARD = 0xFF122836;
    private static final int DARK_TEXT = 0xFFE8F4F8;

    private static final String PREFS = "facc";
    private static final String GITHUB_URL = "https://github.com/FaaRamadhann/Magisk-FACC";

    // ---------- Model ----------
    private static class AppEntry {
        String pkg;
        String label;
        long bytes; // -1 = tidak diketahui (tanpa root)
        boolean isSystem;
    }

    // ---------- State ----------
    private SharedPreferences prefs;
    private PackageManager pm;
    private boolean darkMode;
    private int accentColor = ACCENT;

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> shownApps = new ArrayList<AppEntry>();
    private final Map<String, Boolean> checked = new HashMap<String, Boolean>();
    private boolean selectAll = true;
    private String query = "";
    private int filterMode = 0; // 0=all 1=user 2=system
    private int sortMode = 0;   // 0=cache 1=nama 2=last scanned
    private long totalBytes = -1;
    private boolean rooted = false;
    private boolean moduleOk = false;
    private boolean scanning = false;

    private AppAdapter adapter;

    // ---------- View ----------
    private LinearLayout root;
    private LinearLayout pageHome;
    private LinearLayout pageApps;
    private LinearLayout pageSettings;
    private Button navHome;
    private Button navApps;
    private Button navSettings;

    private TextView homeTotal;
    private TextView homeSub;
    private TextView homeStats;
    private LinearLayout homeTopList;
    private LinearLayout homeScanBox;
    private ProgressBar homeScanBar;
    private TextView homeScanText;
    private TextView homeNotice;

    private EditText searchBox;
    private CheckBox selectAllBox;
    private TextView appsCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        pm = getPackageManager();
        resolveTheme();
        buildUi();
        applyTheme();
        showPage(0);
        refreshAppsList();
        updateHome();
        // Deteksi root + module di background
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean r = RootShell.hasRoot();
                final boolean m = r && RootShell.hasFacc();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        rooted = r;
                        moduleOk = m;
                        updateNotice();
                        if (!m) {
                            loadLocalApps();
                        }
                    }
                });
                if (prefs.getBoolean("auto_scan", true)) {
                    scanCache();
                } else if (m) {
                    scanCache();
                }
            }
        }).start();
    }

    // ================= TEMA =================

    private void resolveTheme() {
        String t = prefs.getString("theme", "system");
        if ("dark".equals(t)) {
            darkMode = true;
        } else if ("light".equals(t)) {
            darkMode = false;
        } else {
            int night = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            darkMode = (night == Configuration.UI_MODE_NIGHT_YES);
        }
        if (prefs.getBoolean("dynamic_color", false)
                && Build.VERSION.SDK_INT >= 31) {
            try {
                accentColor = getResources().getColor(
                        android.R.color.system_accent1_600, getTheme());
            } catch (Exception e) {
                accentColor = ACCENT;
            }
        } else {
            accentColor = ACCENT;
        }
    }

    private void applyTheme() {
        int bg = darkMode ? DARK_BG : SEA_BG;
        int card = darkMode ? DARK_CARD : CARD_BG;
        root.setBackgroundColor(bg);
        paintCards(root, card);
        int txt = darkMode ? DARK_TEXT : TEXT_DARK;
        homeTotal.setTextColor(txt);
        homeStats.setTextColor(darkMode ? DARK_TEXT : TEXT_GRAY);
    }

    private void paintCards(View v, int card) {
        if (v instanceof LinearLayout) {
            Object tag = v.getTag();
            if ("card".equals(tag)) {
                v.setBackgroundColor(card);
            }
            LinearLayout ll = (LinearLayout) v;
            for (int i = 0; i < ll.getChildCount(); i++) {
                paintCards(ll.getChildAt(i), card);
            }
        } else if (v instanceof ScrollView) {
            ScrollView sv = (ScrollView) v;
            if (sv.getChildCount() > 0) {
                paintCards(sv.getChildAt(0), card);
            }
        }
    }

    // ================= UI =================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SEA_BG);

        root.addView(buildHeader());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        content.setLayoutParams(cp);

        pageHome = buildHomePage();
        pageApps = buildAppsPage();
        pageSettings = buildSettingsPage();
        content.addView(pageHome);
        content.addView(pageApps);
        content.addView(pageSettings);
        root.addView(content);

        root.addView(buildBottomNav());
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), dp(14), dp(16), dp(6));

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("FACC");
        t1.setTextSize(22);
        t1.setTextColor(darkMode ? DARK_TEXT : TEXT_DARK);
        try {
            t1.setTypeface(t1.getTypeface(), android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        TextView t2 = new TextView(this);
        t2.setText("App Cache Cleaner");
        t2.setTextSize(13);
        t2.setTextColor(TEXT_GRAY);
        title.addView(t1);
        title.addView(t2);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(lp);
        h.addView(title);

        Button gear = new Button(this);
        gear.setText("\u2699");
        gear.setTextSize(20);
        gear.setBackgroundColor(Color.TRANSPARENT);
        gear.setTextColor(darkMode ? DARK_TEXT : TEXT_DARK);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(2);
            }
        });
        h.addView(gear);
        return h;
    }

    // ---------- HOME ----------

    private LinearLayout buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(16));
        sv.addView(body);

        // Kartu CACHE STORAGE
        LinearLayout card = makeCard();
        TextView lbl = smallLabel("CACHE STORAGE");
        card.addView(lbl);
        homeTotal = new TextView(this);
        homeTotal.setText("\u2014");
        homeTotal.setTextSize(34);
        homeTotal.setGravity(Gravity.CENTER);
        try {
            homeTotal.setTypeface(homeTotal.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        card.addView(homeTotal);
        homeSub = new TextView(this);
        homeSub.setText("cache detected");
        homeSub.setTextSize(13);
        homeSub.setTextColor(TEXT_GRAY);
        homeSub.setGravity(Gravity.CENTER);
        card.addView(homeSub);

        homeScanBox = new LinearLayout(this);
        homeScanBox.setOrientation(LinearLayout.VERTICAL);
        homeScanBox.setVisibility(View.GONE);
        homeScanBox.setPadding(0, dp(8), 0, 0);
        homeScanText = new TextView(this);
        homeScanText.setText("Scanning applications...");
        homeScanText.setTextSize(13);
        homeScanText.setTextColor(TEXT_GRAY);
        homeScanText.setGravity(Gravity.CENTER);
        homeScanBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        homeScanBar.setIndeterminate(true);
        homeScanBox.addView(homeScanBar);
        homeScanBox.addView(homeScanText);
        card.addView(homeScanBox);

        homeNotice = new TextView(this);
        homeNotice.setTextSize(12);
        homeNotice.setTextColor(0xFFB45309);
        homeNotice.setGravity(Gravity.CENTER);
        homeNotice.setPadding(0, dp(6), 0, 0);
        homeNotice.setVisibility(View.GONE);
        card.addView(homeNotice);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(12), 0, 0);
        btnRow.setLayoutParams(rlp);

        Button scan = new Button(this);
        scan.setText("\u21BB SCAN");
        scan.setTextColor(accentColor);
        scan.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scan.setLayoutParams(slp);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanCache();
            }
        });
        btnRow.addView(scan);

        Button clean = new Button(this);
        clean.setText("CLEAN CACHE");
        clean.setTextColor(Color.WHITE);
        clean.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        clean.setLayoutParams(blp);
        clean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCleanPressed();
            }
        });
        btnRow.addView(clean);
        card.addView(btnRow);

        homeStats = new TextView(this);
        homeStats.setTextSize(13);
        homeStats.setTextColor(TEXT_GRAY);
        homeStats.setGravity(Gravity.CENTER);
        homeStats.setPadding(0, dp(10), 0, 0);
        card.addView(homeStats);
        body.addView(card);

        // Kartu CACHE BY APPLICATION
        LinearLayout card2 = makeCard();
        card2.addView(smallLabel("CACHE BY APPLICATION"));
        homeTopList = new LinearLayout(this);
        homeTopList.setOrientation(LinearLayout.VERTICAL);
        card2.addView(homeTopList);
        Button viewAll = new Button(this);
        viewAll.setText("View All \u2192");
        viewAll.setBackgroundColor(Color.TRANSPARENT);
        viewAll.setTextColor(ACCENT_DARK);
        viewAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(1);
            }
        });
        card2.addView(viewAll);
        body.addView(card2);

        page.addView(sv);
        return page;
    }

    // ---------- APPS ----------

    private LinearLayout buildAppsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setVisibility(View.GONE);
        page.setPadding(dp(16), dp(4), dp(16), dp(4));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Applications");
        title.setTextSize(20);
        try {
            title.setTypeface(title.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        page.addView(title);

        searchBox = new EditText(this);
        searchBox.setHint("Search apps...");
        searchBox.setSingleLine(true);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().toLowerCase(Locale.US);
                refreshAppsList();
            }
        });
        page.addView(searchBox);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER_VERTICAL);
        selectAllBox = new CheckBox(this);
        selectAllBox.setText("Select All");
        selectAllBox.setChecked(true);
        selectAllBox.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b,
                                                 boolean isChecked) {
                        selectAll = isChecked;
                        checked.clear();
                        for (AppEntry e : shownApps) {
                            checked.put(e.pkg, isChecked);
                        }
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        selectAllBox.setLayoutParams(slp);
        opts.addView(selectAllBox);

        appsCount = new TextView(this);
        appsCount.setTextSize(12);
        appsCount.setTextColor(TEXT_GRAY);
        opts.addView(appsCount);
        page.addView(opts);

        // Sort
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.addView(smallLabel("Sort by: "));
        final String[] sorts = {"Cache size", "App name", "Last scanned"};
        for (int i = 0; i < sorts.length; i++) {
            final int mode = i;
            Button b = new Button(this);
            b.setText(sorts[i]);
            b.setTextSize(11);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setTextColor(ACCENT_DARK);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sortMode = mode;
                    refreshAppsList();
                    toast("Sort: " + sorts[mode]);
                }
            });
            sortRow.addView(b);
        }
        page.addView(sortRow);

        // Filter
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] filters = {"All", "User Apps", "System Apps"};
        for (int i = 0; i < filters.length; i++) {
            final int mode = i;
            Button b = new Button(this);
            b.setText(filters[i]);
            b.setTextSize(11);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filterMode = mode;
                    refreshAppsList();
                }
            });
            filterRow.addView(b);
        }
        page.addView(filterRow);

        ListView list = new ListView(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        list.setLayoutParams(llp);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        page.addView(list);
        return page;
    }

    // ---------- SETTINGS ----------

    private LinearLayout buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setVisibility(View.GONE);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(16));
        sv.addView(body);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(20);
        try {
            title.setTypeface(title.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        body.addView(title);

        // APPEARANCE
        LinearLayout c1 = makeCard();
        c1.addView(smallLabel("APPEARANCE"));
        c1.addView(themeRow());
        c1.addView(switchRow("Dynamic Color",
                prefs.getBoolean("dynamic_color", false),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        prefs.edit().putBoolean("dynamic_color", on).apply();
                        resolveTheme();
                        applyTheme();
                    }
                }));
        body.addView(c1);

        // SCANNER
        LinearLayout c2 = makeCard();
        c2.addView(smallLabel("SCANNER"));
        c2.addView(switchRow("Include system apps",
                prefs.getBoolean("include_system", true),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        prefs.edit().putBoolean("include_system", on).apply();
                        refreshAppsList();
                    }
                }));
        c2.addView(switchRow("Auto scan on startup",
                prefs.getBoolean("auto_scan", true),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        prefs.edit().putBoolean("auto_scan", on).apply();
                    }
                }));
        c2.addView(intervalRow());
        body.addView(c2);

        // CLEANER
        LinearLayout c3 = makeCard();
        c3.addView(smallLabel("CLEANER"));
        c3.addView(switchRow("Confirmation before cleaning",
                prefs.getBoolean("confirm_clean", true),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        prefs.edit().putBoolean("confirm_clean", on).apply();
                    }
                }));
        c3.addView(switchRow("Show cleaning result",
                prefs.getBoolean("show_result", true),
                new OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        prefs.edit().putBoolean("show_result", on).apply();
                    }
                }));
        body.addView(c3);

        // ABOUT
        LinearLayout c4 = makeCard();
        c4.addView(smallLabel("ABOUT"));
        c4.addView(infoRow("FACC Manager", "App Cache Cleaner"));
        c4.addView(infoRow("Version", "1.0.5"));
        TextView warn = new TextView(this);
        warn.setText("Mode AMAN: hanya isi direktori cache yang dibersihkan "
                + "(tanpa pm clear, tanpa hapus data).");
        warn.setTextSize(12);
        warn.setTextColor(0xFFB45309);
        warn.setPadding(0, dp(4), 0, dp(4));
        c4.addView(warn);
        Button gh = new Button(this);
        gh.setText("GitHub");
        gh.setTextColor(ACCENT_DARK);
        gh.setBackgroundColor(Color.TRANSPARENT);
        gh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(GITHUB_URL)));
                } catch (Exception e) {
                    toastLong(GITHUB_URL);
                }
            }
        });
        c4.addView(gh);
        body.addView(c4);

        page.addView(sv);
        return page;
    }

    private interface OnToggle {
        void onToggle(boolean on);
    }

    private View themeRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("Theme");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        r.addView(t);
        final String cur = prefs.getString("theme", "system");
        final String[] modes = {"System", "Light", "Dark"};
        for (int i = 0; i < modes.length; i++) {
            final String val = modes[i].toLowerCase(Locale.US);
            Button b = new Button(this);
            b.setText(modes[i]);
            b.setTextSize(11);
            b.setTextColor(val.equals(cur) ? Color.WHITE : ACCENT_DARK);
            b.setBackgroundColor(val.equals(cur) ? accentColor
                    : Color.TRANSPARENT);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putString("theme", val).apply();
                    resolveTheme();
                    buildUiRefresh();
                }
            });
            r.addView(b);
        }
        return r;
    }

    private void buildUiRefresh() {
        // Bangun ulang seluruh UI agar tema teraplikasi penuh
        root.removeAllViews();
        buildUi();
        applyTheme();
        showPage(2);
        refreshAppsList();
        updateHome();
    }

    private View intervalRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        int cur = prefs.getInt("interval", 30);
        t.setText("Scan interval: every " + cur + " min");
        r.addView(t);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        final int[] vals = {15, 30, 60, 120};
        for (int i = 0; i < vals.length; i++) {
            final int v = vals[i];
            Button b = new Button(this);
            b.setText(v + "m");
            b.setTextSize(11);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v2) {
                    prefs.edit().putInt("interval", v).apply();
                    toast("Interval: " + v + " min");
                    if (moduleOk) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                RootShell.exec(
                                        "facc --autoclean " + v, 15000);
                            }
                        }).start();
                    }
                    buildUiRefresh();
                }
            });
            btns.addView(b);
        }
        r.addView(btns);
        return r;
    }

    private View switchRow(String label, boolean val, final OnToggle cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        r.addView(t);
        Switch s = new Switch(this);
        s.setChecked(val);
        s.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b,
                                                 boolean isChecked) {
                        cb.onToggle(isChecked);
                    }
                });
        r.addView(s);
        return r;
    }

    private View infoRow(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView a = new TextView(this);
        a.setText(k);
        a.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.setLayoutParams(lp);
        TextView b = new TextView(this);
        b.setText(v);
        b.setTextSize(13);
        b.setTextColor(TEXT_GRAY);
        r.addView(a);
        r.addView(b);
        return r;
    }

    // ---------- BOTTOM NAV ----------

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(darkMode ? DARK_CARD : CARD_BG);
        nav.setPadding(0, dp(4), 0, dp(4));

        navHome = navButton("\uD83C\uDFE0\nHome");
        navApps = navButton("\uD83D\uDCF1\nApps");
        navSettings = navButton("\u2699\nSettings");

        navHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(0);
            }
        });
        navApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(1);
            }
        });
        navSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(2);
            }
        });
        nav.addView(navHome);
        nav.addView(navApps);
        nav.addView(navSettings);
        paintNav(0);
        return nav;
    }

    private Button navButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private void showPage(int idx) {
        pageHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageApps.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        paintNav(idx);
    }

    private void paintNav(int idx) {
        if (navHome == null) {
            return;
        }
        navHome.setTextColor(idx == 0 ? accentColor : TEXT_GRAY);
        navApps.setTextColor(idx == 1 ? accentColor : TEXT_GRAY);
        navSettings.setTextColor(idx == 2 ? accentColor : TEXT_GRAY);
    }

    // ---------- Helpers UI ----------

    private LinearLayout makeCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setTag("card");
        c.setBackgroundColor(darkMode ? DARK_CARD : CARD_BG);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        c.setLayoutParams(lp);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                c.setElevation(dp(2));
            } catch (Exception ignored) {
            }
        }
        return c;
    }

    private TextView smallLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(TEXT_GRAY);
        try {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        return t;
    }

    private void updateNotice() {
        if (!rooted) {
            homeNotice.setVisibility(View.VISIBLE);
            homeNotice.setText(
                    "Butuh root + module FACC. Menampilkan daftar aplikasi saja.");
        } else if (!moduleOk) {
            homeNotice.setVisibility(View.VISIBLE);
            homeNotice.setText(
                    "Module FACC tidak terdeteksi. Install module lalu scan ulang.");
        } else {
            homeNotice.setVisibility(View.GONE);
        }
    }

    // ================= DATA =================

    /** Daftar aplikasi lokal (tanpa ukuran cache) untuk mode non-root. */
    private void loadLocalApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<ApplicationInfo> infos = pm.getInstalledApplications(0);
                final List<AppEntry> list = new ArrayList<AppEntry>();
                for (ApplicationInfo ai : infos) {
                    AppEntry e = new AppEntry();
                    e.pkg = ai.packageName;
                    try {
                        e.label = String.valueOf(pm.getApplicationLabel(ai));
                    } catch (Exception ex) {
                        e.label = ai.packageName;
                    }
                    e.bytes = -1;
                    e.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    list.add(e);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(list);
                        checked.clear();
                        for (AppEntry e : allApps) {
                            checked.put(e.pkg, selectAll);
                        }
                        refreshAppsList();
                        updateHome();
                    }
                });
            }
        }).start();
    }

    /** Scan cache via CLI module (root). */
    private void scanCache() {
        if (scanning) {
            return;
        }
        if (!moduleOk && !rooted) {
            toast("Scan butuh root + module FACC");
            return;
        }
        scanning = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                homeScanBox.setVisibility(View.VISIBLE);
                homeScanText.setText("Scanning applications...");
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Scan agresif bisa lama (ratusan app x 3 lokasi) — 10 menit.
                RootShell.Result r = RootShell.exec(
                        "facc --scan --json", 600000);
                final boolean timedOut = (r != null && r.timedOut);
                final List<AppEntry> list = new ArrayList<AppEntry>();
                final long[] total = {0};
                final int[] withCache = {0};
                if (r != null && r.code == 0 && r.out.startsWith("{")) {
                    try {
                        JSONObject o = new JSONObject(r.out);
                        total[0] = o.optLong("total_bytes", 0);
                        JSONArray items = o.optJSONArray("items");
                        Map<String, Long> sizes =
                                new HashMap<String, Long>();
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject it = items.getJSONObject(i);
                                sizes.put(it.optString("package", ""),
                                        it.optLong("bytes", 0));
                            }
                        }
                        List<ApplicationInfo> infos =
                                pm.getInstalledApplications(0);
                        int done = 0;
                        final int totalApps = infos.size();
                        for (ApplicationInfo ai : infos) {
                            AppEntry e = new AppEntry();
                            e.pkg = ai.packageName;
                            try {
                                e.label = String.valueOf(
                                        pm.getApplicationLabel(ai));
                            } catch (Exception ex) {
                                e.label = ai.packageName;
                            }
                            Long b = sizes.get(e.pkg);
                            e.bytes = (b == null) ? 0 : b.longValue();
                            if (e.bytes > 0) {
                                withCache[0]++;
                            }
                            e.isSystem = (ai.flags
                                    & ApplicationInfo.FLAG_SYSTEM) != 0;
                            list.add(e);
                            done++;
                            final int d = done;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    homeScanText.setText("Scanning "
                                            + d + " / " + totalApps
                                            + " apps");
                                }
                            });
                        }
                    } catch (Exception e) {
                        total[0] = -1;
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        homeScanBox.setVisibility(View.GONE);
                        if (total[0] >= 0 && !list.isEmpty()) {
                            allApps.clear();
                            allApps.addAll(list);
                            totalBytes = total[0];
                            checked.clear();
                            for (AppEntry e : allApps) {
                                checked.put(e.pkg, selectAll);
                            }
                            refreshAppsList();
                            updateHome();
                            homeScanText.setText("Scan completed");
                            toast("Scan completed: " + withCache[0]
                                    + " apps, "
                                    + humanSize(total[0]));
                        } else {
                            homeScanText.setText("Scan failed");
                            if (timedOut) {
                                toastLong("Scan timeout (>10 mnt). Coba tekan SCAN lagi.");
                            } else {
                                toastLong("Scan gagal. Pastikan module FACC terpasang.");
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private void refreshAppsList() {
        shownApps.clear();
        boolean incSys = prefs.getBoolean("include_system", true);
        for (AppEntry e : allApps) {
            if (e.isSystem && !incSys && filterMode != 2) {
                continue;
            }
            if (filterMode == 1 && e.isSystem) {
                continue;
            }
            if (filterMode == 2 && !e.isSystem) {
                continue;
            }
            if (query.length() > 0
                    && e.label.toLowerCase(Locale.US).indexOf(query) < 0
                    && e.pkg.toLowerCase(Locale.US).indexOf(query) < 0) {
                continue;
            }
            shownApps.add(e);
        }
        if (sortMode == 0) {
            Collections.sort(shownApps, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    if (a.bytes != b.bytes) {
                        return a.bytes > b.bytes ? -1 : 1;
                    }
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } else if (sortMode == 1) {
            Collections.sort(shownApps, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        }
        if (appsCount != null) {
            appsCount.setText(shownApps.size() + " apps");
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateHome() {
        // Total
        long known = 0;
        boolean anyKnown = false;
        int nCache = 0;
        for (AppEntry e : allApps) {
            if (e.bytes >= 0) {
                anyKnown = true;
                known += e.bytes;
                if (e.bytes > 0) {
                    nCache++;
                }
            }
        }
        long show = (totalBytes >= 0) ? totalBytes : (anyKnown ? known : -1);
        if (show < 0) {
            homeTotal.setText("\u2014");
        } else {
            homeTotal.setText(humanSize(show));
        }
        if (allApps.isEmpty()) {
            homeStats.setText("Scanning...");
        } else {
            homeStats.setText(allApps.size() + " Apps  •  "
                    + (show < 0 ? "?" : humanSize(show)));
        }
        homeSub.setText(nCache > 0 ? nCache + " apps ber-cache"
                : "cache detected");

        // Top 5
        homeTopList.removeAllViews();
        List<AppEntry> top = new ArrayList<AppEntry>();
        for (AppEntry e : allApps) {
            if (e.bytes > 0) {
                top.add(e);
            }
        }
        Collections.sort(top, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (a.bytes != b.bytes) {
                    return a.bytes > b.bytes ? -1 : 1;
                }
                return 0;
            }
        });
        if (top.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Belum ada data. Jalankan scan.");
            t.setTextColor(TEXT_GRAY);
            t.setTextSize(13);
            homeTopList.addView(t);
        } else {
            int n = Math.min(5, top.size());
            for (int i = 0; i < n; i++) {
                homeTopList.addView(topRow(top.get(i)));
            }
        }
    }

    private View topRow(final AppEntry e) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView dot = new TextView(this);
        dot.setText("\u25CF ");
        dot.setTextColor(DOT_GREEN);
        r.addView(dot);
        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(lp);
        r.addView(name);
        TextView size = new TextView(this);
        size.setText(humanSize(e.bytes));
        size.setTextSize(13);
        size.setTextColor(TEXT_GRAY);
        r.addView(size);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(e);
            }
        });
        return r;
    }

    // ================= ADAPTER =================

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int pos) {
            return shownApps.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            final AppEntry e = shownApps.get(pos);
            LinearLayout r;
            CheckBox cb;
            ImageView iv;
            TextView t1;
            TextView t2;
            TextView ts;
            if (convert instanceof LinearLayout) {
                r = (LinearLayout) convert;
                cb = (CheckBox) r.getChildAt(0);
                iv = (ImageView) r.getChildAt(1);
                LinearLayout mid = (LinearLayout) r.getChildAt(2);
                t1 = (TextView) mid.getChildAt(0);
                t2 = (TextView) mid.getChildAt(1);
                ts = (TextView) r.getChildAt(3);
            } else {
                r = new LinearLayout(MainActivity.this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(0, dp(6), 0, dp(6));
                cb = new CheckBox(MainActivity.this);
                iv = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ilp =
                        new LinearLayout.LayoutParams(dp(40), dp(40));
                ilp.setMargins(0, 0, dp(8), 0);
                iv.setLayoutParams(ilp);
                LinearLayout mid = new LinearLayout(MainActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mlp =
                        new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                mid.setLayoutParams(mlp);
                t1 = new TextView(MainActivity.this);
                t1.setTextSize(14);
                t2 = new TextView(MainActivity.this);
                t2.setTextSize(11);
                t2.setTextColor(TEXT_GRAY);
                mid.addView(t1);
                mid.addView(t2);
                ts = new TextView(MainActivity.this);
                ts.setTextSize(13);
                ts.setTextColor(TEXT_GRAY);
                r.addView(cb);
                r.addView(iv);
                r.addView(mid);
                r.addView(ts);
            }
            cb.setOnCheckedChangeListener(null);
            Boolean c = checked.get(e.pkg);
            cb.setChecked(c == null ? selectAll : c.booleanValue());
            cb.setOnCheckedChangeListener(
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b,
                                                     boolean isChecked) {
                            checked.put(e.pkg, isChecked);
                        }
                    });
            try {
                Drawable d = pm.getApplicationIcon(e.pkg);
                iv.setImageDrawable(d);
            } catch (Exception ex) {
                iv.setImageDrawable(null);
            }
            t1.setText(e.label);
            t2.setText(e.pkg + (e.isSystem ? "  •  system" : ""));
            ts.setText(e.bytes < 0 ? "—" : humanSize(e.bytes));
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetail(e);
                }
            });
            return r;
        }
    }

    // ================= DETAIL =================

    private void showDetail(final AppEntry e) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(18);
        try {
            name.setTypeface(name.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        v.addView(name);
        TextView pkg = new TextView(this);
        pkg.setText(e.pkg);
        pkg.setTextColor(TEXT_GRAY);
        v.addView(pkg);

        TextView cacheLbl = smallLabel("CACHE");
        v.addView(cacheLbl);
        final TextView cacheVal = new TextView(this);
        cacheVal.setText(e.bytes < 0 ? "—" : humanSize(e.bytes));
        cacheVal.setTextSize(26);
        cacheVal.setTextColor(accentColor);
        v.addView(cacheVal);

        v.addView(smallLabel("Storage"));
        final TextView storeInfo = new TextView(this);
        storeInfo.setText("App Size  ...\nUser Data  ...\nCache  "
                + (e.bytes < 0 ? "—" : humanSize(e.bytes)));
        v.addView(storeInfo);

        // Isi info storage di background
        new Thread(new Runnable() {
            @Override
            public void run() {
                String apkSize = "—";
                String dataSize = "—";
                try {
                    ApplicationInfo ai =
                            pm.getApplicationInfo(e.pkg, 0);
                    if (ai.sourceDir != null) {
                        apkSize = humanSize(new File(
                                ai.sourceDir).length());
                    }
                    if (rooted) {
                        RootShell.Result r = RootShell.exec(
                                "du -sk '/data/data/" + e.pkg
                                        + "' 2>/dev/null", 15000);
                        if (r != null && r.code == 0) {
                            String[] parts = r.out.split("\\s+");
                            try {
                                long kb = Long.parseLong(parts[0]);
                                dataSize = humanSize(kb * 1024);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                final String a = apkSize;
                final String d = dataSize;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        storeInfo.setText("App Size  " + a
                                + "\nUser Data  " + d + "\nCache  "
                                + (e.bytes < 0 ? "—"
                                : humanSize(e.bytes)));
                    }
                });
            }
        }).start();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(v)
                .setPositiveButton("CLEAR CACHE",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                List<AppEntry> one =
                                        new ArrayList<AppEntry>();
                                one.add(e);
                                startCleaning(one);
                            }
                        })
                .setNegativeButton("Tutup", null)
                .create();
        dlg.show();
    }

    // ================= CLEAN =================

    private List<AppEntry> selectedApps() {
        List<AppEntry> out = new ArrayList<AppEntry>();
        for (AppEntry e : allApps) {
            Boolean c = checked.get(e.pkg);
            boolean isChecked = (c == null) ? selectAll : c.booleanValue();
            if (isChecked && e.bytes > 0) {
                out.add(e);
            }
        }
        return out;
    }

    private void onCleanPressed() {
        if (!moduleOk) {
            toastLong("Cleaning butuh root + module FACC");
            return;
        }
        final List<AppEntry> sel = selectedApps();
        if (sel.isEmpty()) {
            toast("Tidak ada cache yang dipilih");
            return;
        }
        long total = 0;
        for (AppEntry e : sel) {
            total += Math.max(e.bytes, 0);
        }
        if (prefs.getBoolean("confirm_clean", true)) {
            showConfirm(sel, total);
        } else {
            startCleaning(sel);
        }
    }

    private void showConfirm(final List<AppEntry> sel, long total) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView t = new TextView(this);
        t.setText(sel.size() + " applications selected");
        v.addView(t);
        TextView tt = new TextView(this);
        tt.setText("Total cache\n" + humanSize(total));
        tt.setTextSize(20);
        try {
            tt.setTypeface(tt.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } catch (Exception ignored) {
        }
        v.addView(tt);
        TextView list = new TextView(this);
        StringBuilder sb = new StringBuilder();
        int n = Math.min(8, sel.size());
        for (int i = 0; i < n; i++) {
            AppEntry e = sel.get(i);
            sb.append(e.label).append("   ")
                    .append(humanSize(e.bytes)).append("\n");
        }
        if (sel.size() > n) {
            sb.append("... +").append(sel.size() - n).append(" lainnya");
        }
        list.setText(sb.toString());
        list.setTextSize(13);
        list.setTextColor(TEXT_GRAY);
        v.addView(list);

        new AlertDialog.Builder(this)
                .setTitle("Clean Cache")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("CLEAN " + humanSize(total),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                startCleaning(sel);
                            }
                        })
                .show();
    }

    private void startCleaning(final List<AppEntry> sel) {
        long total = 0;
        for (AppEntry e : sel) {
            total += Math.max(e.bytes, 0);
        }
        final long before = total;

        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(4), dp(8), dp(4));
        final TextView status = new TextView(this);
        status.setText("Cleaning cache...");
        v.addView(status);
        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(sel.size());
        bar.setProgress(0);
        v.addView(bar);
        final TextView log = new TextView(this);
        log.setTextSize(13);
        final ScrollView sv = new ScrollView(this);
        sv.addView(log);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        sv.setLayoutParams(slp);
        v.addView(sv);
        final TextView count = new TextView(this);
        count.setText("0 / " + sel.size() + " applications");
        count.setTextColor(TEXT_GRAY);
        v.addView(count);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Clean Cache")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                long freed = 0;
                int done = 0;
                boolean cancelled = false;
                for (final AppEntry e : sel) {
                    if (cancelled) {
                        break;
                    }
                    RootShell.Result r = RootShell.exec(
                            "facc --clean " + e.pkg + " --json", 180000);
                    boolean ok = r != null && r.code == 0;
                    long f = 0;
                    if (ok && r.out.startsWith("{")) {
                        try {
                            JSONObject o = new JSONObject(r.out);
                            ok = o.optBoolean("ok", true);
                            f = o.optLong("freed_bytes", 0);
                        } catch (Exception ignored) {
                        }
                    }
                    if (ok) {
                        freed += f;
                        e.bytes = 0;
                        sb.append("\u2713 ").append(e.label).append("\n");
                    } else {
                        sb.append("\u2718 ").append(e.label).append("\n");
                    }
                    done++;
                    final int d = done;
                    final long fr = freed;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            log.setText(sb.toString());
                            bar.setProgress(d);
                            count.setText(d + " / " + sel.size()
                                    + " applications");
                            status.setText("Cleaning... "
                                    + humanSize(fr) + " freed");
                        }
                    });
                    try {
                        if (!dlg.isShowing()) {
                            cancelled = true;
                        }
                    } catch (Exception ex) {
                        cancelled = true;
                    }
                }
                final long fFreed = freed;
                final int fDone = done;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        totalBytes = Math.max(0, before - fFreed);
                        for (AppEntry e : sel) {
                            if (e.bytes < 0) {
                                e.bytes = 0;
                            }
                        }
                        refreshAppsList();
                        updateHome();
                        try {
                            dlg.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (prefs.getBoolean("show_result", true)) {
                            showResult(before, fFreed, fDone);
                        } else {
                            toast("Cleaning completed: "
                                    + humanSize(fFreed));
                        }
                    }
                });
            }
        }).start();
    }

    private void showResult(long before, long freed, int apps) {
        long after = Math.max(0, before - freed);
        String msg = "\u2713 Cleaning completed\n\n"
                + humanSize(before) + " \u2192 " + humanSize(after)
                + "\n\nCleared\n" + humanSize(freed)
                + "\n\n" + apps + " apps processed";
        new AlertDialog.Builder(this)
                .setTitle("Result")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    // ================= Util =================

    /** Toast aman dari thread mana pun (selalu via UI thread). */
    private void toast(final String msg) {
        toastLen(msg, Toast.LENGTH_SHORT);
    }

    private void toastLong(final String msg) {
        toastLen(msg, Toast.LENGTH_LONG);
    }

    private void toastLen(final String msg, final int len) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(MainActivity.this, msg, len).show();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Format bytes ala facc_human_size (GB/MB/KB/B). */
    private static String humanSize(long b) {
        if (b < 0) {
            return "—";
        }
        if (b >= 1073741824L) {
            return String.format(Locale.US, "%.2f GB",
                    b / 1073741824.0);
        } else if (b >= 1048576L) {
            return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        } else if (b >= 1024L) {
            return String.format(Locale.US, "%d KB", b / 1024);
        } else {
            return b + " B";
        }
    }
}
