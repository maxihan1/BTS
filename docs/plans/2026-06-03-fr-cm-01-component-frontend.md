# FR-CM-01 컴포넌트 관리 프론트엔드 UI + E2E (D6/D7)

> slug: fr-cm-01-component-frontend
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-CM-01(프로젝트별 컴포넌트 CRUD + 컴포넌트 리드)의 백엔드 D1~D5는 PR #59에서 머지 완료. 이번 작업은 남은 **D6(프론트 UI — 컴포넌트 관리 페이지)** + **D7(E2E)**.

- plan 정본 항목: `docs/plan/product/issue-tracking.md` §3.1.1 FR-CM-01 D6/D7 (미체크)
- 백엔드 산출물(참조): PR #59 — `backend/modules/issue-tracking/.../component/*`, ADR `docs/adr/2026-06-02-component-model-and-permission-deferral.md`
- 백엔드 CRUD API(검증 대상): GET/POST `/api/projects/{idOrKey}/components`, PATCH `/api/.../components/{id}` (name/description), PATCH `.../components/{id}/lead`, DELETE `.../components/{id}` — 정확한 경로/계약은 spec 단계에서 백엔드 코드 grep로 확정

### 핵심 선행 learnings (이번 작업 적용)

- frontend-zod-backend-dto-contract-gap — Zod 스키마는 백엔드 DTO를 grep해 정합. invent 금지.
- e2e-msw-serviceworker-block — 새 API는 MSW 핸들러 추가가 정석, 분기순서 백엔드 일치.
- ui-pr-defer-e2e-regression-latent — UI PR(D6)은 기존 E2E 함께 실행. 텍스트 중복 버튼은 컨테이너/testid 한정.
- parallel-fr-overlapping-frontend-infra-collision — 컴포넌트 리드 선택은 기존 fetchUsers/useUsers 정본 재사용(중복 생성 금지).
- zod-v4-uuid-fixture-strictness — fixture UUID는 v4 형식.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
