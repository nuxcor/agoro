#!/usr/bin/env python3
"""The schedule, from something that knows it.

The panel's PPV slots are marketing strings, not a timetable. One match is
routinely carried by four packs writing four formats, disagreeing about the
kick-off by hours, filling the competition field with the word "all", and
listing a women's fixture under the men's club names. Every rule the parser
has for reading them is a rule about how a pack happens to write, and the
packs keep inventing new ways to write.

This takes the fixtures from ESPN's public scoreboards instead — no key, no
account — and publishes them beside the manifest. The app matches a slot to a
fixture on the club names, which the packs DO get right, and takes the
kick-off and the competition from here.

    python3 fetch_fixtures.py            # -> ../../app/src/main/assets/fixtures.json

Two days back to eight ahead, which covers the app's cue window many times
over and keeps the file small (a few hundred fixtures).
"""
import json, os, sys, time, urllib.error, urllib.request
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

# Manifest league name -> ESPN's path. The names on the left are the ones the
# manifest's sport.leagues uses, because that is what the app bills a row as.
LEAGUES = {
    "NFL": "football/nfl",
    "NBA": "basketball/nba",
    "MLS": "soccer/usa.1",
    "Premier League": "soccer/eng.1",
    "La Liga": "soccer/esp.1",
    "Serie A": "soccer/ita.1",
    "Bundesliga": "soccer/ger.1",
    "Ligue 1": "soccer/fra.1",
    "Champions League": "soccer/uefa.champions",
    "Europa League": "soccer/uefa.europa",
    "Conference League": "soccer/uefa.europa.conf",
    "Carabao Cup": "soccer/eng.league_cup",
    "FA Cup": "soccer/eng.fa",
    # Men's senior national-team football, one row fed by several scoreboards.
    # A tuple because ESPN files each competition separately and the app bills
    # them as one. The World Cup qualifiers are NOT here: the 2030 cycle has
    # not started, all five confederation paths answer 200 with no events, and
    # each would cost eleven requests a run to say so. Add them when it does —
    # "soccer/fifa.worldq.<afc|caf|concacaf|conmebol|ofc|uefa>".
    "Internationals": ("soccer/uefa.nations", "soccer/fifa.friendly",
                       "soccer/caf.nations_qual", "soccer/concacaf.nations.league"),
    # NOT a row. The app carries no women's football; these are here so it can
    # recognise a women's match whose slot does not say so ("Soccer: Barcelona
    # vs. Paris FC (ESP)" was the Women's Champions League) and leave it off.
    # The app keeps them apart from every other fixture — see
    # SportsParser.dropNotOurs for why they must never pair a men's row.
    "Women": "soccer/uefa.wchampions",
}

BASE = "https://site.api.espn.com/apis/site/v2/sports"
DAYS = 8
# Finished matches kept, marked "post". The app drops a slot whose two clubs
# the schedule shows have already played, which is how a pack's name left up
# after full time comes off the screen; it can only do that for a match the
# file still remembers.
DAYS_BACK = 2
UA = {"User-Agent": "agoro-fixtures/1.0 (+https://github.com/nuxcor/agoro)"}


# More failed leagues than this and the run publishes nothing.
MAX_FAILED = 2

# ESPN refusing the question itself, which asking again will not change. Not
# every 4xx: a 408, or the momentary 403 a CDN hands out, is a blip that the
# retries exist for.
REFUSED = (400, 404)


def fetch(league, path, day):
    """One day of one league, or None.

    It used to be one request per league for the whole window — ESPN took
    dates=START-END — until 2026-09-15, when every range began answering 400
    while a single day still answers 200. A day at a time is the shape ESPN's
    own pages ask in, and the one least likely to be withdrawn next.
    """
    url = f"{BASE}/{path}/scoreboard?dates={day}&limit=200"
    for attempt in range(3):
        wait = 1.5 * (attempt + 1)
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = json.load(resp)
            # A 200 that is not a scoreboard — an error document, a reshaped
            # payload — would otherwise read as a day with no games, which is
            # the one hole a failed day must never leave.
            if not isinstance(data, dict) or not isinstance(data.get("events"), list):
                raise ValueError("no events list in the response")
            return data
        except Exception as exc:
            refused = False
            if isinstance(exc, urllib.error.HTTPError):
                refused = exc.code in REFUSED
                if exc.code == 429:
                    # Throttled, and a run is now a hundred-odd requests: take
                    # ESPN's Retry-After where it names one in seconds, and never
                    # less than a pause long enough to matter.
                    try:
                        wait = max(wait * 4, float(exc.headers.get("Retry-After") or 0))
                    except ValueError:
                        wait *= 4
                    wait = min(wait, 60)
                exc.close()
            if refused or attempt == 2:
                print(f"  {league}: FAILED on {day} ({exc})", file=sys.stderr)
                return None
            time.sleep(wait)


