package com.wakeel.sandbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One activity that plays every screen of a small pretend app: a home page with a search box and an
 * icon-only button, a long contact list that opens a chat, a wallet with a "Buy" button, a profile with
 * a password page, a dense photo grid with a selection mode, and a bottom tab bar. Every action writes
 * what happened into the status line, so a test can read back exactly what the agent did.
 */
public class SandboxActivity extends Activity {
    static final String[] NAMES = {
        "Ahmed", "Sara", "Omar", "Layla", "Yousef", "Mariam", "Khalid", "Noor", "Hassan", "Fatima", "Ali", "Huda",
        "Tariq", "Salma", "Nasser", "Reem", "Fahad", "Dana", "Majid", "Hind", "Saeed", "Lina", "Badr", "Zaid",
    };

    /** How many messages each contact received since the app started — a duplicate send shows up here. */
    static final Map<String, Integer> SENT = new HashMap<>();

    /** Real chat apps keep what was typed and not sent. Here one contact does — enough to test an old draft. */
    static final String KEEPS_DRAFT = "Noor";
    static String draft = "";

    private TextView status;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String screen = getIntent().getStringExtra("screen");
        if (screen == null) screen = "home";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setId(R.id.status);
        status.setText("status: idle");
        status.setPadding(pad, dp(6), pad, dp(6));
        root.addView(status);

