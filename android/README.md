# 疾风下载（Android 端）

多线程分段、断点续传的下载管理器；支持把网页 / 分享链接直接解码成可下载的媒体地址。
包名 `com.jifeng.download`，当前版本 **versionCode 7 / versionName 1.7**。

## 一、怎么用

1. **直链下载**：把 http/https 直链粘贴到输入框，点「下载」；或从浏览器/其他 App 分享文本到本应用，会自动抽出链接入队。
2. **网页 / 分享文案解码**：粘贴整段分享文案（例如“看这个视频太搞笑了 7.43 复制打开抖音…https://v.douyin.com/xxx/”），点「超级解码」。应用会：
   - 自动取文本里第一个 http/https 链接，去掉尾部中文与标点；
   - 跟随 301/302/303/307/308 跳转并记录每一跳；
   - 解析页面里的 `og:video`、`<video src>`、`play_addr/play_addr.url_list`、抖音 `video_id`、快手 `srcNoMark/photoUrl`、微博 `stream_url`、B站 `baseUrl/backupUrl` 等字段；
   - 把页面 URL 作为 Referer、页面收集到的 Cookie 一并带到下载请求；
   - 从多个候选里择优（明确视频类型优先；`playwm`、`sample/preview/thumb/cover/poster/avatar/logo`、`_1.mp4` 等预览/水印候选降权）；
   - 解码成功后直接入队，不弹候选列表。

## 二、平台支持范围（实测结论，2026-11-20）

| 平台 | 字段解析规则 | 实测结论 |
| --- | --- | --- |
| 抖音 | 短链 302 跟随；`_ROUTER_DATA` / `play_addr.url_list`；`video_id` 拼 `aweme.snssdk.com/aweme/v1/play/?video_id=`；`/playwm/` 转 `/play/` | **静态 HTML 抽不到直链**。真实短链 `v.douyin.com/...` 302 落到 `iesdouyin.com/share/video/<id>/`，落地页约 6.8 万字节，`play_addr/url_list/video_id/.mp4` 全为 0；详情接口 403 需签名头。应用如实拒绝并提示在抖音内「保存本地」，不伪造 URL |
| B站 | dash `baseUrl` / `backupUrl` | **匿名不给直链**：视频页有 `__INITIAL_STATE__` 与 `playinfo` 键，但 `baseUrl/backupUrl` 为 0，需要登录态；提示粘贴带 baseUrl 的直链 |
| 快手 | `srcNoMark` / `photoUrl` / `<video src>` | 本地夹具字段解析通过；**真实分享页本轮未实测**，线上大概率需登录/签名 |
| 微博 | `stream_url` / mp4 直链 | 本地夹具通过；**真实分享页本轮未实测** |
| 小红书 | — | 判定为**需要登录或签名，无法直连**，不做猜测解析 |
| 普通网页（含 og:video / `<video src>`） | 解析 `og:video`、`<video src>`、转义直链 | **实测可解析**：`w3schools.com/html/html5_video.asp` 抽出 `https://www.w3schools.com/html/mov_bbb.mp4` |
| m3u8 播放列表 | 解析分片清单，分片并发下载后转封装为 MP4 | 本地/Apple 公共流已在历史版本验证；本轮引擎回归通过 |

> 说明：社媒页面结构、签名与风控随版本变化；平台级判定按 host 识别，命中 `_ROUTER_DATA`/`loaderData`/`RENDER_DATA`/`__INITIAL_STATE__` 空壳但无详情字段时，按“接口需签名/登录”处理，不会把落地页自身误当成文件候选。

## 三、失败提示含义

解码 / 下载失败时给出中文原因，而不是笼统的“解析失败”：

- **抖音分享页已不再在网页里输出直链（播放接口需要签名）**：请在抖音内用「保存本地」，或粘贴可直连的 mp4/m3u8 链接。
- **B站需要登录态才能取播放地址**：请粘贴带 baseUrl 的直链。
- **快手 / 微博页面需要登录或签名才给播放地址**：请在 App 内保存本地，或粘贴可直连的 mp4/m3u8 链接。
- **小红书需要登录或签名校验，无法直连下载**。
- **这是网页，但里面没有可直接下载的媒体地址**：可能需要登录/会员，或该平台不提供直链。可以复制页面里的直链再试。
- **只找到 m3u8 播放列表，会走分片合并下载**：候选是播放列表而非直链文件，按分片合并流程处理。
- **返回 403：可能缺少 Referer/Cookie，或被防盗链、登录验证拦截**。
- **短链跳转失败或链接已失效**：检查链接是否完整、是否过期；跳转环节与异常类型会记录在解码链路里。
- **链接返回的是接口数据，不是文件**：可能需要登录、链接已失效或被防盗链拦截。

