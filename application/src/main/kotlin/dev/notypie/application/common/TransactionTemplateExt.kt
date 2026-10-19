package dev.notypie.application.common

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionTemplate

inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T> =
    try {
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
            }
        }
    } catch (rollbackFailure: TransactionSystemException) {
        val application = rollbackFailure.applicationException
        if (application is Error) {
            application.addSuppressed(rollbackFailure)
            throw application
        }
        Result.failure(rollbackFailure)
    } catch (commitFailure: Exception) {
        Result.failure(commitFailure)
    }

internal fun detachedTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
    TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
    }
