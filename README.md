# 疾风下载 Jifeng Download

一个从零手写的双端下载器：Windows 单文件 exe（纯 Python 标准库 + PyInstaller）+ Android 原生 APK（纯 Java，不依赖任何第三方库、不用 Gradle）。
两端都是自研实现，没有调用 aria2 / libtorrent / okhttp 之类的现成组件，协议解析全部自己写。

```
Windows  多线程分段 · 断点续传 · m3u8 · 磁力(BT) · DHT · FTP · 全局限速
Android  动态分段 · 断点续传 · 暂停继续 · 边下边播 · m3u8 · 磁力(BT) · DHT · FTP · 通知栏控制
```

## 功能一览

| 能力 | Windows | Android |
|---|---|---|
| HTTP/HTTPS 多线程分段下载 | ✅ 最多 16 段 | ✅ 最多 8 段，下载中动态再分段 |
| 断点续传（HTTP Range / FTP REST / BT 分片） | ✅ | ✅ |
| 断流自动重连（指数退避，最多 5 次） | ✅ | ✅ |
| m3u8（HLS）合流下载 | ✅ | ✅ |
| 磁力 / BT（HTTP + UDP tracker、ut_metadata、多文件种子） | ✅ | ✅ |
| DHT（无 tracker 也能找源，BEP5 迭代查询） | ✅ | ✅（受模拟器 UDP 出网限制） |
| FTP（匿名登录、断点续传） | ✅ | ✅ |
| 全局限速 + 任务暂停/继续/取消 | ✅ | ✅（通知栏控制） |
| 边下边播（本地 127.0.0.1 流服务 + Range） | — | ✅ |
| 链接识别（直链 / 磁力 / 迅雷 / 裸域名 / 平台口令） | ✅ | ✅ |
| 批量链接、整页抓链接、BT 多文件按目录落盘 | — | ✅ |
| 同名去重、已完成历史（保留 60 条） | ✅ | ✅ |

"平台口令"（抖音/快手/微博/B站分享文本）只做**识别 + 中文原因提示 + 用浏览器打开**，
不解析任何需要签名或登录态的接口——这几家在网页里本来就不再吐直链，硬做只能靠登录态和签名。

## 目录

```
android/            原生 Android 工程（自写 build-apk.ps1，不用 Gradle）
  src/com/jifeng/download/    17 个 Java 源文件
  res/                        布局 / 样式 / 文案
  test/                       本地测试服务与 JVM 测试
windows/            Windows 版（Python 标准库）
  app.py engine.py bt.py dht.py ftp.py sniffer.py
  JifengDownload.spec         PyInstaller 打包配置
  test_*.py                   回归脚本
```

## 构建

**Windows exe**

```powershell
py -3 -m pip install pyinstaller
cd windows
py -3 -m PyInstaller --noconfirm --clean JifengDownload.spec
# 产物：dist/JifengDownload.exe（单文件，不装 Python 也能跑）
```

**Android APK**（需要 Android SDK build-tools 34 + JDK 17）

```powershell
cd android
$env:ANDROID_SDK_ROOT = "D:\Android\Sdk"     # 按自己的路径改
$env:JAVA_HOME        = "C:\Program Files\Eclipse Adoptium\jdk-17..."
py -3 -m pip install pillow                   # 仅用于生成图标
py -3 tools/make_launcher_icons.py            # 生成 5 个密度的 launcher 图标
powershell -ExecutionPolicy Bypass -File build-apk.ps1
# 产物：dist/JifengDownload.apk
```

签名口令走环境变量 `JIFENG_KS_PASS`（不填就用本地 dev 口令，仅供自用安装；上架请用自己的 keystore）。
仓库里**不包含**任何签名私钥。

## 测试

测试脚本都在本地跑，不需要外网：

```powershell
py -3 android/test/rebalance_server.py 8770        # 8 MB 对照限速服务
py -3 android/test/ftp_server.py 2121 test --data-port 2122 --cut 3000000 --cut-once
py -3 android/test/dht_stub.py --port 6881 --peer 127.0.0.1:9001
py -3 android/test/bt_swarm.py                     # 本地 tracker + 做种者
cd windows; py -3 test_engine.py; py -3 test_dht_bt.py; py -3 test_dht_cache.py
```

Android 端回归见 `android/README.md` 里的 `adb` 命令清单，细节和实测数据都在两份 README 里。

## 边界与免责

- 本项目是通用下载工具，**不内置任何资源、不提供破解、不绕过 DRM、不接需要登录态或付费的接口**。
- 请只下载你有权获取的内容；因使用方式产生的后果由使用者自负。
- 不做 BT 做种/announce，DHT 只查询不广播自己；不实现 BEP44、IPv6。
- 平台直链解析明确不做（需要签名/登录态），只给原因和"用浏览器打开"的出口。

## 致谢与第三方素材

- 测试视频 `mov_bbb.mp4` 来自 **Big Buck Bunny**（© Blender Foundation，CC-BY 3.0），仓库不打包该文件，测试时请自行从官方发布源获取。
- `test/get_torrent.py` 会从 `releases.ubuntu.com` 取 Ubuntu 官方 `.torrent` 作为公网 DHT 验证素材，仓库同样不打包。
- 应用图标由 `windows/make_icon.py` 和 `android/tools/make_launcher_icons.py` 用 Pillow 现场绘制，无第三方图标素材。

## License

MIT，见 [LICENSE](LICENSE)。
