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

## Plan

> **구현 규율.** `type == ui` **시각 검증 트랙** (`/bts-impl` §타입별 규율) — red-first 순서 강제
> 면제. 대신 각 task 의 `**검증**` 이 ①동반 실행할 기존 E2E ②라이트/다크 눈확인 항목을 명시한다.
> **순수 로직 task(T1·T2·T4·T8)는 시각 변화가 없으므로 통상 TDD(red→green)를 그대로 지킨다.**

### 파일 구조 (분해 결정 고정)

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `src/lib/aql-text-query.ts` | 자유 텍스트 → `text ~ "…"` AQL + 문자열 이스케이프. **순수·무의존** | 신규 |
| `src/components/command-palette/palette-input.ts` | 비-슬래시 입력의 2계층 판별(이슈키 / 자유텍스트 / 빈). **순수·무의존** | 신규 |
| `src/components/command-palette/use-palette-search.ts` | 디바운스 + 이슈키 조회 + AQL 검색 + 활성 프로젝트 게이트. **훅 층** | 신규 |
| `src/components/command-palette/CommandPalette.tsx` | 래퍼 채택 · 결과 렌더 · Enter 분기 · `runCommand` 봉합 | 수정 |
| `src/mocks/search-handlers.ts` | AQL 목이 쿼리를 실제로 검사 (가짜 그린 제거) | 수정 |
| `src/components/command-palette/commands.ts` | **수정 없음** — 경계 가드 1 의 대상 | 불변 |

**왜 판별(T2)과 조회(T3)를 나누나.** ADR D-5. 판별은 순수 함수라 훅 없이 단위 테스트할 수 있어야
하고, 조회는 `useResolvedActiveProject`·`useDebounce`·`useQuery` 를 쓰는 훅이다. 한 파일에 넣으면
판별 테스트가 훅 하네스를 끌고 온다.

---

### Task 1. `lib/aql-text-query.ts` — 자유 텍스트 → AQL 래핑 + 이스케이프

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/aql-text-query.ts`, `apps/web/src/lib/aql-text-query.test.ts`]
- depends-on: []

**RED**. `apps/web/src/lib/aql-text-query.test.ts`

```ts
// 자유 텍스트를 AQL 전문검색 쿼리로 감싸는 순수 함수 테스트 (FR-UX-12 F4 T1)
import { describe, it, expect } from 'vitest'
import { buildTextQuery, escapeAqlString } from './aql-text-query'

describe('escapeAqlString', () => {
  it('큰따옴표를 이스케이프한다', () => {
    expect(escapeAqlString('로그인"버그')).toBe('로그인\\"버그')
  })

  it('역슬래시를 먼저 이스케이프한다 (이중 이스케이프 방지)', () => {
    expect(escapeAqlString('a\\b')).toBe('a\\\\b')
  })

  it('역슬래시와 큰따옴표가 같이 있어도 순서가 어긋나지 않는다', () => {
    expect(escapeAqlString('a\\"b')).toBe('a\\\\\\"b')
  })

  it('특수문자가 없으면 원문 그대로다', () => {
    expect(escapeAqlString('로그인 버그')).toBe('로그인 버그')
  })
})

describe('buildTextQuery', () => {
  it('자유 텍스트를 text ~ "…" 로 감싼다', () => {
    expect(buildTextQuery('로그인 버그')).toBe('text ~ "로그인 버그"')
  })

  it('앞뒤 공백을 제거한 뒤 감싼다', () => {
    expect(buildTextQuery('  로그인  ')).toBe('text ~ "로그인"')
  })

  it('따옴표가 든 질의도 유효한 AQL 이 된다', () => {
    expect(buildTextQuery('로그인"버그')).toBe('text ~ "로그인\\"버그"')
  })

  it('공백만 있으면 null 을 반환한다 (검색 호출 금지 신호 — E12)', () => {
    expect(buildTextQuery('   ')).toBeNull()
  })

  it('빈 문자열이면 null 을 반환한다', () => {
    expect(buildTextQuery('')).toBeNull()
  })
})
```

**실패 메시지 (예상)**. `Failed to resolve import "./aql-text-query"`

**GREEN**. `apps/web/src/lib/aql-text-query.ts`

```ts
// 자유 텍스트를 AQL 전문검색 쿼리(text ~ "…")로 감싸는 순수 헬퍼 — FR-UX-12 F4 ADR D-2
//
// ★왜 lib/ 인가 (ADR D-2). 소비처가 둘이다 — `runCommand`(슬래시 `/search` 네비게이션,
// CommandPalette.tsx)와 `use-palette-search`(라이브 검색 훅). 훅을 CommandPalette 가
// import 하므로 이 함수를 컴포넌트 파일에 두면 훅→컴포넌트 순환 import 가 된다.
// 두 소비처가 각자 이스케이프하면 drift 가 확정되므로 여기 한 곳에만 둔다.

/**
 * AQL 문자열 리터럴 안에서 특수문자를 이스케이프한다.
 *
 * ★역슬래시를 **먼저** 치환한다. 큰따옴표를 먼저 치환하면 그때 삽입한 역슬래시가
 * 다음 단계에서 다시 이스케이프돼 `\\"` 가 된다(이중 이스케이프).
 *
 * @param raw 원본 문자열
 * @returns AQL 문자열 리터럴에 안전하게 넣을 수 있는 문자열
 */
export function escapeAqlString(raw: string): string {
  return raw.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

/**
 * 자유 텍스트를 AQL 전문검색 쿼리로 변환한다.
 *
 * `text` 는 가상 FTS 필드로 `~` 연산자만 허용한다(FR-SR-04 ADR D4 ·
 * `AqlFields.kt` FIELD_OPERATOR_CONSTRAINTS).
 *
 * @param raw 사용자가 입력한 자유 텍스트
 * @returns AQL 쿼리 문자열. 공백만이거나 비어 있으면 null(검색 호출 금지 신호)
 */
export function buildTextQuery(raw: string): string | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  return `text ~ "${escapeAqlString(trimmed)}"`
}
```

**REFACTOR**. 없음 (10줄 미만, 상수 추출할 것 없음).

**검증**.
- `node_modules/.bin/vitest run src/lib/aql-text-query.test.ts` — 10/10 통과
- 동반 E2E. 없음 (순수 함수, UI 무접촉)
- 눈확인. 없음 (시각 변화 0)

---

### Task 2. `palette-input.ts` — 비-슬래시 입력 2계층 판별

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/palette-input.ts`, `apps/web/src/components/command-palette/palette-input.test.ts`]
- depends-on: []

**RED**. `apps/web/src/components/command-palette/palette-input.test.ts`

