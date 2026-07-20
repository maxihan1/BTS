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

## 도메인 정리

- **소비 BC**: issue-tracking(projects CRUD·archive) + project-workflow. **apps/web 단일 SPA** (한 PR=한 BC 규칙은 백엔드 대상, 프론트는 cross-BC 소비 정상).
- **영향 엔티티(읽기/소비만)**: Project(key=영문대문자+숫자·name·archivedAt), ProjectMembership(생성 시 생성자 자동 admin), GlobalPermissionGrant(CREATE_PROJECT).
- **새 용어**: 없음. 프로젝트(Project)·프로젝트 행정 권한(Project Admin)·아카이브(archivedAt) 모두 glossary 기존 확립. "프로젝트 아카이브"는 glossary 명시 항목 부재 → 후보로 기록하되 Maxi 승인 영역(수동).
- **기존 결정 충돌**: 없음. 소비 ADR 4종 모두 백엔드 결정, 프론트는 계약 준수:
  - `2026-07-17-global-permission-grants.md` — FR-PM-10 · CREATE_PROJECT 판정(`hasGlobalPermission`, SYSTEM_ADMIN 포함)
  - `2026-07-18-auto-assign-system-actor-permission-bypass.md` — PR-2 생성 hot-fix
  - `2026-06-01-project-membership-model.md` · `2026-06-01-project-member-projectidorkey.md`
- **관련 ADR**: 없음 (순수 프론트 소비 — 신규 ADR 불요). 도메인 모델링은 마스터 스펙 `docs/specs/2026-07-17-project-management-crud.md` §2 완료.
- **domain 단계 right-size**: 완전히 스펙된 도메인 소비 PR이라 대화형 grill-with-docs 생략(신규 용어 0·ADR 0). FR-UX-06 프론트 PR 선례 동형.

## 스펙

전체 스펙: [docs/specs/2026-07-20-fr-pj-pr-5-project-crud-ui.md](../specs/2026-07-20-fr-pj-pr-5-project-crud-ui.md)

**Maxi 결정 4건**: ①디자인=기존 ADS v2 적용(shotgun 없음) ②생성=전용 라우트 `/projects/new` ③설정 danger zone(name+archive)·목록 보기전용 ④백엔드 노출 2건(`ProjectResponse.archived` + `whoami.canCreateProject`).

**산출물**: 백엔드 2(BE-1 archived/issue-tracking·BE-2 canCreateProject/identity-access) + 프론트 3화면(목록·생성·설정) + 사이드바 연동 + FR-PJ-01~04 완료마킹. **한 PR=한 BC deviation**(2 백엔드 BC+apps/web) — 게이트1 근거 명시.

## Brainstorming Check

✅ 통과 (자체 적대검토 1회). 갭 3건 해소 — G1(router.ts 수동등록·_shell 자식·구현디테일)·**G2(‌/projects 진입점=사이드바 "모든 프로젝트" 링크·게이트1 확인)**·**G3(아카이브 행→settings/details 네비·게이트1 확인)**. 상세는 스펙 §Brainstorming Check.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
