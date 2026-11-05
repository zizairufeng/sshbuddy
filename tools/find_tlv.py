#!/usr/bin/env python3
"""在服务端 GCC 数据里找出合法的 TS_UD TLV 链起点（LE / BE 两种假设都试）。"""
HEX = ("000500147c00012a14760a01010001c0004d63446e81c0010c0c000400080000000000"
       "030c0800eb030000020cac010200000003000000200000007801000034b5d73f9c93324e"
       "aea8d5b6cfc53accc8c3ee92102161583edf4986e50725fa010000000100000001000000"
       "06001c01525341310801000000080000ff00000001000100df4da3a5f7c06d47db9c3b"
       "5251accf60d91d07e2c6818cd17c5c47abdd588eafa8190d06e28625e64b93c5d550b0"
       "0a6e7487e0c2305cab51f04242ef7f194ba87826b69821908a9faf0deaba9117b68638415"
       "e27704ecdfbcfd41529e679b52b3ac4c8113f428fd3f02d84409034d7adb64295776398"
       "2a196a66757d73da49c8d47e9689af569afd74c3c22296d6fea64d252bf885a9c35f788"
       "eae691f6ccdb6fb231b9688485da82fefbff3c001119c774a16560ed507b733bf49f902"
       "996e2c637a7ebd93712f5e1e9dc0d144f41ff5895c2dd6ef306ccf817a9ef85fe8b1010"
       "c05496a37d831cda830b44b8485488c51a3e251ae27015f47e932925e9b7e8d0000000000"
       "00000008004800570b65531679b605c914c86206a285b4d062d9008f17f3c32517683da"
       "31729f03adb877754ff4727980190e19ea949db685a04b4dfcc608ed30b495abbd0c32f"
       "0000000000000000")
data = bytes.fromhex(HEX)
print("total", len(data))

VALID = set(range(0xC001, 0xC009))


def walk(start, tag_be, len_be):
    pos = start
    tags = []
    while pos + 4 <= len(data):
        tag = int.from_bytes(data[pos:pos + 2], "big" if tag_be else "little")
        ln = int.from_bytes(data[pos + 2:pos + 4], "big" if len_be else "little")
        if tag not in VALID:
            return None
        if ln < 4 or pos + ln > len(data):
            return None
        tags.append((hex(tag), ln, pos))
        pos += ln
        if len(tags) > 8:
            return None
    if tags and pos == len(data):
        return tags
    return None


for tag_be in (False, True):
    for len_be in (False, True):
        for start in range(0, min(60, len(data))):
            r = walk(start, tag_be, len_be)
            if r:
                print("HIT start=%d tagBE=%s lenBE=%s %s" % (start, tag_be, len_be, r))

# 逐字节看一眼 0..40 的 tag/len 假设
print("--- 前 60 字节 ---")
for i in range(0, 60, 16):
    print("%04x %s" % (i, " ".join("%02x" % b for b in data[i:i + 16])))