```ts
// 팔레트 비-슬래시 입력의 2계층 판별 테스트 (FR-UX-12 F4 T2 · FR1)
import { describe, it, expect } from 'vitest'
import { resolveNonCommandInput } from './palette-input'

describe('resolveNonCommandInput', () => {
  it('이슈키 형식이면 issue-key 로 판별하고 대문자로 정규화한다 (S1·S2)', () => {
    expect(resolveNonCommandInput('ATLAS-12')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-12' })
    expect(resolveNonCommandInput('atlas-12')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-12' })
  })

  it('앞뒤 공백이 있어도 이슈키로 판별한다', () => {
    expect(resolveNonCommandInput('  ATLAS-12  ')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-12' })
  })

  it('프로젝트 키만 있으면 자유 텍스트다 (E2)', () => {
    expect(resolveNonCommandInput('ATLAS-')).toEqual({ kind: 'free-text', query: 'ATLAS-' })
  })

  it('숫자만 있으면 자유 텍스트다 (E3 — DC 의 번호 점프는 미채택)', () => {
    expect(resolveNonCommandInput('12')).toEqual({ kind: 'free-text', query: '12' })
  })

  it('일반 텍스트는 free-text 다 (S4)', () => {
    expect(resolveNonCommandInput('로그인')).toEqual({ kind: 'free-text', query: '로그인' })
  })

  it('공백만이면 empty 다 (E12 — 검색 호출 0)', () => {
    expect(resolveNonCommandInput('   ')).toEqual({ kind: 'empty' })
  })

  it('빈 문자열이면 empty 다', () => {
    expect(resolveNonCommandInput('')).toEqual({ kind: 'empty' })
  })

  it('★슬래시로 시작해도 이 함수는 판단하지 않는다 — 호출부가 순서를 지킨다 (FR2)', () => {
    // 이 함수는 not-command 일 때만 불린다는 계약이다. 방어적으로 슬래시를 되돌려보내지
    // 않는다 — 그러면 판별이 두 곳에 생겨 ADR D-1 의 경계가 무너진다.
    expect(resolveNonCommandInput('/goto ATLAS-1')).toEqual({ kind: 'free-text', query: '/goto ATLAS-1' })
  })
})
```

**실패 메시지 (예상)**. `Failed to resolve import "./palette-input"`

**GREEN**. `apps/web/src/components/command-palette/palette-input.ts`

```ts
// 팔레트 비-슬래시 입력의 2계층 판별 — 이슈키 / 자유텍스트 / 빈 (FR-UX-12 F4 ADR D-1·D-5)
//
// ★이 파일은 순수하다(ADR D-5). 활성 프로젝트·네트워크·훅에 의존하지 않는다 —
// 조회는 use-palette-search 가 맡는다.
// ★commands.ts 를 import 하지 않는다(ADR D-1 경계). 슬래시 판별은 parseCommand 가
// 이미 끝냈고, 이 함수는 그 결과가 'not-command' 일 때만 불린다.

/** 이슈 키 형식 — commands.ts 의 ISSUE_KEY_PATTERN 과 같은 규칙 (대문자 정규화 후 검사) */
const ISSUE_KEY_PATTERN = /^[A-Z][A-Z0-9]*-\d+$/

/**
 * 비-슬래시 팔레트 입력의 판별 결과 — 판별 유니온.
 *
 * - `issue-key`: 이슈 키 형식. 대문자로 정규화된 키를 싣는다
 * - `free-text` : 그 외 검색 가능한 텍스트
 * - `empty`    : 공백만 또는 빈 문자열 — 검색을 호출하지 않는다(E12)
 */
export type PaletteInput =
  | { kind: 'issue-key'; issueKey: string }
  | { kind: 'free-text'; query: string }
  | { kind: 'empty' }

/**
 * `parseCommand` 가 `not-command` 를 반환한 입력을 2계층으로 판별한다.
 *
 * **호출 순서가 계약이다(FR2).** 반드시 `parseCommand` 뒤에 부른다. 먼저 부르면
 * `/goto ATLAS-1` 이 자유 텍스트로 새어 슬래시 명령이 죽는다.
 *
 * @param input 팔레트 입력창의 원본 문자열
 * @returns 판별 유니온
 */
export function resolveNonCommandInput(input: string): PaletteInput {
  const trimmed = input.trim()
  if (trimmed === '') return { kind: 'empty' }

  const upper = trimmed.toUpperCase()
  if (ISSUE_KEY_PATTERN.test(upper)) {
    return { kind: 'issue-key', issueKey: upper }
  }

  return { kind: 'free-text', query: trimmed }
}
```

**REFACTOR**. 없음.

**검증**.
- `node_modules/.bin/vitest run src/components/command-palette/palette-input.test.ts` — 8/8 통과
- 동반 E2E. 없음 (순수 함수)
- 눈확인. 없음

---

### Task 3. `use-palette-search.ts` — 디바운스 + 이슈키 조회 + AQL 검색 + 프로젝트 게이트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/use-palette-search.ts`, `apps/web/src/components/command-palette/use-palette-search.test.tsx`]
- depends-on: [1, 2]

**RED**. `apps/web/src/components/command-palette/use-palette-search.test.tsx`

```tsx
// 팔레트 라이브 검색 훅 테스트 — 디바운스·이슈키·프로젝트 게이트 (FR-UX-12 F4 T3)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { usePaletteSearch } from './use-palette-search'

// 활성 프로젝트 해소를 훅 경계에서 갈아끼운다 — 프로젝트 상태별 분기(FR7)를
// 네트워크 없이 검증하기 위함. 컴포넌트 단위 mock 관례(vi.mock 광범위 금지 메모리).
const mockResolved = vi.fn()
vi.mock('@/hooks/use-resolved-active-project', () => ({
  useResolvedActiveProject: () => mockResolved(),
}))

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  mockResolved.mockReturnValue({ status: 'ready', projectKey: 'ATLAS', source: 'stored' })
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('usePaletteSearch — 자유 텍스트', () => {
  it('디바운스 250ms 전에는 검색하지 않는다 (NFR1)', async () => {
    const { result } = renderHook(() => usePaletteSearch({ kind: 'free-text', query: '로그인' }), { wrapper })
    vi.advanceTimersByTime(200)
    expect(result.current.results).toEqual([])
    expect(result.current.isSearching).toBe(false)
  })

  it('250ms 후 검색 결과가 채워진다 (S4)', async () => {
    const { result } = renderHook(() => usePaletteSearch({ kind: 'free-text', query: '로그인' }), { wrapper })
    vi.advanceTimersByTime(250)
    await waitFor(() => { expect(result.current.results.length).toBeGreaterThan(0) })
  })

  it('결과 상한은 7건이다 (NFR3)', async () => {
    const { result } = renderHook(() => usePaletteSearch({ kind: 'free-text', query: '로그인' }), { wrapper })
    vi.advanceTimersByTime(250)
    await waitFor(() => { expect(result.current.results.length).toBeLessThanOrEqual(7) })
  })
})

describe('usePaletteSearch — 이슈키 (S1·S3·E6)', () => {
  it('이슈키는 디바운스 없이 즉시 조회한다', async () => {
    const { result } = renderHook(() => usePaletteSearch({ kind: 'issue-key', issueKey: 'ATLAS-1' }), { wrapper })
    await waitFor(() => { expect(result.current.issueHit?.key).toBe('ATLAS-1') })
  })

  it('없는 키는 issueHit 이 null 이고 자유 텍스트 결과가 대신 온다 (S3)', async () => {
    const { result } = renderHook(() => usePaletteSearch({ kind: 'issue-key', issueKey: 'ATLAS-99999' }), { wrapper })
    await waitFor(() => { expect(result.current.issueHit).toBeNull() })
  })
})

describe('usePaletteSearch — 활성 프로젝트 게이트 (FR7·S8·E8·E9)', () => {
  it('empty 면 자유 텍스트 검색을 호출하지 않고 needsProject 를 세운다', async () => {
    mockResolved.mockReturnValue({ status: 'empty' })
    const { result } = renderHook(() => usePaletteSearch({ kind: 'free-text', query: '로그인' }), { wrapper })
    vi.advanceTimersByTime(250)
    expect(result.current.needsProject).toBe(true)
    expect(result.current.results).toEqual([])
  })

  it('★empty 여도 이슈키 조회는 계속 동작한다 (ADR D-4 — 프로젝트 무관)', async () => {
    mockResolved.mockReturnValue({ status: 'empty' })
    const { result } = renderHook(() => usePaletteSearch({ kind: 'issue-key', issueKey: 'ATLAS-1' }), { wrapper })
    await waitFor(() => { expect(result.current.issueHit?.key).toBe('ATLAS-1') })
    expect(result.current.needsProject).toBe(false)
  })

  it('loading 이면 검색을 보류한다 (E8)', async () => {
    mockResolved.mockReturnValue({ status: 'loading' })
    const { result } = renderHook(() => usePaletteSearch({ kind: 'free-text', query: '로그인' }), { wrapper })
    vi.advanceTimersByTime(250)
    expect(result.current.results).toEqual([])
  })
})

describe('usePaletteSearch — empty 입력 (E12)', () => {
  it('empty 는 어떤 조회도 하지 않는다', async () => {
    const { result } = renderHook(() => usePaletteSearch({ kind: 'empty' }), { wrapper })
    vi.advanceTimersByTime(250)
    expect(result.current.results).toEqual([])
    expect(result.current.issueHit).toBeNull()
    expect(result.current.needsProject).toBe(false)
  })
})
```

