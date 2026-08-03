# Jira 패리티 계약 — BTS UI/UX 영속 정본

> **지위**. BTS 의 모든 UI/UX 작업(신규 화면 · 기존 화면 수정 · 디자인 스펙)이 따르는 영속 규범.
> FR-UX-06(시각 계층) · FR-UX-07~14(인터랙션 계층) 캠페인에서 검증된 계약을 한 곳에 모았다.
> 소진되는 작업 목록은 [`jira-parity-roadmap.md`](jira-parity-roadmap.md), 토큰·프리미티브 카탈로그는
> 루트 [`DESIGN.md`](../../DESIGN.md) — 이 문서는 그 둘이 다루지 않는 **사고 절차와 깨면 안 되는 계약**을 담는다.
>
> **누가 언제 읽나**. `designer` · `frontend-engineer` · `qa-engineer` 에이전트 정의가 참조하고,
> `/bts-spec`이 ui/design 타입 작업의 선행 읽기로 로드한다.

## §1. 사고 절차 — 새 UI 는 Jira Cloud 대조가 먼저다

BTS 의 UI/UX 기준은 **Jira Cloud (2025)** 다. 새 화면·컴포넌트·인터랙션을 제안하기 전에
다음 4단계를 거치고, 결과를 스펙의 `## Jira 대조` 섹션에 남긴다.

1. **대응 화면 식별**. Jira Cloud 에서 같은 일을 하는 화면/플로우를 찾는다
   (보드 · 백로그 · 이슈 상세 · 생성 모달 · Cmd+K · 프로젝트 스위처 · 사이드바 …).
2. **조작감 갭 목록**. 그 화면과 BTS 현재 상태의 차이를 조작 단위로 나열한다
   (단축키 · 인라인 편집 · 기본 탭 · 카드 밀도 · 키보드 항법 · 빈/에러 상태).
3. **BTS 제약과 교차**. 갭 해소안을 §2 즉사 계약 · §4 재사용 자산과 대조한다 —
   계약을 깨는 해소안은 대안을 다시 설계하고, 이미 있는 자산은 새로 만들지 않는다.
4. **대응 화면이 없으면** Jira 를 흉내내지 말고 **ADS(Atlassian Design System) v2 패턴 준용**을
   스펙에 명시한다. 근거 없는 자체 발명은 금지 — 스펙에 "Jira 대응 없음, ADS 준용" 을 남긴다.

## §2. 깨면 즉사하는 계약

E2E 스펙 전수 실측 기반. **발생 수는 이 문서에 새기지 않는다** — 개수 리터럴은 stale 해지는
순간 거짓이 된다(실제로 24 → 34 로 이미 이동했다). 각 행의 명령으로 착수 시점에 실측하라.

| 계약 | 실측 명령 | 지켜야 할 것 |
|---|---|---|
| `aria-label` 4종 — `메인 메뉴` · `관리 메뉴` · `프로젝트 뷰 전환` · `검색` | `grep -rn "getByRole('navigation'" apps/web/e2e/` | 문자열 그대로 보존. **`프로젝트 뷰 전환`을 Radix Tabs 로 바꾸면 `role="navigation"` 이 소멸**해 e2e·유닛이 동시 즉사한다 |
| `<h1>` 단 하나 + 이름 verbatim | `grep -rn "getByRole('heading'" apps/web/e2e/` | h1 글자를 절대 바꾸지 말 것. `PageHeader` 적용은 스타일만 통일 |
| `role="dialog"` 고유 label | `grep -rn "getByRole('dialog'" apps/web/e2e/` | 신규 다이얼로그마다 고유 `aria-label` (strict mode 충돌 방지) |
| 관리 메뉴 기본 펼침 | `grep -rln "관리 메뉴" apps/web/e2e/` | 접으면 webhook·audit-logs·notification-policies 스펙이 not-visible 실패. 모바일 드로어는 **모바일 폭에서만** |
| `검색` 이름 분리 | `grep -rn "name: '검색'" apps/web/e2e/` | 상단바 입력창은 `전역 검색`, `검색`은 AQL 페이지 제출 버튼 전용 (Maxi 확정 2026-07-28 결정 4) |
| 프로젝트 스위처 nav 금지 | (`프로젝트`가 `프로젝트 뷰 전환`의 substring) | 스위처를 `<nav>`로 만들지 말 것 — popover + `role="listbox"` |
| 팔레트 QUICK_LINKS 순서 | `grep -n "QUICK_LINKS" apps/web/e2e/command-palette.spec.ts` | 빈 입력 시 바로가기 4개와 순서 보존 |
| 단축키 레지스트리 동결 | `grep -n "toHaveLength" apps/web/src/lib/shortcuts.test.ts` | 기존 `SHORTCUTS` 5종에 손대지 말 것 — 프론트 단언 + 백엔드 `KeymapAction` 화이트리스트 + DB CHECK 가 동시에 깨진다. 컨텍스트 단축키는 별도 레지스트리(`CONTEXT_SHORTCUTS`) 신설 |

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
