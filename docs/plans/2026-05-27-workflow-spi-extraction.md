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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
