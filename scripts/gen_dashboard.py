# Generates charts/ad-loadtest/dashboards/ad-loadtest.json (also used by docker-compose Grafana):
#   python3 scripts/gen_dashboard.py charts/ad-loadtest/dashboards/ad-loadtest.json
import json
import re
import sys

out = sys.argv[1]
J = {"type": "influxdb", "uid": "influx-jmeter"}
T = {"type": "influxdb", "uid": "influx-telegraf"}
APP = "\"application\" =~ /^$application$/"
TX = "\"transaction\" =~ /^$transaction$/"
PER_TX = f"{APP} AND {TX} AND \"transaction\" != 'all' AND \"transaction\" != 'internal' AND \"statut\" = 'all'"
PER_TX_KO = PER_TX.replace("\"statut\" = 'all'", "\"statut\" = 'ko'")

# Colour follows the transaction, never its rank (reference palette, dark steps, fixed order).
SERIES = {
    "LDAP bind": "#3987e5",
    "LDAP authz (group in chain)": "#d95926",
    "Kerberos TGT (AS)": "#199e70",
    "Kerberos service ticket (TGS)": "#c98500",
    "NTLM SMB session": "#d55181",
    "Computer Kerberos TGT (AS)": "#008300",
    "Computer Kerberos service ticket (TGS)": "#9085e9",
}
CRITICAL = "#d03b3b"


# User-profile variants ("LDAP bind [heavy]") keep their transaction's colour; the profile is the line style.
PROFILE_DASH = {"heavy": [10, 6], "deep": [2, 4], "disabled": [1, 3], "expired": [1, 3], "bad-password": [6, 3, 1, 3]}


def overrides():
    def escape(name):
        return re.sub(r"([()\[\]])", r"\\\1", name)
    colours = [{"matcher": {"id": "byRegexp", "options": f"^{escape(name)}( \\[.*\\])?$"},
                "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}
               for name, c in SERIES.items()]
    dashes = [{"matcher": {"id": "byRegexp", "options": f".* \\[{profile}\\]$"},
               "properties": [{"id": "custom.lineStyle", "value": {"fill": "dash", "dash": dash}}]}
              for profile, dash in PROFILE_DASH.items()]
    return colours + dashes


pid = 0


def panel(title, kind, gridPos, targets, unit=None, desc="", extra=None, ds=J):
    global pid
    pid += 1
    p = {"id": pid, "title": title, "type": kind, "gridPos": gridPos, "datasource": ds,
         "description": desc,
         "targets": [{"refId": chr(65 + i), "rawQuery": True, "resultFormat": t.get("format", "time_series"),
                      "query": t["q"], "alias": t.get("alias", "$tag_transaction"), "datasource": ds}
                     for i, t in enumerate(targets)],
         "fieldConfig": {"defaults": {}, "overrides": []}, "options": {}}
    if unit:
        p["fieldConfig"]["defaults"]["unit"] = unit
    if kind == "timeseries":
        p["fieldConfig"]["defaults"]["custom"] = {"lineWidth": 2, "fillOpacity": 0, "showPoints": "never",
                                                  "spanNulls": True, "axisSoftMin": 0}
        p["fieldConfig"]["overrides"] = overrides()
        p["options"] = {"legend": {"displayMode": "table", "placement": "right", "calcs": ["mean", "max"]},
                        "tooltip": {"mode": "multi", "sort": "desc"}}
    if kind == "stat":
        p["fieldConfig"]["defaults"]["color"] = {"mode": "fixed", "fixedColor": "text"}
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value",
                        "graphMode": "none", "textMode": "value"}
    if extra:
        for k, v in extra.items():
            if isinstance(v, dict) and isinstance(p.get(k), dict):
                p[k].update(v)
            else:
                p[k] = v
    return p


def row(title, y):
    global pid
    pid += 1
    return {"id": pid, "type": "row", "title": title, "collapsed": False,
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []}


