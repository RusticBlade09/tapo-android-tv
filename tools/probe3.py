"""Probe 3: where are the recordings, and can we play one?

Asks each camera and the H500 hub for its storage + recording list, then tries to play
5 seconds of the newest recording over port 8800 (like pytapo's downloader).
Run it YOURSELF:  tools\\.venv\\Scripts\\python tools\\probe3.py
Writes tools/out/probe3_result.txt (no secrets) + a few seconds of recorded video.
"""
import getpass, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe
from probe import log, stream_test
from pytapo import Tapo

CAMS = ["192.168.0.122", "192.168.0.229"]
HUB = "192.168.0.215"


def short(x, n=600):
    return json.dumps(x, default=str)[:n]


def main():
    pw = getpass.getpass("Tapo account password (not shown, not saved): ")

    log(f"=== hub {HUB} ===")
    try:
        hub = Tapo(HUB, "admin", pw, pw)
        log(f"  child list: {short(hub.getChildDevices(), 1500)}")
        for name, fn in (("storage", hub.getSDCard), ("dates", hub.getRecordingsList)):
            try:
                log(f"  {name}: {short(fn())}")
            except Exception as e:
                log(f"  {name} error: {e!r}")
    except Exception as e:
        log(f"  hub error: {e!r}")

    for ip in CAMS:
        log(f"\n=== camera {ip} ===")
        try:
            t = Tapo(ip, "admin", pw, pw)
            try:
                log(f"  storage: {short(t.getSDCard())}")
            except Exception as e:
                log(f"  storage error: {e!r}")
            dates = t.getRecordingsList()
            log(f"  dates with video: {short(dates)}")
            if not dates:
                continue
            day = sorted(str(v) for d in dates for v in (d.values() if isinstance(d, dict) else [d]))[-1]
            day = "".join(ch for ch in day if ch.isdigit())[:8]
            recs = t.getRecordings(day)
            log(f"  {day}: {len(recs)} recordings, first: {short(recs[:3])}")
            first = next(iter(recs[-1].values())) if recs and isinstance(recs[-1], dict) else None
            if first:
                start, end = first.get("startTime"), first.get("endTime")
                log(f"  playing newest {start}..{end}")
                params = {"playback": {"client_id": t.getUserID(), "channels": [0, 1], "scale": "1/1",
                                       "start_time": str(start), "end_time": str(end), "event_type": [1, 2]},
                          "method": "get"}
                log(f"  playback OK: {stream_test(ip, pw, 'PLAY', 5, params=params)}")
        except Exception as e:
            log(f"  error: {e!r}")

    with open(os.path.join(probe.OUT, "probe3_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(probe.log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(probe.OUT, 'probe3_result.txt')}")


if __name__ == "__main__":
    main()