        String tab = "home";
        switch (screen) {
            case "contacts": setTitle("Contacts"); contacts(content); tab = "contacts"; break;
            case "chat": setTitle("Chat"); chat(content, getIntent().getStringExtra("name")); tab = "contacts"; break;
            case "wallet": setTitle("Wallet"); wallet(content); tab = "wallet"; break;
            case "profile": setTitle("Profile"); profile(content); tab = "profile"; break;
            case "security": setTitle("Security"); security(content); tab = "profile"; break;
            case "prefs": setTitle("Preferences"); prefs(content); break;
            case "about": setTitle("About"); content.addView(text("Sandbox 1.0 — a pretend app for tests")); break;
            case "photos": setTitle("Photos"); photos(content); break;
            default: setTitle("Home"); home(content); break;
        }
        root.addView(tabs(tab));
        setContentView(root);
    }

    // ------------------------------------------------------------------ screens

    private void home(LinearLayout c) {
        final EditText search = edit(R.id.search_box, "Search");
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, actionId, event) -> {
            status.setText("searched: " + search.getText());
            return true;
        });
        c.addView(search);

        // An icon with no text at all: the agent can only know it by its id
        ImageButton prefs = new ImageButton(this);
        prefs.setId(R.id.btn_prefs);
        prefs.setImageResource(android.R.drawable.ic_menu_preferences);
        prefs.setOnClickListener(v -> open("prefs", null, false));
        c.addView(prefs, new LinearLayout.LayoutParams(dp(64), dp(64)));

        c.addView(button(R.id.btn_about, "About", v -> open("about", null, false)));
        c.addView(button(R.id.btn_photos, "Photos", v -> open("photos", null, false)));
    }

    private void contacts(LinearLayout c) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            Map<String, String> m = new HashMap<>();
            m.put("n", NAMES[i]);
            m.put("s", i % 2 == 0 ? "online" : "last seen recently");
            rows.add(m);
        }
        ListView list = new ListView(this);
        list.setId(R.id.contact_list);
        list.setAdapter(new SimpleAdapter(this, rows, android.R.layout.simple_list_item_2,
            new String[]{"n", "s"}, new int[]{android.R.id.text1, android.R.id.text2}));
        list.setOnItemClickListener((parent, view, position, id) -> open("chat", NAMES[position], false));
        c.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void chat(LinearLayout c, final String name) {
        TextView to = text("To: " + name);
        to.setId(R.id.chat_to);
        c.addView(to);
        final TextView count = text("messages sent to " + name + ": " + sentTo(name));
        count.setId(R.id.sent_count);
        final EditText message = edit(R.id.message, "Message");
        if (KEEPS_DRAFT.equals(name)) {
            message.setText(draft);
            message.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) { draft = s.toString(); }
            });
        }
        c.addView(message);
        c.addView(button(R.id.btn_send, "Send", v -> {
            SENT.put(name, sentTo(name) + 1);
            count.setText("messages sent to " + name + ": " + sentTo(name));
            status.setText("sent: " + message.getText() + " to " + name);
            message.setText("");
        }));
        c.addView(count);
    }

    private void wallet(LinearLayout c) {
        c.addView(text("Balance: 100 USDT"));
        final EditText coin = edit(R.id.coin_search, "Search coin");
        coin.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        coin.setOnEditorActionListener((v, actionId, event) -> {
            status.setText("coin: " + coin.getText());
            return true;
        });
        c.addView(coin);
        c.addView(button(R.id.btn_history, "Price history", v -> status.setText("history shown")));
        c.addView(button(R.id.btn_buy, "Buy BTC", v -> status.setText("bought")));
    }

    private void profile(LinearLayout c) {
        final EditText bio = edit(R.id.bio, "Bio");
        c.addView(bio);
        c.addView(button(R.id.btn_save, "Save", v -> status.setText("saved: " + bio.getText())));
        c.addView(button(R.id.btn_security, "Security", v -> open("security", null, false)));
    }

    private void security(LinearLayout c) {
        final TextView len = text("pin length: 0");
        len.setId(R.id.pin_len);
        EditText pin = edit(R.id.pin, "PIN");
        pin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { len.setText("pin length: " + s.length()); }
        });
        c.addView(pin);
        c.addView(len);
    }

    private void prefs(LinearLayout c) {
        Switch dark = new Switch(this);
        dark.setId(R.id.sw_dark);
        dark.setText("Dark mode");
        dark.setOnCheckedChangeListener((v, on) -> status.setText("dark: " + (on ? "on" : "off")));
        c.addView(dark);
    }

    /**
     * A photo tile that behaves like the grid of a real gallery: it says it can be long-pressed, accepts
     * the accessibility request — and ignores it. Only a real finger held on it starts the selection.
     */
    static final class Tile extends View {
        Tile(Context c) {
            super(c);
            setLongClickable(true);
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return true;
            return super.performAccessibilityAction(action, arguments);
        }
    }

    /** More thumbnails on one page than an agent can list: the bars around the grid must stay readable. */
    private void photos(LinearLayout c) {
        final int total = 360;
        final boolean[] picked = new boolean[total];
        final boolean[] selecting = {false};
        final boolean[] quiet = {false};
        final Runnable[] refresh = new Runnable[1];

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox all = new CheckBox(this);
        all.setId(R.id.sel_all);
        all.setText("All");
        all.setVisibility(View.GONE);
        final TextView count = text("Camera: " + total + " photos");
        count.setId(R.id.sel_count);
        top.addView(all);
        top.addView(count);
        c.addView(top);

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return total; }
            @Override public Object getItem(int position) { return position; }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(final int position, View convert, ViewGroup parent) {
                Tile t = convert instanceof Tile ? (Tile) convert : new Tile(SandboxActivity.this);
                t.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
                t.setContentDescription("Photo " + (position + 1));
                t.setActivated(picked[position]);
                t.setBackgroundColor(picked[position] ? 0xFF1E88E5 : (position % 2 == 0 ? 0xFFB0BEC5 : 0xFF90A4AE));
                t.setOnClickListener(v -> {
                    if (selecting[0]) {
                        picked[position] = !picked[position];
                        refresh[0].run();
                    } else {
                        status.setText("opened photo " + (position + 1));
                    }
                });
                t.setOnLongClickListener(v -> {
                    selecting[0] = true;
                    picked[position] = true;
                    refresh[0].run();
                    return true;
                });
                return t;
            }
        };
        final GridView grid = new GridView(this);
        grid.setId(R.id.photo_grid);
        grid.setNumColumns(12);
        grid.setAdapter(adapter);
        c.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Selection bar, as in a gallery: Share, Delete, More → Move to album / Copy to album
        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setVisibility(View.GONE);
        final LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.HORIZONTAL);
        more.setVisibility(View.GONE);
        final LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setVisibility(View.GONE);
        final String[] verb = {"moved"};

        actions.addView(button(R.id.btn_share, "Share", v -> status.setText("shared " + chosen(picked) + " photos")));
        actions.addView(button(R.id.btn_delete, "Delete", v -> status.setText("deleted " + chosen(picked) + " photos")));
        actions.addView(button(R.id.btn_more, "More", v -> more.setVisibility(View.VISIBLE)));
        View.OnClickListener toAlbum = v -> {
            verb[0] = v.getId() == R.id.btn_copy ? "copied" : "moved";
            grid.setVisibility(View.GONE);
            actions.setVisibility(View.GONE);
            more.setVisibility(View.GONE);
            picker.setVisibility(View.VISIBLE);
        };
        more.addView(button(R.id.btn_move, "Move to album", toAlbum));
        more.addView(button(R.id.btn_copy, "Copy to album", toAlbum));

        final EditText name = edit(R.id.album_name, "Album name");
        name.setVisibility(View.GONE);
        final Button create = button(R.id.btn_create, "Create", null);
        create.setVisibility(View.GONE);
        final Button newAlbum = button(R.id.btn_new_album, "Create album", null);
        newAlbum.setOnClickListener(v -> {
            newAlbum.setVisibility(View.GONE);
            name.setVisibility(View.VISIBLE);
            create.setVisibility(View.VISIBLE);
        });
        create.setOnClickListener(v -> {
            status.setText(verb[0] + " " + chosen(picked) + " photos to album " + name.getText());
            Arrays.fill(picked, false);
            selecting[0] = false;
            name.setText("");
            name.setVisibility(View.GONE);
            create.setVisibility(View.GONE);
            newAlbum.setVisibility(View.VISIBLE);
            picker.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            refresh[0].run();
        });
        picker.addView(text("Choose an album"));
        picker.addView(newAlbum);
        picker.addView(name);
        picker.addView(create);

        c.addView(actions);
        c.addView(more);
        c.addView(picker);

        all.setOnCheckedChangeListener((b, on) -> {
            if (quiet[0]) return;
            Arrays.fill(picked, on);
            refresh[0].run();
        });
        refresh[0] = () -> {
            int n = chosen(picked);
            count.setText(selecting[0] ? n + " selected" : "Camera: " + total + " photos");
            all.setVisibility(selecting[0] ? View.VISIBLE : View.GONE);
            quiet[0] = true;
            all.setChecked(selecting[0] && n == total);
            quiet[0] = false;
            actions.setVisibility(selecting[0] ? View.VISIBLE : View.GONE);
            if (!selecting[0]) more.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
        };
    }

    private static int chosen(boolean[] picked) {
        int n = 0;
        for (boolean p : picked) if (p) n++;
        return n;
    }

    // ------------------------------------------------------------------ parts

    private View tabs(String current) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        String[] keys = {"home", "contacts", "wallet", "profile"};
        String[] labels = {"Home", "Contacts", "Wallet", "Profile"};
        int[] ids = {R.id.tab_home, R.id.tab_contacts, R.id.tab_wallet, R.id.tab_profile};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            Button b = button(ids[i], labels[i], v -> open(key, null, true));
            b.setSelected(key.equals(current));
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        return bar;
    }

    /** Tabs replace the whole stack (like a real tab bar); inner pages are pushed on top. */
    private void open(String screen, String name, boolean asTab) {
        Intent i = new Intent(this, SandboxActivity.class).putExtra("screen", screen);
        if (name != null) i.putExtra("name", name);
        if (asTab) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        if (asTab) overridePendingTransition(0, 0);
    }

    private Button button(int id, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setId(id);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(onClick);
        return b;
    }

    private EditText edit(int id, String hint) {
        EditText e = new EditText(this);
        e.setId(id);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18f);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private static int sentTo(String name) {
        Integer n = SENT.get(name);
        return n == null ? 0 : n;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