**실패 메시지 (예상)**. `Failed to resolve import "./use-palette-search"`

**GREEN**. `apps/web/src/components/command-palette/use-palette-search.ts`

```ts
// 팔레트 라이브 검색 훅 — 디바운스·이슈키 조회·AQL 검색·활성 프로젝트 게이트 (FR-UX-12 F4 ADR D-5)
import { useSearch } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useDebounce } from '@/hooks/use-debounce'
import { useResolvedActiveProject } from '@/hooks/use-resolved-active-project'
import { fetchIssue } from '@/api/issues'
import { searchAql, type AqlSearchHit } from '@/api/search'
import { buildTextQuery } from '@/lib/aql-text-query'
import type { PaletteInput } from './palette-input'

/** 자유 텍스트 검색 디바운스(ms) — LabelAutocompleteInput 선례와 동일 (NFR1) */
const DEBOUNCE_DELAY_MS = 250

/** 팔레트가 인라인으로 보여줄 결과 상한 — 초과분은 「모든 결과 보기」로 유도 (NFR3) */
const PALETTE_RESULT_LIMIT = 7

/** 팔레트 결과 한 줄 — 키 + 요약 (Jira 대조 J3, 프로젝트명은 의도적 편차 X2 로 제외) */
export interface PaletteResult {
  readonly key: string
  readonly summary: string
}

/** usePaletteSearch 반환값 */
export interface PaletteSearchResult {
  /** 이슈키 즉시매칭 결과. 미일치·404·403 이면 null (S3·E6) */
  readonly issueHit: PaletteResult | null
  /** 자유 텍스트 검색 결과 (최대 PALETTE_RESULT_LIMIT 건) */
  readonly results: readonly PaletteResult[]
  /**
   * 서버가 보고한 **전체** 일치 건수 (`meta.page.totalElements`).
   *
   * ★`results.length` 와 다르다. 팔레트는 7건만 보여주므로 이 값 없이는 사용자가
   * "7건이 전부"라고 오독한다(design 리뷰 2-2). 「모든 결과 보기 (N건)」에 쓴다.
   */
  readonly totalCount: number
  /** 검색 진행 중 여부 */
  readonly isSearching: boolean
  /** 활성 프로젝트 미해소로 자유 텍스트 검색이 불가한 상태 (FR7·S8) */
  readonly needsProject: boolean
  /** 검색 실패 메시지. 없으면 null (FR13) */
  readonly errorMessage: string | null
  /** 「모든 결과 보기」가 넘길 AQL 질의. 자유 텍스트가 아니면 null */
  readonly fullSearchQuery: string | null
}

/**
 * 판별된 팔레트 입력으로 이슈키 조회 + 자유 텍스트 검색을 수행한다.
 *
 * **이슈키는 디바운스하지 않는다** — 키는 완성형으로 입력되고 조회가 단건이라
 * 지연이 체감 손해다. 자유 텍스트만 250ms 디바운스한다.
 *
 * **활성 프로젝트 게이트(ADR D-4).** 자유 텍스트 검색은 `projectKey` 가 필수라
 * `ready` 가 아니면 호출하지 않는다. 이슈키 조회는 프로젝트에 의존하지 않으므로
 * 그대로 진행한다.
 *
 * @param input palette-input 의 판별 결과
 * @returns 조회 결과 + 상태
 */
export function usePaletteSearch(input: PaletteInput): PaletteSearchResult {
  const urlProjectKey = (useSearch({ strict: false }) as { projectKey?: string }).projectKey
  const active = useResolvedActiveProject(urlProjectKey)
  const projectKey = active.status === 'ready' ? active.projectKey : null

  const issueKey = input.kind === 'issue-key' ? input.issueKey : null
  const rawQuery = input.kind === 'issue-key' ? input.issueKey : input.kind === 'free-text' ? input.query : ''
  const debouncedQuery = useDebounce(rawQuery, DEBOUNCE_DELAY_MS)
  const aqlQuery = buildTextQuery(debouncedQuery)

  // 이슈키 조회 — 프로젝트 무관(ADR D-4). 404/403 은 "없음"으로 흡수한다(S3·E6):
  // 존재 여부를 노출하지 않고 자유 텍스트 경로가 이어받는다.
  const issueQuery = useQuery({
    queryKey: ['palette-issue', issueKey],
    queryFn: () => fetchIssue(issueKey as string),
    enabled: issueKey !== null,
    retry: false,
    staleTime: 30_000,
  })

  const canSearch = projectKey !== null && aqlQuery !== null && input.kind !== 'empty'
  const searchQuery = useQuery({
    queryKey: ['palette-search', projectKey, aqlQuery],
    queryFn: () => searchAql({ projectKey: projectKey as string, query: aqlQuery as string, page: 0, size: PALETTE_RESULT_LIMIT }),
    enabled: canSearch,
    retry: false,
    staleTime: 30_000,
  })

  const issueHit: PaletteResult | null =
    issueQuery.data !== undefined ? { key: issueQuery.data.key, summary: issueQuery.data.summary } : null

  const results: readonly PaletteResult[] =
    searchQuery.data?.data.map((hit: AqlSearchHit) => ({ key: hit.key, summary: hit.summary })) ?? []

  return {
    issueHit,
    results,
    // ?? 0 은 조회 전/실패 시. results.length 로 대체하지 않는다 — 그러면 7건 상한이
    // 그대로 총계로 보고돼 design 리뷰 2-2 가 지적한 오독을 코드가 만들어낸다.
    totalCount: searchQuery.data?.meta.page.totalElements ?? 0,
    isSearching: searchQuery.isFetching,
    // 이슈키 경로는 프로젝트가 없어도 동작하므로 안내를 띄우지 않는다(ADR D-4)
    needsProject: input.kind === 'free-text' && projectKey === null && active.status !== 'loading',
    errorMessage: searchQuery.error !== null ? '검색에 실패했습니다.' : null,
    fullSearchQuery: aqlQuery,
  }
}
```

**REFACTOR**. `errorMessage` 는 **단문 고정으로 유지한다.** `routes/search.tsx` 의
`resolveErrorMessage`(문법 오류 위치까지 짚는 4갈래 분류)를 끌어오지 않는다 — 팔레트는 질의를
사용자가 직접 쓰는 화면이 아니라 우리가 `text ~ "…"` 로 만들어 보내는 화면이라, 문법 위치 안내가
사용자에게 아무 행동도 지시하지 못한다. 공유 추출도 하지 않는다(소비처가 서로 다른 것을 원한다).

**검증**.
- `node_modules/.bin/vitest run src/components/command-palette/use-palette-search.test.tsx` — 10/10
- 동반 E2E. 없음 (훅 단위. 통합은 T9)
- 눈확인. 없음

---

