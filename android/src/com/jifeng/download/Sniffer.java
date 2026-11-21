package com.jifeng.download;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 超级解码：把网页/分享链接解析成可以直接下载的文件地址。
 * 不依赖 Android，桌面 JVM 也能跑。抓到的播放地址会带上所属页面 URL 作为 Referer，
 * 页面 Set-Cookie 收集后一并交给下载器，应对 CDN 防盗链。
 */
public class Sniffer {

    // ---- 失败分类（可执行中文提示） ----
    public static final String REASON_NO_LINK = "没有在文本里找到链接：请复制完整的分享文案或直链再试。";
    public static final String REASON_EMPTY = "输入是空的：先粘贴分享文案、直链或磁力链，或者从浏览器用「分享」发过来。";
    public static final String REASON_KOU_LING = "这段是平台口令/分享文案，里面没有网址：抖音、快手口令无法直接解析成直链，"
            + "请在 App 内「保存本地」，或者粘贴带 http(s) 的链接、磁力链（magnet:?xt=...）。";
    public static final String REASON_NO_MEDIA = "这是网页，但里面没有可直接下载的媒体地址：可能需要登录/会员，或该平台不提供直链。可以复制页面里的直链再试。";
    public static final String REASON_M3U8_ONLY = "只找到 m3u8 播放列表，会走分片合并下载。";
    public static final String REASON_FORBIDDEN = "返回 403：可能缺少 Referer/Cookie，或被防盗链、登录验证拦截。";
    public static final String REASON_REDIRECT = "短链跳转失败或链接已失效，请检查链接是否完整、是否过期。";
    public static final String REASON_JSON = "链接返回的是接口数据，不是文件：可能需要登录、链接已失效或被防盗链拦截。";
    public static final String REASON_LOGIN_SIGN = "该平台需要登录或签名校验，无法直连下载。";
    // ---- 平台级原因（线上实测：静态 HTML 不吐直链，需签名/登录态） ----
    public static final String REASON_DOUYIN = "抖音分享页已不再在网页里输出直链（播放接口需要签名）：请在抖音内用「保存本地」，或粘贴可直连的 mp4/m3u8 链接。";
    public static final String REASON_KUAISHOU = "快手分享页需要登录/签名才给播放地址：请在快手内保存本地，或粘贴可直连的 mp4/m3u8 链接。";
    public static final String REASON_WEIBO = "微博视频页需要登录态才给直链：请在微博内保存，或粘贴可直连的 mp4/m3u8 链接。";
    public static final String REASON_BILIBILI = "B站需要登录态才能取播放地址：请粘贴带 baseUrl 的直链。";
    public static final String REASON_SIGN = "该平台接口需要签名或登录态，网页里拿不到直链：请在 App 内保存本地，或粘贴可直连的 mp4/m3u8 链接。";

    public static class Hit {
        public final String url;
        public final String kind;   // MP4 / M4A / MP3 / MOV / FLV / 文件
        public final String note;   // 域名或来源
        public final String referer; // 所属页面 URL，下载时作为 Referer 带上
        public final String cookie;  // 页面收集到的 Cookie，能拿到就带上

        public Hit(String url, String kind, String note, String referer, String cookie) {
            this.url = url;
            this.kind = kind;
            this.note = note;
            this.referer = referer == null ? "" : referer;
            this.cookie = cookie == null ? "" : cookie;
        }

        public String label() {
            String host = "";
            try {
                host = new URL(url).getHost();
            } catch (Exception ignored) {
            }
            return kind + " · " + (host.length() > 0 ? host : note);
        }
    }

