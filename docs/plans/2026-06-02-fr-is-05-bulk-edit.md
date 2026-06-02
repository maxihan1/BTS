# FR-IS-05 — 이슈 일괄 편집 + 일괄 상태 전이 (백엔드 D1~D5)

> slug: fr-is-05-bulk-edit
> type: api
> agent: backend-engineer
> BC: issue-tracking
> 생성: 2026-06-02

## Brief

FR-IS-05 (docs/plan/product/issue-tracking.md §2.2.1) — 이슈 일괄 편집 + 일괄 상태 전이.
이번 작업 범위는 **백엔드만 (D1~D5)**. 프론트 UI(D6)·E2E(D7)는 후속 PR로 분리.

- D1. 도메인 — BulkOp
- D2. 명세 — 트랜잭션 정책, 부분 실패 처리
- D3. 데이터 모델 — (FR-IS-01 활용, 신규 테이블 없음 예상)
- D4. 백엔드 — `POST /api/v1/issues/bulk-update`, 청크 처리
- D5. 백엔드 테스트 — 부분 실패 시 트랜잭션 동작

선행 FR-IS-01~04 모두 구현 완료. 일괄 상태 전이는 FR-IS-01/02의 전이 검증 로직 활용.

classify 결과: type=api, agent=backend-engineer, primary_bc=issue-tracking
(classify 원본은 project-workflow 오판 → fr-index.md 근거로 issue-tracking 정정)

## 도메인 정리

- **BC**: issue-tracking
- **실행 방식 결정 (Maxi, 2026-06-02)**: 비동기(Jira식 "진동벨"). 접수 → pgmq job 백그라운드 처리 → 진행률 조회. 동기(A) 대비 범위 ≈2배 비용 인지 후 확정.
- **트랜잭션 정책 결정 (Maxi, 2026-06-02)**: best-effort 부분 성공. 이슈별 독립 트랜잭션, 실패 항목만 사유와 함께 결과 리포트. 전체 롤백 아님 (Jira 동일).

### 새 Aggregate / 엔티티

- **BulkOperation** (Aggregate Root, 영속) — 일괄 작업 1건
  - `operationType`: `BULK_EDIT`(필드 일괄 편집) | `BULK_TRANSITION`(일괄 상태 전이)
  - `status`: `PENDING` → `RUNNING` → `COMPLETED`(부분 실패 포함) | `FAILED`(작업 자체 실패)
  - 진행 카운트: `totalCount`, `processedCount`, `succeededCount`, `failedCount`
  - `actorId`, `createdAt`, `startedAt`, `completedAt`
- **BulkOperationItem** (BulkOperation 하위) — 대상 이슈 1건의 결과
  - `issueKey`, `status`: `PENDING` | `SUCCEEDED` | `FAILED`, `failureReason`(실패 시 사유 코드/메시지)

### 새 용어 (glossary 추가 후보 — Maxi 승인 대기)

- **일괄 작업 (Bulk Operation)** — 여러 이슈에 같은 변경(편집 또는 상태 전이)을 한 번에 적용하는 비동기 작업 단위. best-effort 부분 성공.
- **일괄 작업 항목 (Bulk Operation Item)** — 일괄 작업 안의 개별 이슈 처리 결과.

### 도메인 규칙 / 정합성

- **도메인 우회 금지** — 각 이슈 처리는 기존 `IssueApplicationService.updateIssue`(L212) / `transitionIssue`(L339)의 도메인 정규화·검증을 그대로 거친다. learnings 2026-05-?? "PATCH merge 도메인 우회" 회귀 방지. 일괄이라고 repository 직행 금지.
- **전이 위임 유지** — 일괄 상태 전이는 이슈별로 기존 `WorkflowTransitionPort.plan()`(project-workflow 위임)을 호출. issue-tracking이 전이 규칙을 자체 구현하지 않는다 (BC 격리).
- **트랜잭션 경계 충돌 없음** — 각 이슈는 여전히 "이슈+히스토리+이벤트 한 트랜잭션". 일괄은 그 위의 application 오케스트레이션 (이슈별 독립 트랜잭션 N개).
- **권한** — 일괄 작업 시작 권한 + 이슈별 권한(UPDATE/TRANSITION) 개별 검증. 권한 없는 이슈는 작업 실패가 아니라 `FAILED` 항목으로 기록.

### 데이터 모델 명세 변경 (D3 수정 필요)

- 원래 D3 = "FR-IS-01 활용, 신규 테이블 없음" 전제 → **비동기 결정으로 무효화**.
- 신규 테이블: `bulk_operations`, `bulk_operation_items` (+ pgmq 큐 1개).

### spec(D2)으로 위임할 세부 결정

