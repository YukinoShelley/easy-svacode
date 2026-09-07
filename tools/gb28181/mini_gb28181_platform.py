#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mini_gb28181_platform.py —— 最小 GB28181 SIP 平台(验收点3 演示/自测用)
职责(只做验收所需最小集):
  * UDP 5060:处理设备 REGISTER / MESSAGE(keepalive) / 注销(Expires=0)
  * 控制 HTTP(默认 127.0.0.1:18081):
      GET  /status            -> 设备在线状态 JSON
      POST /play/<gb_id>      -> 向设备发 INVITE,并在 ZLM openRtpServer 开收流口(app=live, stream=<gb_id>)
      POST /bye/<gb_id>       -> 发 BYE + ZLM closeRtpServer
  * 离线判定:超过 offline_after_s 未收到任何消息即判离线(可配,默认 20s 便于演示)
说明:
  - 这是契约 `docs/GB28181接入/接口契约.md` 的最小落地,专供本机验收链路;
    生产应使用 WVP(wvp-GB28181-pro)+ 本目录 WVP补丁,二者端口/交互一致(5060)。
  - 未实现 digest 认证(域无密码直通),真实设备若强制认证需接 WVP。
用法: python3 mini_gb28181_platform.py [--http-port 18081] [--offline-after 20]
"""
import argparse, json, socket, threading, time, urllib.request, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ZLM_HTTP = "http://127.0.0.1:9992"
ZLM_SECRET = "V3522025zlm0aA9ajn7UiOWi"

class Device:
    def __init__(self, gb_id, contact_ip, contact_port, from_ip, from_port, expires):
        self.gb_id = gb_id
        self.contact_ip, self.contact_port = contact_ip, contact_port
        self.from_ip, self.from_port = from_ip, from_port
        self.expires = expires
        self.last_seen = time.time()
        self.online = True
        self.zlm_port = 0
        self.stream_app = "live"

STATE = {}          # gb_id -> Device
LOCK = threading.Lock()

def log(msg):
    print("[sip] %s" % msg, flush=True)

def send_udp(sock, addr, data):
    sock.sendto(data.encode("utf-8", "ignore"), addr)

def build_response(req_line_head, headers, extra=None, code="SIP/2.0 200 OK"):
    via = headers.get("via", "")
    frm = headers.get("from", "")
    to = headers.get("to", "")
    cid = headers.get("call-id", "")
    cseq = headers.get("cseq", "")
    lines = [code, "Via: %s" % via, "From: %s" % frm, "To: %s" % to,
             "Call-ID: %s" % cid, "CSeq: %s" % cseq,
             "User-Agent: mini-gb28181-platform", "Content-Length: 0"]
    if extra:
        lines.extend(extra)
    return "\r\n".join(lines) + "\r\n\r\n"

def zlm_get(path):
    url = "%s/index/api/%s?secret=%s" % (ZLM_HTTP, path, ZLM_SECRET)
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return json.loads(r.read().decode("utf-8", "ignore"))
    except Exception as e:
        return {"code": -1, "err": str(e)}

def open_rtp(stream_id):
    """ZLM 开 GB 收流口;流注册为 app=live / stream=stream_id(与后端 streamUrl 契约一致)"""
    q = urllib.parse.urlencode({"secret": ZLM_SECRET, "port": 0, "tcp_mode": 0,
                                "stream_id": stream_id, "app": "live", "ssrc": 0})
    r = zlm_get("openRtpServer&" + q) if False else None
    try:
        with urllib.request.urlopen("%s/index/api/openRtpServer?%s" % (ZLM_HTTP, q), timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8", "ignore"))
    except Exception as e:
        return {"code": -1, "err": str(e)}

def close_rtp(stream_id):
    q = urllib.parse.urlencode({"secret": ZLM_SECRET, "stream_id": stream_id, "app": "live"})
    try:
        with urllib.request.urlopen("%s/index/api/closeRtpServer?%s" % (ZLM_HTTP, q), timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8", "ignore"))
    except Exception as e:
        return {"code": -1, "err": str(e)}

def invite_device(dev):
    """发 INVITE 给设备;SDP 携带 ZLM 收流端口/ssrc(GB 会话)→ 设备开始推 PS-RTP"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ssrc = 0x12345678
    call_id = "mini-%s-%d" % (dev.gb_id, int(time.time() * 1000))
    branch = "z9hG4bK%d" % int(time.time() * 1000)
    ip = "127.0.0.1"
    sdp = (
        "v=0\r\no=%s 0 0 IN IP4 %s\r\ns=Play\r\nc=IN IP4 %s\r\nt=0 0\r\n"
        "m=video %d RTP/AVP 96\r\na=rtpmap:96 PS/90000\r\na=recvonly\r\ny=%d\r\n"
        "f=v/2/90000/1/1/1\r\n" % (dev.gb_id, ip, ip, dev.zlm_port, ssrc)
    )
    msg = ("INVITE sip:%s@%s SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=%s;rport\r\n"
           "From: <sip:34020000002000000001@127.0.0.1>;tag=plat%d\r\nTo: <sip:%s@127.0.0.1>\r\n"
           "Call-ID: %s\r\nCSeq: 20 INVITE\r\nContact: <sip:34020000002000000001@127.0.0.1:5060>\r\n"
           "Max-Forwards: 70\r\nContent-Type: application/sdp\r\nContent-Length: %d\r\n\r\n%s"
           % (dev.gb_id, dev.from_ip, branch, int(time.time() * 1000) % 100000,
              dev.gb_id, call_id, len(sdp.encode()), sdp))
    send_udp(sock, (dev.from_ip, dev.from_port), msg)
    sock.close()
    log("INVITE sent to %s (port=%d)" % (dev.gb_id, dev.zlm_port))
    return True

