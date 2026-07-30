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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
