#!/usr/bin/env python3
"""Measure the PPV slots a fixture row offers — which are events, not streams.

`probe_tiers.py` and `black_check.py` both skip the PPV section on purpose:
they measure the LINE-UP, the channels a viewer browses, and a PPV slot is not
on it. The result is that the one shelf whose sources cannot be judged by name
is the only one with nothing measured. On 2026-09-09 the manifest carried
picture data for 6 of 7,791 PPV slots — 0.1% — so `SportsParser.byFeed` found
`measuredHeight`, `measuredFps` and `blackFiller` all zero for essentially
every fixture and fell through to `tierRank`/`sourceRank`, which is the pack's
own name for itself. That is the ranking this whole area keeps proving wrong:
"8K EXCLUSIVE" measured 1080p30, and the slot advertising it was the WORST of
the six feeds carrying that match.

**A PPV slot's measurement describes an event, not a stream, and this is the
whole reason for a separate tool.** Stream 1025280 was "UEFA | 01 - Freiburg vs
Motherwell" on 8 September and "UEFA | 01- Barcelona vs Feyenoord" on the 9th;
seven of that pack's 37 pipes were re-pointed between one afternoon and the
next. So three of `probe_tiers.py`'s rules are actively wrong here:

  * It keeps the LOWEST sample ever seen. For a channel that is right — a feed
    that ever drops to 576 is not an HD source. For a pipe that carries a
    different match every night it means last night's worse match permanently
    outranks tonight's better one. Here the LATEST sample wins, and a sample
    taken against a different name is not folded in at all, it is replaced.
  * It records no name, so nothing downstream can tell whether the reading is
    still about the same content. Every record here carries the slot name the
    panel gave at the moment of measuring, and `build_manifest.py` emits it
    only while that name still matches.
  * Black is a permanent 0/1 in `black_streams.json`. A PPV pipe is
    LEGITIMATELY black between fixtures — sweeping the shelf at 04:00 would
    mark every slot in it black and sink every feed the app can offer. So
    black is recorded here with its name and clock like everything else, and
    it means "black while carrying this", never "black".

Two stages, because they cost wildly different amounts. The black check is the
302 the front serves before any stream opens: one HTTP request, no bandwidth,
no slot on a max_connections=1 line, so it can sweep the whole shelf. Only the
slots that redirect to a real upstream are worth ffprobe, which does open the
stream and must be serial. On a Champions League night that is a few hundred
of the 7,791 rather than all of them.

Run it WHEN THE FIXTURES ARE ON. A slot measured at 04:00 says nothing about
the match it carries at 20:00, and this tool cannot tell the difference — it
records what it saw and when, and leaves that judgement to the reader.

    AGORO_HOST=... AGORO_USER=... AGORO_PASS=... python3 probe_slots.py
        [--categories 1497,1513] [--limit N] [--jobs N] [--black-only]
        [--fixtures-only] [--max-probe N]

--categories restricts the sweep to named live category ids; without it every
category the manifest files under the PPV section is swept.
--fixtures-only ffprobes just the slots whose name reads as "A vs B", which is
what a fixture row can ever offer.
--black-only skips ffprobe entirely — the cheap half, safe to run any time.

Credentials come from the environment, never a file: this repository is
public. AGORO_STREAM_URL overrides the stream url shape for a line whose front
differs (this provider answers on http; the https front closes the connection).
"""
import json, os, re, subprocess, sys, time
from concurrent.futures import ThreadPoolExecutor
from urllib import request, error, parse

HOST, USER, PASS = (os.environ[k] for k in ('AGORO_HOST', 'AGORO_USER', 'AGORO_PASS'))
URL_TEMPLATE = os.environ.get(
    'AGORO_STREAM_URL', 'http://{host}/live/{user}/{pass}/{id}.ts')
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'probed_slots.json')

