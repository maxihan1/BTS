# FR-UX-06 Phase 3 PR12 — 프로젝트 사이드바 확장 + ProjectNavTabs + C3 랜드마크 — 스펙

> slug: fr-ux-06-pr12-project-sidebar-nav-tabs · type: ui · agent: frontend-engineer
> FR 총수 불변 129 (신규 product FR 없음, PR-level 요구사항만). 아래 PR12-FR-x는 PR 내부 추적용.

## 개요 (3 deliverable)

1. **사이드바 프로젝트 트리** — `GET /api/v1/projects` 소비(첫 소비자). 2단 그룹 아코디언.
2. **ProjectNavTabs 공유 컴포넌트** — board/backlog 인라인 `프로젝트 뷰 전환` nav를 공유 컴포넌트로 추출.
3. **C3 랜드마크 복원** — `<main>`을 `__root`에서 걷어내 ShellLayout(+login)로 이관 → `banner` role 회복.

## Maxi 확정 결정 (도메인·스펙 게이트, 2026-07-20)
- D-A=확장형 전체 트리 · D-B=공유 ProjectNavTabs 추출 · D-C=항상 목록·현재만 자동펼침 ·
  D-D=미인증도 `<main>` 보장 · D-E=**2단 그룹 트리**(직접링크 3 + 중첩그룹 2, 요약 생략).

## 사용자 시나리오 (Given-When-Then)

- **S1 프로젝트 목록 표시** — Given 인증 사용자가 앱 진입, When 사이드바 렌더, Then 접근 가능한
  프로젝트가 name 오름차순 목록으로 표시된다(멤버십 없으면 빈 상태).
- **S2 프로젝트 기본뷰 진입** — Given 프로젝트 트리, When 프로젝트명 클릭, Then `/projects/{key}/board`로 이동.
- **S3 트리 펼침** — Given 프로젝트 행, When 디스클로저(▸) 클릭, Then 서브링크(보드·백로그·타임라인 +
  리포트 그룹 + 설정 그룹)가 펼쳐진다. 같은 프로젝트를 다시 클릭하면 접힌다.
- **S4 현재 프로젝트 자동 강조** — Given `/projects/ATLAS/board` 진입, When 사이드바 렌더, Then ATLAS가
  자동 펼침 + `aria-current` 강조되고 나머지 프로젝트는 접힘.
- **S5 프로젝트 밖 라우트** — Given `/dashboards` 진입($projectKey 없음), When 사이드바 렌더, Then 프로젝트
  목록은 여전히 표시되되 전부 접힘(자동 펼침 대상 없음).
- **S6 리포트/설정 그룹** — Given 펼쳐진 프로젝트, When 리포트(또는 설정) 그룹 디스클로저 클릭, Then 실재
  라우트 서브링크가 펼쳐진다(리포트 4·설정 11).
- **S7 뷰 전환(ProjectNavTabs)** — Given 프로젝트 보드/백로그/타임라인 페이지, When 뷰 전환 nav의 링크 클릭,
  Then 해당 뷰로 라우트 이동(뒤로가기 정상, `role=navigation` 유지).
- **S8 C3 랜드마크** — Given 인증 앱 화면, When 스크린리더 랜드마크 탐색, Then `banner`(상단바)·`main`(콘텐츠)·
  `complementary`(사이드바)가 각 1개씩. Given 로그인 화면(미인증), Then `main` 1개 존재.

## 기능 요구사항 (PR12-FR)

### 프로젝트 트리
- **FR1** 사이드바에 `<nav aria-label="프로젝트">` 신설(navLabels.projectNav='프로젝트'). 위치=`메인 메뉴`
  nav **위**(디자인 스펙 §3.1 섹션2 > 섹션3 순서). ⚠️ Playwright substring 함정([[playwright-getbyrole-exact-strict-mode]])
  — '프로젝트'는 '프로젝트 뷰 전환'의 substring. **테스트는 항상 full label + `exact:true`로 조회**, bare '프로젝트' 조회 금지.
- **FR2** 데이터=`GET /api/v1/projects?archived=false`(react-query). 응답 `{data:[{id,key,name}]}`, name 오름차순
  (백엔드 정렬 신뢰). 로딩=스켈레톤, 빈배열=빈상태 문구, 에러=조용한 빈상태(사이드바 앱 차단 금지·fail-safe).
- **FR3** 각 프로젝트 행 = 디스클로저 버튼(`aria-expanded`) + 프로젝트명 Link(→ `/projects/{key}/board`).
- **FR4** 2단 그룹(펼침 시): 직접 링크 3(보드·백로그·타임라인) + `리포트` 그룹(▸ velocity·cfd·cycle-time·worklog)
  + `프로젝트 설정` 그룹(▸ 11 설정 라우트). **요약 미포함**(라우트 부재·S3). 모든 링크는 실재 라우트만.
- **FR5** 활성 프로젝트=`useParams({strict:false}).projectKey`. 일치 프로젝트 자동 펼침 + `aria-current="page"`.
  프로젝트 밖 라우트=전부 접힘. 수동 펼침 상태는 ephemeral(비영속) — 라우트 이동 시 현재 기준 재계산.
- **FR6** 사이드바 접힘(64px 레일, PR11 FR5)=프로젝트 트리는 아이콘/이니셜만(sr-only 명칭), 그룹 펼침 비활성.

### ProjectNavTabs
- **FR7** `components/project/ProjectNavTabs.tsx` 신설 — `<nav aria-label="프로젝트 뷰 전환">` + `<Link>`
  (🔴 Radix Tabs 금지). board.tsx:477·backlog.tsx:59 인라인 nav를 이 컴포넌트로 대체. aria-label 계약 보존.
