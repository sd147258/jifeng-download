import com.jifeng.download.Sniffer;

/** 桌面 JVM 真实链路探针：喂真实链接，打印跳转链 / 落地页大小 / 候选 / 失败分类。 */
public class RealLinkProbe {
    public static void main(String[] a) {
        probe("https://v.douyin.com/KtxB1Wpc-XQ/");
        probe("https://www.w3schools.com/html/html5_video.asp");
    }

    static void probe(String url) {
        System.out.println("===== " + url);
        Sniffer.Result r = Sniffer.resolve(url);
        System.out.println("finalUrl=" + r.finalUrl);
        System.out.println("bodyLen=" + r.bodyLen);
        System.out.println("hops=" + r.hops);
        System.out.println("hits=" + r.hits.size());
        for (Sniffer.Hit h : r.hits) {
            System.out.println("  hit=" + h.kind + " " + h.url);
        }
        System.out.println("reason=" + r.failReason);
        System.out.println();
    }
}
