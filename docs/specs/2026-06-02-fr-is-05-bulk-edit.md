# FR-IS-05 — 이슈 일괄 편집 + 일괄 상태 전이 (백엔드 D1~D5) — 스펙

> slug: fr-is-05-bulk-edit · BC: issue-tracking · 생성: 2026-06-02
> 도메인 결정: [docs/adr/2026-06-02-bulk-operation-async-architecture.md](../adr/2026-06-02-bulk-operation-async-architecture.md)
> 실행 방식: **비동기**(pgmq job) · 트랜잭션: **best-effort 부분 성공**

## 사용자 시나리오 (Given-When-Then)

### S1. 일괄 편집 접수
- **Given** 사용자가 우선순위 UPDATE 권한이 있는 이슈 80개를 골랐고
- **When** `POST /api/v1/issues/bulk-update`로 `operationType=BULK_EDIT`, 우선순위=High를 보내면
- **Then** 즉시 `202 Accepted` + `{ bulkOperationId, status: PENDING }`를 받고, 백그라운드 워커가 이후 처리한다.

### S2. 일괄 상태 전이 — 부분 성공
- **Given** 현재 상태가 제각각인 이슈 100개를 골랐고 `toStateKey=in_progress`로 전이를 요청
- **When** 워커가 각 이슈를 처리하면
- **Then** 전이가 유효한 이슈는 `SUCCEEDED`, 권한 없음·전이 규칙 위반·전이 불가 상태인 이슈는 `FAILED`(사유 포함). 작업 전체는 `COMPLETED`(부분 실패 포함).

### S3. 진행률/결과 조회
- **Given** 접수된 작업 id가 있고
- **When** `GET /api/v1/bulk-operations/{id}`를 호출하면
- **Then** `status`, `totalCount/processedCount/succeededCount/failedCount`, 항목별 결과(`items[]`)를 받는다.

### S4. 워커 크래시 후 재개 (멱등성)
- **Given** 워커가 50/100 처리 후 크래시했고 pgmq가 메시지를 재전달
- **When** 새 워커가 같은 작업을 다시 읽으면
- **Then** 이미 `SUCCEEDED`/`FAILED`인 항목은 건너뛰고 `PENDING` 항목만 처리한다 (중복 변경 없음).

## 기능 요구사항 (FR)

- **FR1** `POST /api/v1/issues/bulk-update` — 일괄 작업 접수. `operationType ∈ {BULK_EDIT, BULK_TRANSITION}`, `issueKeys[]`, 편집 필드(BULK_EDIT) 또는 `toStateKey`(BULK_TRANSITION). 검증 통과 시 `bulk_operations` + `bulk_operation_items`(이슈별 PENDING) 영속 + pgmq 큐 enqueue를 **한 트랜잭션**으로 커밋(outbox). `202` 반환.
- **FR2** 백그라운드 워커가 큐를 폴링해 작업을 읽고, `issueKeys`를 청크로 나눠 이슈별로 처리.
  - BULK_EDIT → 기존 `IssueApplicationService.updateIssue` 재사용.
  - BULK_TRANSITION → 기존 `transitionIssue`(내부에서 `WorkflowTransitionPort.plan` 위임) 재사용.
- **FR3** 이슈별 처리 결과를 `bulk_operation_items.status`(SUCCEEDED/FAILED + failureReason)에 기록하고 `bulk_operations` 카운트를 갱신. 모든 항목 종료 시 작업 `COMPLETED`.
- **FR4** `GET /api/v1/bulk-operations/{id}` — 진행률/결과 조회. 작업 actor 또는 admin만 조회.
- **FR5** **혼합 from-state 전이**: `toStateKey`만 받고 각 이슈의 현재 상태→`toStateKey` 전이 유효성을 이슈별로 `WorkflowTransitionPort`에 위임 검증. 불가하면 그 이슈만 `FAILED`. (Jira식 from-state 그룹핑 UI는 D6 범위.)
- **FR6** **멱등성**: 워커는 `status=PENDING` 항목만 처리. at-least-once 재전달 시 종료 항목 스킵.
- **FR7** **권한**: 접수는 인증 사용자(JWT/PAT). 이슈별 UPDATE/TRANSITION 권한을 처리 시점에 개별 검증. 권한 없는 이슈는 작업 실패가 아니라 `FAILED` 항목.
- **FR8** **완료 이벤트 발행**: 작업이 `COMPLETED`되면 `BulkOperationCompleted`(id, actorId, total/succeeded/failed) 이벤트를 pgmq outbox로 발행. notification BC가 후속 소비해 알림(범위 밖, D6/후속). 이벤트 발행은 작업 메타 완료 트랜잭션에 묶음.
- **범위 제외 (결정)**: 작업 **취소 API 없음**(Jira도 사후취소 비강조, 사전확인 모달은 D6). 필요 시 후속 FR.