- 멱등성/재시도 — pgmq job 재처리 시 이미 SUCCEEDED 항목 중복 처리 방지.
- 결과 보존 기간 — 완료된 BulkOperation 정리(TTL/배치 cleanup) 정책.
- 일괄 전이 **혼합 상태(mixed from-state)** 처리 — 선택한 이슈들의 현재 상태가 제각각일 때 `toStateKey` 개별 검증 vs Jira식 그룹핑.
- 청크 크기 + 한 작업당 이슈 건수 상한.
- **pgmq consumer 패턴 신규 도입 검증** — prod에 백그라운드 job 소비 선례 없음. spec에서 워커 구동/실패격리/at-least-once 처리 정밀화.

### 기존 결정 충돌

- glossary: "일괄/Bulk" 용어 부재 → 신규 추가.
- 기존 ADR 충돌: 없음. pgmq(2026-05-22-pgmq-postgres-image) 도입 결정 위에 consumer 패턴을 처음 얹는 형태.

### 관련 ADR

- [docs/adr/2026-06-02-bulk-operation-async-architecture.md](../adr/2026-06-02-bulk-operation-async-architecture.md) (생성됨, 아래)

## 스펙

전체 스펙. [docs/specs/2026-06-02-fr-is-05-bulk-edit.md](../specs/2026-06-02-fr-is-05-bulk-edit.md)

핵심 요약.
- `POST /api/v1/issues/bulk-update` 접수 → `202` + bulkOperationId. 백그라운드 pgmq 워커가 이슈별 처리.
- best-effort 부분 성공. 항목별 SUCCEEDED/FAILED(reasonCode). 기존 updateIssue/transitionIssue 재사용, 전이는 WorkflowTransitionPort 위임.
- `GET /api/v1/bulk-operations/{id}` 진행률/결과 조회(작업 actor 한정). 완료 시 BulkOperationCompleted 이벤트 발행(FR8).
- 상한 1000건/청크 50, 멱등(항목 상태 기반), 결과 30일 TTL. 취소 API 없음(범위 제외).
- 신규 테이블 V008: bulk_operations, bulk_operation_items + pgmq 큐 q_bulk_operations(BTS 최초 consumer).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 7건 처리 — 수정가능 5건 인라인 보강 + Maxi 결정 2건(취소 제외 / 완료 이벤트 발행만).

## Plan

> 코드 배치: 기존 `type/` 서브패키지 응집 패턴을 따라 `com/bts/issue/bulk/` 하위에 도메인/리포지토리/application/worker/web 응집.
> 단일 Gradle 모듈(issue-tracking) — wave 병렬해도 test 컴파일 단위 공유로 일부 직렬화(learnings "bts-plan wave Gradle module compile").

### Task 1. V008 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V008__bulk_operations.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/db/V008MigrationIntegrationTest.kt`]
- depends-on: []

**RED**: `V008MigrationIntegrationTest`(Testcontainers) — `bulk_operations`/`bulk_operation_items` 테이블 + `q_bulk_operations` pgmq 큐 존재, UNIQUE(bulk_operation_id, issue_key) 검증. 실패: 테이블 없음.
**GREEN**: V008 sql — 두 테이블 + `SELECT pgmq.create('q_bulk_operations')`. init_codegen.sql에 동일 DDL 미러(jOOQ 상수 생성, learnings "jOOQ init_codegen 미러").
**REFACTOR**: 인덱스(bulk_operation_id, status) + 컬럼 코멘트.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*V008MigrationIntegrationTest"`

### Task 2. 도메인 BulkOperation / BulkOperationItem Aggregate

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/domain/BulkOperation.kt`, `.../bulk/domain/BulkOperationItem.kt`, `.../bulk/domain/BulkOperationStatus.kt`, `.../bulk/domain/BulkOperationType.kt`, `.../bulk/domain/FailureReasonCode.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/domain/BulkOperationTest.kt`]
- depends-on: []

**RED**: `BulkOperationTest` — 상태 전이(PENDING→RUNNING→COMPLETED/FAILED), **카운트 집계 멱등**(항목 상태 집계로 재계산, 같은 항목 2회 종료 처리해도 카운트 불변), 종료 항목 스킵 판정. 실패: 클래스 없음.
**GREEN**: 순수 Kotlin Aggregate. `recomputeCounts(items)`, `markItem(issueKey, SUCCEEDED/FAILED, reasonCode)`, `isTerminal(item)`. enum 4종.
**REFACTOR**: 불변식 KDoc + require 가드.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*BulkOperationTest"`

### Task 3. BulkOperation 리포지토리 (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/repository/BulkOperationRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/repository/BulkOperationRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: `BulkOperationRepositoryTest`(Testcontainers) — insert(작업+항목 배치), findById(items 포함), updateItemResult, recomputeAndPersistCounts, findCompletedBefore(TTL). 실패: 클래스 없음.
**GREEN**: jOOQ 기반 CRUD. 항목 결과 갱신은 status=PENDING WHERE 가드(멱등).
**REFACTOR**: 배치 insert + 쿼리 상수화.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*BulkOperationRepositoryTest"`

