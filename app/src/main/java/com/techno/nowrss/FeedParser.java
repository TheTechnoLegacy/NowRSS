package com.techno.nowrss;

import android.text.Html;
import android.util.Xml;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

public class FeedParser {

    public static class Entry {
        public String title = "";
        public String link = "";
        public String summary = "";
        public String source = "";
        public long date = 0;
    }

    public static List<Entry> fetch(String url) throws Exception {
        Http.Response r = Http.get(url, 4 * 1024 * 1024);
        return parse(new java.io.ByteArrayInputStream(r.data), Uri(url));
    }

    private static String Uri(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    public static List<Entry> parse(InputStream in, String fallbackSource) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, null);
        List<Entry> out = new ArrayList<Entry>();
        String feedTitle = null;
        Entry cur = null;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName().toLowerCase(Locale.US);
                if (n.equals("item") || n.equals("entry")) {
                    cur = new Entry();
                } else if (cur == null) {
                    if (n.equals("title") && feedTitle == null) {
                        feedTitle = clean(readText(p));
                    }
                } else if (n.equals("title")) {
                    cur.title = clean(readText(p));
                } else if (n.equals("link")) {
                    String href = p.getAttributeValue(null, "href");
                    if (href != null) {
                        String rel = p.getAttributeValue(null, "rel");
                        if ((rel == null || rel.equals("alternate")) && cur.link.length() == 0) {
                            cur.link = href.trim();
                        }
                        readText(p);
                    } else {
                        String t = readText(p).trim();
                        if (cur.link.length() == 0) cur.link = t;
                    }
                } else if (n.equals("description") || n.equals("summary")
                        || n.equals("content") || n.equals("content:encoded")) {
                    String t = clean(readText(p));
                    if (cur.summary.length() == 0) cur.summary = t;
                } else if (n.equals("pubdate") || n.equals("published")
                        || n.equals("updated") || n.equals("dc:date")) {
                    long d = parseDate(readText(p).trim());
                    if (cur.date == 0) cur.date = d;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName().toLowerCase(Locale.US);
                if ((n.equals("item") || n.equals("entry")) && cur != null) {
                    cur.source = (feedTitle != null && feedTitle.length() > 0) ? feedTitle : fallbackSource;
                    if (cur.title.length() > 0 || cur.summary.length() > 0) out.add(cur);
                    cur = null;
                }
            }
            ev = p.next();
        }
        return out;
    }

    private static String readText(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) depth++;
            else if (e == XmlPullParser.END_TAG) depth--;
            else if (e == XmlPullParser.TEXT || e == XmlPullParser.CDSECT
                    || e == XmlPullParser.ENTITY_REF) sb.append(p.getText());
            else if (e == XmlPullParser.END_DOCUMENT) break;
        }
        return sb.toString();
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = Html.fromHtml(s).toString().replace('\uFFFC', ' ');
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static final String[] FORMATS = {
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    };

    static long parseDate(String s) {
        if (s == null || s.length() == 0) return 0;
        String iso = s.replaceAll("\\.\\d+", "")
                .replaceAll("Z$", "+0000")
                .replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        for (String f : FORMATS) {
            try {
                Date d = new SimpleDateFormat(f, Locale.US).parse(f.startsWith("yyyy") ? iso : s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
