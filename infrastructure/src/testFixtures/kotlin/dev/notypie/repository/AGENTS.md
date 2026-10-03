<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-03 | Updated: 2026-10-03 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/repository

## Purpose
Test doubles for repository transaction boundaries that H2 cannot reproduce. Today: MariaDB snapshot isolation
(`innodb_snapshot_isolation`, ER_CHECKREAD 1020).

## Key Files
| File | Description |
|------|-------------|
| `SnapshotIsolationFixtures.kt` | `SnapshotIsolationTransactionManager` — a `PlatformTransactionManager` that keeps a stack of transactions (`REQUIRES_NEW` pushes, any other propagation joins an open one) and whether each has opened a read view; `consistentRead()` marks the current one, `lockingAccessToRowChangedConcurrently(table)` throws `createSnapshotIsolationFailure(table)` when the current one already read. `createSnapshotIsolationFailure` builds the exception Spring hands callers for 1020: `JpaSystemException` → Hibernate `SnapshotIsolationException` → `SQLException(errorCode = ER_CHECKREAD)`. `createTransactionalProxy<Port>(target, transactionManager)` wraps an `*Impl` in a JDK proxy with a real `TransactionInterceptor` + `AnnotationTransactionAttributeSource`, so the impl's `@Transactional` boundaries apply in a unit spec. |

## For AI Agents

### Working In This Directory
- The fake models the rule, not InnoDB: every locking access after a plain read in the same transaction is
  treated as hitting a row changed concurrently. Stub a plain read with `consistentRead()` and a locking read,
  UPDATE or flush with `lockingAccessToRowChangedConcurrently(...)`; a spec then fails when the code under test
  reads before it locks inside one transaction.
- A call with no open transaction is autocommit here: nothing is marked and nothing throws.
- Call `createTransactionalProxy` with the port type (`createTransactionalProxy<CveTopicRepository>(...)`): the
  proxy implements the interfaces only, so casting to the impl class fails.
- Translation chain behind `createSnapshotIsolationFailure` (read in hibernate-core 7.4.5 and spring-orm 7.0.9
  sources): `MariaDBDialect` maps 1020 to `SnapshotIsolationException`, the JPA layer wraps it in
  `OptimisticLockException`, and `HibernateExceptionTranslator` unwraps the Hibernate cause, has no branch for
  it and falls back to `JpaSystemException`.

### Testing Requirements
Consumed by `repository/cve/CveTopicRepositoryImplTest`.

## Dependencies

### External
Spring AOP / TX (`ProxyFactory`, `TransactionInterceptor`), spring-orm `JpaSystemException`, hibernate-core
`SnapshotIsolationException`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
