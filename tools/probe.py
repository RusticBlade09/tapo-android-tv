"""Step 0 probe: find Tapo cameras on this network and test their local protocol.

Run it YOURSELF (it asks for the Tapo password; nothing is saved or printed):
    tools\\.venv\\Scripts\\python tools\\probe.py
Writes tools/out/probe_result.txt (no secrets) + a few seconds of video per camera.
"""
import concurrent.futures as cf, getpass, hashlib, json, os, re, secrets, socket, ssl, sys, time, urllib.request
from Crypto.Cipher import AES

OUT = os.path.join(os.path.dirname(__file__), "out")
os.makedirs(OUT, exist_ok=True)
log_lines = []


def log(s=""):
    print(s)
    log_lines.append(str(s))


def md5u(s): return hashlib.md5(s.encode()).hexdigest().upper()
def sha256u(s): return hashlib.sha256(s.encode()).hexdigest().upper()
def hexmd5(*parts): return hashlib.md5(":".join(parts).encode()).hexdigest()


# ---------- find cameras: anything answering on TCP 8800 in our /24 ----------
def my_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent
        return s.getsockname()[0]
    finally:
        s.close()


def port_open(ip, port=8800):
    try:
        with socket.create_connection((ip, port), timeout=0.5):
            return ip
    except OSError:
        return None


def scan():
    base = my_ip().rsplit(".", 1)[0]
    with cf.ThreadPoolExecutor(64) as ex:
        return [ip for ip in ex.map(port_open, [f"{base}.{i}" for i in range(1, 255)]) if ip]


# ---------- port 443: which login does the firmware want? (no password sent) ----------
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE  # camera uses a self-signed cert


def post443(ip, body):
    req = urllib.request.Request(f"https://{ip}/", json.dumps(body).encode(),
                                 {"Content-Type": "application/json", "requestByApp": "true",
                                  "User-Agent": "Tapo CameraClient Android"})
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=5) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}")
    except Exception as e:
        return {"exception": repr(e)}


# ---------- port 8800: the video stream (same steps the Kotlin app will do) ----------
def read_headers(f):
    status = f.readline().decode(errors="replace").strip()
    headers = {}
    while True:
        line = f.readline().decode(errors="replace").strip()
        if not line:
            return status, headers
        k, _, v = line.partition(":")
        headers[k.strip().lower()] = v.strip()


def auth_header(www, user, pw):
    p = dict(re.findall(r'(\w+)="?([^",]*)"?', www))
    cnonce = secrets.token_hex(16)
    ha1, ha2 = hexmd5(user, p["realm"], pw), hexmd5("POST", "/stream")
    qop = p.get("qop", "")
    resp = hexmd5(ha1, p["nonce"], "00000001", cnonce, qop, ha2) if qop else hexmd5(ha1, p["nonce"], ha2)
    h = (f'Digest username="{user}", realm="{p["realm"]}", nonce="{p["nonce"]}", uri="/stream", '
         f'qop={qop}, nc=00000001, cnonce="{cnonce}", response="{resp}"')
    if "opaque" in p:
        h += f', opaque="{p["opaque"]}", algorithm=MD5'
    return h


REQ = ("POST /stream HTTP/1.1\r\nHost: {ip}:8800\r\n"
       "Content-Type: multipart/mixed; boundary=--client-stream-boundary--\r\n{auth}\r\n")


