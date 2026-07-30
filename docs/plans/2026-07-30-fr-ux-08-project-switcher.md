# FR-UX-08 — 프로젝트 스위처 · 최근 항목 · 내 작업

> slug: fr-ux-08-project-switcher
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-30

## Brief

**사용자 원문.** FR-UX-08 프로젝트 스위처 + 사이드바 "내 작업"·"최근 항목" 구현 (F12 + F17).

**classify 결과.** `type=ui` · `agent=frontend-engineer` (원 출력의 `slug=fr-ux-08-ui-apps-web-react` · `primary_bc=issue-tracking` 은 아래대로 정정).

**정정 2건.**
1. **slug** — 정본 `docs/plan/product/personalization.md:213` 이 `**Plan slug**. fr-ux-08-project-switcher` 로 이미 지정. classify 자동 생성 slug 를 기각하고 정본을 따른다.
2. **primary_bc** — `issue-tracking` 이 아니라 **personalization**(논리 BC, 물리는 identity-access). 다만 본 작업은 `apps/web/**` 단일 SPA 전용이라 백엔드 모듈 경계와 무관하다.

**범위.** 프론트 전용. 신규 API 0 · 마이그레이션 0 · 백엔드 Kotlin 0줄 · **FR 카운트 불변 139**(FR-UX-08 은 `docs/plan/fr-index.md:186` 에 이미 등록됨 — 신설 아님).

**승계 PR 2건** (로드맵 정본 `~/.claude/plans/ui-ux-sorted-kay.md` §PR 체인 Tier 2).

