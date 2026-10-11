package com.startinsnow.gpstracker.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.fitness.request.DataReadRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

/**
 * 以 Google Fit（Fitness History API）讀取指定時間區間的步數，作為軌跡的步數來源。
 *
 * 前置條件（缺一不可）：
 * - 裝置已設定 Google Fit，且該帳號有步數資料。
 * - 使用者已同意 `fitness.activity.read` 授權（本 App 於 Google 登入時一併請求）。
 * - Google Cloud 專案已啟用 Fitness API，且同意畫面包含該 scope。
 *
 * 這些屬於外部設定；未滿足時 [stepsBetween] 會回傳失敗，呼叫端應退回裝置感測器步數。
 */
class GoogleFitStepReader(private val context: Context) {

    /** 讀取 [startMs, endMs) 之間的總步數（DataType.TYPE_STEP_COUNT_DELTA 聚合）。 */
    suspend fun stepsBetween(account: GoogleSignInAccount, startMs: Long, endMs: Long): Result<Long> = runCatching {
        val request = DataReadRequest.Builder()
            .aggregate(DataType.TYPE_STEP_COUNT_DELTA, DataType.AGGREGATE_STEP_COUNT_DELTA)
            .setTimeRange(startMs, endMs, TimeUnit.MILLISECONDS)
            .build()
        val response = Fitness.getHistoryClient(context, account).readData(request).await()
        var total = 0L
        for (bucket in response.buckets) {
            for (dataSet in bucket.dataSets) {
                for (point in dataSet.dataPoints) {
                    total += point.getValue(Field.FIELD_STEPS).asInt().toLong()
                }
            }
        }
        total
    }
}
