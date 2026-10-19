package com.startinsnow.healthtracker.cloud

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SupabaseApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = SupabaseApi(server.url("/").toString().trimEnd('/'), "anon-key", clock = { 5_000L })
    }

    @After fun tearDown() = server.shutdown()

    private fun recordJson(id: String, syncedAt: String) =
        """{"id":"$id","user_id":"u","type":"WEIGHT","value":70.5,"value2":null,"measured_at":1,"source":"MANUAL","note":"","created_at":2,"updated_at":3,"deleted_at":null,"photo_object":null,"synced_at":"$syncedAt"}"""

    private suspend fun expectKind(kind: CloudErrorKind, block: suspend () -> Unit) {
        try {
            block()
            fail("expected CloudException($kind)")
        } catch (e: CloudException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test fun signInPostsCredentialsAndParsesSession() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"A","refresh_token":"R","expires_in":3600,"token_type":"bearer","user":{"id":"uid","email":"me@example.com"}}"""))
        val s = api.signIn("me@example.com", "pw")
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("anon-key", req.getHeader("apikey"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("me@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("pw", body["password"]!!.jsonPrimitive.content)
        assertEquals(Session("A", "R", 5_000L + 3_600_000L, "uid", "me@example.com"), s)
    }

    @Test fun refreshUsesRefreshTokenGrant() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"A2","refresh_token":"R2","expires_in":60,"user":{"id":"uid"}}"""))
        val s = api.refresh("R1")
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", req.path)
        assertEquals("R1", Json.parseToJsonElement(req.body.readUtf8()).jsonObject["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("A2", s.accessToken)
        assertNull(s.email)
    }

    @Test fun wrongPasswordIsAuthFailed() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":"invalid_credentials"}"""))
        expectKind(CloudErrorKind.AUTH_FAILED) { api.signIn("a@b.c", "bad") }
    }

    @Test fun fetchChangesSendsAuthAndCursorEncoded() = runTest {
        server.enqueue(MockResponse().setBody("[" + recordJson("r1", "2026-10-19T00:00:01+00:00") + "]"))
        val rows = api.fetchChanges("tok", "2026-10-19T00:00:00.5+00:00")
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("anon-key", req.getHeader("apikey"))
        assertEquals("order=synced_at.asc", req.requestUrl!!.encodedQuery!!.split("&").first { it.startsWith("order") })
        assertEquals("gte.2026-10-19T00:00:00.5+00:00", req.requestUrl!!.queryParameter("synced_at"))
        assertTrue("加號必須編碼成 %2B，否則時區會被讀成空白", req.path!!.contains("%2B00%3A00"))
        assertEquals(1, rows.size)
        assertEquals(70.5, rows[0].value, 0.0)
        assertNull(rows[0].value2)
    }

    @Test fun fetchChangesWithoutCursorOmitsFilter() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        api.fetchChanges("tok", null)
        assertNull(server.takeRequest().requestUrl!!.queryParameter("synced_at"))
    }

    @Test fun fetchChangesFollowsPages() = runTest {
        val page1 = (1..500).joinToString(",", "[", "]") { recordJson("a$it", "2026-10-19T00:00:01+00:00") }
        val page2 = "[" + recordJson("b1", "2026-10-19T00:00:02+00:00") + "]"
        server.enqueue(MockResponse().setBody(page1))
        server.enqueue(MockResponse().setBody(page2))
        val rows = api.fetchChanges("tok", null)
        assertEquals(501, rows.size)
        server.takeRequest()
        assertEquals("gte.2026-10-19T00:00:01+00:00", server.takeRequest().requestUrl!!.queryParameter("synced_at"))
    }

    @Test fun unauthorizedMapsToUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"PGRST301"}"""))
        expectKind(CloudErrorKind.UNAUTHORIZED) { api.fetchChanges("old", null) }
    }

    @Test fun missingTableHintsToRunSchema() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        try {
            api.fetchChanges("tok", null)
            fail()
        } catch (e: CloudException) {
            assertEquals(CloudErrorKind.SERVER, e.kind)
            assertTrue(e.message!!.contains("schema.sql"))
        }
    }

    @Test fun upsertUsesMergeDuplicatesAndOmitsSyncedAt() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        api.upsert("tok", RemoteRecord("r1", "WEIGHT", 70.0, null, 1, "MANUAL", "", 2, 3, null, null, syncedAt = "ignored"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/rest/v1/health_records?on_conflict=id", req.path)
        assertEquals("resolution=merge-duplicates,return=minimal", req.getHeader("Prefer"))
        val body: JsonObject = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertFalse(body.containsKey("synced_at"))
        assertTrue("null 欄位要明確送出，避免部分更新", body.containsKey("deleted_at") && body.containsKey("value2"))
        assertEquals("r1", body["id"]!!.jsonPrimitive.content)
    }

    @Test fun uploadPhotoSendsBytesWithUpsertHeader() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        api.uploadPhoto("tok", "uid/r1.jpg", byteArrayOf(1, 2, 3))
        val req = server.takeRequest()
        assertEquals("/storage/v1/object/meter-photos/uid/r1.jpg", req.path)
        assertEquals("true", req.getHeader("x-upsert"))
        assertEquals("image/jpeg", req.getHeader("Content-Type"))
        assertEquals(3L, req.bodySize)
    }

    @Test fun deletePhotoTreats404AsSuccess() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        api.deletePhoto("tok", "uid/gone.jpg")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun deletePhotoServerErrorIsReported() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        expectKind(CloudErrorKind.SERVER) { api.deletePhoto("tok", "uid/x.jpg") }
    }

    @Test fun unreachableServerIsNetworkError() = runTest {
        server.shutdown()
        expectKind(CloudErrorKind.NETWORK) { api.fetchChanges("tok", null) }
    }
}
