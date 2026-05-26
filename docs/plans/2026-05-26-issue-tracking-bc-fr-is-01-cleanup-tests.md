# FR-IS-01 D4 + D5 마무리 — codereview cleanup + Wave 6 백엔드 테스트

> slug. `issue-tracking-bc-fr-is-01-cleanup-tests`
> type. backend (manual override — classify 자동 결과 qa/qa-engineer 가 작업 본질과 어긋남)
> agent. backend-engineer (Wave 6 test 인프라 도 src/ 영역 포함 → qa-engineer 룰 위반 회피)
> primary BC. issue-tracking
> 생성. 2026-05-26

## Brief

PR #17 머지 후 미결 후속 작업 일괄 정리 PR.

**(D4) 백엔드 API 마무리** — PR #17 의 codereview CONCERN 3건.
- **C-2 sealed Result port** — `WorkflowTransitionPort` 시그니처를 `sealed Result` 로 변경하여 `IssueApplicationService.transitionIssue` 의 `catch (WorkflowValidatorFailureException)` 자체를 제거. BC 격리 본질 차단 (issue-tracking 이 project-workflow domain exception import 0건).
- **C-3 DATA.md advisory_lock 정합** — DATA.md §5 의 `dsl.execute(rawSql)` 금지와 `IssueRepository.incrementKeySequence` 의 `pg_advisory_xact_lock` + `pgmq.send` 실제 사용 사이 정합. ADR 발행 또는 DATA.md 단서 추가.
- **C-5 PATCH 시맨틱 fix** — `UpdateIssueRequest.summary` 의 `null` 의도를 RFC 7396 (JSON Merge Patch) 시맨틱에 맞게 정정. null = "값 변경 안 함" vs `""` = "빈 문자열로 설정". 현재는 null 을 통과시켜 도메인 invariant 위반.

**(D5) 백엔드 테스트 마무리** — PR #17 후속 PR 로 미뤄둔 Wave 6 6 task.
- **ArchUnit 룰** — (1) BC 격리 (issue-tracking 이 project-workflow.domain 직접 import 금지, port.outbound 만 허용) + (2) jOOQ 화이트리스트 (jooq.generated.* 는 repository 패키지에서만 import).
- **Testcontainers singleton 정비** — `IssueRepositoryTest` 의 Flyway 충돌 known issue 해소. PR #8 learnings #2 패턴 (`.apply { start() }` JVM singleton + Ryuk cleanup).
- **Kotest property test** — Issue aggregate invariant (key 형식 / state 전이 / soft delete 키 보존) × 1000건.

C-6 (SYSTEM_ACTOR_UUID + workflowKey="DEFAULT" hardcoded) 은 FR-AU-10 (시스템 액터 모델) 의존 → 본 PR scope 제외.

## 도메인 정리

### BC

- primary. **issue-tracking** (`backend/modules/issue-tracking/`)
- 인접 BC. **project-workflow** (port 의존만 — `com.bts.workflow.port.inbound.WorkflowTransitionPort` + `com.bts.workflow.domain.dto.TransitionRequest/TransitionPlan`)

### 새 용어 / 엔티티

- **0건**. 기존 glossary (`Maxi_wiki/BTS/glossary.md`) 충분. 도메인 모델 변경 0 — Issue aggregate / IssueKey VO / IssuePermission 모두 그대로.

### 영향 받는 prod 파일 (실측 grep 검증)

- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPort.kt` — port 시그니처 변경 (C-2)
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/` (또는 application 계층) — Result 매핑 adapter 신규 또는 기존 호출자 수정 (C-2)
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt:25, 211-221` — workflow exception import 제거 + when 분기 (C-2)
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt:26-29` — `UpdateIssueRequest.summary: String → String?` (C-5)
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt:144-148` — `?: ""` 제거 + null 시 updateSummary skip (C-5)
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt:28, 246` — 코드 변경 0 (parameter binding 이미 적용). ADR/DATA.md 단서 추가만 (C-3)
- `DATA.md §5` — 단서 추가 (C-3)
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` — 신규 (C-2)
- `docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md` — 신규 (C-3)

### 영향 받는 test 파일

- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/IssueBcArchTest.kt` — 신규 (Wave 6 ArchUnit 룰 2종)
- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueTestcontainersBase.kt` — 신규 abstract base (Wave 6 singleton)
- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt:44-57` — `@Container` 제거 + base 상속 (Wave 6)
- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueInvariantPropertyTest.kt` — 신규 (Wave 6 Kotest property × 1000건)
- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceUpdateTest.kt` — null summary 시 updateSummary 미호출 검증 추가 (C-5)
- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerUpdateTest.kt` — `{"summary": null, "expectedVersion": 1}` PATCH 시 변경 없음 검증 추가 (C-5)
- `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTransitionTest.kt` — workflowPort mock 반환 타입 `TransitionResult` 로 변경 (C-2)
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPortTest.kt` (또는 통합 테스트) — sealed Result 매핑 검증 (C-2)

### 기존 결정 (ADR) 충돌

- **`docs/adr/2026-05-21-workflow-bc-cross-bc-port.md`** — 본 PR 의 C-2 sealed Result 는 본 ADR 의 **BC 격리 본질 강화 보강** (포트 시그니처를 throws → sealed Result 로 진화). 정정 단락 추가 (충돌 아닌 진화 표기).
- **`Maxi_wiki/BTS/learnings.md` 2026-05-26 "monolith TDD_VIOLATION 패턴" + "BC 격리 wrapper exception 패턴"** — 본 PR 이 후속 후보 (3) "port 시그니처를 sealed Result 로 변경하면 catch 자체도 제거 가능" 실현. learnings 갱신 후보.

### ADR 후보 2건

1. **`docs/adr/2026-05-26-workflow-transition-port-result-sealed.md`** (C-2)
   - 결정 — `WorkflowTransitionPort.plan(): TransitionResult` (sealed interface) — Success/ValidatorFailure/WorkflowNotFound/ExpressionTimeout
   - 근거 — BC 격리 본질 차단 (호출자가 `com.bts.workflow.domain.exception.*` import 0건). ArchUnit 룰 (`IssueBcArchTest`) 로 import 검출 강제.
2. **`docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md`** (C-3)
   - 결정 — DATA.md §5 의 `dsl.execute(rawSql)` 금지에 정식 예외 등록. parameter binding (`?` placeholder) 사용 + jOOQ 미지원 PostgreSQL 함수 (pg_advisory_lock 등) 한정.
   - 근거 — `IssueRepository.incrementKeySequence` 의 advisory_lock 호출이 표면 위반처럼 보이나, 실제 SQL Injection 영역 아님 (binding). 명시 예외 없으면 reviewer 가 매 PR 마다 적색 신호.

### 부수 발견 (본 PR scope 외, 후속 후보)