### Task 4. MSW AQL 핸들러를 진짜 증인으로 교체

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/search-handlers.ts`, `apps/web/src/mocks/search-handlers.test.ts`]
- depends-on: []

**RED**. `apps/web/src/mocks/search-handlers.test.ts`

```ts
// MSW AQL 핸들러가 쿼리를 실제로 검사하는지 검증 — 가짜 그린 제거 (FR-UX-12 F4 T4 · FR11)
import { describe, it, expect } from 'vitest'
import { isSyntacticallyValidAql } from './search-handlers'

describe('isSyntacticallyValidAql — bare 텍스트 거부', () => {
  it('★필드/연산자 없는 bare 텍스트는 무효다 (선재 결함의 정체)', () => {
    expect(isSyntacticallyValidAql('로그인 버그')).toBe(false)
  })

  it('단어 하나만 있어도 무효다', () => {
    expect(isSyntacticallyValidAql('로그인')).toBe(false)
  })

  it('text ~ "…" 는 유효하다', () => {
    expect(isSyntacticallyValidAql('text ~ "로그인 버그"')).toBe(true)
  })

  it('status = open 은 유효하다', () => {
    expect(isSyntacticallyValidAql('status = open')).toBe(true)
  })

  it('priority IN (1, 2) 는 유효하다', () => {
    expect(isSyntacticallyValidAql('priority IN (1, 2)')).toBe(true)
  })

  it('빈 문자열은 무효다', () => {
    expect(isSyntacticallyValidAql('')).toBe(false)
  })
})
```

**실패 메시지 (예상)**. `isSyntacticallyValidAql is not exported`

**GREEN**. `apps/web/src/mocks/search-handlers.ts` — 신규 export + 기본 분기 앞에 삽입

```ts
/**
 * MSW 용 최소 AQL 형태 검사 — **파서 복제가 아니다.**
 *
 * ★왜 필요한가. 이전 핸들러는 쿼리를 아예 읽지 않고 항상 3건을 반환해,
 * `/search 로그인 버그` 가 실서버에서 문법 오류를 내는 **선재 결함을 E2E 가 통과시켰다**
 * (`command-palette.spec.ts` S4). 목이 진실을 말하지 않으면 테스트는 증인이 아니다.
 *
 * 백엔드 `AqlParser.parseComparison` 이 `필드 연산자 값` 을 요구하므로, 그 최소 형태를
 * 만족하지 못하는 입력만 걸러낸다. 전체 문법 검증은 백엔드 통합 테스트의 몫이다.
 *
 * @param query AQL 쿼리 문자열
 * @returns 최소 형태(필드 + 연산자)를 만족하면 true
 */
export function isSyntacticallyValidAql(query: string): boolean {
  const trimmed = query.trim()
  if (trimmed === '') return false
  // 식별자 뒤에 비교 연산자(= != ~ < > <= >=) 또는 IN/NOT IN 이 오는가
  return /[A-Za-z_][A-Za-z0-9_]*\s*(=|!=|~|<=|>=|<|>|\bNOT\s+IN\b|\bIN\b)/i.test(trimmed)
}
```

기본 분기(현행 `return HttpResponse.json(DEFAULT_SEARCH_PAGE)`) **직전**에 삽입.

```ts
  // 시나리오 플래그가 없을 때도 쿼리 형태를 검사한다 — 목이 진실을 말하게 한다(FR11)
  const rawBody: unknown = await request.json().catch(() => ({}))
  const query =
    rawBody !== null && typeof rawBody === 'object' && 'query' in rawBody &&
    typeof (rawBody as Record<string, unknown>)['query'] === 'string'
      ? (rawBody as Record<string, string>)['query']
      : ''

  if (!isSyntacticallyValidAql(query)) {
    return HttpResponse.json(
      {
        errorCode: 'SEARCH_SYNTAX_ERROR',
        detail: `Unexpected token in query: "${query}"`,
        position: 0,
        title: 'AQL syntax error',
        status: 400,
        timestamp: new Date().toISOString(),
      },
      { status: 400 },
    )
  }

  // 기본: 정상 3건 결과 반환
  return HttpResponse.json(DEFAULT_SEARCH_PAGE)
```

**REFACTOR**. `syntax-error` 시나리오 분기가 body 를 읽는 코드와 중복되므로, body 읽기를
핸들러 상단으로 한 번만 올린다(`request.json()` 은 1회만 소비 가능 — **중복 호출 시 두 번째가
빈 객체가 되는 실버그**라 반드시 통합한다).

**검증**.
- `node_modules/.bin/vitest run src/mocks/search-handlers.test.ts` — 6/6
- **★비-공허 확인 필수**. 이 핸들러를 켠 상태에서 `command-palette.spec.ts` S4 가 **red 로
  바뀌어야 한다**(T5 봉합 전). red 가 안 나오면 핸들러가 여전히 공허하다는 증거다.
- 동반 E2E. `search.spec.ts` · `saved-filters.spec.ts` — 두 스펙이 쓰는 질의가 유효 AQL 인지
  확인(무효면 그쪽이 red 가 된다. red 면 그 질의가 실서버에서도 깨진다는 뜻이므로 함께 봉합).
- 눈확인. 없음

---

### Task 5. `runCommand` `/search` 선재 결함 봉합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/CommandPalette.tsx`, `apps/web/src/components/command-palette/CommandPalette.test.tsx`]
- depends-on: [1, 4]

**RED**. `CommandPalette.test.tsx` — 기존 `:68` 테스트를 **교체**

```tsx
  it('/search 로그인 버그 입력 후 Enter → text ~ 로 감싼 AQL 로 navigate 한다 (S7 · FR9)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    const input = screen.getByRole('combobox')
    await user.type(input, '/search 로그인 버그')
    await user.keyboard('{Enter}')
    // 봉합 전에는 { q: '로그인 버그' } 였고 그것은 실서버에서 SEARCH_SYNTAX_ERROR 다
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인 버그"' },
    })
  })

  it('/search 에 따옴표가 들어가도 유효한 AQL 이 된다 (E4)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '/search 로그인"버그')
    await user.keyboard('{Enter}')
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인\\"버그"' },
    })
  })
```

**실패 메시지 (예상)**. `expected { q: 'text ~ "로그인 버그"' } but got { q: '로그인 버그' }`

**GREEN**. `CommandPalette.tsx` — `runCommand` 의 `search` 분기만 수정

```ts
  } else if (parsed.kind === 'search') {
    // ★자유 텍스트를 그대로 q 로 보내면 백엔드 AqlParser 가 SEARCH_SYNTAX_ERROR 를 낸다
    // (선재 결함, ADR D-3). text ~ "…" 로 감싸야 유효한 AQL 이다.
    const aql = buildTextQuery(parsed.query)
    if (aql !== null) {
      void navigate({ to: '/search', search: { q: aql } })
    }
  } else if (parsed.kind === 'issue') {
```

import 추가. `import { buildTextQuery } from '@/lib/aql-text-query'`

**REFACTOR**. `COMMAND_USAGE_HINTS.search` 의 예시(`/search 로그인 버그`)는 이제 **실제로 동작하므로
그대로 둔다** — 봉합 전엔 깨지는 입력을 광고하고 있었다.

**검증**.
- `node_modules/.bin/vitest run src/components/command-palette/CommandPalette.test.tsx`
- **동반 E2E (필수)**. `node_modules/.bin/playwright test e2e/command-palette.spec.ts` — T4 에서 red 로
  바뀐 S4 가 다시 green 이 되어야 한다. **이것이 봉합의 증인이다.**
- 눈확인. 없음 (라우팅 인자만 변경)

---

### Task 6. `components/ui/command.tsx` 래퍼 전면 채택 (시각 변경)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/CommandPalette.tsx`, `apps/web/src/components/command-palette/CommandPalette.test.tsx`]
- depends-on: [5]

