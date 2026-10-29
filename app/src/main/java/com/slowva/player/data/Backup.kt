package com.slowva.player.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.slowva.player.data.db.SlowvaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One zip with the database and the small settings files, written and read through
 * the Storage Access Framework, so a reinstall or a lost signing key keeps the user's data.
 */
object Backup {
    private const val DB_ENTRY = "slowva.db"
    private const val PREFS_DIR = "prefs/"
    /** effects, appearance and network switches; library state lives in the database */
    private val PREF_FILES = listOf("slowva-fx.xml", "slowva-ui.xml", "slowva-net.xml", "slowva-sound.xml")
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

    suspend fun export(context: Context, db: SlowvaDatabase, target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // fold the WAL into the main file so one file is a complete copy
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Can't write there")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                context.getDatabasePath(SlowvaDatabase.NAME).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                PREF_FILES.map { File(prefsDir(context), it) }.filter { it.exists() }.forEach { f ->
                    zip.putNextEntry(ZipEntry(PREFS_DIR + f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Checks the zip, then replaces the database and settings. The caller must end the
     * process right after, because the open database and cached settings are now stale.
     */
    suspend fun restore(context: Context, db: SlowvaDatabase, source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
            try {
                val input = context.contentResolver.openInputStream(source) ?: error("Can't read that file")
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val allowed = e.name == DB_ENTRY || PREF_FILES.any { e.name == PREFS_DIR + it }
                        if (!allowed || e.isDirectory) continue
                        val f = File(staging, e.name.removePrefix(PREFS_DIR))
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_ENTRY_BYTES) error("Backup file is too large")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                val newDb = File(staging, DB_ENTRY)
                require(newDb.exists()) { "Not a Slowva backup" }
                checkDatabase(newDb)

                db.close()
                val dbFile = context.getDatabasePath(SlowvaDatabase.NAME)
                listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
                newDb.copyTo(dbFile, overwrite = true)
                PREF_FILES.forEach { name ->
                    File(staging, name).takeIf { it.exists() }?.copyTo(File(prefsDir(context), name), overwrite = true)
                }
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** Refuses files that aren't SQLite or come from a newer Slowva with a schema this one can't read. */
    private fun checkDatabase(file: File) {
        val header = file.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        require(String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) { "Not a Slowva backup" }
        val version = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        val current = SlowvaDatabase.VERSION
        require(version in 1..current) { "This backup is from a newer Slowva (database v$version, this app reads up to v$current)" }
    }

    private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")
}
