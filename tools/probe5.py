"""Probe 5: H500 HomeBase recordings, the way Sujeom/tapo-h500-local-recordings and the tapo Rust crate do it.
1) getGeneralDeviceList -> cameras paired to the hub   2) searchVideoWithUTC -> clips of the last 24 h
3) download the newest clip over port 8800 (type=download) and save it as MPEG-TS.
Read-only. Run it YOURSELF:  tools\\.venv\\Scripts\\python tools\\probe5.py
Writes tools/out/probe5_result.txt (no secrets) + tools/out/hub_clip.ts
"""
import getpass, hashlib, json, os, re, socket, sys, time, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe
from probe import log, read_headers, read_headers_after_boundary, auth_header, md5u, sha256u, pmt_codecs
from Crypto.Cipher import AES
from pytapo import Tapo

HUB = "192.168.0.215"
PLAYER = str(uuid.uuid4())


def download(pw, dev_id, mac, start, end, seconds=20):
    query = f"?deviceId={dev_id}&type=download&playerId={PLAYER}&media_type=0"
    req = ("POST /stream{q} HTTP/1.1\r\nHost: {ip}:8800\r\nX-Client-UUID: {p}\r\n"
           "Content-Type: multipart/mixed; boundary=--client-stream-boundary--\r\nContent-Length: 0\r\n{auth}\r\n")
    s = socket.create_connection((HUB, 8800), timeout=8)
    f = s.makefile("rb")
    s.sendall(req.format(q=query, ip=HUB, p=PLAYER, auth="").encode())
    status, h = read_headers(f)
    log(f"  8800 first reply: {status}")
    www = h.get("www-authenticate", "")
    hp = sha256u(pw) if 'encrypt_type="3"' in www else md5u(pw)
    if int(h.get("content-length", "0")):
        f.read(int(h["content-length"]))
    s.sendall(req.format(q=query, ip=HUB, p=PLAYER, auth="Authorization: " + auth_header(www, "admin", hp) + "\r\n").encode())
    status, h = read_headers(f)
    log(f"  8800 auth reply: {status}  key-exchange: {h.get('key-exchange', '')[:160]}")
    if " 200" not in status:
        return
    nonce = re.search(r'nonce="([^"]*)"', h.get("key-exchange", "")).group(1)  # may be EMPTY on the H500
    key = hashlib.md5(f"{nonce}:{hp}".encode()).digest()
    iv = hashlib.md5(f"admin:{nonce}".encode()).digest()

    body = json.dumps({"type": "request", "seq": 1, "params": {"method": "get", "download": {
        "dev_id": dev_id, "mac": mac, "channels": [0], "client_id": 1, "start_time": str(start),
        "end_time": str(end), "media_type": 0, "player_id": PLAYER}}})
    s.sendall((f"----client-stream-boundary--\r\nContent-Type: application/json\r\nX-Data-Window-Size: 50\r\n"
               f"Content-Length: {len(body)}\r\n\r\n{body}\r\n").encode())

    ts, session, parts, t_end = bytearray(), None, 0, time.time() + seconds
    while time.time() < t_end:
        line = f.readline()
        if not line:
            log("  hub closed the stream")
            break
        if not line.strip().startswith(b"--"):
            continue
        _, ph = read_headers_after_boundary(f)
        data = f.read(int(ph.get("content-length", "0")))
        session = ph.get("x-session-id", session)
        if ph.get("content-type") == "video/mp2t":
            parts += 1
            if parts == 1:
                log(f"  first video part headers: { {k: v for k, v in ph.items() if k.startswith('x-')} }")
            if ph.get("x-if-encrypt", "1") == "0":
                ts += data
            else:
                plain = AES.new(key, AES.MODE_CBC, iv).decrypt(data)
                ts += plain[:-plain[-1]]
            seq = ph.get("x-data-sequence")
            if seq and int(seq) % 25 == 0:  # ack, or the hub stops sending
                ack = json.dumps({"type": "notification", "params": {"event_type": "stream_sequence"}})
                s.sendall((f"----client-stream-boundary--\r\nContent-Type: application/json\r\nX-Session-Id: {session}\r\n"
                           f"X-Data-Received: {seq}\r\nContent-Length: {len(ack)}\r\n\r\n{ack}\r\n").encode())
        else:
            log(f"  json part: {data[:250]!r}")
            if b'"finished"' in data:
                break
    s.close()
    log(f"  video parts: {parts}, TS bytes: {len(ts)}, 0x47 sync: {bool(ts) and ts[0] == 0x47}, codecs: {pmt_codecs(bytes(ts))}")
    with open(os.path.join(probe.OUT, "hub_clip.ts"), "wb") as out:
        out.write(ts)


def main():
    pw = getpass.getpass("Tapo account password (not shown, not saved): ")
    hub = Tapo(HUB, "admin", pw, pw)
    r = hub.executeFunction("getGeneralDeviceList", {"general_camera_manage": {"paired_general_device_list": {}}})
    cams = r.get("general_camera_manage", {}).get("paired_general_device_list", [])
    log(f"paired cameras: {len(cams)}")
    for c in cams:
        log("  " + json.dumps({k: c.get(k) for k in ("alias", "device_model", "category", "hub_storage_enabled", "channel_id")}))

    now, newest = int(time.time()), None
    for c in cams:
        try:
            res = hub.executeFunction("searchVideoWithUTC", {"playback": {"search_video_with_utc": {
                "channel": 0, "child_device_id": c["device_id"], "child_device_mac": c.get("mac"),
                "start_time": now - 86400, "end_time": now, "start_index": 0, "end_index": 99, "player_id": PLAYER}}})
            clips = res.get("playback", {}).get("search_video_results", [])
            log(f"\n{c.get('alias')}: {len(clips)} clips in last 24h; sample: {json.dumps(clips[:2])[:400]}")
            for clip in clips:
                v = next(iter(clip.values())) if isinstance(clip, dict) and len(clip) == 1 else clip
                st = int(v.get("start_time") or v.get("startTime") or 0)
                if not newest or st > newest[2]:
                    newest = (c["device_id"], c.get("mac"), st, int(v.get("end_time") or v.get("endTime") or st + 10))
        except Exception as e:
            log(f"\n{c.get('alias')} clips ERROR: {str(e)[:300]}")

    if newest:
        log(f"\ndownloading newest clip ({newest[3] - newest[2]} s)")
        try:
            download(pw, *newest)
        except Exception as e:
            log(f"  download ERROR: {e!r}")

    with open(os.path.join(probe.OUT, "probe5_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(probe.log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(probe.OUT, 'probe5_result.txt')}")


if __name__ == "__main__":
    main()
