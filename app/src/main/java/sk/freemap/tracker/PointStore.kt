package sk.freemap.tracker

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.location.Location
import java.io.Closeable

/**
 * On-disk store for recorded fixes. One row per fix, `seq` is a monotonically increasing
 * id handed out by SQLite (AUTOINCREMENT, so it never gets reused after a delete).
 */
class PointStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
), Closeable {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    private var insert: SQLiteStatement? = null

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

    /** Appends [loc] and returns its `seq`. Safe to call from a background thread. */
    @Synchronized
    fun append(loc: Location): Long {
        val st = insert ?: writableDatabase.compileStatement(INSERT_SQL).also { insert = it }
        st.clearBindings()
        st.bindLong(1, loc.time)
        st.bindDouble(2, loc.latitude)
        st.bindDouble(3, loc.longitude)
        bindOrNull(st, 4, loc.hasAltitude(), loc.altitude)
        bindOrNull(st, 5, loc.hasAccuracy(), loc.accuracy.toDouble())
        bindOrNull(st, 6, loc.hasSpeed(), loc.speed.toDouble())
        bindOrNull(st, 7, loc.hasBearing(), loc.bearing.toDouble())
        return st.executeInsert()
    }

    @Synchronized
    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "points")

    @Synchronized
    override fun close() {
        insert?.close()
        insert = null
        super.close()
    }

    private fun bindOrNull(st: SQLiteStatement, index: Int, present: Boolean, value: Double) {
        if (present) st.bindDouble(index, value) else st.bindNull(index)
    }

    companion object {
        const val DB_NAME = "track.db"
        private const val DB_VERSION = 1

        private const val INSERT_SQL =
            "INSERT INTO points (ts, lat, lon, altitude, accuracy, speed, bearing) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
    }
}
