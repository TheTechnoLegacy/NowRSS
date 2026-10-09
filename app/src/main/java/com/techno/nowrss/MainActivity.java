package com.techno.nowrss;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private FeedStore store;
    private EntryAdapter adapter;
    private ProgressBar progress;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int generation = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store = new FeedStore(this);
        progress = (ProgressBar) findViewById(R.id.progress);
        ListView list = (ListView) findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty));
        adapter = new EntryAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                openArticle(adapter.getItem(pos));
            }
        });
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add) { showAddDialog(); return true; }
        if (id == R.id.action_refresh) { refresh(); return true; }
        if (id == R.id.action_manage) { showManageDialog(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void openArticle(FeedParser.Entry e) {
        if (e.link == null || e.link.length() == 0) return;
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("url", e.link);
        i.putExtra("title", e.title);
        i.putExtra("source", e.source);
        i.putExtra("summary", e.summary);
        i.putExtra("date", e.date);
        startActivity(i);
    }

    private void showAddDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.feed_url);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_feed)
                .setView(input)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String url = input.getText().toString().trim();
                        if (url.length() == 0) return;
                        if (!url.contains("://")) url = "https://" + url;
                        if (store.add(url)) refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showManageDialog() {
        final List<String> urls = store.get();
        if (urls.isEmpty()) {
            Toast.makeText(this, R.string.no_feeds_to_manage, Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean[] checked = new boolean[urls.size()];
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_feeds)
                .setMultiChoiceItems(urls.toArray(new String[0]), checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean on) {
                                checked[which] = on;
                            }
                        })
                .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<String> keep = new ArrayList<String>();
                        for (int i = 0; i < urls.size(); i++) if (!checked[i]) keep.add(urls.get(i));
                        store.save(keep);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void refresh() {
        final List<String> urls = store.get();
        final int gen = ++generation;
        if (urls.isEmpty()) {
            adapter.clear();
            progress.setVisibility(View.GONE);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                final List<FeedParser.Entry> all = new ArrayList<FeedParser.Entry>();
                boolean failed = false;
                for (String u : urls) {
                    try {
                        all.addAll(FeedParser.fetch(u));
                    } catch (Exception e) {
                        failed = true;
                    }
                }
                Collections.sort(all, new Comparator<FeedParser.Entry>() {
                    public int compare(FeedParser.Entry a, FeedParser.Entry b) {
                        return a.date == b.date ? 0 : (a.date < b.date ? 1 : -1);
                    }
                });
                final boolean anyFailed = failed;
                ui.post(new Runnable() {
                    public void run() {
                        if (gen != generation) return;
                        progress.setVisibility(View.GONE);
                        adapter.clear();
                        adapter.addAll(all);
                        if (anyFailed) {
                            Toast.makeText(MainActivity.this, R.string.some_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static class EntryAdapter extends ArrayAdapter<FeedParser.Entry> {
        private final LayoutInflater inflater;

        EntryAdapter(Context c) {
            super(c, 0);
            inflater = LayoutInflater.from(c);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.item_card, parent, false);
            FeedParser.Entry e = getItem(pos);
            ((TextView) v.findViewById(R.id.source)).setText(e.source.toUpperCase());
            TextView title = (TextView) v.findViewById(R.id.title);
            title.setText(e.title);
            title.setVisibility(e.title.length() > 0 ? View.VISIBLE : View.GONE);
            TextView snip = (TextView) v.findViewById(R.id.snippet);
            snip.setText(e.summary);
            snip.setVisibility(e.summary.length() > 0 ? View.VISIBLE : View.GONE);
            TextView time = (TextView) v.findViewById(R.id.time);
            if (e.date > 0) {
                time.setText(DateUtils.getRelativeTimeSpanString(
                        e.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
                time.setVisibility(View.VISIBLE);
            } else {
                time.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
