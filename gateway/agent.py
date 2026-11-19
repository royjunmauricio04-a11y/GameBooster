#!/usr/bin/env python3
"""GameBooster gateway agent.

Serves on localhost only (put Caddy/TLS in front). Authorises device WireGuard public keys for
signed-in Supabase users, assigns tunnel addresses, supports revocation, and keeps `wg` and the
persisted state consistent across restarts. Holds no private keys or admin credentials.

Endpoints: POST /register /revoke /probe, GET /devices /health
Usage:  gamebooster-agent serve | list | revoke <public_key> | purge-user <user_uuid>
"""
import base64, fcntl, hashlib, ipaddress, json, logging, os, re, subprocess, sys, threading, time
import socket, urllib.error, urllib.parse, urllib.request, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SUPABASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
ANON_KEY = os.environ.get("SUPABASE_ANON_KEY", "")
IFACE = os.environ.get("WG_IFACE", "wg0")
SUBNET = ipaddress.ip_network(os.environ.get("WG_SUBNET", "10.8.0.0/24"))
DNS = os.environ.get("WG_DNS", "1.1.1.1")
STATE = os.environ.get("STATE_FILE", "/var/lib/gamebooster/peers.json")
LISTEN = (os.environ.get("LISTEN_HOST", "127.0.0.1"), int(os.environ.get("LISTEN_PORT", "8080")))
MAX_DEVICES = int(os.environ.get("MAX_DEVICES_PER_USER", "3"))
PEER_TTL = int(os.environ.get("PEER_TTL_DAYS", "30")) * 86400
TOKEN_CACHE_TTL = int(os.environ.get("TOKEN_CACHE_SECONDS", "60"))
IP_RATE = int(os.environ.get("RATE_PER_IP_PER_MIN", "20"))
USER_RATE = int(os.environ.get("RATE_PER_USER_PER_MIN", "10"))
MAX_CONCURRENT = int(os.environ.get("MAX_CONCURRENT", "32"))
KEY_RE = re.compile(r"^[A-Za-z0-9+/]{43}=$")
HOST_RE = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
PROBE_RATE = int(os.environ.get("RATE_PROBE_PER_MIN", "12"))
ALLOW_PRIVATE_PROBE = os.environ.get("ALLOW_PRIVATE_PROBE") == "1"   # tests only; never set in production

log = logging.getLogger("gbagent")


class ApiError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg); self.code, self.msg = code, msg


def short(pub):  # never log full keys or tokens
    return pub[:6] + "…"


# ---------------- state (JSON file, atomic writes, cross-process lock) ----------------
_tlock = threading.RLock()


class state_lock:
    """Thread lock + flock so the CLI and the daemon never interleave. Do not nest."""
    def __enter__(self):
        _tlock.acquire()
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        self.fd = os.open(STATE + ".lock", os.O_CREAT | os.O_RDWR, 0o600)
        fcntl.flock(self.fd, fcntl.LOCK_EX)

    def __exit__(self, *a):
        fcntl.flock(self.fd, fcntl.LOCK_UN); os.close(self.fd); _tlock.release()


def load():
    try:
        with open(STATE) as f:
            raw = json.load(f)
    except FileNotFoundError:
        return {"version": 2, "devices": {}}
    except ValueError:
        sys.exit("state file is corrupt: refusing to continue (restore from backup or inspect %s)" % STATE)
    if raw.get("version") == 2:
        return raw
    now = int(time.time())   # migrate v1: {uid: {pub, ip}}
    return {"version": 2, "devices": {v["pub"]: {"uid": k, "ip": v["ip"], "created": now, "seen": now}
                                      for k, v in raw.items() if isinstance(v, dict) and "pub" in v}}


def save(st):
    tmp = STATE + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(st, f); f.flush(); os.fsync(f.fileno())
    try:
        s = os.stat(STATE)
        if os.geteuid() == 0:      # CLI run as root must not lock the daemon user out
            os.chown(tmp, s.st_uid, s.st_gid)
    except FileNotFoundError:
        pass
    os.replace(tmp, STATE)


# ---------------- WireGuard ----------------
def wg(*args, check=True):
    return subprocess.run(["wg", *args], check=check, capture_output=True, text=True, timeout=10)


