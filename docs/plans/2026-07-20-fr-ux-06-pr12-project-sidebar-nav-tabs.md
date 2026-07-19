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

## 스펙

전체 스펙. [docs/specs/2026-07-20-fr-ux-06-pr12-project-sidebar-nav-tabs.md](../specs/2026-07-20-fr-ux-06-pr12-project-sidebar-nav-tabs.md)

핵심 3 deliverable + 결정 요약.
- **① 사이드바 프로젝트 트리** — `<nav aria-label="프로젝트">`(메인 메뉴 위), `GET /api/v1/projects` 첫 소비.
  2단 그룹 아코디언(직접링크 보드·백로그·타임라인 + 리포트▸4 + 설정▸11, 요약 생략=라우트 부재). 프로젝트명
  클릭=보드 이동. 활성=$projectKey 자동펼침+aria-current. 밖=목록만·전부 접힘. 접힘레일=아이콘/이니셜.
- **② ProjectNavTabs 추출** — board:477·backlog:59 인라인 `프로젝트 뷰 전환` nav를 공유 컴포넌트로. nav+Link
  (Tabs 금지), 라벨 보존. 통합 링크 집합은 회귀 대조 후 plan에서 확정(GAP-2).
- **③ C3 랜드마크** — `__root` `<main>` 제거 → ShellLayout 양분기 + login.tsx 자체 main. banner/main/complementary
  각 1. login은 _shell 밖(rootRoute 직속)이라 자체 main 필수(실측 확인).

착수 전 필독. [[frontend-nav-aria-label-e2e-contract]] · [[playwright-getbyrole-exact-strict-mode]]
(신규 '프로젝트' 라벨이 '프로젝트 뷰 전환' substring → 테스트 full label+exact).

## Brainstorming Check

✅ 통과 (자가 gap 점검, Blocker 0).
- GAP-1 설정그룹 비관리자 노출 → 기본=전 인증자 표시(백엔드 fail-closed·신규유출0), 게이트1 Maxi 확인.
- GAP-2 ProjectNavTabs 링크 집합 → plan 이연(회귀 diff 대조).
- GAP-3 섹션 순서 미세차 → 비차단.

## Plan

> 전 태스크 agent=`frontend-engineer` (T6만 `qa-engineer`). 경로는 repo 루트 기준.
> TDD red→green→refactor 강제. 파일 겹침 자동 직렬화 대비 files 정확 기재.

### Task 1. 프로젝트 목록 데이터 접근 슬라이스 (API client + Zod + MSW + hook)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/projects.ts`, `apps/web/src/api/__tests__/projects.test.ts`,
  `apps/web/src/mocks/project-list-handlers.ts`, `apps/web/src/mocks/handlers.ts`,
  `apps/web/src/hooks/use-projects.ts`, `apps/web/src/hooks/__tests__/use-projects.test.tsx`]
- depends-on: []

**RED**.
- `api/__tests__/projects.test.ts` — Zod 스키마가 `{data:[{id,key,name}]}` 파싱, 여분 필드 무시,
  잘못된 형태(문자열 id 등) reject. MSW 핸들러 통해 `listProjects()` 호출 시 배열 반환.
- `hooks/__tests__/use-projects.test.tsx` — `useProjects()`가 name 오름차순 목록 반환(MSW fixture 3건),
  빈 응답(`{data:[]}`)=빈배열, 401/에러=쿼리 error 상태(throw 아님·fail-safe는 소비처가 처리).
- 실패 예상: `listProjects`/`projectListSchema`/`useProjects` 미존재.

**GREEN**.
- `api/projects.ts` — `projectSchema = z.object({id:z.string().uuid(), key:z.string(), name:z.string()})`,
  `projectListResponseSchema = z.object({data: z.array(projectSchema)})`, `listProjects(archived=false)` =
  apiFetch(`/api/v1/projects?archived=${archived}`) → parse. **DTO invent 금지**([[frontend-zod-backend-dto-contract-gap]]) — 3필드만.
- `mocks/project-list-handlers.ts` — `http.get('/api/v1/projects', ...)` bare list(하위리소스와 구분),
  fixture 3건(name 정렬). `mocks/handlers.ts`에 전역 등록([[msw-global-handler-registration-gap]]).
- `hooks/use-projects.ts` — react-query `useQuery(['projects', archived], () => listProjects(archived))`.

**REFACTOR**. fixture 상수 추출, KDoc.

**검증**. `cd apps/web && pnpm exec vitest run src/api/__tests__/projects.test.ts src/hooks/__tests__/use-projects.test.tsx`

