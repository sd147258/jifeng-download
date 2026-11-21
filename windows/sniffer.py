# -*- coding: utf-8 -*-
"""
疾风下载 · 网页链接解码（纯标准库）

输入一段分享文案 / 网页链接 → 抓取页面（跟随跳转）→ 抽取候选直链 →
按规则选出最佳候选，连同所属页面 URL（作为 Referer）返回。

候选覆盖：
  - <meta property="og:video*"> / og:audio
  - <video src> / <source src>
  - JSON 字段：playAddr / play_addr / playUrl / downloadAddr / masterUrl /
    srcNoMark / photoUrl / stream_url / baseUrl / backupUrl
  - 正则兜底 .mp4 / .m4a / .mp3 / .mov / .flv / .webm 直链
  - m3u8 master playlist 中 BANDWIDTH 最高的一路
  - 平台字段：抖音 video_id 拼 play 地址、快手、微博、B站

失败分类（中文文案可直接展示给用户）：
  no_link          文案里没有链接
  invalid          短链跳转失败 / 链接失效
  forbidden        403 防盗链 / 需补 Referer·Cookie
  interface_data   返回的是接口 / 网页数据，不是文件
  no_media         页面里没有媒体地址（需登录 / 会员 / 平台不提供直链）
  platform_locked  平台页面需要登录态 / 签名，网页里取不到直链
  only_m3u8        只解析到 m3u8（会走分片合并，不算失败）

注意：抖音 / 小红书 / B站等线上页面需要签名或登录态，静态 HTML 抽不到直链时
如实判 platform_locked，不拼造播放地址。
"""

import re
import urllib.error
import urllib.parse
import urllib.request

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
)

MEDIA_EXT = ("mp4", "m4v", "mov", "flv", "webm", "m4a", "mp3", "aac", "ogg")
IMAGE_EXT = ("png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp", "avif")

# 预览 / 封面 / 水印小图等降权或排除关键字
PREVIEW_RE = re.compile(
    r"(playwm|sample|preview|thumb|cover|poster|avatar|/thumb/|_1\.mp4|"
    r"low_?quality|watermark)", re.I)

_URL_RE = re.compile(r"https?://[^\s，。、；：！？“”‘’（）【】《》<>\"'\\\]]+", re.I)
_META_RE = re.compile(r"<meta\b[^>]*>", re.I)
_ATTR_RE = re.compile(r"(property|content|src|href|type)\s*=\s*[\"']([^\"']*)[\"']", re.I)
_VIDEO_SRC_RE = re.compile(r"<video\b[^>]*src=[\"']([^\"']+)[\"']", re.I)
_SOURCE_SRC_RE = re.compile(r"<source\b[^>]*src=[\"']([^\"']+)[\"']", re.I)
_JSON_FIELD_RE = re.compile(
    r"\"(playAddr|play_addr|playUrl|downloadAddr|masterUrl|srcNoMark|photoUrl|"
    r"stream_url|stream_url_hd|baseUrl|backupUrl|playApi)\"\s*:\s*\"([^\"]+)\"", re.I)
_URL_LIST_RE = re.compile(r"\"url_list\"\s*:\s*\[(.*?)\]", re.I)
_MEDIA_URL_RE = re.compile(
    r"(https?:)?//[^\s\"'\\<>]+\.(?:mp4|m4v|mov|flv|webm|m4a|mp3|aac|ogg)"
    r"(?:\?[^\s\"'<>]*)?", re.I)
_M3U8_RE = re.compile(r"(https?:)?//[^\s\"'<>]+\.m3u8(?:\?[^\s\"'<>]*)?", re.I)
_VARIANT_RE = re.compile(
    r"#EXT-X-STREAM-INF[^\n]*?BANDWIDTH=(\d+)[^\n]*\n([^\s#][^\n]*)", re.I)

# 平台域名 → 平台标识与平台级失败文案
PLATFORM_RULES = [
    ("douyin",
     re.compile(r"(?:^|\.)(?:v\.douyin\.com|iesdouyin\.com|douyin\.com)$", re.I),
     "抖音分享页已不在网页里输出直链（播放接口需要签名）：请在抖音 App 内用「保存本地」，"
     "或粘贴可直连的 mp4 / m3u8 链接"),
    ("kuaishou",
     re.compile(r"(?:^|\.)(?:v\.kuaishou\.com|kuaishou\.com)$", re.I),
     "快手页面需要登录 / 签名才能取播放地址：请在快手 App 内保存，或粘贴可直连的 mp4 / m3u8 链接"),
    ("xiaohongshu",
     re.compile(r"(?:^|\.)(?:xhslink\.com|xiaohongshu\.com)$", re.I),
     "小红书页面需要登录态或签名才能取播放地址：请在 App 内保存，或粘贴可直连的 mp4 / m3u8 链接"),
    ("weibo",
     re.compile(r"(?:^|\.)(?:t\.cn|weibo\.com|sinaimg\.cn)$", re.I),
     "微博页面需要登录态才能取播放地址：请在微博内保存，或粘贴可直连的 mp4 / m3u8 链接"),
    ("bilibili",
     re.compile(r"(?:^|\.)(?:b23\.tv|bilibili\.com)$", re.I),
     "B站播放地址需要登录态 / 签名才能取到：请在浏览器登录后重试，或粘贴可直连的 mp4 / m3u8 链接"),
]

