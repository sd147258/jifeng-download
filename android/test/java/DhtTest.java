package com.jifeng.download;

/** 桌面 JVM 上验证 Dht.findPeers：只用标准库，不依赖 Android。 */
public class DhtTest {
    public static void main(String[] a) throws Exception {
        String hash = a[0];
        Dht.DEBUG = false;
        for (int i = 1; i < a.length; i++) Dht.EXTRA.add(a[i]);
        Dht.clearCache();
        long t0 = System.currentTimeMillis();
        java.util.List<String> cold = Dht.findPeers(hash, 6000);
        System.out.println("cold peers=" + cold.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
        t0 = System.currentTimeMillis();
        java.util.List<String> hot = Dht.findPeers(hash, 6000);
        System.out.println("hot  peers=" + hot.size() + " in " + (System.currentTimeMillis() - t0) + " ms");
        for (String p : hot) System.out.println("  " + p);
    }
}
