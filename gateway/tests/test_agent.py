"""Run:  python3 -m unittest gateway/tests/test_agent.py -v     (no root, no WireGuard, no network needed)"""
import base64, http.client, socket, importlib.util, json, os, shutil, stat, tempfile, threading, time, unittest, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
TMP = tempfile.mkdtemp()
WG_STATE = os.path.join(TMP, "wg.json")
os.makedirs(os.path.join(TMP, "bin")); shutil.copy(os.path.join(HERE, "fake_wg.py"), os.path.join(TMP, "bin", "wg"))
os.chmod(os.path.join(TMP, "bin", "wg"), 0o755)  # ZIP downloads may not preserve executable mode
os.environ.update(PATH=os.path.join(TMP, "bin") + os.pathsep + os.environ["PATH"], FAKE_WG_STATE=WG_STATE,
                  STATE_FILE=os.path.join(TMP, "state", "peers.json"), WG_SUBNET="10.8.0.0/24",
                  SUPABASE_ANON_KEY="anon", ALLOW_PRIVATE_PROBE="1", RATE_PROBE_PER_MIN="4", RATE_PER_IP_PER_MIN="5", RATE_PER_USER_PER_MIN="50", MAX_DEVICES_PER_USER="3")

SB_CALLS = []
REST_CALLS = []
ALLOWED_PROBE = {("game_profiles", "127.0.0.1", "80"): True}      # (table, host, port) the fake RLS lets everyone read
class FakeSupabase(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/rest/v1/"):
            import urllib.parse as up
            u = up.urlparse(self.path); table = u.path.rsplit("/", 1)[1]; q = up.parse_qs(u.query)
            REST_CALLS.append((table, self.headers.get("Authorization", "")[7:]))
            tok = self.headers.get("Authorization", "")[7:]
            if not tok.startswith("tok-"):
                body = b"{}"; self.send_response(401)
            else:
                host = q.get("probe_host", [""])[0].removeprefix("eq."); port = q.get("probe_port", [""])[0].removeprefix("eq.")
                mine = table == "user_games" and host == "mine.test" and port == "80" and tok == "tok-" + "00000000-0000-0000-0000-%012d" % 77
                hit = ALLOWED_PROBE.get((table, host, port)) or mine
                body = json.dumps([{"id": 1}] if hit else []).encode(); self.send_response(200)
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
        tok = self.headers.get("Authorization", "")[7:]
        SB_CALLS.append(tok)
        if tok.startswith("tok-"):
            body = json.dumps({"id": tok[4:]}).encode(); self.send_response(200)
        else:
            body = b"{}"; self.send_response(401)
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass

sb = ThreadingHTTPServer(("127.0.0.1", 0), FakeSupabase); threading.Thread(target=sb.serve_forever, daemon=True).start()
os.environ["SUPABASE_URL"] = "http://127.0.0.1:%d" % sb.server_port

spec = importlib.util.spec_from_file_location("agent", os.path.join(HERE, "..", "agent.py"))
agent = importlib.util.module_from_spec(spec); spec.loader.exec_module(agent)
srv = agent.make_server(("127.0.0.1", 0)); threading.Thread(target=srv.serve_forever, daemon=True).start()
PORT = srv.server_port

BADKEY = "BAD" + "A" * 40 + "="
def uid(n): return "00000000-0000-0000-0000-%012d" % n
def key(c, n=0): return (c * 42 + chr(65 + n % 26)) + "="
_xff = iter(range(1, 10**6))
def call(method, path, token=None, body=None, xff=None, raw_headers=None):
    h = {"X-Forwarded-For": xff or "10.99.%d.%d" % divmod(next(_xff), 250)}
    if token: h["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    if data: h["Content-Length"] = str(len(data))
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path), data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.load(r)
    except urllib.error.HTTPError as e: return e.code, json.load(e)
def wg_peers():
    try:
        with open(WG_STATE) as f: return json.load(f)
    except Exception: return {}
def state():
    with open(agent.STATE) as f: return json.load(f)
def put(path, obj):
    with open(path, "w") as f: json.dump(obj, f)
def reset():
    for p in (agent.STATE, WG_STATE):
        if os.path.exists(p): os.remove(p)

class T(unittest.TestCase):
    def setUp(self): reset(); SB_CALLS.clear(); REST_CALLS.clear(); agent._allow_cache.clear(); agent.ALLOW_PRIVATE_PROBE = True

    def test_register_basic_and_idempotent(self):
        c, r = call("POST", "/register", "tok-" + uid(1), {"public_key": key("A")})
        self.assertEqual((c, r["address"]), (200, "10.8.0.2/32"))
        c, r2 = call("POST", "/register", "tok-" + uid(1), {"public_key": key("A")})
        self.assertEqual(r2["address"], "10.8.0.2/32"); self.assertEqual(len(wg_peers()), 1)

    def test_concurrent_unique_ips(self):
        def one(i): return agent.register(uid(i), key("C", i) if i < 26 else key("D", i))
        with ThreadPoolExecutor(30) as ex: ips = list(ex.map(one, range(30)))
        self.assertEqual(len(set(ips)), 30); self.assertNotIn("10.8.0.1", ips)
        self.assertEqual(len(wg_peers()), 30); self.assertEqual(len(state()["devices"]), 30)

    def test_device_limit_and_multi_device(self):
        for i in range(3): self.assertEqual(call("POST", "/register", "tok-" + uid(1), {"public_key": key("M", i)})[0], 200)
        self.assertEqual(len(wg_peers()), 3)                                   # 2nd/3rd device did not evict the 1st
        self.assertEqual(call("POST", "/register", "tok-" + uid(1), {"public_key": key("M", 9)})[0], 409)
        self.assertEqual(call("POST", "/register", "tok-" + uid(2), {"public_key": key("N")})[0], 200)

    def test_cannot_claim_another_users_key(self):
        call("POST", "/register", "tok-" + uid(1), {"public_key": key("K")})
        c, r = call("POST", "/register", "tok-" + uid(2), {"public_key": key("K")})
        self.assertEqual(c, 403)
        self.assertEqual(state()["devices"][key("K")]["uid"], uid(1))

    def test_revoke_owner_only_and_ip_reuse(self):
        call("POST", "/register", "tok-" + uid(1), {"public_key": key("R")})
        self.assertEqual(call("POST", "/revoke", "tok-" + uid(2), {"public_key": key("R")})[0], 404)   # not yours
        self.assertIn(key("R"), wg_peers())
        self.assertEqual(call("POST", "/revoke", "tok-" + uid(1), {"public_key": key("R")})[0], 200)
        self.assertNotIn(key("R"), wg_peers()); self.assertNotIn(key("R"), state()["devices"])
        self.assertEqual(call("POST", "/register", "tok-" + uid(3), {"public_key": key("S")})[1]["address"], "10.8.0.2/32")

    def test_restart_recovery_and_reconcile(self):
        agent.register(uid(1), key("A")); agent.register(uid(2), key("B"))
        put(WG_STATE, {key("Z"): {"ips": "10.8.0.99/32", "hs": 0}})   # wg lost peers, has a rogue one
        self.assertEqual(agent.reconcile(), 2)
        self.assertEqual(set(wg_peers()), {key("A"), key("B")})

    def test_one_bad_peer_does_not_block_startup(self):
        agent.register(uid(1), key("A"))
        st = state(); st["devices"][BADKEY] = {"uid": uid(9), "ip": "10.8.0.200", "created": 1, "seen": int(time.time())}
        put(agent.STATE, st); os.remove(WG_STATE)
        self.assertEqual(agent.reconcile(), 2); self.assertIn(key("A"), wg_peers())

    def test_wg_failure_rolls_back_state(self):
        c, r = call("POST", "/register", "tok-" + uid(1), {"public_key": BADKEY})
        self.assertEqual(c, 503); self.assertEqual(state()["devices"], {})

    def test_auth_and_validation(self):
        self.assertEqual(call("POST", "/register", None, {"public_key": key("A")})[0], 401)
        self.assertEqual(call("POST", "/register", "garbage", {"public_key": key("A")})[0], 401)
        self.assertEqual(call("POST", "/register", "tok-" + uid(1), {"public_key": "short"})[0], 400)
        self.assertEqual(call("POST", "/register", "tok-" + uid(1), {"public_key": 5})[0], 400)
        self.assertEqual(call("POST", "/register", "tok-not-a-uuid", {"public_key": key("A")})[0], 503)  # supabase returned non-uuid id
        c = http.client.HTTPConnection("127.0.0.1", PORT); c.putrequest("POST", "/register"); c.putheader("Authorization", "Bearer tok-" + uid(1))
        c.putheader("X-Forwarded-For", "10.77.0.1"); c.putheader("Content-Length", "abc"); c.endheaders()
        self.assertEqual(c.getresponse().status, 400)
        c = http.client.HTTPConnection("127.0.0.1", PORT); c.putrequest("POST", "/register"); c.putheader("Authorization", "Bearer tok-" + uid(1))
        c.putheader("X-Forwarded-For", "10.77.0.2"); c.putheader("Content-Length", "99999"); c.endheaders()
        self.assertEqual(c.getresponse().status, 400)
        self.assertEqual(wg_peers(), {})

    def test_rate_limit_per_ip(self):
        codes = [call("POST", "/register", "garbage", {"public_key": key("A")}, xff="10.55.0.1")[0] for _ in range(8)]
        self.assertEqual(codes[:5], [401] * 5); self.assertEqual(codes[5:], [429] * 3)
        self.assertEqual(call("GET", "/health", xff="10.55.0.2")[0], 200)                 # other IPs unaffected

    def test_token_cache_and_expiry_cap(self):
        t = "tok-" + uid(100)                       # unique: the cache is process-global
        call("POST", "/register", t, {"public_key": key("A")}); call("POST", "/register", t, {"public_key": key("A")})
        self.assertEqual(SB_CALLS.count(t), 1)      # second request served from cache
        orig = agent._jwt_exp
        agent._jwt_exp = lambda tok: int(time.time()) - 5      # token already past its own exp
        try:
            agent.verify("tok-" + uid(101)); agent.verify("tok-" + uid(101))
        finally:
            agent._jwt_exp = orig
        self.assertEqual(SB_CALLS.count("tok-" + uid(101)), 2)  # never cached beyond exp

    def test_stale_expiry(self):
        agent.register(uid(1), key("A")); agent.register(uid(2), key("B"))
        st = state(); st["devices"][key("A")]["seen"] = int(time.time()) - 40 * 86400; put(agent.STATE, st)
        self.assertEqual(agent.expire_stale(), 1)
        self.assertEqual(set(state()["devices"]), {key("B")}); self.assertNotIn(key("A"), wg_peers())

    def test_cli_purge_and_state_perms(self):
        agent.register(uid(1), key("A")); agent.register(uid(1), key("B")); agent.register(uid(2), key("C"))
        agent.main(["x", "purge-user", uid(1)])
        self.assertEqual(set(state()["devices"]), {key("C")})
        self.assertEqual(stat.S_IMODE(os.stat(agent.STATE).st_mode), 0o600)

    def test_devices_listing_scoped_to_user(self):
        agent.register(uid(1), key("A")); agent.register(uid(2), key("B"))
        c, r = call("GET", "/devices", "tok-" + uid(1))
        self.assertEqual([d["public_key"] for d in r["devices"]], [key("A")])

    def test_v1_state_migration(self):
        os.makedirs(os.path.dirname(agent.STATE), exist_ok=True)
        put(agent.STATE, {uid(1): {"pub": key("A"), "ip": "10.8.0.2"}})
        self.assertEqual(agent.load()["devices"][key("A")]["uid"], uid(1))

class Listener:
    def __init__(self):
        self.sock = socket.socket(); self.sock.bind(("127.0.0.1", 0)); self.sock.listen(16)
        self.port = self.sock.getsockname()[1]; self.alive = True
        threading.Thread(target=self._loop, daemon=True).start()
    def _loop(self):
        while self.alive:
            try: c, _ = self.sock.accept(); c.close()
            except OSError: break
    def close(self): self.alive = False; self.sock.close()

class ProbeTests(unittest.TestCase):
    def setUp(self): reset(); SB_CALLS.clear(); REST_CALLS.clear(); agent._allow_cache.clear(); agent.ALLOW_PRIVATE_PROBE = True

    def test_probe_measures_real_connect_time(self):
        l = Listener()
        try:
            c, r = call("POST", "/probe", "tok-" + uid(50), {"host": "127.0.0.1", "port": l.port})
            self.assertEqual(c, 403)                                # port not in profiles -> not allowed
            ALLOWED = agent.host_allowed
            agent.host_allowed = lambda *a: True                    # isolate measurement from allow-list
            try: c, r = call("POST", "/probe", "tok-" + uid(50), {"host": "127.0.0.1", "port": l.port})
            finally: agent.host_allowed = ALLOWED
            self.assertEqual(c, 200); self.assertEqual(r["loss_pct"], 0.0)
            self.assertIsNotNone(r["avg_ms"]); self.assertLess(r["avg_ms"], 500)
        finally: l.close()

    def test_probe_closed_port_reports_full_loss(self):
        # Keep a local port bound but not listening. Closing an ephemeral listener first
        # lets an unrelated process claim the port before the probe runs.
        sock = socket.socket(); sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
        ALLOWED = agent.host_allowed; agent.host_allowed = lambda *a: True
        try: c, r = call("POST", "/probe", "tok-" + uid(51), {"host": "127.0.0.1", "port": port})
        finally: agent.host_allowed = ALLOWED; sock.close()
        self.assertEqual((c, r["loss_pct"], r["avg_ms"]), (200, 100.0, None))

    def test_allowlist_uses_users_own_token_and_tables(self):
        # 127.0.0.1 is in the (fake) catalog for everyone; mine.test only in user 77's user_games
        self.assertTrue(agent.host_allowed(uid(60), "tok-" + uid(60), "127.0.0.1", 80))
        self.assertFalse(agent.host_allowed(uid(60), "tok-" + uid(60), "mine.test", 80))
        self.assertTrue(agent.host_allowed(uid(77), "tok-" + uid(77), "mine.test", 80))
        self.assertTrue(all(t.startswith("tok-") for _, t in REST_CALLS))   # always the caller's JWT, never a service key
        n = len(REST_CALLS); agent.host_allowed(uid(77), "tok-" + uid(77), "mine.test", 80)
        self.assertEqual(len(REST_CALLS), n)                                # second check served from cache

    def test_unlisted_host_forbidden_and_validation(self):
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), {"host": "evil.example", "port": 22})[0], 403)
        self.assertEqual(call("POST", "/probe", None, {"host": "127.0.0.1", "port": 80})[0], 401)
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), {"host": "http://x", "port": 80})[0], 400)
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), {"host": "a b", "port": 80})[0], 400)
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), {"host": "127.0.0.1", "port": 0})[0], 400)
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), {"host": "127.0.0.1", "port": 70000})[0], 400)
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), {"host": "127.0.0.1", "port": True})[0], 400)
        self.assertEqual(call("POST", "/probe", "tok-" + uid(61), [1, 2])[0], 400)

    def test_private_targets_blocked_in_production_mode(self):
        agent.ALLOW_PRIVATE_PROBE = False
        ALLOWED = agent.host_allowed; agent.host_allowed = lambda *a: True       # even if a profile pointed at it
        try:
            self.assertEqual(call("POST", "/probe", "tok-" + uid(62), {"host": "127.0.0.1", "port": 80})[0], 403)
            self.assertEqual(call("POST", "/probe", "tok-" + uid(62), {"host": "169.254.169.254", "port": 80})[0], 403)
        finally: agent.host_allowed = ALLOWED

    def test_is_public_classifier(self):
        for bad in ("10.0.0.1", "192.168.1.1", "172.16.0.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fc00::1", "::ffff:10.0.0.1"):
            self.assertFalse(agent.is_public(bad), bad)
        for good in ("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"):
            self.assertTrue(agent.is_public(good), good)

    def test_probe_rate_limited_per_user(self):
        ALLOWED = agent.host_allowed; agent.host_allowed = lambda *a: True
        l = Listener()
        try:
            codes = [call("POST", "/probe", "tok-" + uid(70), {"host": "127.0.0.1", "port": l.port})[0] for _ in range(6)]
        finally: agent.host_allowed = ALLOWED; l.close()
        self.assertEqual(codes[:4], [200] * 4); self.assertEqual(codes[4:], [429, 429])

if __name__ == "__main__":
    unittest.main()