## 비기능 요구사항 (NFR)

- **NFR1 건수 상한**: 한 작업 최대 **1,000 이슈**(Jira 기준). 초과 요청은 `400`. 내부 처리 **청크 50건**.
- **NFR2 실패 격리**: 한 이슈의 예외가 작업 전체를 중단시키지 않는다. 작업 자체 실패(`FAILED`)는 인프라 오류(큐/DB 접근 불가)에 한정.
- **NFR3 멱등 재시도**: pgmq visibility timeout 만료 재전달을 항목 상태로 흡수. 동일 이슈 2회 적용 금지.
- **NFR4 결과 보존**: 완료된 BulkOperation은 **30일** 보존 후 `@Scheduled` cleanup으로 삭제.
- **NFR5 트랜잭션 경계**: 이슈별 변경은 기존 "이슈+히스토리+이벤트 한 트랜잭션" 유지. 작업 메타 갱신(카운트/항목 상태)은 별도 짧은 트랜잭션.

## API 인터페이스 (REST)

```
POST /api/v1/issues/bulk-update
Body: {
  "operationType": "BULK_EDIT" | "BULK_TRANSITION",
  "issueKeys": ["PROJ-1", "PROJ-2", ...],        // 1..1000
  "edit":       { "priority": 3, "labels": [...], ... },  // BULK_EDIT 시
  "transition": { "toStateKey": "in_progress" }           // BULK_TRANSITION 시
}
→ 202 Accepted { "bulkOperationId": "uuid", "status": "PENDING", "totalCount": N }
→ 400 (issueKeys 비었거나 1000 초과, operationType과 payload 불일치)

GET /api/v1/bulk-operations/{id}
→ 200 {
  "id": "uuid", "operationType": "...", "status": "PENDING|RUNNING|COMPLETED|FAILED",
  "totalCount": N, "processedCount": N, "succeededCount": N, "failedCount": N,
  "items": [ { "issueKey": "PROJ-1", "status": "SUCCEEDED|FAILED|PENDING", "failureReason": "..." } ]
}
→ 403 (작업 actor/admin 아님), 404 (없음)
```

## 데이터 모델 변경 (D3 수정 — 신규 테이블)

마이그레이션 **V008**.

```
bulk_operations (
  id uuid PK,
  operation_type text NOT NULL,        -- BULK_EDIT | BULK_TRANSITION
  status text NOT NULL,                -- PENDING|RUNNING|COMPLETED|FAILED
  actor_id uuid NOT NULL,
  payload jsonb NOT NULL,              -- 편집 필드 또는 toStateKey
  total_count int NOT NULL,
  processed_count int NOT NULL DEFAULT 0,
  succeeded_count int NOT NULL DEFAULT 0,
  failed_count int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL,
  started_at timestamptz,
  completed_at timestamptz
)
bulk_operation_items (
  id uuid PK,
  bulk_operation_id uuid NOT NULL REFERENCES bulk_operations(id),
  issue_key text NOT NULL,
  status text NOT NULL,                -- PENDING|SUCCEEDED|FAILED
  failure_reason text,
  processed_at timestamptz,
  UNIQUE (bulk_operation_id, issue_key)
)
-- + pgmq 큐 q_bulk_operations (V008에서 pgmq.create)
```

init_codegen.sql 미러 필수 (learnings "jOOQ init_codegen 미러" — 안 하면 jOOQ 상수 미생성, repository 컴파일 불가).

## pgmq Consumer 패턴 (BTS 최초 도입)

