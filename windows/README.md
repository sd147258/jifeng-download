# 疾风下载 · Windows 桌面版

一个轻量的多线程下载器：HTTP/HTTPS 分段加速、断点续传、暂停继续、任务队列、m3u8 分片合并。
纯 Python + 标准库实现（引擎不依赖第三方 HTTP 库），打包为单文件 exe，无需安装。

![界面](docs/screenshot.png)

## 功能

### 下载正确性（核心）

- **错误页拦截**：探测链接时读取 `Content-Type`，返回 `text/html`、`application/json`、
  `text/javascript`、`application/xhtml+xml`、`text/css` 等网页 / 接口类型时直接判为不可下载，
  给出中文原因（需要登录 / 链接过期 / 防盗链拦截），**不入队、不落盘**。
- **完成后文件头校验**：下载完成先比对字节数，再读取文件头魔数复核：
  - 若是 `<html` / `<!doctype` / JSON `{` / `[` 开头，判定为错误页，**删除文件并报错**，不留半成品；
  - 按魔数修正扩展名：`ftyp`→mp4、`ID3 / FFFx`→mp3、`1A45DFA3`→mkv、`PK`→zip、`Rar!`→rar、
    `7z…`→7z、`%PDF`→pdf、`fLaC`→flac、`OggS`→ogg、`RIFF+AVI `→avi、`1F8B`→gz。
- **Content-Type 推断扩展名**：URL 没有扩展名时按响应类型推断
  （`video/mp4`→.mp4、`video/quicktime`→.mov、`video/x-flv`→.flv、`audio/mp4`→.m4a、
  `audio/mpeg`→.mp3、`application/pdf`→.pdf、`application/zip`→.zip 等）；推断不出用 `.bin`，
  **永远不会落成 `.txt`**。
- **大小校验**：完成后用 `Content-Length` 核对实际字节数，不符判为出错并保留断点文件，可继续补齐，
  不会静默当作成功。

### 请求头与防盗链

- 每个请求都带齐全套浏览器请求头：`User-Agent`、`Accept`、`Accept-Language`、
  `Accept-Encoding: identity`；分段请求走 `keep-alive` 连接复用，单流 / 坏服务器连接用完即关。
- **Referer**：下载框下方有独立 Referer 输入框，防盗链站点填写文件所在网页地址即可；
  探测和每个分段请求都会带上。
- **Cookie / 自定义请求头**：设置面板可填全局 Cookie，以及每行一个 `Key: Value` 的自定义请求头。
- **重试策略**：超时、连接重置 / 拒绝、5xx、429 等网络类错误按指数退避重试 3 次；
  `403 / 404 / 410 / 401 / 416` 不重试，直接给出中文原因
  （403 提示可能被防盗链拦截、试试补 Referer；401 提示需要登录并填 Cookie）。

### m3u8 流媒体

- 识别 `.m3u8` 链接及 `application/vnd.apple.mpegurl` 等类型。
- Master 播放列表解析 `#EXT-X-STREAM-INF`，选 **BANDWIDTH 最高**的一路。
- 分片 **4 路并发**下载，单个分片独立重试；分片进度写入断点文件，可暂停 / 续传。
- **fMP4**（含 `#EXT-X-MAP`）：初始化分片 + 媒体分片直接拼接输出 `.mp4`。
- **TS 分片**：先合并为 `.ts`；若本机 PATH（或程序同目录）存在 `ffmpeg`，调用
  `ffmpeg -c copy` 转封装为 `.mp4`；**没有 ffmpeg 则保留 `.ts`**，任务备注写明
  “TS 分片已合并，可用常见播放器直接播放；安装 ffmpeg 后会自动转成 mp4”。

### 网页链接解码

粘一个网页链接，或一整段带链接的分享文案，程序自动解析出最佳媒体直链并入队下载，**不弹候选列表**。

