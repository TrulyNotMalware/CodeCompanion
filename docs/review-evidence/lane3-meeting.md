## 3차 코드 리뷰: 회의·JPA·마이그레이션 레인

**대상**: `feature/review-critical-fixes` 브랜치의 `cca9984`와 그 이전 브랜치 커밋.

**확인 방법**
- 범위 안의 소스와 테스트를 직접 열람했습니다.
- spring-orm 7.0.9, spring-boot-jpa 4.1.1, hibernate-core 7.4.5 jar를 `javap`로 확인했습니다.
- 최신 빌드의 테스트 XML을 확인했습니다(2026-09-28T08:37Z). `MeetingWriteJpaTransactionTest`는 8/8, `SlackInteractionHandlerImplTest`는 15/15, `MeetingServiceImplTest`는 20/20 통과입니다. 인프라 쪽 결과 디렉터리는 백그라운드 빌드가 다시 만드는 중이라 비어 있었습니다.
- 테스트는 재실행하지 않았고 lsp_diagnostics도 돌리지 않았습니다. 컴파일과 테스트 통과는 위 XML로 대신합니다.

**결론(요약)**
- S8·S8b·S18·N1·N2는 해결됐습니다.
- S10은 조회 쿼리 쪽만 해결됐습니다.
- OSIV 영향: 운영 HTTP 경로에서는 상호작용 tx와 지연 쓰기 tx가 같은 EntityManager와 같은 JDBC 커넥션을 씁니다. 재시도는 롤백 시 EM이 비워지므로 올바르게 동작합니다. 다만 테스트와 문서는 이 구조를 모델링하지 않았습니다(M1).
- 커넥션 풀 교착은 현재 운영 경로에서 일어나지 않습니다.
- 13.5의 배포 체크리스트에 V18·V19가 빠져 있습니다(M3).

---

### 1. 13.5 판정표

