# FR-PJ PR-5 — 프로젝트 생성·목록·설정·아카이브 UI

> slug: fr-pj-pr-5-project-crud-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-20

## Brief

**FR-PJ PR-5** — project-management-crud 마스터 스펙(`docs/specs/2026-07-17-project-management-crud.md`) §9.2의 6분할 중 5번째(**D6 프론트 UI**).

프로젝트 **생성·목록·설정·아카이브** 4개 UI 화면을 FR-UX-06 개편 산출물인 새 **ADS v2 디자인 시스템**(`components/layout/PageLayout·PageHeader·Breadcrumb` + `components/ui/` 프리미티브) 위에 구축한다. 순수 프론트(apps/web)·마이그레이션 0.

**백엔드 완비 (PR-1~4)**:
- `POST /api/v1/projects` — 생성 · `CREATE_PROJECT`
- `GET /api/v1/projects` — 목록 · 멤버십 필터 · 존재누설0 · `?archived`
- `GET /{idOrKey}` — 조회 · BROWSE
- `PATCH /{idOrKey}` — 설정 · PROJECT_ADMIN · name만 · **204 No Content(재조회 필요)**
- `POST /{idOrKey}/archive`·`/unarchive` — PROJECT_ADMIN · 멱등200 · 아카이브 설정변경 409

**포함**: FR-PJ-01~04 완료마킹(전수 동기화 8종 · verify-master-plan).
**착수 전 필독**: `[[frontend-nav-aria-label-e2e-contract]]` · `[[playwright-getbyrole-exact-strict-mode]]`. 사이드바 ProjectTree(#298) 연동 확인.

**분류 정정**: classifier가 design/designer 오판 → ui/frontend-engineer 실측 정정(`.bts-cache/classify.json` note 참조).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
