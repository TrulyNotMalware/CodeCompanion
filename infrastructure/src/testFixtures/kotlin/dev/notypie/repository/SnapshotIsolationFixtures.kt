package dev.notypie.repository

import dev.notypie.configurations.SnapshotIsolationExceptionTranslator
import jakarta.persistence.OptimisticLockException
import org.hibernate.exception.SnapshotIsolationException
import org.springframework.aop.framework.ProxyFactory
import org.springframework.dao.DataAccessException
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.SimpleTransactionStatus
import java.sql.SQLException

class SnapshotIsolationTransactionManager : PlatformTransactionManager {
    private val readViewOpened = ArrayDeque<Boolean>()

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        val joins =
            readViewOpened.isNotEmpty() &&
                definition?.propagationBehavior != TransactionDefinition.PROPAGATION_REQUIRES_NEW
        if (!joins) readViewOpened.addLast(false)
        return SimpleTransactionStatus(!joins)
    }

    override fun commit(status: TransactionStatus) = end(status = status)

    override fun rollback(status: TransactionStatus) = end(status = status)

    fun consistentRead() {
        if (readViewOpened.isNotEmpty()) readViewOpened[readViewOpened.lastIndex] = true
    }

    fun lockingAccessToRowChangedConcurrently(table: String) {
        if (readViewOpened.lastOrNull() == true) throw createSnapshotIsolationFailure(table = table)
    }

    private fun end(status: TransactionStatus) {
        if (status.isNewTransaction) readViewOpened.removeLast()
    }
}

fun createRawSnapshotIsolationFailure(table: String): OptimisticLockException =
    OptimisticLockException(
        "could not execute statement [Record has changed since last read in table '$table']",
        SnapshotIsolationException(
            "could not execute statement [Record has changed since last read in table '$table']",
            SQLException(
                "Record has changed since last read in table '$table'; try restarting transaction",
                "HY000",
                SnapshotIsolationExceptionTranslator.ER_CHECKREAD,
            ),
            "update $table",
        ),
    )

fun createSnapshotIsolationFailure(table: String): DataAccessException =
    checkNotNull(
        HibernateJpaDialect()
            .apply { setJdbcExceptionTranslator(SnapshotIsolationExceptionTranslator()) }
            .translateExceptionIfPossible(createRawSnapshotIsolationFailure(table = table)),
    )

inline fun <reified T : Any> createTransactionalProxy(target: T, transactionManager: PlatformTransactionManager): T =
    ProxyFactory(target)
        .apply { addAdvice(TransactionInterceptor(transactionManager, AnnotationTransactionAttributeSource())) }
        .proxy as T
