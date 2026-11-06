package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.ApiTransport
import com.lepao.companion.core.AuditLog
import com.lepao.companion.core.Endpoints
import com.lepao.companion.core.Protocol
import com.lepao.companion.core.ProtocolException
import com.lepao.companion.core.RunClock
import com.lepao.companion.core.RunRules
import com.lepao.companion.core.SessionContext
import com.lepao.companion.core.SessionStore
import java.io.File

/** Reads run configuration and applies an explicitly selected run zone. */
object ConfigService {

    fun fetch(context: Context, session: SessionContext): RunRules {
        val audit = AuditLog(context, File(context.filesDir, "audit/config.log"))
        val transport = ApiTransport(session, write = false, audit = audit)
        val request = Protocol.buildRequest(Endpoints.BEFORE_RUN, session.identity, emptyMap(), RunClock.nowEpoch())
        val response = transport.api(request)
        val data = response.optJSONObject("data")
            ?: throw ProtocolException("beforeRun response data is not an object")
        return RunRules.parse(data)
    }

    fun selectEastCampus(context: Context, session: SessionContext): RunRules {
        val current = fetch(context, session)
        val schoolId = session.identity["school_id"]
        if (!EastCampusZone.supports(current, schoolId)) {
            throw ProtocolException("当前学校不适用北京交通大学东校区跑区")
        }
        if (EastCampusZone.isSelected(current)) return current
        val target = EastCampusZone.targetId(current)
            ?: throw ProtocolException("配置中未找到唯一的东校区跑区")
        val audit = AuditLog(context, File(context.filesDir, "audit/config.log"))
        val transport = ApiTransport(session, write = true, audit = audit, persist = SessionStore::save)
        transport.api(
            Protocol.buildRequest(
                Endpoints.SET_RUN_ZONE,
                session.identity,
                mapOf("run_zone_id" to target),
                RunClock.nowEpoch(),
            ),
        )
        val confirmed = fetch(context, session)
        if (!EastCampusZone.supports(confirmed, schoolId) ||
            confirmed.runZoneId != target || !EastCampusZone.isSelected(confirmed)
        ) throw ProtocolException("服务端尚未确认东校区跑区，请刷新配置核对")
        return confirmed
    }
}
