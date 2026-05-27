<!-- ADR — issue-tracking ↔ project-workflow 순환 의존 해소를 위한 shared-kernel 모듈 분리 -->

# ADR: shared-kernel 모듈 분리 — issue-tracking ↔ project-workflow 순환 의존 해소

> 결정일. 2026-05-27
> 상태. Accepted
> 컨텍스트. PR #25 (slug `workflow-spi-extraction`). PR #18 (project-workflow FR-WF-02) 머지 차단 해소.
> 관련. PR #17 (issue-tracking FR-IS-01), PR #18 (project-workflow FR-WF-02)

## 컨텍스트

`issue-tracking` 과 `project-workflow` 두 BC(Bounded Context) 가 양방향 모듈 의존을 형성하여 **latent circular dependency** 가 발생했다.

- PR #17 (main 머지본) — `issue-tracking/build.gradle.kts:65` 가 `implementation(project(":modules:project-workflow"))` 도입. `IssueApplicationService.kt:24-27` 이 `com.bts.workflow.port.inbound.WorkflowTransitionPort` + `com.bts.workflow.domain.dto.{TransitionRequest,TransitionResult,TransitionPlan}` 를 import. (issue → workflow)
- PR #18 (project-workflow FR-WF-02, 미머지) — `project-workflow/build.gradle.kts:34` 가 `implementation(project(":modules:issue-tracking"))` 도입. 5 파일이 `com.bts.issue.type.domain.{IssueTypeId,IssueTypeKey}` 를 import. (workflow → issue)

두 PR 각자는 머지 시 cycle 부재. PR #18 이 main 에 합쳐지는 순간 양방향 cycle 완성 → `./gradlew :modules:project-workflow:compileKotlin` FAILURE (Circular dependency between :modules:issue-tracking:classes ↔ :modules:project-workflow:jar). "머지 순서로 완성되는 잠재 고리."

기존 `project-workflow/build.gradle.kts:34` 주석은 `ADR issue-type-cross-bc-introduction` 을 근거로 인용했으나 **그 ADR 파일은 실재하지 않는다** (phantom 참조 — 2026-05-20 learnings 의 그 함정). 즉 cross-BC import 를 정당화하는 문서가 부재한 상태로 고리가 형성됐다. 본 ADR 이 그 공백을 메운다.

## 선택지

순환 고리는 양방향이므로 한 방향만 끊어도 cycle 은 소멸한다. 세 가지 방식을 검토했다.

### A. shared-kernel — 공유 개념 양쪽 다 이전

신규 중립 모듈 `modules:shared-kernel` 이 두 BC 가 공유하는 port/VO/DTO 를 모두 보유. 두 BC 는 shared-kernel 만 의존.

- 장점. 양방향 모듈 의존 + 양쪽 BC 격리 위반 모두 제거. 대칭적이고 깨끗한 모듈 그래프. BTS 의 엄격한 BC 격리 원칙(직접 import 금지)과 정합.
- 단점. `IssueTypeId/IssueTypeKey` 가 issue-tracking 의 정체성 VO 인데 공유 모듈로 이동 → 소유권 희석. shared-kernel 이 잡동사니 서랍이 될 위험(단, 본 대상은 작고 안정적인 VO 라 위험 낮음).

### B. workflow-spi — project-workflow 의 발행 포트만 이전

신규 모듈 `modules:workflow-spi` 가 `WorkflowTransitionPort` + 관련 DTO 만 보유. issue-tracking 은 workflow-spi 만 의존. `IssueTypeId/Key` 는 issue-tracking 에 잔류, project-workflow 가 직접 import 유지.

- 장점. 더 작은 변경. IssueTypeId 가 자기 BC 에 잔류(올바른 소유권). workflow-spi = project-workflow 의 published language(발행 언어).
- 단점. project-workflow → issue-tracking 직접 import 1건이 명문화된 BC 격리 예외로 잔존.

### C. primitive — 경계에서 타입 ID 를 원시값으로

workflow-spi 가 포트+DTO 보유 + project-workflow 가 `IssueTypeId` 대신 `Long`/`String` 원시값 사용 → issue-tracking import 0.

- 장점. 고리 + 격리 위반 완전 제거. 가장 순수.
- 단점. `IssueTypeId(>0 검증)` / `IssueTypeKey(REGEX 검증)` 의 타입 안전을 project-workflow 경계에서 상실(primitive obsession). scheme 매핑 5 파일 재작업.

## 결정

**A 선택 (Maxi 결정, 2026-05-27).** `modules:shared-kernel` 신설 + 공유 port/VO/DTO 양쪽 다 이전.

