# Jira 패리티 계약 — BTS UI/UX 영속 정본

> **지위**. BTS 의 모든 UI/UX 작업(신규 화면 · 기존 화면 수정 · 디자인 스펙)이 따르는 영속 규범.
> §1 리서치 절차만은 **T2/T3 전 타입**이 돈다 — 화면이 없어도 사용성을 결정하는 규칙이 있다.
> FR-UX-06(시각 계층) · FR-UX-07~14(인터랙션 계층) 캠페인에서 검증된 계약을 한 곳에 모았다.
> 소진되는 작업 목록은 [`jira-parity-roadmap.md`](jira-parity-roadmap.md), 토큰·프리미티브 카탈로그는
> 루트 [`DESIGN.md`](../../DESIGN.md) — 이 문서는 그 둘이 다루지 않는 **사고 절차와 깨면 안 되는 계약**을 담는다.
>
> **누가 언제 읽나**. `frontend-engineer` · `qa-engineer` 에이전트 정의가 참조하고,
> `/bts-spec`이 **T2/T3 전 타입**의 선행 읽기로 로드하고, T1 UI 는 §1 경량 경로를 쓴다.

## §1. 사고 절차 — Jira Cloud 실물 조회가 먼저다

BTS 의 UI/UX 기준은 **Jira Cloud (2025)** 다. **기억으로 쓰지 않는다.** 새 화면·컴포넌트·인터랙션,
그리고 사용성을 결정하는 도메인 규칙을 제안하기 전에 아래 5단계를 거치고 결과를 스펙의
`## Jira 대조` 표에 남긴다. 강제 수단은 `scripts/workflow/jira-research-guard.test.ts`.

**0. 재사용 먼저 — 같은 표면을 두 번 조사하지 않는다.**

```bash
grep -rln "## Jira 대조" docs/specs/ docs/plans/ | xargs grep -ln "<표면 키워드>"
```

찾으면 그 행을 **출처 URL·조회일 그대로** 승계하고, **이번 변경이 새로 건드리는 조작만** 추가 조회한다.

**1. 실물 조회 — 근거로 인정하는 도메인은 이 표뿐이다.**

| 도메인 | 무엇의 근거인가 |
|---|---|
| `support.atlassian.com` | Cloud 사용자 문서 — 화면·조작 동작의 1순위 |
| `developer.atlassian.com` | REST·스킴·필드 의미론 — 기능 스펙의 1순위 |
| `atlassian.design` | ADS v2 — Jira 대응 화면이 없을 때의 준용 근거 |
| `confluence.atlassian.com` | DC/Server 문서 — **Cloud 가 아님을 행에 표기**해야 인정 |
| `community.atlassian.com` | Atlassian 공식 답변만. 사용자 추측은 근거가 아니다 |

다섯 도메인은 이미 허용돼 있어 추가 승인 없이 조회된다.
**블로그·팔레트 스크린샷·기억은 근거가 아니다** — §3 이 같은 이유로 이미 판정을 내렸다.

**2. 조작감 갭 표.** 그 화면과 BTS 현재 상태의 차이를 **조작 단위**로 나열하고(단축키 · 인라인 편집 ·
기본 탭 · 카드 밀도 · 키보드 항법 · 빈/에러 상태), 각 행에 **원문 인용 + 출처 URL + 조회일 +
Cloud/DC 구분**을 붙인다. 행마다 `J1`·`J2` 번호를 준다 — plan 이 이 번호로 task 를 물린다.

**3. BTS 제약과 교차.** 갭 해소안을 §2 즉사 계약 · §4 재사용 자산과 대조한다 —
계약을 깨는 해소안은 대안을 다시 설계하고, 이미 있는 자산은 새로 만들지 않는다.

**4. 의도적 편차 명시.** Jira 와 다르게 갈 항목에 `X1`·`X2` 번호를 붙이고 근거를 적는다.
**「Jira Cloud 가 그렇게 한다」고 주장하지 않는 것까지** 적는다 — 근거가 DC 문서뿐이면 그 사실을 쓴다.

**5. 대응 화면이 없으면** Jira 를 흉내내지 말고 **ADS(Atlassian Design System) v2 패턴 준용**을
명시한다. 스펙에 「**대응 없음** — ADS `<패턴>` **준용**」 + 사유를 남긴다. 근거 없는 자체 발명은 금지.

**서식 정본.** [`docs/specs/2026-08-04-fr-ux-12-f4-command-palette-search.md`](../specs/2026-08-04-fr-ux-12-f4-command-palette-search.md)
의 `## Jira 대조` — J1~J6 근거 표 + 의도적 편차 X1~X3. 새 대조는 이 형태를 복제한다.

### 비-UI 타입도 대상이다

