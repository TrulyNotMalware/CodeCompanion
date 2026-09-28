# Wiki Log

append-only. 형식: `## [YYYY-MM-DD] <create|update|lint> | 요약`

## [2026-08-28] create | 위키 신설 — 운영 규칙과 초기 페이지 10종
## [2026-08-28] update | architecture-overview·decisions: 릴레이 모드 표기 정정(폴링은 `real`만), ddd-layering: `OutboundMessagePort` 소유권 정정
## [2026-08-30] update | Codex 교차검증 반영 — ddd-layering·decisions: 아웃박스 wire 포맷은 Phase 8b에서 중립화됨(누수 목록에서 제외), decisions #27: security_check의 gitleaks는 문서 변경에도 실행, #30: dependabot-core 근거 인용, dev-environment: 사이드카 빈은 프로파일 무관 생성
## [2026-09-21] update | dev-environment·decisions #22: 무효 `spring.flyway.enabled: false` 키를 real YAML에서 제거(local은 사용자가 직접 제거), 스테일 불일치 메모 삭제
## [2026-09-21] update | `real` 프로파일을 `slack-live`로 개명 — yaml 파일명과 `on-profile`, AGENTS.md·CDC README·위키(dev-environment, architecture-overview, events-and-outbox, history, decisions #9·#22) 참조 일괄 갱신
## [2026-09-21] update | dev-environment: 죽은 키 `slack.app.mode.stand-alone` 메모 삭제 — 키 자체가 `application-local.yaml`에서 제거됨(백킹 필드는 Phase 8 `6b1083e`에서 이미 삭제)
## [2026-09-21] update | Phase 11 구현 반영 — command-pipeline: SubmissionRouter/파스 모델/IgnoredSubmissionContext 라우팅 추가, decisions #32 신설
## [2026-09-21] update | command-pipeline: 캐스트 가드 baseline 문구 갱신(A2로 슬래시 캐스트 제거, baseline 비움)
## [2026-09-28] update | 운영 리뷰 1차 반영 — dev-environment(CI 트리거·`apply.sh ci`·배포 헬스·롤백·시그니처 시크릿·actuator 노출·Kafka 컨슈머), testing-guide(CI 줄), decisions #27 정정(2026-09-22 변경분)·#33 신설
## [2026-09-28] update | 운영 리뷰 2차 반영 — 공개 호스트 "게이트웨이가 `/api/slack/*`만 전달" 추정 삭제(실측: 모든 경로 401, bearer 계층은 저장소 밖), 배포 게이트를 readiness + service proxy/`kubectl exec` 폴백으로, `max-poll-records: 5`와 실제 예산 계산, 마이그레이션 최고 번호 V21(다음 V22), JDWP·JMX 루프백/단일 포트, decisions #33 재작성
## [2026-09-28] update | 운영 리뷰 3차 반영 — dev-environment: Hikari 풀 20과 산정식·`max_connections` 예산, Slack 호출 타임아웃 기반 poll 예산, 샘플 라우트 `/api/slack`·`/api/slash`, 템플릿 해시 기반 롤백; testing-guide: 해소된 빈 스펙 항목과 `:application` H2 스펙 반영; decisions #33 롤백 문구, #34(레플리카 간 dedup 미결) 신설
## [2026-09-28] update | 아웃박스·디스패치 2차 반영 — events-and-outbox: `attempt_count` 소유권 토큰과 `send_count` 발송 예산(V20·V22), rate-limit 보류(`Retry-After` 보존)·transient 소진 시 IN_PROGRESS 유지, `-dlt` 토픽, dedup 상태 머신 서술 정정; dev-environment: 마이그레이션 최고 번호 V22(다음 V23), 레코드당 예산 약 53초
## [2026-09-28] update | decisions #34: 레플리카 간 dedup 검토 메모 추가 — Events API만 재시도 대상, 요청 경로는 동기·AI 턴은 비동기, outbox와 inbox의 차이, 권고는 이벤트 ID 유니크 테이블을 핸들러 트랜잭션에 포함(미결 유지)