| 항목 | 판정 | 근거 file:line | 설명 |
|---|---|---|---|
| **S8** reschedule 낙관 락 무력화 | **RESOLVED** | `MeetingRepositoryImpl.kt:74-80, 82-95, 97-100, 139-140`<br>`MeetingSchema.kt:37-44, 71-79`<br>`JpaMeetingRepository.kt` 전체(`@Modifying`은 `:74-92` 참가자 attendance 1건뿐) | **동작 방식**: cancel·reschedule·add 세 쓰기 모두 관리 엔티티를 바꾼 뒤 `saveAndFlush`를 부릅니다. `clearAutomatically`·`OPTIMISTIC_FORCE_INCREMENT`·`findMeetingByUidForUpdate`는 grep 결과 0건입니다. `meetings` 테이블에 대한 벌크 UPDATE도 0건입니다.<br>**참가자만 추가할 때의 버전 증가**: `participants`는 inverse(`mappedBy`) bag이고 `@OptimisticLock(excluded=false)`가 명시돼 있어 Hibernate가 dirty collection으로 버전을 올립니다(`MeetingRepositoryWriteTest:132`, `MeetingWriteJpaTransactionTest:113,167`에서 버전 0→1 확인).<br>**경합**: cancel과 add의 경합은 `MeetingRepositoryWriteTest:543-565`, reschedule끼리의 경합은 `:453-480`이 고정합니다. |
| **S8b** 락 패자 500·피드백 0 | **RESOLVED** (M1 주의) | `SlackInteractionHandlerImpl.kt:51-59, 61-76`<br>`MeetingWriteDeferral.kt:6-20`<br>`MeetingServiceImpl.kt:145, 193-194, 346-364`<br>`MeetingRescheduleService.kt:52, 58-72` | **메커니즘**: afterCommit 동기화나 `@TransactionalEventListener`가 아닙니다. ThreadLocal 큐에 쓰기를 모아 두었다가, 프로그래밍 방식의 `commit(status)`가 반환된 뒤 실행합니다. 이 시점에는 `doCleanupAfterCompletion`까지 끝나 있으므로 "afterCommit 안의 쓰기가 커밋되지 않을 수 있다"는 Spring 함정에 해당하지 않습니다. 활성 tx가 없는 상태의 `REQUIRES_NEW`는 새 tx가 됩니다.<br>**예외 처리**: 커밋까지 포함한 `executeWithoutResult`를 `attempt`가 감싸고, `saveAndFlush`가 충돌을 `attempt` 안에서 조기에 드러냅니다. 따라서 충돌은 잡히고 1회 재시도하며, 두 번 다 실패하면 `replyTemplate`로 회신합니다.<br>**원자성**: 성공 회신과 outbox 저장은 같은 쓰기 tx 안에서 커밋됩니다(`SlackMessageRelayServiceImpl.kt:187` BEFORE_COMMIT). 상호작용 tx가 실패하면 큐에 쌓인 쓰기는 버려집니다(`SlackInteractionHandlerImplTest:142-152`).<br>**OSIV에서의 재시도**: `JpaTransactionManager.doRollback`은 새로 만든 EM이 아니면 `em.clear()`를 호출합니다(바이트코드로 확인). 따라서 두 번째 시도는 DB에서 새로 읽습니다. |
| **S10** 참가자 0명 회의 소실 | **부분 해결**(조회 쿼리는 해결) | `JpaMeetingRepository.kt:19, 33, 44, 62, 110` (전부 LEFT, DISTINCT 제거)<br>잔존: `JpaMeetingReminderRepository.kt:17-26`<br>`MeetingFormInput.kt:66-74`<br>`MeetingReminderRepositoryImpl.kt:21-24`<br>`AgendaDispatchRepositoryImpl.kt:22-25` | **해결된 부분**: `getMeeting`과 `/meetup list`에서 호스트만 있는 회의가 이제 보입니다. EXISTS 서브쿼리는 fetch 별칭이 아닌 `p.meeting = m` 기준이라 정확합니다.<br>**남은 부분**: 13.2가 증상으로 적은 "리마인더·아젠다 대상 제외"는 그대로입니다. 수신자가 attending 참가자뿐이고 호스트는 participants에서 빠지기 때문입니다(설계 문제). `findPendingBefore`에는 INNER + `DISTINCT`가 남아 있습니다(의미상 무해, M4·M5). |
| **S18** reschedule 과거 시각·V18 보정 없음 | **RESOLVED** | `MeetingRescheduleService.kt:44-51`<br>`MeetingSchema.kt:76-79`<br>`V21__fix_inverted_meeting_end_at.sql:36-38` | **과거 시각**: 이벤트 시점에 상호작용 tx 안에서 거부하고 회신합니다. 비교 기준은 `LocalDateTime.now(clock).truncatedTo(MINUTES)`이고 `clock`은 `Clock.systemDefaultZone()`입니다(`SlackRequestVerificationConfiguration.kt:16`). JVM 존은 `application/Dockerfile:20`에서 `Asia/Seoul`로 고정돼, 생성 경로(`MeetingFormInput.kt:129`, `Meeting.kt:30`)와 같은 존·같은 경계로 동작합니다. 현재 분(分)은 거부하고 다음 분은 허용하며, `MeetingRescheduleServiceTest:31, 265-300`이 이를 고정합니다.<br>**end_at**: 뒤집힌 `end_at`은 reschedule 시 NULL로 떨어뜨리고, 정상 행은 같은 간격만큼 함께 이동합니다. |
| **N1** agenda claim 원자화 | **RESOLVED** | `DailyAgendaSchedulingService.kt:54-79`<br>`AgendaDispatchRepositoryImpl.kt:11-12`<br>`TransactionTemplateExt.kt:6-9` | claim(`INSERT IGNORE`), 조회, outbox 저장이 한 `runInTx` 안에서 일어나므로 실패하면 claim도 함께 롤백됩니다. 테스트에 H2 tx 매니저를 쓰는 케이스가 추가됐습니다(`DailyAgendaSchedulingServiceTest:269-272`). |
| **N2** reschedule 시 endAt 보정 | **RESOLVED** | `MeetingSchema.kt:76-79`, V21 | 간격을 유지하고, 뒤집힌 값은 NULL(기본 1시간)로 바꿉니다.<br>**참고 1**: 이 규칙은 도메인 `Meeting`이 아니라 인프라 스키마 클래스에 있습니다(설계 메모).<br>**참고 2**: main에서 "더 이른 시각으로" 옮겨진 과거 행은 duration이 부풀어 있지만 SQL로는 판별할 수 없어 V21이 고치지 못합니다. |

---

### 2. 신규 결함 표