def bye_device(dev):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    msg = ("BYE sip:%s@%s SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK%d;rport\r\n"
           "From: <sip:34020000002000000001@127.0.0.1>;tag=plat%d\r\nTo: <sip:%s@127.0.0.1>\r\n"
           "Call-ID: mini-bye-%s\r\nCSeq: 21 BYE\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n"
           % (dev.gb_id, dev.from_ip, int(time.time() * 1000) % 100000,
              int(time.time() * 1000) % 100000, dev.gb_id, dev.gb_id))
    send_udp(sock, (dev.from_ip, dev.from_port), msg)
    sock.close()
    close_rtp(dev.gb_id)
    log("BYE sent to %s" % dev.gb_id)

def handle_sip(data, addr, sock):
    text = data.decode("utf-8", "ignore")
    lines = text.split("\r\n")
    req = lines[0].split(" ")
    if len(req) < 3 or req[2] != "SIP/2.0":
        return
    method = req[0]
    headers = {}
    for ln in lines[1:]:
        if ":" in ln:
            k, v = ln.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    uri = req[1]
    gb_id = uri.split(":")[1].split("@")[0] if "sip:" in uri else ""
    cseq = headers.get("cseq", "")
    expires_hdr = headers.get("expires", "")
    with LOCK:
        dev = STATE.get(gb_id)
        if method == "REGISTER":
            expires = 3600
            try:
                expires = int(expires_hdr or headers.get("expires", "3600"))
            except ValueError:
                pass
            if expires == 0 or gb_id in ("", "0"):
                if dev:
                    dev.online = False
                    if dev.zlm_port:
                        close_rtp(gb_id)
                    log("device offline(注销): %s" % gb_id)
            else:
                # contact 或来源地址即设备信令地址
                contact = headers.get("contact", "")
                cip = cport = None
                if "@" in contact and ":" in contact:
                    try:
                        cip = contact.split("@")[1].split(":")[0].split(">")[0]
                        cport = int(contact.split(":")[-1].split(">")[0])
                    except Exception:
                        pass
                dev = Device(gb_id, cip or addr[0], cport or addr[1], addr[0], addr[1], expires)
                dev.last_seen = time.time()
                dev.online = True
                STATE[gb_id] = dev
                log("REGISTER ok: %s from %s:%s (expires=%s)" % (gb_id, addr[0], addr[1], expires))
            resp = build_response(req, headers)
            send_udp(sock, addr, resp)
        elif method in ("MESSAGE", "NOTIFY") and dev:
            dev.last_seen = time.time()
            dev.online = True
            body = text.split("\r\n\r\n", 1)[1] if "\r\n\r\n" in text else ""
            if "keepalive" in body.lower():
                log("KEEPALIVE from %s" % gb_id)
            resp = build_response(req, headers)
            send_udp(sock, addr, resp)
        elif method in ("200",) or req[0] in ("SIP/2.0",):
            pass  # 设备对 INVITE 的 200 OK 已由 /play 流程以日志记录
        else:
            resp = build_response(req, headers, code="SIP/2.0 200 OK")
            send_udp(sock, addr, resp)

