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

## 도메인 정리

- **BC**. 논리 = `personalization` / 물리 = `apps/web` (프론트 전용). FR-UX-04 ADR §D2 의
  "논리 ≠ 물리" 패턴 승계 — fr-index BC 매핑·카운트 불변.
- **영향 모듈**. `commands.ts`(FR-UX-04 소유, 경계 상대) · `CommandPalette.tsx` ·
  **신규** 판별 레이어 + **신규** `lib/aql-text-query.ts`(중립) · `mocks/search-handlers.ts`
- **소비하는 기존 자산 (신규 의존성 0)**. `useDebounce`(250ms 선례 `LabelAutocompleteInput`,
  cmdk 조합까지 동일) · `useResolvedActiveProject`(판별 유니온 `loading`/`error`/`empty`/`ready`) ·
  `fetchIssue(key)` · `searchAql({projectKey,query,page,size})` · `components/ui/command.tsx`(소비처 0→1)
- **새 용어**. 없음 — glossary 신규 항목 0. 기존 「활성 프로젝트」(4단 해소 함수) 를 그대로 소비한다.
- **기존 결정 충돌**. 없음. FR-UX-04 ADR D1(프론트 전용)·D3(명령 3종 네비게이션)을 **침범하지 않는
  방향**으로 경계를 그었다(아래 D-1).
- **정본 drift 1건 (본 PR 에서 정정)**. FR-UX-04 ADR **D3** 이 `/search <질의>` → `/issues?q=` 라고
  적었으나 실제는 `/search?q=` (`router.ts:534,544`). ADR 에 정정 각주를 단다.
- **관련 ADR (신규 생성)**.
  [docs/decisions/2026-08-04-fr-ux-12-f4-command-palette-search.md](../decisions/2026-08-04-fr-ux-12-f4-command-palette-search.md)

### ★ 실측이 밝힌 선재 결함 — `/search <질의>` 는 실서버에서 깨진다

`runCommand:88` → `navigate({to:'/search', search:{q}})` · `search.tsx:311` 이 그 `q` 를 **가공 없이**
`searchAql({query: q})` 에 넘긴다. 프론트 전수 grep 결과 `text ~` **래핑 코드 0건**.
백엔드 `AqlParser.parseComparison:166` 이 `expectIdent("필드명")` → 연산자를 요구하므로 `로그인 버그` 는
`AqlSyntaxException`. 통과해도 `로그인` 이 `MVP_FIELDS`(`status·label·summary·priority·text`)에 없어
`SEARCH_UNKNOWN_FIELD`.

**가짜 그린의 정체.** `mocks/search-handlers.ts:48` 의 MSW 핸들러가 **쿼리를 읽지 않고** localStorage
시나리오 플래그로만 분기해 기본 3건을 반환한다(주석에도 *"쿼리 문자열 무관 고정 3건"*). 그래서
`command-palette.spec.ts:162` S4 가 결과 3건을 단언하며 통과한다. 유닛(`CommandPalette.test.tsx:68`)은
`navigate` 호출 인자만 검증해 더더욱 못 잡는다. 사용법 힌트(`CommandPalette.tsx:18`)는
**`예: /search 로그인 버그`** 라며 깨지는 입력을 광고 중이다.

### ADR 결정 5건 (상세는 ADR 본문)

| # | 결정 | 출처 |
|---|---|---|
| **D-1** | 판별은 **별도 레이어**. `ParsedCommand` 6갈래 동결, `not-command` 일 때만 2차 호출 | Maxi 확정 |
| **D-2** | 자유텍스트→AQL 래핑은 **제3의 중립 모듈** `lib/aql-text-query.ts` 소유 (이스케이프 포함) | **합산 되짚기 산물** |
| **D-3** | 선재 결함 `/search <질의>` 를 **같은 PR 에서 봉합** + MSW 핸들러를 진짜 증인으로 교체 | Maxi 확정 |
| **D-4** | 활성 프로젝트 미해소 시 **이슈키는 살리고 자유텍스트만 안내** | Maxi 확정 |
| **D-5** | **순수 판별 / 훅 조회 분리** + 경계 가드 3종(역방향 import 차단 · 유니온 6갈래 동결 · 호출 순서) | **합산 되짚기 산물** |

### ★ 합산 되짚기 — 개별 질문에선 안 보였던 결과 2건

메모리 `split-questions-hide-their-combination`(각 답은 합리적인데 합치면 가드 0개) 절차 적용.

1. **D-1 × D-3 → 역방향 의존 함정.** 별도 레이어(D-1)와 `/search` 봉합(D-3)을 합치면 래핑 함수를
   `commands.ts`(FR-UX-04 소유)와 신규 레이어(FR-UX-12 소유)가 **둘 다** 필요로 한다. 래퍼를 신규
   레이어에 두면 `commands.ts → 신규 레이어` 역방향 의존이 생겨 **D-1 이 그은 경계가 첫날부터
   무너진다**. → 중립 모듈 신설(D-2).
