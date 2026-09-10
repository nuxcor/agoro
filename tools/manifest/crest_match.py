"""Match the clubs in the sport roster to their crests.

The fixture rows are text — "Arsenal v Crystal Palace" — and a crest beside
each club is what turns a list of fixtures into something a viewer reads at a
glance from the sofa. The roster is 239 clubs across nine competitions; this
resolves each one to an image URL and writes crest_map.json, which
build_manifest.py folds into the manifest as `sport.club_crest`.

Nothing is downloaded or redistributed. The manifest carries URLs and the app
fetches them at render time, which is exactly what already happens for channel
artwork via logo_match.py.

Three sources, because no single one covers the roster:

  luukhopman/football-logos   the 25 top European leagues, PNG. No licence
                              stated, so it is linked and never vendored.
  klunn91/team-logos          NFL, NBA, MLB, NCAA. MIT.
  ESPN                        one standings request per competition, which
                              answers with every club in it and its badge.

ESPN is consulted LAST, only for a club the two repositories could not place,
and that order is deliberate. The repositories carry the roster's own
spellings and a club's history — a side relegated out of a covered league
keeps its crest through the archive — where ESPN answers for one season's
field and writes "Wolverhampton Wanderers" where the roster says "Wolves".
Used as a fallback it can only add, and what it adds is the gap this file
used to document as permanent:

  MLS, all 30 clubs. The nearest repository is a Laravel Blade icon pack,
  SVG and not addressable by club name, so every MLS row wore a monogram
  unless the ESPN schedule happened to place its slot.
  Washington's NFL side, which klunn91 files only under the name the club
  dropped in 2022.

It is also the same artwork the fixture badges come from — a.espncdn.com, off
fixtures.json — so a row that switches between the two sources as the
schedule places it does not visibly change badge.

    python3 crest_match.py [manifest.json]      # writes crest_map.json
    python3 crest_match.py [manifest.json] --refresh-espn   # re-fetch ESPN

Refreshing the source indexes needs the two repo trees, one call each:

    gh api "repos/luukhopman/football-logos/git/trees/master?recursive=1" \
      --jq '.tree[]|select(.path|endswith(".png"))|.path' > crest_tree_euro.txt
    gh api "repos/klunn91/team-logos/git/trees/master?recursive=1" \
      --jq '.tree[]|select(.path|test("\\.(png|svg)$"))|.path' > crest_tree_us.txt

ESPN caches itself to crest_espn.json on first run; --refresh-espn re-fetches.
"""
import json, os, re, sys, unicodedata, difflib, urllib.request
from urllib.parse import quote

EURO_RAW = "https://raw.githubusercontent.com/luukhopman/football-logos/master/"
US_RAW = "https://raw.githubusercontent.com/klunn91/team-logos/master/"

HERE = os.path.dirname(os.path.abspath(__file__))
_ARGS = [a for a in sys.argv[1:] if not a.startswith('--')]
_FLAGS = {a for a in sys.argv[1:] if a.startswith('--')}
MANIFEST = _ARGS[0] if _ARGS else os.path.join(HERE, 'manifest.json')
OUT = os.path.join(HERE, 'crest_map.json')
ESPN_CACHE = os.path.join(HERE, 'crest_espn.json')

# Words that are decoration on a club name rather than part of it. Dropped from
# BOTH sides before comparing, so "Arsenal" reaches "Arsenal FC.png" and
# "Atletico Madrid" reaches "Atlético de Madrid.png".
NOISE = re.compile(
    r"(?i)\b(fc|cf|ac|as|ss|ssc|sc|cd|rc|ca|us|sv|vfl|vfb|tsg|fsv|bsc|afc|ogc"
    r"|rcd|ud|sd|cp|club|deportivo|calcio|de|del)\b"
)