- **链接提取**：从整段文案里抽出第一个 `http/https` 地址，自动去掉尾部中文、标点和括号，无需手工裁剪。
- **抓取与跳转**：移动端 UA 请求，带 `Accept`、`Accept-Language`、`Referer`；手动跟随 301/302/303/307/308 跳转链并记录每一跳最终地址。
- **候选抽取**：
  - `<meta property="og:video*">` / `og:audio`
  - `<video src>` / `<source src>`
  - JSON 字段：`playAddr`、`play_addr`、`playUrl`、`downloadAddr`、`masterUrl`、`srcNoMark`、`photoUrl`、`stream_url`、`baseUrl`、`backupUrl` 等，含 `url_list` 数组
  - 正则兜底 `.mp4 / .m4v / .mov / .flv / .webm / .m4a / .mp3 / .aac / .ogg` 直链
  - m3u8 master 播放列表选 `BANDWIDTH` 最高的一路
  - 页面 JSON 里已给出的直链字段：快手 `srcNoMark / photoUrl`、微博 `stream_url`、B站 `baseUrl / backupUrl` 等；**只认页面里真实出现的地址，不按 id 拼造播放接口**
- **转义与归一**：解开 `\u002F`、`\u0026`、`\/`、`&amp;amp;`、`&amp;`；`//` 开头补 scheme；相对地址按页面 URL 解析；`/playwm/` 替换为 `/play/`。
- **择优规则**：命中 `playwm / sample / preview / thumb / cover / poster / avatar / _1.mp4 / watermark / low_quality` 的候选直接排除；类型优先级 视频 > m3u8 > 音频；来源优先级 平台 / JSON > og:video > m3u8 > video 标签 > 正则兜底；同组内带宽大者优先。
- **防盗链贯通**：解码出的候选自动把所属页面 URL 作为 `Referer` 传给下载引擎，探测和每个分段请求都会带上；也可在 Referer 框手动覆盖。
- **浏览器兜底**：解码失败时保留原页面地址，点「浏览器打开原页」可在系统浏览器里打开该页面，自行到平台内保存或核对链接。

**支持矩阵（2026-11-20 实测口径）**：

| 类别 | 页面形态 | 解码表现 |
| --- | --- | --- |
| 可直连 | 普通网页 `<video src>` / `<source src>`、`og:video` meta、页面 JSON 里直接带 mp4/m4a/m3u8 地址、公开 m3u8 master 列表、直链文件 URL | 抽出最佳候选直接入队，自动带页面 Referer |
| 需登录 / 签名 | 抖音（`v.douyin.com` / `iesdouyin.com` / `douyin.com`）、小红书（`xhslink.com` / `xiaohongshu.com`）、B站（`b23.tv` / `bilibili.com`）、快手（`v.kuaishou.com` / `kuaishou.com`）、微博（`t.cn` / `weibo.com`） | 静态 HTML 里取不到直链时如实判平台锁定，给出平台级文案，**不拼造地址**；可在 App 内保存本地，或粘贴可直连的 mp4/m3u8 链接 |

> 实测：抖音短链 302 落到 `iesdouyin.com/share/video/<id>/`，200 返回的是 `_ROUTER_DATA` 空壳页，`play_addr / url_list / playAddr / video_id / .mp4` 均无命中；播放接口要签名，蜘蛛 UA 直接 404。B站页面有 `__INITIAL_STATE__` 与 `playinfo`，但 `baseUrl / backupUrl` 同样不输出到静态页。

**失败分类与提示含义**：

| 分类 | 状态栏提示要点 | 下一步建议 |
| --- | --- | --- |
| 文案里没有链接 | 没找到 http/https 地址 | 检查分享文案是否完整复制 |
| 链接失效 / 跳转失败 | 短链跳转失败或链接已失效 | 核对原页面，重新分享 |
| 403 拒绝访问 | 可能被防盗链拦截 | 在 Referer 栏填页面地址，或补充 Cookie |
| 返回接口 / 网页数据 | 返回的不是可下载文件 | 可能需要登录 / 会员，或平台未提供直链 |
| 页面无媒体地址 | 网页里没解析到媒体地址 | 可能需要登录 / 会员，或该平台不提供直链 |
| 平台锁定 | 该平台网页需要登录态 / 签名才给直链 | 按文案在 App 内保存本地，或点「浏览器打开原页」核对；也可粘贴直连 mp4/m3u8 |
| 仅 m3u8 | 只解析到播放列表，自动走分片合并 | 无需操作，按 m3u8 任务下载 |

### 磁力链接（BT）下载（v1.10 新增）

- 输入框直接粘 `magnet:?xt=urn:btih:…`（整段分享文案里夹着磁力链也能捞出），
  也可粘 `thunder://` 迅雷链接——自动 Base64 还原，还原出磁力就走 BT，还原出 http 直链就走普通下载。
