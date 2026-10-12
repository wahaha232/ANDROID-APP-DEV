package com.startinsnow.gpstracker.data.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 備份檔（ZIP）的建立／檢查／還原。只用 java.io / java.util.zip，沒有 Android 相依，可以直接單元測試。
 *
 * 內容：`gps_tracker.db`（Room 資料庫，建立前已把 WAL 併回主檔）、`photos/<trackId>/<檔名>`（照片）、README.txt。
 * **不包含設定與 GitHub Token**：Token 是機密，不該被打包後到處傳。
 */
object BackupArchive {

    const val DB_ENTRY = "gps_tracker.db"
    const val PHOTOS_PREFIX = "photos/"
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    data class Info(val dbSizeBytes: Long, val photoCount: Int, val dbUserVersion: Int)

    fun create(dbFile: File, photosDir: File?, out: File, readme: String) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            addFile(zip, DB_ENTRY, dbFile)
            if (photosDir != null && photosDir.isDirectory) {
                photosDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    addFile(zip, PHOTOS_PREFIX + f.relativeTo(photosDir).invariantSeparatorsPath, f)
                }
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** 檢查這是不是本 App 的備份：要有資料庫、且資料庫檔頭正確；回傳資料庫的 schema 版本供呼叫端判斷是否太新。 */
    fun inspect(zip: File): Result<Info> = runCatching {
        ZipFile(zip).use { z ->
            val entry = z.getEntry(DB_ENTRY) ?: error("備份檔裡找不到資料庫（gps_tracker.db）")
            val header = ByteArray(100)
            z.getInputStream(entry).use { ins ->
                var read = 0
                while (read < header.size) {
                    val n = ins.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                check(read == header.size) { "資料庫檔案不完整" }
            }
            check(header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) { "不是有效的 SQLite 資料庫" }
            Info(
                dbSizeBytes = entry.size,
                photoCount = z.entries().asSequence().count { !it.isDirectory && it.name.startsWith(PHOTOS_PREFIX) },
                dbUserVersion = ByteBuffer.wrap(header, 60, 4).int // SQLite 檔頭：offset 60 = user_version（大端序）
            )
        }
    }

    /**
     * 覆蓋還原：先解壓到暫存資料夾、確認完整，才刪掉現有資料並換成備份內容，避免中途失敗把現有資料弄壞。
     * 解壓時檢查路徑（zip-slip）：任何跳出暫存資料夾的項目一律拒絕。
     */
    fun restore(zip: File, dbFile: File, photosDir: File?, stagingDir: File) {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val stagingRoot = stagingDir.canonicalFile
        try {
            ZipFile(zip).use { z ->
                z.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val target = File(stagingRoot, entry.name).canonicalFile
                    require(target.path.startsWith(stagingRoot.path + File.separator)) { "備份檔含有不安全的路徑：${entry.name}" }
                    target.parentFile?.mkdirs()
                    z.getInputStream(entry).use { ins -> target.outputStream().use { ins.copyTo(it) } }
                }
            }
            val stagedDb = File(stagingRoot, DB_ENTRY)
            require(stagedDb.isFile) { "備份檔裡找不到資料庫" }

            // 解壓都成功了才動現有資料。
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
            stagedDb.copyTo(dbFile, overwrite = true)
            if (photosDir != null) {
                photosDir.deleteRecursively()
                photosDir.mkdirs()
                File(stagingRoot, "photos").takeIf { it.isDirectory }?.copyRecursively(photosDir, overwrite = true)
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }
}