def tokens(name):
    """A club name as the set of words that identify it.

    Accents folded, then the decoration words, then STANDALONE numbers — the
    German clubs carry founding years the roster never does ("1. FC Köln",
    "Schalke 04"). Standalone is the whole point: an earlier version stripped
    every digit anywhere, which collapsed "49ers" and "76ers" both to "ers",
    and the San Francisco 49ers shipped wearing the Philadelphia 76ers' badge.
    """
    s = unicodedata.normalize('NFKD', name).encode('ascii', 'ignore').decode()
    # camelCase is a word boundary. klunn91 files "trailBlazers.png",
    # "airForce.png", "washingtonState.png" — one token to any splitter that
    # only knows about spaces, so "Trail Blazers" could never be a subset of
    # it. Split before the case is folded, which is the only point it is
    # still visible.
    s = re.sub(r'(?<=[a-z0-9])(?=[A-Z])', ' ', s)
    s = NOISE.sub(' ', s)
    out = set()
    for t in re.split(r'[^A-Za-z0-9]+', s.lower()):
        if t and not t.isdigit():
            out.add(t)
    return out


def key(name):
    """The tokens joined, for the exact-match index."""
    return ''.join(sorted(tokens(name)))


# The clubs the roster and the sources call different things. Hand-verified,
# every one of them, because the alternative is a fuzzy match confident enough
# to be wrong: "Inter Miami" scores 0.85 against "Inter Milan", and a row
# wearing another club's badge is worse than a row wearing none.
#
# Roster spelling -> the source's own spelling.
ALIAS = {
    # England: the roster uses what a viewer says, the source uses the
    # registered name.
    'manutd': 'Manchester United', 'manunited': 'Manchester United',
    'wolves': 'Wolverhampton Wanderers',
    # Spain, Italy, France, Germany: the short form in common use.
    'internazionale': 'Inter Milan',
    'pisa': 'Pisa Sporting Club',
    'cologne': '1. FC Köln',
    'psg': 'Paris Saint-Germain',
    'lyon': 'Olympique Lyon',
    'rennes': 'Stade Rennais FC',
    'brest': 'Stade Brestois',
    # Champions League entrants from leagues the source covers under another
    # spelling, or not at all.
    'psv': 'PSV Eindhoven',
    'bodoglimt': 'FK Bodø/Glimt',
    # NFL/NBA: klunn91 files by the nickname alone.
    'sixers': '76ers',
    'commanders': 'Washington Commanders',
}

# Clubs with no crest in any source, recorded so a rebuild does not report
# them as a regression every time. Pafos and Kairat are Champions League
# entrants from leagues neither repo carries; Qarabag is in neither tree
# under any spelling, and ESPN's standings answer for the season's field
# rather than the roster's, so none of the three is there either.
#
# Washington's NFL side used to sit here: klunn91 files it only under the name
# the club dropped in 2022, and matching "Commanders" to "redskins.png" would
# have meant writing that name into this file to do it. ESPN calls the club
# what it calls itself, so the entry is gone rather than worked around.
KNOWN_ABSENT = {'pafos', 'kairat', 'qarabag'}


# ---------------------------------------------------------------------------
# ESPN, the fallback source.
#
# The competition -> the path ESPN files it under, and the same names on the
# left that fetch_fixtures.py uses, because both are keyed by what the manifest
# bills a row as. Every competition the roster carries is here; the ones with
# no club list of their own (the billed-only cups) are not, since there is
# nothing to resolve for them.
ESPN_LEAGUES = {
    "NFL": "football/nfl",
    "NBA": "basketball/nba",
    "MLS": "soccer/usa.1",
    "Premier League": "soccer/eng.1",
    "La Liga": "soccer/esp.1",
    "Serie A": "soccer/ita.1",
    "Bundesliga": "soccer/ger.1",
    "Ligue 1": "soccer/fra.1",
    "Champions League": "soccer/uefa.champions",
}

# Standings, not the teams endpoint. site.api's /teams answers 403 to anything
# without a browser session; the standings behind site.web.api are open, come
# back in one request per competition, and carry the same team records — the
# display name, the short forms, and the badge.
ESPN_STANDINGS = "https://site.web.api.espn.com/apis/v2/sports/{path}/standings"
ESPN_UA = {"User-Agent": "agoro-crests/1.0 (+https://github.com/nuxcor/agoro)"}

