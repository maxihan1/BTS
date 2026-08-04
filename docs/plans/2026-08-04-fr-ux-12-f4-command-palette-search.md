# FR-UX-12 F4 — Cmd+K 커맨드 팔레트 실체 검색

> slug: fr-ux-12-f4-command-palette-search
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-04
> FR: FR-UX-12 (정본 `docs/plan/product/personalization.md §4.10`)

## Brief

**사용자 원문.** `fr-ux-12 진행해줘`

**범위 확정 (Maxi 2026-08-04).** FR-UX-12 승계 PR 2건 중 **F4 만** 이번 PR. F13(상단바 전역 검색
입력창 + 자연어 폴백)은 후속. 로드맵 §PR 체인 실측상 F4·F13 은 의존이 `(F1 ✅)` 뿐이라 **서로
독립**이고 순서 제약이 없다. F4 를 먼저 잡는 근거 — ①팔레트 자유 텍스트가 지금 **화면이 비고
Enter 도 무반응**이라 결손이 더 크다 ②F13 이 건드리는 `navLabels.search` 봉인 단언 9파일을 같은
diff 에 섞지 않아 리뷰 초점이 선명하다 ③FR-UX-09(3PR)·10(2PR)·11(2PR) 3연속 분할 선례.

**D 마커 예상.** D1~D5 `[x]`, **D6/D7 은 `[ ]` 유지** — D6 본문이 「F4 + F13」이라 F13 미착수
동안 닫히지 않는다. FR-UX-10 #336(F10 만 하고 D6/D7 을 연 것) · FR-UX-11 #337 과 동형.

**FR 수 불변 139** 예상 (신규 FR 없음).

### classify 오판 정정

`classify-task.ts` 가 `type=backend` / `agent=backend-engineer` / `primary_bc=issue-tracking` 을
반환했으나 controller 가 **`type=ui` / `frontend-engineer` / `personalization`** 으로 정정했다
(선례 PR #289 동형). 근거는 아래 §착수 전 실측. 정정 기록은 `.bts-cache/classify.json` 의
`classifier_override`.

### 착수 전 실측 — 정본 4건 전량 확인 (뒤집힌 것 0)

최근 5개 FR 연속으로 착수 전 실측이 정본을 뒤집었기에 §4.10 의 주장을 전수 대조했다.
**이번에는 4건 모두 사실이었다.**

| 정본 §4.10 주장 | 실측 | 판정 |
|---|---|---|
| 슬래시 없이 텍스트를 치면 화면이 비고 Enter 도 무반응 (`CommandPalette.tsx:131,165,180`) | `:131` `showQuickLinks = parsed.kind === 'not-command' && inputValue === ''` → 자유 텍스트는 `inputValue !== ''` 라 목록 렌더 0, `guidanceMessage` 도 `not-command` 에서 `null`. `:165` `if (parsed.kind === 'not-command') return` 로 Enter early return, `:180` `shouldFilter={false}` 라 cmdk 기본 목록도 비어 위임처가 없다 | ✅ 사실 |
| `components/ui/command.tsx` 소비처 0 | `grep -rn "ui/command" src/ e2e/` → **0건**. 파일 L1 주석도 「CommandDialog(다이얼로그 결합)는 소비처(CommandPalette) 몫이라 제외」 | ✅ 사실 |
| 전역 검색이 입력창이 아니라 아이콘 버튼 (`TopBar.tsx:57-66`) | 실측 `TopBar.tsx:67-77` — `<Button variant="ghost" size="icon-sm" aria-label={navLabels.search}>` + `<Search className="size-4" />`, `onClick` 은 `navigate({ to: '/search' })` | ✅ 사실 (행 번호만 +10) |
| 진짜 전역 검색은 **3층 차단** | `AqlSearchRequest.kt:36` `projectKey @field:NotBlank` · `SearchController.kt:167` `validateRequest` 가 `projectKey.isBlank()` → 400 · `AqlFields.kt:73` `PLANNED_FIELDS = setOf("assignee", "reporter", "component", "project")` | ✅ 사실 |

### ★ 정본에 없는 신규 함정 1건 — `navLabels.search` 봉인

`src/i18n/nav-labels.ts:42` 의 `search: '검색'` 은 같은 파일 `:6` 에서 **🔒 e2e 계약 문자열**로
봉인돼 있고 소비처가 **유닛 6파일 + e2e 3파일**이다.

- `navigation-contract.test.tsx:115,165` · `ShellLayout.test.tsx:149` 가 **「`'검색'` 버튼이 정확히
  1개」**를 단언한다 → F13 이 아이콘 버튼을 입력창으로 교체하면 이 3개 단언이 동시에 깨진다.
  정본 §4.10 은 "이름표 분리"만 말하고 **이 봉인 단언들을 언급하지 않는다**.
- `command-palette.spec.ts:259` 는 팔레트 안 **option** `'검색'`(QUICK_LINKS 2번째)을 `exact:true`
  로 잡는다 → **본 PR(F4) 의 회귀 가드**. 자유 텍스트 결과를 붙이면서 빈 입력 경로가 죽으면 이
  단언이 깨진다.
- `search.spec.ts:107,109` · `saved-filters.spec.ts:206,238,240` 은 `button name='검색' exact` —
  AQL 페이지 제출 버튼. 정본의 "기존 `검색` 은 AQL 페이지 제출 버튼 전용"과 일치.

**F4 범위 내 영향** — 팔레트 option `'검색'` 1건만. 봉인 단언 3건(버튼 1개)은 **F13 소관**이나,
본 PR 이 팔레트에 새 UI 를 추가하므로 **`'검색'` 텍스트를 새로 만들지 않는지** 확인이 필요하다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
