package dev.notypie.application.common

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> {
    var escaped: Error? = null
    return try {
        execute<Result<T>> { status ->
            try {
                Result.success(action())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                status.setRollbackOnly()
                Result.failure(interrupted)
            } catch (exception: Exception) {
                status.setRollbackOnly()
                Result.failure(exception)
            } catch (error: Error) {
                escaped = error
                throw error
            }
        }
    } catch (rollbackFailure: Exception) {
        val original = escaped ?: return Result.failure(rollbackFailure)
        original.addSuppressed(rollbackFailure)
        throw original
    }
}

internal fun detachedTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
    TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
    }
