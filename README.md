# Freemap GPS Recorder

Android GPS track recorder. Records a high-accuracy location stream from a foreground service and
appends every fix to on-disk SQLite, so recording survives backgrounding and screen lock.

This is an early skeleton: the UI is recording state, a start/stop button, a live point count and a
setup checklist. Track management and upload come later.

## Requirements

- Android 8.0 (API 26) or newer
- Google Play services (uses `FusedLocationProviderClient`)

## Build

```sh
./gradlew assembleDebug     # APK in app/build/outputs/apk/debug/
./gradlew installDebug      # build and install on the attached device
./gradlew releaseApk        # signed, shrunk, as app/build/distributions/freemap-gps-recorder-<version>.apk
```

Toolchain: AGP 9.2.1, Gradle 9.4.1, JDK 17+, compileSdk/targetSdk 36, minSdk 26.

The version lives in `gradle.properties` — `recorder.versionCode` and `recorder.versionName` — and
everything else derives from it: the manifest, the APK filename, and `version` in `GET /status`.
`versionCode` has to go up for every published APK, because it is what the update check compares
against the server's manifest.

Release builds are minified and resource-shrunk (~300 KB). There are no keep rules to speak of: the
app reflects on nothing, and every string that crosses a boundary — the `/status` field names, the
`Vendor.id` values, the SQLite column names, the update manifest's keys — is written out literally
rather than derived from a class or member name. `Vendor.id` exists for exactly that reason, since
the enum's own `name` is renamed by R8.

`releaseApk` produces one universal APK. There is no app bundle and no ABI split: bundles are a Play
distribution format and would be useless for a direct download, and with no native code a single APK
already covers every device.

### Signing

Release signing credentials are read from environment variables first, then `keystore.properties`
beside the project (gitignored — see `keystore.properties.example`), then `~/.gradle/gradle.properties`:

| variable | property |
| --- | --- |
| `FREEMAP_GPS_RECORDER_STORE_FILE` | `recorder.storeFile` |
| `FREEMAP_GPS_RECORDER_STORE_PASSWORD` | `recorder.storePassword` |
| `FREEMAP_GPS_RECORDER_KEY_ALIAS` | `recorder.keyAlias` |
| `FREEMAP_GPS_RECORDER_KEY_PASSWORD` | `recorder.keyPassword` |

With none of them set the build still runs and says so, producing an unsigned APK. The keystore
itself never lives in the repository, and `*.jks`, `*.keystore` and `keystore.properties` are
gitignored so it cannot wander in.

**Back the keystore up somewhere that is not this working copy.** A self-hosted APK has no Play App
Signing safety net: without that key no update can install over an existing install, and every user
would have to uninstall first — losing whatever they had recorded. Signing is v2 + v3; v3 is the
scheme that understands key rotation, which is the nearest thing to an escape route if the key is
ever lost or compromised.

## How it works

`RecordingService` is a foreground service with `foregroundServiceType="location"` and a persistent
notification carrying a **Stop** action. It requests fixes on the terms `RecordingConfig` sets — 1 Hz
at `PRIORITY_HIGH_ACCURACY` by default — and holds a partial wake lock while it runs, because without
it delivery becomes unreliable once the screen goes off.

Fixes are delivered onto a dedicated `HandlerThread`, so SQLite writes never touch the main thread.
`PointStore` appends each one through a single reused compiled statement.

The service has **one state, not two**: it is tracking, or it is not there. It used to carry a second
one for pause — a session kept open with fixes no longer being consumed — because a break taken as a
stop and a later start could be refused outright on Android 12+, which turned a pause into a failed
recording. Requiring the battery-optimisation exemption to record at all removes that failure, and
with it the reason for the second state: a stop and a start release and re-acquire exactly what a
pause and a resume did, open a new `seg` the same way, and are now just as reliable from a page in the
background. What is gone is the notification saying *paused* rather than disappearing.

`RecordingConfig` — the cadence, the displacement gate, the accuracy floor and the priority — is set
over the API and persisted, so the app's own Start button records with whatever the website last asked
for. It is applied by re-subscribing rather than by restarting anything, which is why `POST /start`
against a running recording can stay idempotent instead of having to refuse a config it disagrees with.
Everything it can express is enforced here rather than in whatever page is watching: a fix filtered out
on this side is one that never costs battery to keep or disk to store, and every client then sees the
same track.