- 解析走标准 BitTorrent 协议：`btih` 同时支持 40 位 hex 与 32 位 Base32；先从 tracker
  （HTTP 与 UDP/BEP15 两种 announce 都支持，磁力里所有 `tr=` 逐一尝试）取 peer 列表，
  再经扩展协议（握手扩展位 + `ut_metadata` 分片）把种子 info 拿下来，并用 SHA1 校验 info hash。
- 下载按分片顺序进行，每片下完即逐片 SHA1 校验，坏片重取（同一 peer 最多 3 次，之后换 peer）；
  已有文件会先扫描出「已校验过的连续前缀」，所以**关掉再开能续传**。
- 全局限速（设置页 KB/s）对 BT 同样生效；任务卡片下方会显示「磁力 · 顺序分片 · N 个做种者」。
- 单文件种子下完直接落盘到下载目录，文件名取自磁力的 `dn=`（没有则用 `磁力_<hash8>`），
  完成后照常做魔数与扩展名校验；多文件种子会明确提示「这个种子是多文件，当前版本只支持单文件」。
- 只做下载，不做种、不占上行；不接 DHT / PEX，所以磁力里必须带可用的 tracker。

### 下载速度与并发说明

**段数如何自适应（自动档，分段数选「自动」）**

两级决策：先按文件大小起步，运行中再按实测带宽动态升段。

| 文件大小 | 起步段数 | 说明 |
| --- | --- | --- |
| < 2 MB | 1 | 小文件单流，省掉握手 / 多线程开销 |
| 2 – 8 MB | 2 | |
| 8 – 64 MB | 4 | |
| 64 – 200 MB | 8 | |
| ≥ 200 MB | 16 | |

- 每段不小于 256 KB；动态升段上限 16 段。
- 运行中若最大的在途段剩余区间足够大，监控线程会把它二分为两段，用更多并发连接对抗「服务器对单连接限速」；段失败重分段同理，剩余区间一分为二交给两个任务，而不是整段从头重试。
- 手动指定分段数时以手动为准，不再自动升段。

**什么时候退化成单流**

- 服务器不支持 Range（响应 `Accept-Ranges: none`，或对 Range 请求直接返回 200 整段）：立即退回单线程顺序下载，任务行显示 1 线程。
- 小文件（< 2 MB 且服务器快）在自动档走单段。
- 单连接已被限速但段数达到上限（16）后，不再继续升段。

**连接与超时策略**

- 支持 Range 的分段请求走 Keep-Alive 连接池，同主机复用连接，避免每段 / 每次重试重走三次握手；单流、坏服务器连接用完即关，不回流池。
- 建连超时（15s）与读超时（30s）分开：读超时只中断当前段，该段从断点续传，不会拖死整段。
- 缓冲：socket 接收缓冲 512 KB，按 256 KB 块读取；落盘带 128 KB 写缓冲，不逐块 fsync，只在状态保存 / 收尾时落盘。

**默认值与限速含义**

- 默认 8 段并发、同时 2 个任务（任务数设置里可调 1–4）；自动档在运行中按需升段。
- 全局限速单位 KB/s，`0` = 不限速；令牌桶全局生效，保存后立即对新下载生效。
- 断点续传：分段进度写入 `<文件名>.jf.json`，关程序 / 断网 / 强杀后重开可从断点接着下，续传计数不重复累加。

**本机实测（loopback，perf 测速台，一次真实下载）**

| 文件 | 服务器 | 改前 MB/s · 段数 | 改后 MB/s · 段数 | 期望 MD5 |
| --- | --- | --- | --- | --- |
| 5 MB | 不限速 | 12.42 · 2 | 12.40 · 2 | `cf6ff82d43c5b5d8b504127fdcc56455` |
| 50 MB | 不限速 | 110.29 · 4 | 110.18 · 4 | `375c1622cb10187ce9825fa1079e7022` |
| 5 MB | 每连接 512 KB/s | 1.00 · 2 | 1.55 · 6 | `cf6ff82d43c5b5d8b504127fdcc56455` |
| 50 MB | 每连接 512 KB/s | 2.08 · 4 | 5.41 · 16 | `375c1622cb10187ce9825fa1079e7022` |
| 1 MB | 不限速 | 2.47 · 2 | 2.49 · 1 | `a7d1e1d2ce246c112ef4544dc2fe1bb1` |

> loopback 不限速两档已接近本机上限，改后持平；提速主要体现在「单连接被限速」场景：靠更多并发连接抬高聚合吞吐。数字为本机一次实测，不同网络 / 机器会有差异。