解码失败弹窗提供「**用浏览器打开**」兜底入口（`ACTION_VIEW` 打开原页面），方便在平台内自行保存；该入口不依赖解码成功。

## 四、任务列表操作

- **单个任务**：卡片上「暂停/继续」「取消/删除」「打开」。
- **长按多选**：长按任意任务卡片进入多选态；选中项高亮并带 ✓ 标记；顶部出现工具条，显示“已选 N 项”，可**批量暂停**、**批量删除**，或点「退出」取消多选。多选态下点卡片即勾选/取消勾选；再点退出按钮或清空选择即退出多选。
- **删除可靠性**：删除已完成任务时，先停引擎，再按 MediaStore URI 删除系统下载目录记录并**回查确认记录已消失**；随后删除私有目录成品、`.jfstate` 状态文件，以及残留的 `.part` / `.norm.mp4` / `.ts` 临时分片。文件被占用或删不掉时，会给出中文原因提示，可到文件管理手动删除，不会静默失败。

## 五、构建与测试

### 构建 APK（无 Gradle，命令行直出）

```powershell
cd android
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

产物：

- `dist\JifengDownload.apk`
- `dist\疾风下载.apk`（同名中文副本，字节数 / MD5 与英文包一致）

签名：`jifeng.keystore`，alias `jifeng`；构建脚本末尾自动执行 `apksigner verify` 并核对双副本字节数。

### 桌面 JVM 测试（不依赖 Android 设备）

纯 Java 内核（下载引擎、m3u8 下载器、解码嗅探）可在桌面 JDK 编译运行：

```powershell
$javac = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\javac.exe"
& $javac -encoding UTF-8 -d out_repro `
  src\com\jifeng\download\DownloadEngine.java `
  src\com\jifeng\download\M3u8Downloader.java `
  src\com\jifeng\download\Sniffer.java `
  test\EngineTest.java test\SnifferTest.java test\EngineCorrectnessTest.java