def espn_today():
    """Today as ESPN files it, which is the US Eastern date.

    A single-day query returns the fixtures ESPN files under that day, and it
    files them by New York's clock: Thursday Night Football at 00:15Z on the
    18th comes back under the 17th. Counted from the UTC date — as the range
    query was too — the 00:17Z run asked for tomorrow in New York while
    tonight was being played, and TNF, MNF and a Saturday night's MLS were
    missing from the file for the six hours they were on.
    """
    return datetime.now(ZoneInfo("America/New_York")).date()


def fetch_window(league, path, today):
    """Every event from DAYS_BACK ago to DAYS ahead, once each; None if a day failed.

    Back past today because a late kick-off out west is still being played
    after midnight Eastern, and because a finished match is worth keeping in
    the file marked finished; see DAYS_BACK.

    A league missing one day would publish a hole that looks exactly like a
    rest day, so a single failed day fails the league; see main() for what
    happens to it then.
    """
    events = {}
    for n, offset in enumerate(range(-DAYS_BACK, DAYS + 1)):
        if n:
            time.sleep(0.2)
        day = (today + timedelta(days=offset)).strftime("%Y%m%d")
        data = fetch(league, path, day)
        if data is None:
            return None
        for event in data["events"]:
            # A match can turn up under two days. The later day's copy wins:
            # if the two differ, it is the one that has moved on.
            events[event.get("id") or id(event)] = event
    return list(events.values())


# The spellings worth publishing beside the display name.
#
# NOT "location": on the NFL that field is the CITY, so the Jets and the
# Giants both answer to "New York" and the Rams and the Chargers both answer
# to "Los Angeles" — and the matcher treats a shorter name as the same club
# when it is a subset of a longer one. Two clubs sharing a spelling is the one
# thing an alias list must not do.
ALIAS_FIELDS = ("shortDisplayName", "name")


# The other name a nation goes by, where ESPN's and the packs' do not share a
# word. The app pairs a slot with a fixture on name tokens, so "Turkey" never
# found "Türkiye" and "USA" never found "United States" — and a national match
# on the UEFA shelf has no clock of its own, so an unpaired one is dropped.
# Only pairs with NO common word are worth listing: "Ireland" already pairs
# "Republic of Ireland", "Bosnia" pairs "Bosnia-Herzegovina".
NATION_ALIASES = {
    "Türkiye": ["Turkey"],
    "Czechia": ["Czech Republic"],
    "United States": ["USA"],
    "Ivory Coast": ["Côte d'Ivoire", "Cote d'Ivoire"],
    "Cape Verde": ["Cabo Verde"],
    "South Korea": ["Korea Republic"],
    "Kyrgyz Republic": ["Kyrgyzstan"],
    "Netherlands": ["Holland"],
    "Congo DR": ["DR Congo"],
    "Iran": ["IR Iran"],
}


def side(competitor):
    """One club: how ESPN bills it, what else it answers to, and its badge."""
    team = competitor.get("team") or {}
    name = team.get("displayName")
    if not name:
        return None
    alts, seen = [], {name.casefold()}
    for alt in [team.get(f) for f in ALIAS_FIELDS] + NATION_ALIASES.get(name, []):
        if alt and alt.casefold() not in seen:
            seen.add(alt.casefold())
            alts.append(alt)
    return {"name": name, "alt": alts, "logo": team.get("logo") or ""}


def sides(event):
    """Home and away, as ESPN spells them. The app normalises before matching."""
    comps = (event.get("competitions") or [{}])[0].get("competitors") or []
    home = away = None
    for c in comps:
        info = side(c)
        if not info:
            continue
        if c.get("homeAway") == "home":
            home = info
        elif c.get("homeAway") == "away":
            away = info
    return home, away


