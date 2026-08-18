<!-- FR-WF-01 FSM 워크플로우 완제품 plan — 채워가는 살아있는 문서 -->

# FR-WF-01 FSM 워크플로우 완제품 구현

> slug: project-workflow-bc-fr-wf-01-fsm-1-pr
> type: backend
> agent: backend-engineer
> primary_bc: project-workflow
> 생성: 2026-05-21

## Brief

### 사용자 원문

> project-workflow BC FR-WF-01 FSM 워크플로우 완제품 구현 (상태/전환/조건/검증/후처리). §1 인프라 항목 (Gradle workflow 의존성, V001 워크플로우 마이그레이션, Testcontainers postgres) 흡수. 한 PR로 통째 완제품 진행.

### Classify 결과

- type. `backend`
- agent. `backend-engineer`
- slug. `project-workflow-bc-fr-wf-01-fsm-1-pr`
- primary_bc. `project-workflow`

### 작업 범위 결정 (게이트 직전 확정)

- **범위**. FR-WF-01 (FSM 워크플로우) 완제품 1 PR
- **§1 인프라 흡수**. project-workflow.md §1 "기술 검증" 단계를 폐기. 인프라 항목 (Gradle workflow 의존성, Flyway V001 마이그레이션, Testcontainers postgres 셋업) 은 본 PR plan task로 통합
- **품질 기준**. 완제품 (CLAUDE.md §작업 기준 + DEVELOPMENT.md §1 #16 절대 규칙). PoC / 임시 코드 표현 금지
- **다른 섹션 격리**. identity-access BC (PR #8) 작업 중 — 본 작업은 `backend/modules/project-workflow/` 디렉토리로 완전 분리. 권한 호출은 stub interface로 시작, 실제 연동은 PR #8 머지 후 후속 PR

### 관련 출처

- SDD. 02-requirements §2 워크플로우 (FR-WF) / 11장 API §워크플로우
- 도메인. `docs/plan/product/project-workflow.md` §2.1 FR-WF-01
- 의존성 카탈로그. `docs/poc/dependencies.md` (PoC 체크리스트 §1.1 흡수 대상)
- learnings 참조. 2026-05-21 sub-agent가 작업을 PoC로 오인 (단계 표기 ≠ 품질 표기)

## 도메인 정리 (/bts-domain 작성 완료)

### BC + 영향 모듈

- **primary BC**. `project-workflow`
- **신규 모듈**. `backend/modules/project-workflow/` (스캐폴딩 + Gradle subproject)
- **타 BC 격리 — 직접 import 0건**. 다른 BC는 본 BC가 노출하는 port interface 또는 REST API로 호출

### 핵심 엔티티 (FR-WF-01 범위)

| 엔티티 | 책임 |
|---|---|
| Workflow | FSM 컨테이너 (Aggregate Root) |
| WorkflowState | 상태 + category (TODO / IN_PROGRESS / DONE) |
| WorkflowTransition | from → to 매핑 |
| WorkflowValidator | 전환 가능 검증 (전환 전 게이트) |
| WorkflowPostAction | 전환 후 자동 처리 |

> FR-WF-02 영역의 `WorkflowScheme` / `WorkflowAssignment` 는 본 PR 범위 외. 후속 FR-WF-02 PR.

### 사전 결정 (사용자 확정 — 2026-05-21)

| 결정 | 선택 | 사유 |
|---|---|---|
| **D1** 게이트 용어 통일 | **Validator** | SDD 7장 일관성. Spring 생태계 표준. SDD/glossary/domain note 3곳 동기화 필요 |
| **D2** CustomExpression 파서 | **SpEL** | Spring 내장. 외부 의존성 0. sandbox 안전. domain note의 "ANTLR 4" 표기 제거 |
| **D3** PR 범위 | **D1~D7 전체** | 도메인 + DB + Engine + 테스트 + UI + E2E 한 PR. 진짜 완제품 (CLAUDE.md §작업 기준) |

### 신규 용어 (glossary.md 갱신 후보)

- "WorkflowValidator" — 신규 항목. "워크플로우 / 자동화" 섹션에 추가
- "게이트" 항목 정정 — 현재 정의는 그대로 (전환에 걸린 조건의 일반 개념). 단, 구현 클래스는 `Validator` 명명
- "Post-function" / "PostAction" 신규 항목 — "전환 후 자동 처리" 정의

### 신규 엔티티 (DB 마이그레이션 V001)

`workflows`, `workflow_states`, `workflow_transitions`, `workflow_validators`, `workflow_post_actions` 5개 테이블. DATA.md 절대 규칙 + jOOQ codegen 적용.

### 신규 ADR 후보 (본 PR 에서 작성)

| ADR slug | 내용 |
|---|---|
| `2026-05-21-workflow-validator-terminology` | Validator 채택. Guard/Gate 후보 거부 사유 기록 |
| `2026-05-21-workflow-expression-parser-spel` | SpEL 채택. ANTLR 거부 사유 (1인 운영 과잉) |
| `2026-05-21-workflow-yaml-vs-db-storage` | YAML 정의 + DB 직렬화 정책. 캐시 무효화 명시적 처리 |
| `2026-05-21-workflow-bc-cross-bc-port` | 타 BC (issue-tracking, automation) 호출용 port interface 패턴 |
| `2026-05-21-v001-initial-schema-non-concurrent` | 초기 스키마 V001 의 인덱스는 Flyway 트랜잭션 안에서 CREATE INDEX (CONCURRENTLY 불가). DATA.md §4 단서 — 초기 빈 테이블 예외 (락 영향 0). 후속 인덱스 추가는 V002+ 에서 CONCURRENTLY 적용 |

### 기존 결정 충돌 / 갱신 후보

- **`Maxi_wiki/BTS/domain/project-workflow.md` 1곳**. "ANTLR 4로 게이트 표현식 파서" → "SpEL로 게이트 표현식 파서" 정정 (sync-obsidian 또는 별도 PR. Obsidian 단방향 룰 — Phase 0 수동)
- **`Maxi_wiki/BTS/glossary.md`**. "워크플로우 / 자동화" 섹션에 Validator/PostAction 신규 추가
- **SDD `docs/sdd/07-workflow-engine.md` §7.3 / §7.4**. 본 PR 머지 후 표 갱신 (Validator 명명 적용)

### 타 BC 의존성 (port interface stub 처리)

| 의존 BC | 호출 방향 | 본 PR 처리 |
|---|---|---|
| identity-access | PermissionValidator → 권한 시스템 | `PermissionResolver` 인터페이스 stub. 실제 연결은 PR #8 (FR-AU-09) 머지 후 후속 PR |
| issue-tracking | 이슈 상태 변경 → 전환 실행 | `WorkflowTransitionPort` 인터페이스 본 PR 노출. 실제 호출은 issue-tracking BC PR |
| automation | 자동화 룰 → 전환 트리거 | 위와 같은 port 재사용. automation BC PR에서 호출 |

### 모순 / 충돌 해소

- **CustomExpression 파서 충돌** — SDD §7.3 (SpEL) vs domain note (ANTLR 4) → SpEL 채택으로 해소. domain note 갱신 후속 작업
- **타 BC 호출 패턴 미정** — BC 격리 룰은 "직접 import 금지" 이지 "API/port 호출 금지" 아님. Hexagonal Architecture (port-adapter) 패턴으로 명확화. ADR-4번 본문에 정식 기록
- **identity-access 의존성 차단 위험** — PR #8 미머지 상태. stub 인터페이스로 우회. 본 PR 머지 후 PR #8 머지 후 연결 PR 따로 진행 (3 PR 의존성 라인)

### bts-spec 단계로 넘기는 결정 분기

- **표준 4종 워크플로우 YAML** (software-default / bug-tracking / simple / kanban-basic) — 본 PR seed data 포함 여부. spec에서 결정
- **REST API 형태** — `/api/v1/workflows`, `/api/v1/workflows/{id}/transitions/{tid}` 같은 자원 구조. spec에서 확정
- **YAML 스키마 검증 방식** — Konform / Jackson / 자체 — spec에서 결정 (의존성 카탈로그 조회 필요)

### 관련 SDD

- [07. 워크플로우 엔진](../sdd/07-workflow-engine.md) — 본 PR 의 1차 출처
- [05. 데이터 모델 §5.5](../sdd/05-data-model.md) — workflows 테이블 스키마 가이드
- [08. 자동화 엔진](../sdd/08-automation-engine.md) — automation BC 와의 통합 방향

## 스펙 (/bts-spec Phase A 작성 완료)

전체 스펙. [docs/specs/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md](../specs/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md)

### 핵심 시나리오 6건 (S1~S6)

- S1. 표준 워크플로우로 이슈 전환 (happy path) — software-default 흐름
- S2. PermissionValidator 실패 → 403 + 이벤트 발행
- S3. RequiredFieldValidator 실패 → 422 + 어떤 필드 누락 명시
- S4. CustomExpression Validator — SpEL 평가 (sealed root 객체 IssueView/ActorView)
- S5. PostAction — 필드 자동 채움 + watcher 알림 (호출자 BC outbox)
- S6. 동시 전환 (낙관적 락) — 호출자가 version 충돌 처리

### 핵심 변경 — 도메인 단계 결정 적용

- **port 시그니처**. `execute()` → `plan()`. WorkflowEngine 은 계산만 하고 `TransitionPlan` (toState + fieldChanges + emitEvents) 반환. 이슈 영속화는 호출자(issue-tracking) 책임
- **트랜잭션 전파**. MANDATORY — 호출자 트랜잭션 안에서만 동작. BC 격리 + 단일 트랜잭션 일관성 동시 만족
- **SpEL 보안**. SimpleEvaluationContext + **sealed interface root (IssueView/ActorView, getter-only data class 구현체)** + Future timeout 50ms. 사용자 입력 표현식 평가 금지. (Kotlin `@JvmInline value class` 는 단일 필드 제약이라 다중 필드 root 객체 표현 불가 — sealed interface + concrete data class 패턴 채택)

### Phase A office-hours 호출 여부

호출 안 함. 이유 — office-hours skill 의 트리거 ("새 product 아이디어 평가 / worth building 검증") 와 본 작업 (이미 SDD 에 명세된 FR 구현) 불일치. 메인 에이전트가 SDD 7장 + 도메인 정리 + 사전 결정 4건 기반으로 spec 직접 작성.

## Brainstorming Check (/bts-spec Phase B 작성 완료)

✅ 통과 (1회 iteration, 17 gap 흡수).

- **방식**. sub-agent (general-purpose) dispatch — spec 파일 + SDD + 헌법 + learnings 읽어서 14 차원 sanity check
- **발견**. 14 차원 중 13 차원에서 17 gap (차원 10 만 1회차 통과)
- **흡수**. 15건 spec 본문 직접 수정 + 2건 (GAP-2 이슈 영속화 소유권, GAP-17 Konform 의존성) Maxi 결정 후 spec 본문 반영
- **추적표**. spec §10 Brainstorming Check (17 gap × 흡수 위치)
- **무한 루프 안전판**. SKILL 기준 최대 3회 — 1회로 종료

## Plan

> **공통 컨벤션**.
> - 모듈 root. `backend/modules/project-workflow/`
> - 메인 src. `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/`
> - 메인 resources. `backend/modules/project-workflow/src/main/resources/`
> - 테스트 src. `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/`
> - ktlintFormat 부수 변경 (learning #44) — wave 종료 후 controller (메인 agent) 가 일괄 `chore(format):` 커밋. implementer 는 본인 파일만 포맷.
> - `@Transactional` 부착 클래스 (learning #91) — 반드시 `@Service` (또는 `@Component`) 동시 부착. RED 단계에서 ArchUnit 룰 또는 mock context test 로 검증.
> - 완제품 기준 (learning #107) — "PoC" / "임시" / "일단 동작만" 표현 금지. 모든 task production-ready 품질.
> - 검증 명령. backend = `./gradlew :modules:project-workflow:test --tests <TestClass>`, frontend = `pnpm --filter @bts/web test <file>`, e2e = `pnpm --filter @bts/web test:e2e <spec>`.

---

### Task 1. ADR — v001-initial-schema-non-concurrent (초기 스키마 CONCURRENTLY 예외)

> **2026-05-21 결정 반영 — CONCERN-2 (의존성 카탈로그 신규 도입 거부, identity-access 패턴 일치)** 로 원래 의도였던 libs.versions.toml task 는 폐기. 의존성은 Task 8 의 `backend/modules/project-workflow/build.gradle.kts` 안에 직접 명시. 자리 표시 + 학습 보존을 위해 본 task 슬롯을 CONCERN-9 (V001 인덱스 CONCURRENTLY 누락) 해소용 5번째 ADR 작성으로 전환.

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-21-v001-initial-schema-non-concurrent.md`]
- depends-on: []

**RED**. ADR 파일 부재. `/bts-codereview` 의 ADR slug 검증 fail.

**GREEN**.
- 파일. `docs/adr/2026-05-21-v001-initial-schema-non-concurrent.md`
- 본문. Context (DATA.md §4 "인덱스는 CONCURRENTLY" 룰 vs V001 초기 스키마 5 테이블 + 5 인덱스 Flyway 단일 트랜잭션 안에서 처리 필요) / Decision (V001 초기 스키마 한정 CONCURRENTLY 미적용 — 빈 테이블이라 락 영향 0) / Consequences (후속 인덱스 추가는 V002+ 에서 별도 마이그레이션 + CONCURRENTLY 적용).
- 첫 줄 한국어 헤더 (HTML 코멘트).

**REFACTOR**. 없음.

**검증**. `ls docs/adr/2026-05-21-v001-initial-schema-non-concurrent.md` 파일 존재.

---

### Task 2. settings.gradle.kts — project-workflow 모듈 등록

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`]
- depends-on: []

**RED**.
- 파일. `backend/src/test/kotlin/.../ModuleRegistrationTest.kt`
- 테스트 1줄. Gradle 가 `:modules:project-workflow` 프로젝트를 빌드 그래프에 포함한다 (identity-access 모듈 등록 패턴과 일치).
- 실패 예상 메시지. `Project ':modules:project-workflow' not found in root project`

**GREEN**.
- 파일. `backend/settings.gradle.kts`
- `include(":modules:project-workflow")` 한 줄 추가 (identity-access 등록 라인 바로 아래).

**REFACTOR**. 없음.

**검증**. `(cd backend && ./gradlew :modules:project-workflow:tasks)` — 모듈 등록 확인.

---

### Task 3. V001__init_workflow.sql — 5 테이블 + 6 인덱스 마이그레이션

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/V001__init_workflow.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V001MigrationTest.kt`]
- depends-on: []

**RED**.
- 파일. `V001MigrationTest.kt` (Testcontainers postgres + Flyway migrate 후 `information_schema` 조회)
- 테스트 1줄. Flyway migrate 후 `workflows / workflow_states / workflow_transitions / workflow_validators / workflow_post_actions` 5 테이블 + **6 인덱스** 생성 (FK 6개 중 자기 참조 0건 — 모든 FK 컬럼은 인덱스 보유. CONCERN-6 해소).
- 실패 예상 메시지. `Migration file V001__init_workflow.sql not found`

**GREEN**.
- 파일. `V001__init_workflow.sql`
- 5 CREATE TABLE + **6 CREATE INDEX** (인덱스 6개. `idx_workflow_states_workflow`, `idx_workflow_transitions_workflow`, `idx_workflow_transitions_from`, **`idx_workflow_transitions_to`** (신규 — DATA.md §7 FK 인덱스 룰), `idx_workflow_validators_transition`, `idx_workflow_post_actions_transition`).
- `created_at`/`updated_at` timestamptz, FK ON DELETE CASCADE.
- 인덱스는 Flyway 트랜잭션 안에서 `CREATE INDEX` (CONCURRENTLY 미적용 — 초기 빈 테이블 예외, Task 1 ADR 참조).

**REFACTOR**. SQL 코멘트 한 줄 추가 (테이블 책임 설명). 첫 줄 한국어 헤더 주석. `-- 워크플로우 정의 5 테이블 — FR-WF-01 (V001)`.

**검증**. `./gradlew :modules:project-workflow:test --tests V001MigrationTest`.

---

### Task 4. ADR — workflow-validator-terminology

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-21-workflow-validator-terminology.md`]
- depends-on: []

**RED**. 문서 task — ADR 파일 부재. `docs/adr/` 폴더에 해당 slug 가 없으면 `/bts-codereview` 의 ADR 후보 검증이 fail.

**GREEN**.
- 파일. `2026-05-21-workflow-validator-terminology.md`
- 본문. Context (SDD vs domain note 용어 충돌) / Decision (Validator 채택) / Consequences (Guard/Gate 후보 거부 사유 + glossary / domain note 후속 정정).

**REFACTOR**. 없음.

**검증**. `ls docs/adr/2026-05-21-workflow-validator-terminology.md` (파일 존재) + 첫 줄 한국어 헤더 주석 (마크다운 HTML 코멘트).

---

### Task 5. ADR — workflow-expression-parser-spel

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-21-workflow-expression-parser-spel.md`]
- depends-on: []

**RED**. ADR 파일 부재.

**GREEN**.
- Context (ANTLR vs SpEL 검토) / Decision (SpEL + SimpleEvaluationContext + sealed root + 50ms timeout) / Consequences (외부 의존성 0, sandbox 보안 모델).

**REFACTOR**. 없음.

**검증**. `ls docs/adr/2026-05-21-workflow-expression-parser-spel.md`.

---

### Task 6. ADR — workflow-yaml-vs-db-storage

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md`]
- depends-on: []

**RED**. ADR 파일 부재.

**GREEN**.
- Context (YAML truth vs DB truth) / Decision (하이브리드 — 표준 4종은 YAML/code seed, 커스텀은 FR-WF-02) / Consequences (YAML 해시 기반 멱등 시드, advisory lock 으로 동시 갱신 보호).

**REFACTOR**. 없음.

**검증**. `ls docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md`.

---

### Task 7. ADR — workflow-bc-cross-bc-port

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-21-workflow-bc-cross-bc-port.md`]
- depends-on: []

**RED**. ADR 파일 부재.

**GREEN**.
- Context (BC 격리 + 호출 패턴) / Decision (Hexagonal port-adapter + `WorkflowTransitionPort` + `Propagation.MANDATORY` + `plan()` 시그니처) / Consequences (호출자 트랜잭션 안에서만 동작, emitEvents 명세 반환 — 적용은 호출자 책임).

**REFACTOR**. 없음.

**검증**. `ls docs/adr/2026-05-21-workflow-bc-cross-bc-port.md`.

---

### Task 8. build.gradle.kts — root + project-workflow 모듈 빌드 스크립트

**메타**.
- agent: `backend-engineer`
- files: [`backend/build.gradle.kts`, `backend/modules/project-workflow/build.gradle.kts`]
- depends-on: [2]

**RED**.
- 파일. `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/build/ModuleBuildTest.kt`
- 테스트 1줄. 모듈에 `flyway` plugin + `jOOQ codegen` plugin 적용 + `konform` / `spring-context` / `spring-tx` / `jackson` / `kotest-property` / `kotest-runner-junit5` 의존성이 classpath 에 포함된다.
- 실패 예상 메시지. `Plugin 'org.flywaydb.flyway' not applied`

**GREEN**.
- **root `backend/build.gradle.kts`**. `plugins { ... }` 에 `id("org.flywaydb.flyway") version "..." apply false` + `id("nu.studer.jooq") version "..." apply false` 선언 (각 모듈이 apply true 로 활성화). identity-access 모듈도 이 root plugin 을 활용하는 형태 (현재 identity-access 가 이미 flyway 사용 중이면 root 선언이 이미 있을 수 있음 — 점검 후 선언 누락만 추가).
- **모듈 `backend/modules/project-workflow/build.gradle.kts`**.
  - `plugins { id("org.flywaydb.flyway"); id("nu.studer.jooq"); kotlin("plugin.spring") }`
  - `dependencies` 에 **직접 좌표 명시** (identity-access 패턴 일치 — CONCERN-2 결정 2026-05-21).
    - `implementation("io.konform:konform-jvm:0.7.0")` (Maxi 승인 — GAP-17)
    - `implementation("org.springframework:spring-context")` / `spring-tx`
    - `implementation("com.fasterxml.jackson.module:jackson-module-kotlin")`
    - `testImplementation(platform("io.kotest:kotest-bom:5.9.1"))` (BOM 처리 — CONCERN-8)
    - `testImplementation("io.kotest:kotest-property")` / `kotest-runner-junit5`
    - `testImplementation("org.testcontainers:postgresql")` / `testcontainers-junit-jupiter`
  - `jooq { configurations { ... } }` codegen target 설정. 입력 스키마 = Flyway V001 결과 (V001 SQL 을 codegen migration source 로 지정).

**REFACTOR**. 변수명 정리 + 두 파일 모두 첫 줄 한국어 코멘트 (`// project-workflow 모듈 빌드 스크립트 (Flyway + jOOQ + Konform — identity-access 패턴)`).

**검증**. `(cd backend && ./gradlew :modules:project-workflow:dependencies)` + `(cd backend && ./gradlew :modules:project-workflow:compileKotlin)`.

---

### Task 9. 도메인 entity — Workflow / WorkflowState / WorkflowTransition

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/Workflow.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowState.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/WorkflowAggregateTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. `Workflow.of(key, name, states, transitions)` 가 모든 transition 의 from/to state 가 states 집합에 포함됨을 강제하고, 위반 시 `IllegalArgumentException`.
- 실패 예상 메시지. `class Workflow not found`

**GREEN**.
- 3 개 `data class` (모두 `val` immutable). `Workflow` companion factory `of` 가 invariant 검증. `StateCategory` enum (TODO/IN_PROGRESS/DONE).

**REFACTOR**. KDoc 추가 + 첫 줄 한국어 헤더 (`// FSM 워크플로우 Aggregate Root` / `// 워크플로우 상태 + 카테고리` / `// 전환 정의 (from → to)`).

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowAggregateTest`.

---

### Task 10. SPI interface — WorkflowValidator

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/spi/WorkflowValidator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/spi/WorkflowValidatorContractTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. `WorkflowValidator` interface 는 `type: String` (식별자) + `validate(ctx: TransitionContext): ValidatorResult` 두 멤버를 가지며, 구현체는 sealed 가 아닌 SPI (외부 BC 가 추가 구현 못 함).
- 실패 예상 메시지. `interface WorkflowValidator not found`

**GREEN**.
- `interface WorkflowValidator { val type: String; fun validate(ctx: TransitionContext): ValidatorResult }`. `ValidatorResult` sealed (`Pass` / `Fail(field: String?, reason: String)`).

**REFACTOR**. KDoc + 한국어 헤더.

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowValidatorContractTest`.

---

### Task 11. SPI interface — WorkflowPostAction

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/spi/WorkflowPostAction.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/spi/WorkflowPostActionContractTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. `WorkflowPostAction.evaluate(ctx)` 가 `PostActionPlan(fieldChanges, emitEvents)` 반환. **실행은 없음 — 계산만**.
- 실패 예상 메시지. `interface WorkflowPostAction not found`

**GREEN**.
- `interface WorkflowPostAction { val type: String; fun evaluate(ctx: TransitionContext): PostActionPlan }`.

**REFACTOR**. KDoc 명시 "GAP-2 결정 — 적용은 호출자 BC 책임".

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowPostActionContractTest`.

---

### Task 12. DTO — TransitionRequest / TransitionPlan / FieldChange / DomainEvent

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/TransitionRequest.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/TransitionPlan.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/FieldChange.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/DomainEvent.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/dto/DtoJsonRoundtripTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. 4 DTO 를 Jackson 으로 직렬화/역직렬화 시 라운드트립 일치 (FieldChange.value 는 jsonb 호환 `Any?`).
- 실패 예상 메시지. `class TransitionRequest not found`

**GREEN**.
- 4 `data class` (val, Konform 검증 메서드 포함). Konform DSL 로 `TransitionRequest.validate()` (issueKey 비어 있지 않음, version >= 1 명시).

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests DtoJsonRoundtripTest`.

---

### Task 13. SpEL 루트 — IssueView / ActorView (sealed interface + getter-only data class)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/expression/IssueView.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/expression/ActorView.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/expression/SpelRootSurfaceTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. `IssueView` 와 `ActorView` 의 모든 구현체 surface — Kotlin reflection 으로 멤버 스캔 시 **member function 0건** (val property getter 만 노출). SpEL 평가 컨텍스트에서 action 메서드 호출 불가.
- 실패 예상 메시지. `interface IssueView not found`

**GREEN**.
- **결정 — sealed interface + 구체 data class 패턴** (CONCERN-5 정정 2026-05-21. Kotlin `@JvmInline value class` 는 단일 필드 제약 — 다중 필드 (key/priority/fields/...) 표현 불가).
- `sealed interface IssueView { val key: String; val priority: String; val fields: Map<String, Any?> }` + `data class DefaultIssueView(...) : IssueView`. 동일하게 ActorView (`userId`, `roles: Set<String>`). 두 sealed interface 모두 **action 메서드 0개**.

**REFACTOR**. KDoc + 한국어 헤더 (`// SpEL 평가용 sealed interface root — getter only data class 구현체. action 메서드 0개로 SimpleEvaluationContext 안에서 안전).

**검증**. `./gradlew :modules:project-workflow:test --tests SpelRootSurfaceTest`.

---

### Task 14. inbound port — WorkflowTransitionPort

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPort.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPortContractTest.kt`]
- depends-on: [12]

**RED**.
- 테스트 1줄. `WorkflowTransitionPort` interface 는 `plan(req: TransitionRequest): TransitionPlan` 한 메서드만 노출. `@Transactional(propagation = MANDATORY)` 어노테이션이 부착돼 있다 (reflection 으로 검증).
- 실패 예상 메시지. `interface WorkflowTransitionPort not found`

**GREEN**.
- `interface WorkflowTransitionPort { @Transactional(propagation = Propagation.MANDATORY) fun plan(req: TransitionRequest): TransitionPlan }`.

**REFACTOR**. KDoc — 예외 계약 3종 명시 (`WorkflowValidatorFailureException`, `WorkflowNotFoundException`, `WorkflowExpressionTimeoutException`).

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowTransitionPortContractTest`.

---

### Task 15. outbound port — PermissionResolver interface

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/outbound/PermissionResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/outbound/Scope.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/port/outbound/PermissionResolverContractTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. `PermissionResolver.hasPermission(actorId, permission, scope)` 시그니처 + `Scope` sealed (Global / Project(key) / Issue(key)).
- 실패 예상 메시지. `interface PermissionResolver not found`

**GREEN**.
- `interface PermissionResolver { fun hasPermission(actorId: ActorId, permission: String, scope: Scope): Boolean }` + `sealed interface Scope`.

**REFACTOR**. KDoc — identity-access PR #8 후속 연결 명시.

**검증**. `./gradlew :modules:project-workflow:test --tests PermissionResolverContractTest`.

---

### Task 16. 예외 클래스 3종

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/exception/WorkflowExceptions.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/exception/WorkflowExceptionsTest.kt`]
- depends-on: [8]

**RED**.
- 테스트 1줄. 3 예외 클래스 (`WorkflowValidatorFailureException`, `WorkflowNotFoundException`, `WorkflowExpressionTimeoutException`) 모두 `RuntimeException` 상속 + 구조화 메시지 (validator type / workflow key / expression).
- 실패 예상 메시지. `class WorkflowValidatorFailureException not found`

**GREEN**.
- 3 예외 클래스 (각각 적절한 필드 + KDoc).

**REFACTOR**. 한국어 헤더 `// FR-WF-01 도메인 예외 3종`.

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowExceptionsTest`.

---

### Task 17. Validator 구현 — RequiredFieldValidator

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/RequiredFieldValidator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/RequiredFieldValidatorTest.kt`]
- depends-on: [10, 12]

