<!-- ADR: IssueType 의 cross-BC 사전 도입 결정 — project-workflow PR 이 issue-tracking 모듈에 IssueType Aggregate 를 선도입한 BC 격리 예외 정당화 -->

# ADR — issue-type-cross-bc-introduction

**일자**. 2026-05-29
**상태**. Accepted
**관련 PR**. `fr-is-02-issue-types-backend` (정식화) ← `project-workflow-bc-fr-wf-02-scheme-1-pr` (PR #31, 최초 도입)
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

BTS 는 **한 PR = 한 바운디드 컨텍스트(BC)** 를 핵심 패턴으로 강제한다 (`CLAUDE.md §핵심 패턴`). 다른 BC 의 기능이 필요하면 직접 import 하지 않고 이벤트(pgmq) 발행 또는 SPI(Service Provider Interface) 포트 경유로만 호출한다.

그런데 FR-WF-02(워크플로우 스킴, project-workflow BC)는 "이슈 타입별 워크플로우 매핑"(`IssueTypeScheme`)을 구성하기 위해 **`IssueType` 식별자**가 필요했다. `IssueType` 의 본 정의는 issue-tracking BC 의 영역이다 (SDD §05.2). FR-WF-02 작업 시점에 issue-tracking BC 의 `IssueType` 은 아직 도입되지 않은 상태였다.

이때 두 가지 선택지가 있었다.

### 고려한 옵션

**옵션 A — project-workflow BC 안에 IssueType stub/복제.**
- project-workflow 모듈에 자체 `IssueType` 또는 enum 을 두고 나중에 issue-tracking 의 정본과 동기화.
- 장점. BC 격리 규칙을 글자 그대로 지킴.
- 단점. 같은 개념(`IssueType`)이 두 BC 에 중복 정의됨. issue-tracking 이 정본을 도입할 때 두 정의의 동기화/통합 부담. DDD 관점에서 한 Aggregate 의 정의가 두 곳에 사는 것은 안티패턴.

**옵션 B — issue-tracking 모듈에 IssueType 을 사전 도입(cross-BC).**
- project-workflow PR(FR-WF-02) 안에서 issue-tracking 모듈에 `IssueType` Aggregate + `issue_types` 테이블(V003) + 5 표준 seed + read-only 조회 API 만 선도입. 커스텀 IssueType CRUD 와 `Issue.type` 연결은 FR-IS-02 후속 PR 로 분리.
- 장점. `IssueType` 정본이 처음부터 올바른 BC(issue-tracking)에 위치. 중복/동기화 부담 없음. FR-IS-02 가 그 위에 CRUD 만 얹으면 됨.
- 단점. 한 PR(FR-WF-02)이 두 BC 의 파일을 건드림 — BC 격리 규칙의 예외.

## 결정

**옵션 B 채택.** project-workflow PR(#31) 안에서 issue-tracking 모듈에 `IssueType` 을 사전 도입했다. 단 범위를 엄격히 제한했다.

- 도입분. `IssueType` Aggregate(factory + 표준 5종) + `issue_types` 테이블(V003) + 5 표준 seed + read-only GET API(`IssueTypeController`).
- 제외분(FR-IS-02 후속). 커스텀 IssueType CRUD(POST/PATCH/DELETE), `Issue.type` 연결(`issues.type_id` FK), Epic-Subtask 계층(`hierarchy_level`), IssueTypeScheme.

### 예외 정당화 — 왜 BC 격리를 깨도 되는가

1. **Aggregate 정본의 올바른 거처가 우선.** `IssueType` 은 명백히 issue-tracking BC 의 Aggregate 다(SDD §05.2). 정본을 처음부터 올바른 BC 에 두는 것이, 임시로 잘못된 BC 에 두었다가 옮기는 것보다 회귀 위험이 낮다.
2. **`Issue` Aggregate 미터치.** 도입분은 `IssueType` 단독 + read-only 조회만. issue-tracking 의 핵심 Aggregate 인 `Issue` 는 건드리지 않아, 동시 진행 중이던 FR-IS-01(이슈 CRUD)과 파일 충돌 0.
3. **범위 최소화 + 후속 명시.** CRUD·커스텀·`Issue` 연결을 모두 후속 FR-IS-02 로 미뤄, FR-WF-02 PR 의 cross-BC 침범을 "조회 가능한 5 표준 타입 도입" 수준으로 한정.

## 결과 / 후속

- **본 PR(FR-IS-02)에서 이 ADR 을 정식화.** PR #31 도입 시점에 이 ADR 파일이 누락되어, `IssueType.kt` KDoc / 마이그레이션 통합 테스트 / `bc-migration-prefix-policy` ADR / 여러 plan·spec 이 실재하지 않는 ADR 을 참조하는 phantom 상태였다(learnings 2026-05-20 "phantom 엔티티/ADR" 재발). 본 ADR 작성으로 공백 보강.
- FR-IS-02 는 이 도입분 위에 커스텀 CRUD + `Issue.type` 연결 + `hierarchy_level` 을 얹는다.
- 향후 같은 패턴(한 BC PR 이 다른 BC 의 Aggregate 를 사전 도입)이 필요하면, 본 ADR 의 3가지 예외 정당화 기준을 충족하는지 점검할 것.

## 관련 ADR / 문서

- [[2026-05-21-workflow-bc-cross-bc-port]] — project-workflow 의 cross-BC SPI 포트 (이벤트/포트 경유 호출의 정석 패턴)
- [[2026-05-26-bc-migration-prefix-policy]] — BC 별 Flyway 마이그레이션 네임스페이스 격리 (V003 이 issue-tracking 네임스페이스에 위치)
- SDD §05.2 (IssueType 데이터 모델), `CLAUDE.md §핵심 패턴` (BC 격리)