def main():
    today = espn_today()
    # The window's first instant, Zulu, in the file's own format so the
    # carried fixtures below can be cut on a string comparison.
    floor = (datetime.combine(today - timedelta(days=DAYS_BACK), datetime.min.time(),
                              ZoneInfo("America/New_York"))
             .astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%MZ"))
    dest = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "..", "app", "src", "main", "assets", "fixtures.json"))
    prev = {}
    if os.path.exists(dest):
        try:
            prev = json.load(open(dest))
        except Exception:
            prev = {}
    out, counts, failed = [], {}, []
    for league, path in LEAGUES.items():
        if len(failed) > MAX_FAILED:
            # The run can only end in the refusal below now; every further
            # league would just spend its retries finding that out again.
            break
        events = []
        for one in (path if isinstance(path, tuple) else (path,)):
            got = fetch_window(league, one, today)
            if got is None:
                # One scoreboard missing is a hole in the row that looks like
                # a quiet week, so it fails the whole league — carried below.
                events = None
                break
            events.extend(got)
        if events is None:
            # A request that FAILED, which is not the same as a league with no
            # fixtures this week. Counted, because a run where most of them
            # fail must not publish over a good file.
            #
            # And carried rather than dropped when the run does publish. A
            # day at a time is eleven chances a league to hit one timeout, and
            # publishing the league absent took every one of its rows back to
            # reading kick-offs out of slot names for six hours — then back
            # again, one more commit and cache bust later. Last time's copy of
            # its fixtures is older, not wrong.
            kept = [f for f in prev.get("fixtures") or []
                    if f.get("league") == league and f.get("start", "") >= floor]
            out.extend(kept)
            counts[league] = len(kept)
            print(f"  {league:<18} {len(kept):>3} fixtures, carried from the last good file")
            failed.append(league)
            continue
        n = 0
        for event in events:
            home, away = sides(event)
            date = event.get("date")
            if not (home and away and date):
                continue
            record = {
                "league": league,
                "home": home["name"],
                "away": away["name"],
                # ESPN writes Zulu; the app parses it as such and does its own
                # local arithmetic. No zone is ever inferred from a name here,
                # which is the entire point of this file.
                "start": date,
            }
            # What else each club answers to. The display name alone is why
            # Real Madrid v Inter sat on the wrong clock: ESPN bills the away
            # side "Internazionale", every pack writes "Inter", the two share
            # no word, the fixture went unmatched and the row kept the pack's
            # kick-off — which is the one this file exists to overrule.
            if home["alt"]:
                record["homeAlt"] = home["alt"]
            if away["alt"]:
                record["awayAlt"] = away["alt"]
            # The badge, from the same record as the clock.
            #
            # The app used to look a crest up by club name in a separate
            # index, which meant the name it matched on and the name the badge
            # was filed under had to agree — and they routinely did not. Taken
            # from the fixture there is no lookup to miss.
            if home["logo"]:
                record["homeLogo"] = home["logo"]
            if away["logo"]:
                record["awayLogo"] = away["logo"]
            # Whether it has FINISHED, which nothing else here can answer.
            #
            # A kick-off alone only says a match has begun; the app was reading
            # "started" as "on now" and a fixture stayed on now forever. Match
            # lengths are no help either — football runs two hours, an NFL game
            # three and a half, a Test match days — so the only honest source
            # is the one keeping score. ESPN's state is "pre", "in" or "post".
            state = (((event.get("status") or {}).get("type") or {}).get("state"))
            if state in ("pre", "in", "post"):
                record["state"] = state
            out.append(record)
            n += 1
        counts[league] = n
        print(f"  {league:<18} {n:>3} fixtures")
    out.sort(key=lambda f: (f["start"], f["league"], f["home"]))

    # Refuse to publish a partial outage. The old guard only asked for five
    # fixtures in total, and MLS alone carries forty — so a run that lost every
    # European league and the NFL would have committed happily and taken those
    # rows back to reading kick-offs out of slot names, which is the one thing
    # the guard exists to prevent. A league with no fixtures is ordinary (a
    # winter break, an international window); a league whose REQUEST failed is
    # not, and neither is a collapse in the total.
    if len(failed) > MAX_FAILED:
        raise SystemExit(f"{len(failed)} leagues failed to answer ({', '.join(failed)}) "
                         "— stopped there, refusing to publish over the last good file.")
    was = len(prev.get("fixtures") or [])
    if was >= 20 and len(out) < was * 0.4:
        raise SystemExit(f"{len(out)} fixtures against {was} last time — that is an "
                         "outage, not a quiet week. Refusing to publish.")

    doc = {
        # Held steady when nothing moved. Regenerated every run it made the file
        # byte-different every six hours forever: four commits a day to main,
        # each one re-triggering the release workflow and expiring every box's
        # cache, to say the same thing.
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "site.api.espn.com",
        "days": DAYS,
        "counts": counts,
        "fixtures": out,
    }
    if (prev.get("fixtures") or []) == out and prev.get("counts") == counts:
        doc["generated"] = prev.get("generated", doc["generated"])
    with open(dest, "w") as fh:
        json.dump(doc, fh, indent=1)
    print(f"\n{len(out)} fixtures over {DAYS} days -> {dest}")


if __name__ == "__main__":
    main()