이유. BTS 의 BC 격리 원칙은 "다른 BC 직접 import 금지" 로 엄격하다. A 만이 양방향 직접 import 를 모두 제거한다. 이전 대상 VO(`IssueTypeId/Key`) 는 다른 클래스 의존이 없는 작고 안정적인 `@JvmInline value class` 라 shared-kernel 잡동사니화 위험이 낮다.

### 이전 대상 (전이 폐쇄 — 코드 검증 완료 2026-05-27)

issue-tracking 이 도달 가능한 타입 전체를 이전해야 한다. `IssueApplicationService` 가 `WorkflowTransitionPort` + `TransitionRequest/Result/Plan` 을 import 하고, `TransitionPlan` 이 `FieldChange` + `DomainEvent` 를 참조하므로 후자 둘도 포함된다.

**workflow 쪽 (6) — `com.bts.workflow.*` → `com.bts.shared.workflow.*`**
- `WorkflowTransitionPort` (port.inbound)
- `TransitionRequest`, `TransitionResult`, `TransitionPlan`, `FieldChange`, `DomainEvent` (domain.dto)

**issue 쪽 (2) — `com.bts.issue.type.domain.*` → `com.bts.shared.issue.*`**
- `IssueTypeId`, `IssueTypeKey`

**이전 제외 (잔류 확인).** 같은 `com.bts.workflow.domain.dto` 패키지의 `PostActionPlan`, `TransitionContext` 는 issue-tracking 도달 폐쇄에 미포함 → project-workflow 잔류. `WorkflowValidator` (port KDoc 언급)는 실제 import 아님 → 잔류. project-workflow 자체의 `IssueDomainEvent` 와 본 `DomainEvent` 는 별개 타입.

### 패키지 네임스페이스

`com.bts.shared.*` (두 당사자 BC 의 루트 `com.bts.*` 와 일치. identity-access 의 `com.atlas.bts.*` 는 별개 컨벤션이라 차용 안 함).
- `com.bts.shared.workflow` — 포트 + 워크플로우 DTO 6종
- `com.bts.shared.issue` — IssueTypeId/Key 2종

### 모듈 build.gradle 변경

- 신규 `modules:shared-kernel/build.gradle.kts` — 의존. `io.konform`(TransitionRequest 검증), spring-tx(WorkflowTransitionPort 의 `@Transactional(MANDATORY)`). 다른 모듈 의존 0.
- `issue-tracking/build.gradle.kts` — `:modules:project-workflow` 의존 제거 → `:modules:shared-kernel` 추가.
- `project-workflow/build.gradle.kts` — `:modules:issue-tracking` 의존 제거 → `:modules:shared-kernel` 추가.
- `settings.gradle.kts` — `include(":modules:shared-kernel")` 등록.

### 결과 모듈 그래프 (cycle 부재)

```
issue-tracking   → shared-kernel
project-workflow → shared-kernel
(상호 직접 의존 0)
```

## 미해결 / eng-review 검토 항목

- `WorkflowTransitionPort` 의 `@Transactional(propagation = MANDATORY)` 를 인터페이스에 유지할지(shared-kernel → spring-tx 의존), 아니면 impl 측으로 옮기고 SPI 를 프레임워크 비결합으로 둘지. identity SPI ADR(`authentication-provider-spi-naming`)의 `SpiBoundaryArchTest`("spi 패키지 Spring 비결합") 선례와 대비.
- ArchUnit 룰 — shared-kernel 이 어느 BC 도 역참조하지 않음을 빌드 시점 강제(`com.bts.shared..` → `com.bts.issue..`/`com.bts.workflow..` 의존 금지).

## 영향

- 코드 — `com.bts.shared.*` 패키지 신설(8 클래스). issue-tracking 4 import 경로 갱신, project-workflow 5+ import 경로 갱신.
- PR #18 — 본 PR 머지 후 rebase. project-workflow 의 IssueTypeId import 경로가 `com.bts.shared.issue.*` 로 바뀜.
- glossary — "shared kernel"(DDD 공유 커널 패턴) 용어 추가 후보(Maxi 승인).
- 회귀 검증 — `./gradlew clean test` 전체 PASS + `:modules:project-workflow:compileKotlin` SUCCESS.

## 관련

- PR #17 (issue → workflow 의존 도입), PR #18 (workflow → issue 의존 도입), PR #25 (본 해소)
- 선례 ADR. [authentication-provider-spi-naming](2026-05-20-authentication-provider-spi-naming.md) (SPI 패키지 격리 + 프레임워크 어댑터 패턴)
- learnings. 2026-05-20 phantom 엔티티(미실재 ADR 인용 함정), 2026-05-22 BC 격리 예외 패턴
