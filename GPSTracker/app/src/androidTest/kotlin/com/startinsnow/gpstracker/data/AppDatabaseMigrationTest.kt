package com.startinsnow.gpstracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.startinsnow.gpstracker.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 資料庫 v1 → v2（tracks 新增 stepSource）的遷移測試。
 * 以 schemas/ 匯出的 JSON 建出真正的 v1 資料庫，插入舊資料，再跑遷移並由 Room 驗證 schema 完全一致。
 * 使用獨立的測試資料庫檔，不會碰 App 的真實資料。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    /** 測試用資料庫檔建在 App 的 databases 目錄，測完一定要刪掉，不留垃圾。 */
    @After
    fun deleteTestDatabases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(TEST_DB_EMPTY)
    }

    @Test
    fun migrate1To2_keepsExistingRowsAndDefaultsStepSource() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO tracks (trackId,startTimeMs,endTimeMs,status,distanceMeters,durationMs,avgSpeedMps,maxSpeedMps," +
                    "stepCount,dominantMode,uploadStatus,gpsPointCount,photoCount,maxAltitudeMeters,minAltitudeMeters," +
                    "totalAscentMeters,totalDescentMeters,startLatitude,startLongitude,endLatitude,endLongitude,modeDistributionJson) " +
                    "VALUES ('legacy-1',1000,2000,'FINISHED',1234.5,60000,1.5,3.0,777,'WALKING','UPLOADED',10,2," +
                    "NULL,NULL,5.0,4.0,NULL,NULL,NULL,NULL,'{}')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        migrated.query(
            "SELECT stepCount, stepSource, status, uploadStatus, distanceMeters FROM tracks WHERE trackId = 'legacy-1'"
        ).use { c ->
            assertTrue("migrated row must still exist", c.moveToFirst())
            assertEquals("existing step count is preserved", 777L, c.getLong(0))
            assertEquals("legacy rows default to SENSOR", "SENSOR", c.getString(1))
            assertEquals("FINISHED", c.getString(2))
            assertEquals("UPLOADED", c.getString(3))
            assertEquals(1234.5, c.getDouble(4), 0.0)
        }
    }

    @Test
    fun migrate1To2_worksOnAnEmptyDatabase() {
        helper.createDatabase(TEST_DB_EMPTY, 1).close()
        helper.runMigrationsAndValidate(TEST_DB_EMPTY, 2, true, AppDatabase.MIGRATION_1_2).close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_EMPTY = "migration-test-empty.db"
    }
}
