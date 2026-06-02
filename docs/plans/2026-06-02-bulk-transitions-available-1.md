# FR-IS-05 후속 — 일괄 전이 가용 전이 교집합 서버 계산 엔드포인트 (Jira 방식)

> slug: bulk-transitions-available-1
> type: api
> agent: backend-engineer
> 생성: 2026-06-02

## Brief

일괄 전이 가용 전이 교집합을 서버가 한 번에 계산하는 백엔드 엔드포인트 신설(Jira 방식,
"서버가 정답지" 원칙 — FR-PM-02 선례). 현재 프론트 BulkTransitionDialog가 선택 이슈마다
fetchIssueTransitions를 최대 1000건 동시 fan-out + 클라이언트 intersectTransitions로 교집합
계산 → 서버가 한 방에 계산하도록 변경.

- 신규 엔드포인트: POST /api/v1/issues/bulk-transitions/available { issueKeys[] } → 공통 가용 전이 transitions[]
- 워크플로우 FSM 기반 교집합(가용 전이 조회는 기존 GET /issues/:key/transitions와 동일 시맨틱, 권한은 실행 시점 best-effort)
- 프론트: 1회 호출로 변경(fan-out + client intersectTransitions 제거, 순수함수 단위테스트 보존 검토)
- glossary "일괄 작업(Bulk Operation)/일괄 작업 항목" 용어 추가(도메인 단계, Maxi 사전 승인됨)
- classify: type=api, agent=backend-engineer, primary_bc=project-workflow(검증 필요 — /issues 경로라 issue-tracking 추정)

## 도메인 정리

- **BC 확정: issue-tracking** (classify의 project-workflow는 키워드 오판). 근거 — bulk 작업은 `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/bulk/web/BulkOperationController.kt`, 가용 전이 조회는 `IssueController.availableTransitions` + `IssueApplicationService.availableTransitions(actor, issueKey)`. 둘 다 issue-tracking.
- **영향 엔티티**: 신규 0. 기존 `IssueApplicationService.availableTransitions` 재사용 + shared-kernel `AvailableTransitionView`(`com.bts.shared.workflow.AvailableTransitionsResult.kt`), DTO `TransitionItem(fromStateKey,toStateKey,name,key)` 재사용.
- **신규 엔드포인트**: `POST /api/v1/issues/bulk-transitions/available` (BulkOperationController에 추가) → 서버가 toStateKey 기준 교집합 계산.
- **새 용어**: glossary "일괄 작업(Bulk Operation)" + "일괄 작업 항목(Bulk Operation Item)" 추가 완료(Maxi 사전 승인). 핵심 엔티티 섹션.
- **기존 결정**: 충돌 없음. "서버가 정답지" 원칙([[2026-06-02-issue-permission-query-api]]) 일관, 전이 동일성=(from,to)쌍([[2026-05-28-workflow-transition-identity-policy]]) 준수.
- **신규 ADR**: docs/adr/2026-06-02-bulk-available-transitions-server-side.md (생성됨) — 교집합 서버 이전 결정 + 대안(자체 limiter/p-limit) 기각 사유.
- **BC 격리**: 가용 전이 조회는 이미 issue-tracking이 shared-kernel AvailableTransitionView로 workflow 결과를 받는 기존 경계 사용 → 새 cross-BC 호출 0.
- **grill-with-docs 스킵 사유**: 기존 서비스/DTO 재사용 + 백엔드 구조 직접 grep 검증으로 BC·재사용 지점 확정. 무거운 대화형 grill 불필요(메모리 bts-spec-office-hours-mismatch).

## 스펙

### 목표 (DoD)

서버가 일괄 전이의 공통 가용 전이를 한 번에 계산하는 엔드포인트를 신설하고, 프론트 BulkTransitionDialog를 fan-out(최대 1000건 동시 요청) 대신 **1회 호출**로 전환한다. 교집합 계산의 단일 출처를 서버로 이전한다.

### API 계약 (신규)

- `POST /api/v1/issues/bulk-transitions/available`
- Request body: `{ "issueKeys": ["ATLAS-1", "ATLAS-3"] }` — @Valid, 1~1000건(기존 bulk-update와 동일 상한).
- Response 200: `{ "data": { "transitions": TransitionItem[], "unresolvedIssueKeys": string[] } }`
  - `TransitionItem` = `{ fromStateKey, toStateKey, name, key }` — **기존 `AvailableTransitionsResponse.TransitionItem` DTO 재사용**.
  - `transitions` = toStateKey 기준 교집합. 해석 성공한 **첫 이슈**의 항목(name/key) 보존(프론트 `intersectTransitions`와 동일 시맨틱). 한 이슈라도 가용 전이가 비면 교집합은 빈 배열.
  - `unresolvedIssueKeys` = not-found(소프트삭제 포함) 또는 워크플로우 미설정으로 해석 실패한 issueKey 목록(best-effort).