$java = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe"
& $java -Dfile.encoding=UTF-8 -cp out_repro SnifferTest
& $java -Dfile.encoding=UTF-8 -cp out_repro EngineTest
& $java -Dfile.encoding=UTF-8 -cp out_repro EngineCorrectnessTest
# 真实链路探针（需联网）：抖音短链应判签名拒绝，w3schools 页面应抽出 mov_bbb.mp4
& $java -Dfile.encoding=UTF-8 -cp out_repro RealLinkProbe
```

覆盖：分段/暂停/续传/重载/MD5 一致；HTML/JSON 落地页拒绝；防盗链 403；扩展名与文件头校验；m3u8 选流与分片拼接；短链 302 跳转链路；unicode / `\/` / `&amp;` 转义直链；整段分享文案抽链接；候选择优排除预览/广告图；平台级失败分类文案；Referer/Cookie 贯通。

### 设备侧本地验收服务

```powershell
py -3 test\device_test_server.py     # 127.0.0.1:8000
adb reverse tcp:8000 tcp:8000
```

端点：`/ok.bin`（支持 Range 的随机文件）、`/page`（无媒体 HTML）、`/decode`（og:video / 转义 play_addr / `<video src>` 解码页）、`/error`（JSON 错误体）、`/hotlink`（无 Referer 403，带 Referer 返回文件）。

## 六、已知限制 / 本轮未完成项

- **抖音**：已用真实短链在桌面 JVM 验证为“静态 HTML 无直链、接口需签名”，应用如实拒绝；未做也不尝试伪造播放地址。
- **B站**：已验证匿名页面不给 `baseUrl`，需登录态；未实现登录 Cookie 注入。
- **快手 / 微博**：本地夹具字段解析通过，**真实分享页本轮未实测**，线上是否需登录/签名待复测。
- 小红书按“需登录/签名”拒绝，不支持直连。
- 多选批量暂停 / 删除的触摸交互在雷电模拟器上**无法点击验证**（该模拟器对第三方 App 不派发 `input tap/swipe` 触摸事件）；已通过代码路径、视图布局、dex 字符串与启动无崩溃留存证据，**需真机确认**长按与工具条操作。
- 批量分组、下载限速、通知栏增强留待后续版本。


## 站内播放（v1.9）
- 已完成任务的卡片上，「打开」按钮在音视频文件下显示为「播放」，点卡片本身也会进播放器。
- 播放器：进度条可拖动定位、当前/总时长、暂停继续、全屏（横屏 + 隐藏系统栏，返回键先退全屏）、「其他播放器」兜底、「关闭」。
- 支持格式：mp4/m4v/mkv/mov/flv/webm/3gp/ts/avi 与 mp3/m4a/aac/flac/wav/ogg/opus；编码不被系统支持时给出中文原因（如部分 MKV/HEVC）。
- 播放不打断正在进行的下载；退出播放页会 stopPlayback 并释放 VideoView。
- 自检入口：db shell am start -n com.jifeng.download/.MainActivity --es play_uri <地址> 可直接拉起播放器，便于无触摸环境下验证。

## 任务分组（v1.9）
- 顶部三个页签：进行中 / 已完成 / 失败，括号内是数量，切换后列表只显示该组。
- 已完成组长期保留（依赖 .jfstate 恢复），空态有引导文案。

## 边下边播（v2.3 起）

播放器点开后走本机 127.0.0.1 的 HTTP 服务（LocalStreamServer），按 Range 从正在写入的临时文件吐字节，未下载区域会等待，8 秒无增长则断开。

- 只对单流任务开启；分段任务点播放会提示等下载完成（各段未写满会花屏）。
- mp4 的 moov 索引在文件尾部时，Android 系统播放器要读到尾部才能出画面，会黑屏等待；v2.4 起会显示明确提示，v2.5 起额外尝试向源站预取尾段 1MB（TAIL_PREFETCH）。
- 索引在前（faststart）的 mp4 可正常边下边播。

## 迅雷链 / 磁力链（v2.3 起）

- thunder:// 会 Base64 解码并剥掉 AA…ZZ 包装，还原成真实 http 地址后回填输入框，走正常下载流程。
- magnet:、ed2k: 属 P2P 协议，本工具不支持，会给明确提示。


## v2.6 - v2.8：边下边播真正做到秒出画面

- 根因：以前播放器拿到的地址根本没有数据源（下载中的字节在段临时件 .p0 里，成品要等合并后才出现），所以一直黑屏。
- 现在播放器读的是本机流服务 LocalStreamServer，数据源是正在写入的段临时件，用真实下载进度封顶读取，不再读出预分配空洞。
- mp4 索引（moov）在尾部时：单独向源站按 Range 取回 moov，边下边拷一份 xxx.fast.mp4（顺序 = 头部 + moov + mdat，stco/co64 偏移同步加上位移），播放器一开始就能读到索引，画面立刻出来。
- 开播后该任务锁定为单流（streamSingle），不再二分扩段，保证临时件从头连续写。
- 源站不支持 Range 时不走快速启动，按顺序读，接近下完才出画面。
- 代理副本放在 App 私有目录，一小时后残留的会在下次开播时自动清理。


## v3.0：全局限速 + 通知栏操作

- 设置里新增「全局限速（KB/s，0 = 不限）」，立即生效；所有分段共享一个令牌桶，实测设 200 KB/s 时实际约 250 KB/s（8 段并发的正常误差）。
- 通知栏：下载中给「暂停」+「全部暂停」，暂停状态下给「继续」，不用切回 App。
- 失败任务在列表里直接点「重试」，已完成/已取消的任务按钮变「删除」（只清记录，不删文件）。

## v3.1：声音补齐（完成提醒 + 播放器音量）

- 下载**完成**会单独弹一条「下载完成：文件名」通知，走会响的通道并震动一下；**失败**也提示原因。任务栏那条常驻通知仍是静音的，不吵。
- 设置里新增「下载完成时响铃 + 震动提醒」开关，默认开，可关。
- 播放器控制条新增「音量 N/15」按钮：点开是音量滑杆（拖即时生效），长按一键静音/恢复；播放时音频固定走媒体通道（USAGE_MEDIA / MUSIC），不再被通话或提示音通道抢占。

## v3.2：磁力链接（BT）下载

- 粘贴 `magnet:?xt=urn:btih:...` 就能直接入队，不再提示“不支持磁力链”；支持 40 位 hex 和 32 位 base32 两种 info hash，自动读 `dn` 显示名和全部 `tr` tracker。
- 自研极简 BT 引擎（无第三方库）：HTTP / UDP tracker 找 peer → BEP10 扩展握手 → BEP9 交换 info 元数据（SHA1 校验）→ 16KB 分块顺序拉取 → 每片 SHA1 校验后落盘。
- 走同一套任务模型：进度、速度、暂停/继续/取消、限速（令牌桶）、完成提示音、文件发布到「下载/疾风下载」都一样。

**边界（说清楚）**：
1. 没有 DHT，磁力链必须自带 `tr=`，且还有人在做种，否则报「没有下载源」。
2. 只支持单文件种子；多文件种子会明确提示不支持。
3. 只下载、不做种、不上传。

## v3.3：两个"神秘卡死"的根因修复 + 列表体验

本轮最重要的一条：**磁力下载在手机上跑几十个分块后静默停住、任务列表空着**，两个问题同一个根因。

- 根因：`DownloadService.onCreate()` 末尾无条件调用 `pump()`；此时任务列表还是空的，`pump()` 走
  「没有运行中的任务 → `stopForeground()` + `stopSelf()`」。服务被销毁回调 `onDestroy()` 又把当时
  正在跑的任务全部 `pause()`（`t.stop = true`）。所以 BT 线程拿到 40 多块就被"合法"叫停——它走的是
  `if (t.stop) return;` 的静默分支，不写日志、不报错，看起来就像线程神秘消失（桌面端没有 Service，
  永远跑通）。普通 HTTP 任务同样会在开头几十毫秒内被掐掉。
- 修法：`onCreate` 只在有历史任务时才 `pump()`；`pump()` 的空转清理改成 `stopSelfResult(lastStartId)`，
  只有"没有更新的 start 请求"才允许停服务，新任务入队不会被旧请求误杀。
- 顺手修体验：列表当前页签空了、而别的页签有任务时自动切过去（不再出现"下载明明跑完，界面却是空的"）。

实测（模拟器，BT 单文件 788,493 B / 3 片）：磁力任务 1.5 秒内下完，落地
`/sdcard/Download/疾风下载/mov_bbb (3).mp4`，SHA1 `438ad3bf24fed937085ffa101ed06cdb23e30007`
与源文件一致；通知栏进度从「正在解析链接…」正常刷新到「770.0 KB，已存到 下载/疾风下载」；
HTTP 8 MB 分片任务同样完整落地，页签自动跳到「已完成」。

## v3.5：全面优化（历史记录 / 断网续传 / 名字对齐）

这一版把「用着别扭」的地方集中收了一遍：

- **已完成记录不再丢**：以前 App 进程被系统回收后，「已完成」列表会清空，只剩没下完的任务。
  现在成品发布到系统下载目录后会留一条状态记录（`status=done` + 实际文件名 + 完成时间），
  重启后照旧在「已完成」里，能直接播放/打开、也能删记录或连同文件一起删；最多保留最近 60 条，
  更早的记录自动清掉（已保存的文件不动）。
- **文件名和下载目录对得上**：MediaStore 遇到同名文件会把 `big.bin` 改成 `big (1).bin`，
  以前卡片仍显示 `big.bin`。现在发布完成后回查 MediaStore 拿实际名字并同步到卡片与记录。
- **临时件不再残留**：改名的同时修掉了收尾逻辑——以前按 `t.file()` 删除会删到已改名的旧路径，
  桌面上留一个 8 MB 空占用的临时件；现在由发布流程自己删源文件，实测私有目录只剩一条 `.jfstate`。
- **断网自动暂停、回来自动续传**：注册 `ConnectivityManager.NetworkCallback`，网络掉线先暂停在跑的任务
  （避免一直重试空转耗电），网络恢复后自动把因掉线暂停的任务接着下；手动暂停的任务不会被自动拉起。
- **新任务自动切页签**：在「已完成」页签里加新任务会自动切回「进行中」，不会以为没加上。
- **提示语与日志**：输入框提示改为「粘贴直链 / 磁力链 / 分享文案」；去掉 v3.3 遗留的每次暂停打 8 帧栈日志。

### 验收（雷电模拟器 1280×720，2026-11-20）

| 用例 | 结果 |
| --- | --- |
| 下 8 MB 直链，目标目录已有同名文件 | 卡片显示 `big (2).bin`，与下载目录实际文件一致 |
| 杀进程后重开 | 「已完成 1」，卡片带「今天 16:25」，可播放/删除 |
| 私有目录检查 | 只剩 `big (2).bin.jfstate`（488 B），无残留临时件 |
| 模拟断网（`--es nettest pause`） | 8 段任务在第 2.3 MB 处自动暂停，状态文件 `status=paused` |
| 模拟网络恢复（`--es nettest resume`） | 从 2.3 MB 续到 4.7 MB，最终 10.0 MB 完成，`status=done` |
| 完成后列表 | 「已完成 2」，`slow.bin` / `big (2).bin` 按时间倒序，均带完成时间 |

> 断网那次是用 App 内的自检入口（`am start ... --es nettest pause|resume`）直接触发
> `NetworkCallback` 的同一段逻辑——模拟器一开飞行模式 adb 就断，没法截图取证；
> 真实断网下的回调触发还需要一次真机确认。

## v2.9（补记）：弱网下播放失败自动重连

- 用 `LocalStreamServer` 边下边播时，IO 异常不再直接弹框，改为 3/5/7/9/11 秒退避重连（最多 5 次），
  期间给缓冲提示；服务端数据跟不上时最多等 6 秒再主动断流，避免播放器拿到空洞数据。
- 5 次仍失败才弹「播放失败」，并提示"等下载完再打开 / 换个网络再试"。

## v3.4：链接识别不再"看不懂就报没找到"

用户反馈「没有在文本里找到链接：请复制完整的分享文案或直链再试」且诊断里落地地址为空、正文 0 字符，
实际是这几种输入没被认出来：

- **磁力链**：`magnet:?xt=...` 以前只走「下载」按钮，点「超级解码」会被判成"没有链接"。现在整段分享文案里
  夹着磁力链也能捞出来，直接当 BT 任务入队（实测 1.5 秒下完 788,493 B，SHA1 与源文件一致）。
- **迅雷专用链**：`thunder://` 自动还原真实地址，还原出磁力链就走 BT。
- **没有 http(s) 的裸链接**：`v.douyin.com/xxx`、`host.com/a.mp4` 自动补 `https://` 再抓。
- **平台口令**（"复制打开抖音…"这类没有网址的文案）：单独给原因——口令解析不了，请在 App 内保存本地，
  或粘贴 http(s) 直链 / 磁力链，不再和"没找到链接"混在一起。