**동반 테스트 (시각 검증 트랙 — red-first 면제)**. `CommandPalette.test.tsx` 에 추가

```tsx
  it('입력창이 combobox 로, 바로가기가 option 으로 노출된다 (NFR5 무회귀)', () => {
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '내 이슈', exact: true })).toBeInTheDocument()
  })

  it('★래퍼 채택 후에도 팔레트 안에 「검색」 접근성 이름이 하나뿐이다 (즉사 계약)', () => {
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    // command-palette.spec.ts:259 가 exact:true 로 이 이름을 잡는다. 래퍼 CommandInput 이
    // 추가하는 돋보기 아이콘이 접근성 이름을 만들면 strict mode 로 즉사한다.
    expect(screen.getAllByRole('option', { name: '검색', exact: true })).toHaveLength(1)
  })
```

**구현**. `CommandPrimitive.Input`/`List`/`Group`/`Item` → 래퍼로 교체.
`CommandPrimitive.Dialog` 는 **유지**한다 — 래퍼에 `CommandDialog` 가 없다(파일 L1 주석이
"소비처 몫이라 제외"라고 명시).

- `CommandPrimitive.Input` → `CommandInput` (돋보기 아이콘 + 래핑 div 동반)
- `CommandPrimitive.List` → `CommandList` (`max-h-80` → 래퍼 기본 `max-h-[300px]`)
- `CommandPrimitive.Group` → `CommandGroup` (`GROUP_HEADING_CLASS` 상수 **삭제** — 래퍼가 동일 스타일 내장)
- `CommandPrimitive.Item` → `CommandItem` (`ITEM_CLASS` 상수 **삭제** — 하이라이트가
  `bg-accent`(#F1F2F4 회색) → `bg-(--bg-selected)`(라이트 #E9F2FF / 다크 #082145)로 바뀐다)

**REFACTOR**. `GROUP_HEADING_CLASS`·`ITEM_CLASS` 가 고아가 되므로 삭제한다
(내 변경이 만든 고아만 정리 — 글로벌 §3).

**검증**.
- `node_modules/.bin/vitest run src/components/command-palette/`
- **동반 E2E (필수)**. `command-palette.spec.ts` · `keyboard-shortcuts.spec.ts` ·
  `detail-action-shortcuts.spec.ts` — 3파일 모두 팔레트 표면을 건드린다(계약 §5 사전 grep).
  특히 `command-palette.spec.ts:259` 바로가기 4개/순서 단언.
- **눈확인 (라이트/다크 양쪽, 계약 §6)**.
  1. 빈 입력 — 바로가기 4개 + 명령 힌트 3개의 순서·간격이 그대로인가
  2. **하이라이트 색이 회색에서 파란 tint 로 바뀐 것이 의도대로인가** ← 이 task 의 핵심 위험
  3. 돋보기 아이콘이 placeholder 문구를 밀거나 겹치지 않는가
  4. 목록 최대 높이 320→300px 이 스크롤을 어색하게 만들지 않는가

---

### Task 7. 결과 렌더 + Enter 분기 + 안내 상태

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/CommandPalette.tsx`, `apps/web/src/components/command-palette/CommandPalette.test.tsx`]
- depends-on: [2, 3, 6]

**동반 테스트**. `CommandPalette.test.tsx` 에 추가

```tsx
  it('이슈키 입력 시 그 이슈가 결과 최상단에 뜨고 Enter 로 이동한다 (S1)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), 'ATLAS-1')
    await screen.findByRole('option', { name: /ATLAS-1/ })
    await user.keyboard('{Enter}')
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/$key', params: { key: 'ATLAS-1' } })
  })

  it('자유 텍스트 입력 시 결과 목록이 뜬다 (S4)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    expect(await screen.findAllByRole('option')).not.toHaveLength(0)
  })

  it('「모든 결과 보기」가 감싼 AQL 로 /search 에 넘긴다 (S5 · FR6)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    await user.click(await screen.findByRole('option', { name: '모든 결과 보기' }))
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/search',
      search: { q: 'text ~ "로그인"' },
    })
  })

  it('결과 0건이면 안내를 표시한다 (FR12 · E11)', async () => {
    server.use(searchAqlEmptyHandler)
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '없는것')
    expect(await screen.findByText('결과가 없습니다.')).toBeInTheDocument()
    // 「모든 결과 보기」는 0건에도 남는다 — 전체 페이지에선 더 나올 수 있다(E11)
    expect(screen.getByRole('option', { name: '모든 결과 보기' })).toBeInTheDocument()
  })

  it('★IME 조합 중 Enter 는 결과를 선택하지 않는다 (NFR4)', async () => {
    // cmdk 루트가 `isComposing || keyCode === 229` 를 가드한다(dist 소스 실측).
    // 우리 코드가 아니라 **라이브러리 동작**이므로, cmdk 업그레이드가 조용히 이걸
    // 없애면 한국어 입력 중 Enter 가 엉뚱한 결과를 연다. 이 테스트가 그 증인이다.
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    const input = screen.getByRole('combobox')
    await user.type(input, '로그인')
    await screen.findAllByRole('option')
    mockNavigate.mockClear()
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true, keyCode: 229 })
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('검색 실패 시 팔레트를 닫지 않고 안내만 표시한다 (FR13)', async () => {
    server.use(searchAqlSyntaxErrorHandler)
    const onOpenChange = vi.fn()
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={onOpenChange} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    expect(await screen.findByText('검색에 실패했습니다.')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('★빈 입력 동작은 무회귀다 — 바로가기 4개와 순서 (즉사 계약 · S6)', () => {
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    const options = screen.getAllByRole('option')
    expect(options.slice(0, 4).map((o) => o.textContent)).toEqual([
      '내 이슈', '검색', '대시보드', '받은 편지함',
    ])
  })
```

**구현**. `CommandPalette.tsx`

- `parsed.kind === 'not-command'` 일 때 `resolveNonCommandInput(inputValue)` 호출 (FR2 순서 준수)
- 그 결과를 `usePaletteSearch` 에 넘긴다
- `showQuickLinks` 조건은 **그대로 유지** (`inputValue === ''`) — 즉사 계약
- 결과 그룹을 `CommandGroup heading="이슈"` 로 렌더. `issueHit` 을 먼저, 그다음 `results`
  (중복 키는 `issueHit` 우선으로 1건만)
- 목록 끝에 `CommandItem` 「모든 결과 보기」 — `fullSearchQuery` 가 null 이 아닐 때만
- `needsProject` → "프로젝트를 먼저 선택하세요." · `errorMessage` → 그대로

**★`CommandEmpty` 를 쓰지 않는다 (cmdk 소스 실측으로 잡은 설계 충돌).**
`CommandEmpty` 는 `filtered.count === 0` 일 때만 렌더한다. 그런데 이 팔레트는
`shouldFilter={false}` 라 cmdk 가 필터를 건너뛰고 `filtered.count = 등록된 아이템 수` 로 둔다
(`dist/index.mjs` `J()`). 「모든 결과 보기」가 항상 렌더되므로 **결과 0건이어도 count 는 1** 이고
`CommandEmpty` 는 **영영 발동하지 않는다** — 넣었으면 죽은 코드였다.
따라서 0건 안내는 기존 `guidanceMessage` 와 같은 방식으로 **직접 렌더**한다
(`<p role="alert">결과가 없습니다.</p>`).
- `handleInputKeyDown` 의 `not-command` early return 을 **유지**한다 — cmdk 가 하이라이트된
  `CommandItem` 의 `onSelect` 를 Enter 로 부르므로 각 항목이 자기 라우팅을 소유한다.
  (직접 Enter 를 가로채면 하이라이트가 어디 있든 첫 항목으로 가는 버그가 된다)

**★신규 접근성 이름 금지**. 「모든 결과 보기」·「이슈」 를 쓰고 **`검색` 은 쓰지 않는다**
(즉사 계약 — `command-palette.spec.ts:259` 가 팔레트 option `'검색'` 을 `exact:true` 로 잡는다).

**REFACTOR**. 렌더 분기가 4갈래(빈/이슈키/자유텍스트/안내)로 늘면 결과 영역을 같은 파일 안
하위 컴포넌트로 추출한다. **파일 분리는 하지 않는다** — 상태를 prop 으로 길게 넘기게 된다.

**검증**.
- `node_modules/.bin/vitest run src/components/command-palette/`
- **동반 E2E (필수)**. `command-palette.spec.ts` · `keyboard-shortcuts.spec.ts` ·
  `detail-action-shortcuts.spec.ts`
- **눈확인 (라이트/다크)**.
  1. 자유 텍스트 입력 중 결과 목록 — 키/요약 정렬, 긴 요약의 줄바꿈·말줄임
  2. 「모든 결과 보기」가 결과와 시각적으로 구분되는가
  3. 결과 0건 · 검색 실패 · 프로젝트 미해소 3가지 안내 상태
  4. 이슈키 결과가 자유 텍스트 결과와 섞이지 않고 최상단인가

---

### Task 8. 경계 가드 3종 (ADR D-5)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/boundary.test.ts`]
- depends-on: [2, 7]

**RED**. `apps/web/src/components/command-palette/boundary.test.ts`

```ts
// ADR D-1/D-5 경계를 기계로 강제하는 가드 — 경계는 코드에 흔적을 남기지 않는다 (FR-UX-12 F4 T8)
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parseCommand } from './commands'
import { resolveNonCommandInput } from './palette-input'

const COMMANDS_SRC = readFileSync(resolve(__dirname, 'commands.ts'), 'utf8')

describe('경계 가드 1 — commands.ts 는 신규 판별 레이어를 import 하지 않는다', () => {
  it('★역방향 의존이 생기면 ADR D-1 의 경계가 무너진다', () => {
    expect(COMMANDS_SRC).not.toMatch(/from\s+['"]\.\/palette-input['"]/)
    expect(COMMANDS_SRC).not.toMatch(/from\s+['"]\.\/use-palette-search['"]/)
  })

  it('commands.ts 는 aql-text-query 도 직접 쓰지 않는다 (래핑은 실행부 책임)', () => {
    expect(COMMANDS_SRC).not.toMatch(/aql-text-query/)
  })
})

describe('경계 가드 2 — ParsedCommand 유니온 6갈래 동결', () => {
  it('★FR-UX-04 ADR D3 이 못박은 레지스트리 범위다. 갈래가 늘면 F4 가 경계를 넘은 것이다', () => {
    const kinds = COMMANDS_SRC.match(/kind:\s*'[a-z-]+'/g) ?? []
    const unique = new Set(kinds.map((k) => k.replace(/kind:\s*'|'/g, '')))
    expect([...unique].sort()).toEqual([
      'goto', 'incomplete', 'issue', 'not-command', 'search', 'unknown',
    ])
  })
})

describe('경계 가드 3 — 호출 순서 계약 (FR2)', () => {
  it('★슬래시 입력은 슬래시 경로가 이긴다 — 이슈키로 새면 /goto 가 죽는다', () => {
    const parsed = parseCommand('/goto ATLAS-1')
    expect(parsed.kind).toBe('goto')
    // 순서를 어겨 먼저 부르면 자유 텍스트가 되어버린다는 사실을 명시적으로 고정한다
    expect(resolveNonCommandInput('/goto ATLAS-1').kind).toBe('free-text')
  })

  it('비-슬래시 이슈키만 issue-key 로 판별된다', () => {
    expect(parseCommand('ATLAS-1').kind).toBe('not-command')
    expect(resolveNonCommandInput('ATLAS-1')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-1' })
  })
})
```

**실패 메시지 (예상)**. 가드 2 는 `palette-input.ts` 의 kind 가 섞여 들어오면 실패 —
`commands.ts` 만 읽으므로 통과해야 한다. 초기 red 는 `boundary.test.ts` 부재.

**GREEN**. 구현 코드 변경 없음 — T2·T7 이 이미 경계를 지켰다면 통과한다.
**통과하지 않으면 그게 경계 위반의 증거**이므로 구현을 고친다(테스트를 고치지 않는다).

**REFACTOR**. 없음.

**★비-공허 확인 (뮤테이션)**. 커밋된 GREEN 상태에서 다음을 각각 넣고 red 를 확인한 뒤
`git checkout --` 로 되돌린다 (메모리 `mutation-test-requires-committed-baseline` — **GREEN 이
먼저 커밋돼 있어야 한다**).
1. `commands.ts` 에 `import './palette-input'` 추가 → 가드 1 red
2. `commands.ts` 의 `ParsedCommand` 에 `| { kind: 'issue-key' }` 추가 → 가드 2 red
3. `palette-input.ts` 에서 슬래시를 되돌려보내는 분기 추가 → 가드 3 red

**검증**.
- `node_modules/.bin/vitest run src/components/command-palette/boundary.test.ts` — 5/5
- 동반 E2E. 없음
- 눈확인. 없음

---

### Task 9. E2E — S1~S8 + 기존 스펙 갱신

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/command-palette.spec.ts`]
- depends-on: [7, 8]

**동반 테스트**. `command-palette.spec.ts` 에 S9~S14 추가 (기존 S1~S5 번호와 충돌 회피)

```ts
  // S9. 이슈키 즉시매칭 — 스펙 S1/S2
  test('S9 ATLAS-1 입력 → 이슈가 결과에 뜨고 Enter 로 이동 (대소문자 무관)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)
    await dialog.getByRole('combobox').fill('atlas-1')
    await expect(dialog.getByRole('option', { name: /ATLAS-1/ })).toBeVisible()
    await page.keyboard.press('Enter')
    await page.waitForURL('**/issues/ATLAS-1')
    await expect(dialog).not.toBeVisible()
  })

  // S10. 자유 텍스트 검색 — 스펙 S4
  test('S10 자유 텍스트 입력 → 디바운스 후 결과 목록', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)
    await dialog.getByRole('combobox').fill('로그인')
    // 250ms 디바운스 + 왕복. Playwright 자동 대기에 맡긴다(고정 sleep 금지)
    await expect(dialog.getByRole('option').first()).toBeVisible()
  })

  // S11. 모든 결과 보기 — 스펙 S5
  test('S11 「모든 결과 보기」 → 감싼 AQL 로 검색 페이지 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)
    await dialog.getByRole('combobox').fill('로그인')
    await dialog.getByRole('option', { name: '모든 결과 보기' }).click()
    await page.waitForURL('**/search**')
    expect(new URL(page.url()).searchParams.get('q')).toBe('text ~ "로그인"')
    await expect(page.getByRole('list', { name: '검색 결과' })).toBeVisible()
  })

  // S12. 키보드 전용 완결 — NFR2
  test('S12 방향키+Enter 만으로 검색 결과를 연다 (마우스 없음)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)
    await dialog.getByRole('combobox').fill('로그인')
    await expect(dialog.getByRole('option').first()).toBeVisible()
    await page.keyboard.press('Enter')
    await page.waitForURL('**/issues/**')
  })

  // S13. 슬래시 경로 무회귀 — 스펙 S7 · FR2
  test('S13 /goto ATLAS-1 은 이슈키 경로로 새지 않는다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)
    await runSlashCommand(dialog, '/goto ATLAS-1')
    await page.waitForURL('**/issues/ATLAS-1')
  })

  // S14. 결과 0건 — FR12 · E11
  test('S14 결과 0건이면 안내가 뜨고 팔레트는 열려 있다', async ({ page }) => {
    await setSearchScenario(page, 'empty')
    await loginAndWaitForRootReady(page)
    const dialog = await openCommandPalette(page)
    await dialog.getByRole('combobox').fill('없는것')
    await expect(dialog.getByText('결과가 없습니다.')).toBeVisible()
    await expect(dialog).toBeVisible()
  })
