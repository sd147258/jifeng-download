import com.jifeng.download.BtDownloader;
import com.jifeng.download.DownloadEngine;
import java.io.File;

/** 桌面侧磁力下载自测：java -cp build\classes;test BtCli "<magnet>" */
public class BtCli {
    public static void main(String[] args) throws Exception {
        File dir = new File("test/btout");
        dir.mkdirs();
        final DownloadEngine engine = new DownloadEngine(dir, new DownloadEngine.Callback() {
            public void onUpdate(DownloadEngine.Task t) {
                System.out.println("[update] " + t.status + " " + t.downloaded + "/" + t.total
                        + " " + (int) t.speed + "B/s " + t.filename + (t.error.isEmpty() ? "" : " err=" + t.error));
            }

            public void onFinished(DownloadEngine.Task t) {
                System.out.println("[finish] " + t.status + " " + t.downloaded + "/" + t.total
                        + " " + t.filename + (t.error.isEmpty() ? "" : " err=" + t.error));
            }
        });
        DownloadEngine.Task t = engine.add(args[0], null);
        t.kind = "bt";
        t.filename = "";
        engine.start(t);
        long end = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < end) {
            if ("done".equals(t.status) || "error".equals(t.status)) break;
            Thread.sleep(500);
        }
        System.out.println("RESULT " + t.status + " size=" + t.file().length() + " name=" + t.filename);
        if (!t.error.isEmpty()) System.out.println("ERROR " + t.error);
        System.exit("done".equals(t.status) ? 0 : 1);
    }
}
