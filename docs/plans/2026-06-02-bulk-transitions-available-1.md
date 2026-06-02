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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
