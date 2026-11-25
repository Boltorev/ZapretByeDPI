package com.zapret.byedpi.core

enum class ProbeFailure {
    NONE,
    DNS,
    ROUTE,
    TIMEOUT,
    RESET,
    TLS,
    IO,
    HTTP
}

data class ProbeResult(
    val ok: Boolean,
    val failure: ProbeFailure = ProbeFailure.NONE,
    val detail: String? = null,
    val httpCode: Int? = null
) {
    val isNetworkProblem: Boolean
        get() = failure == ProbeFailure.DNS || failure == ProbeFailure.ROUTE

    val isDpiProblem: Boolean
        get() = failure == ProbeFailure.TIMEOUT ||
            failure == ProbeFailure.RESET ||
            failure == ProbeFailure.TLS ||
            failure == ProbeFailure.IO

    companion object {
        fun ok(code: Int? = null): ProbeResult =
            ProbeResult(true, ProbeFailure.NONE, null, code)

        fun fail(failure: ProbeFailure, detail: String?): ProbeResult =
            ProbeResult(false, failure, detail)
    }
}