- 현재 BTS는 pgmq를 **발행(producer)** 으로만 사용(`pgmq.send`). 소비 prod 코드 없음.
- 본 작업이 **소비자 패턴**을 처음 도입: `@Scheduled` 폴링 워커가 `pgmq.read(queue, vt, qty)`로 메시지를 읽고, 작업 처리 후 `pgmq.delete`. vt(visibility timeout) 만료 시 자동 재전달 → 멱등(FR6)으로 흡수.
- 워커는 issue-tracking 모듈 내부에 둔다 (BC 격리 — 전용 큐 q_bulk_operations).

## 명확화 (Brainstorming 보강)

- **BULK_EDIT 필드 시맨틱**: 기존 `updateIssue`의 RFC 7396 JSON Merge Patch 그대로. 필드 미포함/null = 무변경, 빈 배열(`labels: []`) = 라벨 비우기. 일괄도 동일 시맨틱.
- **접수 시 검증 vs 처리 시 실패 구분**:
  - **모든 이슈에 공통인 오류**(payload 자체 정합성 — priority 1..5, impact 1..3, toStateKey 형식, operationType↔payload 일치)는 **접수 시 전체 `400`**.
  - **이슈별로 갈리는 실패**(존재/권한/전이 가능 여부/낙관락)는 처리 시 항목 `FAILED`.
- **failure_reason 구조화**: 자유 텍스트 아님. `reasonCode`(enum: `NOT_FOUND`, `FORBIDDEN`, `TRANSITION_NOT_ALLOWED`, `VERSION_CONFLICT`, `WORKFLOW_NOT_CONFIGURED`, `TYPE_NOT_FOUND`) + 사람용 `message`. D6 UI가 코드로 분기 표시.
- **카운트 멱등성**: `processed/succeeded/failed_count`는 증분(+1)이 아니라 **항목 상태 집계로 재계산**. 재전달로 같은 항목을 다시 봐도 이미 종료 상태면 스킵 → 카운트 이중 증가 없음.
- **상태 전이 시점**: 워커가 작업을 처음 read 하면 PENDING→`RUNNING`. 모든 항목 종료 시 `COMPLETED`. 인프라 오류로 진행 불가 시에만 `FAILED`.
- **여러 프로젝트 혼합 issueKeys 허용**: 한 작업에 서로 다른 프로젝트 이슈 혼합 가능. 권한/워크플로우는 이슈별로 독립 평가되므로 best-effort와 정합.

## 엣지 케이스

- 빈 `issueKeys` / 1000 초과 → `400`.
- 중복 `issueKeys` → 접수 시 dedup (UNIQUE 제약).
- 존재하지 않는/소프트 삭제된 이슈키 → 항목 `FAILED`(NotFound 사유).
- 권한 없는 이슈 → 항목 `FAILED`(권한 사유).
- 전이 불가(현재 상태에서 toState 불가) → 항목 `FAILED`(전이 규칙 사유).
- 낙관락 충돌 → 항목 단위 1회 재조회 후 재시도, 그래도 충돌 시 `FAILED`.
- 처리 중 이슈 삭제됨 → 항목 `FAILED`.
- `operationType`과 payload 불일치(BULK_EDIT인데 transition만 있음) → `400`.
- 워커 다중 인스턴스 → pgmq vt로 단일 처리 보장 + 항목 상태 멱등.

## 제약 조건

- BC 격리: 전이 규칙 자체 구현 금지(WorkflowTransitionPort 위임). 다른 BC 직접 import 금지.
- 도메인 우회 금지: repository 직행 금지, 기존 application service 재사용.
- 절대 규칙(DEVELOPMENT.md): 소프트 삭제, 트랜잭션 경계 명시, 완제품 품질.

## 측정 가능한 완료 기준

- 단위 테스트: 접수 검증(상한/dedup/payload 불일치), 항목 상태 전이, 멱등 스킵, 부분 성공 카운트.
- 통합 테스트(Testcontainers): pgmq enqueue→consume→처리→결과 기록 e2e, 워커 재전달 멱등(SUCCEEDED 스킵), 1000건 상한, 혼합 from-state 전이 부분 성공.
- best-effort: 100건 중 일부 권한/전이 실패 시 나머지 SUCCEEDED + 정확한 카운트.

