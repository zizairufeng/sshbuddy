#!/usr/bin/env python3
"""零依赖 pcap 解析：按四元组分方向拼接 TCP 载荷并 dump hex。

用法: pcap_dump.py <pcap 文件> [--raw]
  --raw  只输出 C->S 原始字节到 stdout（便于管道给 xxd / diff）
"""
import sys
import struct

path = sys.argv[1]
raw_only = "--raw" in sys.argv

with open(path, "rb") as f:
    blob = f.read()

magic = blob[:4]
if magic == b"\xd4\xc3\xb2\xa1":
    endian, nano = "<", False
elif magic == b"\xa1\xb2\xc3\xd4":
    endian, nano = ">", False
elif magic == b"\x4d\x3c\xb2\xa1":
    endian, nano = "<", True
elif magic == b"\xa1\xb2\x3c\x4d":
    endian, nano = ">", True
else:
    sys.exit("不是 pcap 文件（magic=%s）" % magic.hex())

# pcap 全局头 24 字节，linktype 在最后 4 字节
linktype = struct.unpack(endian + "I", blob[20:24])[0]

off = 24
streams = {}   # (src, sport, dst, dport) -> bytearray
order = []

while off + 16 <= len(blob):
    ts_s, ts_u, caplen, origlen = struct.unpack(endian + "IIII", blob[off:off + 16])
    off += 16
    pkt = blob[off:off + caplen]
    off += caplen
    if len(pkt) < caplen:
        break

    # 只处理以太网 / Linux cooked
    if linktype == 1:
        if len(pkt) < 14:
            continue
        ethertype = struct.unpack(">H", pkt[12:14])[0]
        l3 = pkt[14:]
    elif linktype == 113:
        if len(pkt) < 16:
            continue
        ethertype = struct.unpack(">H", pkt[14:16])[0]
        l3 = pkt[16:]
    else:
        l3 = pkt
        ethertype = 0x0800

    if ethertype == 0x0800:
        if len(l3) < 20:
            continue
        ihl = (l3[0] & 0x0F) * 4
        if l3[9] != 6:
            continue
        total_len = struct.unpack(">H", l3[2:4])[0]
        l4 = l3[ihl:total_len] if total_len >= ihl else l3[ihl:]
        src = ".".join(str(b) for b in l3[12:16])
        dst = ".".join(str(b) for b in l3[16:20])
    elif ethertype == 0x86DD:
        if len(l3) < 40 or l3[6] != 6:
            continue
        l4 = l3[40:]
        src = "v6"
        dst = "v6"
    else:
        continue

    if len(l4) < 20:
        continue
    sport, dport = struct.unpack(">HH", l4[0:4])
    doff = (l4[12] >> 4) * 4
    payload = l4[doff:]
    if not payload:
        continue
    key = (src, sport, dst, dport)
    if key not in streams:
        streams[key] = bytearray()
        order.append(key)
    streams[key] += payload

def render(name, data):
    print("==== %s  %d bytes ====" % (name, len(data)))
    for i in range(0, len(data), 32):
        chunk = data[i:i + 32]
        print("%04x  %-96s |%s|" % (
            i,
            " ".join("%02x" % b for b in chunk),
            "".join(chr(b) if 32 <= b < 127 else "." for b in chunk),
        ))
    print()

for key in order:
    src, sport, dst, dport = key
    name = "%s:%d -> %s:%d" % (src, sport, dst, dport)
    if raw_only:
        if sport > dport:      # 客户端端口大 → C->S
            sys.stdout.buffer.write(bytes(streams[key]))
    else:
        render(name, streams[key])
