# FR-IS-05 D7 — 이슈 일괄 작업 E2E + bulk MSW 핸들러 정본

> slug: fr-is-05-d7-e2e-bulk-msw
> type: qa
> agent: qa-engineer
> 생성: 2026-06-02

## Brief

FR-IS-05 D7 — 이슈 일괄 작업(편집/전이) E2E 테스트 추가. 일괄 편집/전이 + 진행률 폴링
(PENDING→RUNNING→COMPLETED) 끝-to-끝 시나리오를 Playwright로 검증하고, mocks/에 bulk
작업 공용 MSW 핸들러 정본을 stateful하게 추가. 백엔드 D1~D5(PR #54/#56), 프론트
D6(PR #58) 이미 머지됨. 이번은 D7 E2E만.

- classify: type=qa, agent=qa-engineer, slug=fr-is-05-d7-e2e-bulk-msw

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: 신규 없음. 기존 BulkOperation / BulkOperationItem(D1~D5 백엔드 도메인, 이미 머지)을 E2E로 검증만.
- **새 용어**: 없음 (테스트 인프라 작업). 글로서리 "일괄 작업(Bulk Operation)/일괄 작업 항목" 추가는 별도 수동 영역 — Maxi 승인 대기(체크포인트 잔여 #3). 본 PR 범위 밖.
- **기존 결정 충돌**: 없음. 비동기 아키텍처 결정은 [docs/adr/2026-06-02-bulk-operation-async-architecture.md] 에 이미 확정. D7은 그 동작(접수 202 → 폴링 → 종단)을 E2E로 재현·검증.
- **관련 ADR**: docs/adr/2026-06-02-bulk-operation-async-architecture.md (기존, 신규 발행 없음)
- **grill-with-docs 스킵 사유**: D1~D6 머지로 도메인·계약 확정 상태. 새 개념 0건 → 무거운 대화형 grill 불필요(메모리 bts-spec-office-hours-mismatch). 직접 점검으로 충돌 0 확인.

## 스펙

### 목표 (Definition of Done)

D6에서 만든 일괄 작업 UI(액션바·편집/전이/결과 Dialog·폴링)가 끝-to-끝으로 동작함을 Playwright E2E로 검증하고, 산재한 인라인 핸들러 대신 **bulk 작업 공용 MSW 핸들러 정본**(`bulk-operation-handlers.ts`)을 stateful하게 추가한다. 백엔드 비동기 동작(접수 202 → 폴링 → 종단)을 충실히 재현한다.

### 검증 대상 계약 (D1~D6 머지, 변경 없음)

- `POST /api/v1/issues/bulk-update` → 202 `{ data: { bulkOperationId, status:'PENDING', totalCount } }`
- `GET /api/v1/bulk-operations/{id}` → 200 `{ data: BulkOperationResponse }` (status PENDING|RUNNING|COMPLETED|FAILED, processed/succeeded/failedCount, items[])
- 폴링 간격 1500ms(`POLL_INTERVAL_MS`), 종단(COMPLETED/FAILED) 도달 시 자동 중단, error 상태에서도 중단.
- 접수 에러는 ProblemDetail `detail`(한국어) → `toast.error`. raw errorCode 비노출.

### UI 셀렉터 정본 (D6 코드 확인)

- 현재 페이지 전체 선택 체크박스: `data-testid="select-all-page"`
- 개별 이슈 체크박스: `data-testid="select-${key}"` (예 `select-ATLAS-1`)
- 액션바: `data-testid="bulk-action-bar"`, 텍스트 `"{count}건 선택됨"`, 버튼 `일괄 편집`/`일괄 전이`/`선택 해제`
- 일괄 편집 Dialog: 제목 `일괄 편집`, select `aria-label="priority"`·`aria-label="impact"`(빈값=무변경), 버튼 `적용`(둘 다 무변경이면 비활성)/`취소`
- 일괄 전이 Dialog: 제목 `일괄 상태 전이`, Select `aria-label="전이 상태"`, 버튼 `적용`, 교집합 0건 안내 `선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다.`
- 결과 Dialog: 제목 `일괄 작업 결과`, 진행률 `role="status" aria-label="진행률 N/M"`, 상태라벨(`대기 중`/`처리 중`/`완료`/`실패`), 종단 카운트 `성공 N`/`실패 N`, `실패 목록` + issueKey + 한국어 사유, 버튼 `닫기`
- 상태/실패사유 라벨 정본: `src/i18n/bulk-operation-labels.ts`(statusLabels, failureReasonLabels 7종) — E2E는 하드코딩 대신 이 정본 import(PR #22 §F4 패턴)

### 사용자 시나리오 (E2E — `e2e/issue-bulk-operations.spec.ts`)

- **S1 일괄 편집 happy path**. alice 로그인 → `/issues` → `select-all-page`로 3건 선택 → 액션바 `3건 선택됨` 노출 → `일괄 편집` → priority 변경 → `적용` → `일괄 작업이 접수되었습니다.` 토스트 + 결과 Dialog 오픈 → 폴링으로 `처리 중`(진행률 증가) → `완료` 도달 + `성공 3`/`실패 0` → Dialog `닫기` → 액션바 사라짐(선택 해제) + 목록 refetch.
- **S2 일괄 전이 happy path**. ATLAS-1(open)+ATLAS-3(done) 선택(공통 목표상태 `closed`) → `일괄 전이` → `전이 상태` Select에 공통 전이(`Cancel`) 노출 → 선택 → `적용` → 결과 Dialog `완료` + `성공 2`.
- **S3 일괄 편집 부분 실패**. localStorage 플래그(`__bts_e2e_bulk_partial_fail`)로 마지막 선택 이슈 1건 FAILED 유도 → 3건 편집 적용 → 결과 Dialog `완료` + `성공 2`/`실패 1` + `실패 목록`에 해당 issueKey + 한국어 사유(`다른 요청이 먼저 수정함` = VERSION_CONFLICT).
- **S4 접수 실패 토스트**. localStorage 플래그(`__bts_e2e_bulk_reject`='validation'|'forbidden')로 POST 400/403(ProblemDetail) 유도 → `적용` → ProblemDetail `detail`(한국어) `toast.error` → 결과 Dialog 미오픈.
- **S5 공통 전이 없음**. 3건 전체(open/in_progress/done) 선택 → `일괄 전이` → 교집합 0건 → `선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다.` 노출 + `적용` 비활성.

### bulk MSW 핸들러 정본 설계 (`src/mocks/bulk-operation-handlers.ts`)

stateful 모듈 상태 + 폴링 진행 시뮬레이션(메모리 msw-mutation-stateful-refetch 적용).

- 모듈 상태: `Map<id, { operationType, payload, issueKeys, totalCount, pollCount, failKeys }>`. `resetBulkOperationState()` export(테스트 격리용, Playwright는 컨텍스트마다 새 페이지라 자동 초기화되지만 단위 테스트 격리에 사용).
- **POST `/api/v1/issues/bulk-update`**: (1) localStorage `__bts_e2e_bulk_reject` 플래그 검사 → 400 `ISSUE_BULK_VALIDATION_FAILED`(detail "선택한 이슈가 없거나 너무 많습니다.") / 403 `ISSUE_BULK_FORBIDDEN`(detail "일괄 작업 권한이 없습니다.") ProblemDetail. (2) `issueKeys` 길이 1~1000 검증 위반 → 400. (3) 정상 → op 생성(유효 v4 UUID — 메모리 zod-v4-uuid-fixture-strictness, 3그룹 `4`로 시작·4그룹 `8~b`), `__bts_e2e_bulk_partial_fail` 플래그면 마지막 key를 failKeys에 → 202 `{ data: { bulkOperationId, status:'PENDING', totalCount } }`.
- **GET `/api/v1/bulk-operations/:id`**: 없으면 404 `ISSUE_BULK_NOT_FOUND`. 있으면 `pollCount++`, `processed=min(pollCount, total)`, 항목 1건/폴 진행. `status = processed>=total ? COMPLETED : RUNNING`. items: 처리된 key는 failKeys면 FAILED(failureReasonCode VERSION_CONFLICT)·아니면 SUCCEEDED, 미처리 key는 PENDING. succeeded/failed/processedCount 집계 → `{ data: BulkOperationResponse }`.
- `handlers.ts`에 `...bulkOperationHandlers` 등록(알파벳 BC 그룹 정렬 — issue 그룹 인접).
- **분기 순서는 백엔드와 일치**(메모리 e2e-msw-serviceworker-block 후속 원칙).

### 엣지 케이스

- 폴링 종단 후 추가 GET 없음(refetchInterval false) — pollCount 정지로 자연 검증(별도 단언은 flaky, 생략).
- `처리 중` 중간 상태 단언은 폴 간격(1.5s) 의존 → best-effort(진행률 텍스트 존재 확인), 핵심 단언은 `완료`+카운트.
- 결과 Dialog 닫을 때 `bulkOperationId=null` 리셋(D6 구현) — 재오픈 잔상 없음(S1에서 닫기 후 액션바 사라짐으로 간접 검증).

### NFR

- 기존 E2E 회귀 0(메모리 ui-pr-defer-e2e-regression-latent — 같은 `/issues` 화면에 액션바/체크박스 추가가 기존 셀렉터를 깨지 않는지 전체 E2E 실행으로 확인).
- E2E 부팅은 MSW serviceWorker 사용(`serviceWorkers:'block'` 금지 — 메모리 e2e-msw-serviceworker-block).
- 신규 MSW 핸들러 단위 테스트 동반(`__tests__/bulk-operation-handlers.test.ts`), `tsc`(typecheck) 동반(메모리 zod-schema-strengthen-inline-mock-fanout — vitest는 타입 미검증).

## Brainstorming Check

- **계약 invent 위험 없음** — 모든 엔드포인트/스키마는 D1~D6 머지 코드(`api/bulk-operations.ts`, `use-bulk-operation.ts`)에서 직접 확인(메모리 frontend-zod-backend-dto-contract-gap 방지). 새 계약 0건.
- **fixture 정합** — alice userId/whoami는 기존 `loginAsAlice` 헬퍼 재사용(메모리 e2e-fixture-whoami-userid-alignment). bulk 핸들러는 사용자 식별 무관(작업 큐).
- **셀렉터 strict mode** — 같은 화면 `적용`/`닫기` 등 중복 텍스트는 Dialog 컨테이너(`getByRole('dialog')`) 한정 또는 제목 기준 scoping(메모리 playwright-getbyrole-exact-strict-mode).
- **scenario 토글** — 같은 alice로 partial-fail/reject 분기는 localStorage 플래그 + addInitScript(메모리 e2e-msw-scenario-toggle-localstorage-flag, PR #57 선례). worker.use·window.fetch monkeypatch 금지.
- **검증 충분성** — happy(편집/전이) + 부분실패 + 접수실패 + 교집합0 = 5 시나리오로 주요 분기 커버. 과설계 아님.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
