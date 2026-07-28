<!-- FR-UX-07 PR1 — projectKey URL·활성 컨텍스트 승격 + FR-UX-07 신설 등록. /bts 워크플로우 산출물 -->

# FR-UX-07 PR1 — projectKey URL·활성 컨텍스트 승격 + FR-UX-07 신설

> slug: `fr-ux-07-active-project-key`
> type: `ui` · agent: `frontend-engineer` · 논리 BC: `personalization` (물리 `apps/web`)
> 생성: 2026-07-28 · 기저: `3e0dd874a` (main)
> 승인 로드맵: `~/.claude/plans/ui-ux-sorted-kay.md` §F1

## Brief

### 사용자 원문

> UI/UX가 지라클라우드 사용성과 많이 다른데 BTS에서 사용하는 유사한 기능들 지라클라우드 기반으로 리서치 해서 최대한 지라 사용성에 맞춰서 수정 해줘

승인된 27 PR 로드맵의 **첫 PR(F1)**. 로드맵 전체는 `~/.claude/plans/ui-ux-sorted-kay.md`.

### 이 PR이 푸는 문제

`DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩 때문에 **이슈 목록으로 가는 진입로 4개가 전부 ATLAS 프로젝트만** 보여준다. ATLAS 외 프로젝트를 쓰는 사용자에게 제품이 빈 껍데기다.

**실측 (2026-07-28, 코드 확인 완료)**

| 위치 | 현황 |
|---|---|
| `routes/issues.index.tsx:47` | `const DEFAULT_PROJECT_KEY = 'ATLAS'` |
| `routes/issues.index.tsx:788` | `<IssueListPage projectKey={DEFAULT_PROJECT_KEY} …>` — URL로 바꿀 수단 없음 |
| `routes/search.tsx:21` | `const DEFAULT_PROJECT_KEY = 'ATLAS'` |
| `routes/search.tsx:475-478` | `search.projectKey` 우선, 없으면 ATLAS 폴백 (**여긴 URL 전환이 이미 가능**) |
| `router.ts:126-158` | `issuesIndexRoute.validateSearch` 에 `projectKey` **부재** (page/status/assignee/label/component/sort/selected 7종만) |

**오염된 진입로 4개**

| 진입로 | 파일 | 현재 |
|---|---|---|
| 사이드바 "이슈" | `components/layout/Sidebar.tsx:48` | `{ to: '/issues', … }` — search 없음 |
| 명령 팔레트 "내 이슈" | `components/command-palette/commands.ts:59` | `{ label: '내 이슈', to: '/issues' }` |
| 로그인 후 시작 페이지 | `lib/start-page.ts:63-64` | `my_issues` → `{ to: '/issues', search: { assignee: userId } }` |
| 단축키 `g i` | `components/keyboard-shortcuts/shortcuts.ts` | `effect: { kind: 'navigate', to: '/issues' }` |

**파생 결함.** `lib/start-page.ts` 의 `my_issues`(FR-PF-02 완료 표시)는 ATLAS에 담당 이슈가 없는 사용자에게 **로그인 직후 빈 화면**을 준다.

### 같은 PR에 싣는 것 — FR-UX-07 신설 (131 → 132)

로드맵 전체를 추적할 FR을 첫 PR에서 등록해 진척 가시성을 먼저 연다.

**신설 근거가 이미 문서에 있다** — `docs/plan/product/personalization.md:142` (FR-UX-05 §4.3):
> 컨텍스트 의존 단축키(`j/k/e/m/s`)는 **후속 FR로 제외**(Maxi 결정 2026-07-05)

**FR-UX-06 확장 불가 근거** — `personalization.md` §4.4 D1~D7 전량 `[x]`, D4 백엔드 = "없음(물리 `apps/web`)"으로 닫힘. 로드맵은 백엔드 B1/B2를 포함하므로 충돌. 완료 FR의 D단계를 되돌리면 진척률이 역행한다.

**선점 검증 완료 (2026-07-28)** — `docs/` 전체에 `FR-UX-07` **0건**. 열린 PR 0건, worktree 0건. `verify-master-plan.sh` 기준선 EXIT=0 / 131·131.
> 참고. `FR-AU-12`·`FR-IS-12`가 문서에만 존재하나 **선점 아님** — 전자는 `FR-AU-12 ≡ FR-PM-02`로 흡수 확정(ADR 정정 단락), 후자는 실제로 FR-MV-02로 착지. 둘 다 fr-index 미등록.

### 확정 결정 (Maxi, 2026-07-28)

| # | 결정 |
|---|---|
| 1 | 로드맵 범위 = 전부 (Tier 1+2+3, 약 27 PR) |
| 2 | 백엔드 = B1+B2만. **B3(프로젝트 무관 조회) 제외** → "내 작업"은 프로젝트 스코프 v1 |
| 3 | **FR-UX-07 신설** (131→132). 백엔드 B1/B2는 기존 FR 결손 봉합이라 chore |
| 4 | 검색 이름표 분리 — 상단바 입력창 `전역 검색`, 기존 `검색`은 AQL 제출 버튼 전용 (F13에서 적용) |

### classify 정정

`classify-task.ts` 가 `type=backend` / `agent=backend-engineer` / `primary_bc=issue-tracking` 으로 **오분류**(같은 오분류 3번째 재발 — PR9 `route`→api, PR12 →ui, 이번). 실측 정정 = 변경 파일 전량 `apps/web`, `backend/` 0건. 논리 BC = `personalization`(물리 `apps/web`, ADR D5 "논리 ≠ 물리" 승계).

### 지켜야 할 계약 (깨면 즉사)

- `aria-label` 4종 — `메인 메뉴` / `관리 메뉴` / `프로젝트 뷰 전환` / `검색`. `getByRole('navigation')` **24발생**
- 사이드바 nav 링크에 `search` 를 붙이는 건 `메인 메뉴` nav **내부** 변경이라 라벨 계약 무영향
- 팔레트 `QUICK_LINKS` 4개와 **순서** — `command-palette.spec.ts:252-259` 가 "ArrowDown 1회 → 2번째=검색"을 단언
- `<h1>` 단일 + 이름 verbatim — `getByRole('heading')` **250발생**

## 도메인 정리

- **BC.** 논리 `personalization` / 물리 `apps/web` — FR-UX-05 D4 · FR-UX-06 D5 선례 승계. glossary가 이 패턴을 이미 정본화(퀵 필터 = 논리 personalization·물리 agile-planning / 캘린더 피드 토큰 = 논리 personalization·물리 identity-access)
- **영향 엔티티.** 없음 — 도메인 엔티티·테이블·마이그레이션 **0**. 순수 프론트 상태·라우팅 계약
- **새 용어.** **활성 프로젝트 (Active Project)** — glossary 추가 후보 (Maxi 승인 대기, 게이트 1에서 확인)
- **기존 결정 충돌.** 없음. FR-UX-06 §4.4 는 완료로 **닫아 두고** 신규 FR-UX-07 을 연다(확장 시 진척률 역행)
- **관련 ADR.** [2026-07-28-fr-ux-07-active-project-context.md](../decisions/2026-07-28-fr-ux-07-active-project-context.md) **(생성됨, D1~D5)**
  · 승계 [FR-UX-06 §D5](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) · [FR-PR-01 §D1](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md)
  · 근거 [FR-UX-05 §D3](../decisions/2026-07-05-fr-ux-05-keymap.md) — `:78` *"후속 FR로 미룬다(Maxi 결정 2026-07-05)"*

### 활성 프로젝트 — 4단 해소 순서 (ADR D3)

```
① 현재 URL 의 projectKey   (경로 /projects/$projectKey/* 또는 검색 ?projectKey=)
② localStorage 마지막 저장값  (키 `bts.active-project`)
③ 접근 가능한 첫 프로젝트     (GET /api/v1/projects 응답 순서)
④ 프로젝트 0개 → 빈 상태     (/projects 로 안내)
```

**URL 이 최상위인 이유.** 링크 공유·뒤로가기·새로고침이 같은 화면을 재현해야 한다. 저장값이 URL을 이기면 공유 링크가 받는 사람에게 다른 프로젝트를 연다.
**URL 이 프로젝트를 담으면 ②를 갱신한다.** 보드(`/projects/ATLAS/board`)를 보다 사이드바 "이슈"를 누르면 ATLAS 이슈가 나와야 한다 — 이게 "활성"의 의미다.

### Maxi 확정 (2026-07-28, 도메인 단계)

| # | 질문 | 결정 |
|---|---|---|
| D-a | 활성 프로젝트 영속 위치 | **localStorage**. 서버 `user_preferences` 확장은 기각(Flyway + identity-access 변경이 결정 2 초과) — **후속 FR 후보**로 남김. 해소 순서 ②의 소스만 교체되므로 폭발 반경 작음 |
| D-b | 저장값·URL 둘 다 없는 첫 방문 | **접근 가능한 첫 프로젝트 자동 선택**. 빈 화면 대신 곧장 이슈 노출 |

### 실측 근거 (도메인 단계에서 확인)

- `user_preferences`(V031+V032) 실재 — `theme`·`locale`·`date_format`·`start_page`. PATCH API + `PreferencesProvider` 소비 중. **도메인상 자연스러운 자리이나 백엔드 변경이 필요해 v1 기각**
- `Maxi_wiki/BTS/domain/` 에 `personalization.md` **부재**(7개 BC 노트만) — personalization 은 논리 BC 라 물리 노트가 없다. 정상
- `/issues` 프로젝트 스코프 강제 지점 = `IssueApplicationService.kt:1021` `assertPermission(actor, BROWSE, IssueScope.Project(projectKey))`

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
