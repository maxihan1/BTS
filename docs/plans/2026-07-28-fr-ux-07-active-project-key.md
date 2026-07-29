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

### 같은 PR에 싣는 것 — FR-UX-07 신설 + 기능 단위 분할 (131 → 139)

착수 시점(2026-07-28)의 계획은 **로드맵 전체를 추적할 FR 하나**를 첫 PR에서 등록하는 것이었다(131 → 132).
게이트 2 직전(2026-07-29) Maxi 지적으로 그 안이 **뒤집혔다** — 27 PR 을 한 FR 에 담자 `personalization.md §4.5` 의
D1~D7 마커가 `[~] [~] [x] [ ] [ ] [~] [~]` 로 세 상태가 섞였고 D1 본문에 「미착수 — 27 PR 로드맵 잔여」가 붙었다.
**D1(도메인 정리)이 「절반 완료」인 상태는 성립하지 않는다.** 그래서 FR-UX-07 을 **활성 프로젝트 컨텍스트**로 좁히고
나머지를 기능 단위 일곱(FR-UX-08~14)으로 분리해 **등록만** 했다 (131 → **139**).

근거·대조표·분할 매핑은 ADR [decisions/2026-07-28-fr-ux-07-active-project-context.md](../decisions/2026-07-28-fr-ux-07-active-project-context.md) **§D6** 이 정본이다.
결과로 **이 PR 이 좁아진 FR-UX-07 의 D1~D7 을 전량 `[x]` 로 닫는다** — 원안에서는 1/27 만 진행한 상태로 남았을 것이다.

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

**추가 확정 (Maxi, 2026-07-29 — 게이트 2 직전)**

| # | 결정 |
|---|---|
| 5 | **FR-UX-07 을 기능 단위 여덟으로 분할** (131→**139**). 좁아진 FR-UX-07(활성 프로젝트 컨텍스트)은 이 PR 이 완결하고, FR-UX-08~14 는 등록만 한다. 근거 = D 마커는 완주 단위에 붙어야 한다 (ADR §D6) |
| 6 | **결정 3 의 정정** — 분할 후 B1·B2 는 chore 가 아니라 **FR-UX-09 의 D4 · FR-UX-14 의 D4** 다. 「백엔드 없음」으로 비던 칸이 실제 내용으로 채워지는 쪽이 정확하다 |
| 7 | E2E 스펙 **S5·S6·S7·S8 4종 + 엣지 E2(에러+재시도) 1종을 이번 PR 에 추가**한다. 계획서 Task 10 의 `S1~S8 전수` 요구를 실제로 충족시킨다 |
| 8 | `personalization.md` FR-UX-07 의 D1·D2 는 **`[~]` 유지 후 분할로 해소** — 범위를 좁히자 `[x]` 가 정직해졌다. 당초 검토안(본문을 FR 전체 범위로 확대)은 분할이 채택되며 불필요해졌다 |

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

## 스펙

전체 스펙. [docs/specs/2026-07-28-fr-ux-07-active-project-key.md](../specs/2026-07-28-fr-ux-07-active-project-key.md)

### ★ 스펙에서 뒤집힌 것 — 링크가 아니라 라우트가 해소한다

로드맵 §F1 은 "진입로 4곳 배선"(사이드바·팔레트·시작페이지·단축키)을 적었다. **불필요한 것으로 판명.** `/issues` 라우트가 스스로 활성 프로젝트를 해소하면 4곳이 동시에 낫는다.

| | 로드맵 원안 (A) | 스펙 확정 (B) |
|---|---|---|
| 방식 | 링크가 `search={{projectKey}}` 를 실어 보냄 | 라우트가 `useActiveProject()` 로 해소 |
| 변경 파일 | 7 | **4** |
| 건드리는 계약 | `QUICK_LINKS` 순서 · `SHORTCUTS` 5종 동결 · nav 라벨 | **없음** |

**A 기각 사유 3가지.** ①해소가 `useProjects()` 응답에 의존하는데 사이드바가 먼저 렌더된다(비동기 순서) ②`QUICK_LINKS` 를 동적으로 만들면 `command-palette.spec.ts` 의 "ArrowDown 1회 → 2번째=검색" 순서 계약이 흔들린다 ③`shortcuts.ts` 를 건드리면 **프론트 2단언 + 백엔드 `KeymapAction` enum + DB CHECK 제약**이 동시에 깨진다.

**URL 정규화도 기각.** 마운트 직후 `replace` 로 projectKey 를 써넣는 방안은, 이 저장소에 **transient URL 관측 race 로 e2e 가 90초 hang 한 선례**(`saved-filters` SF-1/SF-3, `?filterId=`)가 있어 새로 들이지 않는다.

### 핵심 시나리오 3줄

- `/issues?projectKey=INFRA` 는 INFRA 를 보여주고 활성 프로젝트를 갱신한다 (명시 최우선)
- URL 에 없으면 저장값 → 이름 오름차순 첫 프로젝트 순으로 내려간다. 프로젝트 0개면 빈 상태
- 명시 지정이 권한 실패하면 **조용히 대체하지 않고 에러를 표시**한다 (권한 문제를 숨기지 않는다)

### 변경 파일 (4 + 문서)

| 파일 | 변경 |
|---|---|
| `apps/web/src/router.ts` | `issuesIndexRoute.validateSearch` 에 `projectKey?: string` |
| `apps/web/src/hooks/use-active-project.ts` | **신규** — zustand + localStorage(`bts.active-project`) + 4단 해소 |
| `apps/web/src/routes/issues.index.tsx` | 상수 제거 · 해소 결과 전달 · 로딩/0개 흡수 |
| `apps/web/src/routes/search.tsx` | 상수 제거 · 폴백만 해소 결과로 교체 |
| 문서 9종 | FR-UX-07 등록 + 기능 단위 분할 131→**139** (2026-07-29 결정 5). `CHANGELOG.md` 가 9번째로 전수 동기화 체크리스트에 편입됐다 |

**diff 0 이어야 하는 파일.** `Sidebar.tsx` · `commands.ts` · `shortcuts.ts` · `lib/start-page.ts`

## Brainstorming Check

✅ 통과 (1회 iteration). `office-hours` 미호출 — FR 이 이미 정의된 작업엔 프레임 불일치(2026-05-29 Maxi 확정). Phase B 는 **스펙의 사실 주장을 코드로 되짚는 방식**으로 수행.

- **G1 갭→해소.** "접근 가능한 첫 프로젝트" 가 미정의 표현 → `ProjectQueryRepository.kt:62` `.orderBy(PROJECTS.NAME.asc())` 실측으로 **이름 오름차순 첫 번째** 확정
- **G2 확증.** `QUICK_LINKS` 순서 계약이 e2e 주석으로 명문화돼 있음 — 설계 B 가 보존
- **G3 갭→해소.** `IssueListPage` `projectKey: string` **non-nullable**(`issues.index.tsx:337`). nullable 화하면 5지점 파급 → **어댑터가 흡수**로 제약 명시
- **한계.** 3건 모두 자기 검토. 독립 리뷰는 게이트 2 `bts-codereview` 가 안전망

## Plan (v1 — ⚠️ 독립 리뷰로 **폐기**. 정본은 아래 §Plan v2)

> **이 절은 감사 흔적으로만 남긴다.** 독립 엔지니어링 리뷰가 BLOCKER 5건을 적발했고 전부 실측 확인됐다. 무엇이 왜 바뀌었는지는 §리뷰 결과 참조. **구현자는 §Plan v2 를 따른다.**

> `superpowers:writing-plans` 대신 직접 작성. 이 형식(메타 블록 `agent`/`files`/`depends-on`)은 BTS 전용이라 범용 스킬이 모른다.
> **구조 선례 승계** — 순수 해소 함수는 `lib/`(`lib/start-page.ts:50` `resolveStartPageNav`·`lib/backlog-drag.ts` `resolveBacklogDropAction`), localStorage 스토어는 `hooks/`(`hooks/use-sidebar-collapsed.ts`), 그 테스트는 `hooks/__tests__/`.

### Task 1. 활성 프로젝트 해소 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/active-project.ts`, `apps/web/src/lib/active-project.test.ts`]
- depends-on: []