jobs = int(sys.argv[sys.argv.index('--jobs') + 1]) if '--jobs' in sys.argv else 4
limit = int(sys.argv[sys.argv.index('--limit') + 1]) if '--limit' in sys.argv else 0
max_probe = int(sys.argv[sys.argv.index('--max-probe') + 1]) if '--max-probe' in sys.argv else 0
black_only = '--black-only' in sys.argv
fixtures_only = '--fixtures-only' in sys.argv
only_cats = []
if '--categories' in sys.argv:
    only_cats = [c.strip() for c in sys.argv[sys.argv.index('--categories') + 1].split(',') if c.strip()]

# "A vs B" in the shapes the packs write it. Deliberately loose — this only
# decides what is worth spending a connection on, and the real reading is
# SportsParser's job in the app. A slot this misses is measured anyway when
# --fixtures-only is off.
FIXTURE = re.compile(r'\b(?:vs?\.?|v)\b', re.I)


def api(action=None, **params):
    q = {'username': USER, 'password': PASS}
    if action:
        q['action'] = action
    q.update(params)
    url = f"http://{HOST}/player_api.php?" + parse.urlencode(q)
    with request.urlopen(url, timeout=60) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def ppv_categories():
    """The PPV categories, from the manifest's own section map.

    Read from the manifest rather than matched on the category name so this
    agrees with what the app shelves as PPV by construction, the way
    black_check.py derives its queue from kept_live.json.
    """
    m = json.load(open(os.path.join(HERE, 'manifest.json')))
    cats = m['categories']['live']
    return [cid for cid, c in cats.items() if (c or {}).get('section') == 'PPV']


class NoRedirect(request.HTTPRedirectHandler):
    """The redirect IS the answer; following it would open the stream."""
    def redirect_request(self, *a, **k):
        return None


opener = request.build_opener(NoRedirect)


def black_check(sid):
    """Black, playable, or unknown — from the front's redirect alone.

    Returns (sid, black) where black is True, False, or None for "the front
    would not say". None matters: an id that errors is not an id that is
    black, and recording a failed request as a black screen would sink a
    working feed for a week.
    """
    url = URL_TEMPLATE.format(host=HOST, user=USER, id=sid, **{'pass': PASS})
    try:
        opener.open(url, timeout=20).close()
        return sid, False              # answered without redirecting
    except error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            return sid, 'black.ts' in (e.headers.get('Location') or '')
        return sid, None
    except Exception:
        return sid, None


def _fps(value):
    """ffprobe writes a rational; 0 denominator means it would not say."""
    try:
        num, den = str(value).split('/')
        return round(int(num) / int(den)) if int(den) else 0
    except Exception:
        return 0


def probe(sid):
    """Height, width, codec and frame rate, the same read probe_tiers.py takes.

    Frame rate is the field that matters most here and the one a name never
    carries: six slots on two Champions League matches came back 1080p on five
    of them, at 50, 30 and 25 fps. Codec because the user's box cannot decode
    HEVC at all, so an HEVC feed is not a lower rung on the ladder — it is
    unplayable, and must never outrank an h264 one on height alone.
    """
    url = URL_TEMPLATE.format(host=HOST, user=USER, id=sid, **{'pass': PASS})
    try:
        r = subprocess.run(
            ['ffprobe', '-v', 'error', '-select_streams', 'v:0',
             '-show_entries', 'stream=codec_name,width,height,avg_frame_rate',
             '-of', 'json',
             '-probesize', '3000000', '-analyzeduration', '3000000',
             '-rw_timeout', '8000000', url],
            capture_output=True, text=True, timeout=25)
        v = (json.loads(r.stdout or '{}').get('streams') or [{}])[0]
        return {
            'height': int(v.get('height') or 0),
            'width': int(v.get('width') or 0),
            'codec': v.get('codec_name') or '',
            'fps': _fps(v.get('avg_frame_rate')),
        }
    except Exception:
        return {'height': 0, 'width': 0, 'codec': '', 'fps': 0}


# ---------------------------------------------------------------- the sweep

cats = only_cats or ppv_categories()
print(f"{len(cats)} PPV categories", flush=True)