**RED**.
- 테스트 3건. {pass — 필드 채워짐, fail — null, edge — whitespace/empty string}. config = `{"field": "resolution"}` jsonb.
- 실패 예상 메시지. `class RequiredFieldValidator not found`

**GREEN**.
- `class RequiredFieldValidator(private val field: String) : WorkflowValidator { override val type = "RequiredField"; ... }`. whitespace trim 후 empty 면 Fail.

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests RequiredFieldValidatorTest`.

---

### Task 18. Validator 구현 — PermissionValidator

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/PermissionValidator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/PermissionValidatorTest.kt`]
- depends-on: [10, 12, 15]

**RED**.
- 테스트 3건. {pass — PermissionResolver true, fail — false, edge — Scope.Issue 와 Scope.Project 분기 모두 호출 가능}.
- 실패 예상 메시지. `class PermissionValidator not found`

**GREEN**.
- `class PermissionValidator(private val resolver: PermissionResolver, private val permission: String) : WorkflowValidator`. resolver.hasPermission 결과로 Pass/Fail.

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests PermissionValidatorTest`.

---

### Task 19. Validator 구현 — NotStatusCategoryValidator

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/NotStatusCategoryValidator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/NotStatusCategoryValidatorTest.kt`]
- depends-on: [9, 10]

