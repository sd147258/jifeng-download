import re
import urllib.request

body = urllib.request.urlopen("https://releases.ubuntu.com/24.04/", timeout=20).read().decode("utf-8", "ignore")
links = re.findall(r'href="([^"]+\.torrent)"', body)
print(links[:5])
if links:
    url = "https://releases.ubuntu.com/24.04/" + links[0]
    data = urllib.request.urlopen(url, timeout=30).read()
    open("ubuntu.torrent", "wb").write(data)
    print("saved", url, len(data))
