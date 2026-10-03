package dev.notypie.repository

import org.hibernate.exception.SnapshotIsolationException
import org.springframework.aop.framework.ProxyFactory
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.SimpleTransactionStatus
import java.sql.SQLException

const val ER_CHECKREAD = 1020

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

fun createSnapshotIsolationFailure(table: String): JpaSystemException =
    JpaSystemException(
        SnapshotIsolationException(
            "could not execute statement [Record has changed since last read in table '$table']",
            SQLException("Record has changed since last read in table '$table'", "HY000", ER_CHECKREAD),
            "update $table",
        ),
    )

inline fun <reified T : Any> createTransactionalProxy(target: T, transactionManager: PlatformTransactionManager): T =
    ProxyFactory(target)
        .apply { addAdvice(TransactionInterceptor(transactionManager, AnnotationTransactionAttributeSource())) }
        .proxy as T
