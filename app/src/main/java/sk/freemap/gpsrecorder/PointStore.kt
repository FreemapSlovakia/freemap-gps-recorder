package sk.freemap.gpsrecorder

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.location.Location
import android.os.Build

/**
 * On-disk store for recorded fixes. One row per fix, `seq` is a monotonically increasing id handed
 * out by SQLite (AUTOINCREMENT, so it never gets reused after a delete).
 *
 * A single process-wide instance stays open for the process lifetime: the HTTP API serves the track
 * whether or not a recording is in progress, so ownership can't sit with the service. WAL is on, so
 * those reads run concurrently with the recording thread's inserts instead of queueing behind them.
 */
class PointStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    private var insert: SQLiteStatement? = null

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points (
                seq               INTEGER PRIMARY KEY AUTOINCREMENT,
                ts                INTEGER NOT NULL,
                lat               REAL    NOT NULL,
                lon               REAL    NOT NULL,
                altitude          REAL,
                accuracy          REAL,
                speed             REAL,
                bearing           REAL,
                ${V2_COLUMNS.joinToString(",\n                ")}
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX points_ts ON points (ts)")
    }

    /**
     * Version 2 added the columns behind `altMsl`, the per-component accuracies, `sat`, `src` and
     * `seg`. Rows recorded before it keep `NULL` in all of them and segment `0` — an honest "not
     * known" rather than an invented value — and an upgrade never rewrites or drops a recorded
     * track, which someone may have been in the middle of when the update landed.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            for (column in V2_COLUMNS) db.execSQL("ALTER TABLE points ADD COLUMN $column")
        }
    }

    /**
     * Appends [loc] as part of [segment] and returns the stored row. Safe to call from a background
     * thread; the lock is only there because the compiled statement is shared.
     *
     * [satellites] and [geoidSeparationM] both come from [GnssMonitor], because a fused [loc] carries
     * neither.
     */
    @Synchronized
    fun append(loc: Location, segment: Long, satellites: Int?, geoidSeparationM: Double?): Point {
        val alt = loc.altitude.takeIf { loc.hasAltitude() }
        val acc = loc.accuracy.toDouble().takeIf { loc.hasAccuracy() }
        val spd = loc.speed.toDouble().takeIf { loc.hasSpeed() }
        val brg = loc.bearing.toDouble().takeIf { loc.hasBearing() }
        // GPX `<ele>` is metres above mean sea level, which is not what getAltitude() returns; over
        // Slovakia the geoid separation is some +42 m.
        //
        // Play services' fused client hands over a Location with hasMslAltitude() false even on
        // Android 14+, where the platform's own providers have the figure — so the value is taken
        // where it is available and otherwise reconstructed from this fix's own altitude and the
        // separation GnssMonitor read off a raw GNSS fix. Both are absent below API 34, and on a
        // recording that never sees a GNSS fix at all.
        val ownMsl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            loc.mslAltitudeMeters.takeIf { loc.hasMslAltitude() }
        } else {
            null
        }
        val altMsl = ownMsl ?: geoidSeparationM?.let { separation -> alt?.minus(separation) }
        val altAcc = loc.verticalAccuracyMeters.toDouble().takeIf { loc.hasVerticalAccuracy() }
        val spdAcc = loc.speedAccuracyMetersPerSecond.toDouble().takeIf { loc.hasSpeedAccuracy() }
        val brgAcc = loc.bearingAccuracyDegrees.toDouble().takeIf { loc.hasBearingAccuracy() }
        val src = loc.provider

        val st = insert ?: writableDatabase.compileStatement(INSERT_SQL).also { insert = it }
        st.clearBindings()
        st.bindLong(1, loc.time)
        st.bindDouble(2, loc.latitude)
        st.bindDouble(3, loc.longitude)
        st.bindOrNull(4, alt)
        st.bindOrNull(5, acc)
        st.bindOrNull(6, spd)
        st.bindOrNull(7, brg)
        st.bindOrNull(8, altMsl)
        st.bindOrNull(9, altAcc)
        st.bindOrNull(10, spdAcc)
        st.bindOrNull(11, brgAcc)
        st.bindOrNull(12, satellites?.toLong())
        st.bindOrNull(13, src)
        st.bindLong(14, segment)

        return Point(
            seq = st.executeInsert(),
            ts = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            alt = alt,
            acc = acc,
            spd = spd,
            brg = brg,
            altMsl = altMsl,
            altAcc = altAcc,
            spdAcc = spdAcc,
            brgAcc = brgAcc,
            sat = satellites,
            src = src,
            seg = segment,
        )
    }

    /**
     * Feeds every point with `seq` greater than [since] to [consumer], in `seq` order. Streamed off
     * the cursor rather than materialised, so a long track doesn't have to fit in memory twice.
     */
    fun forEachSince(since: Long, consumer: (Point) -> Unit) {
        readableDatabase.rawQuery(SELECT_SINCE, arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) consumer(c.toPoint())
        }
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "points")

    fun maxSeq(): Long = DatabaseUtils.longForQuery(readableDatabase, SELECT_MAX_SEQ, null)

    /**
     * How many times the track has been thrown away. A client holding a copy compares this against
     * what it saw last: unchanged means its points are still the same points, and a change means the
     * track it holds no longer exists and has to be fetched again from scratch.
     */
    fun generation(): Long = prefs.getLong(KEY_GENERATION, 0L)

    /** The segment the last start or resume opened — what a fix recorded right now belongs to. */
    fun segment(): Long = prefs.getLong(KEY_SEGMENT, 0L)

    /**
     * Opens the next segment and returns its ordinal. Called on every start and every resume, so a
     * point whose `seg` differs from its predecessor's is the first of a new segment — which is what
     * a client needs in order not to draw a straight line across a lunch break.
     *
     * It lives in preferences rather than being derived from the track, because a process kill and
     * the `START_STICKY` restart that follows it is exactly the break nothing else would record: the
     * recording carries on, and the gap in the middle of it is real. Written with `commit` rather than
     * `apply` for that same reason — surviving a kill is the whole job, and an ordinal still queued
     * for a background write when the process dies would be handed out twice, hiding the break.
     */
    @Synchronized
    fun nextSegment(): Long {
        val segment = segment() + 1
        prefs.edit().putLong(KEY_SEGMENT, segment).commit()
        return segment
    }

    /**
     * Drops every recorded point and returns the new [generation].
     *
     * `seq` is AUTOINCREMENT, so the next fix carries on above the highest id ever handed out rather
     * than restarting at 1. That is what stops a client polling with a stale `since` from being
     * served a *different* set of points under ids it already believes it has. `seg` carries on for
     * the same reason, and because nothing needs it to start anywhere in particular.
     *
     * VACUUM is the point of the exercise rather than housekeeping: deleted rows would otherwise
     * leave the file as large as the track that was meant to be gone.
     */
    @Synchronized
    fun clear(): Long {
        val db = writableDatabase
        db.delete("points", null, null)
        db.execSQL("VACUUM")
        val generation = generation() + 1
        prefs.edit().putLong(KEY_GENERATION, generation).commit()
        return generation
    }

    private fun Cursor.toPoint() = Point(
        seq = getLong(0),
        ts = getLong(1),
        lat = getDouble(2),
        lon = getDouble(3),
        alt = doubleOrNull(4),
        acc = doubleOrNull(5),
        spd = doubleOrNull(6),
        brg = doubleOrNull(7),
        altMsl = doubleOrNull(8),
        altAcc = doubleOrNull(9),
        spdAcc = doubleOrNull(10),
        brgAcc = doubleOrNull(11),
        sat = if (isNull(12)) null else getInt(12),
        src = if (isNull(13)) null else getString(13),
        seg = getLong(14),
    )

    private fun Cursor.doubleOrNull(index: Int) = if (isNull(index)) null else getDouble(index)

    private fun SQLiteStatement.bindOrNull(index: Int, value: Double?) {
        if (value == null) bindNull(index) else bindDouble(index, value)
    }

    private fun SQLiteStatement.bindOrNull(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun SQLiteStatement.bindOrNull(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    companion object {
        const val DB_NAME = "track.db"
        private const val DB_VERSION = 2

        private const val PREFS = "track"
        private const val KEY_GENERATION = "generation"
        private const val KEY_SEGMENT = "segment"

        /**
         * The columns version 2 added, shared by [onCreate] and [onUpgrade] so a fresh database and
         * an upgraded one cannot end up with different schemas. `segment` carries a default for the
         * upgrade's sake — `ALTER TABLE ADD COLUMN` needs one to be `NOT NULL` — and keeping it on
         * the fresh path too is what makes the two identical.
         */
        private val V2_COLUMNS = listOf(
            "altitude_msl      REAL",
            "altitude_accuracy REAL",
            "speed_accuracy    REAL",
            "bearing_accuracy  REAL",
            "satellites        INTEGER",
            "provider          TEXT",
            "segment           INTEGER NOT NULL DEFAULT 0",
        )

        private const val COLUMNS =
            "ts, lat, lon, altitude, accuracy, speed, bearing, altitude_msl, altitude_accuracy, " +
                "speed_accuracy, bearing_accuracy, satellites, provider, segment"

        private const val INSERT_SQL =
            "INSERT INTO points ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private const val SELECT_SINCE =
            "SELECT seq, $COLUMNS FROM points WHERE seq > ? ORDER BY seq"

        private const val SELECT_MAX_SEQ = "SELECT IFNULL(MAX(seq), 0) FROM points"

        @Volatile
        private var instance: PointStore? = null

        fun get(context: Context): PointStore =
            instance ?: synchronized(this) {
                instance ?: PointStore(context).also { instance = it }
            }
    }
}