- **F12 — 프로젝트 스위처 + 트리 펼침 영속.** §4.5(FR-UX-07, PR #320)가 "활성 프로젝트"라는 컨텍스트를 만들었지만 **그것을 손으로 바꿀 UI 가 없다.** 신규 `components/project/ProjectSwitcher.tsx` · `TopBar.tsx` · `ProjectTree.tsx:368-370` · 신규 `hooks/use-recent-projects.ts`.
- **F17 — 사이드바 "내 작업"(프로젝트 스코프) + "최근 항목".** `i18n/nav-labels.ts:9` 의 제외 주석을 해제하고 `Sidebar.tsx:47-51` 에 배선.

**착수 시점에 확정된 제약 3종.**

- 🛑 **스위처를 `<nav>` 로 만들면 안 된다.** `프로젝트` 가 기존 `aria-label="프로젝트 뷰 전환"` 의 substring 이라 `getByRole('navigation')` 계약(E2E 18건)과 충돌한다. `components/ui/popover.tsx`(현재 소비처 0) + `role="listbox"` 가 정답.
- 🛑 **cross-project "내 작업"은 범위 밖**(로드맵 B3). `IssueApplicationService.kt:1021` 이 `assertPermission(actor, BROWSE, IssueScope.Project(projectKey))` 로 프로젝트 스코프를 강제하고, `UserCalendarLookupAdapter.kt:25-38` 이 모든 visibility 술어가 `PROJECTS.KEY.eq(projectKey)` 단일 축으로 하드코딩됨을 명시한다. 합집합 조립은 fail-open 사고. v1 은 **활성 프로젝트 스코프**(`?assignee=me`).
- **localStorage 영속 템플릿.** `hooks/use-sidebar-collapsed.ts:15-45`(zustand + fail-safe 3중 폴백)를 §4.5 와 같은 방식으로 복제.

## 도메인 정리

- **BC.** 논리 personalization / 물리 `apps/web` (FR-UX-05 D4 · FR-UX-06 D5 · FR-UX-07 D2 선례 승계). 백엔드 변경 0.
- **영향 엔티티.** 없음 — 서버 도메인 모델 무변경. 클라이언트 상태 3종 신설(펼침 집합 · 최근 프로젝트 · 최근 이슈).
- **새 용어 2건.** **최근 프로젝트**(Recent Projects — MRU 상한 5, 스위처 정렬 축 전용) · **최근 본 이슈**(Recent Issues — MRU 상한 5, 키만 영속). glossary 등재 대기.
- **기존 결정 충돌 3건.** 전부 ADR 에서 명시 정정 —
  1. FR-UX-06 PR12 **FR5**(라우트 이동 시 수동 펼침 **덮어쓰기**) → **더하기만**으로 정정 (§D2)
  2. 정본 §4.6 `?assignee=me` → **`me` 센티널 부재**, `?assignee=<whoami.userId>` 로 표기 정정
  3. 정본 §4.6 D1 "최근 프로젝트" → 사이드바 노출 대상은 **최근 본 이슈**로 재배치 (§D1)
- **관련 ADR.** [docs/decisions/2026-07-30-fr-ux-08-project-switcher.md](../decisions/2026-07-30-fr-ux-08-project-switcher.md) (신규 · D1~D6)
- **선행 ADR.** [2026-07-28-fr-ux-07-active-project-context.md](../decisions/2026-07-28-fr-ux-07-active-project-context.md) (§D3 4단 해소 · §D6 분할 매핑)

### Maxi 확정 4건 (2026-07-30)

| # | 질문 | 확정 |
|---|---|---|
| 1 | 사이드바 "최근 항목"의 정체 | **최근 본 이슈** (최근 프로젝트는 스위처 내부 정렬 축으로만) |
| 2 | 트리 펼침 영속 ↔ 자동펼침 충돌 | **영속 우선**, 자동펼침은 **더하기만** (덮어쓰기 폐지) |
| 3 | "최근" 상한·기록 시점 | **상한 5 · 방문 시 자동 기록 · MRU** (프로젝트·이슈 공통) |
| 4 | 최근 이슈 저장 범위 | **키만 저장**, 제목은 마운트 시 조회 (403/404 자동 탈락) |

### 검증된 사실 (착수 전 실측)

| 사실 | 근거 |
|---|---|
| `?assignee=me` 는 **400** 이다 | `IssueFilterQueryParser.kt:42` — 센티널은 `unassigned` 뿐, 그 외는 `parseUuid` 실패 시 `ResponseStatusException` BAD_REQUEST |
| 대체 경로 실재 | `WhoamiResponse.userId`(`api/schemas.ts:22`) + 선례 `useGadgetData.ts:92-107` `assigneeIds: [userId]` |
| localStorage 에 **업무 내용 저장 전례 0건** | 실측 6곳 전부 화면 설정값 (`bts.theme` · `bts.sidebar.collapsed` · `bts.active-project` · `issue-table-columns` · `timeline-zoom` · 열 표시) |
| 로그아웃이 localStorage 를 안 지운다 | `authStore.ts:34-37` `clearSession()` 은 `sessionStorage.removeItem('bts.auth')` 만 |
| `ProjectTree` 자동펼침이 활성 프로젝트를 안 본다 (**선재 갭**) | `ProjectTree.tsx:369` `useParams({strict:false}).projectKey` — 경로 파라미터만, `useResolvedActiveProject` 미참조 |
| 이슈 상세 라우트 = 기록 지점 | `router.ts:183` `path: '/issues/$key'` |
| 이슈 단건 조회는 캐시된다 | `routes/issues.$key.tsx:186` `useQuery` + `fetchIssue(issueKey)` |
| 스위처를 `<nav>` 로 만들면 깨진다 | `nav-labels.ts:23-26` — `'프로젝트'` 가 `'프로젝트 뷰 전환'` 의 substring, Playwright `getByRole` 기본 substring 매칭 |

## 스펙

전체 스펙. [docs/specs/2026-07-30-fr-ux-08-project-switcher.md](../specs/2026-07-30-fr-ux-08-project-switcher.md) — S1~S10 · FR1~FR16 · NFR1~NFR7 · E1~E12 · L1~L5

**핵심 설계 2건.**
- **1-A. "내 작업" 링크는 `projectKey` 를 싣지 않는다.** `userId` 는 동기(`authStore`)라 링크에 실어도 되지만 `projectKey` 는 비동기(`useProjects` 의존)라 사이드바가 먼저 렌더된다 — FR-UX-07 §1 이 같은 이유로 같은 패턴을 기각했다. `to='/issues' search={{ assignee: userId }}` 만 보내고 프로젝트는 라우트가 해소한다. **`issues.index.tsx` 무변경으로 성립**(`:711,717` 이 이미 소비).
- **1-B. 스위처 착지점은 현재 라우트의 성격이 정한다.** 경로 파라미터(`/projects/$projectKey/*`)가 있으면 같은 하위 경로로 치환 이동, 없으면 활성값만 갱신. 후자에서 활성값만 바꾸면 URL①이 이기고 `useTrackActiveProject:50` 이 원래 키를 **되기록**해 선택이 즉시 되돌려진다.

**Phase B 갭 3건 (전부 해소).**
1. **`?assignee=me` 는 400** — 정본대로 구현했으면 사이드바에 깨지는 링크를 박았다
2. **`nav-labels.test.ts` 가 새 라벨을 안 본다** — 「두 목록이 서로를 안 본다」 양식. FR15 에서 목록 제거형 전수 판별식으로 교체
3. **스위처 선택 되돌림** — §1-B 로 차단, E7 회귀 가드

## Brainstorming Check

✅ 통과 (1회 iteration, 5건 검증 / 갭 3건 발견·해소). `office-hours`·`design-consultation`·`design-shotgun` 전부 스킵 — FR-UX-07 스펙 선례 승계. Phase B 는 추상 브레인스톰 대신 **스펙의 사실 주장을 코드로 되짚는 방식**.

**부수 확증 2건.** D2 정정이 기존 테스트를 **깨지 않는다**(`ProjectTree.test.tsx:186,199,211` · e2e S4/S5 전부 초기 상태 기준 + `playwright.config.ts` 에 `storageState` 없음). FR5 의 덮어쓰기 동작을 직접 단언하는 테스트는 **0건** — JSDoc 에만 있고 봉인돼 있지 않다.

## Plan

> **★ 이 PR 은 PR-A (F12) 다.** 2026-07-30 Maxi 확정으로 FR-UX-08 을 정본대로 2 PR 로 분할했다 (스펙 §11).
> PR-A = 스위처 + 트리 펼침 영속 + 선재 갭 해소. PR-B(후속) = 사이드바 "내 작업"·"최근 항목" + nav 판별식.
> **FR 카운트·D 마커·진척 열 불변** — D 마커는 완주 단위라 F17 까지 끝나야 `[x]` 가 된다.

### Task 1. `use-recent-projects` 훅 — MRU 상한 5 localStorage 영속

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-recent-projects.ts`, `apps/web/src/hooks/__tests__/use-recent-projects.test.ts`]
- depends-on: []

**RED**. `apps/web/src/hooks/__tests__/use-recent-projects.test.ts` 신설. `use-active-project.test.ts` 를 형태 템플릿으로 삼는다.
- `pushRecentProject('A')` 후 목록이 `['A']`
- 이미 맨 앞인 키를 다시 push 하면 **write 생략**(E5) — `setItem` 호출 횟수로 단언
- 중간에 있던 키를 push 하면 맨 앞으로 이동(중복 없음)
- 6번째 push 시 가장 오래된 항목 축출, 길이 **정확히 5**(E6)
- localStorage 에 배열 아닌 값 / 원소가 문자열 아닌 배열 → **빈 목록 폴백**(E4, NFR2)
- `getItem` 이 throw → 빈 목록 폴백, 앱 정상(NFR2)
- 실패 메시지 (예상): `use-recent-projects` 모듈 없음

**GREEN**. `apps/web/src/hooks/use-recent-projects.ts` 신설. zustand `create` + `RECENT_PROJECTS_STORAGE_KEY = 'bts.recent-projects'`. `use-active-project.ts:39-69` 의 fail-safe 3중 폴백(SSR·스토리지 차단·파싱 실패)을 복제하되 값 타입이 `string[]`.

**REFACTOR**. 상한 상수 `MAX_RECENT_PROJECTS = 5` 추출. L1 한국어 주석 + KDoc 에 **NFR1 근거**(UI 선호값, 토큰·PII 아님 — `use-active-project.ts:8-18` 문구 승계) 명시.

**검증**. `apps/web/node_modules/.bin/vitest run src/hooks/__tests__/use-recent-projects.test.ts`

---

### Task 2. `use-project-tree-expanded` 훅 — 펼침 집합 localStorage 영속

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-project-tree-expanded.ts`, `apps/web/src/hooks/__tests__/use-project-tree-expanded.test.ts`]
- depends-on: []