---

### Task 2. ProjectTree 컴포넌트 (2단 그룹 아코디언) + projectNav 라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ProjectTree.tsx`,
  `apps/web/src/components/layout/__tests__/ProjectTree.test.tsx`, `apps/web/src/i18n/nav-labels.ts`]
- depends-on: [1]

**RED** (`ProjectTree.test.tsx`, memory router + QueryClient + MSW 마운트).
- `<nav aria-label="프로젝트">` 존재(**exact** 조회 — [[playwright-getbyrole-exact-strict-mode]] substring 함정).
- 프로젝트 3건 name순 렌더, 각 행에 디스클로저 버튼(`aria-expanded`) + 프로젝트명 링크(→`/projects/{key}/board`).
- 펼침 시 직접링크 3(보드·백로그·타임라인 실 경로) + `리포트` 그룹(velocity/cfd/cycle-time/worklog) + `프로젝트 설정`
  그룹(11 실 경로). **죽은링크 0**(요약 없음).
- 활성 프로젝트(useParams `$projectKey`=ATLAS일 때) 자동 펼침 + `aria-current="page"`. 프로젝트 밖=전부 접힘.
- 사이드바 `<h1>` 부재(트리 안 heading level 1 금지).
- 빈 목록=빈상태 문구, 에러=조용한 미표시(앱 차단 금지).

**GREEN**.
- `ProjectTree.tsx` — `useProjects()` + `useParams({strict:false})`. 2단 그룹 아코디언. 활성 자동펼침=
  파생 상태(현재 key), 수동 펼침=ephemeral useState. 접힘 레일(useSidebarCollapsed)=아이콘/이니셜 sr-only.
  리포트/설정 서브그룹 링크는 실 라우트 상수 배열(리포트 4·설정 11). 아이콘 `aria-hidden`.
- `nav-labels.ts` — `projectNav: '프로젝트'` 추가 + **S3 주석(:9) 갱신**(내작업·최근·필터에서 프로젝트 제거,
  "프로젝트는 PR12에서 추가됨" 명기). 리포트/설정 서브라벨은 ProjectTree 로컬 상수(nav-labels 최소 증분).

**REFACTOR**. 서브링크 상수/타입 추출, 그룹 컴포넌트 분리, KDoc(계약·substring 함정 명기).

**검증**. `pnpm exec vitest run src/components/layout/__tests__/ProjectTree.test.tsx`

---

### Task 3. Sidebar 배선 + navigation-contract 계약 확장

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/Sidebar.tsx`,
  `apps/web/src/components/layout/__tests__/Sidebar.test.tsx`,
  `apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`]
- depends-on: [2]

**RED**.
- `Sidebar.test.tsx` — `<ProjectTree/>`가 `메인 메뉴` nav **위**에 렌더(순서 어서션). 기존 메인/관리 nav 불변.
- `navigation-contract.test.tsx` — 신규 `프로젝트` nav 존재(**exact**) 어서션 추가. 기존 4계약(메인 메뉴·
  관리 메뉴·검색 단일·h1 부재) 무위반 유지. 검색 여전히 1개(사이드바 트리에 검색 없음).

**GREEN**. `Sidebar.tsx`에 `<ProjectTree/>` 삽입(메인 메뉴 nav 위). import 추가.

**REFACTOR**. 배치 KDoc(디자인 스펙 §3.1 섹션 순서 인용).

**검증**. `pnpm exec vitest run src/components/layout/__tests__/`

---

### Task 4. ProjectNavTabs 공유 컴포넌트 추출 (회귀-무해)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/project/ProjectNavTabs.tsx`,
  `apps/web/src/components/project/__tests__/ProjectNavTabs.test.tsx`,
  `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/projects.$projectKey.backlog.tsx`]
- depends-on: []

**RED** (`ProjectNavTabs.test.tsx`).
- `<nav aria-label="프로젝트 뷰 전환">`(라벨 보존) + `props.links` 순서대로 `<Link>` 렌더(Radix Tabs 금지=
  `role="tablist"` 부재 어서션). nav+Link만.

**GREEN**.
- `ProjectNavTabs.tsx` — `ProjectNavTabs({ projectKey, links })`, `links: {to, params, label}[]`. `<nav
  aria-label="프로젝트 뷰 전환">` + map Link. **링크 집합 통합 안 함** — 각 페이지가 기존 링크 verbatim 전달
  (회귀-무해, GAP-2 결정): board={백로그,타임라인}, backlog={보드,타임라인,벨로시티,CFD,사이클타임}.