| ID | 심각도 | 제목 | file:line | 구체적 실패 시나리오 | 도입 | 확신도 | 수정 방향 |
|---|---|---|---|---|---|---|---|
| **M1** | Medium | **OSIV에서 지연 쓰기 첫 시도가 상호작용 tx의 영속성 컨텍스트를 그대로 물려받는데, 테스트와 문서는 반대로 주장함** | `SlackInteractionHandlerImpl.kt:55-57`<br>`MeetingServiceImpl.kt:346-349`<br>`MeetingWriteJpaTransactionTest.kt:59-62, 212-223`<br>`service/meeting/AGENTS.md:42, 46, 50`<br>`MeetingTransactionFixtures.kt:46-47, 128-150`<br>`SlackInteractionHandlerImplTest.kt:65` | **구조**: Boot 4.1.1은 OSIV를 기본으로 켭니다(`JpaBaseConfiguration$JpaWebConfiguration`, 메인 세션이 부팅 WARN으로 확인). HTTP 요청마다 EM이 하나 바인딩됩니다. 상호작용 tx는 이 EM을 재사용하고(`doBegin`: 동기화되지 않은 기존 holder 재사용), 커밋 때 EM을 비우지 않습니다. 지연 쓰기의 `REQUIRES_NEW`는 외부 tx가 없으므로 같은 OSIV EM 위에서 새 tx로 시작합니다. 즉 첫 시도는 상호작용 tx에서 관리되던 엔티티를 그대로 보고, 쿼리 결과로 같은 행이 와도 상태를 갱신하지 않습니다.<br>**현재 영향**: 상호작용 tx가 `MeetingSchema`를 로드하지 않으므로 잠복 상태입니다.<br>**터질 수 있는 경우**: 나중에 상호작용 tx 안에 사전 권한 확인처럼 회의를 읽는 코드가 들어가면, 첫 시도는 오래된 상태로 쓰기 없는 결과(`AlreadyAtRequestedTime`, `NO_NEW_PARTICIPANTS`, `OVER_CAPACITY`, `MEETING_STARTED`)를 내고 버전 검사 없이 틀린 회신을 보냅니다.<br>**테스트 공백**: `MeetingWriteJpaTransactionTest`는 외부 `TransactionTemplate` 안에서 서비스를 인라인 호출합니다(운영에서는 쓰지 않는 경로). 그래서 "시도마다 별도 EM, 상호작용 EM과 다름"을 검증하는데, 운영 HTTP 경로에서는 이 전제가 거짓입니다. `SlackInteractionHandlerImplTest`는 `DataSourceTransactionManager`에 목을 씁니다. 결국 지연 경로를 실제 `JpaTransactionManager`와 OSIV로 돌린 테스트가 0건입니다. | cca9984 | 메커니즘 HIGH(바이트코드), 영향 MEDIUM(잠복) | `spring.jpa.open-in-view: false`를 명시합니다. 스케줄러와 socket 경로는 이미 OSIV 없이 동작하지만, HTTP 경로의 지연 로딩 의존 여부는 먼저 확인해야 합니다. 또는 지연 쓰기 실행 전에 바인딩된 EM을 `clear()`합니다. `EntityManagerHolder`를 바인딩해 OSIV를 흉내 낸 상태로 `handleInteraction`을 도는 JPA 테스트를 추가하고, AGENTS 서술을 고칩니다. |
| **M2** | Low | **풀 교착 판정: 현재 운영 경로에서는 불가. 그러나 운영 설정 주석과 문서가 옛 설계(2커넥션)를 서술함** | `application-prod.yaml:16-17`<br>`resources/AGENTS.md:57-59`<br>`service/meeting/AGENTS.md:42`<br>`SlackInteractionHandlerImpl.kt:52-53` | **교착이 안 되는 이유**<br>• HTTP: `HibernateJpaVendorAdapter`가 `DELAYED_ACQUISITION_AND_HOLD`를 설정하고 `HibernateJpaDialect`는 커넥션을 반환하지 않습니다. 그래서 OSIV 세션이 요청 끝까지 한 커넥션을 붙잡고, 상호작용 tx·지연 쓰기·재시도·실패 회신이 모두 그 커넥션을 공유합니다. 요청당 1개입니다.<br>• Socket Mode: EM이 순차로 생성·종료돼 동시에 1개만 씁니다.<br>**문서와의 차이**: "Meeting writes hold two connections"와 "×2 사이징 규칙"은 8504c07 설계에 대한 서술이라 이제 틀렸습니다. "released its connection"도 OSIV에서는 거짓입니다(요청 끝까지 보유).<br>**남은 위험**: `isActualTransactionActive()` 인라인 분기는 여전히 2커넥션을 쓰는 패턴입니다. 운영 호출자는 없지만, 누군가 `handleInteraction`을 tx로 감싸면 교착이 다시 생기고, 상호작용이 롤백돼도 회의 쓰기는 이미 커밋된 상태가 됩니다. | cca9984(문서 미갱신) | HIGH | 주석과 사이징 규칙을 "요청당 1커넥션(OSIV 보유)"으로 고칩니다. 인라인 분기는 `check(!isActualTransactionActive())` 같은 방식으로 막거나 사유를 문서화합니다. |
| **M3** | Medium | **13.5 배포 체크리스트에 V18·V19 누락, 번호와 적용 순서 불일치** | `review.md:1754`<br>`migration/AGENTS.md:7`<br>`V18__...sql:25-27`<br>`dev-environment.md:123`("origin/main은 V17") | prod는 `ddl-auto: none`이고 새 바이너리는 `MeetingSchema.version`을 매핑합니다. V18 없이 배포하면 모든 `meetings` SELECT가 `Unknown column ...version`으로 실패해 회의 기능 전체가 500이 됩니다. 13.5 2단계는 "V20 → V22 → V21"만 적고 V18은 "이후"라는 전제로만 언급합니다(V18·V19는 12.3의 `:1586`에만 나옴). 또 AGENTS는 "Ordered patch scripts"라고 하는데 V21은 V22 뒤에 적용해야 하므로, 번호 순서대로 적용하면 새 바이너리가 뜨기 전에 V21이 먼저 실행됩니다. | cca9984(문서) | HIGH(누락 자체), 실제 prod 상태는 미확인 | 체크리스트를 다음 순서로 고칩니다: V18(중복 사전 점검) → V19 → V20 → V22 → 구 파드 종료 후 배포 → V21. AGENTS에 "번호 ≠ 적용 순서" 예외를 명시합니다. |
| **M4** | Low | **문서·주석 드리프트** | `V18__...sql:8-10`<br>`migration/AGENTS.md:87`<br>`repository/meeting/AGENTS.md:74-75, 100-102`(대조: `JpaMeetingReminderRepository.kt:17-26`)<br>`V21__...sql:26-29` | • V18 헤더가 여전히 "OPTIMISTIC_FORCE_INCREMENT로 잠근다"고 서술합니다.<br>• 회의 스키마 의존 목록에 V18·V21이 없습니다.<br>• "모든 읽기는 LEFT JOIN FETCH, DISTINCT 없음"이라고 하지만 `findPendingBefore`는 INNER + DISTINCT입니다.<br>• V21 헤더의 "구 바이너리가 이전에 읽은 행으로 end_at을 재계산"은 배포된 적 없는 8504c07의 동작입니다. 실제 구 바이너리(main)는 `start_at`만 바꿔서 V21 실행 뒤에도 새로 뒤집힌 행을 만들 수 있고, 그것이 진짜 대기 사유입니다. | cca9984 / 8504c07 | HIGH | 서술을 정정합니다. |
| **M5** | Low | **리마인더 조회가 컬렉션 fetch와 Pageable을 함께 써서 페이징이 메모리에서 일어남** | `JpaMeetingReminderRepository.kt:17-32`<br>`MeetingReminderRepositoryImpl.kt:71-73` | `JOIN FETCH m.participants`와 `PageRequest.of(0, limit)`을 함께 쓰면 Hibernate가 SQL LIMIT 없이 기한이 지난 PENDING 리마인더와 참가자를 전부 읽은 뒤 메모리에서 자릅니다(`fail_on_pagination_over_collection_fetch` 기본값 false). 60초마다 백로그 전체를 적재합니다. | main 기존 | MEDIUM | id만 먼저 페이징 조회한 뒤 fetch하거나, meeting만 fetch하고 participants는 batch-size로 읽습니다. |
| **M6** | Low | **materialize와 reschedule의 경합: 옛 시각의 리마인더가 발송되고, 새 시각의 리마인더는 영구 누락** | `MeetingReminderSchedulingService.kt:57-58, 71-75`<br>`MeetingReminderRepositoryImpl.kt:29-45`<br>`MeetingRescheduleService.kt:93` | ① materialize가 옛 `startAt` T로 후보를 읽습니다. ② reschedule이 커밋되면서 리마인더를 삭제합니다. ③ `ensureReminder`가 T-10분으로 행을 삽입합니다. ④ T-10분에 "10분 후 시작(새 시각 T')"이라는 DM이 틀린 시각에 나갑니다. ⑤ `(meeting, offset)` UNIQUE 행이 이미 SENT 상태로 있어 T'용 리마인더는 다시 만들어지지 않습니다. 창은 짧지만(곧 시작할 회의를 옮길 때) 결과가 영구적입니다. | main 기존 | MEDIUM | 발송 시점에 `scheduledAt == startAt - offset`을 재검증해 어긋나면 폐기·재생성합니다. 또는 리마인더에 `meeting_start_at` 스냅샷 컬럼을 둡니다. |
| **M7** | Low | **claim 이후 취소를 재확인하지 않음** | `MeetingReminderSchedulingService.kt:107-138`(`item.isCanceled` 미사용)<br>`MeetingReminderRepositoryImpl.kt:80` | `findDueBefore` 이후 발송 tx 사이에 cancel이 커밋되면 취소된 회의의 리마인더 DM이 나갑니다. | main 기존 | MEDIUM | claim 또는 `markSent` UPDATE에 `EXISTS (SELECT 1 FROM meetings WHERE id = meeting_id AND is_canceled = 0)` 조건을 붙입니다. |
| **M8** | Low | **상호작용 tx 수동 경계의 예외 처리 틈** | `SlackInteractionHandlerImpl.kt:66-72`<br>`MeetingWriteDeferral.kt:10-14`<br>`MeetingServiceImpl.kt:168-174, 225-231`<br>`MeetingRescheduleService.kt:65-71` | • catch 경로에서 `rollback`이나 `commit`이 다시 던지면 원래 예외가 가려집니다.<br>• 롤백 대상이 아닌(checked) 예외가 나면 tx는 커밋되는데 `collecting`이 큐를 버려서, 상호작용은 커밋되고 회의 쓰기는 사라집니다. 현재 경로에 checked 예외 발생원이 없어 도달 불가입니다.<br>• 실패 회신 tx마저 실패하면(DB 장애) 예외가 `meetingWrites.forEach` 밖으로 나가 상호작용 커밋 뒤 HTTP 500이 됩니다. | cca9984 | MEDIUM | 원래 예외에 `addSuppressed`로 붙이고, non-rollback 커밋 경로에서도 큐를 실행하거나 명시적으로 로그를 남깁니다. |
| **M9** | Low | **구 바이너리와 공존 시 lost update**(공존은 이미 금지된 조건) | `MeetingSchema.kt:23-84`(`@DynamicUpdate` 없음)<br>main의 벌크 cancel·reschedule | V18 적용 뒤 main 파드가 함께 떠 있으면, main의 벌크 UPDATE는 `version`을 올리지 않습니다. 새 바이너리의 전체 컬럼 UPDATE가 그 사이의 `start_at` 변경을 옛 값으로 덮어써 reschedule이 사라지고, cancel과 add 경합 방지도 무력화됩니다. outbox 제약 때문에 이미 "공존 금지"이므로 실제 위험은 낮습니다. | 브랜치(8504c07~) | HIGH(조건부) | 13.5의 "동시 기동 금지"에 회의 쓰기 사유를 함께 적습니다. |