def sip_loop(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", port))
    sock.settimeout(1.0)
    log("SIP UDP listening on 0.0.0.0:%d" % port)
    while True:
        try:
            data, addr = sock.recvfrom(65535)
            handle_sip(data, addr, sock)
        except socket.timeout:
            pass

def sweep_loop(offline_after):
    while True:
        time.sleep(2)
        now = time.time()
        with LOCK:
            for gb_id, dev in list(STATE.items()):
                if dev.online and now - dev.last_seen > offline_after:
                    dev.online = False
                    if dev.zlm_port:
                        close_rtp(gb_id)
                        dev.zlm_port = 0
                    log("device offline(超时): %s" % gb_id)

class Ctrl(BaseHTTPRequestHandler):
    def _send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/status":
            with LOCK:
                out = [{"gb_id": d.gb_id, "online": d.online, "zlm_port": d.zlm_port,
                        "last_seen": round(d.last_seen, 1)} for d in STATE.values()]
            self._send({"code": 0, "devices": out})
        elif path.startswith("/play/"):
            gb_id = path.split("/")[-1]
            with LOCK:
                dev = STATE.get(gb_id)
                if not dev or not dev.online:
                    self._send({"code": 1, "msg": "device not online"}, 404)
                    return
                if not dev.zlm_port:
                    r = open_rtp(gb_id)
                    port = (r.get("data") or {}).get("port") or r.get("port")
                    if not port:
                        self._send({"code": 1, "msg": "openRtpServer fail: %s" % r}, 500)
                        return
                    dev.zlm_port = int(port)
                    log("openRtpServer -> port %d for %s" % (port, gb_id))
            invite_device(dev)
            self._send({"code": 0, "msg": "INVITE sent", "zlm_port": dev.zlm_port})
        elif path.startswith("/bye/"):
            gb_id = path.split("/")[-1]
            with LOCK:
                dev = STATE.get(gb_id)
            if dev:
                bye_device(dev)
                with LOCK:
                    dev.online = False
                    dev.zlm_port = 0
            self._send({"code": 0, "msg": "bye"})
        else:
            self._send({"code": 1, "msg": "unknown"}, 404)

    def do_POST(self):
        self.do_GET()

    def log_message(self, *a):
        pass

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sip-port", type=int, default=5060)
    ap.add_argument("--http-port", type=int, default=18081)
    ap.add_argument("--offline-after", type=float, default=20.0)
    a = ap.parse_args()
    threading.Thread(target=sip_loop, args=(a.sip_port,), daemon=True).start()
    threading.Thread(target=sweep_loop, args=(a.offline_after,), daemon=True).start()
    print("mini GB28181 SIP platform ready: SIP udp %d | ctrl http 127.0.0.1:%d | offline-after=%ss"
          % (a.sip_port, a.http_port, a.offline_after), flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.http_port), Ctrl).serve_forever()

if __name__ == "__main__":
    main()
