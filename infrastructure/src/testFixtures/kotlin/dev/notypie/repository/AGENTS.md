<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-03 | Updated: 2026-10-03 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/repository

## Purpose
Test doubles for repository transaction boundaries that H2 cannot reproduce. Today: MariaDB snapshot isolation
(`innodb_snapshot_isolation`, ER_CHECKREAD 1020).

## Key Files
| File | Description |
|------|-------------|
| `SnapshotIsolationFixtures.kt` | `SnapshotIsolationTransactionManager` — a `PlatformTransactionManager` that keeps a stack of transactions (`REQUIRES_NEW` pushes, any other propagation joins an open one) and whether each has opened a read view; `consistentRead()` marks the current one, `lockingAccessToRowChangedConcurrently(table)` throws `createSnapshotIsolationFailure(table)` when the current one already read. `createRawSnapshotIsolationFailure(table)` is what Hibernate's EntityManager throws for 1020 (`OptimisticLockException` → `SnapshotIsolationException` → `SQLException("HY000", SnapshotIsolationExceptionTranslator.ER_CHECKREAD)`); `createSnapshotIsolationFailure(table)` runs it through a `HibernateJpaDialect` carrying the production `SnapshotIsolationExceptionTranslator`, i.e. the `SnapshotIsolationConflictException` repository proxies now hand callers. `createTransactionalProxy<Port>(target, transactionManager)` wraps an `*Impl` in a JDK proxy with a real `TransactionInterceptor` + `AnnotationTransactionAttributeSource`, so the impl's `@Transactional` boundaries apply in a unit spec. |

## For AI Agents

### Working In This Directory
- The fake models the rule, not InnoDB: every locking access after a plain read in the same transaction is
  treated as hitting a row changed concurrently. Stub a plain read with `consistentRead()` and a locking read,
  UPDATE or flush with `lockingAccessToRowChangedConcurrently(...)`; a spec then fails when the code under test
  reads before it locks inside one transaction.
- A call with no open transaction is autocommit here: nothing is marked and nothing throws.
- Call `createTransactionalProxy` with the port type (`createTransactionalProxy<CveTopicRepository>(...)`): the
  proxy implements the interfaces only, so casting to the impl class fails.
- Translation chain behind the raw form (read in hibernate-core 7.4.5 and spring-orm 7.0.9 sources):
  `MariaDBDialect` maps 1020 to `SnapshotIsolationException`, the JPA layer wraps it in `OptimisticLockException`,
  and `HibernateExceptionTranslator` unwraps the Hibernate cause and asks its JDBC translator first — the
  production `SnapshotIsolationExceptionTranslator`; without one it falls back to `JpaSystemException`.

### Testing Requirements
Consumed by `repository/cve/CveTopicRepositoryImplTest`, `repository/meeting/MeetingReminderRepositoryImplTest`, `repository/meeting/MeetingWriteConflictTest`, `configurations/SnapshotIsolationExceptionTranslatorTest` and, in `:application`, `MeetingServiceImplTest` and `ApplicationContextSmokeTest`.

## Dependencies

### External
Spring AOP / TX (`ProxyFactory`, `TransactionInterceptor`), spring-orm `HibernateJpaDialect`, Jakarta
`OptimisticLockException`, hibernate-core `SnapshotIsolationException`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
