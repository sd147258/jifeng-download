import com.jifeng.download.DownloadEngine;

import java.io.File;

/** 桌面复现：直连限速端口，打印引擎终态 / 错误 / 段进度，定位限速档卡点。 */
public class ThrottleRepro {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:8001/5m.bin";
        File dir = new File("test_repro_throttle");
        deleteDir(dir);
        dir.mkdirs();
        DownloadEngine e = new DownloadEngine(dir, new DownloadEngine.Callback() {
            public void onUpdate(DownloadEngine.Task t) {
            }

            public void onFinished(DownloadEngine.Task t) {
            }
        });
        DownloadEngine.Task t = e.add(url, null);
        e.start(t);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 60_000) {
            if ("done".equals(t.status) || "error".equals(t.status) || "canceled".equals(t.status)) break;
            Thread.sleep(200);
        }
        System.out.println("status=" + t.status);
        System.out.println("error=" + t.error);
        System.out.println("downloaded=" + t.downloaded + "/" + t.total);
        System.out.println("segments=" + t.segments.size());
        for (DownloadEngine.Segment s : t.segments) {
            System.out.println("  seg " + s.start + "-" + s.end + " done=" + s.done);
        }
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }
}
