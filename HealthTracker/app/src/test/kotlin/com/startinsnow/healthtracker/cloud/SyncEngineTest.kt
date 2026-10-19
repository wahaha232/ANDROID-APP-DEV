package com.startinsnow.healthtracker.cloud

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000_000L
private const val USER = "user-1"

private fun local(
    id: String,
    updatedAt: Long = 100,
    state: SyncState = SyncState.PENDING,
    source: RecordSource = RecordSource.MANUAL,
    deletedAt: Long? = null,
    photoPath: String? = null,
    value: Double = 70.0,
) = HealthRecord(
    id = id, type = RecordType.WEIGHT, value = value, measuredAt = 50, source = source,
    syncState = state, createdAt = 10, updatedAt = updatedAt, deletedAt = deletedAt, photoPath = photoPath,
)

private fun remote(id: String, updatedAt: Long = 100, syncedAt: String? = "2026-10-19T00:00:01+00:00", type: String = "WEIGHT", value: Double = 71.0) =
    RemoteRecord(id = id, type = type, value = value, measuredAt = 50, createdAt = 10, updatedAt = updatedAt, syncedAt = syncedAt)

private class FakeStore : SyncStore {
    val rows = LinkedHashMap<String, HealthRecord>()
    /** 模擬「推送期間使用者又改了這筆」：upsert 時改動 updatedAt。 */
    var onUpsert: (String) -> Unit = {}
    override suspend fun pending() = rows.values.filter { it.syncState != SyncState.SYNCED }
    override suspend fun get(id: String) = rows[id]
    override suspend fun saveFromRemote(record: HealthRecord) { rows[record.id] = record }
    override suspend fun markSynced(id: String, updatedAt: Long): Boolean {
        val r = rows[id] ?: return false
        if (r.updatedAt != updatedAt) return false
        rows[id] = r.copy(syncState = SyncState.SYNCED)
        return true
    }
}

private class FakeSessions(var session: Session? = null) : SessionStore {
    var cursorValue: String? = null
    var lastSync: Long? = null
    var lastResultValue: String? = null
    override suspend fun load() = session
    override suspend fun save(session: Session?) { this.session = session }
    override suspend fun cursor() = cursorValue
    override suspend fun setCursor(cursor: String?) { cursorValue = cursor }
    override suspend fun lastSyncAt() = lastSync
    override suspend fun setLastSyncAt(millis: Long) { lastSync = millis }
    override suspend fun lastResult() = lastResultValue
    override suspend fun setLastResult(message: String?) { lastResultValue = message }
}

private class FakeApi(val store: FakeStore? = null) : RemoteApi {
    val remoteRows = LinkedHashMap<String, RemoteRecord>()
    val upserted = ArrayList<RemoteRecord>()
    val uploaded = ArrayList<Pair<String, Int>>()
    val deletedPhotos = ArrayList<String>()
    val fetchCursors = ArrayList<String?>()
    var tokensSeen = ArrayList<String>()
    var refreshCalls = 0
    var upsertFailures = HashMap<String, CloudException>()
    var fetchFailure: CloudException? = null
    var unauthorizedOnce = false
    var refreshFailure: CloudException? = null
    var onUpsert: (String) -> Unit = {}

    override suspend fun signIn(email: String, password: String) = Session("a0", "r0", NOW + 3_600_000, USER, email)
    override suspend fun refresh(refreshToken: String): Session {
        refreshCalls++
        refreshFailure?.let { throw it }
        return Session("a${refreshCalls}", "r${refreshCalls}", NOW + 3_600_000, USER, "me@example.com")
    }
    override suspend fun fetchChanges(accessToken: String, sinceCursor: String?): List<RemoteRecord> {
        tokensSeen += accessToken
        if (unauthorizedOnce) { unauthorizedOnce = false; throw CloudException(CloudErrorKind.UNAUTHORIZED, "expired", 401) }
        fetchFailure?.let { throw it }
        fetchCursors += sinceCursor
        return remoteRows.values.toList()
    }
    override suspend fun upsert(accessToken: String, record: RemoteRecord) {
        upsertFailures[record.id]?.let { throw it }
        upserted += record
        remoteRows[record.id] = record
        onUpsert(record.id)
    }
    override suspend fun uploadPhoto(accessToken: String, objectPath: String, bytes: ByteArray) { uploaded += objectPath to bytes.size }
    override suspend fun deletePhoto(accessToken: String, objectPath: String) { deletedPhotos += objectPath }
}

class SyncEngineTest {
    private val store = FakeStore()
    private val sessions = FakeSessions(Session("a", "r", NOW + 3_600_000, USER, "me@example.com"))
    private val api = FakeApi()
    private fun engine(readPhoto: (String) -> ByteArray? = { null }) = SyncEngine(store, api, sessions, readPhoto) { NOW }

    @Test fun notSignedInReturnsErrorWithoutCalling() = runTest {
        sessions.session = null
        val r = engine().sync()
        assertEquals("尚未登入雲端", r.error)
        assertTrue(api.fetchCursors.isEmpty())
    }

