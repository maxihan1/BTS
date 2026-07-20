# FR-UX-06 Phase 3 PR13 — PageLayout/PageHeader/Breadcrumb + /settings·/admin 인덱스

> slug: fr-ux-06-pr13-page-layout
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-20

## Brief

FR-UX-06 Jira 재개편 Phase 3(Shell)의 PR13. 전역 사이드바(PR11·PR12 완료) 위에 페이지 레벨 공통 레이아웃을 신설한다.

**본 작업**
- `PageLayout` / `PageHeader` / `Breadcrumb` 공통 레이아웃 컴포넌트 신설 (`components/layout/`).
- `/settings` · `/admin` 인덱스 페이지 추가 (현재 라우트 부재로 죽은링크였던 그룹 헤더에 실제 인덱스 제공).

**PR12 후속 2건 (Maxi 지시로 본 PR13에 포함)**
- ① 자체 `<main>`을 가진 3페이지의 landmark 강등(`<section>`/`<div>`): `issues.$key.tsx`, `admin.workflow-schemes.tsx`, `admin.workflow-schemes.$schemeKey.tsx`. 문서당 `<main>` 1개(WCAG 1.3.1). PR12에서 PRE_EXISTING로 분류된 중첩 main 해소.
- ② 프로젝트 설정 라우트별 authz 게이팅 확인. PR12에서 설정 그룹 11링크를 전 인증자에게 노출(GAP-1)한 정책이 관리 nav 정책과 어긋나는지 점검.

**classify 결과** — type=ui, agent=frontend-engineer, primary_bc=issue-tracking(물리, 실제는 personalization 논리). 순수 프론트(apps/web) 예상.

**착수 전 필독 (계약)**
- [[frontend-nav-aria-label-e2e-contract]] — aria-label 4종 e2e 계약·뷰전환 Tabs 금지·검색 Header 단일.
- [[playwright-getbyrole-exact-strict-mode]] — 신규 라벨 substring 함정, exact 필수.
- CI에 e2e 잡 없음 → UI PR 로컬 e2e 필수 ([[frontend-ci-10min-timeout-nonrequired]]).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
