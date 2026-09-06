#!/bin/bash
# Weekly manifest rebuild. Driven by a launchd agent; see INSTALL below.
#
# Works on a FRESH CLONE in a temp directory, never on a working tree. The
# job runs unattended on a machine someone else is using, and a script that
# checks out branches and commits under a person's feet is a script that will
# one day eat their afternoon's work. Nothing here touches the repository you
# are editing.
#
# Credentials are read from a file OUTSIDE the repository, which is public.
# The file is yours alone (chmod 600) and its contents never reach the clone.
#
# Runs on macOS under launchd and on Linux under systemd; see INSTALL and
# INSTALL (LINUX) below. Nothing in it is specific to either.
#
# INSTALL
#   mkdir -p ~/.config/nuxtv && chmod 700 ~/.config/nuxtv
#   cat > ~/.config/nuxtv/panel.env <<'EOF'
#   AGORO_HOST=your.panel.host
#   AGORO_USER=your-username
#   AGORO_PASS=your-password
#   EOF
#   chmod 600 ~/.config/nuxtv/panel.env
#   launchctl load ~/Library/LaunchAgents/com.agoro.manifest-refresh.plist
#
# UNINSTALL
#   launchctl unload ~/Library/LaunchAgents/com.agoro.manifest-refresh.plist
#   rm ~/Library/LaunchAgents/com.agoro.manifest-refresh.plist
#
# INSTALL (LINUX, systemd --user) — see tools/manifest/systemd/ for the units
#   the machine needs git, python3 and gh, a gh login that can open a pull
#   request, and a key that can push. `loginctl enable-linger $USER` is what
#   lets a --user timer fire while nobody is logged in, which on a home server
#   is the whole point.
set -euo pipefail

# launchd and systemd both hand a job almost no PATH, so the places git, gh
# and python actually live are named rather than assumed. Homebrew's two
# prefixes are harmless on a machine that has neither.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

REPO="${MANIFEST_REPO_URL:-git@github.com:nuxcor/agoro.git}"
# The config directory keeps the OLD name on purpose. The repository was
# renamed to agoro on 2026-09-04; this path names a file that already exists on
# the machine running the job, holding the panel credentials, and a rename here
# would leave the job looking for a file nobody moved — silently, weekly, until
# someone noticed the manifest had stopped. Point MANIFEST_ENV_FILE somewhere
# else if you would rather move it yourself.
ENV_FILE="${MANIFEST_ENV_FILE:-$HOME/.config/nuxtv/panel.env}"
LOG="${MANIFEST_LOG:-$HOME/.config/nuxtv/refresh.log}"

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

KEYCHAIN_SERVICE="${MANIFEST_KEYCHAIN_SERVICE:-nuxtv-panel}"

# Where the panel password comes from, best first.
#
# What encryption buys here, honestly: not much against someone who already
# has your session, because an unattended job must be able to decrypt without
# a human, so whatever unlocks the secret is reachable from that session too.
# What it does buy is that the password stops being a plaintext file in your
# home directory — which is readable by ANYTHING running as you (a rogue
# dependency, a helper process) and which travels into every backup, sync
# client and dotfiles repo that ever touches ~/.config. Those are the two
# realistic ways a home-server credential leaks, and both are closed by
# keeping it out of a file.
#
# The file is still supported and still the fallback. An existing install
# keeps working untouched.
load_credentials() {
    # 1. systemd, which hands the unit a decrypted copy in a private tmpfs
    #    that dies with the process. With LoadCredentialEncrypted the blob on
    #    disk is sealed to the machine (its TPM where there is one), so a copy
    #    of it taken elsewhere is useless. The best of the three.
    if [ -n "${CREDENTIALS_DIRECTORY:-}" ] && [ -r "$CREDENTIALS_DIRECTORY/panel" ]; then
        # shellcheck disable=SC1091
        set -a; . "$CREDENTIALS_DIRECTORY/panel"; set +a
        CRED_SOURCE="systemd credential"
        return 0
    fi

    # 2. The macOS login keychain. Gated by the OS rather than by file
    #    permissions, and Time Machine backs it up encrypted. It needs the
    #    login keychain UNLOCKED, which it is once you have logged in after a
    #    boot — a Mac sitting at the login screen cannot run this, which is
    #    one more reason the server is the better host.
    #
    #    One item per field, not one item holding all three: `security -w`
    #    hex-encodes any password containing a newline and hands back a bare
    #    hex string, so a single multi-line item comes out unreadable.
    if command -v security >/dev/null 2>&1; then
        local host user pass
        host="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a AGORO_HOST -w 2>/dev/null)" || host=""
        user="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a AGORO_USER -w 2>/dev/null)" || user=""
        pass="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a AGORO_PASS -w 2>/dev/null)" || pass=""
        if [ -n "$host" ] || [ -n "$user" ] || [ -n "$pass" ]; then
            export AGORO_HOST="$host" AGORO_USER="$user" AGORO_PASS="$pass"
            CRED_SOURCE="keychain ($KEYCHAIN_SERVICE)"
            return 0
        fi
    fi

    # 3. The plain file. Works everywhere, protects least.
    if [ -r "$ENV_FILE" ]; then
        # shellcheck disable=SC1090
        set -a; . "$ENV_FILE"; set +a
        CRED_SOURCE="$ENV_FILE"
        return 0
    fi
    return 1
}

