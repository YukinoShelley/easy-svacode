#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mini_gb28181_device.py —— 最小 GB28181 模拟国标设备(验收点3 演示/自测用)
行为:向平台 REGISTER(保活 MESSAGE keepalive)→ 收到平台 INVITE 后回复 200,
      并用 ffmpeg 把本地视频封装成 PS 经 RTP 推到平台在 SDP 中下发的收流端口
      (复用同目录 ps_rtp.py,PT=96,ssrc 取自 SDP y= 行)。
用法: python3 mini_gb28181_device.py <gb_id> <平台IP:5060> <视频文件> [--loop]
说明: 未实现 digest 认证(配合 mini_gb28181_platform 无密直通);
      断网/停止即模拟"设备离线"(平台超时判定)。
"""
import argparse, re, socket, subprocess, sys, threading, time

GB_ID = ""
PLATFORM_ADDR = None
VIDEO = ""
SIP_PORT = 6060
LOOP = False
REGISTER_TAG = 0
RTP_SENDER = None

def log(msg):
    print("[dev %s] %s" % (GB_ID, msg), flush=True)

def build_req(method, uri, extra_hdrs, body=""):
    return ("%s %s SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:%d;branch=z9hG4bK%d;rport\r\n"
            "From: <sip:%s@127.0.0.1>;tag=dev%d\r\nTo: <sip:%s@127.0.0.1>\r\n"
            "Call-ID: mini-dev-%s\r\nCSeq: %d %s\r\nContact: <sip:%s@127.0.0.1:%d>\r\n"
            "Max-Forwards: 70\r\n%sContent-Length: %d\r\n\r\n%s"
            % (method, uri, SIP_PORT, int(time.time() * 1000) % 1000000, GB_ID,
               REGISTER_TAG % 100000, GB_ID, GB_ID, REGISTER_TAG + 10, method,
               GB_ID, SIP_PORT, extra_hdrs, len(body.encode()), body))

def parse_sdp(sdp):
    port = 0
    ssrc = 0
    m = re.search(r"m=video (\d+)", sdp)
    if m:
        port = int(m.group(1))
    m = re.search(r"y=(\d+)", sdp)
    if m:
        ssrc = int(m.group(1))
    return port, ssrc

def recv_loop(sock):
    """监听平台下发的 INVITE/BYE/ACK,并按 INVITE 启动推流"""
    sock.settimeout(1.0)
    while True:
        try:
            data, addr = sock.recvfrom(65535)
        except socket.timeout:
            continue
        text = data.decode("utf-8", "ignore")
        lines = text.split("\r\n")
        req = lines[0].split(" ")
        if len(req) < 2:
            continue
        method = req[0]
        if method == "INVITE":
            sdp = text.split("\r\n\r\n", 1)[1] if "\r\n\r\n" in text else ""
            port, ssrc = parse_sdp(sdp)
            via = next((l for l in lines if l.lower().startswith("via:")), "")
            cid = next((l for l in lines if l.lower().startswith("call-id:")), "").split(":", 1)[1].strip()
            cseq = next((l for l in lines if l.lower().startswith("cseq:")), "").split(":", 1)[1].strip()
            # 回复 200 OK(会话在 ACK 后开始推流;此处简化:直接推)
            resp = ("SIP/2.0 200 OK\r\n%s\r\nFrom: <sip:%s@127.0.0.1>\r\nTo: <sip:%s@127.0.0.1>\r\n"
                    "Call-ID: %s\r\nCSeq: %s\r\nUser-Agent: mini-gb28181-device\r\nContent-Length: 0\r\n\r\n"
                    % (via, GB_ID, GB_ID, cid, cseq))
            sock.sendto(resp.encode(), addr)
            log("INVITE received, push RTP -> %s:%d ssrc=0x%x" % (PLATFORM_ADDR[0], port, ssrc))
            start_push(addr[0], port, ssrc)
        elif method == "BYE":
            log("BYE received, stop push")
            stop_push()

def start_push(host, port, ssrc):
    global RTP_SENDER
    stop_push()
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-i", VIDEO,
           "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-an",
           "-f", "vob", "-"]
    if LOOP:
        cmd.insert(cmd.index("-re") + 1, "-stream_loop")
        cmd.insert(cmd.index("-re") + 1, "-1")
    ps_rtp = [sys.executable, sys.path[0] + "/ps_rtp.py", host, str(port), "96", hex(ssrc)]
    RTP_SENDER = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    rtp = subprocess.Popen(ps_rtp, stdin=RTP_SENDER.stdout, stderr=subprocess.DEVNULL)
    RTP_SENDER._rtp = rtp

def stop_push():
    global RTP_SENDER
    if RTP_SENDER:
        try:
            RTP_SENDER.terminate()
            getattr(RTP_SENDER, "_rtp", None) and RTP_SENDER._rtp.terminate()
        except Exception:
            pass
        RTP_SENDER = None

def main():
    global GB_ID, PLATFORM_ADDR, VIDEO, LOOP, REGISTER_TAG
    ap = argparse.ArgumentParser()
    ap.add_argument("gb_id")
    ap.add_argument("platform", help="平台地址 127.0.0.1:5060")
    ap.add_argument("video")
    ap.add_argument("--loop", action="store_true")
    ap.add_argument("--sip-port", type=int, default=6060)
    a = ap.parse_args()
    GB_ID, VIDEO, LOOP = a.gb_id, a.video, a.loop
    global SIP_PORT
    SIP_PORT = a.sip_port
    PLATFORM_ADDR = (a.platform.split(":")[0], int(a.platform.split(":")[1]))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", SIP_PORT))
    threading.Thread(target=recv_loop, args=(sock,), daemon=True).start()
    last_ka = 0
    last_reg = 0
    log("started, will REGISTER to %s:%s" % PLATFORM_ADDR)
    while True:
        now = time.time()
        if now - last_reg > 30:
            last_reg = now
            REGISTER_TAG += 1
            msg = build_req("REGISTER", "sip:%s@%s" % (GB_ID, PLATFORM_ADDR[0]),
                            "Expires: 3600\r\n")
            sock.sendto(msg.encode(), PLATFORM_ADDR)
            log("REGISTER sent")
        if now - last_ka > 20:
            last_ka = now
            REGISTER_TAG += 1
            body = ('<?xml version="1.0"?>\r\n<Notify>\r\n<CmdType>Keepalive</CmdType>\r\n'
                    '<SN>1</SN>\r\n<DeviceID>%s</DeviceID>\r\n<Status>OK</Status>\r\n</Notify>\r\n' % GB_ID)
            msg = build_req("MESSAGE", "sip:%s@%s" % (GB_ID, PLATFORM_ADDR[0]),
                            'Content-Type: Application/MANSCDP+xml\r\n', body)
            sock.sendto(msg.encode(), PLATFORM_ADDR)
        time.sleep(2)

if __name__ == "__main__":
    main()