# 单页应用空壳标记：页面里埋了这些状态块，但没有任何视频详情字段
_SHELL_MARKERS = ("_ROUTER_DATA", "loaderData", "__INITIAL_STATE__", "playinfo")
_DETAIL_RE = re.compile(
    r"play_addr|playAddr|playUrl|downloadAddr|masterUrl|srcNoMark|photoUrl|"
    r"stream_url|baseUrl|backupUrl|url_list|\"uri\"|video_id|aweme_id|"
    r"\.mp4|\.m3u8", re.I)

FAIL_MESSAGES = {
    "no_link": "没有在文案里找到链接，请粘贴包含 http/https 地址的分享内容",
    "invalid": "短链跳转失败或链接已失效，请核对原页面后重试",
    "forbidden": "服务器拒绝访问（403）：可能被防盗链拦截，可在 Referer 栏填写页面地址或补充 Cookie",
    "interface_data": "返回的是接口 / 网页数据而不是可下载文件：可能需要登录、会员，或平台未提供直链",
    "no_media": "网页里没有解析到媒体地址：可能需要登录 / 会员，或该平台不提供直链",
    "only_m3u8": "只解析到 m3u8 播放列表，将自动走分片合并下载",
}


# ---------------------------------------------------------------- 链接处理

def extract_url(text):
    """从整段文案里抽出第一个 http/https 链接，去掉尾部中文与标点。"""
    if not text:
        return ""
    m = _URL_RE.search(text)
    if not m:
        return ""
    url = m.group(0)
    url = url.rstrip(".,;:!?，。、；：！？\"'”’）)】>")
    return url


def unescape_url(u):
    """解转义：\\u002F、\\/、&amp;amp;、&amp; 等。"""
    if not u:
        return ""
    u = u.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\u003F", "?")
    u = u.replace("\\/", "/")
    u = u.replace("&amp;amp;", "&").replace("&amp;", "&")
    if "%" in u:
        try:
            u = urllib.parse.unquote(u)
        except Exception:
            pass
    return u.strip()


def normalize_url(raw, base):
    """// 开头补 scheme；相对地址按 base 解析；playwm → play。"""
    if not raw:
        return ""
    u = unescape_url(raw)
    if not u:
        return ""
    if u.startswith("//"):
        u = "https:" + u
    u = urllib.parse.urljoin(base or "", u)
    u = u.replace("/playwm/", "/play/")
    return u


def _media_type(url, attr_type=""):
    low = (attr_type or "").lower()
    if "m3u8" in low or ".m3u8" in url.lower():
        return "m3u8"
    ext = urllib.parse.urlparse(url).path.rsplit(".", 1)[-1].lower()
    if ext in ("mp4", "m4v", "mov", "flv", "webm"):
        return "video"
    if ext in ("m4a", "mp3", "aac", "ogg"):
        return "audio"
    if "audio" in low:
        return "audio"
    if "video" in low:
        return "video"
    return "video"