**RED**. `apps/web/src/lib/active-project.test.ts`
```ts
// 4단 해소 — URL > 저장값 > 이름 오름차순 첫 프로젝트 > null
resolveActiveProjectKey({ urlKey: 'INFRA', storedKey: 'ATLAS', projects })  // 'INFRA'  (S1)
resolveActiveProjectKey({ urlKey: null,    storedKey: 'INFRA', projects })  // 'INFRA'  (S2)
resolveActiveProjectKey({ urlKey: null,    storedKey: null,    projects })  // 첫 프로젝트 (S3)
resolveActiveProjectKey({ urlKey: null,    storedKey: 'GONE',  projects })  // 첫 프로젝트 (S6, 저장값이 목록에 없음)
resolveActiveProjectKey({ urlKey: null,    storedKey: null,    projects: [] })       // null (S5)
resolveActiveProjectKey({ urlKey: 'NOPERM',storedKey: 'ATLAS', projects })  // 'NOPERM' 그대로 (S7 — 조용한 대체 금지)
```
실패 메시지(예상). `lib/active-project` 모듈 없음

**GREEN**. `apps/web/src/lib/active-project.ts` — 순수 함수. React·스토리지 의존 0. 입력은 이미 조회된 `projects` 배열(**신규 조회 금지**, `useProjects()` 결과를 호출자가 주입)

**REFACTOR**. `resolveActiveProjectKey` KDoc 에 4단 순서와 **왜 URL 이 최상위인지**(공유 링크·뒤로가기 재현) 명시. `ActiveProjectResolution` 타입으로 `{ key, source: 'url'|'stored'|'first'|'none' }` 반환 — 호출자가 "저장값을 갱신할지"(FR4) 판단하려면 **출처를 알아야 한다**

**검증**. `cd apps/web && node_modules/.bin/vitest run src/lib/active-project.test.ts`

---

### Task 2. localStorage 영속 스토어

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-active-project.ts`, `apps/web/src/hooks/__tests__/use-active-project.test.ts`]
- depends-on: [1]

**RED**. `hooks/__tests__/use-active-project.test.ts` — `use-sidebar-collapsed.test.ts` 를 템플릿으로
```
- 저장값 없으면 null
- setActiveProject('INFRA') 후 localStorage 에 기록 + 재구독 시 복원
- localStorage.getItem 이 throw 해도 null 폴백, 앱 정상 (NFR2)
- JSON 파싱 실패 / 문자열 아닌 값 → null (E5)
- 같은 값 재설정 시 write 생략 (E7)
```
실패 메시지(예상). `hooks/use-active-project` 모듈 없음

**GREEN**. zustand `create` + `readStored`/`writeStored` fail-safe. 키 `bts.active-project`

**REFACTOR**. KDoc — **§1.18(토큰 localStorage 금지)과 무관한 이유**를 `use-sidebar-collapsed.ts:11-14` 문구 형식으로 명시 (UI 선호값, 토큰·PII 아님). NFR1 충족

**검증**. `node_modules/.bin/vitest run src/hooks/__tests__/use-active-project.test.ts`

---

### Task 3. router validateSearch — projectKey

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/router.project-key.test.tsx`]
- depends-on: []

**RED**. `src/router.project-key.test.tsx` (`router.split-search.test.tsx` 형식 승계)
```
- /issues?projectKey=INFRA → validateSearch 가 'INFRA' 파싱
- projectKey 부재 → undefined
- projectKey 가 문자열 아님 → undefined (기존 7종과 동일 가드)
- 기존 7종(page·status·assignee·label·component·sort·selected) 파싱 무회귀
```

**GREEN**. `issuesIndexRoute.validateSearch` 반환 타입 + 본문에 `projectKey` 1행 추가 (`router.ts:126-158`)

**REFACTOR**. 없음 (기존 패턴 그대로 1행 추가)

**검증**. `node_modules/.bin/vitest run src/router.project-key.test.tsx src/router.split-search.test.tsx`

---

### Task 4. 이슈 목록 라우트 배선 + 로딩/0개 흡수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`]
- depends-on: [2, 3]

**RED**. `routes/issues.index.test.tsx` 에 추가
```
- URL ?projectKey=INFRA → fetchIssues 가 INFRA 로 호출 (S1)
- URL 없음 + 저장값 INFRA → INFRA 로 호출 (S2)
- 프로젝트 목록 로딩 중 → fetchIssues 호출 0회 (FR7/E1)
- 프로젝트 0개 → EmptyState 렌더 + fetchIssues 0회 + /projects 링크 (FR8/S5)
- 프로젝트 목록 조회 실패 → 에러 + 재시도, 저장값 추측 진행 안 함 (E2)
```

**GREEN**. `DEFAULT_PROJECT_KEY` 상수 삭제 · 어댑터가 `useProjects()` + `useActiveProject()` + `resolveActiveProjectKey()` 조합 · `source === 'url'` 일 때만 저장값 갱신(FR4/E7) · **해소 전·0개면 `IssueListPage` 를 렌더하지 않는다**(G3 — `projectKey: string` non-nullable 계약 보존)

**REFACTOR**. 어댑터의 분기 3종(로딩/0개/정상)을 조기 반환으로 평탄화. `IssueListPage` props·시그니처 **무변경** 확인

**검증**. `node_modules/.bin/vitest run src/routes/issues.index.test.tsx`

---

### Task 5. 검색 라우트 폴백 교체

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/search.tsx`, `apps/web/src/routes/search.test.tsx`]
- depends-on: [2]

**RED**. `routes/search.test.tsx` 에 추가
```
- ?projectKey=INFRA → INFRA 스코프 유지 (기존 동작 무회귀)
- ?projectKey 없음 + 저장값 INFRA → INFRA (기존엔 ATLAS 였다)
- 프로젝트 0개 → AQL 조회 0회
```

**GREEN**. `DEFAULT_PROJECT_KEY` 상수 삭제. `search.tsx:475-478` 의 3항 폴백만 해소 결과로 교체 — **우선순위 구조는 그대로**(E8)

**REFACTOR**. 없음

**검증**. `node_modules/.bin/vitest run src/routes/search.test.tsx`

---

### Task 6. 회귀 봉인 — 하드코딩 재발 + diff-0 계약

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/__tests__/active-project-contract.test.ts`]
- depends-on: [4, 5]

**RED**. 소스 스캔 테스트 (`components/__tests__/button-primitive-usage.test.ts` 형식 승계)
```
- prod 소스에 하드코딩 프로젝트 키 리터럴 0건 (판별식: 대문자 3~10자 문자열이 projectKey 로 대입되는 패턴)
- Sidebar.tsx / commands.ts / shortcuts.ts / lib/start-page.ts 가 활성 프로젝트를 import 하지 않는다 (설계 B 고정)
```

**GREEN**. 테스트만 (프로덕션 코드 변경 0)

**REFACTOR**. **비어 있지 않음 증명 필수** — `archunit-vacuous-rule-silent-pass` 선례. 일부러 `const X = 'ATLAS'` 를 넣어 fail 하는지, `Sidebar.tsx` 에 import 를 넣어 fail 하는지 **2종 뮤테이션**으로 확인 후 원복. 뮤테이션은 **커밋 후에만**(`mutation-test-requires-committed-baseline`)

**검증**. `node_modules/.bin/vitest run src/__tests__/active-project-contract.test.ts` + 뮤테이션 2종 fail 확인

---

### Task 7. FR-UX-07 등록 — 정본 8종 전수 동기화

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/sdd/02-requirements.md`, `CLAUDE.md`, `docs/progress.html`]
- depends-on: []

**RED**. 없음 (문서). 대신 **선-실패 확인** — FR-UX-07 행만 먼저 넣고 `verify-master-plan.sh` 가 **카운트 drift 로 EXIT 4** 를 내는지 본다. 안 내면 verify 가 이 형식을 못 잡는다는 뜻이라 **verify 스크립트를 같은 PR 에서 확장**해야 한다(CLAUDE.md §강제)

**GREEN**. 실측된 갱신 지점 (2026-07-28 확인)
| 파일 | 지점 |
|---|---|
| `fr-index.md` | `:1` 상단 주석 `131개` · `:5` §A.1 헤더 `(131개 전수)` · `:8` 검증 문구 · `:175` `### 사용성 (FR-UX, 6개)`→7개 · `:184` 뒤 FR-UX-07 행 · `:247` personalization `13`→14 및 `UX-01,04,05,06(4)`→`UX-01,04,05,06,07(5)` · `:249` 합계 · §A.4 변경이력 append |
| `personalization.md` | `:1` L1 주석 `13 FR` · `:5` `소속 FR. 13개` · `:110` `## §4 UX 편의 (FR-UX-01, 04, 05, 06)` · 신규 `### §4.5 FR-UX-07` + D1~D7 체크박스 · `:204` §NFR 완료 게이트 |
| `README.md` | `:113` BC 행 (FR 수 **+ 진척 열**) · `:117` 합계 · §7 이력 append · **`grep -n '[0-9]\{2,\} FR' docs/plan/README.md` 전수** (개수로 열거하면 남는다 — 실제로 `:23`·`:129` 산문 2지점이 누락됐다) |
| `CHANGELOG.md` | `[Unreleased]` 블록만 — `:17` `**범위**` · `:19` `**상태**`(날짜 도장까지) · §BC 요약 표의 해당 BC 행 |
| `sdd/02-requirements.md` | FR-UX-07 행 (**verify 가 SDD↔plan 양방향 차집합 검사** — 누락 시 EXIT 1) |
| `CLAUDE.md` | `:10`·`:57` `131 FR` |
| `progress.html` | `node scripts/build-dashboard.mjs` 재생성 |