- 검증 실패: issueKeys 빈 배열 / 1000 초과 → 400 `ISSUE_BULK_VALIDATION_FAILED`(기존 bulk-update 정책 일치).

### 백엔드 설계 (issue-tracking BC)

- `BulkOperationController`에 `availableBulkTransitions` 핸들러 추가(`@PostMapping("/api/v1/issues/bulk-transitions/available")`). actor = `ActorId(SYSTEM_ACTOR_UUID)`(기존 컨트롤러 패턴; 실사용자 actor는 FR-PM-02 후속 범위).
- application(또는 bulk service)에 배치 메서드 신설:
  - 각 issueKey에 `availableTransitions(actor, key)` 호출을 **try/catch로 감싸** `IssueNotFoundException`/`IssueWorkflowNotConfiguredException`은 unresolved로 수집(best-effort, 기존 프론트 `Promise.allSettled`와 동형).
  - 해석 성공 목록으로 **교집합 순수 함수** 호출(toStateKey 기준, 첫 이슈 항목 보존). 순수 함수로 분리해 단위 테스트.
- 응답 DTO: `BulkAvailableTransitionsResponse(transitions: List<TransitionItem>, unresolvedIssueKeys: List<String>)` 신규(TransitionItem 재사용).

### 프론트 변경 (issue-tracking view layer, 같은 BC — learning 2026-05-22 옵션 C)

- `api/issues.ts`(또는 bulk-operations.ts)에 `fetchBulkAvailableTransitions(issueKeys): Promise<{transitions, unresolvedIssueKeys}>` + Zod 스키마 추가(응답 `{data:T}` 래퍼).
- `BulkTransitionDialog.tsx`: `Promise.allSettled(map(fetchIssueTransitions))` + `intersectTransitions` 제거 → 단일 `fetchBulkAvailableTransitions(issueKeys)` 호출. `unresolvedIssueKeys.length>0` → 기존 `hasPartialFailure` 경고, `transitions=[] && unresolved=전체` → `hasTotalFailure` 에러, `transitions=[] && unresolved=0` → 기존 "공통 이동 상태 없음" 안내. 적용 시 issueKeys 전체 전송(기존 동작 유지).
- `lib/transition-intersection.ts` 순수 함수: 더 이상 프론트에서 호출 안 함 → **제거(dead code 방지)** + 해당 단위테스트 제거. 교집합 정본은 서버.
- MSW: `bulk-transitions/available` 핸들러 신규(기존 issue-handlers의 `getAvailableTransitions`/softwareDefaultFixture 재사용해 서버 교집합 mock). 응답에 unresolvedIssueKeys 포함.
- 기존 E2E `issue-bulk-operations.spec.ts` S2(전이 happy)/S5(교집합 0)는 단일 호출 기반으로 여전히 통과해야 함 → MSW 새 핸들러로 충족(회귀 0).

### 엣지 케이스

- 단일 이슈 선택 → 그 이슈 가용 전이 전체 반환(교집합=자기 자신).
- 없는 키/소프트삭제/워크플로우 미설정 → unresolvedIssueKeys.
- 전부 unresolved → transitions=[] + unresolved=전체 → 프론트 hasTotalFailure.
- 일부 unresolved + 나머지 교집합 존재 → transitions + 경고(부분 실패).

### NFR

- 브라우저 동시 요청 N(최대 1000) → **1**. 서버는 단일 요청 내 issueKey 순회 처리(현 규모 충분, 향후 batch 워크플로우 조회 최적화 여지는 별도).
- 기존 bulk E2E(S2/S5) + 단위 회귀 0. 백엔드 ktlint/detekt 그린.

## Brainstorming Check

- **계약 재사용 우선** — TransitionItem DTO·availableTransitions 서비스·intersect 시맨틱 모두 기존 코드 기반. 새 계약 최소(요청 {issueKeys}, 응답에 unresolvedIssueKeys 1필드 추가).
- **부분 실패 UX 보존** — 서버가 unresolvedIssueKeys를 돌려줘 기존 "일부/전량 조회 실패" 경고를 그대로 유지(프론트 동작 회귀 0). 적대적 점검: 서버가 unresolved를 안 주면 프론트가 부분 실패를 구분 못 해 UX 퇴행 → 응답 필수 필드로 못박음.
- **dead code 차단** — intersectTransitions 프론트 제거가 핵심(서버 이전의 본질). 안 지우면 두 곳에 교집합 로직 공존 → drift. 제거 + 서버 단위테스트가 교집합 정본.
- **회귀 경계** — 기존 issue-bulk-operations E2E S2/S5가 새 단일 엔드포인트로 동작하도록 MSW 핸들러 교체. 기존 per-issue transitions 핸들러(상세 화면 전이용)는 유지.
- **PR 범위** — 백엔드(엔드포인트) + 프론트(same BC view layer 소비) 한 PR. 리뷰가 크다고 판단하면 백엔드/프론트 분리 가능(단 프론트가 백엔드 머지 의존 → 순서 비용). 기본 단일 PR.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
