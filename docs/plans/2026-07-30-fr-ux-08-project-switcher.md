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
- **덮어쓰기 폐지(FR6)** — INFRA 를 펼친 상태에서 활성 프로젝트가 ATLAS 로 바뀌면 ATLAS 가 **추가로** 펼쳐지고 **INFRA 는 펼쳐진 채 유지**된다 (S5)
- **영속(FR6)** — 펼친 뒤 언마운트 → 재마운트 시 펼침이 유지된다 (S4)
- **검색 파라미터 경로(FR7)** — 경로 파라미터가 없고 활성 프로젝트가 MIDDLE 일 때 MIDDLE 이 자동 펼침 + `aria-current="page"` (S6, 선재 갭)
- **E13** — 영속된 키 중 접근 불가 프로젝트는 무시된다
- 실패 메시지 (예상): INFRA 가 접힘 / MIDDLE 이 활성으로 표시 안 됨

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
- files: [`apps/web/src/components/project/ProjectSwitcher.tsx`, `apps/web/src/components/project/__tests__/ProjectSwitcher.test.tsx`, `apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/layout/__tests__/TopBar.test.tsx`]
- depends-on: [1]

**RED**. `ProjectSwitcher.test.tsx` 신설 + 기존 `TopBar.test.tsx` 보강.
- 트리거가 현재 활성 프로젝트명을 표시한다
- 열면 `role="listbox"` 와 `role="option"` 이 나온다. **`role="navigation"` 은 생기지 않는다**(FR8/NFR3) — `queryAllByRole('navigation')` 개수 불변 단언
- **정렬(FR10, S3)** — 최근 방문 그룹이 MRU 순으로 위, 나머지는 백엔드 순서 그대로, **중복 없음**(E11)
- **★ E7 회귀 가드** — 경로 파라미터가 있는 라우트(`/projects/ATLAS/board`)에서 INFRA 선택 시 `navigate` 가 `/projects/INFRA/board` 로 불린다. 경로 파라미터가 없으면 `navigate` 가 **안 불리고** `setActiveProject` 만 불린다 (§1-B)
- **키보드(NFR6)** — 열기 · ↑↓ · Enter 선택 · Esc 닫기
- **E1** — 최근 목록에 접근 불가 키가 있으면 목록에서 탈락
- **S10** — 프로젝트 0개면 스위처 미렌더
- 실패 메시지 (예상): `ProjectSwitcher` 모듈 없음

**GREEN**. `components/ui/popover.tsx` 소비 + `role="listbox"`/`option`. `useProjects()` · `useRecentProjects()` · `useResolvedActiveProject()` 조합. 선택 핸들러는 §1-B 분기(`useParams({strict:false}).projectKey` 유무). `TopBar.tsx` 의 로고와 검색 버튼 사이에 배치.

**REFACTOR**. 정렬 로직(최근 그룹 + 나머지, 중복 제거)을 모듈 내 순수 함수로 추출해 단독 테스트 가능하게. L1 한국어 주석 + **`<nav>` 금지 근거**(ADR §D5) KDoc 명시.

**검증**. `apps/web/node_modules/.bin/vitest run src/components/project/__tests__/ProjectSwitcher.test.tsx src/components/layout/__tests__/TopBar.test.tsx`

---

### Task 6. E2E + 정본 §4.6 분할 명시 + 전체 검증

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-switcher.spec.ts`, `docs/plan/product/personalization.md`, `docs/plans/2026-07-30-fr-ux-08-project-switcher.md`]
- depends-on: [3, 4, 5]

**RED**. `apps/web/e2e/project-switcher.spec.ts` 신설. `active-project.spec.ts` 의 `page.addInitScript` localStorage 심기 관례(`:46,65`)를 따른다.
- **S1** — `/issues` 에서 스위처로 전환 → 같은 URL 유지, 목록이 그 프로젝트로 바뀐다
- **S2** — `/projects/ATLAS/board` 에서 전환 → `/projects/INFRA/board` 로 이동
- **★ E7** — S2 직후 활성값이 INFRA 로 **유지**된다 (되기록 차단 실증)
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

### 리스크

| # | 리스크 | 완화 |
|---|---|---|
| R1 | **T4 가 FR-UX-06 PR12 의 FR5 를 뒤집는다.** 기존 테스트가 깨질 수 있다 | Phase B G4 에서 실측 — `ProjectTree.test.tsx:186,199,211` · e2e S4/S5 는 전부 **초기 상태** 기준이라 통과한다(`playwright.config.ts` 에 `storageState` 없음). **깨지면 "되돌린다"가 아니라 "결정이 바뀌었으니 테스트를 고친다"** (ADR §D2) |
| R2 | **E7(선택 되돌림)이 유닛에서는 안 보인다.** `useTrackActiveProject` 는 셸에 있고 스위처는 상단바라 단위 테스트가 둘을 같이 안 띄운다 | e2e(T6)에 **필수 시나리오**로 등재. 유닛은 `navigate` 호출 여부로 §1-B 분기만 검증 |
| R3 | `useResolvedActiveProject` 가 `useProjects()` 를 또 호출해 요청이 늘어난다 | queryKey 공유(staleTime 30s)라 실 요청은 늘지 않는다. **T4 검증에서 네트워크 호출 수 대조** |
| R4 | 워크트리 `pnpm exec` 가 main 을 오염시킨다 (선례 8회) | **`apps/web/node_modules/.bin/*` 직접 호출** + 커밋은 `--no-verify`. 위 검증 명령에 이미 반영 |
| R5 | 펼침 영속이 e2e 간 누출 | `playwright.config.ts` 에 `storageState` 없음 실측 — 테스트마다 새 컨텍스트. 그래도 T6 는 `addInitScript` 로 **명시 초기화**한다 |

## 리뷰 결과 (← /bts-review-plan 채움)
