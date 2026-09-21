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
