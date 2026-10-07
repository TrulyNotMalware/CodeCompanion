package dev.notypie.application.common

import org.springframework.transaction.support.TransactionTemplate

inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> =
    runCatching {
        execute<Result<T>> { status -> runCatching { action() }.onFailure { status.setRollbackOnly() } }
    }.getOrElse { commitFailure -> Result.failure(commitFailure) }
