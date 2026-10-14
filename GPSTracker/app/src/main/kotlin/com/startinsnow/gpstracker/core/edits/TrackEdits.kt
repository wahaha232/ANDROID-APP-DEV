package com.startinsnow.gpstracker.core.edits

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 使用者在 App 內對一筆紀錄做的編輯（標題、備註、移動方式）。與網站「紀錄管理」寫入的 meta.json 同一種資料：
 * 只影響顯示與搜尋，原始 GPS 紀錄與統計不會被修改。[mode] 為 null 代表維持 App 原本的判斷。
 * [dirty] = 還沒同步到網頁。全部為空且 dirty 的項目是「刪除標記」：要把網頁上這筆的編輯一併移除。
 */
@Serializable
data class TrackEdit(
    val title: String = "",
    val notes: String = "",
    val mode: String? = null,
    val dirty: Boolean = false
) {
    val isBlank: Boolean get() = title.isBlank() && notes.isBlank() && mode == null
}

/** 本機保存的編輯清單（trackId → 編輯）。純邏輯，方便單元測試。 */
object TrackEdits {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), TrackEdit.serializer())

    /** 寬鬆解析：壞掉的內容回傳空清單，不會讓 App 當掉。 */
    fun parse(text: String?): Map<String, TrackEdit> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
    }

    fun toJson(edits: Map<String, TrackEdit>): String = json.encodeToString(serializer, edits)

    /** 儲存一筆編輯（標題與備註會去掉頭尾空白）；一律標記為尚未同步。 */
    fun put(edits: Map<String, TrackEdit>, trackId: String, title: String, notes: String, mode: String?): Map<String, TrackEdit> =
        edits + (trackId to TrackEdit(title.trim(), notes.trim(), mode?.takeIf { it.isNotBlank() }, dirty = true))

    /** 已同步到網頁：有內容的保留（dirty = false），刪除標記（全空白）直接移除。 */
    fun markClean(edits: Map<String, TrackEdit>, trackId: String): Map<String, TrackEdit> {
        val e = edits[trackId] ?: return edits
        return if (e.isBlank) edits - trackId else edits + (trackId to e.copy(dirty = false))
    }

    fun dirtyIds(edits: Map<String, TrackEdit>): List<String> = edits.filterValues { it.dirty }.keys.toList()
}

/**
 * 把一筆編輯合併進網頁的 meta.json（`{"tracks": {trackId: {title, notes, mode}}}`）。
 * 只動這一筆：其他筆的編輯（例如在網站上改的）與未知欄位原封不動；全空白就把這筆移除。
 */
object MetaMerge {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun apply(existing: String?, trackId: String, edit: TrackEdit?): String {
        val root = runCatching { Json.parseToJsonElement(existing ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val tracks = (root["tracks"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (edit == null || edit.isBlank) {
            tracks.remove(trackId)
        } else {
            val entry = (tracks[trackId] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            entry["title"] = JsonPrimitive(edit.title)
            entry["notes"] = JsonPrimitive(edit.notes)
            if (edit.mode != null) entry["mode"] = JsonPrimitive(edit.mode) else entry.remove("mode")
            tracks[trackId] = JsonObject(entry)
        }
        val out = root.toMutableMap()
        out["tracks"] = JsonObject(tracks)
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(out))
    }
}
