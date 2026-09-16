package com.startinsnow.gpstracker.export

/** 對應規格「53. Export」CSV 選項，方便在試算表中檢視原始軌跡點。 */
object CsvExporter {
    fun generate(data: ExportTrackData): String {
        val sb = StringBuilder()
        sb.append("timestamp,latitude,longitude,speed_mps,altitude_m,accuracy_m,provider,reliability,quality,mode\n")
        data.points.sortedBy { it.timestampMs }.forEach { p ->
            sb.append(p.timestampMs).append(',')
                .append(p.latitude).append(',')
                .append(p.longitude).append(',')
                .append(p.speedMps ?: "").append(',')
                .append(p.altitudeMeters ?: "").append(',')
                .append(p.accuracyMeters).append(',')
                .append(p.provider).append(',')
                .append(p.reliability).append(',')
                .append(p.quality).append(',')
                .append(p.movementMode.name)
                .append('\n')
        }
        return sb.toString()
    }
}