- **空输入**：直接提示"输入是空的"，不再打印"落地地址/正文长度/候选数"那组对空文本毫无意义的诊断。


## v3.6：批量链接 + BT 多文件种子

**批量链接**：一次可以贴多条，不用一条条来。

- `Sniffer.splitLinks()` 按出现位置先后收集磁力链、迅雷链、http(s) 直链并去重，
  分享文案里混着多条、中间夹文字也能整段捞出来。
- 输入框粘贴多条后点「下载」自动转批量：逐条解析入队，**一次最多 10 条**，
  超过的部分回填输入框等下批；最后提示「批量加入 N 个任务」，
  解析失败的那几条取第一条原因弹出（比如某条链接端口不通）。
- 实测三条（8.0 MB 直链 + 770 KB 视频 + 一条必失败的 `127.0.0.1:1`）：
  成功入队 2 条并全部下完，失败那条单独报原因，不拖累其余任务。

**BT 多文件种子**：一个种子里的文件树原样落地，不再只认单文件。

- 解析 `info.files`，支持 `path.utf-8` 与分段 `path` 列表；逐段清洗挡掉 `..`，
  不会写到目标目录之外。
- 按全局偏移把分片写进对应文件，跨文件的分片自动切开；0 字节文件、空子目录也会建出来。
- 完成后不再走单文件的"魔数猜扩展名"逻辑（那会把目录整体改名成 `movdir.bin` 并按
  目录大小和总字节数比对，误判成"大小不符"）。多文件任务改为直接置 `done`，
  分片早已逐片 SHA1 校验过。