T2/T3 은 타입과 무관하게 이 절차를 돈다. 백엔드는 화면이 아니라 **기능 스펙**을 조회한다 —
워크플로우 전환 규칙 · 권한 스킴 · JQL 의미론 · 검색 동작처럼 **화면이 없어도 사용성을 결정하는**
것들이다. 대응 개념이 없으면 5단계대로 「대응 없음 — 사유」를 적고 넘어간다.
**생략과 「조회했고 대응이 없었다」는 다른 기록이다** — 앞은 판단 근거가 남지 않고 뒤는 남는다.

### T1 UI 경량 경로

`FE_SRC`·`SHELL` 단독 변경은 **plan 파일이 0개**라(`.claude/skills/bts/SKILL.md` 티어표)
표를 쓸 자리가 없다. 표 대신 **게이트 2 요약에 3줄**을 싣는다.

- `Jira 대응` — 화면/플로우 + 출처 URL + 조회일
- `채택` — 이번 변경이 따르는 Jira 동작 1~2개
- `편차` — 다르게 간 것 + 근거. 없으면 `없음`

대응 화면이 없으면 `Jira 대응: 없음 — ADS <패턴> 준용` 한 줄로 갈음한다.
**이 3줄은 기계가 못 잡는다** — 저장소에 파일이 남지 않아 판별식이 볼 것이 없다. 사람 몫이다.

## §2. 깨면 즉사하는 계약

E2E 스펙 전수 실측 기반. **발생 수는 이 문서에 새기지 않는다** — 개수 리터럴은 stale 해지는
순간 거짓이 된다(실제로 24 → 34 로 이미 이동했다). 각 행의 명령으로 착수 시점에 실측하라.

| 계약 | 실측 명령 | 지켜야 할 것 |
|---|---|---|
| `aria-label` 4종 — `메인 메뉴` · `관리 메뉴` · `프로젝트 뷰 전환` · `검색` | `grep -rn "getByRole('navigation'" apps/web/e2e/` **+** `grep -rn "ADMIN_ENTRY_NAME\|name: '관리 메뉴'" apps/web/e2e/` | 문자열 그대로 보존. **`프로젝트 뷰 전환`을 Radix Tabs 로 바꾸면 `role="navigation"` 이 소멸**해 e2e·유닛이 동시 즉사한다. ★**`관리 메뉴` 만 role 이 `link` 다**(J9 이관) — 그래서 첫 grep 으로는 잡히지 않는다. 실측 명령이 둘인 이유이고, 하나만 돌리면 안 읽는 열이 조용히 썩는다 |
| `<h1>` 단 하나 + 이름 verbatim | `grep -rn "getByRole('heading'" apps/web/e2e/` | h1 글자를 절대 바꾸지 말 것. `PageHeader` 적용은 스타일만 통일 |
| `role="dialog"` 고유 label | `grep -rn "getByRole('dialog'" apps/web/e2e/` | 신규 다이얼로그마다 고유 `aria-label` (strict mode 충돌 방지) |
| 관리 진입점 도달성 ✅ **이관됨 J9** | `grep -rln "관리 메뉴" apps/web/e2e/` · `grep -n "ADMIN_ENTRY_NAME" apps/web/e2e/fixtures/admin-hub.ts` | `관리 메뉴` 는 이제 **상단바 링크**(`TopBar.tsx` → `/admin`)의 이름이다 — 사이드바 `<nav>` 가 아니다. 문자열은 동결이되 조회 role 이 `navigation` → `link` 로 옮겨갔고, 그 전환은 `e2e/fixtures/admin-hub.ts` 한 곳이 흡수한다. ★**`page.goto('/admin')` 으로 바꾸지 마라** — hard navigation 이 MSW Service Worker 를 재초기화해 시드된 store 가 리셋된다(`global-permissions` S1~S5 · `notification-policies` S7 전량 사망). ★상단바 링크에 `max-md:hidden` 금지 — 사이드바에서 없어진 뒤로 **유일한 UI 진입로**라 감추면 관리 화면 전부가 모바일에서 도달 불가가 된다 |
| `검색` 이름 분리 ✅ **구현됨 #341** | `grep -rn "name: '검색'" apps/web/e2e/` · `grep -rn "globalSearch" apps/web/e2e/` | 상단바 입력창은 `전역 검색`(`role="searchbox"`), `검색`은 AQL 페이지 제출 버튼 전용 (Maxi 확정 2026-07-28 결정 4). ★**두 셀렉터에 `exact: true` 필수** — `검색`이 `전역 검색`의 substring 이라 `i18n/__tests__/nav-labels.test.ts` 의 라벨 쌍 판별식이 이 면제를 **`exact: true` 유지 조건으로** 승인했다. 빼면 면제가 무효다 |
| 프로젝트 스위처 nav 금지 | (`프로젝트`가 `프로젝트 뷰 전환`의 substring) | 스위처를 `<nav>`로 만들지 말 것 — popover + `role="listbox"` |
| 팔레트 QUICK_LINKS 순서 | `grep -n "QUICK_LINKS" apps/web/e2e/command-palette.spec.ts` | 빈 입력 시 바로가기 4개와 순서 보존 |
| 단축키 레지스트리 동결 | `grep -n "toHaveLength" apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts` | 기존 `SHORTCUTS` 5종에 손대지 말 것 — 프론트 단언 2곳(`toHaveLength(5)` + `DEFAULT_KEYMAP` `toEqual`) + 백엔드 `KeymapAction` 화이트리스트 + DB CHECK(`V033__user_keymap.sql`)가 **동시에** 깨진다. 컨텍스트 단축키는 별도 레지스트리(`CONTEXT_SHORTCUTS`) 신설 |
| 보드 헤더 `⋯`·스위처는 **컨테이너 스코프**로만 잡는다 ✅ **구현됨 PR ⑧** | `grep -rn "보드 관리\|보드 선택" apps/web/e2e/` · `grep -rn "board-header" apps/web/` | 조회는 반드시 `boardHeader(page)`(= `getByTestId('board-header')`) 를 거친다. 헬퍼 정본은 `apps/web/e2e/fixtures/board-helpers.ts` 한 곳이고, 두 spec 이 각자 복붙하던 것을 그리로 모았다. ★**`data-testid="board-header"` 를 지우거나 이름을 바꾸면 두 spec 이 「실패」가 아니라 `count 0` 으로 조용히 죽는다** — `board-manage.spec.ts` 의 `toHaveCount(0)` 단언이 그것을 그대로 통과시킨다. 그래서 두 spec 에 컨테이너 실재 단언(`await expect(boardHeader(page)).toBeVisible()`)을 짝으로 세워 뒀다. ★**앵커(`^…$`)나 `exact: true` 로 대체하지 마라** — 사이드바 보드 `⋯`(PR ⑨)가 `i18n/board-labels.ts` 의 **같은 `triggerAriaLabel` 헬퍼**를 쓰면 접근성 이름이 바이트 단위로 같아져 앵커로는 못 가른다. ★**정규식은 유지**한다 — 접근성 이름이 `보드 관리, {name}` 이고 `board-manage.spec.ts` S3 가 테스트 중간에 이름을 바꾸므로 `exact` 로 굳히면 rename 단계에서 죽는다 |

