# 本地测试服务与 JVM 测试

这些脚本都在电脑上跑，配合模拟器/真机的 `adb reverse`（只映射 TCP，不支持 UDP）：

| 脚本 | 作用 |
|---|---|
| `rebalance_server.py <port>` | 8 MB 对照限速服务：第 8 个连接 30 KB/s、其余 320 KB/s，用来验证动态分段 |
| `slow_server.py` / `fast_server.py` / `no_range_server.py` / `range_slow_server.py` | 各种 HTTP 边界：慢速、无 Range、Range 单位错、截断 |
| `ftp_server.py <port> <dir> [--data-port N] [--cut N] [--cut-once] [--slow]` | 匿名 FTP，可模拟断流/限速；`--data-port` 固定数据口，否则 PASV 临时端口在模拟器里连不上 |
| `bt_swarm.py` | 单文件 BT 水蛭源：tracker 9000 + seeder 9001，infohash 由文件内容算 |
| `bt_swarm_multi.py` | 多文件种子：tracker 9002 + seeder 9003 |
| `dht_stub.py --port 6881 --peer 127.0.0.1:9001` | 最小 DHT 节点，返回固定 peer，用来验证迭代查询与缓存 |
| `udp_probe.py` | 监听 6999 打印收到的 UDP 包，用来证明模拟器 UDP 出网是否可达 |
| `bthash.py` / `get_torrent.py` | 从 `.torrent` 算 info_hash / 取官方 torrent |
| `EngineTest.java`、`EngineCorrectnessTest.java`、`SnifferTest.java`、`DhtTest.java` 等 | 可在桌面 JVM 上直接跑的测试（`javac -d build/test ...`） |

`bt_swarm*.py` 需要当前目录有 `mov_bbb.mp4`（**Big Buck Bunny**，© Blender Foundation，
CC-BY 3.0）；仓库出于体积和版权原因不打包视频，请自行从官方发布源取一个同名文件放进来，
脚本会按文件内容重新计算 infohash，不依赖固定哈希。