- 发布走目录分支：`publishFolder()` 逐文件 insert 到 `下载/疾风下载/<种子名>/<原相对目录>`，
  子目录用 `RELATIVE_PATH` 带进去；卡片显示种子名 + 种子总大小；
  「打开」提示用文件管理器查看，「删除」按 `RELATIVE_PATH LIKE` 批量清干净。
- 实测种子 `movdir`（3 文件 / 450,777 B / 7 片）：
  落地 `movdir/part1.bin` 300,000、`movdir/sub/part2.bin` 150,000、`movdir/sub/tiny.txt` 777，
  md5 三个文件全部与源一致，状态 `status=done multi=1`。

### 本次踩的两个坑

1. `FileEnt` 是 `BtDownloader` 的顶层静态类，写成 `Info.FileEnt` 编译报"找不到符号"，
   而构建脚本把 javac 输出过滤了，只显示 "javac failed"——得手动跑 javac 才看得到真实错误。
2. 多文件跑完状态变 `error`、私有目录里目录被改名成 `movdir.bin`：`btFinish()` 无条件调
   `finishArtifact()`，而它是单文件逻辑（读魔数→补扩展名→比对大小）。目录 `renameTo` 是能成功的，
   于是目录被改名，`length()` 拿到 4096 与总字节数不符 → 判 error。

### 自检入口（新增）

```
adb shell am start -n com.jifeng.download/.MainActivity --es batch_text '<多条链接用真换行分隔>'
```

### 能力边界（诚实版）

