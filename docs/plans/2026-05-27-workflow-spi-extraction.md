# 공통 SPI 모듈 분리 — issue-tracking ↔ project-workflow 순환 의존 해결

> slug: workflow-spi-extraction
> type: backend (구조 리팩토링)
> agent: backend-engineer
> primary BC: project-workflow (양 BC 동시 — BC 격리 정당 예외)
> 생성: 2026-05-27

## Brief

**사용자 원문.** 공통 SPI 모듈 분리 — modules:workflow-spi 신설로 issue-tracking ↔ project-workflow circular dependency 해결. workflow 쪽 WorkflowTransitionPort + TransitionRequest/Plan/Result, issue 쪽 IssueTypeId/IssueTypeKey 를 SPI 모듈로 이전하고 양쪽 BC 의 상호 모듈 의존을 SPI 의존으로 교체. PR #18 (project-workflow FR-WF-02) 의 머지를 막는 latent circular dep 를 푸는 것이 목적.

**배경 — latent circular dependency (머지 순서로 완성되는 고리).**
- PR #17 (머지본, main) — `issue-tracking` 의 `IssueApplicationService.kt:24-27` 이 `com.bts.workflow.domain.dto.{TransitionPlan,TransitionRequest,TransitionResult}` + `com.bts.workflow.port.inbound.WorkflowTransitionPort` import 도입 → issue-tracking → project-workflow 방향 의존
- PR #18 (project-workflow FR-WF-02, 미머지) — `project-workflow` 의 5 파일이 `com.bts.issue.type.domain.{IssueTypeId,IssueTypeKey}` import + `build.gradle.kts:34` 에 `implementation(project(":modules:issue-tracking"))` → project-workflow → issue-tracking 방향 의존
- 두 PR 각자는 머지 시 cycle 부재 → PR #18 이 합쳐지는 순간 양방향 cycle 완성. `./gradlew :modules:project-workflow:compileKotlin` FAILURE 로 검증됨.

**해결 방향.** 두 BC 가 공유하는 port/VO 만 중립 SPI 모듈로 분리. 양 BC 가 SPI 만 의존 → 상호 모듈 의존 제거 → cycle 소멸.

**이전 대상 (실재 검증 완료, 2026-05-27 grep).**
- workflow → SPI. `WorkflowTransitionPort` (port.inbound), `TransitionRequest` / `TransitionPlan` / `TransitionResult` (domain.dto)
- issue → SPI. `IssueTypeId` / `IssueTypeKey` (issue.type.domain)

**미결정 (← bts-domain / Maxi 확인 대상).**
- SPI 모듈 이름. `modules:workflow-spi` vs `modules:shared-kernel`
- 새 패키지 네임스페이스 (예. `com.bts.spi.workflow.*`)
- IssueTypeId/Key 가 issue 도메인 정체성이 강한데 SPI 로 빼는 게 DDD 상 타당한지 (shared-kernel 패턴 vs published-language)

## 도메인 정리

- **BC**. project-workflow + issue-tracking (양 BC — 구조 리팩토링, BC 격리 정당 예외)
- **결정 (Maxi 2026-05-27)**. 옵션 A — `modules:shared-kernel` 신설, 공유 port/VO/DTO 양쪽 다 이전. 양방향 직접 import 완전 제거.
- **패키지**. `com.bts.shared.workflow.*`(포트+DTO 6) + `com.bts.shared.issue.*`(VO 2). 루트는 두 당사자 BC 의 `com.bts.*` 와 일치(identity 의 `com.atlas.bts.*` 차용 안 함).
- **이전 대상 8 (전이 폐쇄 코드 검증)**.
  - workflow→shared. `WorkflowTransitionPort` + `TransitionRequest`/`TransitionResult`/`TransitionPlan`/`FieldChange`/`DomainEvent`
  - issue→shared. `IssueTypeId`/`IssueTypeKey`
  - 잔류(제외 확인). `PostActionPlan`/`TransitionContext`/`WorkflowValidator`(폐쇄 밖), project-workflow 자체 `IssueDomainEvent`(별개 타입)
