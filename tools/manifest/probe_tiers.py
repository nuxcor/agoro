"""Measure what the channels a viewer can actually reach decode at.

The build ranks a tile's sources by the tier token in the stream name, and
providers lie — "FHD" on a 720p feed is routine. This probes the real video
height with ffprobe and writes probed_tiers.json (stream_id -> height), which
build_manifest.py prefers over the advertised token wherever both exist; a
height of 0 records "couldn't decode" so the id isn't probed again and the
advertised token stands.

It also writes probed_media.json (stream_id -> codec, width, height, fps,
at), which is where the answer usually is. Six PPV slots carrying two live
Champions League matches on 2026-09-08 came back 1080p on five of them —
height separated nothing, and frame rate separated everything: 50, 30 and 25
on the same match, with the slot advertising "8K EXCLUSIVE" being the 30.

Resumable: already-probed ids are skipped. Iterate probe -> rebuild until the
set of primaries stops changing — a demoted liar promotes a source that may
itself be unprobed.

Credentials come from the environment (AGORO_HOST/USER/PASS), never a file —
this repository is public. One connection at a time, deliberately: Xtream
lines meter concurrent connections, and a parallel probe looks like account
sharing.

    AGORO_HOST=... AGORO_USER=... AGORO_PASS=... \
        python3 probe_tiers.py manifest-new.json [--all] [--limit N]
        python3 probe_tiers.py --ids 1577208,1562727

--all also probes a tile's non-primary sources — the backups behind whatever is
currently on top — rather than only the line-up as it stands.

--ids measures a named list instead of the line-up, and is the only way to
reach a channel the build DROPS. That is the question it answers: "is this
one worth carrying?", which cannot be asked of a queue derived from what is
already carried. It skips the manifest and kept_live.json entirely, so it
works before a build and against ids no shelf has ever held. Results land in
the same probed_tiers.json, so a keep decided this way arrives already
measured.
"""
import json, os, subprocess, sys, time

# Read before the manifest is opened: --ids answers a question about channels
# that are not in it, so it must not require one to exist.
explicit_ids = []
if '--ids' in sys.argv:
    explicit_ids = [s.strip() for s in sys.argv[sys.argv.index('--ids') + 1].split(',')
                    if s.strip()]

_positional = [a for a in sys.argv[1:] if not a.startswith('--')]
# The value of --ids is a positional-looking argument; it is not the manifest.
if explicit_ids and _positional and _positional[0] == sys.argv[sys.argv.index('--ids') + 1]:
    _positional = _positional[1:]
manifest_path = next(iter(_positional), 'manifest-new.json')
probe_all = '--all' in sys.argv
# Re-measure ids already recorded. Resolution varies over the day, and the
# ranking keeps the lowest sample seen, so a second pass can only improve it.
reprobe = '--reprobe' in sys.argv
limit = int(sys.argv[sys.argv.index('--limit') + 1]) if '--limit' in sys.argv else 0

HOST, USER, PASS = (os.environ[k] for k in ('AGORO_HOST', 'AGORO_USER', 'AGORO_PASS'))
OUT = 'probed_tiers.json'
done = json.load(open(OUT)) if os.path.exists(OUT) else {}

if explicit_ids:
    # --reprobe is implied: a candidate is being asked about NOW, and a stale
    # recording of it is the thing the question is trying to get past.
    queue = list(dict.fromkeys(explicit_ids))
    if limit:
        queue = queue[:limit]
    print(f"{len(queue)} named streams to probe", flush=True)
else:
    queue = None      # built from the manifest below

m = json.load(open(manifest_path)) if queue is None else None
tiles = (list(m['collapse']['live'].values()) + list(m.get('metro_locals', {}).values())
         if queue is None else [])

# The queue is every channel that survives the manifest — tile primaries AND
# the loose channels that belong to no tile.
#
# It used to be tile primaries only, and that quietly excluded most of the
# catalogue: a region could report "fully measured" while none of its loose
# channels had ever been opened. It hid 97 of DSTV's 146 and 83 of Canada's
# 107, each found by hand long after the region was called done. Deriving the
# queue the way the app derives its own line-up is the only version of this
# that cannot drift out of agreement with what a viewer sees.
here = os.path.dirname(os.path.abspath(__file__))
kept_path = os.path.join(here, 'kept_live.json')
if queue is None and not os.path.exists(kept_path):
    sys.exit(f"{kept_path} not found — run build_manifest.py first; it writes the line-up")
