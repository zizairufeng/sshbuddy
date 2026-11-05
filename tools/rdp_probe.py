#!/usr/bin/env python3
"""用 aardwolf 连本机 xrdp(3390)，验证服务端与凭据，并打印收到的 PDU 类型。
用法: python rdp_probe.py [host] [port] [user] [pass]
"""
import asyncio
import logging
import os
import sys

from aardwolf import logger
from aardwolf.connection import RDPConnection
from aardwolf.commons.target import RDPTarget
from aardwolf.commons.iosettings import RDPIOSettings
from aardwolf.protocol.x224.constants import SUPP_PROTOCOLS
from asyauth.common.credentials import UniCredential
from asyauth.common.constants import asyauthSecret, asyauthProtocol

logger.setLevel(logging.INFO)

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 3390
USER = sys.argv[3] if len(sys.argv) > 3 else "rdptest"
PASS = sys.argv[4] if len(sys.argv) > 4 else os.environ.get("RDP_LAB_PASS")
if not PASS:
    sys.exit("缺少口令：第 4 个参数或环境变量 RDP_LAB_PASS")


async def main():
    settings = RDPIOSettings()
    settings.video_width = 1024
    settings.video_height = 768
    settings.channels = []
    settings.clipboard_use_pyperclip = False
    settings.supported_protocols = SUPP_PROTOCOLS.RDP | SUPP_PROTOCOLS.SSL

    cred = UniCredential(
        username=USER, domain="", secret=PASS,
        stype=asyauthSecret.PASSWORD, protocol=asyauthProtocol.NTLM,
    )
    conn = RDPConnection(
        target=RDPTarget(ip=HOST, port=PORT),
        credentials=cred,
        iosettings=settings,
    )
    res = await conn.connect()
    print("CONNECT_OK", res)

    n = 0
    while n < 8:
        try:
            item = await asyncio.wait_for(conn.ext_out_queue.get(), timeout=15)
        except asyncio.TimeoutError:
            print("TIMEOUT waiting for data")
            break
        n += 1
        print("OUT[%d]" % n, type(item).__name__, getattr(item, "type", ""))
        # 第一帧画面存 PNG
        pil = getattr(item, "data", None)
        if type(item).__name__.lower().find("video") >= 0 and pil is not None:
            try:
                pil.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "rdp_probe_frame.png"))
                print("FRAME_SAVED", pil.size)
            except Exception as e:
                print("frame save failed", e)
            break

    try:
        await conn.terminate()
    except Exception:
        pass
    print("DONE")


if __name__ == "__main__":
    asyncio.run(main())