- **부수**. shared-kernel build.gradle 에 `io.konform`(TransitionRequest 검증) + spring-tx(`@Transactional(MANDATORY)`) 필요. 다른 모듈 의존 0.
- **새 용어**. "shared kernel"(DDD 공유 커널) — glossary 추가 후보(Maxi 승인 대기).
- **기존 결정 충돌**. `project-workflow/build.gradle.kts:34` 가 인용한 `ADR issue-type-cross-bc-introduction` 은 phantom(미실재). 본 ADR 이 공백 보강.
- **관련 ADR**. [docs/decisions/2026-05-27-shared-kernel-extraction.md](../decisions/2026-05-27-shared-kernel-extraction.md) (생성됨)
- **eng-review 검토 위임**. (1) `@Transactional` 인터페이스 유지 vs impl 이동(SPI 프레임워크 비결합), (2) shared-kernel 역참조 금지 ArchUnit 룰.

## 스펙

전체 스펙. [docs/specs/2026-05-27-workflow-spi-extraction.md](../specs/2026-05-27-workflow-spi-extraction.md)

핵심 3줄.
- workflow 포트/DTO 6종 MOVE(`com.bts.shared.workflow.*`) + issue VO 2종 CREATE(`com.bts.shared.issue.*`) → shared-kernel 신설.
- `issue-tracking → project-workflow` edge 제거로 PR #18 머지 시 cycle 부재. 동작 변경 0(import 경로 + package 선언만).
- jOOQ task 의존(`compileKotlin dependsOn generateJooq`) 동반 수정 → clean 빌드 회복(별도 커밋).

**base 사실.** main 엔 cycle 없음(issue→workflow 한 방향만). IssueTypeId/Key 는 PR #18 소유 → 본 PR 에선 shared-kernel 에 신규 생성. PR #18 은 rebase 시 자기 IssueTypeId 4파일 제거 + 재지정.

## Brainstorming Check

✅ 통과 (직접 sanity check — office-hours/brainstorming 스킬은 제품 발굴용이라 동작 변경 0 리팩토링엔 부적합, 코드 분석으로 gap 직접 탐지).

발견·해소한 gap.
- automation BC importer 우려 → **미생성 모듈**로 확인(KDoc 미래 의도). 영향 없음.
- IssueTypeId/Key 가 main 미존재 → 이전 전략 move→create 로 재정의(옵션 2, Maxi 결정).
- import 갱신 범위 ~43 파일(DTO 가 project-workflow 내부 광범위 사용) → plan task 에 반영.
- main clean 빌드 깨짐(jOOQ task 의존) → FR6 으로 동반 수정(Maxi 결정).
- 전이 폐쇄 완전성(FieldChange/DomainEvent) → 6 클래스 확정.

## Plan

> **리팩토링 TDD 변형.** 이동(move) task 는 RED 가 없다 — 기존 테스트가 회귀 가드. 이동 후 기존 테스트가 그대로 green 이면 성공. 커밋은 `refactor:`. bts-impl 의 TDD 순서 검증은 신규 작성(Task 2)에만 엄격 적용, 이동 task(3)는 "기존 테스트 green 유지 + refactor 커밋" 으로 판정.

### Task 1. shared-kernel 모듈 스캐폴드

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`, `backend/modules/shared-kernel/build.gradle.kts`]
- depends-on: []

**작업 (config — 단위테스트 없음, gradle 검증)**.
- `backend/settings.gradle.kts` 에 `include(":modules:shared-kernel")` 추가.
- `backend/modules/shared-kernel/build.gradle.kts` 신규 — plugins: kotlin jvm + plugin.spring + ktlint + detekt. dependencies: `io.konform:konform-jvm:0.7.0`, `org.springframework:spring-context`, `org.springframework:spring-tx`, test: kotlin-test/junit5/mockk. **flyway/jooq/webmvc/spring-boot 미포함.** group `com.atlas.bts`, JVM 21 — 기존 모듈 패턴 일치.
- 빈 패키지 디렉토리 `com/bts/shared/workflow`, `com/bts/shared/issue` 준비.

**검증**: `./gradlew :modules:shared-kernel:compileKotlin` SUCCESS (빈 모듈 컴파일).

### Task 2. IssueTypeId / IssueTypeKey 신규 생성 (TDD red→green)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueTypeId.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueTypeKey.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueTypeIdTest.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueTypeKeyTest.kt`]
- depends-on: [1]