---

### 3. 확인했고 문제 없던 것

- **지연 경로 전반**: tx 동기화가 없는 경로도 문제없습니다. Socket Mode(`SocketModeReceiver.kt:147`)는 같은 `collecting` 경로를 탑니다. 이 세 이벤트의 발생원은 상호작용 컨텍스트 세 곳뿐입니다(`CancelMeetingContext.kt:39`, `RescheduleMeetingSubmissionContext.kt:22`, `AddParticipantSubmissionContext.kt:22`). 셋 다 `isInternal=true`라 동기 발행입니다. `OutboundMessageEnqueued`도 internal이라 롤백된 시도가 Kafka로 새지 않습니다. 컨트롤러와 핸들러에는 `@Transactional`도 AOP도 없습니다.
- **지연 쓰기 실패 시 피드백 정합성**: 상호작용 tx는 이 세 경로에서 성공 메시지를 스테이징하지 않습니다. `SubmissionContext.kt:25-31`과 `CancelMeetingContext`는 메시지가 없는 success를 돌려줍니다. 성공 회신은 쓰기 tx와 원자적이고, 실패 시에는 "try again" 하나만 나갑니다.
- **충돌 분류**: 분류 로직(`MeetingWriteConflict.kt:9-16`)은 `ConcurrencyFailureException` 전체를 다룹니다. 여기에는 MariaDB 데드락(1213)이 번역되는 `CannotAcquireLockException`, 그리고 참가자 UNIQUE 이름을 담은 DIVE가 포함됩니다. 키 이름이 V18(`:41-42`)과 스키마(`MeetingSchema.kt:145-150`)에서 일치합니다.
- **도메인과 쿼리 정합**
  - capacity 검사(`MeetingRepositoryImpl.kt:133`)는 호스트를 뺀 인원 ≤ 20으로, 도메인 불변식과 같습니다.
  - V21의 `end_at <= start_at`는 도메인의 strict `endAt shouldBeAfter startAt`(`Meeting.kt:31`)과 `reschedule()`의 `isAfter` 규칙에 맞습니다. MariaDB 문법이고 멱등하며, 버전을 올리는 이유가 테스트로 고정돼 있습니다(`MeetingWriteJpaTransactionTest:241-303`, H2에서 실제 V21 SQL 실행).
  - V18의 UNIQUE는 중복 사전 점검 쿼리를 헤더에 담고 있습니다(`:19-23`). DDL은 비트랜잭션이라 실패하면 앞 단계만 적용된 상태로 남지만, `IF NOT EXISTS`라 재실행해도 안전합니다.
  - LEFT JOIN FETCH로 단건을 조회할 때 Hibernate 7이 fetch 루트를 중복 제거하므로 NonUnique 예외는 없습니다. bag은 하나뿐이라 MultipleBag 문제도 없습니다.
