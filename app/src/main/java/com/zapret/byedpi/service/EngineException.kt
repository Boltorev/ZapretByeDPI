package com.zapret.byedpi.service

/**
 * Ошибка запуска/остановки нативного движка.
 * Сообщение показывается пользователю в состоянии ERROR и пишется в журнал.
 */
class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
