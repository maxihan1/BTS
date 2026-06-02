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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
