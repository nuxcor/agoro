#!/usr/bin/env python3
"""Find the streams that answer with the panel's black-screen filler.

A dead stream on this provider does not fail. The front answers the tune with
`302 -> /video/black.ts`, a real, decodable, silent black video — so the player
gets a valid stream, never errors, never falls through to the tile's backups,
and the viewer sits looking at a blank screen. No watchdog in the app can see
it either: there IS a picture, it is just black.

That makes it a build-time question, and a cheap one. The redirect is served
by the front before any stream opens, so checking a channel costs one HTTP
request, no bandwidth, and no slot on a max_connections=1 line.

Writes black_streams.json (stream id -> 1 black, 0 playable), which
build_manifest.py reads to sink a black source to the bottom of its tile's
ladder. It never drops anything: a club channel is legitimately black between
matches, and a stream missing from a measuring line's package is not a stream
missing from the viewer's.

    AGORO_HOST=... AGORO_USER=... AGORO_PASS=... python3 black_check.py
        [--all] [--ids 1,2,3] [--recheck] [--jobs N]

--all also checks the backups behind each tile, which is what makes the
demotion possible: a tile is only fixable if one of its own sources plays.
A measuring line whose front differs takes AGORO_STREAM_URL, exactly as
probe_tiers.py does.
"""
import json, os, sys, time
from concurrent.futures import ThreadPoolExecutor
from urllib import request, error

HOST, USER, PASS = (os.environ[k] for k in ('AGORO_HOST', 'AGORO_USER', 'AGORO_PASS'))
URL_TEMPLATE = os.environ.get(
    'AGORO_STREAM_URL', 'https://{host}/live/{user}/{pass}/{id}.ts')
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'black_streams.json')

check_all = '--all' in sys.argv
recheck = '--recheck' in sys.argv
jobs = int(sys.argv[sys.argv.index('--jobs') + 1]) if '--jobs' in sys.argv else 4
explicit = []
if '--ids' in sys.argv:
    explicit = [s.strip() for s in sys.argv[sys.argv.index('--ids') + 1].split(',') if s.strip()]

done = json.load(open(OUT)) if os.path.exists(OUT) else {}


def queue_from_manifest():
    m = json.load(open(os.path.join(HERE, 'manifest.json')))
    kept = json.load(open(os.path.join(HERE, 'kept_live.json')))
    # What a viewer browses, the way probe_tiers.py derives it: the PPV slots
    # are 6,300 of the survivors and sit on a hidden shelf.
    ids = [str(c['id']) for c in kept if c.get('section') != 'PPV']
    if check_all:
        tiles = list(m['collapse']['live'].values()) + list(m.get('metro_locals', {}).values())
        ids += [str(s) for t in tiles for s in t['sources']]
    return list(dict.fromkeys(ids))


class NoRedirect(request.HTTPRedirectHandler):
    """The redirect IS the answer; following it would open the stream."""
    def redirect_request(self, *a, **k):
        return None


opener = request.build_opener(NoRedirect)


def check(sid):
    url = URL_TEMPLATE.format(host=HOST, user=USER, id=sid, **{'pass': PASS})
    try:
        opener.open(url, timeout=20).close()
        return sid, 0          # answered without redirecting: a real stream
    except error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            return sid, 1 if 'black.ts' in (e.headers.get('Location') or '') else 0
        return sid, 0          # an error is not a black screen; leave it alone
    except Exception:
        return sid, 0


queue = [s for s in (explicit or queue_from_manifest()) if recheck or s not in done]
print(f"{len(queue)} streams to check ({len(done)} already recorded)", flush=True)
with ThreadPoolExecutor(max_workers=jobs) as pool:
    for i, (sid, black) in enumerate(pool.map(check, queue), 1):
        done[sid] = black
        if i % 50 == 0 or i == len(queue):
            json.dump(done, open(OUT, 'w'))
            print(f"{i}/{len(queue)}", flush=True)
json.dump(done, open(OUT, 'w'))
print(f"done: {len(done)} recorded, {sum(done.values())} black", flush=True)