**RED**.
- 테스트 3건. {pass — 현재 state.category 가 금지 카테고리 아님, fail — 금지 카테고리, edge — 카테고리 enum 매핑 누락}.
- 실패 예상 메시지. `class NotStatusCategoryValidator not found`

**GREEN**.
- `class NotStatusCategoryValidator(private val forbidden: StateCategory) : WorkflowValidator`. ctx.fromState.category == forbidden 이면 Fail.

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests NotStatusCategoryValidatorTest`.

---

### Task 20. SpelEvaluator — Future + ExecutorService timeout 50ms

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/expression/SpelEvaluator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/expression/SpelEvaluatorTest.kt`]
- depends-on: [13, 16]

**RED**.
- 테스트 4건. {valid expression 평가, invalid syntax → 예외, timeout 50ms 초과 → `WorkflowExpressionTimeoutException`, SimpleEvaluationContext 적용 — 임의 method 호출 차단 검증}.
- 실패 예상 메시지. `class SpelEvaluator not found`

**GREEN**.
- `class SpelEvaluator(private val executor: ExecutorService) { fun evaluate(expr: String, root: SpelRoot): Boolean }`. `SimpleEvaluationContext.forReadOnlyDataBinding().build()` 사용. `executor.submit(...).get(50, MILLISECONDS)` 패턴.

