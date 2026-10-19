package com.startinsnow.healthtracker.importer

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import com.startinsnow.healthtracker.data.repository.RecordValidator
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class CsvParseResult(val records: List<HealthRecord>, val errors: List<String>)

/** 確定性 id：同一筆資料不論匯入幾次都得到同一個 id，配合 insertIgnore 達成去重。 */
object ImportIds {
    fun fromHealthConnect(healthConnectRecordId: String): String =
        UUID.nameUUIDFromBytes("hc:$healthConnectRecordId".toByteArray()).toString()

    fun fromMijia(type: RecordType, measuredAt: Long, value: Double): String =
        UUID.nameUUIDFromBytes("mijia:${type.name}:$measuredAt:$value".toByteArray()).toString()

    fun fromCsvRow(type: RecordType, measuredAt: Long, value: Double, value2: Double?): String =
        UUID.nameUUIDFromBytes("csv:${type.name}:$measuredAt:$value:${value2 ?: ""}".toByteArray()).toString()
}

/**
 * 匯入 CSV（Excel 另存「CSV UTF-8」即可）。第一列必須是標題，欄位名稱不分大小寫、可中英文：
 * 時間（measured_at／時間／日期）、類型（type／類型／種類）、數值（value／數值）、
 * 第二數值（value2／舒張壓）、備註（note／備註）。
 * 類型可寫 WEIGHT／體重、BODY_FAT／體脂率、BLOOD_GLUCOSE／血糖、BLOOD_PRESSURE／血壓。
 * 時間可寫 2026-10-19 09:27、2026/10/19 09:27、2026-10-19T09:27:00 或毫秒 epoch；只有日期則視為當天 00:00。
 */
object CsvImporter {
    private val timeNames = setOf("measured_at", "time", "時間", "日期", "量測時間")
    private val typeNames = setOf("type", "類型", "種類")
    private val valueNames = setOf("value", "數值", "值")
    private val value2Names = setOf("value2", "舒張壓", "數值2")
    private val noteNames = setOf("note", "備註")

    private val typeAliases: Map<String, RecordType> = buildMap {
        RecordType.entries.forEach { put(it.name.lowercase(Locale.ROOT), it); put(it.label, it) }
        put("體脂", RecordType.BODY_FAT)
        put("body fat", RecordType.BODY_FAT)
        put("weight", RecordType.WEIGHT)
    }

    private val formatters = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm",
        "yyyy-M-d H:mm", "yyyy/M/d H:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
    ).map { DateTimeFormatter.ofPattern(it) }

    fun parse(
        text: String,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): CsvParseResult {
        val rows = splitRows(text.removePrefix("﻿"))
        if (rows.isEmpty()) return CsvParseResult(emptyList(), listOf("檔案是空的"))
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        fun col(names: Set<String>) = header.indexOfFirst { it in names }
        val iTime = col(timeNames)
        val iType = col(typeNames)
        val iValue = col(valueNames)
        if (iTime < 0 || iType < 0 || iValue < 0) {
            return CsvParseResult(emptyList(), listOf("標題列缺少必要欄位：時間、類型、數值（第一列必須是標題）"))
        }
        val iValue2 = col(value2Names)
        val iNote = col(noteNames)

        val records = ArrayList<HealthRecord>()
        val errors = ArrayList<String>()
        rows.drop(1).forEachIndexed { index, cells ->
            val line = index + 2
            if (cells.all { it.isBlank() }) return@forEachIndexed
            fun cell(i: Int) = if (i in cells.indices) cells[i].trim() else ""
            val type = typeAliases[cell(iType).lowercase(Locale.ROOT)]
            val measuredAt = parseTime(cell(iTime), zone)
            val value = cell(iValue).toDoubleOrNull()
            val value2 = if (iValue2 >= 0) cell(iValue2).toDoubleOrNull() else null
            val problem = when {
                type == null -> "類型「${cell(iType)}」看不懂"
                measuredAt == null -> "時間「${cell(iTime)}」格式不對"
                else -> RecordValidator.validate(type, value, value2)
            }
            if (problem != null) {
                if (errors.size < MAX_ERRORS) errors += "第 $line 列：$problem"
                return@forEachIndexed
            }
            type!!; measuredAt!!; value!!
            val v2 = if (type == RecordType.BLOOD_PRESSURE) value2 else null
            records += HealthRecord(
                id = ImportIds.fromCsvRow(type, measuredAt, value, v2),
                type = type, value = value, value2 = v2, measuredAt = measuredAt,
                source = RecordSource.IMPORT, note = if (iNote >= 0) cell(iNote) else "",
                syncState = SyncState.PENDING, createdAt = now, updatedAt = now,
            )
        }
        return CsvParseResult(records, errors)
    }

    internal fun parseTime(s: String, zone: ZoneId): Long? {
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return if (s.length >= 12) it else null } // 毫秒 epoch；位數太少的純數字不接受
        for (f in formatters) {
            runCatching { return LocalDateTime.parse(s, f).atZone(zone).toInstant().toEpochMilli() }
        }
        runCatching {
            return java.time.LocalDate.parse(s.replace('/', '-').let(::padDate)).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return null
    }

    private fun padDate(s: String): String {
        val p = s.split('-')
        return if (p.size == 3) "%s-%02d-%02d".format(p[0], p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0) else s
    }

    /** 最小可用的 CSV 切列：支援雙引號、引號內逗號與換行、"" 轉義。 */
    internal fun splitRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var cells = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() { cells.add(sb.toString()); sb.setLength(0) }
        fun endRow() { endCell(); if (!(cells.size == 1 && cells[0].isEmpty())) rows.add(cells); cells = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> endCell()
                !inQuotes && c == '\n' -> endRow()
                !inQuotes && c == '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || cells.isNotEmpty()) endRow()
        return rows
    }

    private const val MAX_ERRORS = 20
}
