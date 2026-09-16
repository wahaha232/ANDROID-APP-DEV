package com.startinsnow.gpstracker.export

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 對應規格「44. Google Drive Folder」與「53. Export」。
 * 產出符合 GPS TRACKER/yyyy/MM/yyyy-MM-dd_HHmmss/ 結構的本機資料夾（供 DriveSyncManager 上傳），
 * 並可額外打包成單一 ZIP 供使用者手動分享。
 */
object TrackZipExporter {

    private val folderFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    private val yearFmt = SimpleDateFormat("yyyy", Locale.US)
    private val monthFmt = SimpleDateFormat("MM", Locale.US)

    fun relativeDrivePath(startTimeMs: Long): String {
        val date = java.util.Date(startTimeMs)
        return "GPS TRACKER/${yearFmt.format(date)}/${monthFmt.format(date)}/${folderFmt.format(date)}"
    }

    /** 在 App 私有儲存空間建立完整匯出資料夾（index.html + track.gpx + track.json + photos/）。 */
    fun buildExportDirectory(baseDir: File, data: ExportTrackData): File {
        val exportRoot = File(baseDir, "exports/${data.track.trackId}")
        exportRoot.mkdirs()
        File(exportRoot, "index.html").writeText(TrackHtmlGenerator.generate(data), Charsets.UTF_8)
        File(exportRoot, "track.gpx").writeText(GpxExporter.generate(data), Charsets.UTF_8)
        File(exportRoot, "track.json").writeText(JsonExporter.generate(data), Charsets.UTF_8)
        File(exportRoot, "track.csv").writeText(CsvExporter.generate(data), Charsets.UTF_8)

        val photosDir = File(exportRoot, "photos").apply { mkdirs() }
        data.photos.forEach { photo ->
            val source = File(photo.filePath)
            if (source.exists()) {
                source.copyTo(File(photosDir, source.name), overwrite = true)
            }
        }
        return exportRoot
    }

    fun zipDirectory(directory: File, outputZip: File) {
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(directory).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