**REFACTOR**. 한국어 헤더 `// SpEL 평가기 — sandbox + 50ms timeout` + KDoc 보안 모델.

**검증**. `./gradlew :modules:project-workflow:test --tests SpelEvaluatorTest`.

---

### Task 21. Validator 구현 — CustomExpressionValidator

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/CustomExpressionValidator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/CustomExpressionValidatorTest.kt`]
- depends-on: [10, 12, 20]

**RED**.
- 테스트 3건. {pass — SpEL true, fail — SpEL false, edge — timeout 발생 시 Fail 으로 변환 + `WorkflowExpressionTimeoutException` 원인 보존}.
- 실패 예상 메시지. `class CustomExpressionValidator not found`

**GREEN**.
- `class CustomExpressionValidator(private val evaluator: SpelEvaluator, private val expression: String) : WorkflowValidator`. evaluator 호출 결과로 Pass/Fail. timeout 은 Fail(reason = "expression_timeout") 변환.

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests CustomExpressionValidatorTest`.

---

### Task 22. PostAction 구현 — SetFieldPostAction

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/SetFieldPostAction.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/SetFieldPostActionTest.kt`]
- depends-on: [11, 12]

**RED**.
- 테스트 2건. {valid config (`field` + `value`) → `FieldChange(field, oldValue, newValue)` 반환, invalid config — 필드 누락 → exception}.
- 실패 예상 메시지. `class SetFieldPostAction not found`

**GREEN**.
- `class SetFieldPostAction(private val field: String, private val value: Any?) : WorkflowPostAction`. evaluate 시 fieldChanges 1건 + emitEvents 0건 반환. `${now}` 같은 placeholder 는 단순 치환 (확장은 후속).

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests SetFieldPostActionTest`.