**RED**. 테스트 신설.
- 초기값은 빈 `Set`
- `toggle('ATLAS')` → `has('ATLAS')` true, localStorage 에 `["ATLAS"]` 저장
- `toggle('ATLAS')` 재호출 → false, 저장값 `[]`
- `expand('INFRA')`(더하기 전용)는 기존 원소를 **제거하지 않는다** — FR6 의 핵심 계약
- 이미 있는 키에 `expand` → **write 생략**
- 저장값이 배열 아님 / 원소가 문자열 아님 / `getItem` throw → **빈 Set 폴백**(NFR2)
- 실패 메시지 (예상): `use-project-tree-expanded` 모듈 없음

**GREEN**. `apps/web/src/hooks/use-project-tree-expanded.ts` 신설. `PROJECT_TREE_EXPANDED_STORAGE_KEY = 'bts.project-tree.expanded'`. 메모리 표현 `Set<string>` ↔ 저장 표현 `string[]`. 노출 API 는 `expandedKeys` · `toggle(key)` · `expand(key)` 셋.

**REFACTOR**. 직렬화/역직렬화를 모듈 내 순수 함수로 분리. **상한 없음**이 의도임을 KDoc 에 명시(NFR7).

> **★ 리뷰 BLOCKER-2 반영 — 테스트 리셋 경로를 함께 낸다.** 이 스토어는 `use-sidebar-collapsed`·`use-active-project` 와 같은 **모듈 전역 zustand 싱글톤**이라 테스트 간에 상태가 누출된다. `ProjectTree.test.tsx:100-102` 가 이미 같은 이유로 `useSidebarCollapsed.setState({...})` 리셋을 두고 있다. T4 가 쓸 수 있도록 `setState` 로 초기화 가능한 형태(`expandedKeys: Set`)를 노출하고, **이 훅 자신의 테스트에도 `beforeEach` 리셋을 넣는다.**

**검증**. `apps/web/node_modules/.bin/vitest run src/hooks/__tests__/use-project-tree-expanded.test.ts`

---

### Task 3. `useTrackActiveProject` 확장 — 최근 프로젝트 기록 (FR3)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-track-active-project.ts`, `apps/web/src/hooks/__tests__/use-track-active-project.test.tsx`]
- depends-on: [1]