### 界面

- **分类筛选**：顶部按 视频 / 音频 / 压缩包 / 文档 / 其他 / 全部 切换。
- **多选批量操作**：单击选中、Ctrl 点选加减选、「全选」；批量暂停 / 继续 / 删除，
  删除前二次确认并列出将要删除的文件。
- **设置面板**：分段数（自动 / 2 / 4 / 8 / 16）、限速 KB/s、并行任务数、默认下载目录、
  全局 Cookie、自定义请求头、User-Agent、剪贴板监听开关。
- 每条任务显示中文状态与可指导下一步的失败原因（不展示英文异常原文）。
- **双击任务**打开文件；**右键菜单**：打开文件 / 打开所在目录 / 复制链接。

## 使用

1. 双击 `dist\疾风下载.exe`（首次运行 Windows 可能提示「已保护你的电脑」，点「更多信息 → 仍要运行」）
2. 粘贴直链、`.m3u8` 地址，或网页 / 分享文案；勾选「自动解码网页链接 / 分享文案」时点「开始下载」会先解码再入队，也可直接点「网页解码」；防盗链站点在 Referer 框填网页地址
3. 也可以在命令行直接把链接交给它：`疾风下载.exe https://example.com/a.zip`
4. 下载默认保存到 `%USERPROFILE%\Downloads\疾风下载`，可在「设置」里改

配置保存在 `%APPDATA%\JifengDownload\settings.json`。

## 从源码运行 / 打包

```powershell
cd windows
py -3 app.py                 # 直接运行
py -3 test_engine.py
py -3 test_bt.py        # 磁力（需先起本机 swarm）         # 引擎测试（本地起 Range 服务，73 项断言）
py -3 test_sniffer.py        # 网页解码测试（本地服务造页面，53 项断言）

# 打包单文件 exe（spec 内已带 app.ico）
py -3 -m PyInstaller --noconfirm --clean JifengDownload.spec
# 产物 dist\JifengDownload.exe；再复制一份中文名：
Copy-Item dist\JifengDownload.exe "dist\疾风下载.exe"
```

## 实现说明

- `engine.py`：下载引擎，不依赖 tkinter，可单独测试。
  - `probe()`：只发 `Range: bytes=0-0` 探测总大小 / 是否支持分段 / 文件名 / `Content-Type`，不下整段文件头，错误页直接拒绝；
  - 分段走 Keep-Alive 连接池复用同主机连接；`ThreadPoolExecutor` 受控线程池（上限 16）；单文件句柄加锁 `seek/write`；代际号（generation）隔离新旧线程，避免暂停后重复写；
  - 段失败按剩余区间二分重分段；读超时按段断点续传；令牌桶全局限速；`Manager` 调度器按并行上限排队；
  - m3u8 解析、分片并发、合并与 ffmpeg 转封装均在引擎内完成。
- `sniffer.py`：网页解码模块，纯标准库（`urllib` + 正则），不依赖第三方 HTTP/HTML 库；
  链接提取、跳转跟随、候选抽取、择优、失败分类均在模块内，可脱离 GUI 单独测试。
- `bt.py`：BT 客户端模块，纯标准库（`socket` + `struct` + `hashlib` + `base64`），bencode 编解码（按结构定位字典边界，不靠字符匹配）、磁力与迅雷链解析、HTTP/UDP tracker announce、peer 握手与 `ut_metadata` 取种子、顺序分片下载与逐片 SHA1、前缀扫描续传，全部可脱离 GUI 单测。
- `app.py`：tkinter 深色界面，分类筛选、多选批量、设置面板、400ms 轮询刷新；解码在后台线程执行，
  结果经队列回主线程刷新，关闭时暂停并保存所有任务。
- `test_engine.py`：本地 HTTP 服务覆盖分段 / 续传 / 无 Range 回退 / 命名 / 取消 / HTML·JSON 拒绝 /
  防盗链 403→200 / 大小不符 / Content-Type 定扩展 / 错误页删文件 / m3u8 TS 合并 / fMP4 直拼 mp4 /
  分段自适应三档 / 二分重分段计数 / 读超时段恢复 / 限速并发优于单流 / 限速对比 / 强杀后续传。