- 磁力仍**没有 DHT**：种子不带 `tr=`（没有 tracker）时给中文原因，不会静默卡住。
- 分片是"整片顺序 + 逐片 SHA1"，没有并发下载，也**没有重启续传**（重开从头下）。
- 多文件目标目录同名冲突暂不去重，由 MediaStore 各自改名（可能出现 `part1 (1).bin`）。

## v3.7：动态分段（连接复用，对标 IDM）

以前是"开跑前按文件大小切好 8 段，段数固定"，问题很直白：**某条连接先下完，它的带宽就白空了**，
剩下慢的那段得自己慢慢爬完，整任务时间由最慢那段决定。

现在改成 IDM 那种连接复用：一条连接把手上的段下完，不退出线程，而是去
**把当前剩余最大的那段一分为二，接着下它的后半截**。反复切，直到剩余小于 128 KB 为止。

- 谁先下完谁去抢最大剩余段，线程数不变、连接数不变，只是"活"重新分配。
- 切的阈值是 `MIN_TAIL = 128 KB`（两侧各至少 64 KB），避免切出大量碎片。
- 播放器已挂上（`streamSingle`）时不切段，保证边下边播的临时件是从头连续写入的。
- 段临时件按"起始偏移"命名，切出来的新段天然有独立 `.p<start>` 文件；左段被切短后会把
  临时件截到新区间长度，去掉预分配的尾部零字节，合并时不误判。
- monitor 从下载线程池挪到独立线程——否则 8 个池线程被 8 个段占满，第 8 段永远排不上队。

### 对照实测（同一文件、同一服务端、只差这一步）

测试服务 `test/rebalance_server.py`：8 MB 文件，8 条连接里**第 8 条故意限到 30 KB/s**，
其余 320 KB/s。关掉复用用 `--ez noassist true` 复现旧行为，两次都重装清数据、md5 比对源文件。

| 配置 | 耗时 | 连接数 | md5 |
|---|---|---|---|
| 固定 8 段（noassist） | **35.7 s** | 9 | `58b120a5…` 一致 |
| 动态分段（默认） | **12.0 s** | 10（含 1 次接管） | `58b120a5…` 一致 |

慢连接那 1 MB 原本要吃 35 秒，接管后它的剩余区间被快速连接连着切走，整任务缩到 12 秒左右。

### 新增自检入口

```
adb shell am start -n com.jifeng.download/.MainActivity --es start_url '<url>' --ez noassist true
```

## v3.8：整页抓取（一个页面里所有能下的东西一次抓下来）

以前「超级解码」只挑**一个**最佳命中（视频优先），一个网页挂了十几集、一堆图片就只能一条条复制。
现在多了一个入口：输入框下面那行小字 **「抓本页全部链接 ›」**。

- 静态抓 HTML：`href` / `src` / `data-src` / `data-original` / `data-url` / 正文里的裸链接，
  相对地址按页面地址补全，重复链接按 URL 去重。
- 后缀白名单过滤：视频/音频（mp4 mkv avi mov flv webm ts m3u8 mp3 m4a flac wav…）、
  压缩包（zip rar 7z tar gz…）、安装包（apk exe msi dmg iso）、文档（pdf epub doc xlsx ppt…）、
  图片（jpg png gif webp…）、`bin/dat/torrent`。
- 过滤掉 `javascript:`、`data:`、纯目录链接和普通 `.html` 页面，不会把一堆网页当文件下。
- 抓完弹一个多选列表：默认全选，看清了再取消勾；「下载选中的」入队，**referer 和 cookie 一并带下去**，
  所以带防盗链的图床/下载站也能直接下。
- 最多 80 条，一次别把整站扒下来；没有 JS 执行，纯静态抽取（这点和 IDM 的 Grabber 是一个档位）。

### 实测（本地测试页 `test/page_grab.html`）

页面里放了 5 类链接：相对 `big.bin`、根路径 `/mov_bbb.mp4`、`img src=tiny.png`、
`data-url` 重复的绝对地址、以及普通网页 `note.html` 和 `javascript:void(0)`。

- 抓取结果：**3 条**（big.bin / mov_bbb.mp4 / tiny.png），重复的 big.bin 只出现一次，
  两个不该下的都被过滤掉了。
- 全部下完，md5 与源文件逐个一致：
  - `big.bin` 8,388,608 → `bafa0439a3f515e2641f72e8f5f5cd43`
  - `mov_bbb.mp4` 788,493 → `198918f40ecc7cab0fc4231adaf67c96`
  - `tiny.png` 58 → `bc945dcd5f30bce4f8f62380cccb420d`

### 自检入口

