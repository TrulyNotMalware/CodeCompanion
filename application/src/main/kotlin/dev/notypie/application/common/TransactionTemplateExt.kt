package dev.notypie.application.common

import org.springframework.transaction.support.TransactionTemplate

/**
 * Runs [action] inside the receiver's transaction boundary and converts thrown exceptions
 * into a failed [Result], simultaneously marking the transaction for rollback. The wrapper
 * collapses the repeated boilerplate of `execute<Result<T>> { runCatching { ... }.onFailure
 * { setRollbackOnly() } } ?: Result.failure(...)` into a single intent-revealing call.
 *
 * The defensive null fallback preserves the original behavior — `TransactionTemplate.execute`
 * declares `T?` because the underlying `TransactionCallback` can return null; in our usage we
 * always return a non-null [Result], so a null surfaces as an `IllegalStateException` failure
 * rather than crashing the caller with a NullPointerException.
 */
inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> =
    execute<Result<T>> { status ->
        runCatching { action() }.onFailure { status.setRollbackOnly() }
    } ?: Result.failure(IllegalStateException("transactionTemplate.execute returned null"))