---

### Task 23. PostAction 구현 — AddWatcherPostAction

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/AddWatcherPostAction.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/AddWatcherPostActionTest.kt`]
- depends-on: [11, 12]

**RED**.
- 테스트 2건. {valid config (`watcher`) → `DomainEvent("WatcherAdded", payload)` emitEvents, invalid — 없는 watcher 키}.
- 실패 예상 메시지. `class AddWatcherPostAction not found`

**GREEN**.
- `class AddWatcherPostAction(private val watcher: String) : WorkflowPostAction`. evaluate 시 emitEvents 1건 반환 (호출자 outbox INSERT 책임).

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests AddWatcherPostActionTest`.

---

### Task 24. PostAction 구현 — NotifyPostAction

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/NotifyPostAction.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/NotifyPostActionTest.kt`]
- depends-on: [11, 12]

**RED**.
- 테스트 2건. {valid (`channel`, `recipients`) → `DomainEvent("NotificationRequested", payload)`, invalid — channel 부재}.
- 실패 예상 메시지. `class NotifyPostAction not found`

**GREEN**.
- `class NotifyPostAction(private val channel: String, private val recipients: String) : WorkflowPostAction`. emitEvents 1건. notification BC 가 구독.

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests NotifyPostActionTest`.

---

### Task 25. PostAction 구현 — CallWebhookPostAction

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/CallWebhookPostAction.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/CallWebhookPostActionTest.kt`]
- depends-on: [11, 12]

**RED**.
- 테스트 2건. {valid (`url`, `method`) → `DomainEvent("WebhookRequested", payload)`, invalid — url 비어 있음}.
- 실패 예상 메시지. `class CallWebhookPostAction not found`

**GREEN**.
- `class CallWebhookPostAction(private val url: String, private val method: String) : WorkflowPostAction`. **HTTP 실제 호출 없음** — emitEvents 1건. 실제 webhook 디스패치는 후속 BC.

**REFACTOR**. 한국어 헤더 + KDoc 명시 ("config 평가만 — 실행은 후속 BC").

**검증**. `./gradlew :modules:project-workflow:test --tests CallWebhookPostActionTest`.

---

### Task 26. PostAction 구현 — RunAutomationPostAction

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/RunAutomationPostAction.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/RunAutomationPostActionTest.kt`]
- depends-on: [11, 12]

**RED**.
- 테스트 2건. {valid (`automationKey`) → `DomainEvent("AutomationRequested", payload)`, invalid — key 비어 있음}.
- 실패 예상 메시지. `class RunAutomationPostAction not found`

**GREEN**.
- `class RunAutomationPostAction(private val automationKey: String) : WorkflowPostAction`. emitEvents 1건. automation BC 가 구독.

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests RunAutomationPostActionTest`.

---

### Task 27. AlwaysAllowPermissionResolver — stub 구현

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/AlwaysAllowPermissionResolver.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/adapter/AlwaysAllowPermissionResolverTest.kt`]
- depends-on: [15]

**RED**.
- 테스트 2건. {test profile — hasPermission 항상 true 반환, prod profile — `@Profile("!prod")` 검증으로 Bean 부재 → 부팅 차단 시뮬레이션}.
- 실패 예상 메시지. `class AlwaysAllowPermissionResolver not found`

**GREEN**.
- `@Component @Profile("!prod") class AlwaysAllowPermissionResolver : PermissionResolver`. 항상 true.

**REFACTOR**. 한국어 헤더 + KDoc 명시 ("identity-access PR #8 머지 후 IdentityAccessPermissionResolver 가 대체. 운영 profile 부팅 차단 — DEVELOPMENT §1 #16").