# Move the file's contents into the login keychain, then say what to delete.
# Deliberately does NOT delete it: removing the only copy of a password on
# someone's behalf is not a thing a script should decide, and if the keychain
# read then fails they have nothing.
if [ "${1:-}" = "--import-keychain" ]; then
    if ! command -v security >/dev/null 2>&1; then
        echo "no keychain on this machine; on Linux use systemd-creds — see the README" >&2
        exit 1
    fi
    [ -r "$ENV_FILE" ] || { echo "nothing to import: $ENV_FILE is not readable" >&2; exit 1; }
    # shellcheck disable=SC1090
    set -a; . "$ENV_FILE"; set +a
    for field in AGORO_HOST AGORO_USER AGORO_PASS; do
        security add-generic-password -U -s "$KEYCHAIN_SERVICE" -a "$field" \
            -w "${!field}" -T /usr/bin/security
    done
    echo "stored in the login keychain as '$KEYCHAIN_SERVICE'."
    echo "verify:  $0 --check"
    echo "then remove the plaintext copy:  rm $ENV_FILE"
    exit 0
fi

# Say where the credentials would come from and whether they are usable,
# without touching the panel or the repository. This is what to run after
# changing anything, rather than waiting a week to find out.
if [ "${1:-}" = "--check" ]; then
    if load_credentials; then
        if [ -z "${AGORO_HOST:-}" ] || [ -z "${AGORO_USER:-}" ] || [ -z "${AGORO_PASS:-}" ]; then
            echo "found $CRED_SOURCE, but one or more values are blank"
            exit 1
        fi
        echo "credentials from $CRED_SOURCE — host ${AGORO_HOST}, user ${AGORO_USER}, password set"
        exit 0
    fi
    echo "no credentials found (looked at systemd, the keychain, then $ENV_FILE)"
    exit 1
fi

# The panel half of this job needs credentials. The SCHEDULE half does not,
# and that distinction is the whole of this block.
#
# It used to be one gate at the top: no credentials, nothing done. The
# credentials have been blank since this was installed, so the job has never
# run once — and it was also the only thing refreshing fixtures.json, which
# expires after eight days and takes SportsParser's matchday gate down with
# it. A public ESPN scoreboard was being held hostage to a panel login it does
# not use.
#
# So HAVE_PANEL decides how much of the run happens, and the run happens
# either way.
HAVE_PANEL=1
if ! load_credentials; then
    log "no credentials found — see INSTALL in this script; fixtures only"
    HAVE_PANEL=0
elif [ -z "${AGORO_HOST:-}" ] || [ -z "${AGORO_USER:-}" ] || [ -z "${AGORO_PASS:-}" ]; then
    # Installed with the keys present and the values blank, so it can be
    # filled in without looking anything up. Blank means NOT CONFIGURED YET,
    # which is not an error to shout about every week.
    log "credentials from $CRED_SOURCE are blank; fixtures only until they are filled in"
    HAVE_PANEL=0
else
    log "credentials from $CRED_SOURCE"
fi

# The template carries its own X's: BSD mktemp appends them to a -t name and
# GNU mktemp refuses a template without them, so spelling them out is the one
# form both accept. This script runs on the Mac under launchd and on a Linux
# home server under systemd.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agoro-refresh.XXXXXXXX")"
# Runs on every exit including the failures, so a panel that is down for a
# week does not leave a week of half-built clones in the temp directory.
trap 'rm -rf "$WORK"' EXIT

log "cloning into $WORK"
git clone --depth 1 --quiet "$REPO" "$WORK/agoro"
cd "$WORK/agoro"

if [ "$HAVE_PANEL" = 1 ]; then
    log "fetching and rebuilding"
    if ! python3 tools/manifest/refresh.py --write >>"$LOG" 2>&1; then
        log "refresh failed — see $LOG; the working tree is untouched"
        exit 1
    fi
fi

