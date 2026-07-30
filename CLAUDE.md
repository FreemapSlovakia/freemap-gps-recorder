# Working on this repo

Operational notes — the things that cost time when rediscovered. [README.md](README.md) explains how
the app works and why; [API.md](API.md) is the HTTP contract and is checked against the code.

## Build and check

```sh
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew installDebug           # build and install on the attached device
./gradlew check                  # lint + checkApiDocs
./gradlew releaseApk             # signed, shrunk, plus the latest.json to publish
```

`check` and `releaseApk` both depend on `checkApiDocs`, which fails when API.md and the sources
disagree on endpoints, `/status` fields or point fields. Run it after touching `route()`,
`statusJson()` or `Point.FIELDS` — it compares names, not meaning, so the prose still needs reading.

Comment-only edits leave `assembleRelease` UP-TO-DATE with an APK older than the sources. That is
Gradle being right, not stale output, but for something you are about to publish use
`./gradlew clean releaseApk` so the artefact is unambiguous.

## Testing on the emulator

UI verification runs on the `Pixel6a_API36` AVD, because the Xiaomi test phone rejects
`adb shell input` with `SecurityException: INJECT_EVENTS`. The phone is still the only place the
vendor checklist row appears — `ro.product.manufacturer` is read-only and cannot be faked.

```sh
~/Android/Sdk/emulator/emulator -avd Pixel6a_API36 -no-snapshot-load -no-boot-anim &   # not on PATH
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
```

`-no-snapshot-load`, or a stale snapshot comes back. The AVD is a production Play image: no `adb
root`, and a release APK is not debuggable, so neither `run-as` nor root reads app prefs — `pm clear`
is how you reset state.

**Pick one signing flavour per round of testing.** A debug build will not install over a
release-signed one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the uninstall that fixes it takes the
recorded test track with it. If you need both, do all the debug work first.

```sh
adb forward tcp:8378 tcp:8378                    # re-run after EVERY (re)install — it does not survive
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant sk.freemap.gpsrecorder android.permission.$p
done
adb shell dumpsys deviceidle whitelist +sk.freemap.gpsrecorder   # grant the battery exemption
adb shell dumpsys deviceidle whitelist -sk.freemap.gpsrecorder   # revoke it
adb emu geo fix 21.2367 48.7062 279              # feed one fix (lon lat alt)
```

The exemption is a hard gate on recording (`Setup.canRecord`), so `dumpsys deviceidle whitelist` is
now part of most test setups. Granting it in the app's own UI needs two taps: the app's dialog, then
Android's *Let app always run in background?*.

Anything about starting from the web has to be tested **with the app backgrounded**, since that is the
case that used to fail. `adb shell input keyevent KEYCODE_HOME`, then confirm it really is background
rather than merely not visible:

```sh
adb shell dumpsys activity activities | grep -m1 topResumedActivity    # expect the launcher
adb shell dumpsys activity processes | grep -A2 gpsrecorder | grep oom:   # expect cur=700, not 100
```

Other things worth knowing:

- notification actions: `adb shell dumpsys notification --noredact | grep -A40 gpsrecorder` — look for
  `actions=N` and `android.title`
- forcing the `START_STICKY` restart: `adb shell run-as <pkg> kill -9 $(adb shell pidof <pkg>)`.
  `am kill` will not touch a foreground service
- `priority: "balanced"` records nothing on this AVD — its `network_location_provider` is disabled
- `install -r` twice can leave Play services not delivering to the replaced process for one recording
  attempt; it recovers on the next start

## Releasing

The procedure is in [README.md](README.md#distribution-and-updates): set `recorder.releaseNotes`, bump
`recorder.versionCode` **and** `recorder.versionName`, `./gradlew clean releaseApk`, then upload the
APK **before** `latest.json` or a phone checking in between is offered a 404. The upload target is not
in this repo — see the maintainer's deployment notes.

Upload each file to a temp name and `mv` it into place, so no phone can fetch a half-written APK:

```sh
scp -q freemap-gps-recorder-<v>.apk <target>/.freemap-gps-recorder.apk.tmp
ssh <host> 'cd <dir> && mv .freemap-gps-recorder.apk.tmp freemap-gps-recorder.apk'
```

Then verify what the server actually serves, not what you uploaded:

```sh
curl -s https://download.freemap.sk/freemap-gps-recorder/latest.json
curl -s https://download.freemap.sk/freemap-gps-recorder/freemap-gps-recorder.apk | sha256sum
```

Check the signer matches the published build before uploading, or the update installs for nobody:

```sh
$ANDROID_HOME/build-tools/*/apksigner verify --print-certs <apk> | grep -i 'SHA-256'
```

Two traps that have already cost a wrong conclusion:

- **`adb install` writes failures to stderr and `Performing Streamed Install` to stdout**, so
  `| tail -1` can look like success when the file was never installed. Grep for `Success`.
- **The `Bash` tool's working directory persists between calls.** A `cd` earlier in a session silently
  breaks later relative paths. Use absolute paths for anything that installs or uploads.

The end-to-end check is worth doing: install the previously published APK, launch it, and confirm the
update prompt appears with the new notes (`adb logcat -d -s UpdateCheck:*`), then `install -r` the new
one and confirm `count`/`lastSeq` survive.
