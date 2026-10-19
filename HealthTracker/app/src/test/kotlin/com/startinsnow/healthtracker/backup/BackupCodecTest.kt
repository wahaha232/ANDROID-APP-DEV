package com.startinsnow.healthtracker.backup

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun rec(
    id: String,
    updatedAt: Long = 100,
    deletedAt: Long? = null,
    type: RecordType = RecordType.WEIGHT,
    note: String = "",
    photoPath: String? = null,
) = HealthRecord(
    id = id, type = type, value = 70.5, value2 = if (type == RecordType.BLOOD_PRESSURE) 80.0 else null,
    measuredAt = 1_760_000_000_000, source = RecordSource.MIJIA, note = note, syncState = SyncState.SYNCED,
    createdAt = 10, updatedAt = updatedAt, deletedAt = deletedAt, photoPath = photoPath,
)

class BackupCodecTest {
    private val sample = listOf(
        rec("a", note = "晨間，含逗號與\"引號\"與換行\n第二行"),
        rec("b", type = RecordType.BLOOD_PRESSURE),
        rec("c", deletedAt = 150, updatedAt = 150),
    )

    @Test fun plainRoundTripKeepsEveryFieldIncludingDeleted() {
        val bytes = BackupCodec.encode(sample, null)
        assertFalse(BackupCodec.isEncrypted(bytes))
        val back = BackupCodec.decode(bytes, null).mapNotNull(BackupCodec::toHealthRecord)
        assertEquals(sample.map { it.copy(syncState = SyncState.PENDING, photoPath = null) }, back)
    }

    @Test fun photoPathAndSyncStateAreNotStoredInTheFile() {
        val text = String(BackupCodec.encode(listOf(rec("a", photoPath = "/data/user/0/x/photos/secret.jpg")), null), Charsets.UTF_8)
        assertFalse(text.contains("secret.jpg"))
        assertFalse(text.contains("syncState"))
        assertFalse(text.contains("photoPath"))
    }

    @Test fun encryptedRoundTrip() {
        val bytes = BackupCodec.encode(sample, "correct horse")
        assertTrue(BackupCodec.isEncrypted(bytes))
        assertEquals(sample.map { it.id }, BackupCodec.decode(bytes, "correct horse").map { it.id })
    }

    @Test fun encryptedFileHidesTheContent() {
        val text = String(BackupCodec.encode(listOf(rec("a", note = "很私密的備註")), "pw12345"), Charsets.UTF_8)
        assertFalse(text.contains("很私密的備註"))
        assertFalse(text.contains("70.5"))
    }

    @Test fun twoEncryptionsOfTheSameDataDiffer() {
        val a = String(BackupCodec.encode(sample, "pw"), Charsets.UTF_8)
        val b = String(BackupCodec.encode(sample, "pw"), Charsets.UTF_8)
        assertTrue(a != b) // 每次隨機 salt 與 iv
    }

    private fun expectError(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected BackupException containing $contains")
        } catch (e: BackupException) {
            assertTrue("實際訊息：${e.message}", e.message!!.contains(contains))
        }
    }

    @Test fun wrongPasswordIsRejected() {
        val bytes = BackupCodec.encode(sample, "right")
        expectError("密碼錯誤") { BackupCodec.decode(bytes, "wrong") }
    }

    @Test fun missingPasswordForEncryptedBackupAsksForIt() {
        val bytes = BackupCodec.encode(sample, "right")
        expectError("請輸入密碼") { BackupCodec.decode(bytes, "") }
        expectError("請輸入密碼") { BackupCodec.decode(bytes, null) }
    }

    @Test fun tamperedCiphertextIsRejectedInsteadOfRestoringWrongData() {
        val text = String(BackupCodec.encode(sample, "pw"), Charsets.UTF_8)
        val marker = "\"data\":\""
        val i = text.indexOf(marker) + marker.length
        val flipped = if (text[i] == 'A') 'B' else 'A'
        val tampered = (text.substring(0, i) + flipped + text.substring(i + 1)).toByteArray(Charsets.UTF_8)
        try {
            BackupCodec.decode(tampered, "pw")
            fail("tampered data must not decode")
        } catch (e: BackupException) {
            // 預期：密碼錯誤或檔案損毀
        }
    }

    @Test fun garbageAndForeignFilesAreRejected() {
        expectError("不是有效的備份檔") { BackupCodec.decode("hello".toByteArray(), null) }
        expectError("不是有效的備份檔") { BackupCodec.decode(ByteArray(0), null) }
        expectError("不是本 App 的備份檔") { BackupCodec.decode("""{"format":"other","version":1,"createdAt":0,"encrypted":false,"records":[]}""".toByteArray(), null) }
    }

    @Test fun newerVersionIsRejectedWithUpdateHint() {
        expectError("較新的版本") {
            BackupCodec.decode("""{"format":"healthtracker-backup","version":99,"createdAt":0,"encrypted":false,"records":[]}""".toByteArray(), null)
        }
    }

    @Test fun unreasonableKdfParametersAreRejected() {
        val bytes = """{"format":"healthtracker-backup","version":1,"createdAt":0,"encrypted":true,"kdf":"PBKDF2WithHmacSHA256","iterations":1000000000,"salt":"AAAA","iv":"AAAA","data":"AAAA"}""".toByteArray()
        expectError("不合理") { BackupCodec.decode(bytes, "pw") }
    }

    @Test fun unknownRecordTypeFromNewerVersionIsSkippedNotFatal() {
        val json = """{"format":"healthtracker-backup","version":1,"createdAt":0,"encrypted":false,"records":[
            {"id":"x","type":"STEPS","value":1.0,"measuredAt":1,"source":"MANUAL","createdAt":1,"updatedAt":1},
            {"id":"y","type":"WEIGHT","value":70.0,"measuredAt":1,"source":"FUTURE_SOURCE","createdAt":1,"updatedAt":1}]}"""
        val decoded = BackupCodec.decode(json.toByteArray(), null)
        val mapped = decoded.mapNotNull(BackupCodec::toHealthRecord)
        assertEquals(2, decoded.size)
        assertEquals(listOf("y"), mapped.map { it.id })
        assertEquals(RecordSource.IMPORT, mapped[0].source)
    }

    @Test fun restoredRecordsAreMarkedPendingSoTheyUploadAgain() {
        val back = BackupCodec.decode(BackupCodec.encode(listOf(rec("a")), null), null).mapNotNull(BackupCodec::toHealthRecord)
        assertEquals(SyncState.PENDING, back.single().syncState)
        assertNull(back.single().photoPath)
    }

    @Test fun planRestoreInsertsMissingUpdatesNewerAndSkipsOlderOrEqual() {
        assertEquals(RestoreAction.INSERT, planRestore(null, rec("a")))
        assertEquals(RestoreAction.UPDATE, planRestore(rec("a", updatedAt = 100), rec("a", updatedAt = 200)))
        assertEquals(RestoreAction.SKIP, planRestore(rec("a", updatedAt = 200), rec("a", updatedAt = 100)))
        assertEquals(RestoreAction.SKIP, planRestore(rec("a", updatedAt = 100), rec("a", updatedAt = 100)))
    }

    @Test fun oldBackupDoesNotResurrectARecordDeletedLater() {
        val deletedLocally = rec("a", updatedAt = 500, deletedAt = 500)
        val fromBackup = rec("a", updatedAt = 100)
        assertEquals(RestoreAction.SKIP, planRestore(deletedLocally, fromBackup))
    }
}
