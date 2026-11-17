"""Probe 2: the cameras hang on the Tapo H500 HomeBase. Ask the hub for its cameras,
then try video + PTZ for each one THROUGH the hub (like pytapo childID / go2rtc ?deviceId=).

Run it YOURSELF:  tools\\.venv\\Scripts\\python tools\\probe2.py
Writes tools/out/probe2_result.txt (no secrets).
"""
import getpass, json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe  # reuse the stream test + logger
from probe import log, stream_test
from pytapo import Tapo

HUB = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.215"
SAFE = ("alias", "device_model", "model", "device_type", "ip", "status", "firmware_ver", "sw_ver",
        "hw_ver", "power_supply", "battery_percent", "low_battery", "connected", "online")


def main():
    pw = getpass.getpass(f"Tapo account password for hub {HUB} (not shown, not saved): ")
    hub = Tapo(HUB, "admin", pw, pw)
    kids = hub.getChildDevices()
    log(f"Hub {HUB}: {len(kids) if isinstance(kids, list) else '?'} cameras")
    if not isinstance(kids, list):
        log(f"  raw reply keys: {list(kids)[:20] if isinstance(kids, dict) else type(kids)}"
            f" sum={kids.get('sum') if isinstance(kids, dict) else '?'}")
        kids = kids.get("child_device_list", []) if isinstance(kids, dict) else []
    for k in kids:
        dev = k.get("device_id", "")
        log(f"\n=== child {dev[-6:]} ===")
        log("  " + json.dumps({key: k[key] for key in SAFE if key in k}))
        log(f"  all field names: {sorted(k)}")
        for res in ("VGA", "HD"):
            try:
                log(f"  {res} OK: {stream_test(HUB, pw, res, 4, device_id=dev)}")
            except Exception as e:
                log(f"  {res} stream error: {e!r}")
        try:
            cam = Tapo(HUB, "admin", pw, pw, childID=dev)
            if input(f"  Move '{k.get('alias', dev[-6:])}' a little left and back? (j/n) ").strip().lower() in ("j", "y"):
                cam.moveMotor(10, 0); time.sleep(2); cam.moveMotor(-10, 0)
                log("  PTZ moveMotor via hub: OK")
        except Exception as e:
            log(f"  PTZ error: {e!r}")

    with open(os.path.join(probe.OUT, "probe2_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(probe.log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(probe.OUT, 'probe2_result.txt')}")


if __name__ == "__main__":
    main()
