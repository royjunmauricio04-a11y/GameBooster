#!/usr/bin/env python3
"""Registration agent: verifies a Supabase user JWT, then authorises that user's device
WireGuard public key on wg0 and returns a tunnel address. Listens on localhost only;
put Caddy (TLS) in front. No private keys or admin credentials are handled here."""
import ipaddress, json, os, re, subprocess, threading, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SUPABASE_URL = os.environ["SUPABASE_URL"].rstrip("/")
ANON_KEY = os.environ["SUPABASE_ANON_KEY"]
IFACE = os.environ.get("WG_IFACE", "wg0")
SUBNET = ipaddress.ip_network(os.environ.get("WG_SUBNET", "10.8.0.0/24"))
DNS = os.environ.get("WG_DNS", "1.1.1.1")
STATE = os.environ.get("STATE_FILE", "/var/lib/gamebooster/peers.json")
KEY_RE = re.compile(r"^[A-Za-z0-9+/]{43}=$")
lock = threading.Lock()

def load():
    try:
        with open(STATE) as f: return json.load(f)
    except FileNotFoundError:
        return {}

def save(peers):
    tmp = STATE + ".tmp"
    with open(tmp, "w") as f: json.dump(peers, f)
    os.replace(tmp, STATE)

def wg(*args):
    subprocess.run(["wg", *args], check=True, capture_output=True)

def apply_all(peers):
    for p in peers.values():
        wg("set", IFACE, "peer", p["pub"], "allowed-ips", p["ip"] + "/32")

def verify(token):
    req = urllib.request.Request(SUPABASE_URL + "/auth/v1/user",
        headers={"apikey": ANON_KEY, "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.load(r).get("id")
    except (urllib.error.URLError, ValueError):
        return None

def register(uid, pub):
    with lock:
        peers = load()
        cur = peers.get(uid)
        if cur and cur["pub"] != pub:
            wg("set", IFACE, "peer", cur["pub"], "remove")
        used = {p["ip"] for k, p in peers.items() if k != uid}
        ip = cur["ip"] if cur else next(
            (str(h) for i, h in enumerate(SUBNET.hosts()) if i >= 1 and str(h) not in used), None)
        if ip is None: raise RuntimeError("address pool exhausted")
        wg("set", IFACE, "peer", pub, "allowed-ips", ip + "/32")
        peers[uid] = {"pub": pub, "ip": ip}
        save(peers)
        return ip

class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_POST(self):
        if self.path != "/register": return self._send(404, {"error": "not found"})
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "): return self._send(401, {"error": "missing token"})
        uid = verify(auth[7:])
        if not uid: return self._send(401, {"error": "invalid token"})
        n = int(self.headers.get("Content-Length", 0))
        if n <= 0 or n > 1024: return self._send(400, {"error": "bad request"})
        try: pub = json.loads(self.rfile.read(n)).get("public_key", "")
        except ValueError: return self._send(400, {"error": "bad json"})
        if not KEY_RE.match(pub): return self._send(400, {"error": "invalid public key"})
        try: ip = register(uid, pub)
        except Exception as e: return self._send(503, {"error": str(e)})
        self._send(200, {"address": ip + "/32", "dns": DNS})

    def log_message(self, *a): pass   # do not log tokens or keys

if __name__ == "__main__":
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with lock: apply_all(load())
    ThreadingHTTPServer(("127.0.0.1", 8080), H).serve_forever()