`GnssMonitor` picks up what the fused client drops: the satellite count, and the geoid separation that
turns an ellipsoidal altitude into one above mean sea level. Neither survives the trip through Play
services — a `FusedLocationProviderClient` `Location` has no satellite information at all, and on an
API 36 emulator `hasMslAltitude()` is false on it even though the platform's own providers report the
figure. So both are read from the platform's own GNSS and married up by time.

It asks for nothing of its own. The status callback reports on a receiver the recording has already
turned on, and locations come from `PASSIVE_PROVIDER`, which by definition only delivers what some
other request has already paid for — so a recording at `priority: low` sees nothing and honestly says
so. Both readings go stale deliberately, on windows that match what they describe: ten seconds for the
satellite count, which describes the receiver, and ten minutes for the separation, which describes the
ground and holds across a tunnel.

The separation, and not the MSL altitude, is what gets carried across. An MSL altitude belongs to the
fix that carried it and would be wrong here by the difference between the two fixes' altitudes — 40 m
of error on the emulator, where the raw GNSS fix and the fused one disagree by exactly that. The
separation is geometry of the geoid, and subtracting it from *this* fix's own `alt` reproduces the
platform's own figure to the last decimal.

## Setup

Recording in the background is not one permission but five separate concessions, three of which
Android will not even prompt for. The Activity therefore shows a checklist of them, each with its
live state and a button that opens whatever screen can resolve it. It is re-read on every resume,
because most of them are granted in Settings — outside the app, with nothing to call back.