**REFACTOR**. `docs/plans/` · `docs/decisions/` 는 verify 스캔 대상이 아니므로 카운트 표기 불필요

**검증**. `bash scripts/verify-master-plan.sh` **EXIT 0** + 출력이 `139/139`
> 착수 시점 이 칸은 `132/132` 였다. 2026-07-29 결정 5(기능 단위 분할)로 최종 목표치가 바뀌었다.

---

### Task 8. E2E — S1~S8 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/active-project.spec.ts`]
- depends-on: [4, 5]

**RED**. 신규 spec
```
S1 /issues?projectKey=INFRA → INFRA 이슈 + 저장값 갱신
S2 저장값 폴백 (addInitScript 로 localStorage 선주입)
S3 첫 방문 → 이름 오름차순 첫 프로젝트
S4 /projects/INFRA/board → 사이드바 "이슈" → INFRA
S5 프로젝트 0개 → 빈 상태 (MSW 시나리오 토글)
NFR5 진입 후 URL 불변 (transient 정규화 없음)
```

**GREEN**. MSW 핸들러 보강이 필요하면 **기존 `@/test/server` 단일 인스턴스**에만 등록 (`msw-dual-setupserver-double-dispatch` 회피)

**REFACTOR**. 셀렉터는 기존 계약 재사용. **새 `aria-label` 도입 금지**

**검증**. `node_modules/.bin/playwright test e2e/active-project.spec.ts` (**positional 필터가 삼켜지므로 바이너리 직접 호출** — `e2e-playwright-filter-arg-drop`) + 회귀 `e2e/command-palette.spec.ts` `e2e/issue-filter.spec.ts`

---

## Plan 메타

- **task 수**. 8
- **wave 예상**. 4 — W1 `[T1, T3, T7]` · W2 `[T2]` · W3 `[T4, T5]` · W4 `[T6, T8]`
- **TDD 강제**. yes (T7 문서는 선-실패 확인으로 대체)
- **백엔드 변경**. 0 · 마이그레이션 0 · 신규 npm 0
- **추가 검증**. typecheck · eslint · vitest 전수 · playwright · `verify-master-plan.sh`

### 이 plan 이 의식적으로 피한 것

| 함정 | 회피 |
|---|---|
| worktree `pnpm exec` 가 main `.modules.yaml` 오염 | 전 검증을 `node_modules/.bin/*` 직접 호출. 커밋은 `--no-verify` |
| 봉인이 vacuous | T6 REFACTOR 에서 **뮤테이션 2종 fail 확인 후 원복** (커밋 후에만) |
| e2e positional 필터 삼킴 | T8 검증을 바이너리 직접 호출로 명시 |
| transient URL race | 정규화 자체를 안 함 (스펙 §1) + NFR5 로 어서션 |
| `IssueListPage` prop nullable 화 파급 | T4 GREEN 에서 어댑터가 흡수, prop 무변경을 REFACTOR 에서 확인 |
| verify 가 새 카운트 형식을 못 잡음 | T7 RED 에서 **선-실패 확인** 후 필요하면 verify 확장 |

## 리뷰 결과

**방식.** `autoplan` 4종(CEO·design·eng·DevEx) 대신 **엔지니어링 집중 독립 리뷰** 1회. 근거 — 이 작업은 `type=ui` 지만 **시각 변화가 0**(라우팅·상태 배관)이라 design 리뷰가 무의미하고, *"plan 작성자가 자기 plan 을 리뷰하면 편향"* 이다([[bts-review-plan-autoplan-overkill]]).

**결과. BLOCKER 5 · CONCERN 8 · NIT 4.** BLOCKER 5건은 전부 내가 직접 코드로 재확인했다 — *"지적은 채택하고 처방은 검증"*([[seal-blinds-existing-guard]]).

### BLOCKER (전부 실측 확인)

| # | 지적 | 실측 검증 | 처방 |
|---|---|---|---|
| **B1** | **S4(프로젝트 컨텍스트 추종)를 구현하는 태스크가 없다.** `/issues` 엔 `$projectKey` 경로 파라미터가 없고(`router.ts:122`), `useParams().projectKey` 를 읽는 프로덕션 코드는 전부 `/projects/$projectKey/*` 어댑터인데 **어느 것도 저장하지 않는다**(props 로 내리기만). T8 은 자기가 구현하지 않는 S4 를 e2e 로 검증하겠다고 적었다 | ✅ `grep useParams` 전수 — 저장 지점 0건 확인 | **T5 신설** — `ShellLayout` 에 경로 파라미터 기록기 |
| **B2** | **`handleFilterChange` 가 URL 에서 projectKey 를 날린다.** navigate 5곳 중 이 한 곳만 `...prev` 를 안 펼친다. TanStack `search` 는 객체형이면 **병합이 아니라 치환**이고 전 필드 optional 이라 **타입 체크로도 안 잡힌다** | ✅ `issues.index.tsx:767-770` 실물 확인. 대조 — `:721`·`:746`·`:754`·`:782` 는 `...prev` 사용 | T6 GREEN 에 navigate 5곳 보존 명시 + RED 추가 |
| **B3** | **"`source==='url'` 일 때만 저장" 이 S3·S6/E3 와 충돌.** 스펙은 ③도 저장하라고 명시. 게다가 `refetchOnWindowFocus` 기본 true 라 저장 안 하면 목록 재조회로 **아무 조작 없이 프로젝트가 갈아탄다** | ✅ 스펙 S3 `:49` "그 값이 저장된다" · E3 `:122` "저장값이 교정된다" 대조 | FR4 를 `url \|\| first` 로 확대 |
| **B4** | **`search.tsx` 의 non-nullable 소비처가 3곳**인데 G3 는 `IssueListPage` 만 언급 | ✅ `search.tsx:242,593,609,616` — SearchPage·SaveFilterDialog·ExportDialog 확인 | 스펙 §8 G3 를 4소비처로 확장 |
| **B5** | **봉인 판별식이 자기가 없애려는 형태를 못 잡는다.** 판별식은 "`projectKey` 로 대입되는 패턴" 인데 대상은 `const DEFAULT_PROJECT_KEY = 'ATLAS'` — 매치 안 됨. 뮤테이션도 판별식과 불일치. 스캔 범위 미정의라 `src/mocks/**` 60건+ 로 **처음부터 영구 RED** | ✅ 자명 + `mocks/` 실측 | 판별식·뮤테이션·스캔 목록 전면 재정의 |

### 채택한 CONCERN

**C1** `/search` 도 저장 갱신(조합 훅이 일괄) · **C2** 조합 훅 부재로 T4·T5 가 같은 로직 2벌 작성 → **T4 신설** · **C3** `onUnhandledRequest:'error'`(`test/setup.ts:19`) 인데 어댑터 harness 에 프로젝트 핸들러 없음 → 대량 RED → RED 목록에 명시 · **C4** ADR("응답 순서")과 FR3("이름 오름차순")이 불일치 → **"백엔드 정렬 신뢰, 프론트 재정렬 없음"**(저장소 관례)으로 통일 · **C5** 조기 반환이 split view 상세 페인까지 죽임 → 목록 영역 한정 · **C6** S6·S7·S8 검증 부재 → T10 에 추가 · **C7/C8/N1** → 스펙 §10 알려진 한계 L1~L4 로 등재 · **N2** T2 의 불필요한 `depends-on` 제거 · **N3** `useMemo` 안정화

### 리뷰가 **반증에 실패한 것** (= 원안이 맞다)

