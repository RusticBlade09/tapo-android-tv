"""Probe 6: can the H500 HomeBase show its automations (Tapo "Smart Actions") LOCALLY?
pytapo has no function for it, so this asks the hub:
  1) its FULL component list (a component name hints at a feature, e.g. something with "scene" / "linkage" / "action")
  2) a list of guessed "get…" methods for automations / scenes / rules
READ-ONLY: it only sends "get…" requests, never "set", "add", "delete" or "do". Nothing on the hub changes.
Run it YOURSELF:  tools\\.venv\\Scripts\\python tools\\probe6.py
Writes tools/out/probe6_result.txt (no secrets).
"""
import getpass, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe
from probe import log
from pytapo import Tapo

HUB = "192.168.0.215"

# Guesses, in the camera/hub naming style ("getGeneralDeviceList", "searchDetectionList" work on this hub).
GUESSES = [
    "getSmartActionList", "getSmartActions", "getSceneList", "getScenes", "getSmartScene", "getSceneInfo",
    "getAutomationList", "getAutomations", "getRuleList", "getRules", "getLinkageList", "getLinkageConfig",
    "getLocalSceneList", "getLocalLinkage", "getIotCloudConfig", "getSirenConfig", "getSirenStatus",
    "getAlarmConfig", "getChildDeviceComponentList", "getTriggerList", "getActionList",
]


def ask(hub, method, params):
    try:
        r = hub.executeFunction(method, params)
        log(f"  OK    {method}: {json.dumps(r)[:1500]}")
    except Exception as e:
        log(f"  ERROR {method}: {str(e)[:200]}")


def main():
    pw = getpass.getpass("Tapo account password (not shown, not saved): ")
    hub = Tapo(HUB, "admin", pw, pw)

    log("=== hub components (full list) ===")
    try:
        r = hub.executeFunction("getAppComponentList", {"app_component": {"name": ["app_component_list"]}})
        names = [c.get("name") for c in r.get("app_component", {}).get("app_component_list", [])]
        log("  " + ", ".join(f"{c}" for c in names))
    except Exception as e:
        names = []
        log(f"  ERROR: {str(e)[:300]}")

    log("\n=== guessed automation methods ===")
    for m in GUESSES:
        ask(hub, m, {})

    # Each component may have a matching "get<Name>Config" / "get<Name>Info" (still read-only).
    hints = [n for n in names if any(w in n.lower() for w in ("scene", "link", "action", "rule", "auto", "smart", "iot", "trigger"))]
    log(f"\n=== components that sound like automations: {hints} ===")
    for n in hints:
        cap = n[0].upper() + n[1:]
        for m in (f"get{cap}", f"get{cap}Config", f"get{cap}Info", f"get{cap}List"):
            ask(hub, m, {})

    with open(os.path.join(probe.OUT, "probe6_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(probe.log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(probe.OUT, 'probe6_result.txt')}")


if __name__ == "__main__":
    main()
