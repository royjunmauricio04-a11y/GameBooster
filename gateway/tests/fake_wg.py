#!/usr/bin/env python3
"""Stand-in for the `wg` binary. Peers persist in $FAKE_WG_STATE. Public keys starting with BAD are rejected."""
import json, os, sys
p = os.environ["FAKE_WG_STATE"]
try: st = json.load(open(p))
except Exception: st = {}
a = sys.argv[1:]
if a[:1] == ["show"]:
    if len(a) == 2: sys.exit(0)
    if a[2] == "peers": print("\n".join(st)); sys.exit(0)
    if a[2] == "latest-handshakes": print("\n".join(f"{k}\t{v.get('hs', 0)}" for k, v in st.items())); sys.exit(0)
if a[:1] == ["set"]:
    pub = a[3]
    if pub.startswith("BAD"): sys.stderr.write("rejected\n"); sys.exit(1)
    if a[4] == "remove": st.pop(pub, None)
    else: st[pub] = {"ips": a[5], "hs": st.get(pub, {}).get("hs", 0)}
    json.dump(st, open(p, "w")); sys.exit(0)
sys.exit(2)
