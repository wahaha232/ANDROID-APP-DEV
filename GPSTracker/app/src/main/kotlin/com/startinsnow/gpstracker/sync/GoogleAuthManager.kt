package com.startinsnow.gpstracker.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 對應規格「43. Google Drive / OAuth」。Drive 只用於 Backup/Sync，完全不涉及 GPS 記錄流程，
 * 也絕不在本機儲存 Google 密碼——只保存 GoogleSignInAccount 與短效 OAuth Access Token。
 *
 * 需要在 Google Cloud Console 設定對應的 OAuth Client（Android，含正確的 SHA-1 憑證指紋）
 * 才能在實機通過驗證，這部分屬於「待實機 / API Credential 驗證」項目（規格 90）。
 */
class GoogleAuthManager(private val context: Context) {

    private val driveScope = Scope("https://www.googleapis.com/auth/drive.file")

    private val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(driveScope)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    fun signInIntent(): Intent = signInClient.signInIntent

    fun lastSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun signOut(onComplete: () -> Unit) {
        signInClient.signOut().addOnCompleteListener { onComplete() }
    }

    /** 換取短效 OAuth2 Access Token，用於 Drive REST API 呼叫。這是一個阻塞式 IO 呼叫，需在背景執行緒執行。 */
    suspend fun getAccessToken(account: GoogleSignInAccount): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val androidAccount: Account = account.account
                ?: throw IllegalStateException("Google 帳號無法取得 Android Account 物件")
            GoogleAuthUtil.getToken(context, androidAccount, "oauth2:${driveScope.scopeUri}")
        }
    }

    /** 登出時一併清掉快取的 OAuth token，避免登出後仍能用舊 token 上傳。 */
    suspend fun clearAccessToken(account: GoogleSignInAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val androidAccount = account.account ?: return@runCatching
            val scope = "oauth2:${driveScope.scopeUri}"
            // 先取得（可能是快取的）access token 字串，再明確清除，
            // 否則 GoogleAuthUtil.clearToken 無法指定要清哪一個 token。
            val token = runCatching { GoogleAuthUtil.getToken(context, androidAccount, scope) }.getOrNull()
            if (!token.isNullOrBlank()) {
                GoogleAuthUtil.clearToken(context, token)
            }
        }
    }
}