| item | how it is resolved | blocking? |
| --- | --- | --- |
| Location access | prominent-disclosure screen, then the system prompt for fine + coarse | **yes** |
| Background location | Android 11+ shows no prompt at all, so it is app settings → Permissions → Location → *Allow all the time* | no |
| Notifications | system prompt on Android 13+; implicit before that | **yes** |
| Unrestricted battery use | plain-language explanation, then `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **yes** |
| Vendor app restrictions | vendor-specific guidance, plus a shortcut to that vendor's own screen | no |

Start is blocked until location, notifications and the battery exemption are all in place — that is
`Setup.canRecord`, and `POST /start` refuses the same three with `403` and
`"error":"setup incomplete"`. The first two are what recording *is*: without location there is nothing
to record, and without the notification a recording would run with no visible sign of it, which is the
one thing a foreground service exists to prevent.

The exemption is blocking for a different reason, and it is about the website rather than about
battery. `RecorderApi` answers `POST /start` from the app process with the browser in front, so the
`startForegroundService` it makes is a *background* start — and Android 12+ refuses one of those from
an app that is not exempt. Leaving the exemption as a warning meant `canRecord` could be true and the
start be refused anyway, one `409` later. Making it a term of `canRecord` moves that answer to before
the page ever offers a start button, and names the item to resolve.

`RecordingService` checks `Setup.canKeepRecording` in `onStartCommand` — the two permissions, not the
exemption — and stops instead of starting. That is not a formality: revoking a permission kills the app
process, `START_STICKY` has the system restart the service, and Play services does not fail the
location subscription synchronously, so without the check the service comes back and sits in the
notification claiming to record while appending nothing. The exemption is deliberately outside that
gate: it decides whether a recording may *begin* from the background, and a recording already under way
would only be lost by ending it over a setting the user changed while it ran.

Background location and the vendor item only make a recording *survive*, so they never block anything;
while either is outstanding a banner says as much and the checklist stays on screen. Once everything is
resolved both disappear and the screen is just state, count and button again.

The order in the table is the order the items can be asked in: Android silently drops a
background-location request that is bundled with, or precedes, the foreground one, so the background
row stays disabled until location is granted.

### Vendor app restrictions

Xiaomi, Huawei, Samsung, Oppo, Vivo and OnePlus kill background apps on terms of their own, well
beyond anything the platform does, and no API reports whether they have been told not to. The
checklist detects the manufacturer, shows what to change, and offers a shortcut to the screen that
changes it. Vendor components are undocumented and move between ROM versions, so each is tried in
turn and every launch is wrapped; anything that misses falls back to the app-details page, which is
where the per-app switches live on most of these ROMs anyway. The row is the user's word that it is
done, kept in `SharedPreferences`, and it is only shown at all on those manufacturers.

What the row says is worth keeping narrow: it should ask only for what the checklist above cannot
already verify. On Xiaomi it no longer asks for the per-app *Battery saver* mode, because on HyperOS
that mode and the platform's exemption are the same setting under two names — measured, not assumed,
in the Status section below — so the battery row's ✓ is the honest answer for both. What is left is
the Recents lock, which loses tracks, which no intent opens and no API reports.

Note what this item is *not*: autostart. Autostart governs whether a vendor ROM may launch an app
that is not running — on boot, from a broadcast, from another app. A recording the user started is
already running, so autostart is not what keeps it alive; the per-app battery mode is. It matters
here only in one place, and second-hand: `RecordingService` is `START_STICKY`, so a process kill has
the system try to start the service again, and *that* is a background start a vendor can refuse.
The guidance says so rather than repeating the folklore.

## Local HTTP API

The app serves a small HTTP API on **`127.0.0.1:8378`**, so a page on freemap.sk can follow the
recording as it happens, and start or stop one.

**[API.md](API.md) is the reference** — every endpoint, parameter, status code and response shape,
plus the CORS allowlist. It is checked against the code by `./gradlew checkApiDocs`, which `check`
and `releaseApk` both depend on, so it cannot quietly fall behind. Do not re-document endpoints here:
one description of the API is the point.

What belongs here is why it is shaped this way. It is bound to loopback only — never `0.0.0.0` — so
it is not reachable from the LAN, and it lives in the app process, which the foreground service keeps
alive while recording. The server comes up with the process rather than with the recording, because
`POST /start` has to be answerable before anything is being recorded.

Points are encoded positionally rather than as objects because a long track is mostly repeated key
names otherwise; coordinates are rounded to 7 decimals and metre-scale values to 2, past which it is
float noise that still costs bytes on every point. An absent field is `null` and never `0`, since an
unreported speed and a genuine standstill are different facts. The `fields` list is append-only, which
is what makes a new column free: clients decode by it, so one they do not know is one they ignore.

Both altitudes are on the wire because they answer different questions. `alt` is the raw ellipsoidal
value the platform reports and the one that round-trips losslessly; `altMsl` is metres above mean sea
level, which is what GPX `<ele>` means and what agrees with the DEM the map uses — some 42 m apart over
Slovakia. Only Android 14 can tell them apart, so `altMsl` is null below it and there is nothing to fall
back on but `alt`.

`seq` is SQLite `AUTOINCREMENT`, so ids are never reused even after `DELETE /track`. That is what
makes clearing safe for clients: a stale `?since=` can come back empty, but it can never come back
with *different* points wearing ids the client already has. `generation` in `/status` is the signal
that a clear happened at all.

The stream carries a named `status` event as well as points, and `/status` names the point columns in
`fields`. Both exist for one reason: a client should not have to *infer* something the recorder knows.
Without the status event a connected client polls to notice a stop, which leaves its panel
stale by the poll interval — read as a bug rather than as an interval — and a frozen background tab runs
no timer at all. Without `fields`, a client that attaches to the stream without reading a `/track` page
first must fall back on a hardcoded column list, which stays correct only while the names it knows remain
a prefix of the real ones. Append-only keeps that true, but safe-by-convention is not the same as told.

The status event carries no `id:`, deliberately: `Last-Event-ID` is a point cursor, and a status frame
that set it would have a reconnecting client resume from a value that is not a `seq`, losing or replaying
points. One goes out on connect, and after that only when the object differs from the last one sent — so
receiving one means something genuinely changed, and a client can act on it rather than diffing.

`seg` exists because the recorder is the only party that actually knows where the breaks are. A client
can guess from a gap in `ts`, and it will be wrong at both ends — a tunnel looks like a break and a
twenty-second one looks like none — and every client would have to guess the same way. The ordinal is
kept in preferences rather than derived from the track, so the one break nothing else would notice is
recorded too: a process kill and the `START_STICKY` restart that follows it.

CORS is an allowlist because any site you visit could otherwise talk to a recorder running on your
phone. The list lives in `ALLOWED_ORIGINS` in `RecorderApi.kt`; adding a hostname means adding it
there, since matching is exact on scheme, host and port.

```sh
adb forward tcp:8378 tcp:8378          # then reach it from the host
curl 'http://127.0.0.1:8378/track?since=0'
curl -N http://127.0.0.1:8378/stream
curl -X POST http://127.0.0.1:8378/start
curl -X DELETE http://127.0.0.1:8378/track
```

## Launching from the web

A page can link straight into the recorder with the `freemap-gps-recorder://` scheme:

| link | what happens |
| --- | --- |
| `freemap-gps-recorder://start` | starts recording and immediately hands focus back to the browser |
| anything else, e.g. `freemap-gps-recorder://open` | just opens the app |