**검증**. `./gradlew :modules:project-workflow:test --tests AlwaysAllowPermissionResolverTest`.

---

### Task 28. WorkflowRepository — jOOQ 기반 5 테이블 조회

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/WorkflowRepositoryTest.kt`]
- depends-on: [3, 9, 16]

**RED**.
- 테스트 3건 (Testcontainers postgres). {findByKey 적중, findByKey 부재 → null, findAll}.
- 실패 예상 메시지. `class WorkflowRepository not found`

**GREEN**.
- `@Repository class WorkflowRepository(private val dsl: DSLContext)`. jOOQ generated 테이블 사용. 5 테이블 join → `Workflow` aggregate 복원. jsonb config 는 Jackson 으로 deserialize.

**REFACTOR**. 함수 분리 + 한국어 헤더.

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowRepositoryTest`.

---

### Task 29. WorkflowCache — PostgreSQL advisory lock + 메모리 캐시

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/cache/WorkflowCache.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/cache/WorkflowCacheTest.kt`]
- depends-on: [28]

**RED**.
- 테스트 4건. {miss → DB 적재 + cache hit, invalidate → 다음 조회 miss, advisory lock 동시 갱신 — 200ms 대기 후 503, 멱등 시드 갱신 중 read 대기}.
- 실패 예상 메시지. `class WorkflowCache not found`

**GREEN**.
- `@Service class WorkflowCache(private val repo: WorkflowRepository, private val dsl: DSLContext)`. `ConcurrentHashMap<String, Workflow>` 메모리 + `pg_advisory_xact_lock(hash)` 으로 갱신 보호. lock 대기 timeout 200ms 초과 시 예외.

**REFACTOR**. 한국어 헤더 + KDoc (advisory lock 키 산출 방식, 다중 인스턴스 한계 명시).

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowCacheTest`.

---

### Task 30. YamlSeedService — 표준 4종 YAML 해시 멱등 시드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/main/resources/workflows/software-default.yaml`, `backend/modules/project-workflow/src/main/resources/workflows/bug-tracking.yaml`, `backend/modules/project-workflow/src/main/resources/workflows/simple.yaml`, `backend/modules/project-workflow/src/main/resources/workflows/kanban-basic.yaml`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`]
- depends-on: [28]

**RED**.
- 테스트 4건. {부팅 시 4 YAML 적재 → 5 테이블 row 정합, 동일 YAML 재부팅 → skip (해시 일치), YAML 변경 → 재적재, 잘못된 YAML → 부팅 차단 FailFast}.
- 실패 예상 메시지. `class YamlSeedService not found`

**GREEN**.
- `@Service class YamlSeedService(private val repo: WorkflowRepository, ...)` + `@PostConstruct` 또는 `ApplicationRunner`. SHA-256 해시 비교 → 적재 결정. Jackson YAML + Konform 검증.

**REFACTOR**. 4 YAML 파일 본문 — 스펙 §4.1 예시 따름. 한국어 헤더 (각 YAML 파일 1줄 한국어 코멘트, Service 파일 한국어 헤더).

**검증**. `./gradlew :modules:project-workflow:test --tests YamlSeedServiceTest`.

---

### Task 31. WorkflowEngine — plan() 구현 (port 어댑터)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineUnitTest.kt`]
- depends-on: [14, 17, 18, 19, 21, 22, 23, 24, 25, 26, 29]

**RED**.
- 테스트 4건. {happy path — 4 Validator pass → TransitionPlan, Validator fail → `WorkflowValidatorFailureException`, workflow 부재 → `WorkflowNotFoundException`, Propagation.MANDATORY — 호출자 트랜잭션 없을 때 `IllegalTransactionStateException`}.
- 실패 예상 메시지. `class WorkflowEngine not found`

**GREEN**.
- `@Service class WorkflowEngine(private val cache: WorkflowCache, ...) : WorkflowTransitionPort`. `@Transactional(propagation = MANDATORY)` plan() 구현. Validator 4종 순차 평가 — 첫 Fail 즉시 throw. PostAction 5종 evaluate → fieldChanges + emitEvents 누적.

**REFACTOR**. plan() 30줄 이내로 분리. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowEngineUnitTest`.

---

### Task 32. REST DTO — WorkflowDto / TransitionRequestDto / TransitionResponseDto

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowDto.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/TransitionRequestDto.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/TransitionResponseDto.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/dto/WebDtoSerializationTest.kt`]
- depends-on: [12]

**RED**.
- 테스트 3건. {WorkflowDto Jackson 직렬화 — 스펙 §4.1 응답 형태 일치, TransitionRequestDto Konform 검증 통과/실패, response DTO 라운드트립}.
- 실패 예상 메시지. `class WorkflowDto not found`

**GREEN**.
- 3 `data class` + 도메인 DTO ↔ Web DTO 매퍼 함수 (`toDto()` extension).

**REFACTOR**. 한국어 헤더 + KDoc.

**검증**. `./gradlew :modules:project-workflow:test --tests WebDtoSerializationTest`.

---

### Task 33. WorkflowController — 3 REST API

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowControllerMvcTest.kt`]
- depends-on: [29, 31, 32]

**RED**.
- 테스트 4건 (MockMvc). {GET 목록 200 + 4 워크플로우, GET 단건 200 + 계층 구조, POST transitions 200 + TransitionPlan, POST cache/invalidate 200 + WORKFLOW_MANAGE 권한 검증}.
- 실패 예상 메시지. `class WorkflowController not found`

**GREEN**.
- `@RestController @RequestMapping("/api/v1/workflows") class WorkflowController(...)`. 4 매핑 + `@PreAuthorize`. `@ControllerAdvice` 로 3 도메인 예외 → HTTP 매핑 (403/422/409/503).

**REFACTOR**. 한국어 헤더 + KDoc + `@Transactional` 클래스에 `@Service` 부착 확인 (learning #91 — 본 클래스는 controller 라 `@Transactional` 부착 X. handler 도 마찬가지).

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowControllerMvcTest`.

---

### Task 34. ArchUnit 4룰 — @Transactional + @Service 강제 + BC 격리

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/archunit/ProjectWorkflowArchitectureTest.kt`]
- depends-on: [31, 33]

**RED**.
- 테스트 4건. {(1) `@Transactional` 메서드 보유 클래스는 `@Service` (or `@Component`) 동시 부착, (2~4) project-workflow → identity-access / issue-tracking / automation 직접 import 0건. `*.jooq.tables.*` generated 패키지는 화이트리스트}.
- 실패 예상 메시지. `Class com.bts.workflow.engine.WorkflowEngine has @Transactional but missing @Service`

**GREEN**. ArchUnit rule 정의 (PackageRule + AnnotationRule).

**REFACTOR**. rule DSL 함수 분리 + 한국어 헤더 + KDoc (learning #91 / GAP-7 명시).

**검증**. `./gradlew :modules:project-workflow:test --tests ProjectWorkflowArchitectureTest`.

---

### Task 35. Testcontainers 통합 — S1~S6 + cache invalidate (7건)

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/integration/WorkflowIntegrationTest.kt`]
- depends-on: [30, 31, 33]

**RED**.
- 테스트 7건 (S1 happy / S2 permission fail / S3 required field fail / S4 SpEL pass / S5 PostAction emitEvents / S6 동시 전환 — 호출자 측 낙관락 시뮬, cache invalidate API 후 다음 조회 miss).
- 실패 예상 메시지. `Bean WorkflowEngine not loaded` (의존 task 미완 시).