ALL = f"{APP} AND \"transaction\" = 'all' AND \"statut\" = 'all' AND $timeFilter"
panels = [
    row("Overview", 0),
    panel("Requests", "stat", {"h": 4, "w": 4, "x": 0, "y": 1},
          [{"q": f"SELECT sum(\"count\") FROM \"jmeter\" WHERE {ALL}", "alias": "Requests"}],
          unit="short", desc="Total samples in the selected time range, all protocols."),
    panel("Throughput", "stat", {"h": 4, "w": 4, "x": 4, "y": 1},
          [{"q": f"SELECT sum(\"count\") / 5 FROM \"jmeter\" WHERE {ALL} GROUP BY time(5s) fill(none)", "alias": "req/s"}],
          unit="reqps", desc="Mean authentications + authorisations per second over the range.",
          extra={"options": {"reduceOptions": {"calcs": ["mean"]}, "colorMode": "value", "graphMode": "area", "textMode": "value"}}),
    panel("Mean response", "stat", {"h": 4, "w": 4, "x": 8, "y": 1},
          [{"q": f"SELECT mean(\"avg\") FROM \"jmeter\" WHERE {ALL}", "alias": "mean"}],
          unit="ms", desc="Mean sample time across all transactions."),
    panel("p95 response", "stat", {"h": 4, "w": 4, "x": 12, "y": 1},
          [{"q": f"SELECT max(\"pct95.0\") FROM \"jmeter\" WHERE {ALL}", "alias": "p95"}],
          unit="ms", desc="Worst 5-second p95 seen in the range."),
    panel("Errors", "stat", {"h": 4, "w": 4, "x": 16, "y": 1},
          [{"q": f"SELECT sum(\"countError\") FROM \"jmeter\" WHERE {ALL}", "alias": "errors"}],
          desc="Failed samples: auth failures, timeouts and unexpected authorisation outcomes.",
          extra={"fieldConfig": {"defaults": {"unit": "short", "color": {"mode": "thresholds"},
                 "thresholds": {"mode": "absolute", "steps": [{"color": "text", "value": None}, {"color": CRITICAL, "value": 1}]}},
                 "overrides": []}}),
    panel("Active threads", "stat", {"h": 4, "w": 4, "x": 20, "y": 1},
          [{"q": f"SELECT max(\"meanAT\") FROM \"jmeter\" WHERE {APP} AND \"transaction\" = 'internal' AND $timeFilter GROUP BY time(5s) fill(none)", "alias": "threads"}],
          unit="short", desc="Virtual users currently running."),

    row("Per transaction", 5),
    panel("Throughput by transaction", "timeseries", {"h": 9, "w": 12, "x": 0, "y": 6},
          [{"q": f"SELECT sum(\"count\") / 5 FROM \"jmeter\" WHERE {PER_TX} AND $timeFilter GROUP BY time(5s), \"transaction\" fill(null)"}],
          unit="reqps", desc="Samples per second for each LDAP, Kerberos and NTLM operation."),
    panel("p95 response time by transaction", "timeseries", {"h": 9, "w": 12, "x": 12, "y": 6},
          [{"q": f"SELECT mean(\"pct95.0\") FROM \"jmeter\" WHERE {PER_TX} AND $timeFilter GROUP BY time($__interval), \"transaction\" fill(null)"}],
          unit="ms", desc="95th percentile sample time per 5-second window."),
    panel("Mean response time by transaction", "timeseries", {"h": 9, "w": 12, "x": 0, "y": 15},
          [{"q": f"SELECT mean(\"avg\") FROM \"jmeter\" WHERE {PER_TX} AND $timeFilter GROUP BY time($__interval), \"transaction\" fill(null)"}],
          unit="ms"),
    panel("Errors by transaction", "timeseries", {"h": 9, "w": 12, "x": 12, "y": 15},
          [{"q": f"SELECT sum(\"count\") / 5 FROM \"jmeter\" WHERE {PER_TX_KO} AND $timeFilter GROUP BY time(5s), \"transaction\" fill(0)"}],
          unit="reqps", desc="Failed samples per second."),
    panel("Summary", "table", {"h": 8, "w": 24, "x": 0, "y": 24},
          [{"q": f"SELECT sum(\"count\") AS \"Samples\", mean(\"avg\") AS \"Mean ms\", "
                 f"max(\"pct95.0\") AS \"Max p95 ms\", max(\"pct99.0\") AS \"Max p99 ms\", max(\"max\") AS \"Max ms\" "
                 f"FROM \"jmeter\" WHERE {PER_TX} AND $timeFilter GROUP BY \"transaction\"", "format": "table", "alias": ""},
           {"q": f"SELECT sum(\"count\") AS \"Errors\" FROM \"jmeter\" WHERE {PER_TX_KO} AND $timeFilter GROUP BY \"transaction\"", "format": "table", "alias": ""}],
          desc="Totals for the selected range.",
          extra={"transformations": [{"id": "merge", "options": {}},
                                     {"id": "organize", "options": {"excludeByName": {"Time": True},
                                                                   "indexByName": {"transaction": 0, "Samples": 1, "Errors": 2},
                                                                   "renameByName": {"transaction": "Transaction"}}}],
                 "fieldConfig": {"defaults": {"decimals": 1}, "overrides": []}}),
    panel("Errors by response code", "table", {"h": 7, "w": 24, "x": 0, "y": 32},
          [{"q": f"SELECT sum(\"count\") AS \"Count\" FROM \"jmeter\" WHERE {APP} AND {TX} AND \"responseCode\" =~ /.+/ AND $timeFilter "
                 f"GROUP BY \"transaction\", \"responseCode\", \"responseMessage\"", "format": "table", "alias": ""}],
          desc="What went wrong: LDAP result codes, Kerberos/GSS errors and NT_STATUS codes.",
          extra={"transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True}}}]}),

    row("Domain controller (container)", 39),
    panel("DC CPU", "timeseries", {"h": 8, "w": 12, "x": 0, "y": 40},
          [{"q": "SELECT mean(\"usage_percent\") FROM \"docker_container_cpu\" WHERE \"container_name\" =~ /dc1/ AND \"cpu\" = 'cpu-total' AND $timeFilter GROUP BY time($__interval) fill(null)",
            "alias": "Samba DC"}],
          unit="percent", ds=T, desc="CPU of the Samba DC container (100% = one core)."),
    panel("DC memory", "timeseries", {"h": 8, "w": 12, "x": 12, "y": 40},
          [{"q": "SELECT mean(\"usage\") FROM \"docker_container_mem\" WHERE \"container_name\" =~ /dc1/ AND $timeFilter GROUP BY time($__interval) fill(null)",
            "alias": "Samba DC"}],
          unit="bytes", ds=T),
]
single = panels[-2:]


def dc_panel(title, field, unit, y, x, desc, where=""):
    q = (f"SELECT mean(\"{field}\") FROM \"dc\" WHERE $timeFilter {where}"
         f"GROUP BY time($__interval), \"host\" fill(null)")
    p = panel(title, "timeseries", {"h": 8, "w": 8, "x": x, "y": y}, [{"q": q, "alias": "$tag_host"}],
              unit=unit, ds=T, desc=desc)
    p["fieldConfig"]["overrides"] = []
    p["fieldConfig"]["defaults"]["color"] = {"mode": "palette-classic"}
    return p


panels += [
    row("Domain controllers (Windows performance counters)", 48),
    dc_panel("DC CPU", "win_cpu_Percent_Processor_Time", "percent", 49, 0, "Total processor time on each DC."),
    dc_panel("LSASS CPU", "win_proc_Percent_Processor_Time", "percent", 49, 8,
             "CPU of lsass.exe, where authentication happens (100% = one core). Security agents usually hook in here."),
    dc_panel("LSASS working set", "win_proc_Working_Set", "bytes", 49, 16, "Memory used by lsass.exe."),
    dc_panel("Kerberos authentications", "win_auth_Kerberos_Authentications", "reqps", 57, 0,
             "Security System-Wide Statistics: Kerberos ticket use per second."),
    dc_panel("NTLM authentications", "win_auth_NTLM_Authentications", "reqps", 57, 8,
             "Security System-Wide Statistics: NTLM authentications per second."),
    dc_panel("LDAP binds", "win_ntds_LDAP_Successful_Binds_persec", "reqps", 57, 16, "NTDS: successful LDAP binds per second."),
    dc_panel("LDAP searches", "win_ntds_LDAP_Searches_persec", "reqps", 65, 0, "NTDS: LDAP searches per second."),
    dc_panel("ATQ request latency", "win_ntds_ATQ_Request_Latency", "ms", 65, 8,
             "NTDS: time LDAP requests wait in the ATQ thread pool; rises when the DC is saturated."),
    dc_panel("Netlogon semaphore waiters", "win_netlogon_Semaphore_Waiters", "short", 65, 16,
             "NTLM pass-through requests waiting for a MaxConcurrentApi slot; above 0 means NTLM is queueing."),
]
# Single-series panels: the title names the series, so no legend box.
for p in single:
    p["fieldConfig"]["overrides"] = []
    p["fieldConfig"]["defaults"]["color"] = {"mode": "fixed", "fixedColor": "#3987e5"}
    p["options"]["legend"] = {"displayMode": "list", "placement": "bottom", "showLegend": False}

dashboard = {
    "uid": "ad-loadtest",
    "title": "AD authentication load test",
    "tags": ["jmeter", "active-directory"],
    "timezone": "browser",
    "refresh": "5s",
    "time": {"from": "now-15m", "to": "now"},
    "schemaVersion": 39,
    "templating": {"list": [
        {"name": "application", "label": "Application", "type": "query", "datasource": J,
         "query": "SHOW TAG VALUES FROM \"jmeter\" WITH KEY = \"application\"", "refresh": 2,
         "current": {"text": "ad-loadtest", "value": "ad-loadtest"}},
        {"name": "transaction", "label": "Transaction", "type": "query", "datasource": J,
         "query": "SHOW TAG VALUES FROM \"jmeter\" WITH KEY = \"transaction\" WHERE \"application\" =~ /^$application$/ AND \"transaction\" != 'all' AND \"transaction\" != 'internal'",
         "refresh": 2, "multi": True, "includeAll": True, "allValue": ".*",
         "current": {"text": "All", "value": "$__all"}},
    ]},
    "annotations": {"list": [
        {"name": "Test start/end", "datasource": J, "enable": True, "iconColor": "#c3c2b7",
         "query": f"SELECT \"text\" FROM \"events\" WHERE {APP} AND $timeFilter", "textColumn": "text"}]},
    "panels": panels,
}
with open(out, "w") as f:
    json.dump(dashboard, f, indent=2)
print("panels:", len(panels))