    public static class Result {
        public final List<Hit> hits = new ArrayList<>();
        public boolean sawHtml;
        public boolean sawM3u8;
        public boolean sawShell;          // 命中平台 SSR 空壳（_ROUTER_DATA/loaderData/INITIAL_STATE 但无详情字段）
        public String failReason = "";   // 分类后的中文原因，命中失败时填
        public String cookie = "";        // 页面收集到的 Cookie
        public String m3u8Url = "";       // 页面里第一条 m3u8 地址（只有切片时直接下载合并）
        public String seedUrl = "";       // 原始输入链接
        public String finalUrl = "";      // 跳转落地后的最终 URL
        public int bodyLen;               // 落地页正文长度（字符）
        public final List<String> hops = new ArrayList<>(); // 跳转链路，便于定位环节
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Pattern URL_RE = Pattern.compile("https?://[^\\s\"'<>\\u4e00-\\u9fa5]+");
    /** 磁力链：整段文案里夹着也能捞出来。 */
    private static final Pattern MAGNET_RE = Pattern.compile(
            "magnet:\\?[^\\s\"\'<>\\u4e00-\\u9fa5]+", Pattern.CASE_INSENSITIVE);
    /** 迅雷专用链。 */
    private static final Pattern THUNDER_RE = Pattern.compile(
            "thunder://[^\\s\"\'<>\\u4e00-\\u9fa5]+", Pattern.CASE_INSENSITIVE);
    /** 没有 scheme 的裸链接，如 v.douyin.com/xxx、host.com/a.mp4 */
    private static final Pattern BARE_URL_RE = Pattern.compile(
            "((?:[a-z0-9][a-z0-9\\-]*\\.)+(?:com|cn|net|org|tv|io|me|xyz|top|cc|app|so|info)"
                    + "[^\\s\"'<>\\u4e00-\\u9fa5]*/(?:[^\\s\"'<>\\u4e00-\\u9fa5]*))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_RE = Pattern.compile(
            "https?://[^\"'\\s<>]*\\.(?:mp4|m4a|mp3|mov|flv|wmv|wma|asf)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_RE = Pattern.compile(
            "https?://[^\"'\\s<>]*\\.m3u8",
            Pattern.CASE_INSENSITIVE);
    /** 任意属性的媒体地址：src/data-src/data-url/file/video_url 等，常见于播放器初始化参数。 */
    private static final Pattern ATTR_SRC_RE = Pattern.compile(
            "(?:src|data-src|data-url|data-video|file|video_url|play_url)\\s*=\\s*[\"']"
                    + "([^\"'<>]+\\.(?:mp4|m4a|mp3|webm|mov|flv|wmv|wma|asf)[^\"'<>]*)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** m3u8 地址，允许相对路径（//host/x.m3u8、/path/x.m3u8）。 */
    private static final Pattern M3U8_ANY_RE = Pattern.compile(
            "((?:https?:)?//[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*|/[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OG_VIDEO_RE = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"'](?:og:video(?::url|:secure_url)?|twitter:player:stream)[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_SRC_RE = Pattern.compile(
            "<(?:video|source)[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAY_ADDR_RE = Pattern.compile(
            "\"(?:playAddr|play_addr|playUrl|play_url|downloadAddr|download_addr|masterUrl)\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    // 抖音：play_addr.url_list；video_id 拼 aweme.snssdk.com 播放地址
    private static final Pattern DOUYIN_URL_LIST_RE = Pattern.compile(
            "\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUYIN_VIDEO_ID_RE = Pattern.compile(
            "\"video_id\"\\s*:\\s*\"([A-Za-z0-9_\\-]+)\"");
    // 快手：srcNoMark / photoUrl
    private static final Pattern KUAISHOU_RE = Pattern.compile(
            "\"(?:srcNoMark|photoUrl)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    // 微博：stream_url
    private static final Pattern WEIBO_RE = Pattern.compile(
            "\"stream_url\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    // B站：dash baseUrl / backupUrl
    private static final Pattern BILIBILI_RE = Pattern.compile(
            "\"(?:baseUrl|backupUrl)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** 分享文案里常见的平台口令特征（这类文本本身不含网址）。 */
    private static boolean looksLikeKouLing(String text) {
        if (text == null) return false;
        String t = text.replace(" ", "");
        return t.contains("复制打开抖音") || t.contains("复制此链接")
                || t.contains("打开快手") || t.contains("打开小红书")
                || t.contains("打开微博") || t.contains("口令")
                || t.contains("复制本条信息") || t.contains("长按复制");
    }

    /** 从任意整段分享文案里抽出第一个 http/https 链接，并去掉尾部中文与标点。 */
    /**
     * 从一段文本里拆出所有链接（磁力 / 迅雷 / http(s)），按它们在文本里的先后顺序排列并去重。
     * 用于「一次粘一串链接」的批量下载。
     */
    public static List<String> splitLinks(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.trim().length() == 0) return out;
        java.util.TreeMap<Integer, String> seen = new java.util.TreeMap<Integer, String>();
        Matcher mg = MAGNET_RE.matcher(text);
        while (mg.find()) seen.put(mg.start(), mg.group());
        Matcher mt = THUNDER_RE.matcher(text);
        while (mt.find()) seen.put(mt.start(), mt.group());
        Matcher mu = URL_RE.matcher(text);
        while (mu.find()) {
            int st = mu.start();
            if (seen.containsKey(st)) continue;
            String u = clean(mu.group());
            if (u.length() == 0) continue;
            seen.put(st, u);
        }
        for (String u : seen.values()) {
            boolean dup = false;
            for (String x : out) {
                if (x.equalsIgnoreCase(u)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(u);
        }
        return out;
    }

    public static String extractUrl(String text) {
        if (text == null) return "";
        Matcher m = URL_RE.matcher(text);
        if (!m.find()) return "";
        return clean(m.group());
    }

    /** 从任意文本（含分享文案）里解析出候选直链。 */
    public static Result resolve(String text) {
        Result r = new Result();
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            r.failReason = REASON_EMPTY;
            return r;
        }
        // 磁力链：不用抓页面，直接当成一条 BT 候选
        Matcher mg = MAGNET_RE.matcher(body);
        if (mg.find()) {
            String mag = clean(mg.group());
            r.seedUrl = mag;
            r.hits.add(new Hit(mag, "磁力", "BT", "", ""));
            return r;
        }
        // 迅雷专用链：先还原真实地址再走后续流程
        Matcher th = THUNDER_RE.matcher(body);
        if (th.find()) {
            String real = Thunder.decode(clean(th.group()));
            if (real != null && real.length() > 0) {
                if (Thunder.isMagnet(real)) {
                    r.seedUrl = real;
                    r.hits.add(new Hit(real, "磁力", "BT", "", ""));
                    return r;
                }
                body = real;
            }
        }
        String seed = extractUrl(body);
        if (seed.isEmpty()) {
            Matcher bare = BARE_URL_RE.matcher(body);
            if (bare.find()) seed = "https://" + clean(bare.group());
        }
        if (seed.isEmpty()) {
            r.failReason = looksLikeKouLing(body) ? REASON_KOU_LING : REASON_NO_LINK;
            return r;
        }
        r.seedUrl = seed;
        try {
            fetch(seed, r);
        } catch (Exception e) {
            // 不吞异常：环节定位写进失败原因与跳转链路
            r.failReason = REASON_REDIRECT + "（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）";
        }
        if (r.hits.isEmpty()) classifyEmpty(r, seed);
        return r;
    }

    private static void fetch(String seed, Result r) throws Exception {
        String url = seed;
        StringBuilder cookieJar = new StringBuilder();
        boolean jsonBody = false;

        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (cookieJar.length() > 0) {
                c.setRequestProperty("Cookie", cookieJar.toString());
            }
            collectCookies(c, cookieJar);

            int code = c.getResponseCode();
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.trim().length() == 0) {
                    r.failReason = REASON_REDIRECT;
                    return;
                }
                String next = resolveUrl(url, loc.trim());
                r.hops.add(url + " -> " + next);
                url = next;
                continue;
            }

            if (code == 401 || code == 403) {
                r.failReason = REASON_FORBIDDEN;
                c.disconnect();
                return;
            }
            if (code == 400 || code == 404 || code == 410) {
                r.failReason = REASON_REDIRECT;
                c.disconnect();
                return;
            }
            if (code >= 500) {
                r.failReason = REASON_REDIRECT + "（服务器 " + code + "）";
                c.disconnect();
                return;
            }

            r.cookie = cookieJar.toString();
            r.finalUrl = url;

            if (!type.startsWith("text/html") && !type.contains("json") && !type.contains("javascript")) {
                // 本身就是文件，所属页面即它自己的来源
                addHit(r, url, seed, cookieJar.toString());
                c.disconnect();
                return;
            }

            r.sawHtml = true;
            jsonBody = type.contains("json");
            String body = readBody(c);
            c.disconnect();
            r.bodyLen = body.length();
            body = unescape(body);
            parseBody(body, url, cookieJar.toString(), r);
            return;
        }
        r.failReason = REASON_REDIRECT;
    }

    private static void classifyEmpty(Result r, String seed) {
        if (r.failReason.length() > 0) return;
        if (r.sawM3u8) {
            r.failReason = REASON_M3U8_ONLY;
            return;
        }
        String probe = r.finalUrl.length() > 0 ? r.finalUrl : seed;
        String platform = platformOf(probe);
        if ("xhs".equals(platform)) {
            r.failReason = REASON_LOGIN_SIGN;
            return;
        }
        if ("douyin".equals(platform)) {
            r.failReason = REASON_DOUYIN;
            return;
        }
        if ("kuaishou".equals(platform)) {
            r.failReason = REASON_KUAISHOU;
            return;
        }
        if ("weibo".equals(platform)) {
            r.failReason = REASON_WEIBO;
            return;
        }
        if ("bilibili".equals(platform)) {
            r.failReason = REASON_BILIBILI;
            return;
        }
        if (r.sawShell) {
            r.failReason = REASON_SIGN;
            return;
        }
        if (r.sawHtml) {
            r.failReason = REASON_NO_MEDIA;
        }
    }

    /** 按 host 识别平台；识别不到返回空串。 */
    private static String platformOf(String url) {
        String h = "";
        try {
            h = new URL(url).getHost().toLowerCase();
        } catch (Exception ignored) {
        }
        if (h.contains("douyin") || h.contains("iesdouyin")) return "douyin";
        if (h.contains("kuaishou") || h.contains("gifshow") || h.contains("kwai")) return "kuaishou";
        if (h.contains("xiaohongshu") || h.contains("xhslink")) return "xhs";
        if (h.contains("weibo") || h.equals("t.cn")) return "weibo";
        if (h.contains("bilibili") || h.contains("b23.tv")) return "bilibili";
        return "";
    }

    private static void parseBody(String body, String pageUrl, String cookie, Result r) {
        LinkedHashMap<String, String> found = new LinkedHashMap<>();
        collect(body, OG_VIDEO_RE, found, pageUrl);
        collect(body, VIDEO_SRC_RE, found, pageUrl);
        collect(body, DOUYIN_URL_LIST_RE, found, pageUrl);
        collectVideoId(body, found, pageUrl);
        collect(body, KUAISHOU_RE, found, pageUrl);
        collect(body, WEIBO_RE, found, pageUrl);
        collect(body, BILIBILI_RE, found, pageUrl);
        collect(body, PLAY_ADDR_RE, found, pageUrl);
        collectUrl(body, MEDIA_RE, found, pageUrl);
        collect(body, ATTR_SRC_RE, found, pageUrl);

        Matcher m3 = M3U8_ANY_RE.matcher(body);
        while (m3.find()) {
            String u = normalize(clean(m3.group()), pageUrl);
            if (u == null) continue;
            r.sawM3u8 = true;
            if (r.m3u8Url.length() == 0) r.m3u8Url = u;
            break;
        }

        boolean looksJson = body.trim().startsWith("{") || body.trim().startsWith("[");
        if (found.isEmpty()) {
            if (looksJson) {
                r.failReason = REASON_JSON;
                return;
            }
            // 平台 SSR 空壳：页面只挂了 _ROUTER_DATA / loaderData / RENDER_DATA / __INITIAL_STATE__，
            // 没有 video 详情字段，直链由前端 JS 带签名请求，静态 HTML 抽不到。
            if (body.contains("_ROUTER_DATA") || body.contains("loaderData")
                    || body.contains("RENDER_DATA") || body.contains("__INITIAL_STATE__")) {
                r.sawShell = true;
            }
            return;
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            addHit(r, e.getKey(), e.getValue(), cookie);
        }
    }

    private static void addHit(Result r, String url, String referer, String cookie) {
        for (Hit h : r.hits) {
            if (h.url.equals(url)) return;
        }
        r.hits.add(new Hit(url, kindOf(url), "网页解析", referer, cookie));
    }

    private static void collect(String body, Pattern p, LinkedHashMap<String, String> out, String base) {
        Matcher m = p.matcher(body);
        while (m.find()) {
            String u = clean(m.group(1));
            u = normalize(u, base);
            if (u == null) continue;
            if (u.contains(".m3u8")) continue;
            out.putIfAbsent(u, base);
            if (out.size() >= 12) return;
        }
    }

    /** 无捕获组的媒体链接正则：整段匹配作为候选。 */
    private static void collectUrl(String body, Pattern p, LinkedHashMap<String, String> out, String base) {
        Matcher m = p.matcher(body);
        while (m.find()) {
            String u = clean(m.group(0));
            u = normalize(u, base);
            if (u == null) continue;
            if (u.contains(".m3u8")) continue;
            out.putIfAbsent(u, base);
            if (out.size() >= 12) return;
        }
    }

    /** 抖音 video_id：拼 aweme.snssdk.com 播放地址。 */
    private static void collectVideoId(String body, LinkedHashMap<String, String> out, String base) {
        Matcher m = DOUYIN_VIDEO_ID_RE.matcher(body);
        while (m.find()) {
            String vid = clean(m.group(1));
            if (vid.length() == 0) continue;
            String u = "https://aweme.snssdk.com/aweme/v1/play/?video_id=" + vid;
            out.putIfAbsent(u, base);
            if (out.size() >= 12) return;
        }
    }

    private static String normalize(String u, String base) {
        if (u.startsWith("//")) u = "https:" + u;
        if (!u.startsWith("http")) {
            try {
                u = new URL(new URL(base), u).toString();
            } catch (Exception ignored) {
                return null;
            }
        }
        // 回指页面自身的相对地址不是媒体候选，跳过。
        try {
            URL baseUrl = new URL(base);
            URL cand = new URL(u);
            if (cand.getHost().equals(baseUrl.getHost())
                    && cand.getPath().equals(baseUrl.getPath())) {
                return null;
            }
        } catch (Exception ignored) {
        }
        return u.replace("/playwm/", "/play/");
    }

    private static void collectCookies(HttpURLConnection c, StringBuilder jar) {
        Map<String, List<String>> headers = c.getHeaderFields();
        if (headers == null) return;
        List<String> setCookies = null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                setCookies = e.getValue();
                break;
            }
        }
        if (setCookies == null) return;
        for (String sc : setCookies) {
            if (sc == null) continue;
            String pair = sc.split(";")[0].trim();
            if (pair.length() == 0 || !pair.contains("=")) continue;
            if (jar.length() > 0) jar.append("; ");
            jar.append(pair);
        }
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 400 * 1024) break;
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String unescape(String s) {
        return s.replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\/", "/").replace("&amp;", "&")
                .replace("\\u0026", "&");
    }