**GREEN**.
- `@SpringBootTest @Testcontainers` + postgres 1개. seed 적재 후 7 시나리오 실행.

**REFACTOR**. fixture 빌더 추출 + 한국어 헤더.

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowIntegrationTest`.

---

### Task 36. Property-based test — 표준 4종 × 1000건 + closed 검증

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/property/WorkflowPropertyTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/property/WorkflowGraphClosedTest.kt`]
- depends-on: [30, 31]

**RED**.
- 테스트 4건. {1000건 generator (시퀀스 길이 1~20, validator 무작위 조합) — 3 invariant 검증, 4 워크플로우 각각 closed 검증 (시작점 도달 가능 / DONE 1개+ / 고아 0건)}.
- 실패 예상 메시지. `Property test class not found`

**GREEN**.
- Kotest property `forAll(1000) { ... }` + 그래프 도달성 알고리즘 (BFS).

**REFACTOR**. generator DSL 분리 + 한국어 헤더.

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowPropertyTest --tests WorkflowGraphClosedTest`.

---

### Task 37. Frontend — WorkflowDiagram mermaid 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/workflow/WorkflowDiagram.tsx`, `apps/web/src/components/workflow/workflow.types.ts`, `apps/web/src/components/workflow/WorkflowDiagram.test.tsx`, `apps/web/package.json`, `pnpm-lock.yaml`]
- depends-on: [33]

**RED**.
- 테스트 3건 (Vitest + Testing Library). {workflow prop 주면 mermaid 코드 생성, 상태 4 + 전환 4 → 노드 4 + 엣지 4 렌더, 카테고리별 색상 클래스 적용}.
- 실패 예상 메시지. `Cannot find module './WorkflowDiagram'`

**GREEN**.
- `// 워크플로우 FSM 다이어그램 (mermaid stateDiagram-v2)` 헤더 + named export 컴포넌트. **`mermaid` 라이브러리 사용 — Maxi 승인 완료 (2026-05-21, CONCERN-4 결정)**. DEVELOPMENT.md §1 #17 (외부 의존성 추가 시 Maxi 확인) 룰 준수. `apps/web/package.json` 의 dependencies 에 `mermaid` 추가. 본 task files 메타에 `apps/web/package.json` + `apps/web/pnpm-lock.yaml` 도 포함.

**REFACTOR**. 컴포넌트 200줄 이내 + JSDoc + 카테고리 색상 상수 분리.

**검증**. `pnpm --filter @bts/web test WorkflowDiagram`.

---

### Task 38. E2E — Playwright 4 표준 워크플로우 happy path

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/tests/e2e/workflow.spec.ts`]
- depends-on: [33, 37]

**RED**.
- 테스트 4건. {software-default / bug-tracking / simple / kanban-basic 각 1 happy path — UI 에서 워크플로우 다이어그램 표시 + REST API 전환 호출 mock → 200 응답 + 다이어그램 갱신}.
- 실패 예상 메시지. `Playwright spec not found`

**GREEN**.
- `// FR-WF-01 E2E — 표준 4종 워크플로우 happy path` 헤더. Playwright `test.describe` × 4. backend는 Testcontainers + frontend dev server 조합 (또는 MSW mock).

**REFACTOR**. 공통 fixture 추출 (워크플로우 키 / 시드 보장 helper).

**검증**. `pnpm --filter @bts/web test:e2e workflow`.

---

## Plan 메타

- **task 총 수**. 38
- **예상 wave 수**. 7 (의존성 그래프 longest path 기준)
  - Wave 0 (depends-on `[]`). Task 1, 2, 3, 4, 5, 6, 7 — **7 task 병렬** (의존성/파일 충돌 0)
  - Wave 1 (depends-on ⊂ wave 0). Task 8 — 1 task
  - Wave 2 (depends-on ⊂ wave 0~1). Task 9, 10, 11, 12, 13, 15, 16 — **7 task 병렬**
  - Wave 3 (depends-on ⊂ wave 0~2). Task 14, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27, 28, 32 — **13 task 병렬** (가장 큰 wave)
  - Wave 4 (depends-on ⊂ wave 0~3). Task 21, 29 — 2 task
  - Wave 5 (depends-on ⊂ wave 0~4). Task 30, 31 — 2 task
  - Wave 6 (depends-on ⊂ wave 0~5). Task 33, 34, 35, 36, 37 — **5 task 병렬**
  - Wave 7 (depends-on ⊂ wave 0~6). Task 38 — 1 task
