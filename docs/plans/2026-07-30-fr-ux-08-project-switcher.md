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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