```

**기존 S4 갱신**. `expect(url.searchParams.get('q')).toBe(query)` →
`toBe('text ~ "로그인 버그"')`. 주석의 *"기본 MSW 핸들러는 쿼리 문자열 무관 고정 3건 반환"* 을
**삭제**한다 — T4 이후 거짓이다.

**REFACTOR**. `setSearchScenario` 헬퍼가 없으면 `search.spec.ts` 의 localStorage 플래그 설정
패턴을 재사용한다(복제하지 말고 공용 헬퍼로 추출).

**검증**.
- `node_modules/.bin/playwright test e2e/command-palette.spec.ts` — **2회 연속** green
  (flaky 판별. 메모리 `flaky-determination-needs-repeat-not-single-contrast`)
- **전체 동반 실행**. `node_modules/.bin/playwright test e2e/` — 회귀 0
- 눈확인. T6·T7 에서 완료

---

## Plan 메타

- task 수: **9**
- 예상 wave: **4** (W1 = T1·T2·T4 병렬 → W2 = T3·T5 → W3 = T6 → T7 → W4 = T8·T9)
  - T5·T6·T7 은 `CommandPalette.tsx` 를 공유하므로 **파일 겹침 자동 직렬화** 대상
- 구현 규율: **ui 시각 검증 트랙** (T6·T7 red-first 면제) + **통상 TDD** (T1·T2·T4·T8 순수 로직)
- 병렬 dispatch: bts-impl 이 `depends-on` + `files` 로 wave 계산
- 추가 검증: `tsc -p tsconfig.app.json --noEmit` · `eslint src` · vitest 전량 · playwright 전량 ·
  `pnpm test:workflow` · `bash scripts/verify-master-plan.sh`
- **worktree 주의**. `pnpm` 래퍼 금지 — `node_modules/.bin/*` 직접 호출
  (메모리 `worktree-pnpm-verify-deps-symlink`)

## 리뷰 결과

### plan-design-review (2026-08-04) — 7패스 전량 실행

**분류.** APP UI (workspace-driven · data-dense · task-focused). 랜딩 규칙 미적용.

**목업 미생성 — 근거.** design 바이너리는 `DESIGN_READY` 였으나 생성하지 않았다. 지시 우선순위상
프로젝트 `CLAUDE.md` 가 gstack 스킬 기본값보다 위이고, `jira-parity-contract.md §3` 이
**"팔레트 스크린샷·블로그는 정본이 아니다, 토큰 값은 DESIGN.md 만 믿는다"** 를 못박는다. 이번
변경은 동결 토큰(`DESIGN.md §2`)과 기존 래퍼 스타일에 갇혀 열린 미적 선택지가 없어, 새로 생성한
목업은 실제 시스템과 다른 그림이 되어 판단을 흐린다.
**outside voices 2종 미실행** — `codex` 미설치 + 이 세션은 Agent 사용 금지. 교차 검증 없음을 명시한다.

| 패스 | 전 | 후 | 요지 |
|---|---|---|---|
| 1. 정보 구조 | 6 | 9 | 이슈키 정확일치 ↔ 검색 결과 그룹 분리 · 영역 다이어그램 신설 |
| 2. 상태 커버리지 | 5 | 9 | **로딩 미명세**(공허한 `isSearching`) · **총 건수 미노출** 봉합 |
| 3. 사용자 여정 | 5 | 9 | **막다른 안내** 봉합 (Maxi 확정 A) |
| 4. AI 슬롭 | 9 | 9 | 지적 1건(긴 요약 오버플로) |
| 5. 디자인 시스템 | 8 | 10 | 액션↔결과 시각 계층 분리 (`CommandSeparator` 소비처 0→1) |
| 6. 반응형·접근성 | **3** | 9 | **DESIGN.md 정본 위반 1건** + 스크린리더 무음 + 375px 미명세 |
| 7. 미결 결정 | — | — | 1건 Maxi 확정, 2건 Jira 대조가 해소, 1건 후속 이연 |

**종합. 5/10 → 9/10.** 미해결 0건.

#### ★ Pass 6 이 최대 구멍이었다 — 정본 위반 1건 포함

- **6-1 (정본 위반).** `DESIGN.md:337` 이 **"모바일 터치 타깃 최소 44px × 44px"** 를 규정하는데
  래퍼 `CommandItem` 은 `py-1.5`(6px) + `text-sm` 이라 **약 30px** 이다. 선례도 명확하다 —
  `FilterBar.tsx:236,258,267,336` · `QuickFilterChips.tsx:187,199,210` 이 전부 `min-h-[44px]`.
  FR-UX-11 F9 에서 Maxi 가 **터치 44px `pointer-coarse`** 를 확정한 것과도 어긋난다.
  → **처방.** 결과 항목에 `pointer-coarse` 미디어 쿼리로 `min-h-[44px]` 적용. 데스크톱 밀도는 유지.
- **6-2 (스크린리더 무음).** 결과가 **비동기로** 채워지는데 도착을 알리는 장치가 없다. cmdk 는
  `aria-activedescendant` 만 준다. FR-UX-10 F11 에서 「`s`/`w` 스크린리더 무음」이 High 로
  지적된 계열의 재발이다. `aria-live` 선례는 레포에 이미 8+파일 존재.
  → **처방.** 결과 개수 변화를 `aria-live="polite"` 로 알린다 (예. "3건 찾음").
- **6-3 (375px 미명세).** 팔레트는 `max-w-lg`(512px) 고정 + `top-[15vh]`. 375px 에서 "키 + 요약"
  한 줄이 어떻게 되는지 계획에 0줄이다. FR-UX-11 F9 후속 항목에 **「375px 붕괴(선재)」** 가 이미
  기록돼 있어 방치하면 같은 자리에서 재발한다.
  → **처방.** 눈확인에 375px 추가 + 요약 1줄 말줄임 명시.

#### Pass 1~5 지적과 처방

- **1-1.** 이슈키 정확일치와 검색 결과가 같은 그룹이면 **"이건 정확히 그거야"** 신호가 사라진다.
  Jira Cloud 공식 문서가 *"Labels separate different types of results"* 라고 명시 —
  **Jira 대조가 이 결정을 해소한다**(Maxi 질문 불필요). → 그룹 2개로 분리.
- **1-2.** 팔레트 영역 구조 다이어그램 부재 → 신설(아래).
- **2-1 (공허한 반환값).** 훅이 `isSearching` 을 반환하는데 **쓰는 곳이 계획에 없다.** 250ms
  디바운스 + 왕복 동안 목록이 빈 채로 남아 "고장났나?" 순간이 생긴다. FR-UX-10/11 이 반복해서
  잡은 **공허 가드** 계열의 사전 차단. → 로딩 표시 명세 + 테스트.
- **2-2 (7건이 전부로 오독).** `searchAql` 응답에 `meta.page.totalElements` 가 있는데 안 쓴다.
  상한 7건만 보이면 사용자는 그게 전부라고 믿는다. → 「모든 결과 보기」에 총 건수 표기.
- **3-1.** 검색 실패 시 다음 행동 부재 → 실패해도 「모든 결과 보기」를 남겨 검색 페이지의 상세
  진단(`resolveErrorMessage` 4갈래)으로 갈 길을 연다.
- **3-2 (막다른 안내 — Maxi 확정 A).** 「프로젝트를 먼저 선택하세요」만 띄우면 팔레트 안에서 할 수
  있는 게 없다. → 안내 밑에 **「프로젝트 선택하러 가기」 항목**(→ `/projects`)을 둔다. 신규 UI 0
  (기존 `QUICK_LINKS` 와 같은 `CommandItem` 재사용). 대안 C(팔레트 안 프로젝트 선택)는 FR-UX-08
  스위처의 책임과 중복이라 기각.
- **4-1.** 긴 요약 오버플로 미명세 → 1줄 말줄임(`truncate`) 명시.
- **5-1.** 「모든 결과 보기」가 결과와 **같은 옷**을 입어 액션인지 결과인지 구분 안 됨.
  래퍼에 `CommandSeparator` 가 이미 있고 **소비처 0** 이다. → 결과와 액션 사이에 삽입(소비처 0→1).

#### 팔레트 영역 구조 (신설 — Pass 1 처방)

```
┌─ CommandPrimitive.Dialog (max-w-lg · top-15vh) ────────────┐
│ CommandInput  🔍 [검색하거나 슬래시 명령…]                    │  ← 항상
├────────────────────────────────────────────────────────────┤
│ CommandList (max-h-300px)                                  │
│                                                            │
│  [빈 입력]           [이슈키 입력]        [자유 텍스트]        │
│  ─────────           ───────────         ────────────       │
│  「바로가기」 4개      「이슈」 1건         「검색 결과」 N건     │
│   내 이슈             ATLAS-12 요약        ATLAS-3 요약       │
│   검색                                     ATLAS-9 요약       │
│   대시보드           「검색 결과」 N건       …(최대 7)          │
│   받은 편지함         (같은 문자열로 검색)                     │
│                                          ── separator ──    │
│  「명령어」 3개                            모든 결과 보기 (N건) │
│   /goto /search /issue                                     │
│                                                            │
│  [안내 상태 — 위 목록 대신]                                   │
│   · 검색 중…                    (isSearching)               │
│   · 결과가 없습니다.             (0건)                        │
│   · 검색에 실패했습니다.          (+ 모든 결과 보기 유지)        │
│   · 프로젝트를 먼저 선택하세요.   (+ 프로젝트 선택하러 가기)      │
└────────────────────────────────────────────────────────────┘
```

**보이는 순서 = 확신의 순서.** 정확일치(이슈키) → 유사일치(검색) → 탈출구(모든 결과/프로젝트).

#### NOT in scope (의도적 이연)

| 항목 | 이연 사유 |
|---|---|
| 팔레트 안에서 프로젝트 전환 | FR-UX-08 스위처가 이미 소유한 책임 — 중복 |
| 결과에 프로젝트명 열 | v1 은 단일 프로젝트 스코프라 전 행 동일 = 소음 (스펙 편차 X2) |
| 최근 본 이슈를 빈 입력에 노출 | 빈 입력 동작은 **즉사 계약**이라 이 PR 에서 건드리지 않는다 |
| 결과 항목 hover 어포던스 | F9 후속(hover 대비 미달)과 같은 자리 — 별건으로 처리 |
| 모션/전환 | APP UI 는 즉시성이 미덕. 팔레트에 모션 0 이 정답 |

#### What already exists (재사용 — 신규 0)

`components/ui/command.tsx` 8종(소비처 0→1, `CommandSeparator` 포함) · `useDebounce`(250ms 선례
`LabelAutocompleteInput`) · `useResolvedActiveProject` + `ActiveProjectGate` · `fetchIssue` ·
`searchAql` · `--bg-selected`/`--text-selected` 토큰 · `min-h-[44px]` 선례(`FilterBar`·
`QuickFilterChips`) · `aria-live` 선례 8+파일 · `QUICK_LINKS` CommandItem 패턴.

### 리뷰가 만든 plan 변경 — Task 7 보강 6건

Task 7 의 구현/테스트에 다음을 **추가**한다 (task 수는 9 유지).

1. **그룹 분리** — 「이슈」(정확일치)와 「검색 결과」를 별도 `CommandGroup` 으로. (1-1)
2. **로딩 표시** — `isSearching` 이 true 면 "검색 중…" 을 렌더. **반환만 하고 안 쓰면 공허하다.** (2-1)
3. **총 건수** — 「모든 결과 보기 (N건)」. `meta.page.totalElements` 소비. 훅 반환에 `totalCount` 추가. (2-2)
4. **탈출구 2종** — 검색 실패 시 「모든 결과 보기」 유지 · 프로젝트 미해소 시 **「프로젝트 선택하러
   가기」**(→`/projects`). (3-1 · 3-2 Maxi 확정 A)
5. **`CommandSeparator`** 를 결과와 액션 사이에 삽입 (소비처 0→1). (5-1)
6. **접근성 3종** — `pointer-coarse` 에서 항목 `min-h-[44px]`(DESIGN.md:337 준수) ·
   결과 개수 `aria-live="polite"` · 요약 `truncate`. (6-1 · 6-2 · 4-1)

추가 테스트 (Task 7 동반).

```tsx
  it('이슈 정확일치와 검색 결과가 다른 그룹에 놓인다 (1-1)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), 'ATLAS-1')
    expect(await screen.findByText('이슈')).toBeInTheDocument()
    expect(await screen.findByText('검색 결과')).toBeInTheDocument()
  })

  it('★검색 중에는 진행 표시가 뜬다 — isSearching 이 공허하지 않다 (2-1)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    expect(await screen.findByText('검색 중…')).toBeInTheDocument()
  })

  it('「모든 결과 보기」가 총 건수를 표기한다 — 7건이 전부로 오독되지 않는다 (2-2)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    // 기본 MSW 는 totalElements=50 — 상한 7 과 다른 수여야 비-공허하다
    expect(await screen.findByRole('option', { name: /모든 결과 보기 \(50건\)/ })).toBeInTheDocument()
  })

  it('프로젝트 미해소 시 「프로젝트 선택하러 가기」로 탈출할 수 있다 (3-2)', async () => {
    mockResolved.mockReturnValue({ status: 'empty' })
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    await user.click(await screen.findByRole('option', { name: '프로젝트 선택하러 가기' }))
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/projects' })
  })

  it('★결과 개수가 스크린리더에 알려진다 (6-2)', async () => {
    const user = userEvent.setup()
    render(<CommandPalette open onOpenChange={vi.fn()} />)
    await user.type(screen.getByRole('combobox'), '로그인')
    const live = await screen.findByText(/건 찾음/)
    expect(live).toHaveAttribute('aria-live', 'polite')
  })
```

Task 7 **눈확인 항목에 추가**. ⑤ **375px 폭**에서 요약이 말줄임되고 가로 스크롤이 생기지 않는가
(F9 후속 「375px 붕괴」 재발 방지) ⑥ 터치 모드에서 항목 높이가 44px 이상인가
⑦ separator 가 결과와 「모든 결과 보기」를 시각적으로 가르는가.

### 남은 위험 (BLOCKER 아님)

- **교차 검증 부재.** codex 미설치 + Agent 금지로 outside voices 2종을 못 돌렸다. 이 리뷰는
  **단일 시각**이다. 게이트 2 의 `/bts-codereview` 가 독립 리뷰를 붙여야 한다 — FR-UX-10/11 에서
  독립 리뷰가 매번 결함을 잡았다(F10 12건 · F8 7건 · F9 7건 · F11 실사용 결함 2건).
- **`totalCount` 추가로 훅 반환 계약이 늘었다.** Task 3 의 `PaletteSearchResult` 에
  `totalCount: number` 를 더한다 — Task 3 이 Task 7 보다 먼저이므로 Task 3 구현 시 반영.