- `test_bt.py` / `e2e_bt.py` / `e2e_exe_bt.py`：本机起一个迷你 swarm（tracker 9000 + 做种 9001，单文件 788,493 B、piece 262,144），分别验证 bt.py 模块直下、Manager 端到端、打包 exe 端到端，三处落盘文件 SHA1 均为 `438ad3bf24fed937085ffa101ed06cdb23e30007`，与源文件一致。
- `test_sniffer.py`：本地服务造页面，覆盖 302 跳转链、unicode 转义、分享文案抽链接、og:video /
  video 标签 / play_addr 抽取、m3u8 master 选最高带宽、预览广告图排除、失败分类文案、
  平台域名识别、`_ROUTER_DATA` 空壳判定、平台锁定分类、
  Referer 从解码结果贯通到下载请求（不带 Referer 403、带 Referer 200）。

## 已知限制

- 直链、普通文件服务器、m3u8（HLS）与磁力 / BT 单文件种子；不支持 DRM 加密流媒体、
  需要登录态的私有接口（可手动填 Cookie 尝试）。
- BT 只支持带 tracker 的单文件种子（不做 DHT/PEX、不做种）；磁力没带 `tr=` 时会直接给出中文原因。
  顺序下载意味着前段先到，但当前桌面版不提供 BT 边下边播。
- 网页解码只识别公开页面里直接给出的媒体字段；**抖音、小红书、B站、快手、微博等需要签名校验、
  登录态或接口加密的页面，静态 HTML 拿不到有效直链时会如实判为「平台锁定」并给出对应文案，
  不按 id 拼造播放地址**；可在 App 内保存本地，或粘贴可直连的 mp4/m3u8 链接，部分站点补 Cookie 后可重试。
- m3u8 不做转码，只做分片合并 / 无损转封装；fMP4 直拼 mp4，TS 依赖本机 ffmpeg 才能转 mp4。
- 单文件下载，不做整站抓取；服务器限速时多线程也无法突破。
- 本次打包环境**未安装 ffmpeg / ffprobe**：m3u8 测试只验证了 TS 合并字节数与同步字节（`0x47`），
  **未用 ffprobe 读取时长、未做播放器实测**；安装 ffmpeg 后 TS 路径会自动走转封装。
- 仅用于下载你有权获取的文件。

## v1.11：动态分段（连接复用，对标 IDM）

以前段数虽然会自适应升到 16，但"某段下完、它的线程就退出"这件事没解决——慢的那段照样独占收尾时间。
现在 `_segment_loop` 在段下完之后不 return，而是调 `_take_tail()`：

- 在 `task.lock` 里找**剩余最大且剩余 ≥ 256 KB** 的段，从 `start+done+remaining//2` 处切开，
  后半截交给当前线程继续下，前半截留给原线程。
- 切到剩余小于 `TAIL_MIN_BYTES = 128 KB` 就停，防止无限碎片化。
- 关掉可对照：`set JF_NO_TAIL=1`。

### 对照实测（`test_rebalance.py`，同一 8 MB 文件、服务端第 8 条连接限 30 KB/s）

| 配置 | 耗时 | 段数 | md5 |
|---|---|---|---|
| `JF_NO_TAIL=1`（旧行为） | **34.87 s** | 8 | `58b120a5…` 一致 |
| 默认（连接复用） | **9.02 s** | 16 | `58b120a5…` 一致 |

打包后的 exe 也实测过：`dist\疾风下载.exe http://127.0.0.1:8770/mix.bin`
落盘 `下载\疾风下载\mix.bin` 8,388,608 字节，md5 `58b120a538a3db0da0cdcd54e0e78c1b`，
界面卡片显示「8.0 MB · 已保存 · 16 线程」。

> `test_engine.py` 里三处"段数必须正好等于 8/2/4"的断言改成了 `>=`——
> 连接复用会让段数继续增加，这是新语义，不是回归；内容、大小、md5、断点续传那些断言一条没动，全部通过（0 fail）。

## v1.12 · FTP 下载（和手机端对齐）

### 做了什么

- 新增 `ftp.py`：基于标准库 `ftplib` 的最小客户端——匿名 / `user:pass@host`、
  PASV、`TYPE I`、`SIZE`、`REST` 续传、64 KB 分块回调、暂停/取消靠回调抛 `Stop` 打断、
  限速走引擎的令牌桶；`human_error()` 把 530/550/超时/域名解析失败翻成中文。
- 引擎接线：`_prepare()` 里 `ftp://` 短路到 `_prepare_ftp()`，独立线程 `_ftp_run()` 跑状态机，
  按已落地字节数作为 REST 起点，最多自动续 5 轮；完成走 `_finalize_local_file()` +
  `cleanup_part()`，写 `status=done`（`kind=ftp` 已持久化，重启后不会被当成 HTTP 任务）。
