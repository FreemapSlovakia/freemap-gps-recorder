package sk.freemap.tracker

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.location.Location

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
                seq       INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        INTEGER NOT NULL,
                lat       REAL    NOT NULL,
                lon       REAL    NOT NULL,
                altitude  REAL,
                accuracy  REAL,
                speed     REAL,
                bearing   REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX points_ts ON points (ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Appends [loc] and returns the stored row. Safe to call from a background thread; the lock is
     * only there because the compiled statement is shared.
     */
    @Synchronized
    fun append(loc: Location): Point {
        val st = insert ?: writableDatabase.compileStatement(INSERT_SQL).also { insert = it }
        st.clearBindings()
        st.bindLong(1, loc.time)
        st.bindDouble(2, loc.latitude)
        st.bindDouble(3, loc.longitude)
        bindOrNull(st, 4, loc.hasAltitude(), loc.altitude)
        bindOrNull(st, 5, loc.hasAccuracy(), loc.accuracy.toDouble())
        bindOrNull(st, 6, loc.hasSpeed(), loc.speed.toDouble())
        bindOrNull(st, 7, loc.hasBearing(), loc.bearing.toDouble())

        return Point(
            seq = st.executeInsert(),
            ts = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            alt = loc.altitude.takeIf { loc.hasAltitude() },
            acc = loc.accuracy.toDouble().takeIf { loc.hasAccuracy() },
            spd = loc.speed.toDouble().takeIf { loc.hasSpeed() },
            brg = loc.bearing.toDouble().takeIf { loc.hasBearing() },
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

    /**
     * Drops every recorded point and returns the new [generation].
     *
     * `seq` is AUTOINCREMENT, so the next fix carries on above the highest id ever handed out rather
     * than restarting at 1. That is what stops a client polling with a stale `since` from being
     * served a *different* set of points under ids it already believes it has.
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
        alt = if (isNull(4)) null else getDouble(4),
        acc = if (isNull(5)) null else getDouble(5),
        spd = if (isNull(6)) null else getDouble(6),
        brg = if (isNull(7)) null else getDouble(7),
    )

    private fun bindOrNull(st: SQLiteStatement, index: Int, present: Boolean, value: Double) {
        if (present) st.bindDouble(index, value) else st.bindNull(index)
    }

    companion object {
        const val DB_NAME = "track.db"
        private const val DB_VERSION = 1

        private const val PREFS = "track"
        private const val KEY_GENERATION = "generation"

        private const val INSERT_SQL =
            "INSERT INTO points (ts, lat, lon, altitude, accuracy, speed, bearing) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"

        private const val SELECT_SINCE =
            "SELECT seq, ts, lat, lon, altitude, accuracy, speed, bearing " +
                "FROM points WHERE seq > ? ORDER BY seq"

        private const val SELECT_MAX_SEQ = "SELECT IFNULL(MAX(seq), 0) FROM points"

        @Volatile
        private var instance: PointStore? = null

        fun get(context: Context): PointStore =
            instance ?: synchronized(this) {
                instance ?: PointStore(context).also { instance = it }
            }
    }
}