상세 이력·근거는 `~/.claude/projects/-Users-maxi-moff-Projects-BTS/memory/` 의
`frontend-nav-aria-label-e2e-contract` 메모리와 `docs/plan/product/personalization.md` §4.4 🛑 경고.

## §3. 시각 기준 — 어디가 정본인가

본문 복제 금지. 아래 포인터의 요지만 기억하라.

- **팔레트 = ADS v2 이식** — [`DESIGN.md`](../../DESIGN.md) §2. 구세대 v1 색(`#0C66E4` 계열의
  출처 논쟁)은 [`fr-ux-06-jira-redesign.md`](fr-ux-06-jira-redesign.md) §5.1~5.3 에서 판정 완료 —
  **팔레트 스크린샷·블로그는 정본이 아니다**, 토큰 값은 DESIGN.md 만 믿는다.
- **사이드바 = Jira Cloud 2025 신형 통합 사이드바** — `fr-ux-06-jira-redesign.md` §3.1 앱 셸.
  pathless `_shell` 라우트 구조. 라우트 이동은 nav+Link, 패널 전환은 Radix Tabs (ADR 확정).
- **프리미티브 24종 레지스트리** — `DESIGN.md` §4 전수 목록. 새 UI 는 여기 있는 것부터 소비.
  radix-ui 직접 import 는 표기 규약을 따른다.
- **동결 토큰** — `DESIGN.md` §2 🔒 · §5 `--font-mono` 🔒. 손대지 않는다.
- **elevation 관례** — 그림자 대신 `ring-1 ring-foreground/10`. floating 요소만 shadow 병용 (`DESIGN.md` §8).
- **상태 매트릭스 7종 · 반응형 4종 · WCAG AA** — `fr-ux-06-jira-redesign.md` §7~§9.

## §4. 새로 만들지 말 것 — 재사용 자산 레지스트리

만들기 전에 소비처를 grep 한다 — `grep -rn "<컴포넌트명>" apps/web/src/`.

