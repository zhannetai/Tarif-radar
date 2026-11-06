package ru.tarifradar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Reading(val ts: Long, val block: String, val price: Double)

/** База замеров: время, блок, тариф. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "tariffs.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings (ts INTEGER NOT NULL, block TEXT NOT NULL, price REAL NOT NULL)")
        db.execSQL("CREATE INDEX idx_readings_ts ON readings(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(ts: Long, prices: Map<String, Double>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((block, price) in prices) {
                db.insert("readings", null, ContentValues().apply {
                    put("ts", ts); put("block", block); put("price", price)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun since(fromTs: Long): List<Reading> {
        val out = ArrayList<Reading>()
        readableDatabase.rawQuery(
            "SELECT ts, block, price FROM readings WHERE ts >= ? ORDER BY ts",
            arrayOf(fromTs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Reading(c.getLong(0), c.getString(1), c.getDouble(2)))
        }
        return out
    }

    /** Последний замер: время и тарифы по блокам. */
    fun latest(): Pair<Long, Map<String, Double>>? {
        val db = readableDatabase
        val ts = db.rawQuery("SELECT MAX(ts) FROM readings", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: return null
        val map = LinkedHashMap<String, Double>()
        db.rawQuery("SELECT block, price FROM readings WHERE ts = ? ORDER BY block", arrayOf(ts.toString())).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getDouble(1)
        }
        return ts to map
    }

    fun blocks(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT block FROM readings ORDER BY block", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun snapshotCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT ts) FROM readings", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM readings")
    }
}