**RED**. 기존 `use-track-active-project.test.tsx` 에 케이스 추가.
- `/projects/INFRA/*` 방문 시 활성값뿐 아니라 **최근 목록에도** INFRA 가 push 된다
- **★ `isKnownProject` 가 false 면 최근 목록에도 안 들어간다** — 접근 가능 목록에 없는 키(`/projects/TYPO/board`)로 진입 시 최근 목록 길이 불변. **가드가 두 기록 지점 모두를 덮는지가 이 task 의 핵심 계약**(FR-UX-07 코드리뷰 CR3 재발 방지)
- `enabled=false`(미인증)면 아무것도 기록하지 않는다
- 실패 메시지 (예상): 최근 목록이 비어 있음

**GREEN**. 기존 `useEffect`(`:45-51`) 안, `setActiveProject(projectKey)` **바로 옆**에 `pushRecentProject(projectKey)` 추가. **가드(`enabled` · `projectKey !== null` · `isKnownProject`) 아래에 둔다** — 새 early-return 을 만들지 않는다.

**REFACTOR**. KDoc 에 "기록 지점을 늘리지 않는다"는 FR3 근거 한 줄 추가.

**검증**. `apps/web/node_modules/.bin/vitest run src/hooks/__tests__/use-track-active-project.test.tsx`

---

### Task 4. `ProjectTree` — 덮어쓰기 폐지 + 영속 + 활성 소스 교체 (FR6, FR7)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ProjectTree.tsx`, `apps/web/src/components/layout/__tests__/ProjectTree.test.tsx`]
- depends-on: [2]

**RED**. 기존 `ProjectTree.test.tsx` 에 케이스 추가.
- **★ `beforeEach` 리셋 먼저** — `useProjectTreeExpanded.setState({ expandedKeys: new Set() })` 를 기존 `useSidebarCollapsed` 리셋(`:100-102`) 옆에 추가한다. **이게 없으면 아래 단언들이 순서 의존이 되고, 하필 핵심 단언이 "나머지는 접힘"이라 거짓 실패·거짓 통과가 둘 다 가능하다** (리뷰 BLOCKER-2)
- **덮어쓰기 폐지(FR6)** — INFRA 를 펼친 상태에서 활성 프로젝트가 ATLAS 로 바뀌면 ATLAS 가 **추가로** 펼쳐지고 **INFRA 는 펼쳐진 채 유지**된다 (S5)
- **영속(FR6)** — 펼친 뒤 언마운트 → 재마운트 시 펼침이 유지된다 (S4)
- **검색 파라미터 경로(FR7)** — 경로 파라미터가 없고 활성 프로젝트가 MIDDLE 일 때 MIDDLE 이 자동 펼침 + `aria-current="page"` (S6, 선재 갭)
- **E13** — 영속된 키 중 접근 불가 프로젝트는 무시된다
- **E14 / FR11-b** — 프로젝트 컨텍스트 밖(`/dashboards`)에서 저장값이 첫 프로젝트로 설정된다. **의도된 행동 변화임을 단언으로 못박는다**
- 실패 메시지 (예상): INFRA 가 접힘 / MIDDLE 이 활성으로 표시 안 됨

> **★ 리셋 누락의 비-공허 증명.** 구현 완료 후 `beforeEach` 리셋을 **일부러 제거**해 테스트가 실제로 red 가 되는지 1회 확인한다. red 가 안 되면 리셋이 무의미한 것이므로 단언 설계를 다시 본다.

**GREEN**.
- `useState<Set<string>>` + `useEffect` 재설정 블록(`:362-370`)을 `useProjectTreeExpanded()` 소비로 교체
- `useEffect` 를 `expand(activeProjectKey)`(**더하기**)로 변경 — `new Set([...])` 형태 제거
- 활성 소스를 `useParams({strict:false}).projectKey` → `useResolvedActiveProject(...)` 로 교체. 반환 형태는 `use-resolved-active-project.ts` 실측 후 배선
- `toggleProject` 를 훅의 `toggle` 로 위임

**REFACTOR**. **JSDoc 정정 2문장** — *"이전 수동 펼침을 덮어쓴다(FR5)"* → 더하기 동작 + ADR §D2 링크. *"수동 펼침은 ephemeral 이며 영속하지 않는다"* → 영속됨 + 범위가 **프로젝트 레벨**임(L6) 명시.

**검증**. `apps/web/node_modules/.bin/vitest run src/components/layout/__tests__/ProjectTree.test.tsx`

---

### Task 5. `ProjectSwitcher` + `TopBar` 배치 (FR8~FR11)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/project/ProjectSwitcher.tsx`, `apps/web/src/components/project/__tests__/ProjectSwitcher.test.tsx`, `apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/layout/__tests__/TopBar.test.tsx`, `apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`]
- depends-on: [1]

**RED**. `ProjectSwitcher.test.tsx` 신설 + 기존 `TopBar.test.tsx` 보강.
- 트리거가 현재 활성 프로젝트명을 표시한다
- 열면 `role="listbox"` 와 `role="option"` 이 나온다. **`role="navigation"` 은 생기지 않는다**(FR8/NFR3)
- **정렬(FR10, S3)** — 최근 방문 그룹이 MRU 순으로 위, 나머지는 백엔드 순서 그대로, **중복 없음**(E11)
- **★ E7 회귀 가드 — §1-B 3갈래 전부** (리뷰 BLOCKER-1)
  - ① 경로 파라미터(`/projects/ATLAS/board`) → `navigate` 가 `/projects/INFRA/board` 로 불린다
  - ② **검색 파라미터(`/issues?projectKey=ATLAS`) → `navigate({search})` 가 불린다.** 초안이 빠뜨린 분기
  - ③ 없음(`/issues`) → `navigate` 가 **안 불리고** `setActiveProject` 만 불린다