```
adb shell am start -n com.jifeng.download/.MainActivity --es grab_page '<页面地址>'                  # 抓完直接全下
adb shell am start -n com.jifeng.download/.MainActivity --es grab_page '<页面地址>' --ez grab_ui true  # 只弹勾选窗
```

### 边界

- 只认静态 HTML；详情页靠 JS 拼出来的直链抓不到（和抖音那种空壳页同一个原因）。
- 不做页面爬取深度（只抓你给的那一页），也不支持登录态以外的鉴权。

## v3.9 · FTP 下载（匿名即可，无门槛）

FTP 没有 Range，天然无法多线程分段，所以这一版做的是**单连接 + REST 断点续传**，
把「换个协议」补齐，而不是假装它能像 HTTP 那样 8 段并行。

### 做了什么

- 新增 `FtpDownloader`：手写 `ftp://[user:pass@]host[:port]/path` 解析，PASV 被动模式，
  `USER anonymous / PASS anonymous@` 匿名登录，`TYPE I` 二进制，`SIZE` 取总大小，
  `REST offset` 续传（服务器不认 REST 就截断重来），64 KB 一块写盘并回调节流，
  `RETR` 结束读 226/250；426（掉线）只记日志，由「已写字节 < 总字节」触发上层重试。
- 引擎接线：`DownloadEngine.prepare()` 顶部判 `ftp://` 直接进独立线程 `ftpRun`，
  不走 HTTP 的 `probe()` / 分段 / `mergeParts`；`ftpRun` 自管状态机——
  按已落地字节数作为 REST 起点循环，最多自动续 5 轮；进度实时刷（500 ms），
  暂停/取消靠 `stop` 标志；写完 `finishArtifact()` + 通知。
- 输入框放行 `ftp://`，批量粘贴仍只认 http/magnet（FTP 单条进）。
- 报错给人话：550 → 「服务器上没有这个文件，或没有读取权限（550）」，
  530 → 登录失败，连接被拒 → 检查地址/端口。
- 版本 3.8 → **3.9**（versionCode 29）。

### 实测（本地最小 FTP 服务 `test/ftp_server.py`，8 MB `test/mix.bin`）

1. **正常下载**：SIZE 8388608 → PASV → RETR → 落盘
   `/sdcard/Download/疾风下载/mix.bin`，8,388,608 字节，
   md5 `58b120a538a3db0da0cdcd54e0e78c1b` 与源一致，界面「已完成 1 · 8.0 MB · 已保存」。
2. **掉线续传**：服务端第一次传输只发 3,000,000 字节就 426 断开，客户端自动重连：
   `attempt 0 failed: FTP 传输提前结束（3000000 / 8388608 字节）` →
   `REST 3000000 -> 350` → 续完，最终 md5 仍为 `58b120a5…`，大小 8,388,608。
3. 协议日志（logcat TAG `JfFtp`）逐步可见：
   `USER -> 331 / PASS -> 230 / TYPE I -> 200 / SIZE -> 213 8388608 / PASV -> 227 / RETR -> 150`。

### 自检入口

```
adb shell am start -n com.jifeng.download/.MainActivity --es start_url 'ftp://127.0.0.1:2121/mix.bin'
```

### 边界（都是无门槛才做）

- 只用 PASV；不碰 FTPS（TLS 证书）与主动模式。
- 单连接，不支持多线程分段（FTP 协议本身没有 Range）。
- 不支持目录递归下载，只下你给的那一个文件。

## v3.10 · DHT（BEP5）自动找源

磁力链从此不再依赖 `tr=`：有 tracker 照旧先问 tracker，tracker 全挂或者压根没 tracker 时，
按 info_hash 去 Mainline DHT 里找做种者。全部无账号、无门槛，只发标准 KRPC（UDP）。

### 做了什么

- 新增 `Dht.java`：随机 20 字节节点 id，从 `router.bittorrent.com` / `dht.transmissionbt.com` /
  `router.utorrent.com` / `dht.libtorrent.org` 起步，按 **XOR 距离**从近到远迭代 `get_peers`：
  收 `values`（peer 的 ip:port）直接返回，收 `nodes`（26 字节一条：20 字节 id + 4 字节 ip +
  2 字节 port）把新节点并入候选，始终问离目标最近的 8 个；最多 96 次查询 / 1.8 秒一轮 /
  预算默认 12 秒，拿到 peer 就收工。
- `BtDownloader`：无 `tr=` 不再直接判死；tracker 没结果时也走 DHT；两路都没有才报
  「tracker 和 DHT 都没找到做种者」。
