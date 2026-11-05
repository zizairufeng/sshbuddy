#!/usr/bin/env python3
"""把 pcap 里 TCP 载荷按方向顺序 dump 成 hex，便于人工核对协议字节。"""
import sys
from scapy.all import rdpcap, TCP, IP, IPv6, Raw

path = sys.argv[1]
pkts = rdpcap(path)
streams = {}
order = []
for p in pkts:
    if not p.haslayer(TCP) or not p.haslayer(Raw):
        continue
    sport = p[TCP].sport
    key = "S->C" if sport == 3390 else "C->S"
    data = bytes(p[Raw].load)
    if key not in streams:
        streams[key] = bytearray()
        order.append(key)
    streams[key] += data

for key in order:
    blob = bytes(streams[key])
    print("==== %s  %d bytes ====" % (key, len(blob)))
    for i in range(0, len(blob), 32):
        chunk = blob[i:i + 32]
        print("%04x  %-96s |%s|" % (
            i,
            " ".join("%02x" % b for b in chunk),
            "".join(chr(b) if 32 <= b < 127 else "." for b in chunk),
        ))
    print()