## 비동기 인프라 보강 (게이트1 리뷰 BLOCKER 해소 — Maxi 비동기(B) 확정 2026-06-02)

code-reviewer 적대적 리뷰 BLOCKER/CONCERN 반영. 비동기(B) 유지 + 워커 인프라 떠안기 결정.

- **B1 권한·actor**: 워커는 HTTP 밖 스레드 → `bulk_operations.actor_id`를 읽어 `ActorId` 복원 후 기존 `updateIssue/transitionIssue(actor, …)`에 전달. 권한 검증 실효성은 FR-PM-02(PR #53)가 운영용 `IdentityAccessIssuePermissionResolver`를 배선하면 **포트 재사용으로 자동 적용**(FR-IS-05는 포트 계약만 의존, hard-block 아님). dev/test는 **deny stub 주입**으로 "권한 없는 이슈 → FAILED(FORBIDDEN)" 경로 검증(AlwaysAllow always-true 가짜그린 회피).
- **B2 워커 부팅 인프라**: issue-tracking에 부팅 진입점(`@SpringBootApplication` + `@EnableScheduling`)이 **부재**(현재 identity-access만 존재). 본 작업이 issue-tracking 부팅 진입점 + 스케줄링을 신설. 워커는 그 컨텍스트에서 구동. **이는 FR-IS-05 범위를 넘는 아키텍처 작업임을 명시.**
- **B3 작업레벨 동시성**: vt 만료 중 동시 2워커 처리 방지 위해 **작업레벨 CAS** — `UPDATE bulk_operations SET status='RUNNING' WHERE id=? AND status='PENDING'`로 단일 워커만 진입(0 row면 타 워커 처리 중 → skip). vt는 "최대 청크 처리시간 + 여유"로 산정(성능예산 기반). learnings advisory lock TOCTOU대로 lock/CAS 후 재조회.
- **C1 부분실패 창 제거 (NFR5 수정)**: 이슈 변경과 **해당 항목 상태 기록(SUCCEEDED/FAILED)을 동일 트랜잭션**으로 묶는다(같은 모듈·DB라 가능). 워커 크래시 시 이슈도 항목도 함께 롤백 → 재처리 시 PENDING이라 안전. 작업 카운트(processed/succeeded/failed)는 항목 상태 집계 재계산(멱등). → 기존 NFR5의 "이슈 변경 ≠ 항목 트랜잭션 분리"를 **폐기**.
- **C2 성능 예산**: 1,000건 처리 목표시간 명시(NFR 추가). 이슈별 cross-BC `WorkflowTransitionPort.plan()` 호출 횟수(최대 1,000회) 인지 → vt 산정 근거.
- **C4 완료 이벤트 큐 확정**: 별도 큐 `q_bulk_operation_events`(issue 이벤트 큐 `q_issue_events`와 분리, BulkOperation은 IssueDomainEvent 아님). 스키마: `{bulkOperationId, actorId, total, succeeded, failed, completedAt}`. **COMPLETED 전이는 1회만**(CAS RUNNING→COMPLETED) → 이벤트 1회 발행 보장.
- **C5 PAT/JWT**: 기존 이슈 API 인증 정책 그대로(JWT/PAT 모두 허용). 일괄도 동일 — 별도 제외 안 함(기존 이슈 단건 편집과 동일 권한면 일괄도 허용이 일관).
- **C3 TTL cleanup**: `@Scheduled` cleanup 컴포넌트로 완료 30일 경과 BulkOperation 삭제. 별도 task로 분해.
- **N1**: 워커(폴링/큐 I/O)와 processor(순수 처리)를 별 task로 분리 — 멱등·동시성을 타이밍 의존 없이 단위 검증.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 7건 처리.
- 수정 가능 5건 인라인 보강: BULK_EDIT merge patch 시맨틱, 접수검증 vs 항목실패 구분, failure_reason 구조화(reasonCode enum), 카운트 집계 멱등, RUNNING 전이 시점 + 멀티 프로젝트 혼합 허용.
- Maxi 결정 2건: 작업 취소 = **제외**(Jira 정합, 후속), 완료 알림 = **이벤트 발행만**(FR8, BulkOperationCompleted outbox).
