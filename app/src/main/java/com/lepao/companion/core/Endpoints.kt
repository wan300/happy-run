package com.lepao.companion.core

/**
 * Wire contract table verified against the 2026-11-02 phone capture.
 *
 * Do not infer the envelope from the signature family: location and gyroscope use the newer
 * signature but remain ordinary forms in the actual capture.
 */
data class Contract(
    val newerSignature: Boolean = false,
    val encrypted: Boolean = false,
    val dynamic: Boolean = false,
)

object Endpoints {
    const val API_BASE = "https://api2.lptiyu.com/v3/api.php/"
    const val OSS_ENDPOINT = "https://lptiyu-data.oss-cn-hangzhou.aliyuncs.com/"
    const val OSS_BUCKET = "lptiyu-data"

    const val BEFORE_RUN = "Run2/beforeRunV260"
    const val SET_RUN_ZONE = "Run/setRunZone"
    const val START = "Run/getTimestampV278"
    const val LOCATION = "Run/setRunLocationRecord"
    const val OSS_STS = "report/getOssSts"
    const val STOP = "Run/stopRunV278"
    const val GYROSCOPE = "Run2/gyroscope"
    const val RECORD_DETAIL = "Run/recordDetailV270"
    const val REFRESH = "Login/RefreshToken"

    /** Read-only endpoints may be called without enabling the write transport. */
    val READ_ONLY: Set<String> = setOf(BEFORE_RUN, RECORD_DETAIL)

    val CONTRACTS: Map<String, Contract> = mapOf(
        BEFORE_RUN to Contract(newerSignature = true, encrypted = true, dynamic = true),
        SET_RUN_ZONE to Contract(),
        START to Contract(),
        LOCATION to Contract(newerSignature = true, dynamic = true),
        OSS_STS to Contract(newerSignature = true, encrypted = true, dynamic = true),
        STOP to Contract(newerSignature = true, encrypted = true, dynamic = true),
        GYROSCOPE to Contract(newerSignature = true, dynamic = true),
        RECORD_DETAIL to Contract(),
        REFRESH to Contract(newerSignature = true, encrypted = true, dynamic = true),
    )

    fun contract(endpoint: String): Contract =
        CONTRACTS[endpoint] ?: throw ProtocolException("Unknown endpoint: $endpoint")
}