- 自检开关：`--es dht_node '<host:port>'`（自定义引导节点）、`--es dht_ms '<毫秒>'`（预算）、
  `--ez no_dht true`、`--ez force_dht true`（跳过 tracker 只走 DHT）、
  `--ez dht_only true`（只找源不下载）、`--ez meta_only true`（只拿元数据不下载文件）、
  `--ez dht_debug true`（打每条应答）。
- 版本 3.9 → **3.10**（versionCode 30）。

### 实测

1. **算法层（同一份 `Dht.java` 跑在桌面 JVM）**：对本地 KRPC 响应器
   `test/dht_stub.py`（127.0.0.1:6881）→ `peers=2 in 2890 ms`，两个地址都对。
2. **公网 DHT（桌面 JVM）**：用 Ubuntu 24.04.3 桌面的真实 info_hash
   `d160b8d8ea35a5b4e52837468fc8f03d55cef1f7`（从官方 `.torrent` 算 info 段 sha1 得到），
   只给公网引导节点 → `peers=4 in 7783 ms`，地址为真实 swarm 里 39552/21696/48551/9002 端口的 peer。
3. **模拟器上**：公网引导节点应答正常解析（`resp ... nodes=208`），也拿到过 1 个真实 peer；
   但这台雷电的 NAT 只放行部分 UDP 回包，迭代到陌生节点后多数不应答，`dht_ms=18000` 也只跑到
   两轮就没了候选，元数据全链路没法在这台机器上跑完——这是模拟器网络限制，不是代码分支。

### 自检入口

```
adb shell am start -n com.jifeng.download/.MainActivity \
  --es start_url '<无 tr= 的磁力>' --ez force_dht true --ez dht_only true --es dht_ms '15000' --ez dht_debug true
```

### 边界

- 只做查询，不做 announce（不对外宣告自己，也不做公网节点）。
- 不做 BEP44（可变数据）、不做 IPv6（`nodes6`）。
- UDP 出网被封时，DHT 会安静地失败，最后给「没找到做种者」的中文原因。

## v3.11：DHT peer 缓存 + 自检开关隔离 + 列表刷新节流

这一版不加新协议，只把已经能用的东西磨顺。

### 1. DHT peer 缓存（省时间）

`Dht.java` 里加了一层内存缓存：同一个 info_hash 查到的 peer 列表保留 10 分钟
（`CACHE_TTL_MS = 600_000`，最多 24 条，超了清空）。命中时直接返回副本，不再打 UDP。

桌面 JVM 上用本地 DHT stub 量的冷热对比（`test/java/DhtTest.java`）：

```
cold peers=1 in 2190 ms
hot  peers=1 in 0 ms
```

一个细节值得知道：**空结果不写缓存**。查不到 peer 时下次仍会重走整套迭代——这是故意的，
否则一次网络抖动就会让某个 hash 在 10 分钟内被判死刑。代价是公网冷门哈希的耗时波动很大：
同一个 Ubuntu hash 连查两次，一次 0 peer（3.97 s 候选耗尽提前结束），一次 407 peer（13.83 s）。

### 2. 自检开关按 Intent 重置（修掉一个真实隐患）

`MainActivity.handlePlay()` 现在开头会把所有自检开关（`forceDht`、`metaProbeOnly`、
`dhtProbeOnly`、`dhtEnabled`、`dhtBudgetMs`、`Dht.DEBUG`、`Dht.EXTRA`、
`DownloadEngine.assistEnabled`、`FtpDownloader.DEBUG`）全部复位成默认值，再按本次
Intent 的 extras 应用。之前用 `--ez meta_only true` 做过取证，下一次正常磁力会继承
「只探测元数据不下载」，表现为任务卡在解析中——现在不会了。

### 3. 列表刷新节流

`refresh()` 先算一个签名（id/status/downloaded/total/group 拼起来，存 `lastListSig`），
签名没变就不 `setData`、不重设页签数字、不 `paintTabs()`。进度在跑时签名一直在变，
所以进度照常刷新；空闲时只是少做了几十次无用重绘。

### 本轮设备回归（emulator-5556，雷电 jftest）

| 项目 | 结果 |
|---|---|
| HTTP 8 MB 动态分段 | 8,388,608 B，md5 `58b120a538a3db0da0cdcd54e0e78c1b`，与服务端一致 |
| 磁力（带 tracker） | 788,493 B，md5 `198918f40ecc7cab0fc4231adaf67c96`，与源一致；证明 Intent 复位没破坏正常磁力 |
| 完成态 UI | 「已完成 1」页签 + `mix.bin 8.0 MB 已保存到 下载/疾风下载`，列表刷新节流后照常出卡片 |