- **진입로 4파일 diff 0 — 성립.** `Sidebar.tsx:48` · `commands.ts:59` + `CommandPalette.tsx:156` · `shortcuts.ts:100` · `start-page.ts:65` 넷 다 **새 내비게이션**이라 projectKey 를 지우는 게 아니라 애초에 없는 상태에서 시작. §1 의 A 기각 3근거도 코드로 재확인됨
- **URL 정규화 기각 — 지지.** 정규화를 넣으면 B2 와 결합해 필터 조작마다 URL 재기록이 발생
- **cross-project 조회 제외 — 지지**
- **하드코딩 전수 — 추가 발견 0.** `DEFAULT_PROJECT_KEY` 정확히 4발생, 프로덕션 프로젝트 키 리터럴 0건

---

## Plan v2 (정본)

**변화.** task 8 → **10**, wave 4 → **4**(재배치). 신설 2개(T4 조합 훅 · T5 경로 기록기), 나머지는 RED/GREEN 보강.

### Task 1. 활성 프로젝트 해소 순수 함수

**메타**. agent `frontend-engineer` · files [`apps/web/src/lib/active-project.ts`, `apps/web/src/lib/active-project.test.ts`] · depends-on []

**RED**. `resolveActiveProjectKey({ urlKey, storedKey, projects })` → `{ key, source: 'url'|'stored'|'first'|'none' }`
```
S1 url 우선           ('INFRA', stored 'ATLAS')        → { key:'INFRA', source:'url' }
S2 저장값 폴백         (null, 'INFRA')                  → { key:'INFRA', source:'stored' }
S3 첫 원소            (null, null)                     → { key: projects[0].key, source:'first' }
S6 저장값이 목록에 없음 (null, 'GONE')                   → { key: projects[0].key, source:'first' }
S5 프로젝트 0개        (null, null, [])                 → { key: null, source:'none' }
S7 권한 없는 url       ('NOPERM', 'ATLAS')              → { key:'NOPERM', source:'url' }  ← 조용한 대체 금지
```
**★ 재정렬 금지 (C4).** `projects[0]` 을 그대로 쓴다. 백엔드가 `ORDER BY name ASC`(`ProjectQueryRepository.kt:62`)이고 저장소 관례가 "백엔드 정렬 신뢰, 프론트 재정렬 없음"(`routes/projects.index.tsx:42`)이다. 테스트명도 "이름 오름차순"이 아니라 **"목록의 첫 원소"** 로 쓴다

**GREEN**. 순수 함수. React·스토리지 의존 0. `projects` 는 호출자가 주입(신규 조회 금지)

**REFACTOR**. KDoc 에 4단 순서 + **왜 `source` 를 반환하는가**(호출자가 FR4 저장 여부를 판단하려면 출처를 알아야 한다) + **왜 URL 이 최상위인가**(공유 링크 재현)

**검증**. `node_modules/.bin/vitest run src/lib/active-project.test.ts`

---

### Task 2. localStorage 영속 스토어

**메타**. agent `frontend-engineer` · files [`apps/web/src/hooks/use-active-project.ts`, `apps/web/src/hooks/__tests__/use-active-project.test.ts`] · depends-on []

> N2 — v1 의 `depends-on: [1]` 은 근거가 없었다. 이 태스크는 T1 산출물을 쓰지 않는 순수 스토어다. 제거해 W1 에 합류.

**RED**. `use-sidebar-collapsed.test.ts` 템플릿 — 저장/복원 · `getItem` throw 시 null 폴백(NFR2) · 문자열 아닌 값 → null(E5) · 같은 값 재설정 시 write 생략(E7)

**GREEN**. zustand `create` + fail-safe `readStored`/`writeStored`. 키 `bts.active-project`

**REFACTOR**. KDoc — **§1.18 무관 근거**를 `use-sidebar-collapsed.ts:11-14` 형식으로 (NFR1)

**검증**. `node_modules/.bin/vitest run src/hooks/__tests__/use-active-project.test.ts`

---

### Task 3. router validateSearch — projectKey

**메타**. agent `frontend-engineer` · files [`apps/web/src/router.ts`, `apps/web/src/router.project-key.test.tsx`] · depends-on []

**RED**. `router.split-search.test.tsx` 형식 승계 — `?projectKey=INFRA` 파싱 · 부재 시 undefined · 비-문자열 시 undefined · 기존 7종 무회귀

**GREEN**. `issuesIndexRoute.validateSearch`(`router.ts:126-158`) 반환 타입 + 본문에 1행

**REFACTOR**. 없음

**검증**. `node_modules/.bin/vitest run src/router.project-key.test.tsx src/router.split-search.test.tsx`

---

### Task 4. 조합 훅 `useResolvedActiveProject()` — **신설 (C2)**

**메타**. agent `frontend-engineer` · files [`apps/web/src/hooks/use-resolved-active-project.ts`, `apps/web/src/hooks/__tests__/use-resolved-active-project.test.tsx`] · depends-on [1, 2]

**왜.** v1 은 `/issues` 와 `/search` 가 **같은 wave 에서 병렬로** 동일 조합 로직을 각자 짜게 돼 있었다. 드리프트 확정이고 T8 봉인은 하드코딩 리터럴만 봐서 못 잡는다.

**RED**
```
- useProjects 로딩 중 → { key:null, source:'loading', isLoading:true }
- 해소 결과가 T1 순수 함수와 일치
- source 가 'url' 또는 'first' 면 저장값 갱신, 'stored' 면 write 0회 (FR4/B3/E7)
- 프로젝트 조회 실패 → { key:null, source:'error' } (E2)
- 반환 객체가 useMemo 로 안정화돼 있다 (N3 — 같은 입력이면 참조 동일)
```

**GREEN**. `useProjects()` + `useActiveProject()` + `useSearch({strict:false}).projectKey` + `resolveActiveProjectKey()` 조합. 저장은 `useEffect`(의존성은 **원시값 `key`·`source` 만**)

**REFACTOR**. 반환 타입에 `isLoading`·`isError` 를 포함해 소비처가 조기 반환을 단순화할 수 있게

**검증**. `node_modules/.bin/vitest run src/hooks/__tests__/use-resolved-active-project.test.tsx`

---

### Task 5. 경로 파라미터 기록기 — **신설 (B1)**

**메타**. agent `frontend-engineer` · files [`apps/web/src/hooks/use-track-active-project.ts`, `apps/web/src/components/layout/ShellLayout.tsx`, `apps/web/src/components/layout/__tests__/ShellLayout.test.tsx`] · depends-on [2]

**왜.** **S4 가 성립하는 유일한 자리.** `/projects/$projectKey/*` 를 볼 때 그 키를 저장해야 이후 `/issues` 가 그 프로젝트를 연다. 현재 `useParams().projectKey` 를 읽는 코드는 전부 props 로 내리기만 하고 저장하지 않는다.

**RED**. `ShellLayout.test.tsx`
```
- /projects/INFRA/board 렌더 → 저장값이 'INFRA'
- /issues 렌더 → 저장값 변화 0 (경로 파라미터 없음)
- 같은 프로젝트 재방문 → write 0회 (E7)
- 미인증 분기에서는 기록하지 않는다 (ShellLayout:52-56 passthrough)
```

**GREEN**. `useTrackActiveProject()` = `useParams({strict:false}).projectKey` 관측 → 변화 시 `setActiveProject`. `ShellLayout` **인증 분기에만** 마운트

**REFACTOR**. 훅을 별 파일로 분리해 `ShellLayout` diff 를 1줄로 유지(랜드마크 구조 무변경 — `banner`/`main`/`complementary` e2e 계약)

**검증**. `node_modules/.bin/vitest run src/components/layout/__tests__/ShellLayout.test.tsx` + `e2e/landmark.spec.ts`

---

### Task 6. 이슈 목록 배선 + navigate projectKey 보존

