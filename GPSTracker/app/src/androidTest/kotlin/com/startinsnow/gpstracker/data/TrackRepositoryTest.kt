package com.startinsnow.gpstracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.data.db.AppDatabase
import com.startinsnow.gpstracker.data.repository.TrackRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TrackRepository 的結束／復原／清理流程測試（記憶體內 Room，不會碰 App 的真實資料庫）。
 * 這些流程是錄製服務結束時實際呼叫的資料層：finalize、Crash Recovery、abandon、Auto Cleanup 的 SQL 篩選。
 */
@RunWith(AndroidJUnit4::class)
class TrackRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TrackRepository
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        workDir = File(context.cacheDir, "repo-test").apply { mkdirs() }
        repo = TrackRepository(
            trackDao = db.trackDao(),
            trackPointDao = db.trackPointDao(),
            photoDao = db.photoDao(),
            movementSegmentDao = db.movementSegmentDao(),
            gpsOutageDao = db.gpsOutageDao(),
            filesDir = workDir
        )
    }

    @After
    fun tearDown() {
        db.close()
        workDir.deleteRecursively()
    }

    @Test
    fun findRecoverableTrack_returnsTheUnfinishedTrackAndIgnoresFinishedOnes() = runBlocking {
        val finished = repo.createTrack(null)
        repo.finalizeTrack(finished.trackId, endTimestampMs = 5_000L, stepCount = 0L)
        assertNull("only finished tracks -> nothing to recover", repo.findRecoverableTrack())

        val crashed = repo.createTrack(null) // 仍是 RECORDING，模擬程序被殺
        assertEquals(crashed.trackId, repo.findRecoverableTrack()?.trackId)
    }

    @Test
    fun findRecoverableTrack_alsoFindsAPausedTrack() = runBlocking {
        val track = repo.createTrack(null)
        repo.setStatus(track.trackId, TrackStatus.PAUSED)
        assertEquals(track.trackId, repo.findRecoverableTrack()?.trackId)
    }

    @Test
    fun finalizeTrack_marksFinishedAndKeepsTheExactSnapshotDistance() = runBlocking {
        val track = repo.createTrack(null)
        repo.finalizeTrack(
            trackId = track.trackId,
            endTimestampMs = 9_000L,
            stepCount = 321L,
            stepSource = "ZEPP_LIFE",
            activeDurationMs = 1_200_000L,
            snapshotDistanceMeters = 3_600.0,
            snapshotAvgSpeedMps = 0.1,
            snapshotMaxSpeedMps = 4.0,
            snapshotPointCount = 7
        )
        val saved = repo.getTrack(track.trackId)!!
        assertEquals(TrackStatus.FINISHED, saved.status)
        assertEquals(9_000L, saved.endTimeMs)
        assertEquals("snapshot distance is authoritative (no points to recompute from)", 3_600.0, saved.distanceMeters, 0.0)
        assertEquals("average speed = distance / moving time", 3.0, saved.avgSpeedMps, 1e-9)
        assertEquals(321L, saved.stepCount)
        assertEquals("ZEPP_LIFE", saved.stepSource)
        assertEquals(1_200_000L, saved.durationMs)
        assertNull("a finished track is no longer recoverable", repo.findRecoverableTrack())
    }

    @Test
    fun abandonTrack_closesAnUnfinishedTrackWithoutLosingIt() = runBlocking {
        val track = repo.createTrack(null)
        repo.abandonTrack(track.trackId)
        val saved = repo.getTrack(track.trackId)
        assertNotNull("abandon must not delete the data", saved)
        assertEquals(TrackStatus.FINISHED, saved!!.status)
        assertNotNull(saved.endTimeMs)
        assertNull(repo.findRecoverableTrack())
    }

    @Test
    fun updateSteps_changesOnlyStepsAndSource() = runBlocking {
        val track = repo.createTrack(null)
        repo.finalizeTrack(track.trackId, 8_000L, stepCount = 100L, snapshotDistanceMeters = 500.0, activeDurationMs = 60_000L)

        repo.updateSteps(track.trackId, 4_321L, "ZEPP_LIFE")

        val saved = repo.getTrack(track.trackId)!!
        assertEquals(4_321L, saved.stepCount)
        assertEquals("ZEPP_LIFE", saved.stepSource)
        assertEquals("other fields untouched", 500.0, saved.distanceMeters, 0.0)
        assertEquals(TrackStatus.FINISHED, saved.status)
    }

    @Test
    fun autoCleanup_queries_onlyReturnOldFinishedTracks_andBackupFilterRequiresUpload() = runBlocking {
        val cutoff = 100_000L
        val oldUploaded = finishedTrackEndingAt(10_000L).also { repo.setUploadStatus(it, UploadStatus.UPLOADED) }
        val oldNotUploaded = finishedTrackEndingAt(20_000L)
        val oldFailed = finishedTrackEndingAt(30_000L).also { repo.setUploadStatus(it, UploadStatus.FAILED) }
        val recentUploaded = finishedTrackEndingAt(500_000L).also { repo.setUploadStatus(it, UploadStatus.UPLOADED) }
        val stillRecording = repo.createTrack(null).trackId // 未完成的永遠不該被清理

        val allOld = repo.getFinishedTrackIdsBefore(TrackStatus.FINISHED, cutoff).toSet()
        assertEquals(setOf(oldUploaded, oldNotUploaded, oldFailed), allOld)

        val backedUpOnly = repo.getUploadedFinishedTrackIdsBefore(TrackStatus.FINISHED, cutoff).toSet()
        assertEquals("with Drive sync on, only already-backed-up tracks may be auto-deleted", setOf(oldUploaded), backedUpOnly)
        assertTrue(recentUploaded !in allOld && stillRecording !in allOld)
    }

    @Test
    fun deleteTrack_removesTheTrack() = runBlocking {
        val track = repo.createTrack(null)
        repo.deleteTrack(track.trackId)
        assertNull(repo.getTrack(track.trackId))
    }

    private suspend fun finishedTrackEndingAt(endMs: Long): String {
        val track = repo.createTrack(null)
        repo.finalizeTrack(track.trackId, endTimestampMs = endMs, stepCount = 0L)
        return track.trackId
    }
}
