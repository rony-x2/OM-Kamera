"""
OM Kamera – MCP-Brücke (stdio <-> HTTP)

Claude Desktop (oder ein anderer MCP-Client) startet dieses Skript als lokalen Prozess.
Es reicht jede JSON-RPC-Nachricht per HTTP an die App OM Kamera auf der Sony-Kamera weiter
(http://<kamera>:8080/mcp) und gibt die Antwort zurück. Nur Python-Standardbibliothek.

Ist die Kamera nicht erreichbar (App geschlossen, WLAN oder MCP aus), antwortet die Brücke
selbst: Werkzeugliste aus dem Zwischenspeicher, Werkzeugaufrufe mit einer klaren Fehlermeldung.

Dazu kommen zwei Werkzeuge der Brücke selbst, weil die Kamera kein aktuelles HTTPS spricht
und Apps nur per USB installiert werden können:
  update_pruefen       neuestes Release auf GitHub mit der Version auf der Kamera vergleichen
  update_installieren  neueste APK laden und mit pmca-console auf die USB-Kamera installieren

Umgebungsvariablen:
  OMKAMERA_URL    z. B. http://192.168.2.38:8080/mcp
  OMKAMERA_TOKEN  der MCP-Token aus dem App-Menü (App > MCP-Schnittstelle)
  OMKAMERA_PMCA   Pfad zu pmca-console.exe (sonst neben der Brücke oder im PATH gesucht)
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

URL = os.environ.get("OMKAMERA_URL", "http://192.168.2.38:8080/mcp")
TOKEN = os.environ.get("OMKAMERA_TOKEN", "")
TIMEOUT = float(os.environ.get("OMKAMERA_TIMEOUT", "20"))
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "mcp_tools.json")
PROTOCOL = "2025-06-18"


def log(msg):
    # stderr landet im MCP-Log von Claude Desktop, stdout ist dem Protokoll vorbehalten
    sys.stderr.buffer.write(("[omkamera] " + msg + "\n").encode("utf-8", "replace"))
    sys.stderr.buffer.flush()


def send(obj):
    # Immer UTF-8 als Bytes: unter Windows wäre die Konsole sonst cp1252
    sys.stdout.buffer.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
    sys.stdout.buffer.flush()


def post(message, timeout):
    """Schickt eine Nachricht an die Kamera. Liefert (status, antwort) oder wirft OSError."""
    data = json.dumps(message, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(URL, data=data, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": "Bearer " + TOKEN,
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, (json.loads(body) if body.strip() else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def cached_tools():
    try:
        with open(CACHE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return []


def store_tools(tools):
    try:
        with open(CACHE, "w", encoding="utf-8") as f:
            json.dump(tools, f, ensure_ascii=False, indent=1)
    except OSError:
        pass


# ---- Werkzeuge der Brücke: Update ----

REPO = os.environ.get("OMKAMERA_REPO", "rony-x2/OM-Kamera")
BRIDGE_TOOLS = [
    {
        "name": "update_pruefen",
        "description": "Prüft auf GitHub, ob es eine neuere Version von OM Kamera gibt, und vergleicht sie mit "
                       "der Version auf der Kamera. Ändert nichts.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "update_installieren",
        "description": "Lädt die neueste APK von GitHub und installiert sie mit pmca-console auf die Kamera, die "
                       "per USB am Rechner hängt (WLAN reicht nicht). Dauert etwa eine Minute; danach muss OM Kamera "
                       "an der Kamera neu gestartet werden. Nur auf ausdrücklichen Wunsch aufrufen.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]
BRIDGE_NAMES = {t["name"] for t in BRIDGE_TOOLS}


def version_tuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v or "")[:4])


def latest_release():
    req = urllib.request.Request("https://api.github.com/repos/%s/releases/latest" % REPO,
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "omkamera-bridge"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        rel = json.loads(resp.read().decode("utf-8"))
    apk = next((a for a in rel.get("assets", []) if a.get("name", "").endswith(".apk")), None)
    return rel, apk


def camera_version():
    """Version der App auf der Kamera per WLAN, None wenn nicht erreichbar."""
    try:
        status, answer = post({"jsonrpc": "2.0", "id": 0, "method": "tools/call",
                               "params": {"name": "status", "arguments": {}}}, 6)
        text = answer["result"]["content"][0]["text"]
        return json.loads(text).get("app")
    except Exception:
        return None


def find_pmca():
    for c in (os.environ.get("OMKAMERA_PMCA"), os.path.join(HERE, "pmca-console.exe"),
              shutil.which("pmca-console"), shutil.which("pmca-console.exe")):
        if c and os.path.isfile(c):
            return c
    return None


def bridge_tool(name):
    """Führt ein Werkzeug der Brücke aus; liefert (text, fehler)."""
    try:
        rel, apk = latest_release()
    except Exception as e:
        return "GitHub nicht erreichbar: %s" % e, True
    tag = rel.get("tag_name", "?")
    cam = camera_version()
    if name == "update_pruefen":
        lines = ["Neueste Version auf GitHub: %s (%s)" % (tag, rel.get("html_url", ""))]
        if cam is None:
            lines.append("Kamera per WLAN nicht erreichbar – Version dort unbekannt.")
        elif version_tuple(tag) > version_tuple(cam):
            lines.append("Auf der Kamera: %s – Update verfügbar. Installation per USB mit update_installieren "
                         "oder tools/omk-update.ps1." % cam)
        else:
            lines.append("Auf der Kamera: %s – aktuell." % cam)
        return "\n".join(lines), False
    # update_installieren
    if apk is None:
        return "Im Release %s ist keine APK." % tag, True
    pmca = find_pmca()
    if pmca is None:
        return "pmca-console.exe nicht gefunden – OMKAMERA_PMCA setzen oder neben die Brücke legen.", True
    target = os.path.join(tempfile.gettempdir(), apk["name"])
    try:
        req = urllib.request.Request(apk["browser_download_url"], headers={"User-Agent": "omkamera-bridge"})
        with urllib.request.urlopen(req, timeout=120) as resp, open(target, "wb") as f:
            shutil.copyfileobj(resp, f)
        if os.path.getsize(target) != apk.get("size", os.path.getsize(target)):
            return "Download unvollständig: %s" % target, True
        run = subprocess.run([pmca, "install", "-f", target], stdin=subprocess.DEVNULL,
                             capture_output=True, timeout=600)
    except subprocess.TimeoutExpired:
        return "pmca-console hat nach 10 Minuten nicht geantwortet.", True
    except Exception as e:
        return "Installation fehlgeschlagen: %s" % e, True
    out = (run.stdout + run.stderr).decode("utf-8", "replace")
    shown = "\n".join(l for l in out.splitlines() if not re.match(r"^(Down|Up)loading \d+%|^Installing \d+%", l))
    if "Task completed successfully" not in out:
        return "Installation nicht bestätigt – hängt die Kamera per USB am Rechner?\n\n" + shown, True
    return ("%s installiert (vorher %s). OM Kamera an der Kamera über das Anwendungsmenü neu starten.\n\n%s"
            % (tag, cam or "unbekannt", shown)), False


def bridge_call(message):
    params = message.get("params") or {}
    text, error = bridge_tool(params.get("name"))
    return {"jsonrpc": "2.0", "id": message.get("id"),
            "result": {"content": [{"type": "text", "text": text}], "isError": error}}


def with_bridge_tools(tools):
    names = {t.get("name") for t in tools}
    return list(tools) + [t for t in BRIDGE_TOOLS if t["name"] not in names]


def local_answer(message, reason):
    """Antwort der Brücke, wenn die Kamera nicht antwortet."""
    mid = message.get("id")
    method = message.get("method", "")
    if method == "initialize":
        version = (message.get("params") or {}).get("protocolVersion", PROTOCOL)
        result = {
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "om-kamera", "version": "bruecke"},
            "instructions": "Steuerung der Sony-Kamera über die App OM Kamera. "
                            "Die Kamera war beim Start nicht erreichbar: " + reason,
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": with_bridge_tools(cached_tools())}
    elif method == "tools/call":
        result = {
            "content": [{"type": "text", "text": "Kamera nicht erreichbar: " + reason
                         + ". Läuft OM Kamera, und sind WLAN und MCP-Schnittstelle im App-Menü eingeschaltet?"}],
            "isError": True,
        }
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "Unbekannte Methode " + method}}
    return {"jsonrpc": "2.0", "id": mid, "result": result}


def handle(message):
    is_request = "id" in message
    if message.get("method") == "tools/call" and (message.get("params") or {}).get("name") in BRIDGE_NAMES:
        return bridge_call(message) if is_request else None
    # Start und Werkzeugliste sollen Claude nicht aufhalten, wenn die Kamera aus ist
    quick = message.get("method") != "tools/call"
    try:
        status, answer = post(message, 4 if quick else TIMEOUT)
    except (OSError, ValueError) as e:
        reason = str(getattr(e, "reason", e))
        log("Kamera nicht erreichbar: " + reason)
        return local_answer(message, reason) if is_request else None
    if not is_request:
        return None
    if status == 200 and isinstance(answer, dict):
        if message.get("method") == "tools/list" and "result" in answer:
            store_tools(answer["result"].get("tools", []))
            answer["result"]["tools"] = with_bridge_tools(answer["result"].get("tools", []))
        return answer
    reason = "HTTP %s %s" % (status, answer if isinstance(answer, str) else "")
    if status == 401:
        reason = "Token falsch – OMKAMERA_TOKEN mit dem Token im App-Menü vergleichen"
    elif status == 403:
        reason = "MCP-Schnittstelle ist im App-Menü ausgeschaltet"
    log(reason)
    return local_answer(message, reason.strip())


def main():
    log("Brücke gestartet, Ziel " + URL)
    for raw in sys.stdin.buffer:
        line = raw.decode("utf-8").strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            send({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "JSON nicht lesbar"}})
            continue
        messages = message if isinstance(message, list) else [message]
        for m in messages:
            answer = handle(m)
            if answer is not None:
                send(answer)


if __name__ == "__main__":
    main()
