# Freemap Tracker

Android GPS track recorder. Records a high-accuracy location stream from a foreground service and
appends every fix to on-disk SQLite, so recording survives backgrounding and screen lock.

This is an early skeleton: the UI is deliberately just recording state, a start/stop button and a
live point count. The first-run UX, track management and upload come later.

## Requirements

- Android 8.0 (API 26) or newer
- Google Play services (uses `FusedLocationProviderClient`)

## Build

```sh
./gradlew assembleDebug     # APK in app/build/outputs/apk/debug/
./gradlew installDebug      # build and install on the attached device
```

Toolchain: AGP 9.2.1, Gradle 9.4.1, JDK 17+, compileSdk/targetSdk 36, minSdk 26.

## How it works

`TrackingService` is a foreground service with `foregroundServiceType="location"` and a persistent
notification carrying a **Stop** action. It requests fixes at 1 Hz with
`PRIORITY_HIGH_ACCURACY` and holds a partial wake lock for the duration of the recording — without
it, delivery becomes unreliable once the screen goes off.

Fixes are delivered onto a dedicated `HandlerThread`, so SQLite writes never touch the main thread.
`PointStore` appends each one through a single reused compiled statement.

Permissions are requested inline when Start is pressed: fine + coarse location (plus
`POST_NOTIFICATIONS` on Android 13+) in one request, then background location as a **separate**
follow-up — the system silently drops a background-location request that is bundled with the
foreground ones. Denying background location still starts recording, since a foreground service
started from the foreground does not require it.

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
adb shell run-as sk.freemap.tracker cat databases/track.db > track.db
sqlite3 track.db 'SELECT * FROM points ORDER BY seq DESC LIMIT 10;'
```

Note that WAL is enabled, so pull `track.db-wal` alongside it to see the most recent commits.

## Status

Verified on a Xiaomi device running Android 16 (API 36): with the app backgrounded and the screen
locked, points appended continuously for over 9 minutes at 1 Hz — `seq` contiguous, no gap
above 1.2 s. Stopping from the notification action tears down the service, releases the wake lock
and removes the notification, leaving the recorded points intact.