### Task 4. 접수 application service + 검증 + enqueue

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/application/BulkOperationApplicationService.kt`, `.../bulk/application/BulkUpdateRequest.kt`, `.../bulk/event/BulkOperationEnqueuePublisher.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/application/BulkOperationApplicationServiceTest.kt`]
- depends-on: [2, 3]

**RED**: `BulkOperationApplicationServiceTest` — 접수 검증(issueKeys 빈/1000 초과 → 400, dedup, payload 정합성 priority1..5·impact1..3·operationType↔payload), 영속+enqueue 한 트랜잭션(outbox). 실패: 클래스 없음.
**GREEN**: `submit(actor, request)` → 검증 → 작업+항목(PENDING) 영속 + `pgmq.send(q_bulk_operations, {bulkOperationId})` 같은 트랜잭션. bulkOperationId 반환.
**REFACTOR**: 검증 로직 분리 + 예외→400 매핑.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*BulkOperationApplicationServiceTest"`

### Task 5. pgmq consumer 워커 + 처리 로직 + 완료 이벤트 (BTS 최초 consumer)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/worker/BulkOperationWorker.kt`, `.../bulk/application/BulkOperationProcessor.kt`, `.../bulk/event/BulkOperationCompleted.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/worker/BulkOperationProcessorTest.kt`]
- depends-on: [4]

**RED**: `BulkOperationProcessorTest` — 청크 처리(상한/청크50), **best-effort 부분 성공**(권한·전이 실패는 항목 FAILED+reasonCode, 나머지 SUCCEEDED), **멱등 스킵**(이미 SUCCEEDED 항목 재처리 안 함), 완료 시 `BulkOperationCompleted` 이벤트 발행. 실패: 클래스 없음.
**GREEN**: `BulkOperationWorker`(@Scheduled 폴링 → `pgmq.read(vt, qty)` → process → `pgmq.delete`). `BulkOperationProcessor`: 항목별 기존 `IssueApplicationService.updateIssue`/`transitionIssue` 재사용(도메인 우회 금지), reasonCode 매핑, 카운트 집계 영속, 완료 이벤트 발행.
**REFACTOR**: vt/qty/청크 상수화 + 워커 단일성 KDoc(pgmq vt 단일처리).
**검증**: `./gradlew :backend:issue-tracking:test --tests "*BulkOperationProcessorTest"`

### Task 6. REST 엔드포인트 (POST 접수 / GET 조회)

**메타**.
- agent: `backend-engineer` (권한 가드는 review-plan에서 security-engineer 검토)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/web/BulkOperationController.kt`, `.../bulk/web/BulkOperationResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/web/BulkOperationControllerTest.kt`]
- depends-on: [4]

**RED**: `BulkOperationControllerTest`(MockMvc/slice) — `POST /api/v1/issues/bulk-update` → 202 + bulkOperationId, 검증 실패 400. `GET /api/v1/bulk-operations/{id}` → 200(작업 actor), 403(타인), 404(없음). 실패: 클래스 없음.
**GREEN**: 컨트롤러 2개 엔드포인트 + 조회 권한(actor 본인) 가드 + 응답 DTO 매핑.
**REFACTOR**: 예외 핸들러 정렬(기존 IssueExceptionHandler 패턴) + 응답 매핑 분리.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*BulkOperationControllerTest"`

### Task 7. 통합 테스트 (Testcontainers e2e)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/bulk/integration/BulkOperationIntegrationTest.kt`]
- depends-on: [5, 6]

**RED**: enqueue→consume→처리→결과 기록 e2e, **워커 재전달 멱등**(같은 메시지 2회 read 시 SUCCEEDED 스킵·카운트 불변), 혼합 from-state 일괄 전이 부분 성공, 1000건 상한, 완료 이벤트 발행 확인. 실패: 동작 미구현.
**GREEN**: 위 task들로 통과. 필요한 미세 보강만.
**REFACTOR**: 픽스처 헬퍼 정리.
**검증**: `./gradlew :backend:issue-tracking:test --tests "*BulkOperationIntegrationTest"`

## Plan 메타

- task 수: 7
- 예상 wave: Wave0 [T1,T2] → Wave1 [T3] → Wave2 [T4] → Wave3 [T5,T6] → Wave4 [T7] (단일 모듈 test 컴파일 직렬화 감안)
- TDD 강제: yes (test 커밋 선행 검증)
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산
- 추가 검증: ktlint, detekt(baseline 동결만), Testcontainers 통합
- review-plan 중점: security(조회 권한·접수 권한), pgmq consumer 운영(워커 단일성/vt), 도메인 우회 금지 재확인

## 리뷰 결과 (← /bts-review-plan 채움)