- **★ E7-b** — `?projectKey=ATLAS&status=open&selected=ATLAS-3` 에서 전환 시 `status`·`selected` 가 **살아남는다** (`...prev` 미펼침 시 red. FR-UX-07 FR4-b 재발 방지)
- **키보드(NFR6)** — 열기 · ↑↓ · Enter 선택 · Esc 닫기
- **E1** — 최근 목록에 접근 불가 키가 있으면 목록에서 탈락
- **S10** — 프로젝트 0개면 스위처 미렌더
- **긴 프로젝트명(리뷰 CONCERN-3)** — 트리거 폭이 `max-w` 로 제한되고 텍스트가 `truncate` 되며, **접근가능 이름은 잘리지 않는다**(`getByRole('button',{name:전체이름})` 성립)
- 실패 메시지 (예상): `ProjectSwitcher` 모듈 없음

**GREEN**. `components/ui/popover.tsx` 소비 + `role="listbox"`/`option`. `useProjects()` · `useRecentProjects()` · `useResolvedActiveProject()` 조합. 선택 핸들러는 §1-B **3갈래** 분기(`useParams({strict:false}).projectKey` · `useSearch({strict:false}).projectKey`). `TopBar.tsx` 의 로고와 검색 버튼 사이에 배치하고 트리거에 `max-w-[180px] truncate` 를 준다.

**REFACTOR**. 정렬 로직(최근 그룹 + 나머지, 중복 제거)과 §1-B 착지점 판정을 **모듈 내 순수 함수 2개**로 추출해 단독 테스트 가능하게. L1 한국어 주석 + **`<nav>` 금지 근거**(ADR §D5) KDoc 명시.

**검증**.
```
cd apps/web && node_modules/.bin/vitest run \
  src/components/project/__tests__/ProjectSwitcher.test.tsx \
  src/components/layout/__tests__/TopBar.test.tsx \
  src/components/layout/__tests__/navigation-contract.test.tsx
```
> **★ `navigation-contract.test.tsx` 가 이 task 의 1순위 가드다** (리뷰 CONCERN-2). 실제 `routeTree` 를 `RouterProvider` 로 마운트해 **`검색` 버튼 정확히 1개**(`:111`)와 **aria-label 4종 무위반**(`:149`)을 봉인한다. TopBar 를 건드리는 변경이 이 파일을 안 돌리면 봉인이 무의미하다. **이미 실재하는 비-공허 판별식이므로 같은 취지의 단언을 새로 만들지 않는다.**

---