# Roster spelling -> ESPN's, for the pairs no rule can bridge. Separate from
# ALIAS above because an alias is only true of one source: klunn91 files
# Washington under a nickname, ESPN under the club's full name, and a single
# table would have each of them answering for the other's source.
#
# Keyed by key(), which is the roster spelling's tokens sorted and joined.
ESPN_ALIAS = {
    # The one MLS club ESPN bills by its initials. It matters more than the
    # rest of the table: "Los Angeles FC" is the name the app SHOWS, because
    # sport.club_alias canonicalises the panel's "LAFC" onto it, so this is
    # the spelling the crest has to be filed under.
    'angeleslos': 'LAFC',
}


def load_espn(refresh=False, cache=ESPN_CACHE):
    """{league: {spelling: badge URL}}, one request per competition, cached.

    Cached to a file for the same reason bind_logos.py caches the tv-logos
    tree: a rebuild is run repeatedly while its output is being read, and
    nine requests an iteration to somebody else's API to be told the same
    thing is not a thing to do. --refresh-espn is the way to age it out.

    A competition that fails to answer contributes nothing and is reported.
    It cannot take a crest away — every hit here is a club the repositories
    already had no answer for.
    """
    if not refresh and os.path.exists(cache):
        with open(cache, encoding='utf-8') as fh:
            return json.load(fh)
    out = {}
    for league, path in ESPN_LEAGUES.items():
        url = ESPN_STANDINGS.format(path=path)
        try:
            req = urllib.request.Request(url, headers=ESPN_UA)
            with urllib.request.urlopen(req, timeout=30) as resp:
                doc = json.load(resp)
        except Exception as exc:
            print(f"  ESPN {league}: FAILED ({exc})", file=sys.stderr)
            out[league] = {}
            continue
        teams = {}
        _entries(doc, teams)
        out[league] = teams
        print(f"  ESPN {league:<18} {len(teams):>3} clubs")
    with open(cache, 'w', encoding='utf-8') as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
    return out


def _entries(node, out):
    """Every team record in a standings document, however deep it is grouped.

    The shape is a tree: a league splits into conferences, conferences into
    divisions, and only the leaves carry entries. The NFL nests two deep, the
    Premier League not at all, so this walks rather than indexes.
    """
    for child in node.get('children') or []:
        _entries(child, out)
    for entry in (node.get('standings') or {}).get('entries') or []:
        team = entry.get('team') or {}
        logo = (team.get('logos') or [{}])[0].get('href')
        if not logo:
            continue
        # Every spelling ESPN offers, most specific first — the same three
        # fields fetch_fixtures.py publishes beside a fixture. NOT `location`,
        # for the reason given there: it is the city, and two clubs sharing a
        # city would share a badge.
        for spelling in (team.get('displayName'), team.get('name'),
                         team.get('shortDisplayName')):
            if spelling:
                out.setdefault(spelling, logo)


def _season_rank(path):
    """Newest first: logos/ is the current season, history/ goes back to 2021.

    History is indexed and not skipped, and it is load-bearing. The current
    logos/England - Premier League holds Coventry, Hull and Ipswich and holds
    no West Ham, Wolves or Burnley — so the current season alone cannot dress
    the league the roster actually carries. Any club that has been in a covered
    league since 2021 keeps a crest through relegation.
    """
    if path.startswith('logos/'):
        return (0, 0)
    m = re.match(r'history/(\d{4})-', path)
    return (1, -int(m.group(1))) if m else (2, 0)


def build_index(tree_path, base_url, folders=None):
    """path list -> ({club key: URL}, {club key: token set}), newest first.

    `folders` restricts the tree to the top-level directories named, which is
    how the US index is kept honest. klunn91 files every league it carries in
    one repository — MLB/, NBA/, NCAA/, NFL/ — and a single flat index over all
    of them resolves on the club NICKNAME alone: "Cardinals" and "Giants" exist
    in both MLB and the NFL, MLB sorts first, and setdefault handed the NFL
    roster two baseball badges. The pool a league is resolved against is now the
    league's own folder.

    encoding='utf-8' explicitly, on every read and write in this module. The
    tree listings carry "Atlético de Madrid", "1.FC Köln", "FK BodøGlimt", and
    the default is the locale's — so under LC_ALL=C, which is an ordinary CI
    container, this raises UnicodeDecodeError or silently mis-decodes into keys
    that match nothing and URLs that 404.
    """
    if not os.path.exists(tree_path):
        sys.exit(f"{tree_path} not found — see the module docstring for the gh call")
    with open(tree_path, encoding='utf-8') as fh:
        paths = sorted((l.strip() for l in fh if l.strip()), key=_season_rank)
    idx, tok = {}, {}
    for p in paths:
        if folders and p.split('/')[0] not in folders:
            continue
        base = os.path.splitext(os.path.basename(p))[0]
        if base.startswith('_'):          # _NFL_logo.png and friends: the league, not a club
            continue
        # setdefault, so the first (newest) spelling of a club wins and the
        # older seasons only fill in what the newer ones do not have.
        # quote(), not a hand-rolled escape. The first version of this used
        # ord() per character, which emits Latin-1 — "Atlético" became %E9
        # where raw.githubusercontent wants the UTF-8 %C3%A9, and every one of
        # the 40 accented clubs 404'd while the ASCII ones looked fine.
        k = key(base)
        if k not in idx:
            idx[k] = base_url + quote(p, safe='/')
            tok[k] = tokens(base)
    return idx, tok


