"""Probe 4: try several ways to reach the H500 HomeBase recordings. Read-only questions only
(no settings are changed). Run it YOURSELF:  tools\\.venv\\Scripts\\python tools\\probe4.py
Writes tools/out/probe4_result.txt (no secrets).
"""
import getpass, json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe
from probe import log
from pytapo import Tapo

CAMS = ["192.168.0.122", "192.168.0.229"]
HUB = "192.168.0.215"
NOW = int(time.time())
DAY = time.strftime("%Y%m%d")


def ask(t, label, method, params):
    """One raw request; log the reply (or the error) and return it."""
    try:
        r = t.executeFunction(method, params)
        log(f"  {label}: {json.dumps(r, default=str)[:900]}")
        return r
    except Exception as e:
        log(f"  {label} ERROR: {str(e)[:400]}")
        return None


def searches(t, prefix, uid=None):
    ask(t, f"{prefix} searchDateWithVideo", "searchDateWithVideo",
        {"playback": {"search_year_utility": {"channel": [0], "start_date": "20250101", "end_date": DAY}}})
    for idkey in ("id", "player_id"):
        ask(t, f"{prefix} searchVideoWithUTC({idkey})", "searchVideoWithUTC",
            {"playback": {"search_video_with_utc": {"channel": 0, "start_time": NOW - 86400, "end_time": NOW,
                                                    "start_index": 0, "end_index": 20, idkey: uid or 1}}})


def main():
    pw = getpass.getpass("Tapo account password (not shown, not saved): ")
    dev_ids = {}
    for ip in CAMS:
        log(f"=== camera {ip} ===")
        t = Tapo(ip, "admin", pw, pw)
        info = ask(t, "basic_info", "getDeviceInfo", {"device_info": {"name": ["basic_info"]}})
        dev_ids[ip] = (info or {}).get("device_info", {}).get("basic_info", {}).get("dev_id")
        ask(t, "hub storage", "getHubStorage", {"hub_manage": {"name": "hub_storage_info"}})
        ask(t, "components", "getAppComponentList", {"app_component": {"name": ["app_component_list"]}})
        uid = None
        try:
            uid = t.getUserID()
        except Exception as e:
            log(f"  userID ERROR: {e!r}")
        searches(t, "camera", uid)

    log(f"\n=== hub {HUB} ===")
    hub = Tapo(HUB, "admin", pw, pw)
    ask(hub, "components", "getAppComponentList", {"app_component": {"name": ["app_component_list"]}})
    ask(hub, "device info", "getDeviceInfo", {"device_info": {"name": ["basic_info"]}})
    searches(hub, "hub")
    for ip, dev in dev_ids.items():
        if not dev:
            continue
        log(f"--- hub, as child {ip} ---")
        try:
            child = Tapo(HUB, "admin", pw, pw, childID=dev)
            searches(child, "hub-child")
        except Exception as e:
            log(f"  child ERROR: {e!r}")

    with open(os.path.join(probe.OUT, "probe4_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(probe.log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(probe.OUT, 'probe4_result.txt')}")


if __name__ == "__main__":
    main()