`start` is the useful one: the Activity fires up the foreground service and then gets out of the
way — it finishes when the link created the task, and moves the task to the back when the app was
already open, so the screen the user left behind survives. Either way focus returns to the browser
instead of leaving them looking at the native UI. If the app is not able to record yet it stays up
with the [checklist](#setup) instead, opens the first thing standing in the way, and hands back the
moment recording actually starts — which may be several prompts later.

A link makes the app foreground, so the foreground-service start it makes is always permitted — the
one thing `POST /start` cannot count on from the background, and why the exemption below is a
requirement rather than a warning. The link still goes through the same `Setup.canRecord` gate, so with
the exemption outstanding it opens that checklist row instead of starting; resolving it is a one-time
visit that makes every later start, link or `POST /start`, work.

An optional `?port=` is echoed back as `portEcho` in `GET /status`, alongside the authoritative
`port`. Send the port you intend to talk to, then read `/status` on it: getting your own value back
confirms that the app answering there is the one the link just launched, and not something else
bound to that port. A mismatch with `port` means the page and the app disagree — trust `port`.

```js
location.href = 'freemap-gps-recorder://start?port=8378'
// then, on return to the page:
const s = await (await fetch('http://127.0.0.1:8378/status')).json()
// s.recording === true, s.portEcho === 8378
```

Nothing tells the page whether the app is installed — the link silently does nothing if it is not,
so treat a `/status` that never answers as "not installed".

### Battery-optimisation exemption

`POST /start` is answered while the app is backgrounded, so it needs the app to be exempt from battery
optimisation — without it Android 12+ rejects the foreground-service start with
`ForegroundServiceStartNotAllowedException`. That is why the exemption is a **blocking**
[checklist](#setup) item and one of `Setup.canRecord`'s terms: the alternative was telling a page it
could record and then answering its start with a `409`, which is a worse way to learn the same fact.
The `409` still exists as a safety net, and `/status` reports the exemption as `batteryExempt`.

## Distribution and updates

The APK is downloaded from the Freemap server and installed by hand, so the first install runs into
the one thing Android asks about a browser download: whether that browser may install apps. The
in-app **Installing and updating** screen (overflow menu) explains that, says that an update installs
over the top and keeps the recorded track — and that uninstalling does not — and shows the manifest
URL for anyone who would rather point [Obtainium](https://obtainium.imranr.dev/) at it.

The app checks for a newer version against a small JSON manifest, at
`recorder.updateManifestUrl` (`gradle.properties`, baked in as `BuildConfig.UPDATE_MANIFEST_URL`) —
currently `https://download.freemap.sk/freemap-gps-recorder/latest.json`:

```json
{
  "versionCode": 5,
  "versionName": "0.5",
  "apkUrl": "https://download.freemap.sk/freemap-gps-recorder/freemap-gps-recorder.apk",
  "notes": "Clear the recorded track from the website, and a documented local API.",
  "minSupportedVersionCode": 1
}
```

**That file is generated, not hand-written.** `./gradlew releaseApk` writes it to
`app/build/distributions/latest.json` from the same properties the APK is built from, so it cannot
advertise a version that was never built. Publishing a release is: set `recorder.releaseNotes`, bump
the version, run `./gradlew clean releaseApk`, then upload both files — the APK as
`freemap-gps-recorder.apk` and the manifest as `latest.json`. Upload the APK **first**, or a phone that
checks in between will offer a download that 404s.

Upload each file under a temporary name and rename it into place. The unversioned filename is live the
moment it is writable, so a plain overwrite leaves a window in which a phone downloads a truncated
APK — and the rename closes it for the cost of one extra command. Then read back what the *server*
serves rather than what was uploaded: the manifest's `versionCode`, and the APK's SHA-256 against the
local build. Check the signer first, too — an APK signed with a different key installs for nobody who
already has this app, and `apksigner verify --print-certs` says so before the upload rather than after.
[CLAUDE.md](CLAUDE.md#releasing) has the commands.

| field | | from |
| --- | --- | --- |
| `versionCode` | **required** | `recorder.versionCode` |
| `apkUrl` | **required**, must begin with `https://` | `recorder.apkUrl` |
| `versionName` | optional; defaults to `versionCode` as text | `recorder.versionName` |
| `notes` | optional; defaults to empty | `recorder.releaseNotes` |
| `minSupportedVersionCode` | optional; defaults to `0` | `recorder.minSupportedVersionCode` |

Two things to get right when publishing. The manifest must not be cached for long, or an update sits
invisible behind a stale copy — the app only asks once a day as it is. And if `apkUrl` stays at one
unversioned filename, that URL has to not be cached either, or the browser fetches the previous APK
from cache and the user installs the version they already had. Serving the manifest with
`Cache-Control: no-cache` and the APK with a short max-age avoids both.

**Neither header is set on the server today** — as of 0.8 both files come back with only `ETag` and
`Last-Modified`, so freshness is left to whatever the client decides to guess. Conditional requests do
pick the new files up, and nothing has been observed going stale, but this is a server-config gap
rather than something the build can fix.

`versionCode` is compared against this build's own; anything higher is offered in a dismissable
dialog with `notes` in it, and **Download** hands `apkUrl` to the browser. Nothing is downloaded and
nothing is installed by the app itself — no silent update, no self-installer. `apkUrl` has to be
`https`, since it arrives over the network and decides where the user is sent to fetch something they
will then install. `minSupportedVersionCode` is the server saying it has dropped this build: the
prompt then says the website may no longer be able to follow a recording, and the version cannot be
skipped.

When it checks:

- **at most once a day**, counted from every attempt rather than every success, so a server that is
  down cannot turn that into once per resume
- **unmetered by preference** — on mobile data it waits up to 30 days rather than never, since a
  phone that only ever sees mobile data would otherwise never hear about an update at all and the
  manifest is a few hundred bytes
- **never during a recording**, including a check the user asks for, which answers *not while
  recording* instead. An update prompt over a running track is an interruption of the one thing the
  app exists to do
- not while handing focus back to a browser after a `freemap-gps-recorder://` link
- **Skip this one** remembers that `versionCode`, so an offer declined for good is not repeated
  tomorrow. A manual check ignores that, and so does an obsolete build

Every failure — offline, timeout, 404, a body that is not the manifest, one over 64 KB — is a log
line at `I/UpdateCheck` and nothing else. A recorder that cannot check for updates is still a
recorder, so nothing here is allowed to interrupt or crash recording. A check the user asked for is
the one case that reports failure, in a toast.

To point a build at a manifest of your own:

```sh
python3 -m http.server 8099 --bind 127.0.0.1     # serving latest.json
adb reverse tcp:8099 tcp:8099
./gradlew releaseApk -Precorder.updateManifestUrl=http://127.0.0.1:8099/latest.json
```

That is what the loopback exception in `network_security_config.xml` is for. Cleartext is off
everywhere else, and the app's own HTTP API is unaffected either way — listening on a socket is not
subject to that policy.

## Storage

One row per fix in `points`, in the app's private `track.db`:

| column | notes |
| --- | --- |
| `seq` | `INTEGER PRIMARY KEY AUTOINCREMENT` — monotonic, never reused, continues across sessions |
| `ts` | fix time, epoch milliseconds |
| `lat`, `lon` | degrees |
| `altitude` | metres above the WGS84 ellipsoid, `NULL` when the fix has none |
| `accuracy` | metres, `NULL` when the fix has none |
| `speed` | m/s, `NULL` when the fix has none |
| `bearing` | degrees, `NULL` when the fix has none |
| `altitude_msl` | metres above mean sea level, `NULL` below Android 14 |
| `altitude_accuracy` | metres, `NULL` when the fix has none |
| `speed_accuracy` | m/s, `NULL` when the fix has none |
| `bearing_accuracy` | degrees, `NULL` when the fix has none |
| `satellites` | used in the fix, `NULL` when GNSS has not reported recently enough to say |
| `provider` | `gps`, `fused`, `network`, or `NULL` |
| `segment` | ordinal, bumped on every start. `0` on rows recorded before schema 2 |

Optional fields are stored as `NULL` rather than `0` when the platform reports them as absent, so a
stationary fix is not mistaken for one heading due north.

Schema version 2 added everything from `altitude_msl` down, by `ALTER TABLE ADD COLUMN` on the existing
table. An upgrade never rewrites or drops a recorded track — someone may have been in the middle of one
when the update landed — so rows from before it keep `NULL` in the new columns and `segment` `0`, which
is an honest "not known" rather than an invented value.

To inspect a recording on a debug build:

```sh
adb shell run-as sk.freemap.gpsrecorder cat databases/track.db > track.db
sqlite3 track.db 'SELECT * FROM points ORDER BY seq DESC LIMIT 10;'
```

Note that WAL is enabled, so pull `track.db-wal` alongside it to see the most recent commits.

## Status

Verified on a Xiaomi device running Android 16 (API 36): with the app backgrounded and the screen
locked, points appended continuously for over 9 minutes at 1 Hz — `seq` contiguous, no gap
above 1.2 s. Stopping from the notification action tears down the service, releases the wake lock
and removes the notification, leaving the recorded points intact.

The HTTP API was verified on the same device, with the app backgrounded throughout:

- the listening socket is `127.0.0.1:8378` only — a connection from the device to its own
  non-loopback address is refused, while loopback answers
- `GET /track?since=N` returns the points after `N`; `POST /stop` and `POST /start` toggle recording
- a 6-minute `/stream` tail with the screen asleep delivered 359 events, `seq` 796–1154 contiguous,
  no missing steps and no inter-arrival gap above 2 s
- reconnecting with `Last-Event-ID: 595` replayed 596–610 in a burst and then continued live from
  611, with no gap or duplicate at the handover
- with nothing recording, `: ping` heartbeats arrive every 15 s

`freemap-gps-recorder://start` was verified on the same device with Firefox as the default browser, on a
cold process and with the app already open: recording started in both cases and the browser was the
resumed activity again within seconds, with no dialog in between. When the app was already open its
task survived the hand-back; when the link had created the task, no activity was left behind. `?port=`
came back as `portEcho`, and a mismatched port was logged rather than acted on. That run predates 0.8,
which makes the battery exemption part of `canRecord` — a link fired without it now opens the battery
row rather than starting, which is the 0.8 paragraph below.

The setup checklist was walked end to end on an API 36 emulator, since MIUI refuses `INJECT_EVENTS`
over adb and the phone cannot be driven by script:

- from a state with every permission revoked: Start disabled, no banner, location and notifications
  marked missing and blocking, background disabled behind *Grant location access first*
- the disclosure screen precedes the system location prompt, and granting it enables the background
  row on return
- background location opens App info, where *Allow all the time* lives; the battery item explains
  itself and then raises `RequestIgnoreBatteryOptimizations`, and the row flips to ✓ on resume
- granting the last item from outside the app, while it sat in the background, collapsed both the
  checklist and the banner on resume — nothing else prompted it
- `POST /start` with notifications revoked answered `403` with `"canRecord":false` and
  `"error":"setup incomplete"`, then `200` once granted
- `freemap-gps-recorder://start` still hands focus straight back when setup is complete; with location
  revoked it keeps the app up and opens the first blocking item instead

Revoking location mid-recording is what turned up the zombie-service case above: the service came
back with `recording:true` and `lastSeq` frozen. With the `canKeepRecording` gate in `onStartCommand`
the restart logs `cannot record: location or notification permission missing, stopping` and leaves no service record,
and no `ForegroundServiceDidNotStartInTimeException` for stopping before going foreground.

On the phone, vendor detection reports `"oem":{"vendor":"xiaomi","needed":true}`, and setting the
acknowledgement flips `acknowledged` and `setupComplete` together. The vendor dialog's own buttons
are the one thing not exercised by script, for the `INJECT_EVENTS` reason above.

MIUI's per-app battery mode is readable — `dumpsys activity service com.miui.powerkeeper` lists a
`pkg|time|mode` row per app, `miuiAuto` by default and `noRestrict` for an unrestricted one — which
settles whether it is a switch of its own. On HyperOS (Android 16, 2026-07) it is not: setting the
app to *Battery saver* on its info page moved powerkeeper to `miuiAuto` **and** dropped the package
from `dumpsys deviceidle whitelist` in the same moment, so `isIgnoringBatteryOptimizations` reports
what the MIUI UI shows. The reverse is only true through the UI — removing the package from the
whitelist over adb left powerkeeper at `noRestrict`, which is adb going around MIUI rather than two
settings disagreeing. Hence the Xiaomi guidance no longer asks for a battery mode the checklist
already verifies.

The signed, minified release APK was verified on the same emulator. A clean install (uninstall
first) reports `versionCode=2`, `signatures=PackageSignatures{version:3}`, and `/status` answers
`"version":{"code":2,"name":"0.2"}`. Recording under R8 behaves as it does unshrunk: the checklist,
the disclosure screen and both system prompts render, and with the app backgrounded the service runs
`isForeground=true types=0x8` and appends contiguous points. A screen-off run started from a
`freemap-gps-recorder://start` link recorded `seq` 1–283 over 408 s with nothing missing and no gap above
3.4 s, and `POST /stop` left no service record behind. The positional `/track` encoding survives
shrinking, which is the one thing to watch there.

The update check was exercised against a manifest served over `adb reverse`:

- a higher `versionCode` raises the prompt with the manifest's notes in it, and **Download** starts
  `ACTION_VIEW https://…` in the browser — nothing is downloaded or installed by the app
- **Skip this one** suppresses it: a day later the check runs, logs `3 available, previously skipped`
  and stays silent, while a manual check offers it again
- `minSupportedVersionCode` above this build turns it into *Update needed*, which overrides the skip
  and drops the skip button
- the daily gate holds — a second launch minutes later makes no request at all
- 25 hours after a check, on mobile data, it makes no request; with Wi-Fi back, the same elapsed time
  checks immediately. That pair is the whole unmetered rule
- an update prompt never appears during a recording, and a check asked for from the menu answers
  *not while recording* instead
- every failure is a log line and nothing else: `HTTP 404` against the real (not yet published)
  `https://freemap.sk/tracker/latest.json`, `apkUrl is not https` for a manifest pointing at
  cleartext, and a `JSONException` for a body that is not JSON. No crash in any of them, and the
  missing `ACCESS_NETWORK_STATE` permission that the first run turned up failed exactly this quietly

An update installing over the top keeps the recording: 104 points were still there, and still served,
after reinstalling with `install -r`.

The origin allowlist was checked against the minified 0.3 build on the emulator, header by header.
`https://www.freemap.sk`, `https://www.freemap.eu`, `https://freemap.sk`, `https://local.freemap.sk`,
`http://local.freemap.sk:5173` and `https://local.freemap.sk:8080` each get their own origin echoed
back on `/status`, `/track` and `/stream` alike, and on the `OPTIONS` preflight. `https://evil.com`,
`https://freemap.sk.evil.com`, `https://evil-local.freemap.sk` and `http://local.freemap.sk.evil.com`
get no `Access-Control-Allow-Origin` at all — nor does `http://www.freemap.sk` or
`https://www.freemap.eu:8443`, which is the point of matching scheme and port and not just the host.
`Vary: Origin` is on every response including the ones with no origin to echo.

`DELETE /track` was exercised on the minified 0.4 build. Clearing 283 points leaves `count` and
`lastSeq` at `0` and takes `generation` from `0` to `1`; `GET /track` then returns an empty `points`
array. The next fix recorded is **`seq` 284, not 1** — the AUTOINCREMENT guarantee the whole
clear-safety argument rests on. Asked for during a recording it answers `409` with
`"error":"recording"` and deletes nothing (`count` unchanged at 2), and a second clear after stopping
takes `generation` to `2`. The preflight advertises `GET, POST, DELETE, OPTIONS`, an allowed origin
gets the header on the `DELETE` response too, and `PUT /track` is `405`.

`checkApiDocs` was checked by breaking API.md three ways and confirming each one fails the build:
renaming an endpoint heading (`/stream` routed but not documented, `/streaming` documented but not
routed), renaming a method (`DELETE` allowed by CORS with nothing documenting it), and deleting the
`generation` row from the status field table. The last one is why the field check reads table rows
rather than backticked words — the first version of it passed, because a fenced code block breaks
backtick pairing and the JSON example still contained the word.

Two things were not driven end to end. The manifest URL is not published yet, so the only server this
has spoken to is a local one. And the install-source prompt itself is Android's, on a first browser
download — the help screen describes it but nothing here has been installed from a browser.

Everything 0.6 added was exercised on the API 36 emulator, both as a debug build and as the signed,
minified release APK.

**The schema 2 upgrade was tested over a real track, which is the one change here that could lose a
recording rather than merely misreport one.** Five points were recorded on 0.5, and the pulled database
confirmed `user_version` 1 with the original eight columns. Installing 0.6 over the top with
`install -r` left `count` and `lastSeq` at 5 and `generation` at 0, and `/track` served all five points
under the new fifteen-field header with `null` in every added column and `seg` `0` — not a fabricated
value, and not a rewritten row.

A `START_STICKY` restart is the break nothing else would record, so it was forced — `run-as kill -9` on
the recording process, since `am kill` will not touch a foreground service. The system restarted the
service, which came back at `tracking started in segment 22, 41 points stored`, one segment on from the
21 it died in.

The config was exercised key by key. A full body is applied and echoed; a body naming one key changes
one thing; an explicit `null` clears the accuracy floor; no body at all reuses what was stored. Clamping
holds in both directions — `intervalMs` of `1` comes back `200`, `86400000` comes back `3600000`, a
negative `maxAccuracyM` comes back `null` — and an unknown key is ignored while a `priority` of `turbo`
leaves the previous one alone. A body of `hello` and an `intervalMs` of `"fast"` both answer `400` with
`"error":"bad config"` and leave the stored config untouched. The accuracy floor is enforced on this
side and not merely hidden: against fixes reporting 5 m, a floor of 3 m recorded **0** points and a
floor of 10 m recorded 3.

`altMsl` turned up the one genuine surprise, and it changed the implementation. Play services'
`FusedLocationProviderClient` reports `hasMslAltitude()` **false** on API 36, even though the platform's
own `gps` and `fused` providers both carry `mslAlt` — so reading it straight off the fix, which is what
the obvious implementation does, yields `null` forever. The recorder now takes the geoid separation from
a passive GNSS fix instead and subtracts it from each fix's own `alt`. That reproduces the platform's
own answer exactly: for `alt=300.0` the recorder stored `260.12` where `dumpsys location` reports
`mslAlt=260.1202623946683`. It is `null` for the first points of a recording, until a GNSS fix has been
along — which is visible in the transcript as the segment boundary where it starts appearing.

`sat` reads `0` on this emulator throughout, which is the honest answer rather than a missing one: the
GNSS status callback does fire, and `dumpsys` confirms `satellites=0` in the fix's own extras. That the
callback fires at all is what the emulator can show; a non-zero count needs real sky.

The minified release APK was checked last, since the positional encoding is the thing R8 could quietly
break. It installs as `versionName=0.6` with `PackageSignatures{version:3}`, serves the full
fifteen-field header, records across a break into a new segment, computes `altMsl`, and returns
`"priority":"high"` and `"src":"fused"` intact — the enum `id` and the field names surviving shrinking is
exactly what writing them out literally is for. `checkApiDocs` was extended to cover the point fields
too, and was confirmed to fail both when a documented row is removed and when API.md invents a field
the code does not have.

Two things this emulator cannot show. `priority: "balanced"` records nothing on it, because its
`network_location_provider` is disabled — the request is accepted and the config reported, but no
provider answers, so the cadence each priority actually produces still needs real hardware. And `install
-r` twice left Play services not delivering to the freshly replaced process for one recording attempt;
it recovered on the next start, and it is worth knowing that a zero-point recording immediately after an
update may be that rather than a bug.

0.8 was verified on the same API 36 emulator, as a debug build and then as the signed, minified
release APK, with the app kept in the background — the launcher on top and the recorder's process at
oom adj 700 — for every HTTP call, since that is the situation the whole change is about.

**The exemption is a real gate now, and it is refused before a page is misled rather than after.**
With location, background location and notifications all granted but the exemption revoked, `/status`
answers `"batteryExempt":false` and `"canRecord":false`, and `POST /start` answers `403` with
`"error":"setup incomplete"` in the same body that names the missing item. Granting it flips both to
true, and the same `POST /start` then answers `200` with `"recording":true` from the background —
`tracking started in segment 1` — which is the `409` the old build risked, gone. A stop and a start
over HTTP with the app still backgrounded are both `200`, and the second start opens `segment 2` with
the four fixes fed during the stopped window absent: 13 points before, 13 after, then the new segment.

The gate is on starting and not on a recording already under way, which the split between
`Setup.canRecord` and `Setup.canKeepRecording` is for. Revoking the exemption mid-recording leaves
`"recording":true` and the points still arriving while `POST /start` turns to `403`; killing the
process with `run-as kill -9` then had the service come back at `tracking started in segment 3, 30
points stored` rather than stopping. Ending a live track over a setting changed while it ran would
have lost it.

Pause and resume are gone: `POST /pause` and `POST /resume` answer `404 no such endpoint` on both
builds, `/status` and the `status` event carry no `paused` key, and the notification reports
`actions=1` with the title *Recording track*. The app's own screen has no pause button, and the
`checkApiDocs` count fell from 7 endpoints to 5 with the status fields at 27.

The checklist shows the battery row as blocking: red ✗, *Required*, and **START** disabled with all
three location and notification items ✓. A `freemap-gps-recorder://start` link fired with the exemption
outstanding no longer starts — it echoes `portEcho` and opens that row's explanation instead — and
granting it from the dialog it raises fulfils the pending link on the way back, `tracking started in
segment 4`, with focus handed to the launcher again.

The minified release APK behaves identically, which is the thing worth checking: `"priority":"high"`,
`"src":"fused"` and the fifteen-field header all survive R8, and start/stop/start/stop from the
background are `200` throughout.
