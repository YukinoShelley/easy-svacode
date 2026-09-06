#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ps_rtp.py —— GB28181 PS-over-RTP 打包器（P0 验证用）
用法: ffmpeg ... -f vob - | python3 ps_rtp.py <zlm_ip> <rtp_port> [pt] [ssrc]
从 stdin 读取 MPEG-PS 字节流，按 GB28181 封装为 RTP(payload=96) 发到 ZLM RTP 收流口。
"""
import sys, socket, struct

def main():
    ip = sys.argv[1]
    port = int(sys.argv[2])
    pt = int(sys.argv[3]) if len(sys.argv) > 3 else 96
    ssrc = int(sys.argv[4], 16) if len(sys.argv) > 4 else 0x12345678
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    seq = 0
    ts = 0
    MAXLEN = 1350  # 单包负载(AVOID MTU 过大丢包)
    while True:
        chunk = sys.stdin.buffer.read(65536)
        if not chunk:
            break
        # 把一个 PS 大块切成多个 RTP 包
        off = 0
        while off < len(chunk):
            payload = chunk[off:off + MAXLEN]
            off += len(payload)
            # RTP 头: V=2, P=0, X=0, CC=0, M=0, PT
            b0 = 0x80 | (0 << 5)  # V=2
            b1 = (0 << 7) | (pt & 0x7f)  # M=0
            rtp = struct.pack('>BBHII', b0, b1, seq & 0xFFFF, ts & 0xFFFFFFFF, ssrc & 0xFFFFFFFF) + payload
            sock.sendto(rtp, (ip, port))
            # 时间戳按 90kHz 近似推进(每包 3600 ≈ 一帧@25fps)
            seq += 1
            ts += 3600
    sock.close()

if __name__ == '__main__':
    main()
