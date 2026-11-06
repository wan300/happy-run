package com.lepao.companion.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.round

/**
 * Login-state lifecycle: health report, `Login/RefreshToken` rotation and pre-run auto renewal.
 *
 * A refresh rotates the pair; the old token is invalidated immediately, so the new pair is written
 * atomically before anything else observes it.
 */
object TokenService {

    private val TOKEN_KEYS = listOf("uid", "access_token", "refresh_token", "refresh_expire")

    fun health(session: SessionContext, now: Long = RunClock.nowEpoch()): LoginHealth {
        val expire = session.refreshExpire
        val days = if (expire != null) round((expire - now) / 86400.0 * 100.0) / 100.0 else null
        val matches = session.refreshAccessToken != null &&
            session.refreshAccessToken == session.identity["token"]
        return LoginHealth(
            uidPresent = session.identity["uid"].isNotBlank(),
            accessTokenPresent = session.identity["token"].isNotBlank(),
            refreshTokenPresent = !session.refreshToken.isNullOrBlank(),
            refreshExpire = expire,
            refreshDaysLeft = days,
            refreshedAt = session.refreshedAt,
            canRefresh = !session.refreshToken.isNullOrBlank(),
            refreshTokenMatchesAccessToken = matches,
        )
    }

    fun buildRefreshRequest(
        session: SessionContext,
        timestamp: Long = RunClock.nowEpoch(),
        nonce: String? = null,
    ): WireRequest {
        val refreshToken = session.refreshToken
        if (refreshToken.isNullOrBlank()) {
            throw SessionException("No refresh_token in the stored context; capture a fresh login")
        }
        val extra = mapOf(
            "access_token" to session.identity["token"],
            "refresh_token" to refreshToken,
        )
        return Protocol.buildRequest(Endpoints.REFRESH, session.identity, extra, timestamp, nonce)
    }

    fun applyRefresh(session: SessionContext, data: JSONObject, now: Long = RunClock.nowEpoch()): SessionContext {
        val missing = TOKEN_KEYS.filter { JsonUtil.optStringOrNull(data, it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw ProtocolException("Refresh response lacks $missing")
        }
        val uid = JsonUtil.optStringOrNull(data, "uid")!!
        if (uid != session.identity["uid"]) {
            throw SessionException("Refresh returned another account")
        }
        val accessToken = JsonUtil.optStringOrNull(data, "access_token")!!
        val refreshToken = JsonUtil.optStringOrNull(data, "refresh_token")!!
        val expire = JsonUtil.optLongOrNull(data, "refresh_expire")
            ?: throw ProtocolException("Refresh response lacks refresh_expire")
        if (expire <= now) throw ProtocolException("Refresh returned an already expired refresh window")
        val updated = session.copy()
        updated.identity.put("token", accessToken)
        updated.refreshToken = refreshToken
        updated.refreshExpire = expire
        updated.refreshAccessToken = accessToken
        updated.refreshedAt = now
        return updated
    }

    fun refresh(context: Context, session: SessionContext): SessionContext {
        val audit = AuditLog(context, File(context.filesDir, "audit/login.log"))
        val transport = ApiTransport(session, write = true, audit = audit)
        val response = transport.api(buildRefreshRequest(session))
        val data = response.optJSONObject("data")
            ?: throw ProtocolException("Refresh response data is not an object")
        val updated = applyRefresh(session, data)
        SessionStore.save(updated)
        DiagLog.info("login refreshed uid=${updated.identity.maskedUid()} daysLeft=${health(updated).refreshDaysLeft}")
        return updated
    }

    data class AutoRefreshOutcome(
        val attempted: Boolean,
        val refreshed: Boolean,
        val reason: String,
        val session: SessionContext,
    )

    /**
     * Refresh only when the pair belongs to the currently used access token and the window is close
     * to expiry. Failure is reported without blocking a run; the caller decides whether to continue.
     */
    fun maybeAutoRefresh(
        context: Context,
        session: SessionContext,
        minDaysLeft: Double = 3.0,
        now: Long = RunClock.nowEpoch(),
    ): AutoRefreshOutcome {
        val state = health(session, now)
        if (!state.canRefresh) {
            return AutoRefreshOutcome(false, false, "no stored refresh_token", session)
        }
        if (!state.refreshTokenMatchesAccessToken) {
            return AutoRefreshOutcome(false, false, "refresh_token belongs to another access token", session)
        }
        val days = state.refreshDaysLeft
        if (days != null && days > minDaysLeft) {
            return AutoRefreshOutcome(false, false, "refresh window still open", session)
        }
        return try {
            val updated = refresh(context, session)
            AutoRefreshOutcome(true, true, "rotated", updated)
        } catch (error: Exception) {
            DiagLog.warn("login refresh failed: ${error.javaClass.simpleName}")
            AutoRefreshOutcome(true, false, "refresh failed: ${error.message}", session)
        }
    }
}
