package com.startinsnow.healthtracker.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 同步引擎依賴的遠端介面（測試時用假的實作取代）。 */
interface RemoteApi {
    suspend fun signIn(email: String, password: String): Session
    suspend fun refresh(refreshToken: String): Session
    suspend fun fetchChanges(accessToken: String, sinceCursor: String?): List<RemoteRecord>
    suspend fun upsert(accessToken: String, record: RemoteRecord)
    suspend fun uploadPhoto(accessToken: String, objectPath: String, bytes: ByteArray)
    suspend fun deletePhoto(accessToken: String, objectPath: String)

    /** 下載照片；雲端沒有這張（404）回傳 null。換新手機登入後用來把舊紀錄的照片補回來。 */
    suspend fun downloadPhoto(accessToken: String, objectPath: String): ByteArray?
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: TokenUser,
)

@Serializable
private data class TokenUser(val id: String, val email: String? = null)

/** 直接呼叫 Supabase 的 REST（GoTrue 登入、PostgREST 資料表、Storage 照片）。 */
class SupabaseApi(
    baseUrl: String,
    private val anonKey: String,
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RemoteApi {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    private val jsonType = "application/json".toMediaType()

    override suspend fun signIn(email: String, password: String): Session =
        tokenRequest("password", buildJsonObject { put("email", email); put("password", password) }.toString())

    override suspend fun refresh(refreshToken: String): Session =
        tokenRequest("refresh_token", buildJsonObject { put("refresh_token", refreshToken) }.toString())

    private suspend fun tokenRequest(grantType: String, body: String): Session {
        val url = base.newBuilder().addPathSegments("auth/v1/token").addQueryParameter("grant_type", grantType).build()
        val req = Request.Builder().url(url).header("apikey", anonKey)
            .post(body.toRequestBody(jsonType)).build()
        execute(req).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // GoTrue 登入或續期失敗多半是 400（密碼錯誤、refresh token 失效）。
                if (resp.code == 400 || resp.code == 401 || resp.code == 422) {
                    throw CloudException(CloudErrorKind.AUTH_FAILED, "登入失敗：帳號或密碼錯誤，或登入已失效", resp.code)
                }
                throw serverError(resp.code, text)
            }
            val t = json.decodeFromString(TokenResponse.serializer(), text)
            return Session(t.accessToken, t.refreshToken, clock() + t.expiresIn * 1000, t.user.id, t.user.email)
        }
    }

    override suspend fun fetchChanges(accessToken: String, sinceCursor: String?): List<RemoteRecord> {
        val all = ArrayList<RemoteRecord>()
        var cursor = sinceCursor
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addPathSegments("rest/v1/health_records")
                .addQueryParameter("select", "*")
                .addQueryParameter("order", "synced_at.asc")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                // OkHttp 不會把 '+' 編碼成 %2B；時間戳的時區（+00:00）若不編碼，伺服器會把加號讀成空白而解析失敗。
                .apply { cursor?.let { addEncodedQueryParameter("synced_at", "gte." + URLEncoder.encode(it, "UTF-8")) } }
                .build()
            val req = authed(Request.Builder().url(url), accessToken).get().build()
            val page = execute(req).use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw httpError(resp.code, text)
                json.decodeFromString(ListSerializer(RemoteRecord.serializer()), text)
            }
            all += page
            val last = page.lastOrNull()?.syncedAt
            // 不滿一頁就是抓完了；整頁同一個時間戳（理論上不會發生）也要停，避免無窮迴圈。
            if (page.size < PAGE_SIZE || last == null || last == cursor) return all
            cursor = last
        }
        return all
    }

    override suspend fun upsert(accessToken: String, record: RemoteRecord) {
        val url = base.newBuilder().addPathSegments("rest/v1/health_records").addQueryParameter("on_conflict", "id").build()
        // synced_at 由資料庫 trigger 蓋章，推送時直接拿掉這個欄位。
        // photo_object 為 null 時整個欄位省略（merge-duplicates 只更新有送出的欄位，不會把雲端原有的照片連結清掉）
        // context 為 null 也省略：雲端還沒加 context 欄位時，沒填情境的紀錄仍能正常同步
        val omitted = buildSet {
            add("synced_at")
            if (record.photoObject == null) add("photo_object")
            if (record.context == null) add("context")
        }
        val body = JsonObject(json.encodeToJsonElement(RemoteRecord.serializer(), record).jsonObject - omitted).toString()
        val req = authed(Request.Builder().url(url), accessToken)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toRequestBody(jsonType)).build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
        }
    }

    override suspend fun uploadPhoto(accessToken: String, objectPath: String, bytes: ByteArray) {
        val req = authed(Request.Builder().url(objectUrl(objectPath)), accessToken)
            .header("x-upsert", "true")
            .post(bytes.toRequestBody("image/jpeg".toMediaType())).build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
        }
    }

    override suspend fun deletePhoto(accessToken: String, objectPath: String) {
        val req = authed(Request.Builder().url(objectUrl(objectPath)), accessToken).delete().build()
        execute(req).use { resp ->
            // 照片本來就不存在也算成功。
            if (!resp.isSuccessful && resp.code != 404) throw httpError(resp.code, resp.body?.string().orEmpty())
        }
    }

    override suspend fun downloadPhoto(accessToken: String, objectPath: String): ByteArray? {
        val url = base.newBuilder().addPathSegments("storage/v1/object/authenticated/$BUCKET/$objectPath").build()
        val req = authed(Request.Builder().url(url), accessToken).get().build()
        return execute(req).use { resp ->
            when {
                resp.code == 404 || resp.code == 400 -> null // Storage 對不存在的物件可能回 400 或 404
                !resp.isSuccessful -> throw httpError(resp.code, resp.body?.string().orEmpty())
                else -> withContext(Dispatchers.IO) { resp.body?.bytes() }
            }
        }
    }

    private fun objectUrl(objectPath: String): HttpUrl =
        base.newBuilder().addPathSegments("storage/v1/object/$BUCKET/$objectPath").build()

    private fun authed(b: Request.Builder, accessToken: String): Request.Builder =
        b.header("apikey", anonKey).header("Authorization", "Bearer $accessToken")

    private suspend fun execute(req: Request): Response = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw CloudException(CloudErrorKind.NETWORK, "無法連線到雲端，請檢查網路後再試")
        }
    }

    private fun httpError(code: Int, body: String): CloudException =
        if (code == 401) CloudException(CloudErrorKind.UNAUTHORIZED, "登入已過期", code) else serverError(code, body)

    private fun serverError(code: Int, body: String): CloudException {
        val hint = when (code) {
            403 -> "沒有權限（請確認已執行 schema.sql 並登入正確帳號）"
            404 -> "找不到資料表或 bucket（請確認已執行 schema.sql）"
            429 -> "請求太頻繁，請稍後再試"
            else -> "伺服器錯誤"
        }
        return CloudException(CloudErrorKind.SERVER, "$hint（HTTP $code）", code)
    }

    companion object {
        const val BUCKET = "meter-photos"
        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 20

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