kept = json.load(open(kept_path)) if queue is None else []
# PPV event slots are 6,286 of the 6,941 survivors and sit on a shelf hidden
# by default. Probing them in queue order buried the real channels: a sweep
# reported ~6,300 unmeasured when only 154 browsable channels had never been
# opened. --ppv includes them; by default the queue is what a viewer browses.
skip_ppv = '--ppv' not in sys.argv
candidates = [str(c['id']) for c in kept
              if not (skip_ppv and c.get('section') == 'PPV')]
# --all reaches past the line-up into the backups a tile is holding in reserve.
if probe_all and queue is None:
    candidates += [str(s) for t in tiles for s in t['sources']]

if queue is None:
    queue, seen = [], set()
    for s in candidates:
        if (reprobe or s not in done) and s not in seen:
            seen.add(s); queue.append(s)
    if limit: queue = queue[:limit]
    print(f"{len(queue)} streams to probe ({len(done)} already recorded)", flush=True)


# How a stream is addressed on this line.
#
# The user's panel serves https and a /live/ segment; a measuring line on the
# same upstream catalogue need not. World 8K answers plain
# http://host/USER/PASS/<id>.ts and nothing else, and the hardcoded shape
# meant every probe against it timed out and recorded a 0 — a decode failure
# is indistinguishable from "the URL was wrong", so the file filled with
# zeros that then told the build not to probe those ids again.
#
# {host} {user} {pass} {id} are substituted. Set AGORO_STREAM_URL to point
# this at a line whose front differs.
URL_TEMPLATE = os.environ.get(
    'AGORO_STREAM_URL', 'https://{host}/live/{user}/{pass}/{id}.ts')


def _fps(value):
    """ffprobe writes a rational; 0 denominator means it would not say."""
    try:
        num, den = str(value).split('/')
        return round(int(num) / int(den)) if int(den) else 0
    except Exception:
        return 0


def probe(sid):
    """Height, and the two fields height alone cannot answer.

    Height was the whole record until 2026-09-08, when six PPV slots
    carrying two live Champions League matches came back 1080p on five of
    them — so height separated nothing, and the field that DID separate them
    was frame rate: 50, 30 and 25 on the same match. For football that is the
    most visible difference on the screen, and it was invisible here.

    Codec for the other half of it. The sixth slot was HEVC, which the user's
    box cannot decode at all (see the box-cannot-decode-hevc note), so it is
    not a lower rung on the same ladder — it is unplayable, and a ranking
    that only knows heights would have put its 720p above a 1080p rival's
    lower sample.
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


# The richer record, beside probed_tiers.json rather than inside it:
# build_manifest.py reads that file as {id: height} and a dict there would
# read as a truthy height. Nothing downstream has to change to keep working.
MEDIA_OUT = 'probed_media.json'
media = json.load(open(MEDIA_OUT)) if os.path.exists(MEDIA_OUT) else {}


for i, sid in enumerate(queue, 1):
    got = probe(sid)
    height = got['height']
    # The LOWEST sample wins, never the latest. A live feed's resolution is
    # not a constant: BBC News measured 1080 one morning and 576 that
    # afternoon, and ranking on the optimistic sample put an SD feed at the
    # front of its tile — which is exactly the complaint the measuring was
    # meant to end. A feed that ever drops to SD is not an HD source.
    previous = done.get(sid) or 0
    done[sid] = min(previous, height) if (previous and height) else (height or previous)
    # Frame rate takes the same pessimistic rule and for the same reason: a
    # feed that ever drops to 25 is not a 50fps source. The codec does not —
    # it is a fact about the encoder, not a sample of its output.
    if got['height']:
        was = media.get(sid) or {}
        media[sid] = {
            **got,
            'fps': min(was['fps'], got['fps']) if was.get('fps') and got['fps'] else (
                got['fps'] or was.get('fps') or 0),
            # When this was measured. A PPV slot id carries a DIFFERENT match
            # tomorrow, off a possibly different upstream, so unlike a channel
            # its measurement describes an event and not a stream. Whoever
            # ranks fixtures has to be able to ask how old this is.
            'at': int(time.time()),
        }
    if i % 10 == 0 or i == len(queue):
        json.dump(done, open(OUT, 'w'))
        json.dump(media, open(MEDIA_OUT, 'w'), indent=1)
        print(f"{i}/{len(queue)}  last {sid} -> "
              f"{got['codec'] or 'none'} {got['width']}x{height} @{got['fps']}", flush=True)
    time.sleep(0.2)

json.dump(done, open(OUT, 'w'))
json.dump(media, open(MEDIA_OUT, 'w'), indent=1)
ok = sum(1 for v in done.values() if v)
print(f"done: {len(done)} recorded, {ok} decoded", flush=True)