    private static String clean(String s) {
        if (s == null) return "";
        String u = s.trim();
        String tail = ",;:\"'，。！？、；：…）】」』》”’";
        while (u.length() > 0 && tail.indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String resolveUrl(String base, String loc) throws Exception {
        return new URL(new URL(base), loc).toString();
    }

    // ---------------------------------------------------------------- 整页抓取
    public static class PageResult {
        public final java.util.List<String> links = new java.util.ArrayList<>();
        public String pageUrl = "";
        public String cookie = "";
        public String error = "";
        public boolean sawHtml;
    }

    private static final Pattern LINK_ATTR_RE = Pattern.compile(
            "(?:href|src|data-src|data-original|data-url|data-href)\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_IN_TEXT_RE = Pattern.compile("https?://[^\\s\"'<>\\\\]+");

    /** 可下载后缀白名单：图片/音视频/压缩包/文档/安装包/种子。 */
    private static final java.util.HashSet<String> DL_EXT = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "m3u8",
                    "mp3", "m4a", "aac", "flac", "wav", "ape", "ogg",
                    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "torrent",
                    "apk", "exe", "msi", "dmg", "iso", "img",
                    "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv",
                    "bin", "dat", "img", "raw",
                    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg"));

    /** 地址是否以可下载的后缀结尾（忽略查询串/锚点）。 */
    public static boolean looksDownloadable(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String scheme = u.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            String path = u.getPath() == null ? "" : u.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) return false;
            return DL_EXT.contains(name.substring(dot + 1).toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 整页抓取：把一个页面里所有看起来能下的链接捞出来（html / json 都认）。
     * 只做静态 HTML 抽取，不跑 JS；返回按出现顺序去重的绝对地址，最多 limit 条。
     */
    public static PageResult grabPage(String seed, int limit) {
        PageResult pr = new PageResult();
        if (seed == null || seed.trim().length() == 0) {
            pr.error = REASON_EMPTY;
            return pr;
        }
        String url = seed.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) url = "https://" + url;
        if (limit <= 0) limit = 80;
        StringBuilder jar = new StringBuilder();
        String body = null;
        String type = "";
        try {
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(9000);
                c.setReadTimeout(9000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (jar.length() > 0) c.setRequestProperty("Cookie", jar.toString());
                collectCookies(c, jar);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.trim().length() == 0) {
                        pr.error = "页面跳转缺少目标地址";
                        return pr;
                    }
                    url = resolveUrl(url, loc.trim());
                    continue;
                }
                if (code == 401 || code == 403) {
                    c.disconnect();
                    pr.error = "这个页面需要登录或拒绝访问（HTTP " + code + "）";
                    return pr;
                }
                if (code != 200) {
                    c.disconnect();
                    pr.error = "页面打不开（HTTP " + code + "）";
                    return pr;
                }
                type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
                if (!type.startsWith("text/html") && !type.contains("json")
                        && !type.contains("javascript")) {
                    // 本身就是个文件：直接把地址返回，由调用方决定下不下
                    c.disconnect();
                    pr.pageUrl = url;
                    pr.links.add(url);
                    return pr;
                }
                body = readBody(c);
                c.disconnect();
                break;
            }
        } catch (Exception e) {
            pr.error = "抓页面失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return pr;
        }
        if (body == null) {
            pr.error = "页面读不出内容";
            return pr;
        }
        pr.pageUrl = url;
        pr.cookie = jar.toString();
        pr.sawHtml = true;
        body = unescape(body);
        java.util.LinkedHashMap<String, Boolean> found = new java.util.LinkedHashMap<>();
        Matcher m = LINK_ATTR_RE.matcher(body);
        while (m.find() && found.size() < limit * 4) {
            String u = normalize(clean(m.group(1)), url);
            if (u != null && looksDownloadable(u)) found.put(u, Boolean.TRUE);
        }
        Matcher m2 = URL_IN_TEXT_RE.matcher(body);
        while (m2.find() && found.size() < limit * 4) {
            String u = clean(m2.group());
            if (looksDownloadable(u)) found.put(u, Boolean.TRUE);
        }
        for (String u : found.keySet()) {
            if (pr.links.size() >= limit) break;
            pr.links.add(u);
        }
        return pr;
    }

    private static String kindOf(String url) {
        String u = url.toLowerCase();
        if (u.contains(".mp4")) return "MP4";
        if (u.contains(".m4a")) return "M4A";
        if (u.contains(".mp3")) return "MP3";
        if (u.contains(".mov")) return "MOV";
        if (u.contains(".flv")) return "FLV";
        return "文件";
    }

    /**
     * 多候选择优：优先明确视频类型；对 playwm、sample/preview/thumb/cover/poster、
     * 预览小图后缀 _1.mp4 降权；同资源多镜像取第一个可用。
     */
    public static Hit bestHit(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return null;
        Hit best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Hit h : hits) {
            int score = kindScore(h.kind);
            String u = h.url.toLowerCase();
            if (u.contains("playwm")) score -= 30;
            if (u.contains("sample") || u.contains("preview") || u.contains("thumb")
                    || u.contains("cover") || u.contains("poster") || u.contains("avatar")
                    || u.contains("logo")) {
                score -= 40;
            }
            if (u.matches(".*_1\\.mp4.*")) score -= 25;
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }

    private static int kindScore(String kind) {
        if ("MP4".equals(kind)) return 100;
        if ("MOV".equals(kind) || "FLV".equals(kind)) return 90;
        if ("M4A".equals(kind)) return 80;
        if ("MP3".equals(kind)) return 70;
        return 60;
    }
}
