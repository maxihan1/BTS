# FR-UX-06 Phase 3 PR12 — 프로젝트 사이드바 확장 + ProjectNavTabs + C3 랜드마크 복원

> slug: fr-ux-06-pr12-project-sidebar-nav-tabs
> type: ui
> agent: frontend-engineer
> primary_bc: personalization (물리 identity-access)
> 생성: 2026-07-20

## Brief

FR-UX-06 Jira 재개편 Phase 3(Shell)의 PR12. PR11에서 전역 사이드바 뼈대
(ShellLayout·TopBar·Sidebar·AccountMenu)가 이미 렌더된다. PR12는 세 가지를 얹는다.

1. **프로젝트 트리** (디자인 스펙 §3.1 섹션2) — 사이드바에 프로젝트 목록 표시.
   `GET /api/v1/projects` 소비 (PR11에서 존재·fail-closed 확인됨, #277 PR-3 ProjectQueryController).
2. **ProjectNavTabs (프로젝트 뷰 전환)** — 보드/백로그/타임라인 등 뷰 전환 통합.
   🔴 **Radix Tabs 금지** — `role="navigation"` 소멸 시 e2e 5 + 유닛 5 즉사.
   정답 = `<nav aria-label="프로젝트 뷰 전환">` + `<Link>` 를 탭처럼 스타일링 (라우트 이동).
3. **C3 랜드마크 복원** — PR11 게이트2 이연분. `<main>` 을 `__root` 에서 ShellLayout으로
   이관해 `banner` role 회복.

순수 프론트엔드 (React 19 / TS strict). 백엔드/Kotlin 미변경. GET /api/v1/projects는 기존 API 소비만.
FR 총수 불변 129 (마킹은 #277 PR-5 몫).

**분류 정정 기록**: classifier가 backend/backend-engineer로 오분류 → ui/frontend-engineer로 실측 정정
(PR9 route→api·PR10 router→api 동일 반복 오분류, gstack-diff-scope-blind-to-kotlin 계열).

**착수 전 필독 계약**:
- `frontend-nav-aria-label-e2e-contract` — aria-label 4종 e2e 계약, 검색 Header 단일,
  관리메뉴 기본펼침, **뷰전환 Tabs 금지**. 규칙: 라우트 바뀌면 nav+Link, 같은 라우트 패널만 바뀌면 Tabs.
  ⚠️ 이 메모리는 PR11 전 관측 — Header.tsx 삭제됐으니 라벨 소유 위치는 Sidebar 계열로 실측 재확인 필요.
- `fr-ux-06-jira-redesign-plan` (허브), `fr-ux-06-pr11-sidebar-done` (PR11 세부·교훈).
- `no-project-list-api-blocks-sidebar` (GET /api/v1/projects 존재로 갱신됨).

**검증 baseline (PR11 시점)**: 유닛 7391, e2e 전수 530 passed, typecheck 0, lint 0.

## 도메인 정리

> 방법: 대화형 grill-with-docs 대신 **직접 코드 실측**으로 그라운딩(UI PR·단순 도메인·신규용어 0·
> ADR 충돌 0, 진짜 리스크는 stale 전제였고 아래 실측으로 해소).

- **BC**: personalization (논리, 물리 구현은 UI shell = apps/web). 소비 API는 issue-tracking BC 소유.
- **신규 용어**: 없음. `프로젝트`(Project — 이슈 컨테이너, key=영문대문자+숫자)는 glossary 기존 등재.
- **기존 결정 충돌**: 없음. 이 PR은 ADR `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md`(D1~D8)의
  Phase 3 Shell을 구현. FR 총수 불변 129.

### 실측 확정 계약 (2026-07-20, worktree 기준)

1. **프로젝트 목록 API** — `GET /api/v1/projects?archived=false`
   (`backend/modules/issue-tracking/.../ProjectQueryController.kt`, #277 PR-3).
   - 응답: `{ "data": [ { id: UUID, key: string, name: string } ] }` — **3필드만**(YAGNI 확정).
     래퍼 = `DataResponse` = `{ data: ... }`.
   - 정렬: **name 오름차순**. 권한: `isAuthenticated()` + fail-closed(멤버십 없으면 빈 배열, 404 아님).
   - 단건: `GET /api/v1/projects/{idOrKey}` (BROWSE 게이트, 미존재 404·권한없음 403).
   - **프론트 소비처 0** (grep 실측 — 하위리소스 `/projects/:key/...`만 존재). PR12가 **첫 소비자** →
     API client + Zod 스키마 + MSW 핸들러 신설 필요. 기존 MSW project 파일(member·permission·lead)에
     bare list 핸들러 **없음**.

2. **aria-label 계약 4종** — `apps/web/src/i18n/nav-labels.ts`에 **중앙화**(리터럴 grep 실패 이유).
   글자 변경 금지(🔒 e2e 계약).
   | 상수 | 값 | 현재 소유(PR11 후 실측) |
   |---|---|---|
   | `navLabels.mainNav` | `메인 메뉴` | `Sidebar.tsx:87` |
   | `navLabels.adminNav` | `관리 메뉴` | `Sidebar.tsx:98` (isSystemAdmin 게이팅·기본 펼침) |
   | `navLabels.projectViewNav` | `프로젝트 뷰 전환` | `board.tsx:477` · `backlog.tsx:59` (PR11 미접촉) |
   | `navLabels.search` | `검색` | `TopBar.tsx` (Header 삭제로 단일 소유) |
   - 회귀 가드 `navigation-contract.test.tsx` — routeTree 전체 마운트, 4라벨+h1부재 어서션.
     migration-agnostic(KDoc은 stale Header:91 인용하나 어서션은 routeTree라 Sidebar로도 green).
   - 🔴 **뷰 전환은 Radix Tabs 금지** — `role=navigation` 소멸 시 e2e 5+유닛 5 즉사. 정답 = nav+Link.

3. **C3 랜드마크 버그 실체** — `__root.tsx:25`의 `<main>`이 `_shell`(ShellLayout) **전체**를 감싼다.
   렌더 트리: `__root <main>` → `_shell <div><TopBar(header)/><Sidebar(aside)/><Outlet/></div>`.
   `<header>`·`<aside>`가 `<main>` **안**이라 TopBar가 `banner` role을 못 얻음(landmark 오염).
   - **수정**: `<main>`을 `__root`에서 제거 → ShellLayout의 콘텐츠 div(`min-w-0 flex-1 overflow-y-auto`)를
     `<main>`으로 승격. 결과: TopBar `<header>`=banner·Sidebar `<aside>`=complementary·`<main>`=콘텐츠.
   - ⚠️ **미인증 분기 고려 필요**: ShellLayout `!isAuthenticated`는 bare `<Outlet/>`. `__root`에서 main을
     빼면 login·공개 라우트에 main 랜드마크가 사라짐 → 미인증 경로의 main 처리를 spec에서 결정해야 함.

4. **S3 규칙(nav-labels.ts:9-10, 2026-07-20 Maxi 확정)** — "백킹 없는 항목(내작업·최근·필터·**프로젝트**)
   미포함, 각 항목은 해당 기능 FR에서 추가". PR11이 프로젝트 제외 → **PR12가 백킹(API) 확인된 지금 추가**
   (S3의 예정된 확장). PR12 착수 시 이 주석 갱신 대상.

### 디자인 스펙 §3.1 사이드바 섹션 (정본)
| 섹션 | 항목 | PR11 상태 |
|---|---|---|
| 1 | 내 작업·최근·즐겨찾기 | 즐겨찾기만 구현(FavoritesMenu), 내작업·최근 미구현(백킹없음) |
| 2 | **프로젝트 트리** (확장 시 요약/보드/백로그/타임라인/리포트/설정) | **미구현 ← PR12 대상** |
| 3 | 이슈·대시보드·캘린더·필터 | 이슈·대시보드·캘린더 구현, 필터 미구현 |
| 4 | 관리 (6링크) | 구현(isSystemAdmin·기본펼침) |

### ✅ Maxi 결정 (2026-07-20, 도메인 게이트)
- **D-A → 확장형 전체 트리**. 프로젝트 목록 + 각 프로젝트 확장 시 서브링크(6종은 아래 D-E 라우트 매핑 참조).
- **D-B → 공유 ProjectNavTabs 컴포넌트로 추출**. board.tsx:477·backlog.tsx:59 인라인 nav를 공유
  컴포넌트로 통합(nav+Link 유지·Tabs 금지·`프로젝트 뷰 전환` 라벨 보존). 타 뷰(타임라인/리포트)도 재사용.
- **D-D → 미인증도 `<main>` 보장**. ShellLayout `!isAuthenticated` 분기도 `<main><Outlet/></main>`으로 감쌈.
- **D-C (미확답, spec에서 확정)** — 활성 프로젝트는 route param `$projectKey`에서 판별. 프로젝트 컨텍스트
  밖(/dashboards 등) 트리 표시/확장 정책은 spec에서.

### 🔴 D-E. 프로젝트 트리 서브링크 ↔ 실 라우트 매핑 (spec 핵심 결정)
디자인 스펙 6서브링크 중 3개가 백킹 라우트 부재 → S3(죽은 링크 금지)와 충돌.

| 디자인 서브링크 | 실 라우트(실측) | 상태 |
|---|---|---|
| 보드 | `/projects/$projectKey/board` | ✅ |
| 백로그 | `/projects/$projectKey/backlog` | ✅ |
| 타임라인 | `/projects/$projectKey/timeline` | ✅ |
| 리포트 | `/reports/velocity`·`/reports/cfd`·`/reports/cycle-time`·`/reports/worklog` (인덱스 라우트 없음) | ⚠️ |
| 프로젝트 설정 | `/settings/{workflow-scheme,members,components,versions,custom-fields,issue-templates,field-permissions,automation,slack-channels,project-lead,import}` 11종 (인덱스 없음) | ⚠️ |
| **요약** | **없음** (`/projects/$projectKey` index·`/summary` 부재) | 🔴 죽은 링크 |

- 기존 뷰전환 nav 실링크: board→{backlog,timeline}, backlog→{board,timeline,velocity,cfd,cycle-time}.
- spec 결정 필요: 요약 처리(생략 vs 요약페이지 신설=스코프증가) · 리포트/설정 처리(대표 라우트 링크 vs
  하위 펼침 vs 생략) · ProjectNavTabs가 담을 뷰 집합.

### 프로젝트 하위 라우트 인벤토리 (router.ts 실측)
- 뷰: board · backlog · timeline
- 리포트: reports/velocity · reports/cfd · reports/cycle-time · reports/worklog
- 스프린트: sprints/$sprintId/burndown
- 설정 11종: workflow-scheme · members · components · versions · custom-fields · issue-templates ·
  field-permissions · automation · slack-channels · project-lead · import
- **부재**: `/projects/$projectKey`(프로젝트 홈/요약) · `/reports` 인덱스 · `/settings` 인덱스

- 관련 ADR: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (D1~D8, 충돌 없음·이 PR이 구현)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