# The near-miss floor. Chosen against the roster: it admits the accent and
# punctuation variants and rejects Inter Miami -> Inter Milan (0.85) and
# Wolfsburg -> Wolfsberger AC. Loosening it does not add coverage, it adds
# wrong badges.
NEAR = 0.88


def resolve(club, pool, pool_tokens, alias_table=None):
    """One club against one index. Exact, then token subset, then near-miss.

    `alias_table` because an alias is a fact about a SOURCE, not about a club:
    klunn91 files Washington under a nickname and ESPN under the full name, so
    each index is resolved against its own table and never the other's.
    """
    alias = (ALIAS if alias_table is None else alias_table).get(key(club))
    want = tokens(alias) if alias else tokens(club)
    k = ''.join(sorted(want))
    if k in pool:
        return pool[k]
    # SUBSET OF WORDS, not substring of letters. Substring matching in either
    # direction is what put five more wrong badges in the manifest: "angers"
    # sits inside "rangers", "rapid" inside "coloradorapids", "sporting"
    # inside "sportingkansascity" — and the tie-break took the SHORTEST hit,
    # which deliberately picks the least specific candidate. Words cannot do
    # that: Angers and Rangers share no word at all.
    #
    # Fewest surplus words wins, which is the same instinct the old shortest
    # rule had and the reason it was there: "Barcelona" is a subset of both
    # "FC Barcelona" (nothing left over once FC is noise) and "Barcelona B"
    # (one word left over), and the senior side is the one with nothing left.
    if want:
        hits = [(len(toks - want), x) for x, toks in pool_tokens.items() if want <= toks]
        if hits:
            return pool[min(hits)[1]]
    close = difflib.get_close_matches(k, list(pool), 1, NEAR)
    if close and difflib.SequenceMatcher(None, k, close[0]).ratio() >= NEAR:
        return pool[close[0]]
    return None


