# FR-AU-09 인증 API를 사용하는 로그인 폼 UI (D6 작업)

> slug: fr-au-09-login-form-ui-d6
> type: ui (classify-task 보정 — 원래 `api`로 분류됐으나 사용자 승인 후 `ui`로 변경)
> agent: frontend-engineer (designer 협업)
> primary_bc: identity-access
> 생성: 2026-05-21

## Brief

### 사용자 원문

`FR-AU-09 인증 API를 사용하는 로그인 폼 UI를 만들어줘 (D6 작업)`

### 배경 (Context)

직전 PR #8 (FR-AU-09 세션/토큰 관리)가 머지되면서 백엔드 인증 API가 완성된 상태. 이제 그 API를 실제로 호출하는 **첫 화면**을 만든다 — D6 = `docs/plans/2026-05-20-fr-au-09-securityfilterchain-local-provider-pr-6-7.md` 의 "D6 로그인 폼 UI" 잔여 작업. 직전 세션 체크포인트 `20260521-141610-pr8-fr-au-09-auth-system-shipped.md` §Remaining Work #3 에서 우선순위 3순위로 명시.

### 활용 백엔드 API (PR #8 완성품 — 변경 없음)

- `POST /api/v1/auth/login` — username + password → access_token + refresh_token
- `POST /api/v1/auth/refresh` — refresh_token → 새 access_token (rotation)
- `POST /api/v1/auth/logout` — 세션 종료

### 작업 분류 결과

- type: `ui`
- agent: `frontend-engineer`
- primary_bc: `identity-access`
- task_count: 0 (plan 단계에서 분해)

### classify-task 보정 메모

`classify-task.ts`가 입력의 "API" 키워드를 잡고 `type=api, agent=backend-engineer`로 분류. 사용자 확인 후 `ui / frontend-engineer`로 보정. 향후 유사 케이스 회귀 가드 후보 — UI/API 키워드 동시 등장 시 우선순위 규칙 검토 (learnings 후보).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
