#!/usr/bin/env python3
"""SteamStream Host - runs on your gaming PC (zero dependencies, Python 3.9+).

Exposes a small token-protected HTTP API so the Android app can:
  GET  /status     host info, Steam/Sunshine running, MAC (for Wake-on-LAN)
  GET  /library    installed + owned games
  POST /launch     {"appid": 620}  start a game on the PC
  POST /install    {"appid": 620}  start a Steam install
  POST /uninstall  {"appid": 620}  open Steam's uninstall prompt
Video streaming itself is done by Sunshine + Moonlight.
"""
import json, os, re, secrets, socket, subprocess, sys, time, urllib.request, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_DIR = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).parent
CONFIG_PATH = APP_DIR / "config.json"
DEFAULTS = {"port": 8765, "token": "", "steam_api_key": "", "steam_id": ""}
IGNORED_APPIDS = {228980}  # Steamworks Common Redistributables


def load_config():
    cfg = dict(DEFAULTS)
    if CONFIG_PATH.exists():
        cfg.update(json.loads(CONFIG_PATH.read_text()))
    if not cfg["token"]:
        cfg["token"] = secrets.token_urlsafe(24)
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))
    return cfg


CFG = load_config()


# ---------- Steam helpers ----------
def steam_root():
    if sys.platform == "win32":
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
                return Path(winreg.QueryValueEx(k, "SteamPath")[0])
        except OSError:
            pass
        for p in (r"C:\Program Files (x86)\Steam", r"C:\Program Files\Steam"):
            if Path(p).exists():
                return Path(p)
    else:
        for p in ("~/.steam/steam", "~/.local/share/Steam"):
            if Path(p).expanduser().exists():
                return Path(p).expanduser()
    return None


def library_dirs():
    root = steam_root()
    if not root:
        return []
    dirs = [root / "steamapps"]
    vdf = root / "steamapps" / "libraryfolders.vdf"
    if vdf.exists():
        for m in re.finditer(r'"path"\s+"([^"]+)"', vdf.read_text(errors="ignore")):
            d = Path(m.group(1).replace("\\\\", "\\")) / "steamapps"
            if d not in dirs:
                dirs.append(d)
    return [d for d in dirs if d.exists()]


def installed_games():
    games = {}
    for d in library_dirs():
        for f in d.glob("appmanifest_*.acf"):
            t = f.read_text(errors="ignore")
            a = re.search(r'"appid"\s+"(\d+)"', t)
            n = re.search(r'"name"\s+"([^"]*)"', t)
            if a and n and int(a.group(1)) not in IGNORED_APPIDS:
                games[int(a.group(1))] = n.group(1)
    return games


_owned_cache = (0.0, {})


def owned_games():
    global _owned_cache
    if not (CFG["steam_api_key"] and CFG["steam_id"]):
        return {}
    if time.time() - _owned_cache[0] < 300:
        return _owned_cache[1]
    url = ("https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/"
           f"?key={CFG['steam_api_key']}&steamid={CFG['steam_id']}&include_appinfo=1&format=json")
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            data = json.load(r)
        games = {g["appid"]: g.get("name", str(g["appid"])) for g in data["response"].get("games", [])}
        _owned_cache = (time.time(), games)
    except Exception as e:
        print("Steam API error:", e)
    return _owned_cache[1]


def library():
    inst = installed_games()
    out = {a: {"appid": a, "name": n, "installed": True} for a, n in inst.items()}
    for a, n in owned_games().items():
        if a not in out and a not in IGNORED_APPIDS:
            out[a] = {"appid": a, "name": n, "installed": False}
    return sorted(out.values(), key=lambda g: g["name"].lower())


def open_uri(uri):
    if sys.platform == "win32":
        os.startfile(uri)  # noqa
    elif sys.platform == "darwin":
        subprocess.Popen(["open", uri])
    else:
        subprocess.Popen(["xdg-open", uri])


def running(name):
    try:
        if sys.platform == "win32":
            out = subprocess.run(["tasklist", "/FI", f"IMAGENAME eq {name}.exe", "/NH"],
                                 capture_output=True, text=True).stdout
            return name.lower() in out.lower()
        return subprocess.run(["pgrep", "-x", name], capture_output=True).returncode == 0
    except Exception:
        return False


def mac_address():
    return ":".join(f"{(uuid.getnode() >> i) & 0xff:02x}" for i in range(40, -1, -8))


def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


# ---------- HTTP API ----------
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        got = self.headers.get("Authorization", "")
        return secrets.compare_digest(got, f"Bearer {CFG['token']}")

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def do_GET(self):
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        if self.path == "/status":
            return self._send(200, {
                "hostname": socket.gethostname(), "mac": mac_address(),
                "steam": running("steam"), "sunshine": running("sunshine")})
        if self.path == "/library":
            return self._send(200, {"games": library()})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        try:
            appid = int(self._body().get("appid"))
        except (TypeError, ValueError):
            return self._send(400, {"error": "appid required"})
        actions = {"/launch": f"steam://rungameid/{appid}",
                   "/install": f"steam://install/{appid}",
                   "/uninstall": f"steam://uninstall/{appid}"}
        if self.path not in actions:
            return self._send(404, {"error": "not found"})
        open_uri(actions[self.path])
        self._send(200, {"ok": True})


def main():
    port = int(CFG["port"])
    print("=== SteamStream Host ===")
    print("Token  :", CFG["token"])
    print("Port   :", port)
    for ip in local_ips():
        print(f"Address: http://{ip}:{port}")
    print("Tip: use your Tailscale IP (100.x.y.z) in the Android app to connect from anywhere.")
    if not (CFG["steam_api_key"] and CFG["steam_id"]):
        print(f"Note: only INSTALLED games are listed. Add steam_api_key and steam_id to {CONFIG_PATH} to list your whole library.")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
