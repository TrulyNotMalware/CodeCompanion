# CodeCompanion Wiki

이 디렉터리는 CodeCompanion의 **설계 철학·규칙·결정**을 기록하는 프로젝트 전용 위키다.
디렉터리별 작업 지침은 각 `AGENTS.md`(AI 에이전트용, 영어)가 맡고, 이 위키는 "왜 이렇게 설계했는가"와
"무엇을 지켜야 하는가"를 사람이 읽는 언어로 남긴다. 둘은 서로 링크한다.

## 운영 규칙

- **언어**: 본문은 한국어. 식별자·설정 키·파일 경로·기술 용어는 코드에 쓰인 원어 그대로.
- **파일명**: 영문 kebab-case. 링크는 GitHub에서 렌더링되도록 상대 경로 마크다운 링크를 쓴다
  (`[[wikilink]]` 금지).
- **근거 우선**: 모든 기술적 주장은 코드나 문서에 근거를 둔다. 각 페이지 끝의 `## 근거`에 저장소 상대
  경로를 적고, 확인하지 못한 내용은 본문에 `(미확인)`으로 표시한다.
- **델타만 기록**: 공식 문서나 일반 지식으로 대체되는 내용은 쓰지 않는다. 이 프로젝트의 결정·제약·함정·
  이유만 남긴다.
- **페이지 머리**: 제목 아래 `_type: … · updated: YYYY-MM-DD_` 한 줄과 한 문장 요약(인용 블록).
  `type`은 `architecture` / `pattern` / `guide` / `decision` / `history` 중 하나.
- **갱신**: 내용을 바꾸면 `updated`를 고치고 [`log.md`](log.md)에 한 줄을 **추가**한다(append-only,
  과거 항목 수정 금지). 결정이 뒤집히면 지우지 말고 이력을 남긴다.
- **민감 정보 금지**: 토큰·시크릿·내부 주소는 README에 이미 있는 수준을 넘지 않는다.

## 페이지

| 페이지 | 유형 | 한 줄 |
|--------|------|-------|
| [architecture-overview.md](architecture-overview.md) | architecture | 세 모듈의 책임과 의존 방향, 요청이 흐르는 길 |
| [ddd-layering.md](ddd-layering.md) | decision | 도메인 순수성·전송 중립성을 어디까지, 왜, 어떻게 강제하는가 |
| [command-pipeline.md](command-pipeline.md) | architecture | Slack 페이로드 → 중립 인바운드 → Command/Intent → OutboundMessage |
| [events-and-outbox.md](events-and-outbox.md) | architecture | 트랜잭셔널 아웃박스, 릴레이 모드, 멱등성과 CAS |
| [error-handling-and-validation.md](error-handling-and-validation.md) | pattern | ErrorCode·exceptionDetails DSL·validate DSL과 계층별 예외 소유권 |
| [coding-style.md](coding-style.md) | guide | Kotlin 작성 규칙, 커밋 메시지, 주석·널·파일 구성 원칙 |
| [testing-guide.md](testing-guide.md) | guide | Kotest/MockK 스타일, testFixtures, 가드 테스트, 모듈별 실행 |
| [dev-environment.md](dev-environment.md) | guide | 프로파일, Gradle 프리셋, 로컬 실행, 마이그레이션·시크릿 관례, CI/CD |
| [decisions.md](decisions.md) | decision | 근거가 있는 기술 결정 목록(ADR 요약) |
| [history.md](history.md) | history | 2024-06부터의 마일스톤과 설계가 바뀐 지점 |

## 관련

- [`../../AGENTS.md`](../../AGENTS.md) — 저장소 최상위 에이전트 지침
- [`../../README.md`](../../README.md) — 기능·기술 스택·시작하기