# ---------------------------------------------------------------- 抓取

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch_page(url, cookie=None, extra_headers=None, timeout=20, max_hops=6):
    """手动跟随 301/302，记录每一跳。返回 dict：ok/status/final_url/url_chain/body/ctype。"""
    headers = {
        "User-Agent": MOBILE_UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
    if cookie:
        headers["Cookie"] = cookie
    if extra_headers:
        for k, v in extra_headers.items():
            if v:
                headers[k] = v

    opener = urllib.request.build_opener(_NoRedirect())
    chain = []
    current = url
    last_status = 0
    for _ in range(max_hops):
        chain.append(current)
        h = dict(headers)
        if len(chain) > 1:
            h["Referer"] = chain[-2]
        else:
            h["Referer"] = current
        req = urllib.request.Request(current, headers=h)
        try:
            resp = opener.open(req, timeout=timeout)
            body = resp.read()
            ctype = resp.headers.get("Content-Type", "")
            final = resp.geturl() or current
            resp.close()
            return {"ok": True, "status": 200, "final_url": final,
                    "url_chain": chain, "body": body.decode("utf-8", errors="replace"),
                    "ctype": ctype}
        except urllib.error.HTTPError as e:
            last_status = e.code
            loc = e.headers.get("Location") if e.headers else None
            if e.code in (301, 302, 303, 307, 308) and loc:
                current = urllib.parse.urljoin(current, unescape_url(loc))
                continue
            body = ""
            try:
                body = e.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            return {"ok": False, "status": e.code, "final_url": current,
                    "url_chain": chain, "body": body,
                    "ctype": e.headers.get("Content-Type", "") if e.headers else ""}
        except Exception as e:
            return {"ok": False, "status": last_status or -1, "final_url": current,
                    "url_chain": chain, "body": "", "ctype": "", "error": str(e)}
    return {"ok": False, "status": 310, "final_url": current, "url_chain": chain,
            "body": "", "ctype": "", "error": "redirect loop"}


# ---------------------------------------------------------------- 候选抽取

def _add(candidates, url, ctype, source, base, bandwidth=0, attr_type=""):
    u = normalize_url(url, base)
    if not u or not u.startswith("http"):
        return
    ext = urllib.parse.urlparse(u).path.rsplit(".", 1)[-1].lower()
    if ext in IMAGE_EXT:
        return
    # JSON / 正则兜底来源：URL 必须带可识别的媒体扩展名（平台 play 端点除外）
    if source in ("json", "regex") and ext not in MEDIA_EXT and ext != "m3u8":
        return
    if any(c["url"] == u for c in candidates):
        return
    candidates.append({
        "url": u,
        "type": ctype or _media_type(u, attr_type),
        "source": source,
        "bandwidth": int(bandwidth or 0),
        "referer": base,
    })


def extract_candidates(body, base_url):
    """从页面文本里抽取全部候选直链。"""
    candidates = []
    if not body:
        return candidates

    # 1) og:video / og:audio meta
    for tag in _META_RE.findall(body):
        attrs = dict((k.lower(), v) for k, v in _ATTR_RE.findall(tag))
        prop = attrs.get("property", "")
        content = attrs.get("content", "")
        if content and re.match(r"og:(video|audio)", prop, re.I):
            _add(candidates, content, "", "og", base_url, attr_type=prop)

    # 2) <video src> / <source src>
    for m in _VIDEO_SRC_RE.finditer(body):
        _add(candidates, m.group(1), "", "video_tag", base_url)
    for m in _SOURCE_SRC_RE.finditer(body):
        _add(candidates, m.group(1), "", "video_tag", base_url,
             attr_type=m.group(0))

    # 3) JSON 字段
    for m in _JSON_FIELD_RE.finditer(body):
        _add(candidates, m.group(2), "", "json", base_url)
    # play_addr 常见为对象："play_addr":{"url_list":["..."]}
    for m in _URL_LIST_RE.finditer(body):
        for um in re.finditer(r"\"([^\"]+)\"", m.group(1)):
            _add(candidates, um.group(1), "", "json", base_url)

    # 4) m3u8 master：选最高带宽一路（仍作为候选记录）
    variants = []
    for m in _VARIANT_RE.finditer(body):
        bw = int(m.group(1))
        variants.append((bw, m.group(2).strip()))
    if variants:
        variants.sort(key=lambda x: x[0], reverse=True)
        _add(candidates, variants[0][1], "m3u8", "m3u8", base_url,
             bandwidth=variants[0][0])
    else:
        for m in _M3U8_RE.finditer(body):
            _add(candidates, m.group(0), "m3u8", "m3u8", base_url)

    # 5) 正则兜底媒体直链
    for m in _MEDIA_URL_RE.finditer(body):
        _add(candidates, m.group(0), "", "regex", base_url)

    return candidates


# ---------------------------------------------------------------- 平台 / 失败分类

def _detect_platform(url, chain=None):
    """按 URL（含跳转链）域名识别平台，返回 (平台标识, 文案) 或 (None, None)。"""
    hosts = [url] + list(chain or [])
    for h in hosts:
        host = urllib.parse.urlparse(h or "").netloc.lower()
        for key, rx, msg in PLATFORM_RULES:
            if rx.search(host):
                return key, msg
    return None, None


def _is_shell(body):
    """页面是单页应用空壳：埋了状态块但没有任何视频详情字段。"""
    if not body:
        return False
    if not any(mark in body for mark in _SHELL_MARKERS):
        return False
    return not _DETAIL_RE.search(body)


def classify_failure(ctype, body, final_url, chain=None):
    """无候选时判定失败分类，返回 (category, message, platform)。"""
    platform, pmsg = _detect_platform(final_url, chain)
    if platform:
        return "platform_locked", pmsg, platform
    if ctype in ("application/json", "text/json"):
        return "interface_data", FAIL_MESSAGES["interface_data"], None
    if ctype in ("text/html", "application/xhtml+xml"):
        if _is_shell(body):
            return "interface_data", FAIL_MESSAGES["interface_data"], None
        return "no_media", FAIL_MESSAGES["no_media"], None
    return "interface_data", FAIL_MESSAGES["interface_data"], None


# ---------------------------------------------------------------- 择优

def pick_best(candidates):
    """
    候选择一规则（便于测试）：
      1. 命中预览 / 封面 / 水印 / 低码率关键字的直接排除；
      2. 类型优先级：video > m3u8 > audio；
      3. 来源优先级：平台 / JSON > og:video > m3u8 > video 标签 > 正则兜底；
      4. 同组内带宽（BANDWIDTH）大者优先。
    """
    if not candidates:
        return None
    type_score = {"video": 3, "m3u8": 2, "audio": 1}
    source_score = {"platform": 6, "json": 5, "og": 4, "m3u8": 4, "video_tag": 3, "regex": 2}

    def score(c):
        if PREVIEW_RE.search(c["url"]):
            return (-1, -1, -1)
        return (type_score.get(c["type"], 0), source_score.get(c["source"], 0), c["bandwidth"])

    ranked = sorted(candidates, key=score, reverse=True)
    best = ranked[0]
    if score(best)[0] < 0:
        return None
    return best


# ---------------------------------------------------------------- 主入口

def sniff(text, cookie=None, extra_headers=None, timeout=20):
    """
    返回 dict：
      ok: bool
      category: no_link / invalid / forbidden / interface_data / no_media / only_m3u8 / ok
      message: 中文提示
      candidate: {url, type, source, bandwidth, referer} 或 None
      page_url: 最终页面 URL（作为 Referer）
      url_chain: 跳转链路
    """
    raw_url = extract_url(text or "")
    if not raw_url:
        return {"ok": False, "category": "no_link",
                "message": FAIL_MESSAGES["no_link"], "candidate": None,
                "page_url": "", "url_chain": [], "platform": None}

    page = fetch_page(raw_url, cookie=cookie, extra_headers=extra_headers, timeout=timeout)
    chain = page.get("url_chain", [])
    final_url = page.get("final_url", raw_url)

    if not page.get("ok"):
        status = page.get("status", 0)
        if status == 403:
            return {"ok": False, "category": "forbidden",
                    "message": FAIL_MESSAGES["forbidden"], "candidate": None,
                    "page_url": final_url, "url_chain": chain, "platform": None}
        return {"ok": False, "category": "invalid",
                "message": FAIL_MESSAGES["invalid"], "candidate": None,
                "page_url": final_url, "url_chain": chain, "platform": None}

    ctype = page.get("ctype", "").split(";")[0].strip().lower()
    body = page.get("body", "")
    candidates = extract_candidates(body, final_url)

    # 直链兜底：落地 URL 本身就是文件（扩展名 / Content-Type 可识别；octet-stream 交给引擎探测）
    if not candidates:
        ext = urllib.parse.urlparse(final_url).path.rsplit(".", 1)[-1].lower()
        if (ext in MEDIA_EXT or ext == "m3u8"
                or ctype.startswith("video/") or ctype.startswith("audio/")
                or "mpegurl" in ctype or ctype == "application/octet-stream"):
            mtype = "m3u8" if ext == "m3u8" else _media_type(final_url)
            candidates.append({"url": final_url, "type": mtype, "source": "direct",
                               "bandwidth": 0, "referer": final_url})

    best = pick_best(candidates)

    if best is None:
        cat, msg, platform = classify_failure(ctype, body, final_url, chain)
        return {"ok": False, "category": cat, "message": msg, "candidate": None,
                "page_url": final_url, "url_chain": chain, "platform": platform}

    if best["type"] == "m3u8":
        return {"ok": True, "category": "only_m3u8",
                "message": FAIL_MESSAGES["only_m3u8"], "candidate": best,
                "page_url": final_url, "url_chain": chain, "platform": None}

    return {"ok": True, "category": "ok",
            "message": "", "candidate": best,
            "page_url": final_url, "url_chain": chain, "platform": None}


def describe(candidate, page_url="", total=0):
    """状态栏一句话反馈：类型 · 域名 · 预计大小（大小未知时不写体积）。"""
    if not candidate:
        return ""
    type_name = {"video": "视频", "audio": "音频", "m3u8": "流媒体"}.get(candidate["type"], "媒体")
    host = urllib.parse.urlparse(candidate["url"]).netloc
    parts = ["已解析：%s · %s" % (type_name, host)]
    if total and total > 0:
        parts.append(engine_human_size(total))
    return " · ".join(parts)


def engine_human_size(n):
    try:
        import engine as _e
        return _e.human_size(n)
    except Exception:
        return str(n)
