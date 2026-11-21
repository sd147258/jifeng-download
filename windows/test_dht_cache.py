import time
import dht

h = "4f2e83c2674b2f36a880368f1415b9ff997ab848"   # 本地做种者的种子哈希
dht.clear_cache()
t0 = time.time()
a = dht.find_peers(h, 4, extra=[("127.0.0.1", 6881)])
print("cold peers=%d %.2fs" % (len(a), time.time() - t0))
t0 = time.time()
b = dht.find_peers(h, 4, extra=[("127.0.0.1", 6881)])
print("hot  peers=%d %.4fs" % (len(b), time.time() - t0))