### Task 6. E2E + 정본 §4.6 분할 명시 + 전체 검증

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-switcher.spec.ts`, `docs/plan/product/personalization.md`, `docs/plans/2026-07-30-fr-ux-08-project-switcher.md`]
- depends-on: [3, 4, 5]

**RED**. `apps/web/e2e/project-switcher.spec.ts` 신설. `active-project.spec.ts` 의 `page.addInitScript` localStorage 심기 관례(`:46,65`)를 따른다.
- **S1** — `/issues` 에서 스위처로 전환 → 같은 URL 유지, 목록이 그 프로젝트로 바뀐다
- **S2** — `/projects/ATLAS/board` 에서 전환 → `/projects/INFRA/board` 로 이동
- **★ E7 (a)** — S2 직후 활성값이 INFRA 로 **유지**된다 (되기록 차단 실증)
- **★ E7 (b)** — `/issues?projectKey=ATLAS` 에서 전환 → URL 의 `projectKey` 가 INFRA 로 바뀌고 **유지**된다. **초안이 빠뜨린 분기이므로 e2e 필수**
- **★ E7-b** — `?projectKey=ATLAS&status=open` 에서 전환 후 `status=open` 이 **URL 에 살아 있다**
- **S4** — 두 프로젝트를 펼치고 `/dashboards` 왕복 → 둘 다 펼쳐진 채
- **S6** — `/issues?projectKey=MIDDLE` 진입 시 트리에서 MIDDLE 이 `aria-current="page"`
- **NFR3** — 기존 `project-tree.spec.ts` S3/S4/S5 가 **무수정 green**

**GREEN**. 위 시나리오 통과까지 Task 3~5 산출물 보정.

**REFACTOR**. `docs/plan/product/personalization.md` §4.6 에 **PR 분할 계획**(PR-A=F12 / PR-B=F17) + 신규 ADR 링크 추가. **D 마커·FR 카운트·진척 열은 건드리지 않는다.**

**검증**.
```
set -o pipefail
cd apps/web && node_modules/.bin/tsc -p tsconfig.app.json --noEmit; echo "TSC=$?"
node_modules/.bin/eslint src e2e; echo "LINT=$?"
node_modules/.bin/vitest run; echo "UNIT=$?"          # 개수는 XML 아닌 vitest 요약으로 기준선 대조
node_modules/.bin/playwright test project-switcher project-tree active-project; echo "E2E=$?"
cd ../.. && bash scripts/verify-master-plan.sh; echo "VERIFY=$?"   # EXIT 0, 139/139
git diff --stat | grep -c '^ backend/' ; echo "(backend 변경 0 이어야 함)"
```

## Plan 메타

- **task 수**. 6
- **예상 wave**. 3 — W1[T1,T2] → W2[T3,T4,T5] → W3[T6]. 파일 교집합 0 (`use-track-active-project.ts` / `ProjectTree.tsx` / `ProjectSwitcher.tsx`+`TopBar.tsx` 서로 무관)
- **TDD 강제**. yes (`test:` 커밋이 `feat:` 커밋보다 먼저)
- **추가 검증**. typecheck(`tsconfig.app.json` — CI 와 동일 설정) · eslint · vitest · playwright · `verify-master-plan.sh`
- **파일 예산**. 신규 4(훅 2 + 컴포넌트 1 + e2e 1) · 수정 5(`use-track-active-project` · `ProjectTree` · `TopBar` · `personalization.md` · plan)
- **불변 단언**. 백엔드 0줄 · 마이그레이션 0 · `package.json` diff 0 · `issues.index.tsx` 무변경 · `shortcuts.ts` 무변경 · `Sidebar.tsx` 무변경(PR-B 소관) · `nav-labels.ts` 무변경(PR-B 소관) · FR 139/139 불변 · 진척 132 불변
- **★ 후속 조건**. **PR-B 는 PR-A 머지 후 착수**한다 — 코드 파일은 교집합 0 이지만 문서 3개(`personalization.md` · spec · plan)를 공유한다 (리뷰 §후속 조건)

### 리스크

| # | 리스크 | 완화 |
|---|---|---|
| R1 | **T4 가 FR-UX-06 PR12 의 FR5 를 뒤집는다.** 기존 테스트가 깨질 수 있다 | Phase B G4 에서 실측 — `ProjectTree.test.tsx:186,199,211` · e2e S4/S5 는 전부 **초기 상태** 기준이라 통과한다(`playwright.config.ts` 에 `storageState` 없음). **깨지면 "되돌린다"가 아니라 "결정이 바뀌었으니 테스트를 고친다"** (ADR §D2) |
| R2 | **E7(선택 되돌림)이 유닛에서는 안 보인다.** `useTrackActiveProject` 는 셸에 있고 스위처는 상단바라 단위 테스트가 둘을 같이 안 띄운다 | e2e(T6)에 **필수 시나리오**로 등재. 유닛은 `navigate` 호출 여부로 §1-B 분기만 검증 |
| R3 | `useResolvedActiveProject` 가 `useProjects()` 를 또 호출해 요청이 늘어난다 | queryKey 공유(staleTime 30s)라 실 요청은 늘지 않는다. **T4 검증에서 네트워크 호출 수 대조** |
| R4 | 워크트리 `pnpm exec` 가 main 을 오염시킨다 (선례 8회) | **`apps/web/node_modules/.bin/*` 직접 호출** + 커밋은 `--no-verify`. 위 검증 명령에 이미 반영 |
| R5 | 펼침 영속이 e2e 간 누출 | `playwright.config.ts` 에 `storageState` 없음 실측 — 테스트마다 새 컨텍스트. 그래도 T6 는 `addInitScript` 로 **명시 초기화**한다 |

## 구현 진행 (2026-07-31 갱신)

| Task | 상태 | 커밋 | 비고 |
|---|---|---|---|
| **T1** `use-recent-projects` | ✅ 완료 | `2d85f0498`(red) → `1271321cb`(green) | 15 테스트 |
| **T2** `use-project-tree-expanded` | ✅ 완료 | 동상 | 14 테스트 · **뮤테이션 M1 통과**(`expand`를 덮어쓰기로 되돌리면 T-PE-4·13 red) |
| **T3** `useTrackActiveProject` 확장 | ✅ 완료 | `924f443a3`(red) → `0be17b708`(green) | 12 테스트 · **뮤테이션 M2 통과**(push를 가드 밖으로 빼면 T-TR-9·10 red) |
| **T4** `ProjectTree` 펼침 영속 + 소스 확장 | ✅ 완료 | `ebb0cf361`(red) → `89491b4f5`(green+refactor) | 16 테스트 · **전체 스위트 530/530** · typecheck 0 |
| **T5** `ProjectSwitcher` + `TopBar` | ⏳ 미착수 | — | §1-B 3갈래 분기 · `role="listbox"` · `navigation-contract.test.tsx` 필수 |
| **T6** e2e + 정본 + 전체 검증 | ⏳ 미착수 | — | E7(a)(b) · E7-b 회귀 가드 |

**구현 중 확정된 정정 1건.** **FR7 의 소스를 `useResolvedActiveProject` 가 아니라 `useParams ?? useSearch` 로 좁혔다** (스펙 FR7 정정단락). 초안대로면 `/dashboards` 에서도 첫 프로젝트가 펼쳐지고 `aria-current="page"` 가 붙어 기존 계약 2건이 깨지고 접근성 의미가 틀린다. 선재 갭의 정체는 「URL 대 저장값」이 아니라 「경로 파라미터 대 검색 파라미터」였다. **파생 — FR11-b·E14 철회, 리뷰 CONCERN C1 불성립.**

**선언 외 파일 수정 2건 (정당·test-only).** `Sidebar.test.tsx`·`ShellLayout.test.tsx` 의 라우터 mock 에 `useSearch` 추가. `ProjectTree` 가 새 훅을 호출하며 필요해졌다. **영향 범위는 추측이 아니라 전체 스위트 실측으로 확정** — 라우터를 mock 하는 65개 파일 중 `ProjectTree` 를 렌더하는 **2개만** 깨졌다.

**구현 방식의 한계.** `bts-impl` 정본은 sub-agent 병렬 dispatch 지만, 이 세션은 에이전트 호출 금지 지시가 걸려 있어 **컨트롤러가 직접 TDD 를 수행**했다(#322·#323·#324 와 동일 조건). 따라서 **implementer ↔ verifier 의 독립성이 없다** — TDD 순서는 `git log` 로 기계 검증 가능하지만(`test:` → `feat:`), drift 판정은 자기 검증이다.

## 리뷰 결과

### 리뷰 방식 (2026-07-30)

`type=ui` 분기의 정본은 `/plan-design-review` 다. 이 저장소의 최근 5 PR(#320~#325)과 동일하게 **컨트롤러가 직접 적대적 리뷰**를 수행했고, eng·design 두 관점을 모두 적용했다. **독립 모델 리뷰는 미실시** — 한계는 아래 §한계에 명시한다.

리뷰는 계획서를 읽는 데 그치지 않고 **주장마다 코드로 되짚었다**(`useResolvedActiveProject:85-91` · `ProjectTree.test.tsx:100-102` · `navigation-contract.test.tsx:111,149` · `TopBar.tsx:38`).

### 🛑 BLOCKER 2건 (둘 다 계획 수정으로 해소)

**BLOCKER-1 — §1-B 의 판별자가 틀렸다. 스위처가 안 먹는 경로가 남아 있었다.**

초안은 착지점 분기를 *"경로 파라미터 유무"* 로 잡았다. 그런데 ADR §D3 의 해소 ①은 *"경로 파라미터 **또는** 검색 파라미터"* 를 **둘 다** URL 로 친다. 그래서 **`/issues?projectKey=ATLAS` 가 어느 분기에도 안 걸린다** — 초안대로면 `setActiveProject(INFRA)` 만 하고, 해소 ①이 URL 의 ATLAS 로 이겨 **화면이 안 바뀐다.**

더 나쁜 것은 **저장값까지 되돌아간다**. `useResolvedActiveProject.ts:85-91` 의 `useEffect` 가 해소 출처 `url` 이면 그 키를 다시 저장한다. 즉 **되기록 지점이 `useTrackActiveProject:50` 과 둘**이고, 초안은 하나만 보고 있었다.

FR-UX-07 이 `?projectKey=` 를 **공유 가능한 링크로 승격**시켰으므로 이 경로는 드문 경우가 아니다.

**해소.** §1-B 를 **3갈래**로 정정(경로 / 검색 / 없음). 검색 분기는 `navigate({ search: (prev) => ({ ...prev, projectKey }) })` 이고 **`...prev` 를 펼치지 않으면 필터·정렬·`?selected=` 가 전부 날아간다**(FR-UX-07 FR4-b 가 겪은 함정 — TanStack `search` 는 객체형이면 병합이 아니라 치환이고 전 필드 optional 이라 타입 체크로도 안 잡힌다). E7 을 (a)(b) 2건으로, E7-b 를 신설해 T5 유닛·T6 e2e 양쪽에 회귀 가드로 넣었다.

**BLOCKER-2 — 새 zustand 싱글톤을 들이면서 테스트 리셋을 계획에 안 넣었다.**

`ProjectTree.test.tsx:100-102` 가 이미 *"`useSidebarCollapsed` 는 모듈 전역 zustand 싱글톤 — 이전 테스트의 상태가 누출되지 않도록 리셋"* 을 두고 있다. `useProjectTreeExpanded` 도 같은 성질인데 초안에는 리셋이 없었다.

**이게 왜 BLOCKER 인가.** T4 의 핵심 단언이 *"나머지는 접힘"* 이다. 리셋이 없으면 앞선 테스트가 펼친 상태가 새어 들어와 **거짓 실패**가 나고, 반대로 단언 순서가 바뀌면 **거짓 통과**가 난다. 검증 장치 자체가 고장 난 채로 green 을 받는 형태다.

**해소.** T2 에 `setState` 초기화 가능한 형태 노출을 명시하고, T4 RED 첫 항목으로 `beforeEach` 리셋을 올렸다. **리셋을 일부러 빼서 실제로 red 가 되는지 확인**하는 비-공허 증명도 함께 넣었다.

### ⚠️ CONCERN 3건 (전부 계획 반영)

| # | 내용 | 반영 |
|---|---|---|
| **C1** | **`ProjectTree` 가 `useResolvedActiveProject` 를 쓰면 저장 생산 지점이 전 페이지로 넓어진다.** 그 훅은 읽기 전용이 아니다(`:85-91`). 현재 소비처는 `/issues`·`/search` 어댑터 둘뿐인데 `ProjectTree` 는 **모든 인증 페이지의 사이드바**다 — `/dashboards`·`/settings`·`/admin/*` 어디를 열어도 해소 ③(첫 프로젝트)이 저장된다. 해롭진 않지만(가드가 훅 안에 있어 값이 수렴) **계획에 없던 행동 변화**였다 | FR11-b 로 **의도된 동작으로 명시 채택** + E14 신설 + T4 RED 에 단언 추가. 조용히 넘어가지 않는다 |
| **C2** | **T5 검증에 `navigation-contract.test.tsx` 가 빠졌다.** 이 파일은 실제 `routeTree` 를 마운트해 `검색` 버튼 **정확히 1개**(`:111`)와 aria-label 4종 무위반(`:149`)을 봉인한다 — TopBar 를 건드리는 T5 의 **1순위 가드**인데 안 돌리게 돼 있었다 | T5 `files`·검증 명령에 추가. **이미 실재하는 비-공허 판별식이므로 같은 취지의 단언을 새로 만들지 않는다**(중복 방지) |
| **C3** | **(design) 스위처 트리거 폭이 미정의.** `TopBar` 는 `h-12` 고정에 좌측 3요소가 붙어 있다. 가변 길이 프로젝트명이 들어오면 긴 이름에서 검색·만들기 버튼을 밀어낸다 | T5 GREEN 에 `max-w-[180px] truncate` 명시 + RED 에 "**접근가능 이름은 잘리지 않는다**" 단언 추가 (시각 truncate ≠ 접근성 이름 손실) |

### ✅ PASS 3건 (검토했고 변경 불필요)

| # | 검토 항목 | 판정 |
|---|---|---|
| **P1** | T4 가 FR6(펼침 영속) + FR7(활성 소스 교체)을 한 task 에 묶은 것 | **정당.** 둘 다 `ProjectTree.tsx:362-370` **같은 블록**을 고친다. 쪼개면 같은 파일이라 어차피 직렬화되고 두 번째가 첫 번째 라인을 즉시 덮어쓴다. RED 단언은 S5/S4 vs S6 로 이미 분리돼 있어 실패 원인 구분이 된다 |
| **P2** | wave 배치의 파일 교집합 0 판정 | **맞다.** W2 = `use-track-active-project.ts` / `ProjectTree.tsx` / `ProjectSwitcher.tsx`+`TopBar.tsx`+`navigation-contract.test.tsx` — C2 반영 후에도 교집합 0. `ProjectTree` 는 `useTrackActiveProject` 를 소비하지 않는다(`ShellLayout` 이 소유)라 T3↔T4 간 런타임 결합도 없다 |
| **P3** | R1(FR-UX-06 PR12 FR5 정정)의 폭발 반경 판정 | **맞다, 단 BLOCKER-2 조건부.** `ProjectTree.test.tsx:186,199,211` 과 e2e S4/S5 는 전부 초기 상태 기준이고 `playwright.config.ts` 에 `storageState` 가 없어 e2e 는 컨텍스트마다 격리된다. **다만 유닛의 "초기 상태" 는 zustand 리셋이 있어야만 성립**하므로 BLOCKER-2 해소가 이 판정의 전제다 |

### 📌 후속 조건 1건

**PR-B 는 PR-A 머지 후에 착수한다.** 코드 파일은 교집합 0 이 맞지만 **문서 3개는 겹친다** — `docs/plan/product/personalization.md`(PR-A 는 분할 계획 추가, PR-B 는 D 마커 `[x]`) · 이 spec(§11) · 이 plan. 동시 진행하면 카운트 drift 와 충돌이 난다(`migration-vnumber-concurrent-branch-collision` 과 같은 결). Plan 메타 §불변 단언에 반영.

### 한계

**독립 모델 리뷰 미실시.** 위 BLOCKER 2건·CONCERN 3건은 **내가 쓴 계획을 내가 되짚어** 찾은 것이다. 이 저장소의 최근 이력에서 **독립 리뷰가 컨트롤러의 BLOCKER 를 반복 적발**했다(#317 4연속 · #314 2회 · #322 `plan-eng-review` 가 계획의 자기모순 적발). 게이트 2 의 `bts-codereview` 가 실질 안전망이다.

**BLOCKER: 없음** (2건 발견, 2건 계획 수정으로 해소). `type=ui` 이고 `auth`/`migration` 이 아니므로 무시 옵션 있는 등급이었으나, 둘 다 실제 동작 결함이라 무시하지 않고 고쳤다.