| 만들려는 것 | 이미 있는 것 |
|---|---|
| 팔레트/검색 UI | `components/ui/command.tsx` |
| 드롭다운/스위처 | `components/ui/popover.tsx` |
| 이슈 유형 아이콘 | `components/issue/IssueTypeIcon.tsx` — lucide 매핑 + `role="img"` 완비 |
| 이슈 필드 컨트롤 | `components/issue/meta/` 8종 — Assignee·Priority·Labels·Type·Impact·Environment·CustomFields·StateTransition |
| 필터바 | `components/filters/FilterBar.tsx` — 확장 슬롯 4종 (`leadingSection`/`leadingChips`/`extraActiveCount`/`onReset`) |
| 키보드 DnD 공지 | `KanbanBoard.tsx` `buildDragAnnouncements` — 한국어 조사 처리까지 완성 |
| localStorage 영속 훅 | `hooks/use-sidebar-collapsed.ts` — zustand + fail-safe try/catch 템플릿 |
| 모바일 분기 | `hooks/use-media-query.ts` |
| 목록/타입 조회 | `useProjects()` · `useIssueTypes()` · `useFavorites()` |
| 다크 판정 | `lib/theme.ts` `resolveTheme` |
| 빈 상태 | `components/ui/` `EmptyState` 프리미티브 (`DESIGN.md` §4) |
| 프로젝트 요약 집계 | `GET /api/v1/projects/{key}/summary` — 카드 4종 + 분포 4종을 서버가 계산해 준다. 프론트에서 이슈를 끌어와 세지 않는다 |
| 프로젝트 활동 피드 | `GET /api/v1/projects/{key}/activity?limit=` — 변경 그룹 단위. 이슈 단건 changelog 를 N번 부르지 않는다 |

### 요약 화면(J4)의 확정된 집계 규칙

Jira Cloud 실물 확인 결과다. 기억으로 다시 정하지 않는다.

| 무엇 | 규칙 | 근거 |
|---|---|---|
| 상단 카드 4종 | 최근 7일 완료·업데이트·생성 + **향후** 7일 마감 | [summary view](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-summary-view/) (Cloud, 2026-09-03 조회) |
| 「최근 2주」 | **Status overview 의 Done 버킷에만** 적용 — 원문 "Only items that have been completed in the last two weeks will appear in Done" | 같은 문서 |
| 우선순위·유형·담당자 분포 | 기간 제한 **없음**. 활성·가시 이슈 전량 | 같은 문서 + 목업 부제 「최근 7일 활동과 **현재** 작업 분포입니다」 |
| 완료 판정 | 상태 이력의 **DONE 진입 시각**. `updated_at` 이 아니다 — BTS 에 `resolved_at` 컬럼이 없다 | 백엔드 판정 D1 |

## §5. 착수 전 사전 grep — 눈가리개 방지

수정 대상이 건드리는 표면을 **코드가 아니라 테스트에서** 먼저 잰다.

```bash
grep -rn "<수정할 라우트/컴포넌트명>" apps/web/e2e/     # 영향받는 e2e 전수
grep -rn "<수정할 aria-label/문자열>" apps/web/e2e/     # 계약 문자열 노출 지점
grep -rn "<컴포넌트명>" apps/web/src/ --include="*.test.*"  # 유닛 어서션
```

결과가 0 이 아니면 해당 스펙 갱신을 같은 PR 범위로 산정한다. "유닛 전부 초록인데 e2e 만
빨강" 은 mock 이 삼킨 prop 서명이다 — 기존 E2E 동반 실행 없이 UI PR 을 닫지 않는다.

## §6. 브라우저 눈확인 — 생략 금지

FR-UX-06 22 PR 이 시각 변화를 한 번도 브라우저로 확인하지 않고 끝난 것이 프로세스 결함으로
기록돼 있다(`TODOS.md` 회고). **시각/조작 변화가 있는 PR 은 머지 전 실제 브라우저에서 확인한다.**

- 경로. preview 프록시 (커밋 `4e89ca616` 로 실 백엔드 손검증 경로 신설됨)
- 라이트/다크 **양쪽**. 대상 화면의 기본 상태 + 빈/에러 상태
- 확인 결과(스크린샷 또는 관찰 요지)를 PR 본문 또는 게이트 2 요약에 남긴다

## §7. 관련 문서 지도

| 무엇 | 어디 |
|---|---|
| 토큰·프리미티브·타이포·간격 카탈로그 | [`DESIGN.md`](../../DESIGN.md) (루트) |
| Jira 리디자인 설계 정본 (FR-UX-06) | [`docs/design/fr-ux-06-jira-redesign.md`](fr-ux-06-jira-redesign.md) |
| 인터랙션 패리티 로드맵 (FR-UX-07~14 잔여 추적) | [`docs/design/jira-parity-roadmap.md`](jira-parity-roadmap.md) |
| 캠페인 허브 (D 마커·PR 이력) | `docs/plan/product/personalization.md` §4.4~§4.12 |
| ADR 모음 | `docs/decisions/2026-07-17-fr-ux-06-*` · `2026-07-28-fr-ux-07-*` |
