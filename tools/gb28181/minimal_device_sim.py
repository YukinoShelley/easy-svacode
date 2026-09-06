#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
minimal_device_sim.py —— GB28181 模拟设备【兜底·骨架】
作用：向 WVP(PRO SIP 平台) 发起 REGISTER 注册、保活，并响应 INVITE 后推 PS/RTP。
⚠️ 这是供团队补齐的“最小骨架”，SIP digest 认证、目录(Catalog)、SDP 协商、PS 封装需
   在实际联调时按 GB/T 28181-2016 补全（涉及加密/摘要算法与消息体格式）。
   仅作为“没有现成开源模拟器”时的兜底与学习起点。
"""
import argparse, socket, time, base64, hashlib, os

def build_sip_register(call_id, cseq, id_, domain, pass_=""):
    """生成 REGISTER 请求（首次无鉴权，401 后再带 Authorization 摘要）。骨架：仅文本模板。"""
    c = f"REGISTER sip:{domain} SIP/2.0\r\n" \
        f"Via: SIP/2.0/UDP {id_};branch=z9hG4bK-{cseq};rport\r\n" \
        f"From: <sip:{id_}@{domain}>;tag={call_id}\r\n" \
        f"To: <sip:{id_}@{domain}>\r\n" \
        f"Call-ID: {call_id}\r\n" \
        f"CSeq: {cseq} REGISTER\r\n" \
        f"Contact: <sip:{id_}@{id_}:5060>\r\n" \
        f"Expires: 3600\r\n" \
        f"Content-Length: 0\r\n\r\n"
    return c

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sip", default="10.122.205.66")
    ap.add_argument("--sip-port", type=int, default=5060)
    ap.add_argument("--id", default="34020000001320000001")
    ap.add_argument("--pass", default="12345678")
    ap.add_argument("--zlm", default="10.122.205.66")
    args = ap.parse_args()

    domain = args.id[:10]  # 简化：域 = 前 10 位（实际按编码规则）
    print(f"[sim] SIP 平台 {args.sip}:{args.sip_port}  设备 {args.id}  域 {domain}")
    print("[sim] !!! 骨架：完整 SIP digest/Catalog/INVITE/SDP/PS 需按 GB/T 28181 补全（见脚本头注释）")

    # 注册示例（真实实现需处理 401->带摘要->REGISTER，并定期保活 MESSAGE）
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5)
    seq = 1
    while True:
        req = build_sip_register(args.id, seq, args.id, domain)
        print(f"[sim] -> REGISTER cseq={seq}")
        sock.sendto(req.encode(), (args.sip, args.sip_port))
        try:
            data, _ = sock.recvfrom(4096)
            print("[sim] <- ", data.decode(errors="ignore").split("\r\n")[0])
            # TODO: 处理 401/100，构造 Authorization 摘要；INVITE 时回 SDP，把 c/m 指向 ZLM RTP 端口
        except socket.timeout:
            print("[sim] 超时")
        seq += 1
        time.sleep(30)

if __name__ == "__main__":
    main()
