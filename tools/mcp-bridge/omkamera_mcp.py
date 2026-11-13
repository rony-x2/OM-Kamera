"""
OM Kamera – MCP-Brücke (stdio <-> HTTP)

Claude Desktop (oder ein anderer MCP-Client) startet dieses Skript als lokalen Prozess.
Es reicht jede JSON-RPC-Nachricht per HTTP an die App OM Kamera auf der Sony-Kamera weiter
(http://<kamera>:8080/mcp) und gibt die Antwort zurück. Nur Python-Standardbibliothek.

Ist die Kamera nicht erreichbar (App geschlossen, WLAN oder MCP aus), antwortet die Brücke
selbst: Werkzeugliste aus dem Zwischenspeicher, Werkzeugaufrufe mit einer klaren Fehlermeldung.

Umgebungsvariablen:
  OMKAMERA_URL    z. B. http://192.168.2.38:8080/mcp
  OMKAMERA_TOKEN  der MCP-Token aus dem App-Menü (App > MCP-Schnittstelle)
"""

import json
import os
import sys
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
    print("[omkamera] " + msg, file=sys.stderr, flush=True)


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


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
        result = {"tools": cached_tools()}
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