def stream_test(ip, cloudpw, res="VGA", seconds=4, device_id=None, force_md5=False, params=None):
    # Cameras behind a Tapo hub: stream from the hub, naming the camera (as pytapo's childID does).
    player = "a1b2c3d4-0000-4000-8000-" + secrets.token_hex(6)
    query = f"?deviceId={device_id}&playerId={player}&type=video" if device_id else ""
    extra = f"X-Client-UUID: {player}\r\n" if device_id else ""

    def req(auth):
        return (REQ.replace("POST /stream ", f"POST /stream{query} ").replace("{auth}", extra + "{auth}")
                .format(ip=ip, auth=auth).encode())

    s = socket.create_connection((ip, 8800), timeout=5)
    f = s.makefile("rb")
    s.sendall(req(""))
    status, h = read_headers(f)
    log(f"  8800 first reply: {status}")
    www = h.get("www-authenticate", "")
    use_sha = 'encrypt_type="3"' in www and not force_md5
    pw = sha256u(cloudpw) if use_sha else md5u(cloudpw)
    log(f"  8800 hash type: {'SHA256' if use_sha else 'MD5'}")
    if int(h.get("content-length", "0")):
        f.read(int(h["content-length"]))
    auth = "Authorization: " + auth_header(www, "admin", pw) + "\r\n"
    try:
        s.sendall(req(auth))
        status, h = read_headers(f)
        log("  8800 same-connection retry: ok")
    except OSError:
        log("  8800 same-connection retry FAILED -> new connection")
        s = socket.create_connection((ip, 8800), timeout=5)
        f = s.makefile("rb")
        s.sendall(req(auth))
        status, h = read_headers(f)
    log(f"  8800 auth reply: {status}")
    if " 401" in status and use_sha:
        s.close()
        log("  SHA256 refused -> one retry with MD5 (some firmware wants that)")
        return stream_test(ip, cloudpw, res, seconds, device_id, force_md5=True, params=params)
    if " 200" not in status:
        return False
    kx = h.get("key-exchange", "")
    log(f"  key-exchange has username=none: {'username=\"none\"' in kx}")
    nonce = re.search(r'nonce="([^"]+)"', kx).group(1)
    user = "admin"
    if 'username="none"' in kx:
        user, pw = "none", "TPL075526460603"
    key = hashlib.md5(f"{nonce}:{pw}".encode()).digest()
    iv = hashlib.md5(f"{user}:{nonce}".encode()).digest()

    preview = {"audio": ["default"], "channels": [0], "resolutions": [res]}
    if device_id:
        preview["deviceId"] = device_id
    body = json.dumps({"params": params or {"preview": preview, "method": "get"}, "seq": 1, "type": "request"})
    s.sendall((f"----client-stream-boundary--\r\nContent-Type: application/json\r\n"
               f"Content-Length: {len(body)}\r\n\r\n{body}\r\n").encode())

    ts = bytearray()
    parts, t_end = {}, time.time() + seconds
    while time.time() < t_end:
        line = f.readline()
        if not line:
            log("  stream closed by device")
            break
        if not line.strip().startswith(b"--"):
            continue  # skip CRLF between parts
        _, ph = read_headers_after_boundary(f)
        ctype, n = ph.get("content-type", ""), int(ph.get("content-length", "0"))
        data = f.read(n)
        parts[ctype] = parts.get(ctype, 0) + 1
        if ctype == "video/mp2t":
            if parts[ctype] == 1:
                log(f"  first video part headers: { {k: v for k, v in ph.items() if k.startswith('x-')} }")
            if ph.get("x-if-encrypt", "1") == "0":
                ts += data
            else:
                plain = AES.new(key, AES.MODE_CBC, iv).decrypt(data)
                ts += plain[:-plain[-1]]
        elif "json" in ctype and len(log_lines) < 400:
            log(f"  json part: {data[:200]!r}")
    s.close()
    log(f"  parts seen: {parts}")
    log(f"  TS bytes: {len(ts)}, starts with 0x47 sync: {bool(ts) and ts[0] == 0x47}")
    log(f"  codecs in PMT: {pmt_codecs(bytes(ts))}")
    with open(os.path.join(OUT, f"{ip}_{(device_id or 'direct')[-6:]}_{res}.ts"), "wb") as out:
        out.write(ts)
    return bool(ts) and ts[0] == 0x47


def read_headers_after_boundary(f):
    headers = {}
    while True:
        line = f.readline().decode(errors="replace").strip()
        if not line:
            return None, headers
        k, _, v = line.partition(":")
        headers[k.strip().lower()] = v.strip()


def pmt_codecs(ts):
    names = {0x1B: "H.264", 0x24: "H.265", 0x90: "PCMA?", 0x91: "PCMU?", 0x0F: "AAC"}
    found = set()
    for i in range(0, len(ts) - 188, 188):
        pkt = ts[i:i + 188]
        if pkt[0] != 0x47 or not (pkt[1] & 0x40):
            continue
        off = 4 + (1 + pkt[4] if pkt[3] & 0x20 else 0)  # skip adaptation field
        off += 1 + pkt[off]  # skip pointer field
        if off < 188 and pkt[off] == 0x02:  # table_id PMT
            sec_len = ((pkt[off + 1] & 0x0F) << 8) | pkt[off + 2]
            j = off + 12 + (((pkt[off + 10] & 0x0F) << 8) | pkt[off + 11])
            end = off + 3 + sec_len - 4
            while j + 5 <= end and j + 5 < 188:
                found.add(names.get(pkt[j], hex(pkt[j])))
                j += 5 + (((pkt[j + 3] & 0x0F) << 8) | pkt[j + 4])
            if found:
                break
    return sorted(found)


def main():
    log(f"PC IP: {my_ip()}")
    cams = scan()
    log(f"Port 8800 open on: {cams}")
    if not cams:
        log("No cameras found. Is this PC on the same Wi-Fi/network as the cameras?")
        return
    for ip in cams:
        log(f"\n=== {ip} (no password yet) ===")
        log(f"  443 discover: {json.dumps(post443(ip, {'method': 'login', 'params': {'sub_method': 'discover'}}))[:400]}")

    cloudpw = getpass.getpass("\nTapo account password (not shown, not saved): ")
    for ip in cams:
        log(f"\n=== {ip} video test ===")
        try:
            ok_vga = stream_test(ip, cloudpw, "VGA")
            log(f"  VGA OK: {ok_vga}")
            if ok_vga:
                log(f"  HD OK: {stream_test(ip, cloudpw, 'HD')}")
        except Exception as e:
            log(f"  stream error: {e!r}")

        log(f"=== {ip} control (pytapo) ===")
        try:
            from pytapo import Tapo
            t = Tapo(ip, "admin", cloudpw, cloudpw)
            info = t.getBasicInfo()["device_info"]["basic_info"]
            log(f"  model={info.get('device_model')} name={info.get('device_alias')} fw={info.get('sw_version')}")
            log(f"  pytapo transport: {t.transport.method} / {type(t.transport.transport).__name__}")
            if input("  Move this camera a little left and back? (j/n) ").strip().lower() in ("j", "y"):
                t.moveMotor(10, 0); time.sleep(2); t.moveMotor(-10, 0)
                log("  PTZ moveMotor: OK")
        except Exception as e:
            log(f"  control error: {e!r}")

    with open(os.path.join(OUT, "probe_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(OUT, 'probe_result.txt')}")


if __name__ == "__main__":
    main()