2. **D-1 × D-4 → 순수성 파괴 + 무가드 경계.** 판별 레이어(D-1)는 순수 함수여야 하는데 활성
   프로젝트(D-4)는 **훅**이다. 한 모듈에 넣으면 판별을 훅 없이 단위 테스트할 수 없다.
   또 D-1 의 경계는 **코드에 흔적을 남기지 않아** 다음 편집자가 되돌려도 아무도 못 막는다.
   → 순수/훅 분리 + 경계 가드 3종(D-5).

## 스펙

전체 스펙. [docs/specs/2026-08-04-fr-ux-12-f4-command-palette-search.md](../specs/2026-08-04-fr-ux-12-f4-command-palette-search.md)

핵심 시나리오 3줄 요약.
- 이슈키(`ATLAS-12`, 대소문자 무관)를 치면 그 이슈가 결과 최상단에 뜨고 Enter 로 이동한다
- 자유 텍스트는 250ms 디바운스 후 `text ~ "…"` 를 활성 프로젝트 스코프로 검색해 팔레트 안에
  7건까지 보여주고, 끝의 「모든 결과 보기」가 `/search` 로 같은 질의를 넘긴다
- 활성 프로젝트가 없으면 자유 텍스트만 안내로 막고 이슈키 경로는 계속 살린다

FR 13건 · NFR 6건 · 엣지 12건 · 완료 기준 10항목. **신규 API 0 · 마이그레이션 0 · 영속 상태 0.**

### Maxi 확정 (누적 4건)

| # | 결정 | 단계 |
|---|---|---|
| 1 | 범위 = **F4 만** (F13 은 후속) | 착수 |
| 2 | 판별은 **별도 레이어** — `ParsedCommand` 6갈래 동결 | domain |
| 3 | 선재 결함 `/search <질의>` **같은 PR 봉합** + MSW 핸들러 교체 | domain |
| 4 | 활성 프로젝트 미해소 시 **이슈키는 살리고 자유텍스트만 안내** | domain |
| 5 | `components/ui/command.tsx` **래퍼 전면 채택** (소비처 0→1) | spec |

### Jira 대조 결과 (계약 §1)

**공식 문서 대조 6건** — `J1` 이슈키 즉시 이동(**DC 공식**, Cloud 팔레트 문서엔 미기재) ·
`J2` instant results + View all results(**Cloud 공식**) · `J3` 결과 행 = 키·요약·프로젝트
(**Cloud 공식**) · `J4` 검색 대상 = Summary/Description/any text field(**Cloud 공식**) ·
`J5` 입력 중 팔레트 미개방(**Cloud 공식**, F11 이 이미 구현) · `J6` `/` 의 의미.

**★ 의도적 편차 3건.**
- **X1. `/` 의 의미가 Jira 와 정반대다.** Jira 는 `/` = 「명령이 아니라 항목을 검색한다」는 표시
  (*"The forward slash … indicates you are **not** searching for commands"*), BTS 는 `/` = 슬래시
  명령이다. 뒤집으면 FR-UX-04 ADR D3 · `commands.ts` · 유닛 2파일 · E2E S1~S5 가 동시에 깨진다.
  **사용자 체감 결과는 같다** — 텍스트를 치면 이슈가 나온다. 다른 건 명령 문법뿐이고
  VS Code·Linear·GitHub 도 `/`·`>` 를 명령 접두사로 쓴다.
- **X2. 결과 행에서 프로젝트명 제외** — v1 이 활성 프로젝트 스코프라 전 행이 같은 값. 소음.
- **X3. 이슈키 즉시매칭의 근거를 Jira Cloud 로 주장하지 않는다** — J1 은 DC 문서에만 있다.
  채택 근거는 정본 §4.10 명시 + 조작 효율이다.

## Brainstorming Check

✅ **ui 경량 경로** (Maxi 확정 2026-08-03) — Phase B brainstorming 스킵. `## Jira 대조`(계약 §1) +
즉사 계약(§2) 교차 + `## 시각 검증 기준` 이 sanity check 를 대신한다.

office-hours 는 **"fully formed plan" 경로**로 실행 — Phase 2 수요 검증 6대 질문은 스킵(이미 승인된
유지보수 FR), Phase 3 전제 검증 5건 + Phase 4 대안 3종은 수행. **대안 C(부분 래퍼 채택)는 토큰
실측으로 탈락** — `--accent`(#F1F2F4 회색) ≠ `--bg-selected`(#E9F2FF 파란 tint)라 한 팔레트 안에서
하이라이트 색이 항목마다 갈린다.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
