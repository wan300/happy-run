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
import java.io.File

/** Reads the account's current run configuration through a read-only transport. */
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
}