- **FR8** 통합 뷰 링크 집합(props로 현재뷰 전달, 현재뷰는 비활성/생략). 기존 각 페이지의 링크 인벤토리를
  보존/통합하되, e2e·유닛(뷰전환 5+5)이 green 유지되도록 링크 집합 확정은 plan에서(회귀 대조 필수).

### C3 랜드마크
- **FR9** `__root.tsx` `<main>` 제거. ShellLayout **양 분기**(auth: 콘텐츠 div→`<main>`, unauth: `<main><Outlet/></main>`)
  + `login.tsx` LoginPage 자체 `<main>` 추가. 결과: 페이지당 `<main>` 정확히 1개, TopBar `<header>`=banner,
  Sidebar `<aside>`=complementary. **이중 main 금지·main 부재 금지** 양쪽 회귀 가드.

## 비기능 요구사항 (NFR)
- **NFR1 a11y** — nav 랜드마크 accessible name 필수, 디스클로저 `aria-expanded`, 활성 `aria-current`,
  아이콘 `aria-hidden`. WCAG 2.1 AA.
- **NFR2 계약 보존** — aria-label 4종(메인 메뉴·관리 메뉴·프로젝트 뷰 전환·검색) 불변, 사이드바 `<h1>` 금지,
  검색 TopBar 단일. `navigation-contract.test.tsx` green 유지.
- **NFR3 회귀 baseline** — vitest ≥7391(+신규), e2e 전수 530 passed 유지(+신규 트리/랜드마크 e2e), typecheck 0·lint 0.
- **NFR4 완제품** — 죽은 링크 0(S3), 권한 없는 프로젝트는 API가 fail-closed로 미노출(프론트 추가 게이팅 없음·
  요약 API 부재 [[ui-permission-gating-needs-summary-api-exposure]]).

## API 인터페이스 (기존, 신규 백엔드 0)
- `GET /api/v1/projects?archived=false` → `200 {data:[{id:UUID,key:string,name:string}]}` name asc, fail-closed.
- 미인증 401. 신규 프론트 자산: API client + Zod 스키마 + MSW 핸들러(list, 기존 project 파일에 부재).

## 데이터 모델 변경
- 없음(백엔드 미변경). 프론트 상태: react-query 프로젝트 목록 캐시 + ephemeral 펼침 UI 상태.

## 엣지 케이스
- E1 빈 프로젝트 목록 → 빈 상태(앱 차단 금지). E2 API 에러 → 조용한 fail-safe(트리 미표시, 앱 정상).
- E3 활성 $projectKey가 접근 목록에 없음(권한밖) → 자동 강조 대상 없음, 페이지 자체 403.
- E4 프로젝트 다수 → 사이드바 `overflow-y-auto` 스크롤(기존). E5 사이드바 접힘 레일 트리 표시(FR6).
- E6 미인증 공개 라우트(dashboards.shared, _shell 하위) → ShellLayout unauth 분기 main. E7 login → 자체 main.
- E8 board/backlog 뷰전환 e2e(5+5)가 ProjectNavTabs 추출 후에도 동일 어서션 통과(회귀 대조).

## 제약 조건
- 🔴 Radix Tabs 금지(뷰전환·트리 모두 nav+Link). 라우트 이동=nav+Link 규칙([[frontend-nav-aria-label-e2e-contract]]).
- 🔴 aria-label 4종 글자 불변(nav-labels.ts). 신규 '프로젝트' 라벨은 substring 함정 회피(exact 조회).
- 🔴 사이드바 `<h1>` 금지. 검색 TopBar 단일.
- BC 격리 — 순수 프론트(apps/web). 백엔드 미변경. FR 총수 129 불변.
- S3 주석(nav-labels.ts:9) 갱신 — 프로젝트가 이제 포함되므로 목록에서 프로젝트 제거.

## 측정 가능한 완료 기준
- [ ] `GET /api/v1/projects` 소비 트리 렌더(S1~S6 e2e/유닛).
- [ ] ProjectNavTabs 추출, 뷰전환 5+5 green(회귀 대조).
- [ ] C3: `getByRole('banner')` 1·`getByRole('main')` 1(auth) + `getByRole('main')` 1(login).
- [ ] navigation-contract.test green, aria-label 4종 무위반.
- [ ] vitest ≥7391 green, e2e 전수 530+신규 green, typecheck 0·lint 0.

## Brainstorming Check (Phase B, 자가 gap 점검)

- **GAP-1 (게이트1 Maxi 확인)** — 설정 그룹 11링크의 비관리자 노출. **기본 결정**: 전 인증자 표시.
  근거 — 설정 라우트는 `requireAuth`로 이미 URL 직접 도달 가능·백엔드 fail-closed 가드, PR12가 신규
  유출 안 만듦([[ui-permission-gating-needs-summary-api-exposure]] — per-project-admin 프론트 신호/요약
  API 부재). 403-on-click은 설정 페이지 사전존재 특성. Maxi가 원하면 (b)설정그룹 숨김/(c)isSystemAdmin
  게이팅으로 축소 가능.
- **GAP-2 (plan 이연)** — ProjectNavTabs 통합 링크 집합 확정 + timeline/reports 채택. 회귀 대조
  필수(board.spec·backlog.spec·board.test·backlog.test 링크 인벤토리 `git show HEAD:` 대조).
- **GAP-3 (비차단)** — 사이드바 섹션 순서(즐겨찾기 vs 프로젝트)가 디자인 스펙과 미세 차이(PR11 레거시).
  계약 아님·시각 배치 문제. 프로젝트 트리는 메인 메뉴 nav 위 배치.

✅ 통과 — Blocker 0. GAP-1은 게이트1 노트, GAP-2는 plan 상세, GAP-3 비차단.
