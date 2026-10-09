package com.techno.nowrss;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Persists the list of subscribed feed URLs. */
public class FeedStore {
    private final SharedPreferences sp;

    public FeedStore(Context c) {
        sp = c.getSharedPreferences("feeds", Context.MODE_PRIVATE);
    }

    public List<String> get() {
        List<String> out = new ArrayList<String>();
        String raw = sp.getString("urls", "");
        for (String s : raw.split("\n")) {
            if (s.trim().length() > 0) out.add(s.trim());
        }
        return out;
    }

    public void save(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String u : urls) sb.append(u).append('\n');
        sp.edit().putString("urls", sb.toString()).commit();
    }

    public boolean add(String url) {
        List<String> urls = get();
        if (urls.contains(url)) return false;
        urls.add(url);
        save(urls);
        return true;
    }
}