# The fixtures, which EXPIRE. fetch_fixtures.py publishes eight days of ESPN
# scoreboards and this job runs weekly, so leaving it out meant the schedule
# ran dry a day before the next rebuild replaced it — and a dry schedule does
# not merely lose the kick-offs. SportsParser's matchday gate is what keeps a
# domestic fixture from being billed Champions League, and with no fixtures to
# check against it passes everything through. The fix goes quiet exactly when
# the file goes stale, which is the worst way for a thing to break.
#
# Needs no credentials — ESPN's scoreboards are public — so it runs even on a
# machine whose panel login has lapsed, and a failure here is not fatal to the
# catalogue rebuild that has already succeeded.
log "fetching fixtures"
if ! python3 tools/manifest/fetch_fixtures.py >>"$LOG" 2>&1; then
    log "fixtures failed — see $LOG; continuing with the catalogue"
fi

# Which streams answer with the panel's black-screen filler. Dead streams move
# with the provider, so this is measured every rebuild rather than once: the
# manifest sinks a black source to the bottom of its tile's ladder, and a
# measurement from last month sinks the wrong ones. One HTTP request per
# stream, no bandwidth and no connection slot — the redirect is served before
# any stream opens.
#
# It runs AFTER refresh.py, because it reads the line-up that build wrote, and
# the build then runs once more to fold the result in.
log "checking for black streams"
if [ "$HAVE_PANEL" = 1 ] && python3 tools/manifest/black_check.py --all >>"$LOG" 2>&1; then
    (cd tools/manifest && python3 build_manifest.py manifest.json >>"$LOG" 2>&1 \
        && cp manifest.json ../../app/src/main/assets/catalogue-manifest.json) \
        || log "rebuild after black_check failed; keeping the first build"
elif [ "$HAVE_PANEL" = 1 ]; then
    log "black_check failed — see $LOG; keeping the previous measurements"
fi

if git diff --quiet -- app/src/main/assets/catalogue-manifest.json \
                       app/src/main/assets/fixtures.json; then
    log "no drift; nothing to open"
    exit 0
fi

# Both files live under app/, so CI's version-guard wants a bump even though
# the app needs no release to pick either up — it reads them off main and
# prefers the newer `generated` stamp. Patch only.
VERSION=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)
CODE=$(grep -o 'versionCode = [0-9]*' app/build.gradle.kts | head -1 | awk '{print $3}')
NEXT="${VERSION%.*}.$(( ${VERSION##*.} + 1 ))"
# In place, portably. GNU sed reads `-i ''` as a filename and BSD sed demands
# the argument, so neither spelling works on both; a temp file and a move does.
bump() {  # bump <sed-expression> <file>
    sed "$1" "$2" > "$2.bump" && mv "$2.bump" "$2"
}
bump "s/versionName = \"$VERSION\"/versionName = \"$NEXT\"/" app/build.gradle.kts
bump "s/versionCode = $CODE/versionCode = $((CODE + 1))/" app/build.gradle.kts

BRANCH="manifest-refresh-$(date +%Y%m%d)"
git checkout -q -b "$BRANCH"
git add app/build.gradle.kts app/src/main/assets/catalogue-manifest.json \
        app/src/main/assets/fixtures.json
if [ "$HAVE_PANEL" = 1 ]; then
    SUBJECT="Catalogue refresh $(date +%Y-%m-%d)"
    BODY="Scheduled rebuild: the provider's line-up moved, so the curation keyed to it
was re-applied. Duplicate folding, drop lists and series shelving are all
keyed by stream or series id and cover nothing the provider added since the
last build; this is what re-applies them. The black-stream measurements were
re-taken in the same run, because which streams are dead moves with the
provider."
else
    SUBJECT="Fixtures $(date +%Y-%m-%d)"
    BODY="Schedule only: the panel credentials are not filled in, so the catalogue was
left alone. The fixtures need no login and expire after eight days, and an
empty schedule silently disables the matchday gate that keeps a domestic
fixture from being billed as a European tie."
fi

git commit -q -m "$SUBJECT

$BODY

Version bumped only to satisfy the version guard — the app reads both files
off main and prefers the newer generated stamp, so it needs no release to
pick this up."
git push -q -u origin "$BRANCH"

gh pr create --base main --head "$BRANCH" \
    --title "Catalogue refresh $(date +%Y-%m-%d)" \
    --body "Scheduled weekly rebuild — the provider's line-up drifted.

The drift table is in the run log at \`$LOG\`. Worth a look before merging:
a large swing in dropped counts usually means the provider renamed or
re-shelved something, which is a curation question rather than a rebuild.

$NEXT bumped for the version guard only; the app picks the manifest up off
main without a release." >>"$LOG" 2>&1

log "opened a pull request for $BRANCH ($VERSION -> $NEXT)"
