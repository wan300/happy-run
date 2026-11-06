package com.lepao.companion.core

/** Base type for every expected, user-visible failure of the companion. */
open class LepaoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A wire contract or a business result that needs explicit handling. */
class ProtocolException(
    message: String,
    cause: Throwable? = null,
    /** Non-null when the server answered with a non-success business status code. */
    val businessStatus: Int? = null,
) : LepaoException(message, cause)

/** The peer may have processed a request whose outcome was never received. */
class DeliveryUncertain(message: String, cause: Throwable? = null) : LepaoException(message, cause)

/** Local state forbids the requested transition. */
class StateException(message: String) : LepaoException(message)

/** Login material is missing, expired or belongs to another account. */
class SessionException(message: String) : LepaoException(message)
