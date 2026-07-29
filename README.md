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
notification carrying a **Stop** action. It requests fixes at 1 Hz with
`PRIORITY_HIGH_ACCURACY` and holds a partial wake lock for the duration of the recording — without
it, delivery becomes unreliable once the screen goes off.

Fixes are delivered onto a dedicated `HandlerThread`, so SQLite writes never touch the main thread.
`PointStore` appends each one through a single reused compiled statement.

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
| Unrestricted battery use | plain-language explanation, then `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | no |
| Vendor app restrictions | vendor-specific guidance, plus a shortcut to that vendor's own screen | no |

Start is blocked until location and notifications are granted — without the first there is nothing
to record, and without the second a recording would run with no visible sign of it, which is the one
thing a foreground service exists to prevent. `POST /start` refuses the same two with `403` and
`"error":"setup incomplete"`.

`RecordingService` checks the same gate in `onStartCommand` and stops instead of starting. That is not
a formality: revoking a permission kills the app process, `START_STICKY` has the system restart the
service, and Play services does not fail the location subscription synchronously — so without the
check the service comes back and sits in the notification claiming to record while appending nothing.

The rest only make a recording *survive*, so they never block anything; while any of them is
outstanding a banner says as much and the checklist stays on screen. Once everything is resolved
both disappear and the screen is just state, count and button again.

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
unreported speed and a genuine standstill are different facts.

`seq` is SQLite `AUTOINCREMENT`, so ids are never reused even after `DELETE /track`. That is what
makes clearing safe for clients: a stale `?since=` can come back empty, but it can never come back
with *different* points wearing ids the client already has. `generation` in `/status` is the signal
that a clear happened at all.

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

This is also the way to start recording that always works. `POST /start` needs the
battery-optimisation exemption below, because it is a background start; a link makes the app
foreground, so the foreground-service start is permitted with no exemption and no extra dialog —
the exemption prompt is deliberately skipped on this path so nothing interrupts the hand-back.

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

`POST /start` while the app is backgrounded needs the app to be exempt from battery optimisation —
without it Android 12+ rejects the foreground-service start with
`ForegroundServiceStartNotAllowedException`, which the endpoint reports as a `409` with the exception
name in `error`. Declining the exemption costs only remote start, not recording, which is why it is
a non-blocking [checklist](#setup) item; `/status` reports it as `batteryExempt`.

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
the version, run `./gradlew releaseApk`, then upload both files — the APK as
`freemap-gps-recorder.apk` and the manifest as `latest.json`. Upload the APK **first**, or a phone that
checks in between will offer a download that 404s.

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
| `altitude` | metres, `NULL` when the fix has none |
| `accuracy` | metres, `NULL` when the fix has none |
| `speed` | m/s, `NULL` when the fix has none |
| `bearing` | degrees, `NULL` when the fix has none |

Optional fields are stored as `NULL` rather than `0` when the platform reports them as absent, so a
stationary fix is not mistaken for one heading due north.

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
cold process and with the app already open, and with the battery-optimisation exemption *removed*:
recording started in both cases and the browser was the resumed activity again within seconds, with
no dialog in between. When the app was already open its task survived the hand-back; when the link
had created the task, no activity was left behind. `?port=` came back as `portEcho`, and a
mismatched port was logged rather than acted on.

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
back with `recording:true` and `lastSeq` frozen. With the gate in `onStartCommand` the restart logs
`cannot record: location or notification permission missing, stopping` and leaves no service record,
and no `ForegroundServiceDidNotStartInTimeException` for stopping before going foreground.

On the phone, vendor detection reports `"oem":{"vendor":"xiaomi","needed":true}`, and setting the
acknowledgement flips `acknowledged` and `setupComplete` together. The vendor dialog's own buttons
are the one thing not exercised by script, for the `INJECT_EVENTS` reason above.

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