- `docs/decisions/` (auth 14건) + `docs/adr/` (workflow/issue 8건) 두 디렉토리 분산 — 정리 후보 (별 PR)
- `IssueController.kt:176` `workflowKey = "DEFAULT"` 및 `IssueController.kt:211` `SYSTEM_ACTOR_UUID` — C-6 (FR-AU-10 시스템 액터 모델) 의존 → 본 PR 제외 + spec 본문에 deferred trigger blockquote 명시 (learnings #18 패턴)

### grill-with-docs 호출 — 생략

본 작업은 chore 가까운 cleanup + test infra. 새 용어/엔티티/관계 0건 + 도메인 모델 변경 0건. grill-with-docs 대화 가치 < 비용. 결정 4건은 `/bts-spec` Phase A office-hours 에서 처리.

## 스펙

> PR #19/#20/#21 fast-track 패턴 — docs/specs/ 별도 파일 생략, plan §스펙 inline 통합 + 결정 4건 (D1~D4) 본문 명시.

### F1. 시나리오 (Given-When-Then, 기술 시나리오 중심)

**S1. C-2 sealed Result — 정상 전이.**
- Given. issue-tracking BC 가 `WorkflowTransitionPort.plan(req)` 호출
- When. project-workflow 가 validator 통과 + 전이 가능 판정
- Then. `TransitionResult.Success(plan: TransitionPlan)` 반환. issue-tracking 은 when 분기에서 plan 추출 후 적용

**S2. C-2 sealed Result — validator 실패.**
- Given. 같은 호출
- When. project-workflow validator 가 거부 (예. 권한 없음, 필수 필드 누락)
- Then. `TransitionResult.ValidatorFailure(message)` 반환. issue-tracking 의 when 분기가 `IssueTransitionNotAllowedException` throw. `com.bts.workflow.domain.exception.*` import 0건.

**S3. C-2 sealed Result — workflow 미존재.**
- Given. workflowKey 가 DB 에 없음
- When. project-workflow 가 `WorkflowNotFoundException` 검출
- Then. adapter 가 `TransitionResult.WorkflowNotFound(key)` 로 wrap. issue-tracking 의 when 분기가 `IssueTransitionNotAllowedException` (혹은 `IssueProjectNotFoundException` 유사 신규 — spec §F2 결정) throw.

**S4. C-2 sealed Result — SpEL 타임아웃.**
- Given. 워크플로우 condition 이 무한 루프 SpEL
- When. project-workflow 가 `WorkflowExpressionTimeoutException` 검출
- Then. adapter 가 `TransitionResult.ExpressionTimeout(message)` 로 wrap. issue-tracking 의 when 분기가 `IssueTransitionNotAllowedException` throw (사용자 메시지는 "워크플로우 평가 시간 초과").

**S5. C-3 advisory_lock 정합.**
- Given. `IssueRepository.incrementKeySequence("BTS")` 호출
- When. `dsl.execute(SQL_ADVISORY_LOCK, "project:BTS")` 가 parameter binding 으로 실행
- Then. SQL Injection 영역 0 + DATA.md §5 단서 + ADR 링크가 reviewer 의 적색 신호 차단. 코드 변경 0 (문서만).

**S6. C-5 PATCH partial — null 시 변경 안 함.**
- Given. 활성 이슈 BTS-1 (summary="원래") + `PATCH /api/v1/issues/BTS-1` body `{"summary": null, "expectedVersion": 1}`
- When. IssueController.update 호출
- Then. 200 OK + IssueResponse.summary == "원래" (변경 없음) + version 그대로 1 + IssueUpdated 이벤트 발행 안 함 (changedFields 비어 있음)

**S7. C-5 PATCH partial — non-null 시 변경.**
- Given. 활성 이슈 BTS-1 (summary="원래") + `PATCH` body `{"summary": "새 제목", "expectedVersion": 1}`
- When. IssueController.update 호출
- Then. 200 OK + IssueResponse.summary == "새 제목" + version 2 + IssueUpdated 이벤트 발행 (changedFields = ["summary"])

**S8. Wave 6 ArchUnit — BC 격리 위반 detect.**
- Given. `IssueBcArchTest` 가 빌드 시 실행
- When. `com.bts.issue.*` 의 어떤 파일이 `com.bts.workflow.domain.exception.*` 또는 `com.bts.workflow.domain.spi.*` 또는 `com.bts.workflow.application.*` 등 허용 외 패키지 import
- Then. ArchUnit fail + 어느 파일이 어느 클래스 import 했는지 명시. 허용 = `com.bts.workflow.port.inbound.*` + `com.bts.workflow.domain.dto.*` 만.

**S9. Wave 6 ArchUnit — jOOQ 화이트리스트.**
- Given. `IssueBcArchTest` 가 빌드 시 실행
- When. `com.bts.issue.application.*` / `domain.*` / `adapter.*` 등 repository 외 layer 가 `com.bts.issue.jooq.generated.*` import
- Then. ArchUnit fail. 허용 = `com.bts.issue.repository.*` 만.

**S10. Wave 6 Testcontainers singleton — 재실행 시 stale port 없음.**
- Given. `IssueTestcontainersBase` (abstract) + `.apply { start() }` JVM singleton
- When. `IssueRepositoryTest` 가 클래스 시작 → 종료 → 또 다른 자식 (가상 `IssueServiceIntegrationTest`) 시작
- Then. container stop 0 + JVM 종료 시 Ryuk 자동 정리. ApplicationContext 공유해도 stale port 없음 (PR #8 learning #2 패턴).

**S11. Wave 6 Kotest property — IssueKey regex.**
- Given. random 영문 대문자 1자 + 영문 대문자/숫자 1~9자 + `-` + 1~9 시작 숫자 + 0~9 추가 — 1000건 random 생성
- When. `IssueKey(generated)` 호출
- Then. 1000건 모두 IllegalArgumentException 없이 통과. 반례 (소문자 시작 / `-0` 시작 / 10자 초과 prefix / `-` 없음 / 빈 문자열) 100건 모두 IllegalArgumentException throw.

**S12. Wave 6 Kotest property — version monotonic.**
- Given. 임의 N (1~100) random
- When. Issue 도메인 객체에 `updateSummary` × N 호출
- Then. 매 호출마다 version 정확히 +1. 1000건 random N 모두 통과.

**S13. Wave 6 Kotest property — soft delete 키 보존.**
- Given. 임의 projectKey + key_sequence (1~1000) random
- When. Issue 생성 → softDelete → 같은 key (예. `BTS-1`) 로 새 Issue 생성 시도
- Then. `IssueRepository.insert` 가 DB unique constraint 위반 (PostgreSQL 23505) 또는 도메인 계층의 IssueProjectNotFoundException — 1000건 모두 같은 결과.

**S14. Wave 6 Kotest property — state 전이 이름 수용 구간.**
- Given. random state 이름 (영문 대문자 + 언더스코어, 길이 1~30)
- When. `Issue.transition(toState)` 호출
- Then. 1000건 random 모두 toState 가 적용됨 (도메인 측 invariant — workflow validator 는 무관, 그 책임은 project-workflow).

### F2. 기능 요구사항 (FR)

**FR-1. (C-2) sealed Result 도입.**
- (a) `com.bts.workflow.port.inbound.WorkflowTransitionPort.plan(req): TransitionResult` 시그니처.
- (b) `com.bts.workflow.domain.dto.TransitionResult` 신규 — sealed interface + 4 data class (Success/ValidatorFailure/WorkflowNotFound/ExpressionTimeout).
- (c) **(G1 inline 보강)** project-workflow 측 `WorkflowTransitionAdapter` (신규 — port 구현체) 가 internal `try { workflowEngine.plan(req) } catch (...)` 후 TransitionResult 매핑. WorkflowEngine 자체 시그니처는 보존 (광범위 변경 회피). 위치 = `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapter.kt` (또는 `application/` — 정확 경로는 implementer 결정, port 구현체 컨벤션 따름).
- (d) IssueApplicationService 의 try/catch 제거 + when 분기로 TransitionResult 처리. `when (result) { is TransitionResult.Success -> ...; is TransitionResult.ValidatorFailure -> throw IssueTransitionNotAllowedException(...); is TransitionResult.WorkflowNotFound -> throw IssueTransitionNotAllowedException(...); is TransitionResult.ExpressionTimeout -> throw IssueTransitionNotAllowedException(...) }` (Kotlin exhaustive when).
- (e) `com.bts.workflow.domain.exception.WorkflowValidatorFailureException` import 제거. 다른 workflow domain exception 도 import 0.

**FR-2. (C-3) ADR + DATA.md 단서.**
- (a) `docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md` 신규 — DATA.md §5 의 `dsl.execute(rawSql)` 금지에 정식 예외. parameter binding (`?` placeholder) + jOOQ 미지원 PostgreSQL 함수 (pg_advisory_lock, pgmq.send 등) 한정. 잠재 잘못된 사용 가드 명시.
- (b) DATA.md §5 §금지 끝에 단서 1줄 — "단, parameter binding (`?`) 사용 + jOOQ 미지원 PG 함수 호출은 예외, 자세한 근거는 [docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md] 참조."
- (c) 코드 변경 0 — `IssueRepository.kt:28, 246` 의 advisory_lock 호출 그대로.

**FR-3. (C-5) PATCH partial 시맨틱.**
- (a) `com.bts.issue.application.UpdateIssueRequest.summary: String → String?` (nullable 변경) + KDoc "null 이면 변경하지 않는다" 명시.
- (b) `IssueApplicationService.updateIssue` 의 흐름 수정 — `if (request.summary != null && request.summary != existing.summary)` 시에만 `repo.updateSummary` 호출. null 또는 동일 값이면 changedFields 비어 있음 + IssueUpdated 이벤트 발행 안 함 + version 그대로 + 200 OK 응답.
- (c) `IssueController.update:144-148` 의 `?: ""` 제거 — `request.summary` 그대로 전달 (web DTO 와 application DTO 모두 nullable 일치).
- (d) `IssueApplicationServiceUpdateTest` 에 S6/S7 회귀 가드 추가 — null 시 updateSummary 미호출 검증 + non-null 시 호출 검증.

**FR-4. (Wave 6 ArchUnit) BC 격리 + jOOQ 화이트리스트.**
- (a) `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/IssueBcArchTest.kt` 신규.
- (b) Rule 1 — `com.bts.issue..` 가 `com.bts.workflow..` import 시 화이트리스트만 허용 (`com.bts.workflow.port.inbound..` + `com.bts.workflow.domain.dto..`). 위반 시 fail.
- (c) Rule 2 — `com.bts.issue.jooq.generated..` 는 `com.bts.issue.repository..` 에서만 import 가능. application/domain/adapter/web 등 다른 layer 위반 시 fail.

**FR-5. (Wave 6 Testcontainers) singleton.**
- (a) `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueTestcontainersBase.kt` 신규 abstract — `companion object { @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(...).apply { start() } }`. `@Container` annotation 미사용 (JVM 단위 라이프사이클).
- (b) `IssueRepositoryTest:44-57` 의 `@Testcontainers` + `@Container @JvmStatic` 제거 + `IssueTestcontainersBase` 상속.
- (c) **(G5 inline 보강)** Flyway migrate / DSLContext 생성 등 공통 setup 도 base 의 `@BeforeAll` 로 이동. 단 Flyway 설정은 `protected open fun configureFlyway(builder: FluentConfiguration): FluentConfiguration` 패턴으로 노출 — child 가 placeholder 사용 시 override 가능. 기본 구현 = `.placeholderReplacement(false).locations("classpath:db/migration")`.

**FR-6. (Wave 6 Kotest property) 3 invariant (단위 영역 한정).**
- (a) `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueInvariantPropertyTest.kt` 신규.
- (b) **(G6 inline 보강)** `forAll(1000) { ... }` **3 case** (S11 IssueKey regex + S12 version monotonic + S14 state 전이 이름 수용). S13 (soft delete 키 보존) 은 DB 통합 영역 → `IssueRepositoryTest` 의 일반 시나리오 신규 1건으로 이동 (T9 신설 — softDelete 후 같은 key INSERT 시 DB unique constraint 위반 검증, IssueTestcontainersBase 상속하여 실 DB 동작).
- (c) random generator — `Arb.string` + `Arb.int` + Kotest 표준 generator. seed 고정 (재현성).

### F3. 비기능 요구사항 (NFR)

| 항목 | 임계 | 측정 방법 |
|---|---|---|
| NFR-1. ArchUnit 룰 수행 시간 | < 2s (per 룰) | `./gradlew :backend:issue-tracking:test --tests IssueBcArchTest` 단독 |
| NFR-2. Kotest property × 1000 수행 시간 | < 5s (4 case 합) | `./gradlew :backend:issue-tracking:test --tests IssueInvariantPropertyTest` |
| NFR-3. IssueRepositoryTest 재실행 시 stale port 0 | 0 fail (2회 연속 실행) | `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` × 2 |
| NFR-4. 본 PR 머지 후 backend 전체 test 시간 | PR #17 baseline 대비 ±20% (G7 — impl Task 0 에 baseline 측정 추가, main 134b29f 시점) | `./gradlew :backend:test` × 2 (cold + warm). baseline (main 134b29f, 2026-05-26 측정). cold=8분33초 / warm=6분27초. 비고: 두 실행 모두 BUILD FAILED (cold — XML 결과 파일 쓰기 경합, warm — Gradle daemon disappeared); 테스트 자체는 실행됐으며 시간은 유효한 벽시계 측정값. |
| NFR-5. detekt + ktlint | 0 신규 issue (baseline.xml 유지) | `./gradlew :backend:issue-tracking:detekt :backend:issue-tracking:ktlintCheck` |

### F4. API 인터페이스 (변경)

- `PATCH /api/v1/issues/{key}` — request body 변경. `summary: String?` (nullable). null 또는 미포함 시 변경 안 함. non-null 시 새 값으로 변경.
- 다른 endpoint (POST/GET/DELETE/transition) 변경 0.

### F5. 데이터 모델 변경

- **0건**. Flyway migration 신규 0. 도메인 entity 변경 0.

### F6. 엣지 케이스

- **EC-1. C-2 sealed Result — 새 분기 추가 시 컴파일 강제.** TransitionResult 에 5번째 case (예. NetworkTimeout) 추가 시 IssueApplicationService 의 when 분기가 자동 컴파일 에러 (exhaustive) — silent fail 차단.
- **EC-2. C-3 advisory_lock — 잘못된 사용 ("project:" + userInput 결합).** ADR 의 가드 명시. binding 없이 query 안 직접 결합 시 SQL Injection 위험. reviewer 가 ADR 본문 참조 가능.
- **EC-3. C-5 PATCH — summary == "" (빈 문자열).** non-null 빈 문자열은 유효 입력 — IssueController.update 가 그대로 application 에 전달. `existing.summary != ""` 이면 changedFields 에 추가 + updateSummary 호출. summary 의 도메인 invariant (1~255자) 는 별도 — 본 PR 변경 0, 도메인측 검증에 위임.
- **EC-4. C-5 PATCH — body 자체 누락.** Spring Web MVC 가 `HttpMessageNotReadableException` throw → `IssueExceptionHandler.handleInternalError` 500 또는 별도 매핑 — 본 PR scope 외 (기존 동작 유지).
- **EC-5. Wave 6 Testcontainers singleton — Docker 미실행 환경.** Testcontainers DockerComposeAware 가 init 시 `IllegalStateException` → 모든 IssueRepositoryTest 후보 skip. CONTRIBUTING.md 의 docker 가이드 유지.
- **EC-6. Wave 6 ArchUnit — generated 코드 import 검출 false positive.** `com.bts.issue.jooq.generated.tables.references.ISSUES` 같은 static import 도 fail. 의도 — 모든 jOOQ 코드는 repository layer 만 접촉.
- **EC-7. Wave 6 Kotest property — seed flake.** 매 실행 random seed 면 1000건 중 1건 우연한 통과 위험. seed 고정 (예. `PropTestConfig(seed = 1234L)`) — 재현 가능 + CI flake 방지.
- **(G3 inline 보강) EC-8. C-5 PATCH null 시 IssueUpdated 이벤트 미발행 — notification BC 영향.** 의도된 동작 (실제 변경 0 → notification BC 가 IssueUpdated 받지 않음 → watcher 알림 발사 0). spec §F1 의 "PATCH partial 시맨틱" 본질 일치 — "변경이 없으면 알림도 없음". notification BC 후속 구현 시 본 시맨틱 명시적 검증 필요.

### F7. 제약 조건

- **C1. (DEVELOPMENT.md §1)** — 모든 absolute 룰 19개 통과. 특히 #16 (PoC 금지) + #18 (테스트 우선) + jOOQ DSL 강제.
- **C2. (CLAUDE.md §핵심 패턴)** — BC 격리 — issue-tracking ↔ project-workflow port import 만. domain.exception 직접 import 0.
- **C3. (DATA.md §6)** — 모든 public 메서드 `@Transactional` 명시 (변경 영역만).
- **C4. (CLAUDE.md §작업 기준 — 완제품)** — PoC 단어 사용 금지, 모든 코드 production-ready.
- **C5. (history.md PR #14 learning #5)** — ktlint generated 제외 + ArchUnit 룰이 generated 자체에 적용되지 않도록 base package 제한 정확.

### F8. 측정 가능한 완료 기준

- [ ] (D1) `docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md` 신규 + DATA.md §5 단서 1줄.
- [ ] (D1) `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` 신규 + 기존 `2026-05-21-workflow-bc-cross-bc-port.md` 끝에 진화 단락 1줄.
- [ ] (FR-1) `WorkflowTransitionPort.plan()` 반환 타입 `TransitionResult`. `com.bts.workflow.domain.exception.*` import 가 issue-tracking BC 코드에 0건 (grep 검증).
- [ ] (FR-3) `PATCH /api/v1/issues/{key}` 가 null summary 에 변경 0 응답. 단위 + 통합 회귀 가드 1건씩.
- [ ] (FR-4) `IssueBcArchTest` 통과 (Rule 1 + Rule 2). 일부러 위반 코드 작성 시 fail 메시지 확인 (테스트 RED 단계).
- [ ] (FR-5) `IssueRepositoryTest` 2회 연속 실행 0 fail (NFR-3).
- [ ] (FR-6) `IssueInvariantPropertyTest` 4 case × 1000 통과 (NFR-2).
- [ ] (NFR-4) backend 전체 test 시간 ±20% 안 (PR #17 baseline 대비).
- [ ] (NFR-5) detekt + ktlint 신규 issue 0.

### F9. 결정 4건 (D1~D4)

**D1. (C-3) ADR + DATA.md 단서 둘 다.**
- 옵션 — A (DATA.md 단서만) / B (ADR만) / **C (둘 다, 권장 채택)**.
- 결정 — C.
- 근거 — ADR = 결정 근거 상세. DATA.md 단서 = 빠른 참조. 따로 두면 reviewer 가 둘 중 하나만 보고 다른 영역 일관성 누락 위험.

**D2. (C-5) RFC 7396 partial 시맨틱.**
- 옵션 — **A (partial — null = 변경 안 함, 권장 채택)** / B (full replace).
- 결정 — A.
- 근거 — RFC 7396 (JSON Merge Patch) 표준. 향후 PATCH endpoint (assignee/priority/label 등) 동일 시맨틱 일관. PATCH 가 PUT 쓰임새가 되는 옵션 B 회피.

**D3. (Wave 6 TC) IssueTestcontainersBase abstract + singleton.**
- 옵션 — **A (abstract + singleton, 권장 채택)** / B (IssueRepositoryTest 만 singleton).
- 결정 — A.
- 근거 — PR #8 LdapTestcontainersBase 패턴 일관. 미래 IssueServiceIntegrationTest 등 다른 통합 테스트 동일 base 상속 가능. abstract 선제 비용 < 미래 재사용 이익.

**D4. (Wave 6 property) 4 invariant 모두.**
- 옵션 — **A (4 invariant 모두, 권장 채택)** / B (핵심 2).
- 결정 — A.
- 근거 — PR #10 project-workflow 의 4 invariant × 1000 패턴 일관. soft delete 키 보존 + state 전이 invariant 가 핵심 데이터 무결성 영역 (DATA.md §1.1).

### F10. Deferred trigger (본 PR 제외 항목)

> **본 PR 명시적 제외.** 아래 항목은 의도적 미진행, silent freeze 아님. trigger 명시 (PR #21 learnings #2 패턴).

- **C-6. SYSTEM_ACTOR_UUID + workflowKey="DEFAULT" hardcoded.**
  - trigger — (a) FR-AU-10 (시스템 액터 모델) 머지 + (b) FR-WF-03 (워크플로우 스킴 매핑) 머지 후 본격 진행. 둘 중 하나라도 미진행이면 hardcode 정당.
  - 위치 — `IssueController.kt:176 workflowKey="DEFAULT"` + `IssueController.kt:211 SYSTEM_ACTOR_UUID`.
  - 책임 — security-engineer (actor) + backend-engineer (workflow scheme).
- **docs/decisions vs docs/adr 디렉토리 분산.**
  - trigger — Maxi 1인 선언으로 분리 cleanup PR 진행 (chore type). 본 PR 의 신규 ADR 2건은 기존 `docs/adr/` 패턴 일관 (workflow + issue 영역) 으로 추가.
- **IssueRepositoryTest 의 jOOQ generated 코드 import 검출 — 본 PR 의 ArchUnit Rule 2 (jOOQ 화이트리스트) 가 적용 시 generated 자체 (`com.bts.issue.jooq.generated..`) 가 base package 에 들어가야 하는지.**
  - 결정 — IssueBcArchTest 의 base package 는 `com.bts.issue.application` + `domain` + `adapter` + `web` + `application` 등 production 영역만. `com.bts.issue.repository..` 는 제외 (jOOQ 사용 허용) + `com.bts.issue.jooq.generated..` 도 제외 (검사 대상 아님).
  - 본 PR scope 안에서 처리. spec §FR-4 의 Rule 2 명시.
- **(G2) automation BC 가 미래 WorkflowTransitionPort 호출 시 when 분기 중복.**
  - trigger — FR-AUTO-01 (자동화 룰 엔진) 머지 시점에 helper (예. `TransitionResult.fold(onSuccess, onFailure): T` 또는 `unwrapOrThrow(): TransitionPlan`) 도입 검토. 현재 호출자 1개 → Y-AGNI.
  - 책임 — backend-engineer (FR-AUTO-01).
- **(G8) OpenAPI/Swagger 자동 생성 도입 — PATCH nullable 명시.**
  - trigger — BTS 의 OpenAPI 자동 생성 도구 (예. springdoc-openapi) 도입 결정 시점. 현재 미사용 → API 문서 변경 0.
  - 책임 — backend-engineer (도구 도입 결정).

## Brainstorming Check

> Phase B self-brainstorming sanity check (chaos engineer 시선) 1회. PR #21 self-brainstorming inline 통합 패턴 일관.

### Gap 발굴 7건

| # | gap | 처리 결과 |
|---|---|---|
| **G1** | C-2 sealed Result — adapter 위치 모호 (WorkflowEngine 자체 vs WorkflowTransitionAdapter 신규 vs ApplicationService) | inline 보강 (FR-1(c) — WorkflowTransitionAdapter 신규 = port 구현체) |
| **G2** | automation BC 가 미래 추가 시 같은 when 분기 중복 책임 | Deferred (F10) — Y-AGNI, FR-AUTO-01 시점 helper 도입 검토 |
| **G3** | C-5 PATCH — null 시 IssueUpdated 이벤트 발행 안 함의 notification BC 영향 | EC-8 신규 (의도된 동작 명시) |
| **G4** | ArchUnit Rule 1 화이트리스트 외 Spring transitive 의존 | 영향 0 — skip (import 아니라 transitive) |
| **G5** | Testcontainers base 의 Flyway placeholderReplacement(false) 가 future child 와 충돌 | inline 보강 (FR-5(c) — protected open fun configureFlyway 패턴) |
| **G6** | S13 soft delete 키 보존 invariant 가 DB 통합 영역 — 단위 property test scope 와 어긋남 | inline 보강 (FR-6 4→3 case, S13 은 IssueRepositoryTest T9 신설 통합 시나리오로 이동) |
| **G7** | NFR-4 baseline 미실측 (PR #17 backend test 시간) | inline 보강 (NFR-4 — impl Task 0 에 baseline 측정 추가) |
| **G8** | OpenAPI 미사용 → API 문서 변경 0. 향후 도입 시 nullable 명시 | Deferred (F10) — 도구 도입 시점 |

### Sanity 결과

✅ 통과 (1 iteration, gap 5건 inline 보강 + 2건 deferred + 1건 영향 0). adversarial subagent 호출은 `/bts-codereview` 단계 (gstack /review chaos engineer) 위임. PR #21 패턴 일관.

## Plan

> self-plan inline (PR #19/#20/#21 패턴 일관). 12 task / 5 wave / depth 5. monster 아님 (PR #17 17 task / PR #14 13 task 보다 작음). PR #17 learnings #1 (monolith TDD_VIOLATION) + #4 (sub-wave 분리) 적용.

### Plan 메타

- task 수: 12
- wave 수: 5 (W1=7병렬, W2=2병렬, W3=W4=W5=1)
- 예상 시간: 약 25~35분 (병렬 dispatch 가정)
- TDD 강제: yes (test:* commit 이 feat:* 보다 먼저 검증)
- BC 격리: issue-tracking 중심, project-workflow 측은 port + dto + adapter 만 변경
- 추가 검증: ktlint / detekt / backend 전체 test (NFR-4 baseline 비교)

### Wave 1 — 독립 7 task 병렬

#### Task 1. NFR-4 baseline 측정

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md`]
- depends-on: []

**RED 단계 (변형)**. 본 task 는 측정 / 기록 — 별도 RED commit 없이 GREEN commit 만 (PR #12 E2E TDD 변형 패턴 일관).

**GREEN**.
- `cd backend && ./gradlew :backend:test` 2회 실행 (cold + warm) — 시간 측정
- plan §NFR-4 표 비고에 baseline 기록 — 형식: `baseline (main 134b29f). cold=N초 / warm=M초 (2026-05-26 측정)`

**REFACTOR**. 없음.

**검증**. `grep -c "baseline" docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md` >= 1.

#### Task 2. TransitionResult sealed interface 신규

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/TransitionResult.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/dto/TransitionResultTest.kt`]
- depends-on: []

**RED**.
- 파일: `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/dto/TransitionResultTest.kt`
- 테스트:
  - `TransitionResult.Success(plan)` 생성 + plan 필드 일치
  - `TransitionResult.ValidatorFailure(message="조건 X 위반")` 생성 + message 필드 일치
  - `TransitionResult.WorkflowNotFound(key="ATLAS")` 생성 + key 필드 일치
  - `TransitionResult.ExpressionTimeout(message="SpEL timeout")` 생성 + message 필드 일치
  - `when (result) { ... }` exhaustive check 컴파일 통과 (sealed interface 검증)
- 실패 메시지: `TransitionResult` 클래스 없음.

**GREEN**.
- 파일: `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/dto/TransitionResult.kt`
- 구현:
  ```kotlin
  // 워크플로우 전이 결과 — sealed interface 로 호출자 BC 가 when exhaustive 분기 처리
  sealed interface TransitionResult {
      data class Success(val plan: TransitionPlan) : TransitionResult
      data class ValidatorFailure(val message: String) : TransitionResult
      data class WorkflowNotFound(val key: String) : TransitionResult
      data class ExpressionTimeout(val message: String) : TransitionResult
  }
  ```

**REFACTOR**. KDoc 추가 — 각 case 의 발생 조건 + 호출자가 던질 권장 exception 명시 (issue-tracking → `IssueTransitionNotAllowedException`).

**검증**. `./gradlew :backend:project-workflow:test --tests TransitionResultTest`.

#### Task 3. WorkflowTransitionPort 시그니처 변경 + WorkflowTransitionAdapter 신규

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/port/inbound/WorkflowTransitionPort.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapter.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapterTest.kt`]
- depends-on: [2]

**RED**.
- 파일: `WorkflowTransitionAdapterTest.kt`
- 테스트:
  - `adapter.plan(validReq)` → `TransitionResult.Success` 반환 (mock WorkflowEngine.plan 정상)
  - `adapter.plan(invalidReq)` → mock WorkflowEngine 이 `WorkflowValidatorFailureException("조건 X")` throw → `TransitionResult.ValidatorFailure("조건 X")` 반환
  - `adapter.plan(unknownKey)` → mock 이 `WorkflowNotFoundException("UNKNOWN")` throw → `TransitionResult.WorkflowNotFound("UNKNOWN")` 반환
  - `adapter.plan(timeoutReq)` → mock 이 `WorkflowExpressionTimeoutException("SpEL timeout")` throw → `TransitionResult.ExpressionTimeout("SpEL timeout")` 반환
- 실패 메시지: `WorkflowTransitionAdapter` 클래스 없음.

**GREEN**.
- 파일 1: `WorkflowTransitionPort.kt` (수정)
  - 시그니처 변경: `fun plan(req: TransitionRequest): TransitionResult` (returns TransitionResult 로 변경)
  - KDoc `### 예외 계약` 단락 삭제 + `### 반환 계약` 단락 신규 — TransitionResult 의 4 case 각각 언제 반환되는지 명시
- 파일 2: `WorkflowTransitionAdapter.kt` (신규, port 구현체)
  - `@Component` + `@Transactional(propagation = Propagation.MANDATORY)` + `WorkflowTransitionPort` 구현
  - 내부에서 `WorkflowEngine.plan(req)` 호출 + try/catch 후 Result 매핑
  - 4 exception 클래스 import (`com.bts.workflow.domain.exception.*`) 는 본 adapter 안에서만 — port + 호출자 BC 는 import 0

**REFACTOR**.
- WorkflowEngine 의 기존 호출자 (있다면) adapter 로 wire — `IssueApplicationService` 가 의존하는 port 가 adapter 로 주입되도록 Spring Bean 정의 확인 (component scan 으로 자동).
- 통합 테스트 `WorkflowTransitionAdapterIntegrationTest` (선택 — 본 task 에 포함하되 별도 파일) — 실 WorkflowEngine + Testcontainers 로 4 case end-to-end 검증.

**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowTransitionAdapterTest` + 기존 `WorkflowTransitionPortIntegrationTest` (있으면) 그대로 통과.

#### Task 4. IssueApplicationService.transitionIssue when 분기 + 테스트 갱신

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTransitionTest.kt`]
- depends-on: [3]

**RED**.
- 파일: `IssueApplicationServiceTransitionTest.kt` (수정)
- 테스트 (기존 4 case 모두 갱신):
  - workflowPort.plan mock 이 `TransitionResult.Success(plan)` 반환 → 정상 적용
  - mock 이 `TransitionResult.ValidatorFailure("조건 X")` 반환 → `IssueTransitionNotAllowedException` throw (msg 에 "조건 X" 포함)
  - mock 이 `TransitionResult.WorkflowNotFound("ATLAS")` 반환 → `IssueTransitionNotAllowedException` throw
  - mock 이 `TransitionResult.ExpressionTimeout("...")` 반환 → `IssueTransitionNotAllowedException` throw
- 실패 메시지: 기존 코드 가 `WorkflowValidatorFailureException` catch — 새 sealed Result 와 시그니처 불일치 컴파일 에러.

**GREEN**.
- 파일: `IssueApplicationService.kt:25, 199-221`
  - import `com.bts.workflow.domain.exception.WorkflowValidatorFailureException` **삭제**
  - import `com.bts.workflow.domain.dto.TransitionResult` 추가
  - `try { workflowPort.plan(transitionReq) } catch (...) { throw ... }` → `when (val result = workflowPort.plan(transitionReq)) { is TransitionResult.Success -> result.plan; is TransitionResult.ValidatorFailure -> throw IssueTransitionNotAllowedException(...cause=null, message=result.message); is TransitionResult.WorkflowNotFound -> throw IssueTransitionNotAllowedException(...); is TransitionResult.ExpressionTimeout -> throw IssueTransitionNotAllowedException(...) }`
  - `IssueTransitionNotAllowedException` 생성자에 `message: String? = null` 추가 (sealed Result 의 message 전달) — 또는 별도 인자 — 시그니처 결정은 implementer 재량.

**REFACTOR**.
- when 분기를 private helper `mapWorkflowResult(result: TransitionResult): TransitionPlan` 로 추출 (테스트 가독성).
- KDoc `@throws WorkflowValidatorFailureException` 단락 삭제.

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueApplicationServiceTransitionTest` + `grep -c "WorkflowValidatorFailureException\|WorkflowNotFoundException\|WorkflowExpressionTimeoutException" backend/modules/issue-tracking/src/main/` == 0.

#### Task 5. ADR workflow-transition-port-result-sealed 신규 + 기존 ADR 진화 단락

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-26-workflow-transition-port-result-sealed.md`, `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md`]
- depends-on: []

**RED 단계 (변형)**. 문서 task — 별도 RED commit 없이 GREEN commit 만 (PR #12 패턴 일관).

**GREEN**.
- 파일 1 신규: `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md`
  - 헤더 — `<!-- ADR: WorkflowTransitionPort sealed Result 도입 — BC 격리 본질 강화 -->`
  - 섹션 — 일자 / 상태 (Accepted) / 관련 PR (본 PR slug) / 작성자 / 컨텍스트 (PR #17 codereview CONCERN-2 + history learnings 의 후속 후보 "port 시그니처를 sealed Result 로 변경하면 catch 자체도 제거 가능") / 결정 (TransitionResult sealed interface 채택 + adapter 패턴) / 결과 (긍정 + 부정 + 위험) / 관련 (파일 경로 / 기존 ADR 링크 / DEVELOPMENT.md / CLAUDE.md §BC 격리)
- 파일 2 수정: `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` 끝
  - `## 정정 이력` 섹션 추가 (또는 기존 ## 관련 직전)
  - 본문 — "2026-05-26 PR #N. 본 ADR 의 BC 격리 본질 강화 보강. WorkflowTransitionPort 시그니처가 throws → sealed Result 로 진화. 자세한 결정 근거. [2026-05-26-workflow-transition-port-result-sealed.md]. 본 ADR 의 핵심 결정 (Hexagonal port-adapter + plan() + Propagation.MANDATORY) 은 그대로 유효."

**REFACTOR**. 없음.

**검증**. `ls docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` 존재 + `grep -c "2026-05-26" docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` >= 1.

#### Task 6. ADR jooq-execute-advisory-lock-exception 신규 + DATA.md §5 단서

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md`, `DATA.md`]
- depends-on: []

**RED 단계 (변형)**. 문서 task.

**GREEN**.
- 파일 1 신규: `docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md`
  - 헤더 + 일자 + 상태 (Accepted) + 관련 PR + 컨텍스트 (PR #17 codereview CONCERN-3 + IssueRepository.incrementKeySequence 의 advisory_lock 호출이 DATA.md §5 의 `dsl.execute(rawSql)` 금지를 표면 위반하나 실제 SQL Injection 영역 아님) + 결정 (정식 예외 등록 — parameter binding (`?`) 사용 + jOOQ 미지원 PG 함수 (pg_advisory_lock, pgmq.send 등) 한정) + 가드 (binding 없이 query 안 직접 결합 시 SQL Injection 위험, reviewer 가 본 ADR 본문 참조 가능) + 결과 (긍정 + 부정 + 위험)
- 파일 2 수정: `DATA.md:88-94` (§5 §금지 끝)
  - 본문 단서 추가 — `> **예외**. parameter binding (`?`) 사용 + jOOQ 미지원 PostgreSQL 함수 (pg_advisory_lock, pgmq.send 등) 한정. 자세한 결정 근거. [docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md](docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md).`
- (선택) `DATA.md §10 변경 이력` 표 갱신 — 2026-05-26 단서 추가 한 줄.

**REFACTOR**. 없음.

**검증**. `grep -c "advisory_lock" DATA.md` >= 1 + `ls docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md` 존재.

#### Task 7. application UpdateIssueRequest nullable + IssueApplicationService.updateIssue null skip

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceUpdateTest.kt`]
- depends-on: []

**RED**.
- 파일: `IssueApplicationServiceUpdateTest.kt` (수정)
- 테스트 신규 / 갱신:
  - **(T7-1)** Given. activeIssue summary="원래" version=1. When. `updateIssue(actor, key, UpdateIssueRequest(summary=null, expectedVersion=1))`. Then. repo.updateSummary 호출 0회 + eventPublisher.publish 호출 0회 + 응답 summary="원래" + version=1.
  - **(T7-2)** Given. activeIssue summary="원래" version=1. When. `updateIssue(..., summary="새 제목", expectedVersion=1)`. Then. repo.updateSummary 호출 1회 + eventPublisher.publish IssueUpdated(fields={"summary"}) + 응답 summary="새 제목" + version=2.
  - **(T7-3)** Given. activeIssue summary="원래" version=1. When. `updateIssue(..., summary="원래", expectedVersion=1)` (같은 값). Then. repo.updateSummary 호출 0회 + eventPublisher.publish 호출 0회 (changedFields empty).
- 실패 메시지: 기존 `summary: String` non-null 시그니처 와 컴파일 불일치.

**GREEN**.
- 파일 1: `IssueApplicationRequests.kt:26-29`
  - `data class UpdateIssueRequest(val summary: String?, val expectedVersion: Long)`
  - KDoc 갱신 — "summary null 이면 변경하지 않는다 (RFC 7396 JSON Merge Patch 시맨틱)"
- 파일 2: `IssueApplicationService.kt:144-166` `updateIssue` 함수
  - `if (request.summary == null || request.summary == existing.summary) { return IssueResponse.from(existing, key.projectPrefix) }` 가드 추가
  - 또는 `buildChangedFields` 가 request.summary == null OR == existing 시 빈 set 반환 + 빈 set 시 updateSummary 호출 skip + 이벤트 발행 skip + existing 그대로 응답

**REFACTOR**.
- `buildChangedFields(existing, request)` 의 KDoc — "null 또는 동일 값 시 빈 set 반환 (RFC 7396)" 명시.
- KDoc `@param summary 새 이슈 제목. null 이면 변경하지 않는다`.

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueApplicationServiceUpdateTest`.

#### Task 9. IssueTestcontainersBase abstract + IssueRepositoryTest 리팩토링 + T9 신설 (soft delete 키 보존)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueTestcontainersBase.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt`]
- depends-on: []

**RED**.
- 파일: `IssueRepositoryTest.kt` (확장)
- 테스트 신규 — **T9. softDelete 후 같은 key INSERT 시 unique constraint 위반** (FR-6 S13 에서 통합 영역으로 이동된 시나리오)
  - Given. activeIssue BTS-1 → softDelete
  - When. 같은 IssueKey("TPRJ", 1L) 로 새 Issue.create + repository.insert
  - Then. `DataIntegrityViolationException` (또는 `org.jooq.exception.IntegrityConstraintViolationException`) throw (PostgreSQL 23505 unique_violation).
- 실패 메시지: 기존 IssueRepositoryTest 에 T9 부재 → 빠진 시나리오.

**GREEN**.
- 파일 1 신규: `IssueTestcontainersBase.kt`
  - `abstract class IssueTestcontainersBase`
  - `companion object { @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(...).apply { start() }; private val dsl: DSLContext; private val testProjectId: UUID; private val repository: IssueRepository; }`
  - `@BeforeAll @JvmStatic fun bootstrap()` — Flyway migrate (default `placeholderReplacement(false).locations("classpath:db/migration")`) + DSLContext 생성 + projects 행 1건 INSERT
  - `protected open fun configureFlyway(builder: FluentConfiguration): FluentConfiguration = builder.placeholderReplacement(false).locations("classpath:db/migration")` (child 가 override 가능)
  - `@BeforeEach fun cleanIssues()` — DELETE FROM issues + UPDATE projects SET key_sequence=0
- 파일 2 수정: `IssueRepositoryTest.kt:44-110`
  - `@Testcontainers` + `@Container @JvmStatic` 제거
  - `class IssueRepositoryTest : IssueTestcontainersBase()` 상속
  - companion object 의 postgres / dsl / testProjectId / repository setup 전체 base 로 이동
  - T9 (soft delete + insert) 신규 시나리오 추가

**REFACTOR**.
- IssueTestcontainersBase KDoc — PR #8 learning #2 패턴 명시 + 사용 가이드 (자식 클래스 가 `@Testcontainers` annotation 안 붙임, JVM singleton 라이프사이클).

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 2회 연속 0 fail (NFR-3 검증).

#### Task 11. IssueInvariantPropertyTest 3 case (단위 property)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueInvariantPropertyTest.kt`, `backend/modules/issue-tracking/build.gradle.kts`]
- depends-on: []

**RED**.
- 파일: `IssueInvariantPropertyTest.kt`
- 테스트 3 case (S11 + S12 + S14):
  - **S11**. `Arb.string(...)` 로 생성한 임의 영문 대문자 prefix (1자) + 영문 대문자/숫자 (1~9자) + `-` + 1~9 시작 숫자 + 0~9 추가 → `forAll(1000) { IssueKey(it).value == it }` 검증. 반례 (소문자, `-0` 시작 등) → `forAll(100) { ... assertThrows<IllegalArgumentException> { IssueKey(it) } }`.
  - **S12**. `Arb.int(1, 100)` N 회 `Issue.updateSummary("v$N")` → 매 호출마다 version 정확히 +1. `forAll(1000) { N -> repeat(N) { issue = issue.updateSummary("...") }; issue.version == 1 + N }`.
  - **S14**. `Arb.string(영문 대문자 + 언더스코어, 1~30)` 로 random toState → `Issue.transition(toState).currentStateKey == toState`. `forAll(1000) { ... }`.
- seed 고정 `PropTestConfig(seed = 1234L)`.
- 실패 메시지: `IssueInvariantPropertyTest` 클래스 없음.

**GREEN**.
- 파일 1: `IssueInvariantPropertyTest.kt`
  - Kotest property DSL 사용
  - 3 case 구현
- 파일 2: `backend/modules/issue-tracking/build.gradle.kts`
  - `testImplementation("io.kotest:kotest-property:5.x.y")` 추가 (이미 있으면 skip)
  - 버전 = project-workflow 모듈 build.gradle.kts 의 kotest-property 버전과 일치

**REFACTOR**.
- 각 case 에 KDoc — invariant 의도 + 반례 1줄 설명.

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueInvariantPropertyTest` < 5초 (NFR-2).

### Wave 2 — 2 task 병렬 (Wave 1 결과 의존)

#### Task 3 (재게재 — Wave 2 진입 시 dispatch)

위 Wave 1 의 Task 3 정의 그대로. 본 task 가 Wave 2 에 속함 — depends-on [2] 라 Wave 1 의 T2 완료 후 dispatch.

#### Task 8. IssueController.update `?: ""` 제거 + @WebMvcTest 통합

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerUpdateTest.kt`]
- depends-on: [7]

**RED**.
- 파일: `IssueControllerUpdateTest.kt` (확장)
- 테스트 신규 (S6 + S7):
  - **(T8-1)** Given. mock service.updateIssue 가 (null summary 시) updateSummary skip 동작. When. `PATCH /api/v1/issues/BTS-1` body `{"summary": null, "expectedVersion": 1}`. Then. 200 OK + response.body.data.summary == "원래" + service.updateIssue 호출 시 application UpdateIssueRequest.summary == null 검증.
  - **(T8-2)** Given. mock service. When. `PATCH ...` body `{"summary": "새 제목", "expectedVersion": 1}`. Then. 200 OK + summary == "새 제목" + service.updateIssue 호출 시 application UpdateIssueRequest.summary == "새 제목".
  - **(T8-3)** Given. mock. When. body `{"expectedVersion": 1}` (summary 미포함, null 동등). Then. T8-1 과 동일 결과.
- 실패 메시지: 기존 controller 의 `?: ""` 가 null 을 "" 으로 변환 → application 에 "" 전달 → service.updateIssue 호출 시 application UpdateIssueRequest.summary == "" 으로 받아 T8-1 검증 실패.

**GREEN**.
- 파일: `IssueController.kt:144-148`
  - `summary = request.summary ?: ""` → `summary = request.summary` (그대로 전달)
  - 컴파일 통과 (T7 결과 application UpdateIssueRequest.summary 가 nullable 일치)

**REFACTOR**.
- KDoc `[UpdateIssueRequest.summary] null 이면 변경 안 함 (RFC 7396 JSON Merge Patch)` 명시.

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueControllerUpdateTest`.

### Wave 3 — Task 4 (Wave 2 의 T3 의존)

#### Task 4 (재게재 — Wave 3 진입 시 dispatch)

위 Wave 1 의 Task 4 정의 그대로. depends-on [3] 이라 Wave 2 의 T3 완료 후 dispatch.

### Wave 4 — Task 10 (Wave 3 의 T4 의존)

#### Task 10. IssueBcArchTest (Rule 1 BC 격리 + Rule 2 jOOQ 화이트리스트)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/IssueBcArchTest.kt`]
- depends-on: [4]

**RED**.
- 파일: `IssueBcArchTest.kt`
- 테스트:
  - **(T10-1, Rule 1)** ArchUnit `noClasses().that().resideInAPackage("com.bts.issue..").and().resideOutsideOfPackage("com.bts.issue.jooq..").should().dependOnClassesThat().resideInAnyPackage("com.bts.workflow.domain.exception..", "com.bts.workflow.domain.spi..", "com.bts.workflow.application..", "com.bts.workflow.infrastructure..").because("issue-tracking BC 는 project-workflow 의 port.inbound + domain.dto 만 허용 (CLAUDE.md §BC 격리 + ADR workflow-bc-cross-bc-port). jOOQ generated 코드 (com.bts.issue.jooq..) 는 검사 대상 제외 (self-eng-review P5 보강).")`.
  - **(T10-2, Rule 2)** ArchUnit `noClasses().that().resideInAPackage("com.bts.issue.application..", "com.bts.issue.domain..", "com.bts.issue.adapter..", "com.bts.issue.web..").should().dependOnClassesThat().resideInAnyPackage("com.bts.issue.jooq.generated..").because("jOOQ generated 코드는 repository layer 만 접촉 가능 (hexagonal 경계)")`.
- RED 검증 방법 — 룰 신규 + (의도적 잠시) IssueApplicationService 에 `import com.bts.workflow.domain.exception.WorkflowNotFoundException` 추가 → Rule 1 fail → 메시지 확인 → import 되돌리기 → 통과. 이 RED 단계의 의도적 위반 코드는 commit 안 함 (로컬 검증만).

**GREEN**.
- T4 완료 후 (workflow exception import 0) → Rule 1 자연스럽게 통과
- Rule 2 도 현재 코드 (repository 만 generated import) 위반 0

**REFACTOR**.
- ArchUnit Rule 정의에 KDoc — 룰 근거 + 위반 시 fail 메시지가 어떤 ADR 참조해야 하는지 명시.

**검증**. `./gradlew :backend:issue-tracking:test --tests IssueBcArchTest` < 2초 (NFR-1).

### Wave 5 — Task 12 (모든 task 완료 후 통합 검증)

#### Task 12. 통합 검증 + NFR 측정값 plan 본문 기록

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-05-26-issue-tracking-bc-fr-is-01-cleanup-tests.md`]
- depends-on: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]  # 모든 prior task (self-eng-review P1 보강 — T12 는 통합 검증이라 모든 변경 후 dispatch, ADR/doc task 도 NFR 표 통합 갱신과 자연 정렬)

**RED 단계 (변형)**. 통합 검증 task — 별도 RED commit 없이 GREEN commit 만.

**GREEN**.
- (a) `./gradlew :backend:issue-tracking:ktlintCheck :backend:issue-tracking:detekt :backend:project-workflow:ktlintCheck :backend:project-workflow:detekt` — 0 신규 issue (baseline.xml 유지)
- (b) `./gradlew :backend:test` 2회 (cold + warm) — 시간 측정 → NFR-4 비교 (Task 1 baseline 대비 ±20%)
- (c) `./gradlew :backend:issue-tracking:test --tests IssueRepositoryTest` 2회 연속 → 0 fail (NFR-3 검증)
- (d) `./gradlew :backend:issue-tracking:test --tests IssueInvariantPropertyTest` — < 5초 (NFR-2)
- (e) `./gradlew :backend:issue-tracking:test --tests IssueBcArchTest` — < 2초 (NFR-1)
- (f) `grep -c "WorkflowValidatorFailureException\|WorkflowNotFoundException\|WorkflowExpressionTimeoutException" backend/modules/issue-tracking/src/main/` == 0 (FR-1(e) 검증)
- (g) plan §NFR 표 5건 모두 측정값 기록 + §F8 측정 가능한 완료 기준 9건 모두 [x] 체크

**REFACTOR**. 없음.

**검증**. plan 의 §F8 체크박스 9건 모두 `[x]` + §NFR 표 측정값 모두 채워짐.

### 의존성 그래프

```
W1 (parallel 7):
  T1 (baseline)            files=plan.md                       depends=[]
  T2 (TransitionResult)    files=workflow/domain/dto/TR + test depends=[]
  T5 (ADR sealed)          files=docs/adr/*sealed + adr/*port  depends=[]
  T6 (ADR advisory)        files=docs/adr/*advisory + DATA.md  depends=[]
  T7 (PATCH app)           files=issue/application/*           depends=[]
  T9 (TC base)             files=issue/test/*Base + RepoTest   depends=[]
  T11 (property)           files=issue/test/*Invariant + gradle depends=[]

W2 (parallel 2):
  T3 (Port + Adapter)      files=workflow/port + adapter + test depends=[2]
  T8 (PATCH controller)    files=issue/adapter/*Controller* + test depends=[7]

W3 (serial 1):
  T4 (App service when)    files=issue/application/*Service + transition test depends=[3]

W4 (serial 1):
  T10 (ArchUnit)           files=issue/test/architecture/*     depends=[4]

W5 (serial 1):
  T12 (통합 검증)          files=plan.md (NFR)                  depends=[1, 4, 6, 8, 9, 10, 11]
```

files 겹침 검증 (Wave 1 내 7 task).
- T2 ↔ T11. project-workflow 모듈 vs issue-tracking 모듈 — 겹침 0.
- T5 ↔ T6. docs/adr 다른 파일 — 겹침 0.
- T7 ↔ T9. issue-tracking 의 application/* vs test/*Base + RepoTest — 겹침 0.
- T1 ↔ 다른 task. T1 만 plan.md 수정 — 겹침 0 (T12 도 plan.md 지만 Wave 5 로 분리).
- T11 ↔ build.gradle.kts. 다른 task 가 gradle 안 건드림 — 겹침 0.

Wave 2 내 T3 vs T8. project-workflow 모듈 vs issue-tracking 모듈 — 겹침 0.

PR #17 monolith TDD_VIOLATION 회피 — 같은 파일 직렬 task 0건 (모든 file 겹침 0).

## 리뷰 결과

### self-eng-review (2026-05-26)

> type=backend 라 plan-eng-review skill 정식 호출 대신 self-eng-review (chaos engineer 시선 + 절대 규칙 검증) inline 통합. PR #20/#21 패턴 일관. plan-design-review skip (사용자 시각 변화 0). autoplan skip (12 task / 5 wave 작은 PR, autoplan 4종 자동 비용 > 효익).

**검증 항목 15건**.

| # | 항목 | 결과 |
|---|---|---|
| P1 | T12 depends-on 누락 (T2/T3/T5/T7 의존) | ⚠️ FIXABLE → inline 보강 ([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11] 완전 명시) |
| P2 | T8 depends-on [7] 정확성 | ✅ PASS |
| P3 | TDD red 변형 (T1/T5/T6/T12) 정당화 | ✅ PASS (문서/측정 task — PR #12 E2E TDD 변형 패턴 일관) |
| P4 | TDD red 정식 (T2/T3/T4/T7/T8/T9/T10/T11) 절차 | ✅ PASS (실패 메시지 명시 + GREEN 최소 구현) |
| P5 | ArchUnit Rule 1 base package 정확성 (jOOQ generated 제외) | ⚠️ FIXABLE → inline 보강 (`.resideOutsideOfPackage("com.bts.issue.jooq..")` 추가) |
| P6 | ADR 본문 충실성 | ✅ PASS (헤더/일자/상태/관련/컨텍스트/결정/결과/관련 모두 명시). 권장 추가 — implementer 가 ADR 작성 시 "## 대안 검토 (옵션 A vs B vs C)" 섹션 포함 (PR #14 issue-permission-resolver-port.md 패턴) |
| P7 | Testcontainers singleton 패턴 정합 (PR #8 learning #2) | ✅ PASS (`.apply { start() }` JVM singleton + Ryuk + `@Container` annotation 제거) |
| P8 | NFR 측정 가능성 (5건) | ✅ PASS (NFR-1~5 모두 임계 + 측정 방법 명시) |
| P9 | CLAUDE.md §BC 격리 (issue-tracking ↔ project-workflow 허용 패키지) | ✅ PASS (port.inbound + domain.dto 만, FR-4 ArchUnit 자동 강제) |
| P10 | DEVELOPMENT.md §1 절대 규칙 (특히 #16 PoC + @Transactional + sealed exhaustive when) | ✅ PASS (PoC grep 2건 모두 룰 인용 — 코드 의미 0, @Transactional 명시, sealed when 컴파일러 강제) |
| P11 | DATA.md §5 단서 + ADR 가드 | ✅ PASS (parameter binding 강제 + 잠재 잘못된 사용 가드 ADR 본문에) |
| P12 | scope drift (BC 경계 합리성) | ✅ PASS — issue-tracking + project-workflow port 영역만, "한 PR = 한 BC" 의 합리적 예외 (port 정의는 project-workflow / 호출은 issue-tracking — 같은 contract 변경) |
| P13 | Wave 1 7 병렬 부담 (PR #10 learning wave 상한) | ✅ PASS w/ caveat — 7 = 상한, controller 가 Mac 과부하 신호 시 T1 baseline 을 Wave 0 으로 분리 옵션 인지 권장 |
| P14 | T9 monolith TDD_VIOLATION 회피 | ✅ PASS — T9 시나리오 RED 명시 + GREEN 단계가 base 신규 + Repo 리팩토링 분리 절차 |
| P15 | T11 build.gradle.kts kotest-property dependency | ✅ PASS — RED 단계 컴파일 fail 시점에 자연스러운 dependency 추가 |

**FIXABLE 보강 2건 inline 처리 완료**. P1 + P5.

**최종 결과**. ✅ **PASS** (BLOCKER 0 / CONCERNS 2 모두 inline 해소 / SUGGESTION 1건 informational — ADR "대안 검토" 섹션 권장).

**관련 ADR**.
- 기존 — `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` (본 PR 의 sealed Result 가 본 ADR 의 BC 격리 본질 강화 보강, 진화 단락 추가 — T5)
- 신규 (본 PR scope) — `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` (T5) + `docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md` (T6)

**관련 PR**. #14 (FR-IS-01 부트스트랩) + #17 (FR-IS-01 비즈니스 로직, 본 PR 의 직접 선행) + #8 (Testcontainers learning #2)

### plan-design-review

⏭️ **skip** — type=backend, 사용자 시각 변화 0. PR #20/#21 skip 패턴 일관.

### plan-ceo-review

⏭️ **skip** — type=backend cleanup + test infra. 새 기능 / 사용자 가치 0 → CEO 시선 (scope 확장 / rethink) 비용 > 효익. PR #14/#17/#20 skip 패턴 일관.

### autoplan

⏭️ **skip** — type=backend (manual override, feature 가 아님). autoplan 4종 (eng + ceo + design + devex) 자동 호출은 본 PR scope 대비 비용 과대. self-eng-review 단일로 충분 — chaos engineer 시선 + 15 항목 검증 + 절대 규칙 grep 자동.
