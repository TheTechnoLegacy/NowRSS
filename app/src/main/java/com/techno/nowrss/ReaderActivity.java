package com.techno.nowrss;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shows an article in Reader mode: only text, images and videos, rendered inside the app. */
public class ReaderActivity extends Activity {

    private WebView web;
    private ProgressBar progress;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String url, title, source, summary;
    private long date;
    private volatile boolean destroyed;

    private static final String CSS =
            "body{margin:0;padding:16px 16px 40px;background:#fff;color:#333;"
            + "font-family:sans-serif;font-size:17px;line-height:1.55;word-wrap:break-word}"
            + ".site{color:#dd4b39;font-size:12px;font-weight:bold;letter-spacing:.5px}"
            + "h1{font-size:25px;line-height:1.25;margin:6px 0 4px;font-weight:normal;color:#222}"
            + ".date{color:#999;font-size:13px;margin-bottom:14px}"
            + "h2,h3,h4,h5,h6{line-height:1.3;color:#222;margin:1.2em 0 .4em}"
            + "h2{font-size:21px}h3{font-size:19px}h4,h5,h6{font-size:17px}"
            + "p{margin:0 0 1em}a{color:#1a73c8;text-decoration:none}"
            + "img,video{max-width:100%;height:auto;display:block;margin:12px 0}"
            + "figure{margin:12px 0}figcaption{color:#888;font-size:13px}"
            + "blockquote{margin:1em 0;padding:0 0 0 14px;border-left:3px solid #ddd;color:#555}"
            + "pre{overflow:auto;background:#f3f3f3;padding:8px;font-size:14px}"
            + "ul,ol{padding-left:24px}"
            + "a.vid{display:block;position:relative;margin:12px 0;background:#222;color:#fff;"
            + "padding:14px;text-align:center}a.vid img{margin:0 0 8px}"
            + ".note{color:#999;font-size:14px;border-top:1px solid #eee;padding-top:10px}";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_reader);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        Intent i = getIntent();
        url = i.getStringExtra("url");
        title = nz(i.getStringExtra("title"));
        source = nz(i.getStringExtra("source"));
        summary = nz(i.getStringExtra("summary"));
        date = i.getLongExtra("date", 0);
        setTitle(source.length() > 0 ? source : getString(R.string.app_name));

        progress = (ProgressBar) findViewById(R.id.progress);
        web = (WebView) findViewById(R.id.web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setBuiltInZoomControls(false);
        ws.setLoadsImagesAutomatically(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                openExternal(u);
                return true;
            }
        });

        // show the feed's own text immediately, then replace it with the extracted article
        web.loadDataWithBaseURL(url, page(source, title, "<p>" + esc(summary) + "</p>", date),
                "text/html", "utf-8", null);

        new Thread(new Runnable() {
            public void run() {
                String body = null;
                String pageTitle = title;
                String site = source;
                try {
                    Http.Response r = Http.get(url, 3 * 1024 * 1024);
                    String html = new String(r.data, charset(r));
                    Readability.Result res = Readability.extract(html, r.finalUrl);
                    if (res.textLength >= 200 || res.hasMedia) {
                        body = res.html;
                        if (pageTitle.length() == 0) pageTitle = res.title;
                        if (site.length() == 0) site = res.site;
                    }
                } catch (Exception ignored) {
                }
                final String bodyHtml = body != null ? body
                        : "<p>" + esc(summary) + "</p><p class=\"note\">"
                          + esc(getString(R.string.reader_failed)) + " <a href=\"" + esc(url) + "\">"
                          + esc(getString(R.string.open_original)) + "</a></p>";
                final String t = pageTitle, s = site;
                ui.post(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        progress.setVisibility(View.GONE);
                        web.loadDataWithBaseURL(url, page(s, t, bodyHtml, date),
                                "text/html", "utf-8", null);
                    }
                });
            }
        }).start();
    }

    private static String charset(Http.Response r) {
        String name = null;
        if (r.contentType != null) {
            Matcher m = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(r.contentType);
            if (m.find()) name = m.group(1);
        }
        if (name == null) {
            String head = new String(r.data, 0, Math.min(r.data.length, 3000));
            Matcher m = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
            if (m.find()) name = m.group(1);
        }
        try {
            if (name != null && java.nio.charset.Charset.isSupported(name)) return name;
        } catch (Exception ignored) {
        }
        return "UTF-8";
    }

    private String page(String site, String t, String bodyHtml, long when) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<style>").append(CSS).append("</style></head><body>");
        if (site.length() > 0) sb.append("<div class=\"site\">").append(esc(site.toUpperCase(Locale.getDefault()))).append("</div>");
        if (t.length() > 0) sb.append("<h1>").append(esc(t)).append("</h1>");
        if (when > 0) {
            sb.append("<div class=\"date\">").append(esc(DateUtils.formatDateTime(this, when,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH)))
              .append("</div>");
        }
        sb.append(bodyHtml).append("</body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void openExternal(String u) {
        if (u == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.reader, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { finish(); return true; }
        if (id == R.id.action_browser) { openExternal(url); return true; }
        if (id == R.id.action_share) {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, (title.length() > 0 ? title + "\n" : "") + url);
            startActivity(Intent.createChooser(s, getString(R.string.share)));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