- `board.tsx:477`·`backlog.tsx:59` 인라인 nav를 `<ProjectNavTabs .../>`로 대체(링크 집합 동일 유지).

**REFACTOR**. links 타입 export, KDoc(nav+Link 규칙·Tabs 금지 이유).

**검증**. **회귀 대조 필수** — `git show HEAD:apps/web/src/routes/projects.$projectKey.board.tsx`로 기존 링크
집합 확인 후 동일 보장. `pnpm exec vitest run src/components/project src/routes/__tests__/projects.board.test.tsx
src/routes/projects.$projectKey.backlog.test.tsx` (뷰전환 5+5 무수정 green).

---

### Task 5. C3 랜드마크 복원 (__root/ShellLayout/login main 재배치)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/__root.tsx`, `apps/web/src/components/layout/ShellLayout.tsx`,
  `apps/web/src/routes/login.tsx`, `apps/web/src/routes/__root.test.tsx`,
  `apps/web/src/components/layout/__tests__/ShellLayout.test.tsx`, `apps/web/src/routes/login.test.tsx`]
- depends-on: []

**RED**.
- `ShellLayout.test.tsx` — auth 분기: `banner`(header) 1 · `main` 1 · `complementary`(aside) 1, header/aside가
  main **밖**(landmark 중첩 아님). unauth 분기: `main` 1(bare Outlet 아님).
- `login.test.tsx` — LoginPage에 `main` 1 존재(미인증 D-D).
- `__root.test.tsx` — RootLayout이 더 이상 `<main>` 직접 렌더 안 함(이중 main 방지). 기존 오버레이/훅 불변.

**GREEN**.
- `__root.tsx` — `<main>` 제거, `<div>{overlays}<Outlet/></div>`.
- `ShellLayout.tsx` — auth: 콘텐츠 div(`min-w-0 flex-1 overflow-y-auto`)를 `<main>`으로 승격. unauth:
  `return <main><Outlet/></main>`. KDoc의 "main은 RootLayout 소유" 서술 갱신.
- `login.tsx` — LoginPage 최상위를 `<main>`으로 감쌈.

**REFACTOR**. 랜드마크 소유 KDoc(banner/main/complementary 맵), login이 _shell 밖이라 자체 main 필요 명기.

**검증**. `pnpm exec vitest run src/routes/__root.test.tsx src/components/layout/__tests__/ShellLayout.test.tsx
src/routes/login.test.tsx` + `navigation-contract.test.tsx` green(랜드마크 변경이 nav 계약 무영향 확인).

---

### Task 6. E2E — 프로젝트 트리 + 랜드마크

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-tree.spec.ts`, `apps/web/e2e/landmark.spec.ts`]
- depends-on: [3, 5]

**RED→GREEN** (Playwright, MSW e2e 시나리오).
- `project-tree.spec.ts` — 로그인 후 사이드바 `프로젝트` nav(**exact**) 표시, 프로젝트 클릭→보드 이동, 디스클로저
  펼침→서브링크, 활성 프로젝트 자동펼침+aria-current. 죽은링크 0.
- `landmark.spec.ts` — 인증 화면 `banner`·`main`·`complementary` 각 1, 로그인 화면 `main` 1.
- **전수 e2e 회귀 대조**([[e2e-playwright-filter-arg-drop]] — 바이너리 직접호출·개수 판정): baseline 530 유지+신규.

**검증**. `apps/web/node_modules/.bin/playwright test project-tree landmark` + 전수 `pnpm test:e2e` 530+신규.

---

## Plan 메타

- task 수: 6
- 예상 wave: W1=[T1, T4, T5] (depends-on []·파일 무겹침) → W2=[T2] → W3=[T3] → W4=[T6]. 약 4 wave.
- TDD 강제: yes (red→green→refactor)
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산. **공유 편집점**: nav-labels.ts(T2만),
  Sidebar.tsx(T3만), ShellLayout.tsx(T5만), navigation-contract.test.tsx(T3·T5 둘 다 → 직렬 필요:
  T5는 green 확인만·T3이 프로젝트 nav 추가. **T3·T5 navigation-contract.test 편집 충돌** → T5 files에서
  navigation-contract.test 제외하고 검증만, 편집은 T3 단독). board/backlog(T4만).
- 추가 검증: typecheck(tsconfig.app [[ci-typecheck-tsconfig-app-vs-local]]), lint, vitest 전수(≥7391),
  playwright 전수(530+신규). FR 129 불변 → verify-master-plan 통과.

## 리뷰 결과 (← /bts-review-plan 채움)