def main():
    with open(MANIFEST, encoding='utf-8') as fh:
        manifest = json.load(fh)
    leagues = (manifest.get('sport') or {}).get('leagues') or {}
    if not leagues:
        sys.exit(f"{MANIFEST} carries no sport.leagues — run build_manifest.py first")

    # Named per competition, never a default. An `else euro` sent every
    # league without an entry into the European index; MLB or NHL added to the
    # roster tomorrow would silently do the same thing MLS did.
    global POOL
    euro, euro_tok = build_index(os.path.join(HERE, 'crest_tree_euro.txt'), EURO_RAW)
    us_tree = os.path.join(HERE, 'crest_tree_us.txt')
    nfl, nfl_tok = build_index(us_tree, US_RAW, folders={'NFL'})
    nba, nba_tok = build_index(us_tree, US_RAW, folders={'NBA'})
    print(f"index: {len(euro)} European clubs, {len(nfl)} NFL, {len(nba)} NBA")
    POOL = {
        'Premier League': (euro, euro_tok), 'La Liga': (euro, euro_tok),
        'Serie A': (euro, euro_tok), 'Bundesliga': (euro, euro_tok),
        'Ligue 1': (euro, euro_tok), 'Champions League': (euro, euro_tok),
        'NFL': (nfl, nfl_tok), 'NBA': (nba, nba_tok),
        # MLS: no entry here, still. The repositories have nothing for it —
        # ESPN below is what dresses it.
    }

    # The fallback, indexed the same way and scoped to its own competition.
    # Per league rather than pooled, for the reason the US index is split by
    # folder: the nicknames are shared across sports, and one flat index hands
    # an NFL row a basketball badge.
    espn_raw = load_espn(refresh='--refresh-espn' in _FLAGS)
    ESPN = {}
    for league, teams in espn_raw.items():
        idx, tok = {}, {}
        for spelling, url in teams.items():
            k = key(spelling)
            if k and k not in idx:
                idx[k], tok[k] = url, tokens(spelling)
        ESPN[league] = (idx, tok)
    print(f"espn: {sum(len(v[0]) for v in ESPN.values())} club keys "
          f"across {len(ESPN)} competitions")

    # The sport a competition is played in, which is what scopes a crest key.
    # The bare club name is not unique across sports — "Spurs" is San Antonio
    # and Tottenham, "Patriots" and "Falcons" and "Giants" are each two clubs —
    # so a flat name->URL map has one of every pair silently overwriting the
    # other, and whichever lost wore the wrong badge.
    SPORT = {
        'NFL': 'gridiron', 'NBA': 'basketball',
        'MLS': 'soccer', 'Premier League': 'soccer', 'La Liga': 'soccer',
        'Serie A': 'soccer', 'Bundesliga': 'soccer', 'Ligue 1': 'soccer',
        'Champions League': 'soccer', 'Europa League': 'soccer',
        'Conference League': 'soccer', 'UEFA': 'soccer',
        'Carabao Cup': 'soccer', 'FA Cup': 'soccer',
    }

    crest, missing, from_espn = {}, [], 0
    for league, clubs in leagues.items():
        pool, pool_tok = POOL.get(league, (None, None))
        espn, espn_tok = ESPN.get(league, (None, None))
        if pool is None and espn is None:
            # No source for this competition, and that is the whole answer.
            # A competition with no pool used to fall through to the European
            # index on `else`, which is how MLS — documented as resolving to
            # nothing — put Portugal's Sporting CP on Sporting Kansas City and
            # Romania's FC Rapid on the Colorado Rapids. Falling through to a
            # NAMED index for the same competition is the opposite move: it
            # cannot reach another league's clubs, because there are none in it.
            missing.extend((league, c) for c in clubs)
            continue
        for club in clubs:
            url = resolve(club, pool, pool_tok) if pool is not None else None
            if url is None and espn is not None:
                url = resolve(club, espn, espn_tok, ESPN_ALIAS)
                if url:
                    from_espn += 1
            if url:
                # Both keys. The scoped one is what the app prefers and is the
                # only one that can be right for a shared nickname; the bare one
                # is what every build already shipped reads, and dropping it
                # would take the badges off any box that has not updated yet.
                sport = SPORT.get(league)
                if sport:
                    crest[f"{sport}|{club}"] = url
                # Last league wins the bare key, which is what this always did.
                # It is the wrong answer for a shared nickname — that is what
                # the scoped key above is for — but the manifest reaches a box
                # within a day while an app update does not, so a build that
                # only knows the bare key must keep reading exactly what it read
                # before. Roster order puts football after the US leagues, so
                # "Spurs" stays Tottenham there.
                crest[club] = url
            elif key(club) not in KNOWN_ABSENT:
                missing.append((league, club))

    with open(OUT, 'w', encoding='utf-8') as fh:
        json.dump(crest, fh, indent=1, ensure_ascii=False)
    total = sum(len(v) for v in leagues.values())
    scoped = sum(1 for k in crest if '|' in k)
    print(f"{scoped}/{total} clubs matched -> {OUT} ({len(crest)} keys with the bare "
          f"aliases), {from_espn} of them from ESPN")
    if missing:
        by = {}
        for lg, c in missing:
            by.setdefault(lg, []).append(c)
        print("no crest:")
        for lg, v in sorted(by.items(), key=lambda x: -len(x[1])):
            print(f"  {lg:18s} {len(v):3d}  {', '.join(v[:6])}{' …' if len(v) > 6 else ''}")


if __name__ == '__main__':
    main()