    @Test fun pullInsertsNewRemoteRecordsAsSynced() = runTest {
        api.remoteRows["r1"] = remote("r1")
        val r = engine().sync()
        assertTrue(r.ok)
        assertEquals(1, r.pulled)
        assertEquals(SyncState.SYNCED, store.rows["r1"]!!.syncState)
        assertEquals(71.0, store.rows["r1"]!!.value, 0.0)
        assertEquals(NOW, sessions.lastSync)
    }

    @Test fun remoteNewerOverwritesLocalButKeepsLocalPhoto() = runTest {
        store.rows["x"] = local("x", updatedAt = 100, state = SyncState.SYNCED, photoPath = "/p/x.jpg")
        api.remoteRows["x"] = remote("x", updatedAt = 200)
        engine().sync()
        assertEquals(200, store.rows["x"]!!.updatedAt)
        assertEquals("/p/x.jpg", store.rows["x"]!!.photoPath)
    }

    @Test fun localNewerIsPushedNotOverwritten() = runTest {
        store.rows["x"] = local("x", updatedAt = 300, value = 65.0)
        api.remoteRows["x"] = remote("x", updatedAt = 200)
        val r = engine().sync()
        assertEquals(65.0, store.rows["x"]!!.value, 0.0)
        assertEquals(1, r.pushed)
        assertEquals(65.0, api.remoteRows["x"]!!.value, 0.0)
        assertEquals(SyncState.SYNCED, store.rows["x"]!!.syncState)
    }

    @Test fun sameVersionOnBothSidesJustMarksSynced() = runTest {
        store.rows["x"] = local("x", updatedAt = 100)
        api.remoteRows["x"] = remote("x", updatedAt = 100)
        val r = engine().sync()
        assertEquals(SyncState.SYNCED, store.rows["x"]!!.syncState)
        assertEquals(0, r.pushed) // 拉取時已標為同步，不必再推
    }

    @Test fun unknownRemoteTypeIsSkipped() = runTest {
        api.remoteRows["u"] = remote("u", type = "STEPS")
        api.remoteRows["k"] = remote("k")
        val r = engine().sync()
        assertTrue(r.ok)
        assertNull(store.rows["u"])
        assertNotNull(store.rows["k"])
    }

    @Test fun cursorAdvancesAndIsSentNextTime() = runTest {
        api.remoteRows["a"] = remote("a", syncedAt = "2026-10-19T00:00:01+00:00")
        api.remoteRows["b"] = remote("b", syncedAt = "2026-10-19T00:00:09+00:00")
        val e = engine()
        e.sync()
        e.sync()
        assertEquals(listOf<String?>(null, "2026-10-19T00:00:09+00:00"), api.fetchCursors)
    }

    @Test fun pendingRecordsArePushedAndMarkedSynced() = runTest {
        store.rows["a"] = local("a")
        store.rows["b"] = local("b", state = SyncState.LOCAL_ONLY)
        store.rows["c"] = local("c", state = SyncState.SYNCED)
        val r = engine().sync()
        assertEquals(2, r.pushed)
        assertEquals(setOf("a", "b"), api.upserted.map { it.id }.toSet())
        assertTrue(store.rows.values.all { it.syncState == SyncState.SYNCED })
    }

    @Test fun editDuringPushStaysPending() = runTest {
        store.rows["a"] = local("a", updatedAt = 100)
        api.onUpsert = { id -> store.rows[id] = store.rows[id]!!.copy(updatedAt = 150) }
        engine().sync()
        assertEquals(SyncState.PENDING, store.rows["a"]!!.syncState)
    }

    @Test fun ocrRecordUploadsPhotoToUserFolder() = runTest {
        store.rows["g"] = local("g", source = RecordSource.OCR, photoPath = "/p/g.jpg")
        engine { byteArrayOf(1, 2, 3) }.sync()
        assertEquals(listOf("$USER/g.jpg" to 3), api.uploaded)
        assertEquals("$USER/g.jpg", api.upserted.single().photoObject)
    }

    @Test fun missingPhotoFileStillSyncsRecord() = runTest {
        store.rows["g"] = local("g", source = RecordSource.OCR, photoPath = "/gone.jpg")
        val r = engine { null }.sync()
        assertTrue(r.ok)
        assertTrue(api.uploaded.isEmpty())
        assertEquals(1, api.upserted.size)
    }

    @Test fun deletedOcrRecordRemovesRemotePhoto() = runTest {
        store.rows["g"] = local("g", source = RecordSource.OCR, deletedAt = 120, photoPath = "/p/g.jpg")
        engine().sync()
        assertEquals(listOf("$USER/g.jpg"), api.deletedPhotos)
        assertEquals(120L, api.upserted.single().deletedAt)
    }

    @Test fun manualRecordPushHasNoPhotoObject() = runTest {
        store.rows["m"] = local("m")
        engine().sync()
        assertNull(api.upserted.single().photoObject)
        assertTrue(api.deletedPhotos.isEmpty())
    }

