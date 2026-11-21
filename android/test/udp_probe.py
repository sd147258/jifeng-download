import socket

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", 6999))
print("udp probe on 6999", flush=True)
while True:
    data, addr = s.recvfrom(2048)
    print("got %r from %s" % (data, addr), flush=True)
    s.sendto(b"pong", addr)