- **리마인더 claim과 reschedule·cancel 경합**: `markSent`는 토큰 CAS이고 실패하면 outbox를 롤백하므로(`MeetingReminderSchedulingService.kt:129-137`) 삭제된 리마인더가 이중 발송되지 않습니다. 남는 두 경합은 M6·M7로 정리했습니다.
- **재제출 멱등성**: 같은 요청을 다시 보내면 세 쓰기 모두 버전을 올리지 않는 no-op이고, 중립 회신만 나갑니다.
- **tx 매니저 빈**: `PlatformTransactionManager`는 JPA 하나뿐입니다(Kafka TM 없음).

### 4. 미검증으로 남긴 것

- **실제 MariaDB에서만 확인 가능한 동작**
  - InnoDB는 FK 검사 때 부모 `meetings` 행에 S 잠금을 겁니다. 이 때문에 동시 add 두 건이 버전 충돌이 아니라 데드락(1213)으로 끝날 가능성이 큽니다. 분류상 재시도 대상이지만, 재시도가 1회뿐이라 3건 이상 동시면 실패 회신이 나갑니다.
  - prod `meeting_participants`에 FK가 실제로 있는지.
  - Connector/J 3.5.10의 1062 메시지 형식. 테스트는 합성 메시지와 H2 메시지만 씁니다.
  - `innodb_lock_wait_timeout`(50초) 동안 Slack 3초 ack를 넘기는 경우.
  - `SQL_PROD_ISOLATION_LEVEL`의 실제 값. H2 기본은 READ COMMITTED이고 MariaDB 기본은 REPEATABLE READ이며, gap·next-key 잠금은 H2에 없습니다.
  - V18·V21을 실제 데이터에 적용했을 때의 결과.
  - 리마인더 `CURRENT_TIMESTAMP`(DB 세션 존)와 바인딩된 `Instant`의 존 정합(S2와 같은 유형). 리마인더는 토큰 CAS 덕분에 중복 발송까지는 가지 않는다고 봅니다.
- **실행으로 확인하지 않은 것**
  - M1의 오래된 엔티티 재사용 시나리오. 바이트코드와 Hibernate 의미로 판정했습니다.
  - OSIV를 끌 때 HTTP 경로의 지연 로딩 의존 여부.
  - prod 프로파일의 Spring 컨텍스트 부팅(메인 세션은 local 프로파일로만 기동).
  - Slack timepicker의 사용자 타임존 의미. 앱 전체가 존 없는 값을 서버 존(Seoul)으로 해석하는데, 생성과 reschedule이 같은 방식이라 서로 일관됩니다.
  - `fail_on_pagination_over_collection_fetch` 기본값 false는 설정 키 존재만 jar에서 확인했습니다.

**권고: 수정 요청(REQUEST CHANGES)은 아니고 COMMENT입니다.** HIGH 확신도의 High 이상 결함은 없습니다. 머지 전에 M3(배포 체크리스트)를 고치고, M1(OSIV 명시 결정과 테스트)은 우선 처리를 권합니다.