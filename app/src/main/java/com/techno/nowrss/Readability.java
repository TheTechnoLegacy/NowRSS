package com.techno.nowrss;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small self-contained "reader mode" extractor (no external libraries, no android.* classes).
 * Finds the main article in an HTML page and returns clean HTML containing only
 * text, headings, lists, quotes, images and videos.
 */
public class Readability {

    public static class Result {
        public String title = "";
        public String site = "";
        public String image = "";
        public String html = "";
        public int textLength = 0;
        public boolean hasMedia = false;
    }

    // ---------------------------------------------------------------- DOM

    private static class Node {
        final String tag; // null = text node
        String text;
        Map<String, String> attrs;
        final List<Node> kids = new ArrayList<Node>();
        Node parent;
        int depth;
        double score;
        int tl = -1, ll = -1;

        Node(String tag) { this.tag = tag; }

        String attr(String k) { return attrs == null ? null : attrs.get(k); }
    }

    private static final Set<String> VOID = set("img", "br", "hr", "input", "meta", "link", "source",
            "track", "wbr", "area", "base", "col", "embed", "param");
    private static final Set<String> RAW = set("script", "style", "textarea", "title", "noscript");
    private static final Set<String> DROP = set("script", "style", "noscript", "nav", "footer", "aside",
            "form", "button", "select", "input", "textarea", "svg", "canvas", "object", "embed",
            "template", "dialog", "head", "label", "menu");
    private static final Set<String> BLOCK = set("p", "div", "section", "ul", "ol", "h1", "h2", "h3",
            "h4", "h5", "h6", "blockquote", "pre", "figure", "table", "article", "main", "hr");
    private static final Set<String> WRAPPABLE = set("div", "section", "article", "main", "td", "th",
            "center", "dd", "dt", "header");
    private static final Set<String> KEEP_INLINE = set("em", "i", "strong", "b", "u", "sub", "sup",
            "code", "small", "mark", "cite", "q", "abbr", "time");
    private static final Set<String> CLOSE_P = set("p", "div", "ul", "ol", "li", "h1", "h2", "h3",
            "h4", "h5", "h6", "blockquote", "pre", "table", "section", "article", "figure", "hr");

    private static final Pattern NEG = Pattern.compile(
            "(?:^|[\\s_-])(comments?|sidebar|footer|share|sharing|social|promo|advert|ads?|sponsor(?:ed)?"
                    + "|related|newsletter|subscribe|cookie|menu|navbar|popup|modal|banner|breadcrumbs?"
                    + "|recommended|outbrain|taboola|widget|paywall|toolbar)(?:$|[\\s_-])");
    private static final Pattern BAD_IMG = Pattern.compile(
            "(avatar|icon|logo|sprite|pixel|tracking|emoji|spacer|blank|1x1)");

    private static Set<String> set(String... a) {
        return new HashSet<String>(Arrays.asList(a));
    }

    // ------------------------------------------------------------- parsing

