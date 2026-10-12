package com.startinsnow.gpstracker.data.backup

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假的 SQLite 檔：100 位元組檔頭（魔術字串 + offset 60 的 user_version）加一些內容。 */
    private fun fakeDb(version: Int = 2, magicOk: Boolean = true, body: String = "rows"): File {
        val header = ByteArray(100)
        val magic = (if (magicOk) "SQLite format 3\u0000" else "not a database!!").toByteArray(Charsets.ISO_8859_1)
        magic.copyInto(header)
        ByteBuffer.wrap(header).putInt(60, version)
        return tmp.newFile().also { it.writeBytes(header + body.toByteArray()) }
    }

    private fun photosDir(): File {
        val dir = tmp.newFolder("photos")
        File(dir, "track-1").mkdirs()
        File(dir, "track-1/START_1.jpg").writeText("jpg-1")
        File(dir, "track-1/IMG_2.jpg").writeText("jpg-2")
        File(dir, "track-2").mkdirs()
        File(dir, "track-2/END_3.jpg").writeText("jpg-3")
        return dir
    }

    @Test
    fun createThenInspect_reportsDbVersionAndPhotoCount() {
        val out = tmp.newFile("b.zip")
        BackupArchive.create(fakeDb(version = 2), photosDir(), out, "readme")
        val info = BackupArchive.inspect(out).getOrThrow()
        assertEquals(2, info.dbUserVersion)
        assertEquals(3, info.photoCount)
    }

    @Test
    fun restore_replacesExistingDataAndRestoresPhotos() {
        val out = tmp.newFile("b.zip")
        BackupArchive.create(fakeDb(body = "from-backup"), photosDir(), out, "readme")

        // 另一支手機上的「現有資料」：不同的資料庫內容與一張不在備份裡的照片
        val liveDb = File(tmp.newFolder("databases"), "gps_tracker.db").also { it.writeText("old-db") }
        File(liveDb.path + "-wal").writeText("old-wal")
        val livePhotos = tmp.newFolder("livePhotos")
        File(livePhotos, "stale.jpg").writeText("stale")

        BackupArchive.restore(out, liveDb, livePhotos, File(tmp.root, "staging"))

        assertTrue(liveDb.readText(Charsets.ISO_8859_1).endsWith("from-backup"))
        assertFalse("old WAL must not be applied on top of the restored database", File(liveDb.path + "-wal").exists())
        assertEquals("jpg-1", File(livePhotos, "track-1/START_1.jpg").readText())
        assertEquals("jpg-3", File(livePhotos, "track-2/END_3.jpg").readText())
        assertFalse("photos not in the backup are replaced", File(livePhotos, "stale.jpg").exists())
        assertFalse("staging folder is cleaned up", File(tmp.root, "staging").exists())
    }

    @Test
    fun inspect_rejectsAFileThatIsNotADatabase() {
        val out = tmp.newFile("bad.zip")
        BackupArchive.create(fakeDb(magicOk = false), null, out, "readme")
        assertTrue(BackupArchive.inspect(out).isFailure)
    }

    @Test
    fun inspect_rejectsAZipWithoutTheDatabase() {
        val out = tmp.newFile("nodb.zip")
        ZipOutputStream(out.outputStream()).use { z -> z.putNextEntry(ZipEntry("README.txt")); z.write(1); z.closeEntry() }
        assertTrue(BackupArchive.inspect(out).isFailure)
    }

    @Test
    fun restore_refusesPathsThatEscapeTheStagingFolder_andLeavesCurrentDataAlone() {
        val evil = tmp.newFile("evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(BackupArchive.DB_ENTRY)); z.write(fakeDb().readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("../../escaped.txt")); z.write("pwned".toByteArray()); z.closeEntry()
        }
        val liveDb = File(tmp.newFolder("databases2"), "gps_tracker.db").also { it.writeText("keep-me") }

        val failed = runCatching { BackupArchive.restore(evil, liveDb, null, File(tmp.root, "staging2")) }.isFailure

        assertTrue(failed)
        assertEquals("existing data is untouched when the archive is rejected", "keep-me", liveDb.readText())
        assertFalse(File(tmp.root.parentFile, "escaped.txt").exists())
    }
}