def wait_for_iface(timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if wg("show", IFACE, check=False).returncode == 0:
            return True
        time.sleep(2)
    return False


def reconcile():
    """State file is the source of truth: (re)apply every device, drop peers it doesn't know."""
    with state_lock():
        st = load()
        for pub, d in st["devices"].items():
            try:
                wg("set", IFACE, "peer", pub, "allowed-ips", d["ip"] + "/32")
            except Exception:
                log.error("reconcile: could not apply peer %s (continuing)", short(pub))
        live = set(wg("show", IFACE, "peers", check=False).stdout.split())
        for pub in live - set(st["devices"]):
            wg("set", IFACE, "peer", pub, "remove", check=False)
            log.warning("reconcile: removed unknown peer %s", short(pub))
        return len(st["devices"])


# ---------------- device operations ----------------
def _alloc(devs):
    used = {d["ip"] for d in devs.values()}
    for i, h in enumerate(SUBNET.hosts()):
        if i >= 1 and str(h) not in used:        # host #0 is the server's own address
            return str(h)
    raise ApiError(503, "address pool exhausted")


def register(uid, pub):
    with state_lock():
        st = load(); devs = st["devices"]
        before = json.dumps(st)
        d = devs.get(pub)
        if d and d["uid"] != uid:
            raise ApiError(403, "public key belongs to another account")
        if not d:
            if sum(1 for x in devs.values() if x["uid"] == uid) >= MAX_DEVICES:
                raise ApiError(409, "device limit (%d) reached; revoke a device first" % MAX_DEVICES)
            d = {"uid": uid, "ip": _alloc(devs), "created": int(time.time())}
        d["seen"] = int(time.time())
        devs[pub] = d
        save(st)
        try:
            wg("set", IFACE, "peer", pub, "allowed-ips", d["ip"] + "/32")
        except Exception:
            save(json.loads(before))             # roll back so state never lists a peer wg refused
            log.error("register: wg set failed for %s", short(pub))
            raise ApiError(503, "gateway could not apply peer")
        log.info("register uid=%s dev=%s ip=%s", uid[:8], short(pub), d["ip"])
        return d["ip"]


def revoke(pub, uid=None):
    """uid=None means admin (CLI). Returns True if a device was removed."""
    with state_lock():
        st = load(); d = st["devices"].get(pub)
        if not d or (uid is not None and d["uid"] != uid):
            return False
        del st["devices"][pub]
        save(st)
        wg("set", IFACE, "peer", pub, "remove", check=False)
        log.info("revoke dev=%s uid=%s", short(pub), d["uid"][:8])
        return True


def list_devices(uid):
    return [{"public_key": p, "created": d["created"], "seen": d["seen"]}
            for p, d in load()["devices"].items() if d["uid"] == uid]


def expire_stale():
    """Drop devices neither registered nor handshaking within PEER_TTL."""
    with state_lock():
        st = load(); now = int(time.time())
        hs = {}
        for line in wg("show", IFACE, "latest-handshakes", check=False).stdout.splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                hs[parts[0]] = int(parts[1])
        gone = [p for p, d in st["devices"].items() if now - max(d["seen"], hs.get(p, 0)) > PEER_TTL]
        for p in gone:
            del st["devices"][p]
        if gone:
            save(st)
            for p in gone:
                wg("set", IFACE, "peer", p, "remove", check=False)
                log.info("expired stale peer %s", short(p))
        return len(gone)


# ---------------- auth, caching, rate limiting ----------------
_cache, _cache_lock = {}, threading.Lock()


def _jwt_exp(token):
    try:
        payload = token.split(".")[1]
        return int(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))).get("exp", 0))
    except Exception:
        return 0