    @Test fun networkErrorAbortsAndKeepsPending() = runTest {
        store.rows["a"] = local("a")
        api.fetchFailure = CloudException(CloudErrorKind.NETWORK, "無法連線到雲端")
        val r = engine().sync()
        assertEquals("無法連線到雲端", r.error)
        assertEquals(SyncState.PENDING, store.rows["a"]!!.syncState)
        assertNull(sessions.lastSync)
    }

    @Test fun networkErrorDuringPushStopsRemaining() = runTest {
        store.rows["a"] = local("a")
        store.rows["b"] = local("b")
        api.upsertFailures["a"] = CloudException(CloudErrorKind.NETWORK, "斷線")
        val r = engine().sync()
        assertEquals("斷線", r.error)
        assertTrue(api.upserted.isEmpty())
        assertEquals(0, r.pushed)
    }

    @Test fun oneServerErrorDoesNotBlockOthers() = runTest {
        store.rows["bad"] = local("bad")
        store.rows["good"] = local("good")
        api.upsertFailures["bad"] = CloudException(CloudErrorKind.SERVER, "伺服器錯誤（HTTP 500）", 500)
        val r = engine().sync()
        assertEquals(1, r.pushed)
        assertEquals(1, r.failed)
        assertFalse(r.ok)
        assertEquals(SyncState.PENDING, store.rows["bad"]!!.syncState)
        assertEquals(SyncState.SYNCED, store.rows["good"]!!.syncState)
    }

    @Test fun expiredTokenIsRefreshedBeforeUse() = runTest {
        sessions.session = Session("old", "r", NOW + 10_000, USER, null) // 不到 60 秒就過期
        engine().sync()
        assertEquals(1, api.refreshCalls)
        assertEquals("a1", api.tokensSeen.first())
        assertEquals("a1", sessions.session!!.accessToken)
    }

    @Test fun unauthorizedTriggersOneRefreshAndRetry() = runTest {
        api.unauthorizedOnce = true
        val r = engine().sync()
        assertTrue(r.ok)
        assertEquals(1, api.refreshCalls)
        assertEquals(listOf("a", "a1"), api.tokensSeen)
    }

    @Test fun failedRefreshClearsSessionAndAsksToSignInAgain() = runTest {
        sessions.session = Session("old", "r", NOW - 1, USER, null)
        api.refreshFailure = CloudException(CloudErrorKind.AUTH_FAILED, "bad refresh", 400)
        val r = engine().sync()
        assertEquals("登入已失效，請重新登入", r.error)
        assertNull(sessions.session)
    }

    @Test fun networkFailureIsRetryableAndAuthFailureIsNot() = runTest {
        api.fetchFailure = CloudException(CloudErrorKind.NETWORK, "斷線")
        assertTrue(engine().sync().retryable)
        api.fetchFailure = null
        sessions.session = null
        assertFalse(engine().sync().retryable)
    }

    @Test fun serverErrorOnOneRecordIsRetryable() = runTest {
        store.rows["bad"] = local("bad")
        api.upsertFailures["bad"] = CloudException(CloudErrorKind.SERVER, "伺服器錯誤（HTTP 500）", 500)
        assertTrue(engine().sync().retryable)
    }

    @Test fun successfulSyncIsNotRetryable() = runTest {
        assertFalse(engine().sync().retryable)
    }

    @Test fun lastResultIsRecordedForBothSuccessAndFailure() = runTest {
        store.rows["a"] = local("a")
        engine().sync()
        assertEquals("同步完成：下載 0 筆、上傳 1 筆", sessions.lastResultValue)
        api.fetchFailure = CloudException(CloudErrorKind.NETWORK, "無法連線到雲端")
        engine().sync()
        assertEquals("無法連線到雲端", sessions.lastResultValue)
    }

    @Test fun hasPendingReflectsStore() = runTest {
        assertFalse(engine().hasPending())
        store.rows["a"] = local("a")
        assertTrue(engine().hasPending())
    }

    @Test fun failedSyncDoesNotAdvanceLastSyncTime() = runTest {
        store.rows["bad"] = local("bad")
        api.upsertFailures["bad"] = CloudException(CloudErrorKind.SERVER, "x", 500)
        engine().sync()
        assertNull(sessions.lastSync)
    }

    @Test fun signOutClearsSessionAndCursorButKeepsLocalData() = runTest {
        store.rows["a"] = local("a")
        sessions.cursorValue = "c"
        engine().signOut()
        assertNull(sessions.session)
        assertNull(sessions.cursorValue)
        assertEquals(1, store.rows.size)
    }

    @Test fun signInStoresSessionAndResetsCursor() = runTest {
        sessions.session = null
        sessions.cursorValue = "stale"
        engine().signIn("  me@example.com ", "pw")
        assertEquals("me@example.com", sessions.session!!.email)
        assertNull(sessions.cursorValue)
    }
}
