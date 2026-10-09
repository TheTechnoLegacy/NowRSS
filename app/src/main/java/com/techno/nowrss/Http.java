package com.techno.nowrss;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/** Tiny HTTP helper: manual redirects (incl. http->https), size cap, TLS 1.2 on old Android. */
public class Http {
    public static class Response {
        public byte[] data;
        public String finalUrl;
        public String contentType;
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 4.4.2; Nexus 5) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/36.0.1985.135 Mobile Safari/537.36";

    private static javax.net.ssl.SSLSocketFactory tlsFactory;

    private static synchronized javax.net.ssl.SSLSocketFactory tls() throws Exception {
        if (tlsFactory == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            tlsFactory = new Tls12SocketFactory(ctx.getSocketFactory());
        }
        return tlsFactory;
    }

    public static Response get(String url, int maxBytes) throws IOException {
        String cur = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,application/rss+xml,application/atom+xml,*/*;q=0.8");
            if (Build.VERSION.SDK_INT < 21 && c instanceof HttpsURLConnection) {
                try {
                    ((HttpsURLConnection) c).setSSLSocketFactory(tls());
                } catch (Exception ignored) {
                }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect without Location");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            try {
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) break;
                }
                in.close();
                Response r = new Response();
                r.data = out.toByteArray();
                r.finalUrl = cur;
                r.contentType = c.getContentType();
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
