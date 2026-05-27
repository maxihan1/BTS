# shared-kernel 모듈 분리 — 스펙

> slug: workflow-spi-extraction · type: backend(구조 리팩토링) · 2026-05-27
> ADR: [docs/decisions/2026-05-27-shared-kernel-extraction.md](../decisions/2026-05-27-shared-kernel-extraction.md)
> 결정: 옵션 2 (완전 shared-kernel) + jOOQ task 의존 수정 동반 (Maxi 2026-05-27)

## 배경 한 줄

PR #18 머지 시 완성되는 `issue-tracking ↔ project-workflow` latent 순환 의존을, 공유 port/VO/DTO 를 중립 `modules:shared-kernel` 로 분리하여 제거한다. 동작 변경 0 의 구조 리팩토링.

## 사용자 시나리오 (Given-When-Then) — "사용자" = 빌드 시스템 + 두 BC

- **S1 — 순환 해소.** Given main 에 `issue-tracking → project-workflow` edge 존재, When shared-kernel 분리 후 issue-tracking 이 포트를 shared-kernel 에서 가져오면, Then `issue-tracking → project-workflow` edge 가 사라져 PR #18 머지 시에도 cycle 부재.
- **S2 — 동작 보존.** Given 기존 모든 단위/통합 테스트, When 클래스 이동 + import 경로 갱신만 수행하면(로직 변경 0), Then 모든 테스트가 그대로 통과.
- **S3 — clean 빌드 회복.** Given main 은 jOOQ task 의존 미선언으로 `./gradlew clean test` 실패, When `compileKotlin dependsOn generateJooq` 선언 추가, Then clean 상태에서 `./gradlew clean test` 가 우회 없이 통과.

## 기능 요구사항 (FR)

- **FR1.** `backend/modules/shared-kernel` 모듈 신설. `backend/settings.gradle.kts` 에 `include(":modules:shared-kernel")`.
- **FR2.** workflow 포트/DTO 6종 MOVE — `com.bts.workflow.*` → `com.bts.shared.workflow.*`. `WorkflowTransitionPort` + `TransitionRequest`/`TransitionResult`/`TransitionPlan`/`FieldChange`/`DomainEvent`.
- **FR3.** issue 타입 VO 2종 CREATE — `com.bts.shared.issue.*` 에 `IssueTypeId`/`IssueTypeKey` + 단위 테스트 신규 작성(main 에 미존재).
- **FR4.** import 경로 갱신 — 이동한 6 클래스의 모든 importer(project-workflow 내부 ~37 + issue-tracking ~6, main+test 합 ~43 파일)를 `com.bts.shared.workflow.*` 로 재지정. FQN 인라인 참조도 포함.
- **FR5.** build.gradle 의존 재배선 — issue-tracking: `:modules:project-workflow` 제거 + `:modules:shared-kernel` 추가. project-workflow: `:modules:shared-kernel` 추가.
- **FR6.** jOOQ task 의존 수정 — project-workflow + issue-tracking 의 `compileKotlin` 에 `dependsOn("generateJooq")` 선언 추가. (별도 커밋 — 순환 해소와 추적 분리.)

## 비기능 요구사항 (NFR)

- **NFR1 (동작 불변).** 프로덕션 로직 변경 0. 클래스 본문/시그니처 동일, package 선언 + import 경로만 변경.
- **NFR2 (격리).** shared-kernel 은 어떤 BC 도 역참조 금지 — ArchUnit 룰로 빌드 시점 강제(`com.bts.shared..` → `com.bts.issue..`/`com.bts.workflow..` 의존 0).
- **NFR3 (의존 최소).** shared-kernel build.gradle 은 konform + spring-context + spring-tx 만. flyway/jooq/webmvc 미포함.
- **NFR4 (검증 회복).** FR6 적용 후 `./gradlew clean test` 가 generateJooq 수동 우회 없이 통과.

## API 인터페이스 / 데이터 모델

변경 없음. REST 엔드포인트·DB 스키마·마이그레이션 무관(순수 코드 구조 이동).

## 엣지 케이스

- **EC1 (전이 폐쇄 완전성).** `TransitionPlan` 이 `FieldChange`+`DomainEvent` 참조 → 6 클래스 전부 이동해야 shared-kernel → project-workflow 역의존 미발생. (검증 완료.)
- **EC2 (잔류 타입).** `PostActionPlan`/`TransitionContext`(같은 패키지지만 폐쇄 밖), `WorkflowValidator`(KDoc만), project-workflow 자체 `IssueDomainEvent`(별개) — 이동 금지, project-workflow 잔류.
- **EC3 (@Transactional).** `WorkflowTransitionPort` 인터페이스의 `@Transactional(MANDATORY)` → shared-kernel 에 spring-tx 필요. 인터페이스 유지 vs impl 이동은 plan/eng-review 결정(identity SPI 의 "spi 프레임워크 비결합" 선례 대비).
- **EC4 (konform).** `TransitionRequest` 의 konform 검증 → shared-kernel 에 konform 의존 필요.
- **EC5 (PR #18 충돌).** 본 PR 이 옮긴 6 클래스의 import 경로 변경이 PR #18 의 동일 파일과 rebase 충돌 가능. PR #18 rebase 시 IssueTypeId 4파일 제거 + 5+2 파일 재지정과 함께 처리.

## 제약 조건

- BC 격리 정당 예외 — 구조 리팩토링이라 두 BC build.gradle/import 동시 수정. ADR 에 사유 명시.
- TDD 강제 — FR3(IssueTypeId/Key 신규)는 red→green→refactor. FR2(이동)는 기존 테스트가 회귀 가드(이동 후 그대로 통과 = green 유지).

## 측정 가능한 완료 기준

1. `./gradlew :modules:shared-kernel:test` PASS (IssueTypeId/Key 신규 테스트).
2. `./gradlew clean test` 전체 PASS — generateJooq 수동 우회 없이(FR6 효과).
3. `./gradlew :modules:project-workflow:compileKotlin` SUCCESS — 본 PR 단독 기준 cycle 부재.
4. shared-kernel ArchUnit 역참조 금지 룰 PASS.
5. `./gradlew ktlintCheck` PASS (detekt 는 BTS 전체 기존 상태라 별도).
