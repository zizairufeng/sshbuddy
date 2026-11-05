#!/usr/bin/env python3
"""按 TPKT 逐包拆解某个方向的 TCP 流，打印每个包的层次偏移。"""
import sys
import os
import subprocess

pcap = sys.argv[1]
direction = sys.argv[2] if len(sys.argv) > 2 else "cs"

blob = subprocess.run(
    [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "pcap_dump.py"), pcap, "--raw"],
    capture_output=True,
).stdout

# --raw 只吐 C->S；需要 S->C 时用全量解析
if direction == "sc":
    sys.exit("暂只支持 C->S，用 pcap_dump.py 直接看")

off = 0
idx = 0
while off + 4 <= len(blob):
    if blob[off] != 0x03:
        # 找下一个 TPKT 起点
        nxt = blob.find(b"\x03\x00", off + 1)
        if nxt < 0:
            print("剩余 %d 字节不是 TPKT: %s" % (len(blob) - off, blob[off:off + 32].hex()))
            break
        print("跳过 %d 字节: %s" % (nxt - off, blob[off:nxt].hex()))
        off = nxt
        continue
    total = int.from_bytes(blob[off + 2:off + 4], "big")
    pkt = blob[off:off + total]
    idx += 1
    print("--- #%d @0x%04x TPKT len=%d  x224=%s" % (idx, off, total, pkt[4:7].hex()))
    p = 7
    if p < len(pkt):
        choice = pkt[p]
        print("    mcs choice=0x%02x" % choice)
        if choice == 0x64:          # SendDataRequest
            initiator = int.from_bytes(pkt[p + 1:p + 3], "big")
            channel = int.from_bytes(pkt[p + 3:p + 5], "big")
            prio = pkt[p + 5]
            # PER 长度
            b0 = pkt[p + 6]
            if b0 & 0x80:
                ulen = ((b0 & 0x3f) << 8) | pkt[p + 7]
                hl = 2
            else:
                ulen = b0
                hl = 1
            pay = p + 6 + hl
            print("    initiator=%d channel=%d prio=0x%02x ulen=%d payload@+%d"
                  % (initiator, channel, prio, ulen, pay))
            body = pkt[pay:pay + ulen]
            print("    body[0:24]=%s" % body[:24].hex())
            flags_le = int.from_bytes(body[0:4], "little")
            print("    sec_flags(u32 le)=0x%08x  lo16=0x%04x hi16=0x%04x"
                  % (flags_le, flags_le & 0xffff, flags_le >> 16))
            print("    ASCII: %s" % "".join(chr(b) if 32 <= b < 127 else "." for b in body))
            if len(body) > 4 and (flags_le & 0xffff) in (0x40, 0x0001):
                print("    TS_SHARECONTROLHEADER pduType=0x%04x"
                      % int.from_bytes(body[6:8], "little"))
        else:
            print("    tail=%s" % pkt[p:p + 32].hex())
    off += total