**메타**. agent `frontend-engineer` · files [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`] · depends-on [3, 4]

**RED**
```
- ?projectKey=INFRA → fetchIssues 가 INFRA (S1)
- 저장값 INFRA, URL 없음 → INFRA (S2)
- ★ 필터 변경 후 URL 에 projectKey 가 남는다 (B2)
- ★ 정렬·페이지·selected 변경 후에도 남는다 (B2 회귀 4지점)
- 로딩 중 → fetchIssues 0회 (E1)
- 프로젝트 0개 → EmptyState + fetchIssues 0회 + /projects 링크 (S5)
- 조회 실패 → 에러 + 재시도 (E2)
- ★ ?projectKey=NOPERM 이 403 → 에러 표시 + 저장값 미갱신 (S7/E4, 부정 단언)
- ★ /issues?selected=KEY 에서 프로젝트 로딩 중에도 상세 페인은 산다 (C5)
```
**★ MSW 준비 (C3).** `test/setup.ts:19` 가 `onUnhandledRequest:'error'` 이고 `renderRouteAdapter()`(`issues.index.test.tsx:1367`)에 프로젝트 핸들러가 없다. **RED 착수 전 `GET /api/v1/projects` 핸들러를 harness 에 등록**하지 않으면 SV1~SV9 전량이 원인과 무관한 메시지로 깨진다

**GREEN**. `DEFAULT_PROJECT_KEY` 삭제 · `useResolvedActiveProject()` 소비 · **navigate 5곳 전부 `...prev` 보존**(특히 `handleFilterChange:767-770` + `FilteredEmptyState` 초기화 `:604-607`) · 조기 반환은 **목록 영역 한정**(C5)

**REFACTOR**. `IssueListPage` props 시그니처 **무변경** 확인(G3)

**검증**. `node_modules/.bin/vitest run src/routes/issues.index.test.tsx`

---

### Task 7. 검색 라우트 — 3소비처 흡수

**메타**. agent `frontend-engineer` · files [`apps/web/src/routes/search.tsx`, `apps/web/src/routes/search.test.tsx`] · depends-on [4]

**RED**. `?projectKey=INFRA` 무회귀 · URL 없음 + 저장값 INFRA → INFRA · 프로젝트 0개 → AQL 조회 **0회** · 팔레트 `/search foo` 후에도 projectKey 유지 (C1/B2 동형 — `CommandPalette.tsx:88` 객체형 navigate)
**★ MSW 준비 (C3).** `SearchRouteAdapter` describe 3종(`search.test.tsx:554,590,633`)에 프로젝트 핸들러 등록

**GREEN**. 상수 삭제 · `useResolvedActiveProject()` 소비 · **`SearchRouteAdapter` 조기 반환 허용** — `SearchPage`(`:593`)·`SaveFilterDialog`(`:609`)·`ExportDialog`(`:616`) **3소비처 전부** non-nullable 계약 보존(B4)

**REFACTOR**. `search.tsx:475-479` 우선순위 구조 보존(E8)

**검증**. `node_modules/.bin/vitest run src/routes/search.test.tsx`

---

### Task 8. 회귀 봉인 — 판별식 재정의

**메타**. agent `frontend-engineer` · files [`apps/web/src/__tests__/active-project-contract.test.ts`] · depends-on [6, 7]

**★ v1 폐기 사유 (B5).** 판별식이 `const DEFAULT_PROJECT_KEY = 'ATLAS'` 를 **매치하지 못했다** — 없애려는 대상을 못 잡는 봉인. 뮤테이션도 판별식과 불일치. 스캔 범위 미정의로 `src/mocks/**` 60건+ 에 걸려 영구 RED.

**RED**
```
판별식 (2종 OR)
  /(?:DEFAULT_)?PROJECT_KEY\s*=\s*['"][A-Z][A-Z0-9]{1,9}['"]/     ← 상수 선언형
  /projectKey\s*[:=]\s*['"][A-Z][A-Z0-9]{1,9}['"]/                ← 대입/프로퍼티형

스캔 대상 = 명시적 파일 목록 (button-primitive-usage.test.ts 방식, 글롭 금지)
  src/routes/**, src/components/**, src/hooks/**, src/lib/**, src/api/**
  제외 — src/mocks/**, src/test/**, **/*.test.*, **/__tests__/**

계약 2
  위 목록에 매치 0건
  Sidebar.tsx / commands.ts / shortcuts.ts / lib/start-page.ts 가
  use-active-project·use-resolved-active-project 를 import 하지 않는다 (설계 B 고정)
```

**GREEN**. 테스트만 (프로덕션 변경 0)

**REFACTOR**. **뮤테이션 3종으로 비-공허 증명** — ①`const DEFAULT_PROJECT_KEY = 'ATLAS'` 재삽입 ②`projectKey: 'ATLAS'` 삽입 ③`Sidebar.tsx` 에 import 삽입. **셋 다 fail 해야** 통과. 커밋 후에만 실행([[mutation-test-requires-committed-baseline]])하고 `git checkout` 으로 원복

**검증**. 뮤테이션 3종 fail 확인 → 원복 → green

---

### Task 9. FR-UX-07 등록 — 정본 8종

**메타**. agent `frontend-engineer` · files [`docs/plan/fr-index.md`, `docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/sdd/02-requirements.md`, `CLAUDE.md`, `docs/progress.html`] · depends-on []

**RED**. **선-실패 확인** — FR-UX-07 행만 먼저 넣고 `verify-master-plan.sh` 가 EXIT 4(카운트 drift)를 내는지 본다. 안 내면 verify 가 이 형식을 못 잡는다는 뜻이라 **같은 PR 에서 verify 를 확장**한다(CLAUDE.md §강제)

**GREEN**. 갱신 지점 (2026-07-28 실측 — 리뷰가 라인 정확성 확인)
| 파일 | 지점 |
|---|---|
| `fr-index.md` | `:1` 주석 · `:5` §A.1 헤더 · `:8` 검증 문구 · `:175` `(FR-UX, 6개)`→7 · `:184` 뒤 행 추가 · `:247` personalization `13`→14 · `UX-01,04,05,06(4)`→`(5)` · `:249` 합계 · §A.4 이력 |
| `personalization.md` | `:1` L1 · `:5` 소속 FR · `:110` §4 헤더 · 신규 `§4.5 FR-UX-07` + D1~D7 · `:204` 완료 게이트 |
| `README.md` | `:113` BC 행 (FR 수 **+ 진척 열**) · `:117` 합계 · §7 이력 · **`grep -n '[0-9]\{2,\} FR' docs/plan/README.md` 전수** (개수 열거는 눈가리개다 — `:23`·`:129` 산문 2지점이 실제로 남았다) |
| `CHANGELOG.md` | `[Unreleased]` 블록만 — `:17` `**범위**` · `:19` `**상태**`(날짜 도장 포함) · §BC 요약 표 행 |
| `sdd/02-requirements.md` | FR-UX-07 행 (**누락 시 verify EXIT 1**) |
| `CLAUDE.md` | `:10` · `:57` |
| `progress.html` | `node scripts/build-dashboard.mjs` |

**REFACTOR**. `docs/plans`·`docs/decisions` 는 verify 스캔 대상 아님

**검증**. `bash scripts/verify-master-plan.sh` **EXIT 0** + `139/139`
> 착수 시점 이 칸은 `132/132` 였다. 2026-07-29 결정 5(기능 단위 분할)로 최종 목표치가 바뀌었다.

---

### Task 10. E2E — S1~S8 전수

**메타**. agent `qa-engineer` · files [`apps/web/e2e/active-project.spec.ts`] · depends-on [5, 6, 7]

**RED**. **S1~S8 전수 + NFR5** (v1 은 S6·S7·S8 이 없었다 — C6)
```
S1 ?projectKey=INFRA → INFRA + 저장 갱신
S2 저장값 폴백 (addInitScript 로 localStorage 선주입)
S3 첫 방문 → 목록 첫 원소 + 저장됨
S4 /projects/INFRA/board → 사이드바 "이슈" → INFRA   ← T5 가 없으면 실패
S5 프로젝트 0개 → 빈 상태 (MSW 시나리오 토글)
S6 저장값 낡음 → 폴백 + 저장값 교정
S7 권한 없는 projectKey → 에러 + 저장값 미갱신
S8 시작 페이지 my_issues → 활성 프로젝트 담당 이슈
NFR5 진입 후 URL 불변 · B2 필터 변경 후 projectKey 잔존
```

**GREEN**. MSW 는 **기존 `@/test/server` 단일 인스턴스**에만 등록([[msw-dual-setupserver-double-dispatch]])

**REFACTOR**. 기존 셀렉터 재사용. **새 `aria-label` 도입 금지**

**검증**. `node_modules/.bin/playwright test e2e/active-project.spec.ts` (**바이너리 직접 호출** — positional 필터 삼킴) + 회귀 `command-palette` · `issue-filter` · `landmark`

---

## Plan v2 메타

- **task 수**. 10 (v1 8 + 신설 2)
- **wave**. 4 — W1 `[T1, T2, T3, T9]` · W2 `[T4, T5]` · W3 `[T6, T7]` · W4 `[T8, T10]`
- **TDD 강제**. yes (T9 문서는 선-실패 확인으로 대체)
- **백엔드 변경**. 0 · 마이그레이션 0 · 신규 npm 0
- **변경 프로덕션 파일**. **8** (신규 4 + 수정 4) — 신규 `lib/active-project.ts` · `hooks/use-active-project.ts` · `hooks/use-resolved-active-project.ts` · `hooks/use-track-active-project.ts` / 수정 `router.ts` · `components/layout/ShellLayout.tsx` · `routes/issues.index.tsx` · `routes/search.tsx`
  > 로드맵 §F1 은 4개로 적었다. 리뷰가 B1(경로 기록기)·C2(조합 훅)를 적발해 **4 → 8**. 세어서 적는다([[spec-stated-count-becomes-blindfold]])
- **diff 0 이어야 하는 파일**. `Sidebar.tsx` · `commands.ts` · `shortcuts.ts` · `lib/start-page.ts` (T8 이 봉인)


## 검증 증거

### T8 봉인 뮤테이션 (비-공허 증명, 2026-07-28)

커밋 후 실행 → 원복 → 기준선 green 확인. 셋 다 **깨져야** 봉인이 실효한다.

| 뮤테이션 | 주입 | 탐지 출력 |
|---|---|---|
| M1 | `issues.index.tsx` 에 `const DEFAULT_PROJECT_KEY = 'ATLAS'` | `routes/issues.index.tsx:49 [P1-상수선언] const DEFAULT_PROJECT_KEY = 'ATLAS'` |
| M2 | `search.tsx` 에 `const fallback = { projectKey: 'ATLAS' }` | `routes/search.tsx:23 [P2-대입] const fallback = { projectKey: 'ATLAS' }` |
| M3 | `Sidebar.tsx` 에 `use-active-project` import | `components/layout/Sidebar.tsx → use-active-project` |

**★ M1 이 리뷰 BLOCKER B5 의 정확한 반증이다** — 초안 판별식(`projectKey` 대입형 하나)은 이 형태를 매치하지 못했다. 판별식을 2종으로 나눈 뒤에야 잡힌다.

### 전체 스위트 — 봉합 전 / 봉합 후

**두 시점을 구분한다.** 「봉합 전」은 게이트 2 코드리뷰 봉합에 착수하기 **직전**(미커밋 diff 포함) 실측이고,
「봉합 후」는 §코드리뷰 결과의 CR1~CR7 봉합을 전부 반영한 뒤의 재측정이다. **한 칸에 뭉치면 어느
시점의 값인지 사라진다** — 아래 폐기된 `526 파일 / 8,215 건` 이 정확히 그렇게 망가진 값이다.

| 항목 | 봉합 전 (2026-07-29, 오케스트레이터 실측) | 봉합 후 |
|---|---|---|
| 유닛 (vitest) | 미커밋 상태 **527 파일 / 8,222 건 전량 통과**. HEAD(커밋된 상태) **527 파일 / 8,219 건** | **528 파일 / 8,236 건 전량 통과** · EXIT 0. 순증 = 파일 +1(`ActiveProjectGate.test.tsx` 신설) · 건수 **+17**(HEAD 대비). 파생 산술 금지 — 이 값은 `node_modules/.bin/vitest run` 출력 절대값이다 |
| E2E (playwright) | 신규 `e2e/active-project.spec.ts` **7/7** · 회귀 **35/35**. 출처는 e2e 커밋 `667526c90` 의 커밋 메시지이며 **코드리뷰 이전 시점**이다 — 봉합 후 재실행 전까지 현재 상태의 증거가 아니다 | `active-project.spec.ts` **13/13 통과 · 2회 연속**(23~24초). 회귀 `landmark`+`command-palette`+`issue-filter` **18/18**. 실행 = 임시 config 로 `baseURL`·`webServer.url` 을 **둘 다 5174 로 덮고** 바이너리 직접 호출 (`reuseExistingServer: !CI` + 5173 하드코딩 2곳이 남의 서버를 조용히 재사용하는 함정 회피). 임시 config 삭제·포트 잔류 0 확인 |
| typecheck | `tsc -p tsconfig.app.json --noEmit` EXIT **0** | EXIT **0** (MSW 목 2파일 변경 후 재측정) |
| eslint | `eslint src` **0 error** (경고 8 — 사전 존재분과 동일) | **0 error** · EXIT 0 (경고 8 — 사전 존재분과 동일, 증감 0) |
| verify-master-plan | EXIT **0** · **132/132** PASS | EXIT **0** · **139/139** PASS — 기능 단위 분할(결정 5) 반영 후 실측. 파이프 없이 `$?` 로 직접 확인했다(`tail` 파이프를 물리면 종료코드가 `tail` 것이 잡혀 EXIT 4 가 0 으로 보인다) |
| `DEFAULT_PROJECT_KEY` | 프로덕션 선언·사용 **0건** (잔존 4건은 전부 설명 주석) | 프로덕션(`apps/web/src`) 선언·사용 **0건** — 잔존 7건 전부 설명 주석·테스트명. `apps/web/e2e/*` 의 11건은 e2e 로컬 상수로 **봉인 스캔 대상 밖**(`SRC_ROOT` 가 `src/` 한정)이다. `issue-filter.spec.ts:20` 이 이 PR 로 **거짓이 된 주석**이라 함께 정정했다 |
| diff 0 계약 | `Sidebar.tsx`·`commands.ts`·`shortcuts.ts`·`start-page.ts` **4파일 전부 유지** | **4파일 전부 유지** — `git diff main...HEAD` · `git diff` 둘 다 공집합. 「라우트가 활성 프로젝트를 해소한다」 설계 덕에 진입로 파일을 하나도 건드리지 않고 결함이 풀렸다 |

> ⚠️ **폐기된 수치 — `526 파일 / 8,215 건`.** 이 값은 자기 커밋(`c4884b6f4`) 시점에도 **틀렸다**.
> `8,215` 이 `7,935 + 280` 이라는 **산술 결과와 정확히 일치**하므로 측정이 아니라 계산으로 적힌 값이다
> (게다가 `vitest.config.ts:17` 이 e2e 를 제외하므로 e2e 태스크는 유닛 수를 바꿀 수 없다).
> 기준선 대비 증분(`기준선 7,935 → +280 신규`) 표기는 **같은 집계법으로 재측정하지 않는 한 쓰지 않는다**
> ([[test-count-baseline-grep-vs-xml]]). 위 표는 절대값만 적는다.

### 구현 중 자체 발견·정정 (5건)

| # | 내용 |
|---|---|
| 1 | T4 fixture `id` 가 UUID 가 아니라 `projectSchema.id` 의 `z.string().uuid()` 에서 Zod parse 실패 → 6건이 원인과 무관하게 깨졌다. RED 가 "모듈 없음" 으로 먼저 터져 가리고 있었다 |
| 2 | T4 에서 `first` 로 해소된 뒤 저장되면 다음 렌더는 `stored` 에서 해소된다 — 출처 전이가 정상이고 그게 저장의 증거다. transient 상태를 단언하던 기대를 정정 |
| 3 | T6 계측 — capture 핸들러를 렌더 뒤에 등록해 최초 조회를 놓쳤다 |
| 4 | T6 계측 — **한 번의 `server.use(a,b,c)` 안에서는 앞선 인자가 우선**(첫 매칭이 이긴다)인데 capture 를 마지막에 둬서 기본 핸들러가 이겼다. 호출 단위로는 나중 `server.use` 가 이기는 것과 **규칙이 반대**다 |
| 5 | T9 — 완료 게이트 줄에 `(14 FR)` 과 `FR-UX-07` 을 같이 써서 verify 가 **사유 없이 EXIT 1**. 판별식은 **`(N FR)` 형식 + 같은 줄 FR ID 토큰** 조합이다 (기존 4줄은 `(FR-AU 10개)` 형태라 무사) |

---

## 코드리뷰 결과 (게이트 2, 2026-07-29, PR #320)

> 이 섹션의 `CR«n»` 은 §리뷰 결과(`:374`)의 plan CONCERN `C«n»` 과 **별개 네임스페이스**다.
> 코드 주석의 접두사 없는 `(C«n»)`·`(N«n»)` 은 전부 plan 리뷰를 가리킨다 (`AP9 (C5)` · `T-RA-9 (N3)`).
> 원본 보고서 소실 — 열거 축은 **`git diff --stat` 의 변경 파일 전수**이고, C 번호는 회수되면 붙이고
> 안 되면 「번호 미상」으로 남긴다 (**개수는 눈가리개**다 — [[orchestrator-instruction-counts-are-blindfolds]]).

| # | 지적 | 봉합 지점 · 가드 |
|---|---|---|
| CR1 | 캐시가 있는데 `isError` 를 먼저 평가해 화면 전체가 에러가 된다 | `use-resolved-active-project.ts` 해소 본문 · `T-RA-10` · `T-RA-12a/b` |
| CR3 | 저장값 생산 지점 2곳 중 `useTrackActiveProject` 에 접근 가능 목록 대조 가드가 없다 | `use-track-active-project.ts` · `T-TR-7` · 파급 `ShellLayout.test.tsx` |
| CR4 | AP9 가 `waitFor(ready)` 라 C5 결함을 되주입해도 통과하는 공허 가드 | `issues.index.test.tsx` AP9 재작성 (영구 pending + 목록 부재 단언) |
| CR6 | `resolveActiveProjectKey` 의 `first` 경로만 빈 키를 무검사로 통과시킨다 | `lib/active-project.ts` · `T-RA-11` + 순수 단위 3종 |
| CR7 | P1 이 `PROJECT_KEY` **접미사**만 잡아 별칭 상수로 우회된다 | `active-project-contract.test.ts` P3 신설 + 한 줄 블록주석 필터 |
| 번호 미상 A | 스펙 E2 「에러 + 재시도」인데 재시도 수단이 없다 | `ActiveProjectGate.tsx` · `routes/search.tsx` — **소비처 2곳 중 1곳만 배선됐던 반쪽 봉합** |
| 번호 미상 B | `CLAUDE.md` 현재 단계 문구가 「전량 완료」로 읽힐 소지 | `CLAUDE.md:10` |

### ★ 가장 값어치 있던 것

1. **반쪽 봉합을 타입으로 닫은 것 (번호 미상 A).** `onRetry?` 가 optional 이라 `/issues` 소비처가
   빠졌는데도 `tsc` · `eslint` · `vitest` 3종이 전부 그린이었다. FR-UX-07 은 27 PR 로드맵이라
   소비처가 늘어난다 — 삼항 복붙으로 막으면 **세 번째 소비처에서 같은 방식으로 다시 빠진다**.
   `retry` 를 error 멤버 **안**에 넣어 호출부가 누락할 수 없게 했다.
   > 인용된 선례 `SlackResultBanner` 는 소비처가 1곳뿐이라 그 관례를 2-소비처·증가 예정 컴포넌트로
   > 이전하면 안 된다 — [[sibling-precedent-validation-placement-depends-on-producer-count]].
2. **미인증 셸이 인증 API 를 때린 것 (CR3 파생).** `useProjects()` 가 훅 본문 최상단이라 `enabled`
   와 무관하게 발사됐고, `ShellLayout` 이 미인증 조기 반환 **앞에서** 호출한다. `_shell` 아래 미인증
   도달 라우트 2곳 중 `/dashboards/shared/$token` 은 파일 주석이 *"인증 훅·인증 store 는 절대
   사용하지 않는다 — 401 자동 refresh 나 로그인 리다이렉트를 유발하면 익명 경로 UX 가 깨진다(EC-11)"*
   로 못박은 경로다. **CR3 가 그 계약을 페이지가 아니라 부모 셸에서 깼다.**
3. **「고쳤다는 증거」가 공허했던 것 (CR1·CR3 가드).** T-RA-10 은 refetch 이전 렌더를 읽어
   결함판으로 되돌려도 통과했고, T-TR-7 의 `waitFor` 는 t=0 에 즉시 통과했다. 즉 **CR1·CR3 회귀를
   잡는 테스트가 저장소에 0건**이었다. [[zero-measurement-means-wrong-discriminant]] 의 정확한 사례.

### ★ 처방 검증에서 내가 틀린 것

*"지적은 채택하고 처방은 검증"* ([[seal-blinds-existing-guard]]). 아래는 **반증 라운드가 죽인 대안**이다.
다시 떠올라도 채택하지 마라.

| 대상 | 죽은 대안 | 반증 |
|---|---|---|
| A(게이트) | `ResolvedActiveProject` 를 통째로 props 로 | `'ready'` 로 게이트를 렌더하는 상태가 표현 가능해져 **기존 타입 가드를 지운다** |
| A(게이트) | `{status:'loading'\|'empty'} \| {status:'error';onRetry}` props 유니온 | 소비처가 넘기는 `activeProject.status` 는 리터럴이 아니라 유니온 *값*이라 **두 소비처가 모두 컴파일 실패** |
| CR3 | `useQueryClient().getQueryData(['projects', false])` 로 읽기 | 구독이 없어 목록 도착 시 effect 가 재실행되지 않아 **T-TR-1 이 깨진다** |
| CR3 | `useResolvedActiveProject` 에도 `enabled:false` | `isPending` 이 영구 true 라 **영구 스피너** |
| CR3 가드 | `server.resetHandlers()` 후 프로젝트 핸들러만 빼고 재등록 | `test/server.ts` 초기 목록에 이미 `...projectHandlers` 가 있어 되돌아온다 — **여전히 handled** |
| CR3 가드 | `render()` 직후 동기 `expect(count).toBe(0)` / `await waitFor(() => expect(count).toBe(0))` | 둘 다 **결함을 되주입해도 통과**한다 (요청은 마운트 effect 이후 마이크로태스크에 나가고, `waitFor` 는 첫 체크에서 즉시 성공) |
| CR1 파생 | 빈 캐시 판정을 `projects.length > 0` 로 | **URL 키 경로(E4/S7)를 함께 죽인다** — `projects=[] && isError` 에서 `?projectKey=ATLAS` 가 화면 전체 에러가 된다 |
| CR7 | 값이 아니라 형태로 (`=\s*['"][A-Z][A-Z0-9]{1,9}['"]`) | 실측 **95히트/40파일**이 전부 정당한 enum·판별자 리터럴 — 화이트리스트가 룰보다 커진다 |
| CR7 | 블록주석 전역 제거 `/\/\*[\s\S]*?\*\//g` | **줄번호가 무너진다** (실측 6줄→4줄). 반드시 줄 안에서만 제거 |
| 문서 | 진척 열에 `◪` 같은 새 글리프 | 검증 주체가 없고 Obsidian 미러·`build-dashboard.mjs` 가 모르는 기호가 는다. `☐` 는 이미 쓰이던 마커다 |
| 문서 | 진척 열에 `13/14` 같은 숫자 | 그 자체가 **"verify 가 못 잡는 새 카운트 표기"** 가 되어 룰을 또 확장해야 한다 |
| 문서 | 룰 E 대상에 `fr-index.md` 추가 | `fr-index.md:265` 의 `issue-tracking 29 FR` 은 **BC 단위 값**이라 총계 대조 시 오탐 EXIT 4 |
| 문서 | `CLAUDE.md:10` 의 `909건 → 910건` 단독 치환 | 날짜 도장(`2026-07-27 실측`)을 옮기지 않으면 **존재하지 않는 측정 기록**이 된다. 그대로 둔다 |

### 반증돼 범위가 늘지 않은 것

- **`ResolvedActiveProject` 에 `isStale: true` 축 추가** — ready/empty 에서도 「목록을 새로 고치지
  못했습니다 · 다시 시도」를 비차단 배너로 띄우면 ready 경로의 무음 실패까지 닫힌다. 소비처 2곳 +
  게이트 + 테스트가 함께 움직여야 해서 **별도 PR**.
- **트래커를 `<ActiveProjectTracker />` null 컴포넌트로 강등** — `useProjects` 시그니처를 안 건드리고
  닫히지만, 이미 커밋된 `useTrackActiveProject(enabled)` 계약과 그 테스트 2벌을 함께 갈아엎어야 해
  변경면이 넓다. **외과적 최소 변경은 `enabled` 게이팅** 쪽이다.
- **하드코딩 전수 재조사** — 추가 발견 0. 게이트 1 리뷰의 결론이 유지된다.

### 이연 (TODOS.md 등재)

| # | 내용 | 근거 |
|---|---|---|
| 1 | **로그아웃이 TanStack Query 캐시를 비우지 않는다.** `authStore.ts` 의 `clearSession` 에 `queryClient.clear()` 히트 0건이라 이전 세션의 프로젝트 목록이 살아 있다 | 이번 PR 의 노출 창(리로드 없는 로그아웃 직후)은 `if (!enabled) return` 이 막지만, **사용자 전환 시 데이터 잔존은 별개의 위험**이다 |
| 2 | **`fr-index.md:265` 의 `issue-tracking 29 FR` 이 실측 37 과 어긋난다** | **선재 드리프트**이며 이 PR 범위 밖이다. 룰 E 대상에 `fr-index.md` 를 넣지 않은 이유이기도 하다 (BC 단위 값이라 총계와 대조하면 오탐) |
| 3 | **스펙 §10 「알려진 한계」에 L5(P3 판별식의 시드 4키 한정) 미등재** | 등재가 저장소 관례이나 `docs/specs/…` 는 이번 봉합의 어느 트랙 파일 목록에도 없었다. 한계 자체는 `active-project-contract.test.ts` 의 P3 정의 아래 주석으로 명시돼 있다 |

### 봉합 비-공허 증명 (뮤테이션)

**실행 완료 (2026-07-29, 커밋 `e2c1c65b4` 직후).** 뮤테이션은 **커밋된 기준선 위에서만** 유효하다
([[mutation-test-requires-committed-baseline]]). 항목마다 **주입 → RED 확인 → 원복**을 자동 러너로
일괄 수행했고, 마지막에 `git status --porcelain` 공집합으로 원복을 확인했다.

**13건 전량 RED — 살아남은 뮤턴트 0.** 아래 표의 결과 칸이 실측이다.

> ★ 1차 러너가 X3·X10·X12 를 빠뜨렸고, 초안은 그것을 「등가 뮤턴트로 흡수됐다」고 적었다.
> **검증 없이 쓴 서술이라 정정한다** — 셋은 서로 다른 가드를 찌르는 독립 뮤턴트이고, 2차 러너로
> 실제 실행해 전부 RED 를 확인했다. 「돌리지 않은 것」을 「돌릴 필요 없는 것」으로 바꿔 적는 것이
> 바로 이 표가 막으려는 실패 양식이다.

| # | 출처 | 주입 | 기대 | 결과 |
|---|---|---|---|---|
| X1 | A(게이트) | `ActiveProjectGate.tsx` 의 `<Button>` 블록 삭제 | `ActiveProjectGate.test.tsx` + AP6 + SA5 **3개 모두 RED** | ✅ RED — 3개 파일 / 3건 |
| X2 | A(게이트) | `issues.index.tsx` 의 `state={activeProject}` 삭제 | **컴파일 에러** (`tsc` EXIT ≠ 0) | ✅ RED — `error TS2741: Property "state" is missing in type "{}"` |
| X3 | A(게이트) | `use-resolved-active-project.ts` 의 `retry: () => void refetch()` → `retry: () => {}` | 훅 `retry` 배선 테스트 + AP6/SA5 RED | ✅ RED — 3개 파일 / 3건 (T-RA-13 · AP6 · SA5) |
| X4 | CR3 | `useProjects(false, { enabled })` 의 `{ enabled }` 인자 제거 | 훅 테스트 **와** `ShellLayout` 미인증 테스트 **둘 다** RED (한쪽만이면 아직 절반이다) | ✅ RED — 2개 파일 / 3건 (T-TR-3b · ShellLayout 미인증 2종). **양쪽 다 빨개졌다** |
| X5 | CR3 | `if (!enabled) return` 삭제 | `T-TR-3` **와** `ShellLayout` 미인증 테스트 | ✅ RED — 2개 파일 / 2건 (T-TR-3a · ShellLayout) |
| X6 | CR3 | `&& raw.length > 0` 삭제 | `T-TR-6` | ✅ RED — 1개 파일 / 1건 (T-TR-6) |
| X7 | CR3 | `const isKnownProject = projects !== undefined` 로 교체 | `T-TR-7` | ✅ RED — 1개 파일 / 1건 (T-TR-7) |
| X8 | CR1 | 해소 순서 되돌리기 (`if (isError)` 를 최상단으로) | `T-RA-10` | ✅ RED — 1개 파일 / 2건 (T-RA-10 외) |
| X9 | CR1 파생 | 빈 캐시 에러 승격 삭제 | `T-RA-12a` | ✅ RED — 1개 파일 / 1건 (T-RA-12a) |
| X10 | CR1 파생 | 빈 캐시 판정을 `projects.length > 0` 로 (죽은 대안) | `T-RA-12b` (URL 키 경로 보호) | ✅ RED — 1개 파일 / 1건 (T-RA-12b). 기각안이 URL 키 경로를 죽인다는 실증 |
| X11 | CR6 | `first` 루프의 `nonEmpty` 되돌리기 | `lib/active-project.test.ts` 1번 | ✅ RED — 1개 파일 / 3건 |
| X12 | CR6 | stored 지점 `nonEmpty` 제거 | `lib/active-project.test.ts` 3번 | ✅ RED — 1개 파일 / 1건 (stored nonEmpty) |
| X13 | CR7 | `/* 설명 */ const DEFAULT_PROJECT_KEY = 'ATLAS'` 주입 (M4) | `[P1-상수선언]` 으로 fail. **M1 은 주석 없는 형태만 덮어 필터 구멍을 통과한다** | ✅ RED — 1개 파일 / 1건 (`[P1-상수선언]` 적발) |

### verify-master-plan 룰 검증 (F9, 트랙 D — 실행 완료)

룰을 추가할 때는 **일부러 위반을 넣어 fail 을 확인**한다 (`CLAUDE.md §강제`). 원복은 git 조작 없이
사전 백업 파일 `cp` 로 했고, 마지막에 `diff -q` 로 4개 파일 전부 원본과 동일함을 확인했다.

| 방향 | 주입 | 기대 | 실측 |
|---|---|---|---|
| 양성 1 | 살아있는 구역에 `131 FR` 잔존 (README `:23`·`:132`, CHANGELOG `:17`) | EXIT 4 | ✅ EXIT 4 · **3건 적발**. 파일명이 `README.md`/`CHANGELOG.md` 로 정확히 보고됨 (`basename` 정정 전에는 전부 `CLAUDE.md` 로 오보고됐다) |
| 양성 2 | personalization 행을 `☑ D단계` 로 되돌림 | EXIT 4 | ✅ EXIT 4 — `README §1 personalization.md 진척 '☑ D단계' — 실제 미완 D 단계 6건` |
| 양성 3 | 미완 0인 BC(`automation`) 행을 `☐ D단계` 로 (**역방향**) | EXIT 4 | ✅ EXIT 4 — `미완 D 단계 0건이므로 '☑ D단계' 여야 한다` |
| 음성 1 | README §7 이력 · CHANGELOG `## [0.1.0]` 동결 블록에 `131 FR` | EXIT 0 | ✅ EXIT 0 (동결 구역 면제 작동) |
| 음성 2 | 정상 문서 편집 (`FR-UX-02` 상호참조 + `132 FR` 표기) | EXIT 0 | ✅ EXIT 0 |
| 최종 | 전부 원복 | EXIT 0 · `132/132` | ✅ EXIT 0 · `PASS. FR ID 132/132` |

> **양성 1·2 는 주입 없이도 재현됐다.** 룰을 추가한 직후 문서를 고치기 **전에** 돌린 첫 실행이
> 이미 EXIT 4 였다 — 즉 이 두 위반은 가설이 아니라 **저장소에 실제로 존재하던 드리프트**다.
> 룰 추가 전 같은 상태에서 verify 는 **EXIT 0** 이었다. 그게 이 룰이 공허하지 않다는 증거다.

### 수정 후 재검증

수치는 §검증 증거 → **전체 스위트 — 봉합 전 / 봉합 후** 표에 적는다. 「봉합 후」 칸은
오케스트레이터가 전 트랙 봉합 완료 후 실측으로 채운다 — **추측으로 채우지 않는다.**

### 미확인 (추측하지 않음)

- **E2E 봉합 후 재실행.** 봉합 전 수치(신규 7/7 · 회귀 35/35)는 **코드리뷰 이전 커밋 메시지**가 출처다.
  실행 레시피는 포트 5174 + `baseURL`·`webServer.url` 을 **둘 다** 덮은 임시 config 다 —
  `playwright.config.ts` 의 `reuseExistingServer: !CI` 때문에 5173 에 무엇이든 떠 있으면 조용히
  재사용되고, 실제로 7/28 에 main 트리 dev 서버가 5173 을 점유한 사고가 있었다.
  `apps/web/test-results/.last-run.json` 은 어떤 스펙이 돌았는지 기록이 없고 gitignore 대상이라
  **증거로 인용하지 않는다**.
  > **위험 수준**. 미확인이지만 고위험은 아니다 — `e2e/active-project.spec.ts` 의 대기가 `expect.poll`
  > 이라 CR3 의 지연 기록을 이미 흡수하고, `ZETA` 가 단일 정본 시드(`project-handlers.ts`)에 있다.
