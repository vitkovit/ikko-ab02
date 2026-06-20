package com.mw.dialer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PERM_REQUEST = 1;
    private static final String[] ALPHABET = {
        "#","A","B","C","D","E","F","G","H","I","J","K","L","M",
        "N","O","P","Q","R","S","T","U","V","W","X","Y","Z"
    };

    private LinearLayout tabContacts, tabDial, tabRecents;
    private ImageView tabContactsIcon, tabDialIcon, tabRecentsIcon;
    private TextView tabContactsLabel, tabDialLabel, tabRecentsLabel;
    private View tabRecentsDot;          // red corner dot on the Recent tab when missed calls are pending
    private FrameLayout content;

    private View contactsView, dialView, recentsView;
    private SectionedContactAdapter contactAdapter;
    private List<Object> allSectioned = new ArrayList<>(); // String=header, String[]=contact
    private String dialNumber = "";
    private TextView dialDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabContacts = findViewById(R.id.tab_contacts);
        tabDial = findViewById(R.id.tab_dial);
        tabRecents = findViewById(R.id.tab_recents);
        tabContactsIcon = findViewById(R.id.tab_contacts_icon);
        tabContactsLabel = findViewById(R.id.tab_contacts_label);
        tabDialIcon = findViewById(R.id.tab_dial_icon);
        tabDialLabel = findViewById(R.id.tab_dial_label);
        tabRecentsIcon = findViewById(R.id.tab_recents_icon);
        tabRecentsLabel = findViewById(R.id.tab_recents_label);
        tabRecentsDot = findViewById(R.id.tab_recents_dot);
        content = findViewById(R.id.content);

        tabContacts.setOnClickListener(v -> showTab(0));
        tabDial.setOnClickListener(v -> showTab(1));
        tabRecents.setOnClickListener(v -> showTab(2));

        checkPermissions();
    }

    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        for (String p : new String[]{
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        }) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (needed.isEmpty()) init();
        else requestPermissions(needed.toArray(new String[0]), PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) { init(); }

    private void init() {
        contactsView = buildContactsView();
        dialView = buildDialView();
        recentsView = buildRecentsView();
        showTab(2);          // land on Recent by default
        refreshMissedDot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMissedDot();   // a missed call may have arrived while we were away
    }

    private void showTab(int tab) {
        content.removeAllViews();
        int on = 0xFFffffff, off = 0xFF444444;
        setTab(tabContactsIcon, tabContactsLabel, tab == 0 ? on : off);
        setTab(tabDialIcon, tabDialLabel, tab == 1 ? on : off);
        setTab(tabRecentsIcon, tabRecentsLabel, tab == 2 ? on : off);
        if (tab == 0) content.addView(contactsView);
        else if (tab == 1) content.addView(dialView);
        else {
            content.addView(recentsView);
            loadRecents();
            markMissedSeen();        // opening Recent acknowledges the missed calls -> clear the dot
        }
    }

    /** Show the red dot on the Recent tab when there are unacknowledged missed calls. */
    private void refreshMissedDot() {
        if (tabRecentsDot == null) return;
        int missed = 0;
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            Cursor c = null;
            try {
                c = getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls._ID},
                        CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1",
                        null, null);
                if (c != null) missed = c.getCount();
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
        }
        tabRecentsDot.setVisibility(missed > 0 ? View.VISIBLE : View.GONE);
    }

    /** Mark all pending missed calls as seen (clears the dot here and the launcher badge). */
    private void markMissedSeen() {
        if (checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            refreshMissedDot();
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put(CallLog.Calls.NEW, 0);
            v.put(CallLog.Calls.IS_READ, 1);
            getContentResolver().update(CallLog.Calls.CONTENT_URI, v,
                    CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1", null);
        } catch (Exception ignored) {}
        refreshMissedDot();
    }

    private void setTab(ImageView icon, TextView label, int color) {
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    // ── CONTACTS ─────────────────────────────────────────────────────────────

    private static final int IMPORT_REQUEST = 2;

    private View buildContactsView() {
        View v = LayoutInflater.from(this).inflate(R.layout.fragment_contacts, null);
        ListView list = v.findViewById(R.id.list);
        EditText search = v.findViewById(R.id.search);
        AlphaSlider slider = v.findViewById(R.id.alpha_slider);
        TextView importBtn = v.findViewById(R.id.btn_import);

        v.setFocusableInTouchMode(true);
        v.requestFocus();

        allSectioned = buildSectioned(loadContacts());
        contactAdapter = new SectionedContactAdapter(this, allSectioned);
        list.setAdapter(contactAdapter);
        list.setOnItemClickListener((p, view, pos, id) -> {
            Object item = contactAdapter.getItem(pos);
            if (item instanceof String[]) call(((String[]) item)[1]);
        });

        // Niagara balloon slider — no popup, just scroll
        slider.setListener((letter, done) -> {
            if (!done) scrollToSection(list, letter);
        });

        // Import VCard button
        importBtn.setOnClickListener(vv -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/x-vcard");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(intent, "Select VCF file"), IMPORT_REQUEST);
            } catch (Exception e) {
                Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
            }
        });

        // Search filter
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().toLowerCase(Locale.getDefault());
                if (q.isEmpty()) {
                    contactAdapter.setItems(allSectioned);
                    slider.setVisibility(View.VISIBLE);
                } else {
                    List<String[]> filtered = new ArrayList<>();
                    for (Object o : allSectioned) {
                        if (o instanceof String[]) {
                            String[] c2 = (String[]) o;
                            if (c2[0].toLowerCase(Locale.getDefault()).contains(q) || c2[1].contains(q))
                                filtered.add(c2);
                        }
                    }
                    contactAdapter.setItems(buildSectioned(filtered));
                    slider.setVisibility(View.GONE);
                }
            }
            public void afterTextChanged(Editable s) {}
        });

        return v;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == IMPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // Trigger system contacts import by opening the VCF URI
            Intent importIntent = new Intent(Intent.ACTION_VIEW);
            importIntent.setDataAndType(data.getData(), "text/x-vcard");
            importIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(importIntent);
                Toast.makeText(this, "Opening VCF for import...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open VCF file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void scrollToSection(ListView list, String letter) {
        List<Object> items = contactAdapter.getItems();
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof String && o.equals(letter)) {
                list.setSelection(i);
                return;
            }
            // For '#', scroll to first non-letter contact
            if (letter.equals("#") && i == 0) {
                list.setSelection(0);
                return;
            }
        }
    }

    private List<String[]> loadContacts() {
        List<String[]> result = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return result;
        Cursor c = getContentResolver().query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                         ContactsContract.CommonDataKinds.Phone.NUMBER},
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (c != null) {
            while (c.moveToNext()) {
                String name = c.getString(0);
                String num = c.getString(1);
                if (name != null) result.add(new String[]{name, num != null ? num : ""});
            }
            c.close();
        }
        return result;
    }

    private List<Object> buildSectioned(List<String[]> contacts) {
        List<Object> items = new ArrayList<>();
        String lastLetter = "";
        for (String[] contact : contacts) {
            String first = contact[0].isEmpty() ? "#"
                : contact[0].substring(0, 1).toUpperCase(Locale.getDefault());
            if (!first.matches("[A-Z]")) first = "#";
            if (!first.equals(lastLetter)) {
                items.add(first); // section header
                lastLetter = first;
            }
            items.add(contact);
        }
        return items;
    }

    // ── DIAL ─────────────────────────────────────────────────────────────────

    private View buildDialView() {
        View v = LayoutInflater.from(this).inflate(R.layout.fragment_dial, null);
        dialDisplay = v.findViewById(R.id.display);

        int[] ids = {R.id.key1,R.id.key2,R.id.key3,R.id.key4,R.id.key5,R.id.key6,
                     R.id.key7,R.id.key8,R.id.key9,R.id.keyStar,R.id.key0,R.id.keyHash};
        String[] keys = {"1","2","3","4","5","6","7","8","9","*","0","#"};

        for (int i = 0; i < ids.length; i++) {
            final String d = keys[i];
            v.findViewById(ids[i]).setOnClickListener(view -> {
                dialNumber += d;
                dialDisplay.setText(dialNumber);
            });
        }
        v.findViewById(R.id.keyDel).setOnClickListener(view -> {
            if (!dialNumber.isEmpty()) {
                dialNumber = dialNumber.substring(0, dialNumber.length() - 1);
                dialDisplay.setText(dialNumber.isEmpty() ? null : dialNumber);
            }
        });
        v.findViewById(R.id.keyDel).setOnLongClickListener(view -> {
            dialNumber = ""; dialDisplay.setText(null); return true;
        });
        v.findViewById(R.id.keyCall).setOnClickListener(view -> {
            if (!dialNumber.isEmpty()) call(dialNumber);
            else Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show();
        });
        return v;
    }

    // ── RECENTS ──────────────────────────────────────────────────────────────

    private View buildRecentsView() {
        return LayoutInflater.from(this).inflate(R.layout.fragment_recents, null);
    }

    private void loadRecents() {
        ListView list = recentsView.findViewById(R.id.recents_list);
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return;
        List<String[]> calls = new ArrayList<>();
        Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
            new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
            null, null, CallLog.Calls.DATE + " DESC LIMIT 60");
        if (c != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault());
            while (c.moveToNext()) {
                String name = c.getString(0), num = c.getString(1);
                int type = c.getInt(2);
                String date = sdf.format(new Date(c.getLong(3)));
                String arrow = type == CallLog.Calls.INCOMING_TYPE ? "↙" :
                               type == CallLog.Calls.OUTGOING_TYPE ? "↗" : "✗";
                String color = "#888888";
                calls.add(new String[]{(name != null && !name.isEmpty()) ? name : num, num, arrow, date, color});
            }
            c.close();
        }
        list.setAdapter(new RecentAdapter(this, calls));
        list.setOnItemClickListener((p, view, pos, id) -> call(calls.get(pos)[1]));
    }

    // ── CALL ─────────────────────────────────────────────────────────────────

    private void call(String number) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return;
        startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number)));
    }

    // ── ADAPTERS ─────────────────────────────────────────────────────────────

    class SectionedContactAdapter extends BaseAdapter {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_CONTACT = 1;
        private final Context ctx;
        private List<Object> items;

        SectionedContactAdapter(Context ctx, List<Object> items) {
            this.ctx = ctx;
            this.items = items;
        }

        void setItems(List<Object> items) { this.items = items; notifyDataSetChanged(); }
        List<Object> getItems() { return items; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int pos) {
            return items.get(pos) instanceof String ? TYPE_HEADER : TYPE_CONTACT;
        }
        @Override public boolean isEnabled(int pos) { return items.get(pos) instanceof String[]; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (getItemViewType(pos) == TYPE_HEADER) {
                if (convertView == null)
                    convertView = LayoutInflater.from(ctx).inflate(R.layout.item_section, parent, false);
                ((TextView) convertView.findViewById(R.id.letter)).setText((String) items.get(pos));
            } else {
                if (convertView == null)
                    convertView = LayoutInflater.from(ctx).inflate(R.layout.item_contact, parent, false);
                String[] contact = (String[]) items.get(pos);
                ((TextView) convertView.findViewById(R.id.name)).setText(contact[0]);
                ((TextView) convertView.findViewById(R.id.number)).setText(contact[1]);
            }
            return convertView;
        }
    }

    static class RecentAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<String[]> data;
        RecentAdapter(Context ctx, List<String[]> data) { this.ctx = ctx; this.data = data; }
        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int pos) { return data.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(ctx).inflate(R.layout.item_recent, parent, false);
            String[] call = data.get(pos);
            ((TextView) convertView.findViewById(R.id.name)).setText(call[0]);
            TextView typeIcon = convertView.findViewById(R.id.type_icon);
            typeIcon.setText(call[2]);
            typeIcon.setTextColor(android.graphics.Color.parseColor(call[4]));
            ((TextView) convertView.findViewById(R.id.time)).setText(call[3]);
            return convertView;
        }
    }
}
