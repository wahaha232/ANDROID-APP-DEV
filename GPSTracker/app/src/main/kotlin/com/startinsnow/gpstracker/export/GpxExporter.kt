package com.startinsnow.gpstracker.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 對應規格「51. GPX」。輸出標準 GPX 1.1，供第三方 GPS 軟體匯入。 */
object GpxExporter {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun generate(data: ExportTrackData): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GPS TRACKER\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>GPS TRACKER ${data.track.trackId}</name>\n    <trkseg>\n")
        data.points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }.forEach { p ->
            sb.append("      <trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">\n")
            if (p.altitudeMeters != null) sb.append("        <ele>${p.altitudeMeters}</ele>\n")
            sb.append("        <time>${iso.format(Date(p.timestampMs))}</time>\n")
            if (p.speedMps != null) sb.append("        <speed>${p.speedMps}</speed>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n")
        data.photos.forEach { photo ->
            if (photo.latitude != null && photo.longitude != null) {
                sb.append("  <wpt lat=\"${photo.latitude}\" lon=\"${photo.longitude}\">\n")
                sb.append("    <name>${photo.type}</name>\n")
                sb.append("    <time>${iso.format(Date(photo.timestampMs))}</time>\n")
                sb.append("  </wpt>\n")
            }
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
