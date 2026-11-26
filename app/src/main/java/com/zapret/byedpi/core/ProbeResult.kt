package com.zapret.byedpi.core

enum class ProbeFailure {
    NONE,
    DNS,
    ROUTE,
    TIMEOUT,
    RESET,
    TLS,
    IO,

    /** TLS-рукопожатие прошло, но данных в ответе нет — типичный признак DPI. */
    NO_DATA,
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

    /**
     * NO_DATA тоже считается провалом стратегии, а не сети: рукопожатие
     * прошло, значит маршрут и TLS живы, а полезные данные не доходят.
     */
    val isDpiProblem: Boolean
        get() = failure == ProbeFailure.TIMEOUT ||
            failure == ProbeFailure.RESET ||
            failure == ProbeFailure.TLS ||
            failure == ProbeFailure.IO ||
            failure == ProbeFailure.NO_DATA

    companion object {
        fun ok(code: Int? = null): ProbeResult =
            ProbeResult(true, ProbeFailure.NONE, null, code)

        fun fail(failure: ProbeFailure, detail: String?): ProbeResult =
            ProbeResult(false, failure, detail)
    }
}