names = {}          # stream id -> the name the panel gives it RIGHT NOW
for cid in cats:
    try:
        for s in api('get_live_streams', category_id=cid) or []:
            sid, nm = s.get('stream_id'), s.get('name')
            if sid is not None and nm:
                names[str(sid)] = nm
    except Exception as e:
        # A category that will not answer is not an empty category. Skipped
        # rather than recorded, so nothing downstream reads it as measured.
        print(f"  category {cid}: {type(e).__name__}, skipped", flush=True)

queue = list(names)
if limit:
    queue = queue[:limit]
print(f"{len(queue)} slots named by the panel", flush=True)

done = json.load(open(OUT)) if os.path.exists(OUT) else {}
now = int(time.time())

def record(sid, clock, **fields):
    """Write a slot's reading, bound to the name it was taken against.

    Replaces rather than merges whenever the name has changed: the previous
    record described a different match on the same pipe, and folding the two
    together is the mistake this file exists to avoid.

    [clock] is which measurement is being timed — the two halves of this tool
    age separately. A --black-only pass hours after a full one must not stamp
    its own clock on the picture it did not take, or a stale height would look
    freshly measured to whoever is deciding whether to trust it.
    """
    was = done.get(sid) or {}
    if was.get('name') != names[sid]:
        was = {}
    done[sid] = {**was, 'name': names[sid], clock: now, **fields}


blacks = {}


def _flush():
    """Write what is known so far.

    The sweep is thousands of requests and the slow ones are slow because they
    time out, so a full pass over this shelf is long enough that being killed
    part-way through it is the normal case, not the exceptional one. Nothing
    here is worth re-measuring just because the run did not reach the end.
    """
    json.dump(done, open(OUT, 'w'), indent=1)


with ThreadPoolExecutor(max_workers=jobs) as pool:
    for i, (sid, black) in enumerate(pool.map(black_check, queue), 1):
        blacks[sid] = black
        if black is not None:
            record(sid, 'black_at', black=black)
        if i % 200 == 0 or i == len(queue):
            _flush()
            print(f"  black check {i}/{len(queue)}", flush=True)

n_black = sum(1 for v in blacks.values() if v is True)
n_live = sum(1 for v in blacks.values() if v is False)
n_unk = sum(1 for v in blacks.values() if v is None)
print(f"black {n_black} | carrying something {n_live} | no answer {n_unk}", flush=True)


# Only what is carrying something, and only fixtures if asked: ffprobe opens
# the stream and this line meters concurrent connections, so the queue has to
# earn every entry.
probe_queue = [s for s in queue if blacks.get(s) is False]
if fixtures_only:
    probe_queue = [s for s in probe_queue if FIXTURE.search(names[s])]
if max_probe:
    probe_queue = probe_queue[:max_probe]

if black_only:
    json.dump(done, open(OUT, 'w'), indent=1)
    print(f"black-only: {len(done)} slots recorded", flush=True)
    sys.exit(0)

print(f"{len(probe_queue)} slots to ffprobe (serial)", flush=True)
for i, sid in enumerate(probe_queue, 1):
    got = probe(sid)
    if got['height']:
        record(sid, 'at', **got, black=False)
    else:
        # Opened, would not decode. Recorded as such rather than left blank:
        # "we asked and got nothing" is a different fact from "never asked",
        # and only one of them is worth asking again tonight.
        record(sid, 'at', undecodable=True, black=False)
    if i % 10 == 0 or i == len(probe_queue):
        json.dump(done, open(OUT, 'w'), indent=1)
        print(f"  {i}/{len(probe_queue)}  {sid} -> "
              f"{got['codec'] or 'none'} {got['width']}x{got['height']} @{got['fps']}"
              f"  {names[sid][:48]}", flush=True)
    time.sleep(0.2)

json.dump(done, open(OUT, 'w'), indent=1)
measured = sum(1 for v in done.values() if v.get('height'))
print(f"done: {len(done)} slots recorded, {measured} with a picture", flush=True)