**RED**: shared-kernel test 에 `IssueTypeIdTest`(value>0 require, 0/음수 IllegalArgument) + `IssueTypeKeyTest`(REGEX `^[a-z][a-z0-9-]{1,29}$`, 위반 시 IllegalArgument) 작성. 클래스 부재로 실패. (PR #18 의 동명 테스트 본문을 정본으로 차용 — package 만 `com.bts.shared.issue`.)

**GREEN**: `IssueTypeId(@JvmInline value class, Long, require>0)` + `IssueTypeKey(@JvmInline value class, String, REGEX)` 작성. PR #18 본과 동일 시맨틱.

**REFACTOR**: KDoc + REGEX companion 상수.

**검증**: `./gradlew :modules:shared-kernel:test` PASS.

### Task 3. workflow 포트/DTO 6종 이동 + importer 재지정 + build.gradle 재배선 (원자적 refactor)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/*` (신규 6), project-workflow 의 기존 6 클래스 (삭제), `backend/modules/issue-tracking/build.gradle.kts`, `backend/modules/project-workflow/build.gradle.kts`, project-workflow ~37 importer + issue-tracking ~6 importer (main+test)]
- depends-on: [1]

**작업 (이동 — RED 없음, 기존 테스트 회귀 가드)**.
1. 6 클래스를 `com.bts.shared.workflow` 로 이동 — `WorkflowTransitionPort`, `TransitionRequest`, `TransitionResult`, `TransitionPlan`, `FieldChange`, `DomainEvent`. package 선언만 변경, 본문 동일. (EC3: `@Transactional(MANDATORY)` 인터페이스 유지 — eng-review 결과 반영 가능.)
2. project-workflow 의 원본 6 파일 삭제.
3. 전 importer 의 `import com.bts.workflow.{port.inbound,domain.dto}.*` → `import com.bts.shared.workflow.*` 재지정. FQN 인라인 참조 포함. (grep 으로 누락 0 확인.)
4. `issue-tracking/build.gradle.kts:65` `:modules:project-workflow` 제거 → `:modules:shared-kernel` 추가.
5. `project-workflow/build.gradle.kts` `:modules:shared-kernel` 추가.

**검증**:
- `./gradlew :modules:project-workflow:compileKotlin` SUCCESS.
- `./gradlew :modules:project-workflow:test :modules:issue-tracking:test` 기존 테스트 전부 그대로 PASS (동작 불변 증명).
- `grep -rn "com.bts.workflow.domain.dto\|com.bts.workflow.port.inbound" backend/modules` → 0 (잔존 참조 없음).

### Task 4. jOOQ task 의존 수정 (clean 빌드 회복)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/build.gradle.kts`, `backend/modules/issue-tracking/build.gradle.kts`]
- depends-on: [3]   # 동일 build.gradle 파일 — 파일 겹침 자동 직렬

**작업 (config)**.
- 두 모듈 build.gradle 에 `tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") { dependsOn("generateJooq") }` (또는 동등) 추가. compileKotlin↔generateJooq 미선언 의존 해소.

**검증**: clean 상태에서 `./gradlew clean test` 가 generateJooq 수동 선행 없이 통과.

### Task 5. shared-kernel 역참조 금지 ArchUnit 가드 (NFR2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/architecture/SharedKernelBoundaryArchTest.kt`]
- depends-on: [1, 3]

**RED→GREEN**: `com.bts.shared..` 가 `com.bts.issue..` / `com.bts.workflow..` / `com.atlas.bts..` 에 의존하지 않음을 ArchUnit 으로 검증. shared-kernel 이 중립이므로 GREEN. identity 의 `SpiBoundaryArchTest` 선례 참고.

**검증**: `./gradlew :modules:shared-kernel:test --tests *SharedKernelBoundaryArchTest` PASS.

## Plan 메타

- task 수: 5
- wave 예상: W1=[T1] → W2=[T2, T3] (파일 무겹침, 둘 다 depends-on [1]) → W3=[T4, T5]. 약 3 wave.
- TDD 강제: Task 2 (신규 VO) 만 red→green 엄격. Task 3 (이동) 은 refactor — 기존 테스트 green 유지로 판정. Task 1/4 는 config(gradle 검증). Task 5 는 guard test.
- 동작 변경: 0 (NFR1). FR2 이동은 시그니처/본문 불변.
- eng-review 위임: EC3 `@Transactional` 인터페이스 유지 vs impl 이동(SPI 프레임워크 비결합 — identity 선례 대비).
- 검증 baseline 주의: main clean 빌드가 jOOQ 로 깨진 상태 → Task 4 이전 검증은 generateJooq 수동 선행 필요, Task 4 이후 우회 불요.

## 리뷰 결과

### plan-eng-review (2026-05-27)

**Step 0 스코프.** 축소 불요. ~43 파일 터치는 이동 리팩토링의 본질(import 경로 churn)이지 scope creep 아님. 신규 로직 0. SPI/shared-kernel 은 표준 DDD 패턴 [Layer 1].

**Architecture** ✅ — 순환 해소 정합성 코드 검증(build.gradle + import 양방향). issue→workflow edge 제거(본 PR) + workflow→issue edge 제거(PR #18 rebase 시 IssueTypeId shared-kernel 경유).
- **결정 1 (EC3 @Transactional) → 1A 채택.** `@Transactional(MANDATORY)` 를 포트 인터페이스에 유지, shared-kernel 이 spring-tx 의존. NFR1(동작 불변) 정신 — 가장 작은 diff, 어노테이션 위치 보존. identity SpiBoundaryArchTest "spi 프레임워크 비결합" 선례와는 다르나, 프레임워크 비결합화는 별도 후속으로 분리 가능(TODO 후보).

**Code Quality** ✅ — 이동은 package 선언만(본문/시그니처 불변). IssueTypeId/Key 가 본 PR shared-kernel 과 PR #18 issue-tracking 에 일시 중복 → PR #18 rebase 시 4파일 제거로 해소(DRY 회복, 문서화됨).

**Tests** ✅ gap 0 — FR3 신규 VO 는 TDD red→green(require>0 / REGEX 엣지 ★★★), FR2 이동은 기존 project-workflow/issue-tracking 테스트가 회귀 가드(green 유지 = 동작 보존 증명), T5 ArchUnit 이 shared-kernel 역참조 재발 차단. 런타임 동작 변경 0 이라 user-flow 커버리지 N/A.

**Performance** ✅ 해당 없음 — 런타임 변경 0, 모듈 분할의 빌드 영향 미미.

**NOT in scope.**
- @Transactional 프레임워크 비결합화(impl 이동) — 1A 로 보류, 별도 리팩토링 TODO.
- issue-tracking 의 jOOQ `false` 설정 자체 재검토 — FR6 는 dependsOn 만 추가, 설정 철학 변경 아님.
- PR #18 본체 수정 — 본 PR 머지 후 rebase 단계에서 처리.

**What already exists.** 재사용 대상 없음 — 순환은 cross-import 자체가 원인. identity-access 의 `SpiBoundaryArchTest`(`backend/modules/identity-access/.../architecture/`) 가 T5 ArchUnit 룰 작성 시 참조 선례.

**병렬화.** 대체로 직렬(이동 원자성 — 중간 상태 비컴파일). W1=[T1] → W2=[T2 issue VO, T3 workflow 이동] (파일 무겹침, 병렬 가능) → W3=[T4 jOOQ, T5 ArchUnit]. T4↔T3 는 동일 build.gradle 겹침으로 자동 직렬.

**Failure modes.** 런타임 신규 코드 경로 0 → 프로덕션 실패 모드 신규 없음. 유일 리스크는 빌드 타임(import 누락 시 compile fail — loud, silent 아님). T3 검증의 grep 잔존참조 0 체크가 가드.

- BLOCKER: 없음. unresolved: 0. critical gaps: 0.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 1 issue (EC3 @Transactional → 1A), 0 critical gaps |
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | 동작 변경 0 리팩토링 — 해당 약함 |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | UI 변경 없음 |
| DX Review | `/plan-devex-review` | DX gaps | 0 | — | API/도구 표면 변경 없음 |

- **UNRESOLVED:** 0
- **OUTSIDE VOICE:** 생략 (기계적 리팩토링, 코드 분석으로 충분 검증)
- **VERDICT:** ENG CLEARED — 구현 진행 가능. @Transactional 1A 확정, BLOCKER 0.