def verify(token):
    """Signature + expiry are validated by Supabase itself (GET /auth/v1/user). Positive results are cached
    briefly, never beyond the token's own `exp`, so a revoked session may stay valid for up to TOKEN_CACHE_TTL."""
    h = hashlib.sha256(token.encode()).hexdigest()
    now = time.time()
    with _cache_lock:
        hit = _cache.get(h)
        if hit and hit[1] > now:
            return hit[0]
    req = urllib.request.Request(SUPABASE_URL + "/auth/v1/user",
                                 headers={"apikey": ANON_KEY, "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            uid = str(uuid.UUID(str(json.load(r).get("id"))))
    except urllib.error.HTTPError as e:
        raise ApiError(401 if e.code in (400, 401, 403) else 503, "invalid or expired token" if e.code in (400, 401, 403) else "auth service error")
    except (urllib.error.URLError, ValueError, TimeoutError):
        raise ApiError(503, "auth service unavailable")
    exp = _jwt_exp(token)
    expiry = min(now + TOKEN_CACHE_TTL, exp) if exp else now + TOKEN_CACHE_TTL
    with _cache_lock:
        if len(_cache) > 2000:
            for k in [k for k, v in _cache.items() if v[1] <= now]:
                del _cache[k]
            if len(_cache) > 2000:
                _cache.clear()
        if expiry > now:
            _cache[h] = (uid, expiry)
    return uid


class RateLimiter:
    def __init__(self):
        self.hits, self.lock = {}, threading.Lock()

    def allow(self, key, limit, window=60):
        now = time.time()
        with self.lock:
            q = [t for t in self.hits.get(key, []) if now - t < window]
            ok = len(q) < limit
            if ok:
                q.append(now)
            self.hits[key] = q
            if len(self.hits) > 10000:
                self.hits = {k: v for k, v in self.hits.items() if v and now - v[-1] < window}
            return ok


limiter = RateLimiter()


# ---------------- gateway -> game probe (SSRF-hardened) ----------------
_allow_cache = {}


def is_public(addr):
    ip = ipaddress.ip_address(addr)
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_global            # False for private, loopback, link-local (incl. cloud metadata), shared, reserved


def host_allowed(uid, token, host, port):
    """The target must be a probe_host/probe_port in game_profiles or in THIS user's user_games (RLS applies,
    because the query runs with the user's own JWT). Prevents using the gateway as an open port scanner."""
    key = (uid, host, port)
    now = time.time()
    with _cache_lock:
        if _allow_cache.get(key, 0) > now:
            return True
    q = urllib.parse.quote(host, safe="")
    for table in ("game_profiles", "user_games"):
        url = "%s/rest/v1/%s?select=id&probe_host=eq.%s&probe_port=eq.%d&limit=1" % (SUPABASE_URL, table, q, port)
        req = urllib.request.Request(url, headers={"apikey": ANON_KEY, "Authorization": "Bearer " + token})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                if json.load(r):
                    with _cache_lock:
                        if len(_allow_cache) > 2000:
                            _allow_cache.clear()
                        _allow_cache[key] = now + 300
                    return True
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise ApiError(401, "invalid or expired token")
            if e.code != 404:                      # 404 = optional table missing on an older schema
                raise ApiError(503, "auth service error")
        except (urllib.error.URLError, ValueError, TimeoutError):
            raise ApiError(503, "auth service unavailable")
    return False


def probe(host, port, samples=5, timeout=1.5):
    """TCP connect time from the gateway to host:port. Every resolved address must be public."""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        raise ApiError(400, "host did not resolve")
    if not infos:
        raise ApiError(400, "host did not resolve")
    if not ALLOW_PRIVATE_PROBE and not all(is_public(i[4][0]) for i in infos):
        raise ApiError(403, "target not allowed")
    family, _, _, _, sockaddr = infos[0]          # connect to the address we validated (no second DNS lookup)
    rtts = []
    for _ in range(samples):
        t0 = time.perf_counter()
        try:
            with socket.socket(family, socket.SOCK_STREAM) as sk:
                sk.settimeout(timeout)
                sk.connect(sockaddr)
            rtts.append((time.perf_counter() - t0) * 1000)
        except OSError:
            pass
        time.sleep(0.1)
    loss = (samples - len(rtts)) * 100.0 / samples
    if not rtts:
        return {"avg_ms": None, "jitter_ms": None, "loss_pct": loss}
    jitter = sum(abs(b - a) for a, b in zip(rtts, rtts[1:])) / (len(rtts) - 1) if len(rtts) > 1 else 0.0
    return {"avg_ms": round(sum(rtts) / len(rtts), 1), "jitter_ms": round(jitter, 1), "loss_pct": loss}


# ---------------- HTTP ----------------
class Handler(BaseHTTPRequestHandler):
    timeout = 10                                   # slow-client protection

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json"); self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def _client_ip(self):
        xff = self.headers.get("X-Forwarded-For", "")
        return xff.split(",")[-1].strip() if xff else self.client_address[0]   # rightmost = added by our proxy

    def _auth(self):
        if not limiter.allow("ip:" + self._client_ip(), IP_RATE):
            raise ApiError(429, "too many requests")
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or len(auth) > 4096:
            raise ApiError(401, "missing token")
        self.token = auth[7:]
        uid = verify(self.token)
        if not limiter.allow("uid:" + uid, USER_RATE):
            raise ApiError(429, "too many requests")
        return uid

    def _json_body(self):
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise ApiError(400, "bad request")
        if n <= 0 or n > 1024:
            raise ApiError(400, "bad request")
        try:
            body = json.loads(self.rfile.read(n))
        except ValueError:
            raise ApiError(400, "bad json")
        if not isinstance(body, dict):
            raise ApiError(400, "bad json")
        return body

    def _pubkey(self):
        pub = self._json_body().get("public_key", "")
        if not isinstance(pub, str) or not KEY_RE.match(pub):
            raise ApiError(400, "invalid public key")
        return pub

    def _host_port(self):
        b = self._json_body()
        host, port = b.get("host", ""), b.get("port", 0)
        if not isinstance(host, str) or not HOST_RE.match(host):
            raise ApiError(400, "invalid host")
        if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
            raise ApiError(400, "invalid port")
        return host, port

    def _run(self, fn):
        try:
            self._send(*fn())
        except ApiError as e:
            self._send(e.code, {"error": e.msg})
        except Exception:
            log.exception("unhandled error")
            self._send(500, {"error": "internal error"})

    def do_GET(self):
        if self.path == "/health":
            def h():
                if not limiter.allow("ip:" + self._client_ip(), IP_RATE * 3):
                    raise ApiError(429, "too many requests")
                return 200, {"ok": True}
            return self._run(h)
        if self.path == "/devices":
            return self._run(lambda: (200, {"devices": list_devices(self._auth())}))
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/register":
            def r():
                uid = self._auth(); pub = self._pubkey()
                return 200, {"address": register(uid, pub) + "/32", "dns": DNS}
            return self._run(r)
        if self.path == "/probe":
            def pr():
                uid = self._auth(); host, port = self._host_port()
                if not limiter.allow("probe:" + uid, PROBE_RATE):
                    raise ApiError(429, "too many requests")
                if not host_allowed(uid, self.token, host, port):
                    raise ApiError(403, "host is not in your game profiles")
                return 200, probe(host, port)
            return self._run(pr)
        if self.path == "/revoke":
            def v():
                uid = self._auth(); pub = self._pubkey()
                if not revoke(pub, uid):
                    raise ApiError(404, "no such device")
                return 200, {"revoked": True}
            return self._run(v)
        self._send(404, {"error": "not found"})

    def log_message(self, *a):
        pass                                        # default access log would be noisy; we log events ourselves


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.sem = threading.BoundedSemaphore(MAX_CONCURRENT)

    def process_request(self, request, client_address):
        if not self.sem.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.sem.release()


def make_server(addr=LISTEN):
    return Server(addr, Handler)


def _maintenance():
    while True:
        time.sleep(3600)
        try:
            expire_stale()
        except Exception:
            log.exception("maintenance failed")


def serve():
    if "YOUR-" in SUPABASE_URL or not ANON_KEY or "YOUR-" in ANON_KEY:
        sys.exit("Edit /etc/gamebooster.env: set SUPABASE_URL and SUPABASE_ANON_KEY")
    if not SUPABASE_URL.startswith("https://") and os.environ.get("ALLOW_INSECURE_SUPABASE") != "1":
        sys.exit("SUPABASE_URL must be https://")
    if not wait_for_iface():
        sys.exit("WireGuard interface %s is not up" % IFACE)
    n = reconcile()
    log.info("started; %d device(s) reconciled", n)
    threading.Thread(target=_maintenance, daemon=True).start()
    make_server().serve_forever()


def main(argv):
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    cmd = argv[1] if len(argv) > 1 else "serve"
    if cmd == "serve":
        serve()
    elif cmd == "list":
        for p, d in load()["devices"].items():
            print(d["uid"], d["ip"], p, time.strftime("%Y-%m-%d", time.gmtime(d["seen"])))
    elif cmd == "revoke" and len(argv) == 3:
        print("revoked" if revoke(argv[2]) else "not found")
    elif cmd == "purge-user" and len(argv) == 3:
        with state_lock():
            pubs = [p for p, d in load()["devices"].items() if d["uid"] == argv[2]]
        print("revoked %d device(s)" % sum(revoke(p) for p in pubs))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
