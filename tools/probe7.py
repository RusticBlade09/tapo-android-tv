"""Probe 7: like probe6, but prints the hub's RAW answer + error code for every guess, so we can tell
"unknown method" (-40210) from "known method, wrong params" (e.g. -40106 / -40101) — the second one is a real lead.
probe6 found the hub has a "localSmart" component (automations that run on the hub) but pytapo hid the error codes.
READ-ONLY: only "get…" requests. Nothing on the hub changes.
Run it YOURSELF:  tools\\.venv\\Scripts\\python tools\\probe7.py
Writes tools/out/probe7_result.txt (no secrets).
"""
import getpass, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe
from probe import log
from pytapo import Tapo

HUB = "192.168.0.215"

NAMES = [
    # control: known to work on this hub, so we see what a good answer looks like
    "getAppComponentList",
    # automations / local smart actions
    "getLocalSmart", "getLocalSmartList", "getLocalSmartConfig", "getLocalSmartInfo", "getLocalSmartRule",
    "getLocalSmartRuleList", "getLocalSmartActionList", "getLocalRuleList", "getLocalScene", "getLocalSceneList",
    "getSmartActionList", "getSmartActionRuleList", "getSceneList", "getRuleList", "getRuleInfoList",
    "getAutomationList", "getAutomationRuleList", "getLinkageList", "getTriggerList",
    # other components on this hub that may touch automations / chimes
    "getEventCenter", "getEventCenterConfig", "getChime", "getChimeConfig", "getChimeList",
    "getSiren", "getSirenConfig", "getSirenStatus", "getSirenTypeList",
]

# params shapes this protocol uses: {} or {"<module>": {"name": [...]}}
PARAMS = [
    {},
    {"local_smart": {"name": ["config", "rule_list"]}},
    {"localSmart": {"name": ["config", "rule_list"]}},
]


def raw(hub, method, params):
    """One request; return (error_code, short text). Never raises."""
    try:
        r = hub.performRequest({"method": "multipleRequest", "params": {"requests": [{"method": method, "params": params}]}})
    except Exception as e:
        # pytapo raises "Error: …, Response: {json}" on an error answer: dig the hub's own code out of it
        s = str(e)
        try:
            r = json.loads(s.split("Response: ", 1)[1])
        except Exception:
            return "EXC", s[:300]
    inner = (r.get("result") or {}).get("responses", [{}])
    inner = inner[0] if inner else {}
    code = inner.get("error_code", r.get("error_code"))
    return code, json.dumps(inner if inner else r)[:1200]


def main():
    pw = getpass.getpass("Tapo account password (not shown, not saved): ")
    hub = Tapo(HUB, "admin", pw, pw)
    leads = []
    for m in NAMES:
        for p in (PARAMS if m != "getAppComponentList" else [{"app_component": {"name": ["app_component_list"]}}]):
            code, text = raw(hub, m, p)
            log(f"{m}  params={json.dumps(p)}  ->  error_code={code}\n    {text}")
            if code not in (-40210, "EXC"):
                leads.append(f"{m} {json.dumps(p)} -> {code}")
            if code == -40210:
                break  # unknown method: other params won't help
    log("\n=== LEADS (anything that is NOT 'unknown method' -40210) ===")
    for l in leads:
        log("  " + l)

    with open(os.path.join(probe.OUT, "probe7_result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(probe.log_lines))
    print(f"\nKlaar. Result saved to {os.path.join(probe.OUT, 'probe7_result.txt')}")


if __name__ == "__main__":
    main()