- **예상 시간**. 직렬 38 × 3분 = 약 114분. wave 병렬 dispatch 적용 시 약 25~30분 (가장 큰 wave 13 task 동시 dispatch 가 병목).
- **TDD 강제**. yes (모든 task RED → GREEN → REFACTOR 명시).
- **병렬 dispatch**. bts-impl 이 task 메타 (depends-on + files) 로 wave 자동 계산.
- **추가 검증 항목**.
  - ktlint / detekt — wave 종료 후 controller 가 일괄 `chore(format):` 커밋 (learning #44). SQL/YAML 포맷도 동일.
  - ArchUnit 4룰 — Task 34 단일 클래스로 통합 (`@Transactional + @Service` + BC 격리 3건 + jOOQ generated 화이트리스트)
  - Property-based 1000건 — Task 36 (closed 검증 4건 별도)
  - Testcontainers 7건 — Task 35 (S1~S6 + cache invalidate)
  - 신규 backend 의존성 (Konform / Kotest property) — **Task 8 build.gradle.kts 안에 직접 좌표 명시** (CONCERN-2 결정 2026-05-21 — identity-access 패턴 일치. Konform 은 Maxi 승인 GAP-17 반영. Task 1 은 ADR slot 으로 재활용)
  - 신규 frontend 의존성 (mermaid) — **Maxi 승인 완료 (CONCERN-4 결정 2026-05-21)**. Task 37 의 `apps/web/package.json` + `pnpm-lock.yaml` 변경분 포함
  - **jOOQ codegen 단계** (CONCERN-7 해소) — wave 2 종료 후 controller (메인 agent) 가 `(cd backend && ./gradlew :modules:project-workflow:generateJooq)` 1회 실행. wave 3 의 Task 28 (Repository) 가 generated 코드를 import 가능. 별도 task 화 안 함 (controller orchestration 단계).
  - 첫 줄 한국어 헤더 — 모든 신규 소스 파일 (kotlin / typescript / sql / yaml / md) REFACTOR phase 에 명시
  - 완제품 기준 (learning #107) — task 본문에 "PoC / 임시 / 일단 동작만" 표현 0건. 모든 코드 production-ready.

## 신규 세션 인계 메모 (2026-05-21 — 이전 세션 종료 시점)

> **이전 세션 도달점**. 게이트 1 통과 완료 (Maxi 승인). 본 plan + spec 작성/정정 완료. `/bts-impl` 진입 직전에 컨텍스트 부담으로 신규 세션 이장 결정.

### 시작 방법

신규 conversation 에서 다음 1줄 명령으로 재개.

```
/bts-impl
```

또는 명시적으로.

```
/bts-impl FR-WF-01 — 본 plan 파일 (docs/plans/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md) 의 38 task wave 병렬 dispatch 시작. 게이트 1 통과 완료.
```

### 진입 시 첫 확인 사항 (BLOCKER 후보)

1. **`apps/` 디렉토리 부재 — wave 5/6 진입 전 해소 필수**
   - 메인 트리 (`/Users/maxi.moff/Projects/BTS`) 와 본 워크트리 모두 `apps/` 디렉토리 없음
   - CLAUDE.md 명시 "`apps/web/` — Phase 0 진입 후 생성" — 아직 미생성 상태
   - 영향 task. **Task 37** (`apps/web/src/components/workflow/WorkflowDiagram.tsx`), **Task 38** (`apps/web/tests/e2e/workflow.spec.ts`)
   - 권장 처리. wave 5 dispatch 직후 / wave 6 시작 전에 **chore wave** 추가 — Vite + React + TypeScript scaffold + pnpm workspace 셋업
   - 또는 wave 6 전 별도 prep PR 진행 후 본 PR rebase
   - **Maxi 결정 필요 항목** — 신규 세션 진입 시 짧게 확인

2. **다른 섹션 (PR #8 — FR-AU-09) 워크트리 격리 상태**
   - 위치. `.worktrees/fr-au-09-securityfilterchain-local-provider-pr-6-7/`
   - 본 작업은 `backend/modules/project-workflow/` 전용이라 디렉토리 격리됨
   - root 파일 변경 시 (Task 8 `backend/build.gradle.kts`, Task 2 `backend/settings.gradle.kts`) PR #8 머지 시점에 conflict 가능 — 머지 순서 Maxi 확인

3. **main 1커밋 ahead of origin 상태**
   - 92b72ec ("PoC" 표현 제거 + 완제품 기준 지침) 푸시 안 됨
   - 본 PR base 가 origin/main 이라 그 1커밋 + plan 변경분이 PR diff 에 함께 노출됨
   - 머지 시 자동 동기화 — 별도 대응 불필요

### 13 결정 사항 누적 (잊지 말 것)

(spec §9 또는 plan §도메인 정리 §사전 결정 참조)

1. Validator 용어 채택 (Guard/Gate 거부)
2. CustomExpression 파서 = SpEL (ANTLR 거부)
3. PR 범위 = D1~D7 전체
4. 이슈 영속화 소유 = issue-tracking BC (project-workflow 는 plan() 계산만)
5. Konform 의존성 본 PR 도입 (Maxi 승인)
6. mermaid 의존성 본 PR 도입 (Maxi 승인)
7. 직접 좌표 패턴 (libs.versions.toml 신규 도입 거부)
8. port 트랜잭션 전파 = MANDATORY
9. PostgreSQL advisory lock (workflow_id 해시 기반)
10. 단일 인스턴스 가정 (다중은 후속 PR)
11. SpEL root = sealed interface + getter-only data class (value class 거부)
12. V001 초기 스키마 CONCURRENTLY 미적용 (Task 1 ADR 로 기록)
13. workflow_transitions.to_state_id FK 인덱스 추가 (V001 6 인덱스)

### 환경 점검 결과 (이전 세션 마지막 시점)

- backend Gradle 멀티모듈 ✅
- backend/build.gradle.kts plugins ✅ (Flyway/jOOQ 부재 — Task 8 추가)
- backend/modules/identity-access/ ✅ (main 머지분)
- apps/ ❌ (위 BLOCKER-1 참조)
- Docker ✅ Java 21 ✅ pnpm 11 ✅

### Draft PR #10 상태

- URL. https://github.com/maxihan1/BTS/pull/10
- 현재 push 된 커밋. 빈 커밋 (`f04328f chore: ...wip`) 만. plan + spec 변경분은 워크트리 local 에 있고 push 안 됨
- 신규 세션이 wave 0 첫 커밋부터 push 시작 예정

## 리뷰 결과 (/bts-review-plan 작성 완료)

### plan-eng-review (sub-agent dispatch — superpowers:code-reviewer, 2026-05-21)

- **PASS 7건** — 차원 1 TDD 사이클, 5 BC 격리 (ArchUnit 4룰), 6 13 결정 반영, 7 테스트 전략, 8 학습 회귀 (#44 / #91 / #76 / #107), 10 ADR 4건, 12 단독 머지 가능성
- **CONCERN 10건 → 모두 흡수**
  - C1 module path 불일치 (`:backend:project-workflow` → `:modules:project-workflow`) — BLOCKER 흡수, 일괄 치환
  - C2 의존성 카탈로그 신규 도입 vs 직접 좌표 — **Maxi 결정 — 직접 좌표 (identity-access 패턴 일치)**. Task 1 폐기 → ADR slot 재활용 (V001 CONCURRENTLY 예외 ADR)
  - C3 root build.gradle.kts plugin 미선언 — BLOCKER 흡수, Task 8 files 메타에 root 추가 + GREEN 본문 보강
  - C4 mermaid 의존성 미확정 — **Maxi 결정 — 본 PR 도입 승인**. Task 37 본문 명시 + files 메타에 package.json/pnpm-lock 추가
  - C5 sealed value class Kotlin 표현 오류 — Task 13 본문 정정 (sealed interface + getter-only data class 구현체)
  - C6 workflow_transitions.to_state_id FK 인덱스 누락 — Task 3 V001 SQL 6 인덱스로 갱신 (spec §5 도 동시 갱신 — `idx_workflow_transitions_to`)
  - C7 jOOQ codegen wave 누락 — Plan 메타에 "wave 2 종료 후 controller 가 generateJooq 실행" 명시
  - C8 kotest-property BOM 누락 — Task 8 dependencies 에 kotest-bom 처리 명시
  - C9 V001 인덱스 CONCURRENTLY 부재 — 신규 ADR 5건 (Task 1 슬롯 재활용 — `v001-initial-schema-non-concurrent`)
  - C10 fast-track 38 task 분할 가능성 — 정보 제공만 (사용자 "한 PR 통째" 명시 — 분할 안 함)
- **BLOCKER 2건 → 모두 해소**
  - B1 = C1 (module path) — 일괄 치환 완료
  - B2 = C3 (root plugin) — Task 8 보강 완료

### 종합 verdict

- 1차 verdict (sub-agent). **NO-GO (수정 후 GO)**
- 정정 후 verdict (메인 controller). **GO** — BLOCKER 2 + CONCERN 10 모두 plan 본문 흡수. Maxi 결정 2건 (C2 직접 좌표, C4 mermaid 승인) 본문 반영 완료
- 게이트 1 대기. 산출물 4종 (도메인 정리 / 스펙 / plan / 리뷰 결과) Maxi 확인 요청

### 추가 디자인/devex/ceo 리뷰 권장 여부

- **design-review**. 권장 안 함 — D6 UI 는 단일 mermaid 컴포넌트 (200줄 미만 예상), designer agent 가 task 33 디자인 시안 + Task 37 구현 협업으로 충분
- **devex-review**. 권장 안 함 — REST API 3개 + port interface 모두 내부 사용 (외부 SDK 노출 없음). 게이트 2 (/bts-codereview) 단계의 일반 코드 리뷰로 충분
- **ceo-review**. 권장 안 함 — SDD 에 이미 명세된 FR 구현. 전략적 ambition 재검토 불필요