- 界面放行 `ftp://`，提示语改为「FTP 下载（单连接，支持断点续传）」，标题副文案加上了 FTP。
- 版本 1.11 → **1.12**。

### 实测（`test_ftp.py`，本地 FTP 服务，8 MB `mix.bin`，第一次传输 3,000,000 字节处断流）

```
status done  total 8388608  downloaded 8388608  secs 2.50
md5 58b120a538a3db0da0cdcd54e0e78c1b  size 8388608
```

即：426 掉线后自动 `REST 3000000` 续传，一次跑完，md5 与源一致。
打包后的 exe 启动正常（`ftp` 模块随 `engine` 一起打进单文件），可直接粘贴 `ftp://` 地址。

### 边界

- 只用 PASV，不碰 FTPS / 主动模式；单连接，无多线程分段。
- 不支持目录递归。

## v1.13 · DHT（BEP5）自动找源

### 做了什么

- 新增 `dht.py`：和手机端同一套算法——按 XOR 距离从近到远迭代 `get_peers`，
  `values` 拿 peer、`nodes`（26 字节/条）扩候选，最多 96 次查询、1.8 秒一轮、默认 12 秒预算。
- `bt.get_peers()`：tracker 全挂或没有 `tr=` 时自动退到 DHT，拿到 peer 就返回；
  引擎里「没有 tracker 就报错」的死路删掉了，失败文案改成「tracker 和 DHT 都没找到做种者」。
- 版本 1.12 → **1.13**；标题副文案加 `磁力 DHT`。

### 实测

1. **无 tracker 的完整链路**（`test_dht_bt.py`）：磁力只带 `xt=urn:btih:`，
   没有 `tr=`；DHT 从本地响应器拿到做种者 → 直连 9001 端口 → 下完
   `mov_bbb.mp4` 788,493 字节，`md5 198918f40ecc7cab0fc4231adaf67c96` 与源一致，4.5 秒、
   1 个 peer。
2. **公网 DHT**：Ubuntu 24.04.3 桌面的真实 info_hash `d160b8d8…`，
   只给公网引导节点 → 14 秒预算内拿到 **4 个真实 peer**（58.255.83.98:39552 等）。
3. 打包后的 exe 里 `dht`、`ftp`、`bt`、`engine` 四个模块都在（解包 PYZ 核对）。

### 边界

- 只查询不 announce，不做 BEP44 / IPv6。
- UDP 被墙/被封时安静失败，最后给中文原因。

## v1.14：DHT peer 缓存

和 Android 侧同款：`dht.py` 里 `_CACHE` 按 info_hash 记住 10 分钟
（`CACHE_TTL = 600`，最多 24 条，超了清空），命中直接返回，不再发 UDP。
空结果不写缓存——一次抖动不该让某个 hash 在十分钟内被判死。

本地 stub 上的冷热对比（`test_dht_cache.py`，先 `clear_cache()` 再连查两次）：

```
cold peers=1 2.09s
hot  peers=1 0.0000s
```

回归：`test_engine.py` 0 fail；`test_dht_bt.py`（磁力完全不带 `tr=`、只靠 DHT 找源）
下完 `mov_bbb.mp4` 788,493 B，md5 `198918f40ecc7cab0fc4231adaf67c96`，2.5 s / 1 peer。

## v1.15：WMV / WMA 识别

和 Android 侧同一套规则：`CONTENT_TYPE_EXT` 补 `video/x-ms-wmv`、`video/x-ms-asf`、
`application/vnd.ms-asf`、`audio/x-ms-wma`；`ext_from_head` 补 ASF 魔数 → `.wmv`。
Windows 自带播放器能放 WMV，所以「打开」不用改，双击就是默认关联程序。

实测（`test_wmv.py`，同一个本地服务）：

```
/movie.wmv   status=done name=movie.wmv  size=1048592 ext_ok=True
/noext       status=done name=noext.wmv  size=1048592 ext_ok=True   # 只有 ASF 头
/ctonly      status=done name=ctonly.wmv size=1048576 ext_ok=True   # 只有 Content-Type
```

回归 `test_engine.py` 仍 0 fail；exe 重新打包后 PYZ 里 `bt / dht / engine / ftp / sniffer` 均在。