    private static Node parse(String s) {
        Node root = new Node("#root");
        Node cur = root;
        int i = 0, n = s.length();
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                addText(cur, s.substring(i));
                break;
            }
            if (lt > i) addText(cur, s.substring(i, lt));
            if (s.startsWith("<!--", lt)) {
                int e = s.indexOf("-->", lt + 4);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (lt + 1 < n && (s.charAt(lt + 1) == '!' || s.charAt(lt + 1) == '?')) {
                int e = s.indexOf('>', lt);
                i = e < 0 ? n : e + 1;
                continue;
            }
            boolean closing = lt + 1 < n && s.charAt(lt + 1) == '/';
            int j = lt + (closing ? 2 : 1);
            int k = j;
            while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '-'
                    || s.charAt(k) == ':')) k++;
            if (k == j) {
                addText(cur, "<");
                i = lt + 1;
                continue;
            }
            String name = s.substring(j, k).toLowerCase(Locale.US);
            int e = k;
            char q = 0;
            while (e < n) {
                char c = s.charAt(e);
                if (q != 0) {
                    if (c == q) q = 0;
                } else if (c == '"' || c == '\'') {
                    q = c;
                } else if (c == '>') {
                    break;
                }
                e++;
            }
            String attrStr = s.substring(k, Math.min(e, n));
            i = Math.min(e + 1, n);

            if (closing) {
                Node t = cur;
                while (t != null && t != root && !t.tag.equals(name)) t = t.parent;
                if (t != null && t != root) cur = t.parent;
                continue;
            }

            Node node = new Node(name);
            node.attrs = parseAttrs(attrStr);
            if (CLOSE_P.contains(name) && "p".equals(cur.tag)) cur = cur.parent;
            if (name.equals("li") && "li".equals(cur.tag)) cur = cur.parent;
            node.parent = cur;
            node.depth = cur.depth + 1;
            cur.kids.add(node);
            boolean selfClose = attrStr.trim().endsWith("/");
            if (VOID.contains(name) || selfClose) continue;
            if (RAW.contains(name)) {
                int e2 = indexOfIgnoreCase(s, "</" + name, i);
                if (e2 < 0) {
                    node.text = s.substring(i);
                    i = n;
                } else {
                    node.text = s.substring(i, e2);
                    int e3 = s.indexOf('>', e2);
                    i = e3 < 0 ? n : e3 + 1;
                }
                continue;
            }
            if (node.depth < 250) cur = node; // refuse to nest absurdly deep
        }
        return root;
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int m = needle.length();
        for (int i = from; i + m <= s.length(); i++) {
            if (s.regionMatches(true, i, needle, 0, m)) return i;
        }
        return -1;
    }

    private static void addText(Node cur, String raw) {
        if (raw.length() == 0) return;
        Node t = new Node(null);
        t.text = decode(raw);
        t.parent = cur;
        t.depth = cur.depth + 1;
        cur.kids.add(t);
    }

    private static Map<String, String> parseAttrs(String s) {
        Map<String, String> m = new HashMap<String, String>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '/')) i++;
            int st = i;
            while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '='
                    && s.charAt(i) != '/') i++;
            if (i == st) {
                i++;
                continue;
            }
            String name = s.substring(st, i).toLowerCase(Locale.US);
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            String val = "";
            if (i < n && s.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                if (i < n && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char q = s.charAt(i++);
                    int vs = i;
                    while (i < n && s.charAt(i) != q) i++;
                    val = s.substring(vs, Math.min(i, n));
                    i++;
                } else {
                    int vs = i;
                    while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
                    val = s.substring(vs, i);
                }
            }
            if (!m.containsKey(name)) m.put(name, decode(val));
        }
        return m;
    }

    private static final Map<String, String> ENT = new HashMap<String, String>();
    static {
        String[][] e = {{"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"},
                {"nbsp", " "}, {"ndash", "\u2013"}, {"mdash", "\u2014"}, {"hellip", "\u2026"},
                {"lsquo", "\u2018"}, {"rsquo", "\u2019"}, {"ldquo", "\u201c"}, {"rdquo", "\u201d"},
                {"copy", "\u00a9"}, {"reg", "\u00ae"}, {"trade", "\u2122"}, {"laquo", "\u00ab"},
                {"raquo", "\u00bb"}, {"bull", "\u2022"}, {"middot", "\u00b7"}, {"euro", "\u20ac"},
                {"pound", "\u00a3"}, {"deg", "\u00b0"}};
        for (String[] p : e) ENT.put(p[0], p[1]);
    }

    private static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String name = s.substring(i + 1, semi);
                    String rep = null;
                    if (name.startsWith("#")) {
                        try {
                            int cp = (name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X'))
                                    ? Integer.parseInt(name.substring(2), 16)
                                    : Integer.parseInt(name.substring(1));
                            rep = new String(Character.toChars(cp));
                        } catch (Exception ignored) {
                        }
                    } else {
                        rep = ENT.get(name);
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ helpers

    private static int textLen(Node n) {
        if (n.tl >= 0) return n.tl;
        int t = 0;
        if (n.tag == null) {
            t = n.text.trim().length();
        } else if (!DROP.contains(n.tag)) {
            for (Node k : n.kids) t += textLen(k);
        }
        n.tl = t;
        return t;
    }

    private static int linkLen(Node n) {
        if (n.ll >= 0) return n.ll;
        int t = 0;
        if (n.tag != null) {
            if (n.tag.equals("a")) t = textLen(n);
            else for (Node k : n.kids) t += linkLen(k);
        }
        n.ll = t;
        return t;
    }

    private static double linkDensity(Node n) {
        int t = textLen(n);
        return t == 0 ? 0 : (double) linkLen(n) / t;
    }

    private static int directText(Node n) {
        int t = 0;
        for (Node k : n.kids) if (k.tag == null) t += k.text.trim().length();
        return t;
    }

    private static Node findFirst(Node n, String tag) {
        if (tag.equals(n.tag)) return n;
        for (Node k : n.kids) {
            Node r = findFirst(k, tag);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean containsMedia(Node n) {
        if (n.tag == null) return false;
        if (n.tag.equals("img") || n.tag.equals("video") || n.tag.equals("iframe")) return true;
        for (Node k : n.kids) if (containsMedia(k)) return true;
        return false;
    }

    private static void collectMeta(Node n, Map<String, String> m) {
        if (n.tag != null) {
            if (n.tag.equals("meta")) {
                String key = n.attr("property");
                if (key == null) key = n.attr("name");
                String val = n.attr("content");
                if (key != null && val != null && !m.containsKey(key.toLowerCase(Locale.US))) {
                    m.put(key.toLowerCase(Locale.US), val);
                }
            } else if (n.tag.equals("title") && n.text != null && !m.containsKey("#title")) {
                m.put("#title", decode(n.text).trim());
            }
            for (Node k : n.kids) collectMeta(k, m);
        }
    }

    private static boolean hidden(Node n) {
        if (n.attrs == null) return false;
        if (n.attrs.containsKey("hidden")) return true;
        String st = n.attr("style");
        if (st != null) {
            String t = st.replace(" ", "").toLowerCase(Locale.US);
            if (t.contains("display:none") || t.contains("visibility:hidden")) return true;
        }
        return false;
    }

    private static boolean junk(Node n) {
        if (n.attrs == null) return false;
        String cls = n.attr("class"), id = n.attr("id"), role = n.attr("role");
        if (role != null && (role.equals("navigation") || role.equals("complementary")
                || role.equals("banner") || role.equals("contentinfo"))) return true;
        String s = ((cls == null ? "" : cls) + " " + (id == null ? "" : id)).toLowerCase(Locale.US);
        return s.length() > 1 && NEG.matcher(s).find();
    }

    private static String videoIframeUrl(Node n) {
        String src = n.attr("src");
        if (src == null || src.length() == 0) src = n.attr("data-src");
        return src;
    }

    private static boolean isVideoIframe(Node n) {
        String src = videoIframeUrl(n);
        if (src == null) return false;
        src = src.toLowerCase(Locale.US);
        return src.contains("youtube.com/embed/") || src.contains("youtube-nocookie.com/embed/")
                || src.contains("player.vimeo.com/video/") || src.contains("dailymotion.com/embed/video/");
    }

    private static void prune(Node n) {
        for (int i = n.kids.size() - 1; i >= 0; i--) {
            Node k = n.kids.get(i);
            if (k.tag == null) continue;
            boolean drop = false;
            if (k.tag.equals("iframe")) drop = !isVideoIframe(k);
            else if (DROP.contains(k.tag)) drop = true;
            else if (hidden(k) || junk(k)) drop = true;
            if (drop) n.kids.remove(i);
            else prune(k);
        }
    }

    // ------------------------------------------------------------- scoring

    private static void score(Node n, List<Node> cands) {
        for (Node k : n.kids) {
            if (k.tag == null) continue;
            double s = 0;
            if (k.tag.equals("p") || k.tag.equals("pre") || k.tag.equals("blockquote")) {
                int len = textLen(k);
                if (len >= 25) s = 1 + Math.min(len / 100.0, 3);
            } else if (k.tag.equals("div") || k.tag.equals("td") || k.tag.equals("section")) {
                int len = directText(k);
                if (len >= 80) s = 1 + Math.min(len / 100.0, 3);
            }
            if (s > 0) {
                Node p = k.parent;
                if (p != null) {
                    if (p.score == 0) cands.add(p);
                    p.score += s;
                    Node gp = p.parent;
                    if (gp != null) {
                        if (gp.score == 0) cands.add(gp);
                        gp.score += s / 2;
                    }
                }
            }
            score(k, cands);
        }
    }

    // --------------------------------------------------------------- emit

    private static class Ctx {
        final StringBuilder sb = new StringBuilder();
        final Set<String> seen = new HashSet<String>();
        final String base;
        int media = 0;
        Ctx(String base) { this.base = base; }
    }

    static String abs(String base, String u) {
        if (u == null) return null;
        u = u.trim();
        if (u.length() == 0) return null;
        String l = u.toLowerCase(Locale.US);
        if (l.startsWith("data:") || l.startsWith("javascript:") || l.startsWith("blob:")
                || l.startsWith("mailto:") || l.startsWith("#")) return null;
        try {
            String r;
            if (u.startsWith("//")) r = new URL(new URL(base).getProtocol() + ":" + u).toString();
            else r = new URL(new URL(base), u).toString();
            String rl = r.toLowerCase(Locale.US);
            return (rl.startsWith("http://") || rl.startsWith("https://")) ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String bestImageSrc(Node n, String base) {
        String[] keys = {"src", "data-src", "data-original", "data-lazy-src", "data-lazy"};
        String found = null;
        for (String k : keys) {
            String v = abs(base, n.attr(k));
            if (v != null) {
                found = v;
                break;
            }
        }
        String ss = n.attr("srcset");
        if (ss == null) ss = n.attr("data-srcset");
        if (ss != null && ss.trim().length() > 0) {
            String[] parts = ss.split(",");
            String last = parts[parts.length - 1].trim();
            int sp = last.indexOf(' ');
            String u = abs(base, sp > 0 ? last.substring(0, sp) : last);
            if (u != null && (found == null || n.attr("src") == null || n.attr("src").startsWith("data:"))) {
                found = u;
            }
        }
        return found;
    }

    private static int intAttr(Node n, String k) {
        String v = n.attr(k);
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private static void emitChildren(Node n, Ctx c) {
        for (Node k : n.kids) emit(k, c);
    }

    private static boolean hasBlockChild(Node n) {
        for (Node k : n.kids) if (k.tag != null && BLOCK.contains(k.tag)) return true;
        return false;
    }

    private static void emit(Node n, Ctx c) {
        if (n.tag == null) {
            c.sb.append(esc(n.text));
            return;
        }
        String t = n.tag;
        if (DROP.contains(t)) return;
        if (t.equals("h1")) return; // title is shown separately

        // drop link-heavy blocks ("related stories", tag clouds, ...)
        if ((t.equals("ul") || t.equals("ol") || t.equals("div") || t.equals("section")
                || t.equals("table") || t.equals("p")) && !containsMedia(n)) {
            int len = textLen(n);
            double ld = linkDensity(n);
            if (t.equals("p") ? (ld > 0.9 && len < 100) : (ld > 0.6 && len < 800)) return;
        }

        if (t.equals("img")) {
            String src = bestImageSrc(n, c.base);
            if (src == null || c.seen.contains(src)) return;
            int w = intAttr(n, "width"), h = intAttr(n, "height");
            if ((w >= 0 && w <= 60) || (h >= 0 && h <= 60)) return;
            String cls = (n.attr("class") == null ? "" : n.attr("class")) + " "
                    + (n.attr("id") == null ? "" : n.attr("id"));
            if (BAD_IMG.matcher(cls.toLowerCase(Locale.US)).find()) return;
            c.seen.add(src);
            c.media++;
            String alt = n.attr("alt");
            c.sb.append("<img src=\"").append(esc(src)).append("\" alt=\"")
                    .append(alt == null ? "" : esc(alt)).append("\">");
            return;
        }
        if (t.equals("video")) {
            String src = abs(c.base, n.attr("src"));
            StringBuilder inner = new StringBuilder();
            for (Node k : n.kids) {
                if ("source".equals(k.tag)) {
                    String s = abs(c.base, k.attr("src"));
                    if (s != null) {
                        if (src == null) src = s;
                        String type = k.attr("type");
                        inner.append("<source src=\"").append(esc(s)).append("\"");
                        if (type != null) inner.append(" type=\"").append(esc(type)).append("\"");
                        inner.append(">");
                    }
                }
            }
            if (src == null) return;
            String poster = abs(c.base, n.attr("poster"));
            c.media++;
            c.sb.append("<video controls preload=\"none\"");
            if (poster != null) c.sb.append(" poster=\"").append(esc(poster)).append("\"");
            if (n.attr("src") != null) c.sb.append(" src=\"").append(esc(src)).append("\"");
            c.sb.append(">").append(inner).append("</video>");
            return;
        }
        if (t.equals("iframe")) {
            String raw = videoIframeUrl(n);
            String abs = abs(c.base, raw);
            if (abs == null) return;
            Matcher m = Pattern.compile("youtube(?:-nocookie)?\\.com/embed/([A-Za-z0-9_-]{6,})").matcher(abs);
            c.media++;
            if (m.find()) {
                String id = m.group(1);
                c.sb.append("<a class=\"vid\" href=\"https://www.youtube.com/watch?v=").append(id)
                        .append("\"><img src=\"https://img.youtube.com/vi/").append(id)
                        .append("/hqdefault.jpg\" alt=\"\"><span>\u25B6 Watch video</span></a>");
                return;
            }
            m = Pattern.compile("player\\.vimeo\\.com/video/(\\d+)").matcher(abs);
            if (m.find()) {
                c.sb.append("<a class=\"vid\" href=\"https://vimeo.com/").append(m.group(1))
                        .append("\"><span>\u25B6 Watch video on Vimeo</span></a>");
                return;
            }
            m = Pattern.compile("dailymotion\\.com/embed/video/([A-Za-z0-9]+)").matcher(abs);
            if (m.find()) {
                c.sb.append("<a class=\"vid\" href=\"https://www.dailymotion.com/video/").append(m.group(1))
                        .append("\"><span>\u25B6 Watch video on Dailymotion</span></a>");
                return;
            }
            c.media--;
            return;
        }
        if (t.equals("br")) {
            c.sb.append("<br>");
            return;
        }
        if (t.equals("hr")) {
            c.sb.append("<hr>");
            return;
        }
        if (t.equals("a")) {
            String href = abs(c.base, n.attr("href"));
            if (href == null) {
                emitChildren(n, c);
            } else {
                c.sb.append("<a href=\"").append(esc(href)).append("\">");
                emitChildren(n, c);
                c.sb.append("</a>");
            }
            return;
        }
        if (t.matches("h[2-6]")) {
            if (textLen(n) == 0) return;
            c.sb.append("<").append(t).append(">");
            emitChildren(n, c);
            c.sb.append("</").append(t).append(">");
            return;
        }
        if (t.equals("p") || t.equals("blockquote") || t.equals("pre") || t.equals("li")
                || t.equals("figure") || t.equals("figcaption")) {
            if (textLen(n) == 0 && !containsMedia(n)) return;
            c.sb.append("<").append(t).append(">");
            emitChildren(n, c);
            c.sb.append("</").append(t).append(">");
            return;
        }
        if (t.equals("ul") || t.equals("ol")) {
            if (textLen(n) == 0) return;
            c.sb.append("<").append(t).append(">");
            emitChildren(n, c);
            c.sb.append("</").append(t).append(">");
            return;
        }
        if (KEEP_INLINE.contains(t)) {
            if (textLen(n) == 0) return;
            c.sb.append("<").append(t).append(">");
            emitChildren(n, c);
            c.sb.append("</").append(t).append(">");
            return;
        }
        if (WRAPPABLE.contains(t) && !hasBlockChild(n) && textLen(n) > 0) {
            c.sb.append("<p>");
            emitChildren(n, c);
            c.sb.append("</p>");
            return;
        }
        // everything else (div, section, span, table rows, ...): just its content
        emitChildren(n, c);
        if (t.equals("td") || t.equals("th")) c.sb.append(" ");
    }

    // ---------------------------------------------------------------- API

    public static Result extract(String html, String baseUrl) {
        Result res = new Result();
        Node root = parse(html);
        Map<String, String> meta = new HashMap<String, String>();
        collectMeta(root, meta);

        String title = meta.get("og:title");
        if (title == null || title.trim().length() == 0) title = meta.get("twitter:title");
        if (title == null || title.trim().length() == 0) title = meta.get("#title");
        res.title = title == null ? "" : title.trim();
        String site = meta.get("og:site_name");
        res.site = site == null ? "" : site.trim();
        String img = meta.get("og:image");
        if (img == null) img = meta.get("twitter:image");
        res.image = img == null ? "" : (abs(baseUrl, img) == null ? "" : abs(baseUrl, img));

        Node body = findFirst(root, "body");
        if (body == null) body = root;
        prune(body);

        List<Node> cands = new ArrayList<Node>();
        score(body, cands);
        Node best = null;
        double bestScore = 0;
        for (Node c : cands) {
            double s = c.score * (1 - linkDensity(c));
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        if (best == null) best = body;

        // widen to the parent while it adds little extra text (lead image, standfirst, ...)
        while (best.parent != null && best.parent != root && best.parent != body
                && !"body".equals(best.parent.tag) && !"html".equals(best.parent.tag)
                && textLen(best.parent) <= textLen(best) * 1.25 + 60) {
            best = best.parent;
        }

        Ctx ctx = new Ctx(baseUrl);
        emitChildren(best, ctx);
        String out = ctx.sb.toString();

        if (ctx.media == 0 && res.image.length() > 0 && out.length() > 0) {
            out = "<img src=\"" + esc(res.image) + "\" alt=\"\">" + out;
            ctx.media++;
        }
        res.html = out;
        res.hasMedia = ctx.media > 0;
        res.textLength = textLen(best);
        return res;
    }
}
