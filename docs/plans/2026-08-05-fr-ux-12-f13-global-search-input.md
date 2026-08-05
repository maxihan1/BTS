# FR-UX-12 F13 — 상단바 전역 검색 입력창 + 자연어 폴백

> slug: fr-ux-12-f13-global-search-input
> type: ui
> agent: frontend-engineer
> primary_bc: personalization
> FR: FR-UX-12 (D6/D7 을 닫는다)
> 생성: 2026-08-05

## Brief

**사용자 원문.** `/bts FR-UX-12 F13`

**정본 근거.**
- `docs/plan/product/personalization.md:415` — *"F13 — 상단바 전역 검색 입력창 + 자연어 폴백. 지금 전역 검색은 입력창이 아니라 아이콘 버튼이다. `TopBar.tsx:57-66` · `routes/search.tsx` · 신규 `lib/aql-natural.ts`."*
- `docs/plan/product/personalization.md:420` — **`전역 검색` 이름표는 F13 소관** (F4 #340 이 범위 밖으로 명시하고 넘긴 것).
- `docs/plan/product/personalization.md:424` — D6 책임 `designer → frontend-engineer`, **F13 미착수라 `[ ]` 유지**.
- `docs/design/jira-parity-roadmap.md:64` — 의존 `(F1 ✅)` 뿐. 독립 착수 가능.

**classify 정정 (controller).** `backend`/`backend-engineer`/`search-export-import`
→ **`ui`/`frontend-engineer`/`personalization`**. 정본 파일 목록이 전부 `apps/web/` 이고
D6 책임이 frontend-engineer. "자연어 검색" 문자열이 BC 를 오인 견인했다. 선례 PR #289.

**착수 전 실측으로 확인된 최대 걸림돌 — `navLabels.search` 봉인 4곳.**
`apps/web/src/i18n/nav-labels.ts:42` 의 `search: '검색'` 이 e2e 계약 문자열로 봉인(`:6`)돼 있고,
아이콘 버튼을 입력창으로 바꾸면 아래가 동시에 깨진다.

| 파일 | 줄 | 단언 |
|---|---|---|
| `components/layout/__tests__/navigation-contract.test.tsx` | `115` | `'검색'` 버튼 정확히 1개 |
| `components/layout/__tests__/navigation-contract.test.tsx` | `165` | `'검색'` 버튼 정확히 1개 |
| `components/layout/__tests__/ShellLayout.test.tsx` | `149` | `navLabels.search` 버튼 정확히 1개 |
| `components/layout/__tests__/TopBar.test.tsx` | `116` | `'검색'` 버튼 1개 **+ 클릭 시 `/search` 이동** |

정본 §4.10 은 이 단언 4건을 언급하지 않는다 — spec 단계에서 문서 동기화 대상.
`TopBar.test.tsx:116` 은 개수뿐 아니라 **동작(클릭→이동)**까지 단언하므로 개수 조정만으로는 부족하다.

**선행 교훈 (learnings 라우팅 결과).**
- `2026-05-26` E2E 셀렉터는 i18n 정본 import — `navLabels.search` 봉인이 이 처방의 산물.
- `2026-05-31` **UI 변경 PR 은 같은 화면의 기존 E2E 를 반드시 함께 돌려라** — 새 요소가 기존
  전역 셀렉터를 strict mode violation 으로 깬다. F13 이 정확히 이 형상.

## 도메인 정리

**단계 판정 — 스킵** (`type == ui` 기본 스킵, Maxi 확정 2026-08-03). 진입 트리거 3종 전수 대조.

| 트리거 | 실측 | 판정 |
|---|---|---|
| 새 엔티티 | 없음. 프론트 전용(백엔드 0줄·마이그레이션 0) | 미해당 |
| 라우트 신설 | `apps/web/src/routes/search.tsx` **이미 존재**(26KB, 테스트 34KB 동반) | 미해당 |
| 새 용어 | **"자연어 폴백" 1건** — 도메인 엔티티가 아니라 UI 어휘 | 머지 시 glossary 등재 |

- BC: personalization (논리) · `apps/web` (물리). FR-SR-01 "논리≠물리" 선례 승계.
- 영향 엔티티: **없음**.
- 새 용어(등재 대기): **자연어 폴백** — 사용자가 친 자연어를 AQL 로 옮겨 검색을 성립시키는 2차 경로.
  `lib/aql-natural.ts` 소유. F4 가 만든 `lib/aql-text-query.ts`(중립 소유, ADR D-2)의 형제.
- 기존 결정 충돌: **없음.** 아래 §「전역」의 의미 참조.

### ★ 「전역 검색」의 의미 — 선행 결정이 이미 존재한다

F4 ADR(`2026-08-04-fr-ux-12-f4-command-palette-search.md:25`)이 실측으로 기록했다.

> 진짜 전역 검색(프로젝트 무관)은 **3층 차단** — `AqlSearchRequest.kt:36` `projectKey @NotBlank` ·
> `SearchController.kt:167` blank→400 · `AqlFields.kt:73` `PLANNED_FIELDS` 에 `project`·`assignee`.

즉 **이름(`전역 검색`)과 동작(프로젝트 한정)이 어긋나 있다.** 이를 F13 에서 열 것인가.
**열지 않는다** — 뒤집기가 아니라 **선행 결정의 승계**다.

- `glossary.md:90` (활성 프로젝트, FR-UX-07) — *"`/issues` 는 v1 에서 여전히 단일 프로젝트 스코프
  (cross-project 는 로드맵 **B3 후속**, **보안 fail-open 위험**으로 제외)"*.
- `docs/design/jira-parity-roadmap.md:64` — F13 파일 목록에 **백엔드 파일 0개**.

따라서 이 PR 에서 「전역」은 **"어디서나 접근 가능"**(상단바 상시 노출)이지 **"프로젝트 무관"**이
아니다. 이름표 계약(`전역 검색` vs `검색`)은 정본이 F13 소관으로 지정했으므로(`personalization.md:420`)
**스펙 단계에서 못 박는다** — 이름이 없는 범위를 약속하지 않도록.

- 관련 ADR (승계·참조).
  - [2026-08-04-fr-ux-12-f4-command-palette-search.md](../decisions/2026-08-04-fr-ux-12-f4-command-palette-search.md) — 직계 선행(D1~D5). 3층 차단 실측·`전역 검색` 이름표를 F13 으로 이관.
  - [2026-07-04-fr-ux-04-slash-cmd.md](../decisions/2026-07-04-fr-ux-04-slash-cmd.md) — D1 프론트 전용 · D3 명령 3종 동결. 자연어는 명령이 아니다.
  - [2026-07-28-fr-ux-07-active-project-context.md](../decisions/2026-07-28-fr-ux-07-active-project-context.md) — 활성 프로젝트 4단 해소. 검색 스코프의 출처.
- 신규 ADR 후보: **`전역 검색` 이름표 계약 + 자연어 폴백 판별 경계** (스펙 단계에서 확정).

## 스펙

전체 스펙. [docs/specs/2026-08-05-fr-ux-12-f13-global-search-input.md](../specs/2026-08-05-fr-ux-12-f13-global-search-input.md)

**핵심 3줄.**
- 상단바 아이콘 버튼을 `role="searchbox"` + 접근성 이름 **`전역 검색`** 입력창으로 교체한다.
- 입력을 **3갈래**로만 가른다 — 이슈키는 그 이슈로 즉시, AQL 문법은 원문 통과, 나머지는 `text ~` 로 감싸 검색.
- `검색` 이라는 이름은 **AQL 페이지 제출 버튼 전용**으로 남는다. 이 이름 분리가 유닛 4곳 + E2E 5파일을 같은 PR 에서 끌고 온다.

**Maxi 확정 3건.**

| # | 결정 | 요지 |
|---|---|---|
| M1 | **`/` 단축키 현행 유지** | 「Jira 도 `/` 로 검색창을 포커스한다」는 내 초기 근거가 **실측으로 거짓 판정**. Jira 의 진입은 Cmd+K 이고 `/` 는 팔레트 **내부** 모드 전환자다. F4 가 Cmd+K 를 이미 닫았으므로 `/` 까지 돌리면 경로가 셋이 되고 동결 `SHORTCUTS` 를 건드린다 |
| M2 | **자연어 폴백 = 3갈래 판별만** | 키워드 매핑은 목록에 없는 말이 나오는 순간 신뢰가 깎이고 목록 확장 압력을 상시로 받는다 |
| M3 | 「전역」 = 어디서나 접근 | 프로젝트 무관 아님. cross-project 는 B3 후속(보안 fail-open) |

**★ 스펙 단계가 캔 선재 결함 1건 (FR9).** `ISSUE_KEY_PATTERN` 이 이미 **2곳에 복제**돼 있고
(`command-palette/commands.ts:6` · `palette-input.ts:9`), 후자 주석이 *"commands.ts 의 것과 같은 규칙"*
이라며 스스로 복제임을 자백한다. F13 이 세 번째를 만들면 3중 복제 — **중립 위치 1곳으로 올리고
기존 2곳이 import 하게 바꾼다.** F4 가 `/search` 선재 결함을 같은 PR 에서 봉합한 선례를 따른다.

**★ 안전장치 — role 과 name 이 둘 다 바뀐다.** `button`+`검색` → `searchbox`+`전역 검색` 이라
잔존 참조가 **조용히 통과할 수 없다**. 빠뜨린 곳은 즉시 빨간불이 된다.

## Brainstorming Check

**ui 경량 경로 — Phase B 스킵** (`/bts-spec` §ui 경량 경로, Maxi 확정 2026-08-03).
brainstorming 대신 `## Jira 대조`(계약 §1 4단계 · 공식 문서 대조 6건 · 의도적 편차 3건) +
`## 제약 조건`(즉사 계약 §2 5행) + `## 시각 검증 기준`(§6)이 sanity check 를 대신했다.
신규 화면·신규 도메인 개념이 없어 경량 경로 대상이다.

**office-hours 는 escape hatch 적용.** 정본 로드맵 + 착수 전 실측으로 plan 이 이미 형성돼 있어
Phase 2(6문항 진단) 스킵, **Phase 3(전제 검증) + Phase 4(대안)만** 실행했다(스킬 자체 규정).
Phase 3.5(2차 의견)는 codex 미설치로 미실행 — **F4 #340 과 동일한 결손이고, 그때 게이트 2
독립 리뷰가 BLOCKER 를 잡아낸 전력이 있으므로 이번에도 게이트 2 독립 리뷰가 유일한 외부 시각이다.**

## Plan

**Goal.** 상단바 검색 아이콘 버튼을 `전역 검색` 입력창으로 바꾸고, 입력을 이슈키 / AQL / 자유 텍스트
3갈래로 갈라 목적지를 정한다.

**Architecture.** 판별은 `lib/aql-natural.ts` 의 **순수 함수** 한 곳에 둔다(F4 의 `palette-input.ts`
와 동형, ADR D-5 승계). 이스케이프·필드·연산자는 전부 기존 `lib/` 자산 재사용. `TopBar` 는 판별
결과를 라우터 호출로 옮기기만 하고 **활성 프로젝트를 조회하지 않는다**(FR12).

**파일 구조.**

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `apps/web/src/lib/issue-key.ts` | 이슈키 정규식 **단일 출처** | 신규 |
| `apps/web/src/lib/aql-natural.ts` | 전역 검색 입력 3갈래 판별 (순수) | 신규 |
| `apps/web/src/i18n/nav-labels.ts` | `globalSearch: '전역 검색'` 신설 | 수정 |
| `apps/web/src/components/layout/TopBar.tsx` | 아이콘 버튼 → 입력창 | 수정 |
| `command-palette/commands.ts` · `palette-input.ts` | 정규식 import 로 전환 | 수정 (선재결함) |

---

### Task 1. 이슈키 정규식 단일 출처화 (선재결함 봉합)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/issue-key.ts`, `apps/web/src/lib/issue-key.test.ts`, `apps/web/src/components/command-palette/commands.ts`, `apps/web/src/components/command-palette/palette-input.ts`]
- depends-on: []
- 규율: **TDD red-first 적용** (순수 로직 · 시각 변화 없음)

**RED**. 파일 `apps/web/src/lib/issue-key.test.ts`

```ts
import { describe, it, expect } from 'vitest'
import { ISSUE_KEY_PATTERN } from './issue-key'

describe('ISSUE_KEY_PATTERN', () => {
  it('대문자 프로젝트키-숫자 형식에 일치한다', () => {
    expect(ISSUE_KEY_PATTERN.test('ATLAS-42')).toBe(true)
    expect(ISSUE_KEY_PATTERN.test('A1-7')).toBe(true)
  })

  it('소문자·부분일치·여분 토큰은 불일치다', () => {
    expect(ISSUE_KEY_PATTERN.test('atlas-42')).toBe(false)
    expect(ISSUE_KEY_PATTERN.test('ATLAS-42 로그인')).toBe(false)
    expect(ISSUE_KEY_PATTERN.test('ATLAS-')).toBe(false)
    expect(ISSUE_KEY_PATTERN.test('-42')).toBe(false)
  })

  it('전역 플래그가 없다 — lastIndex 상태가 test() 호출 간에 남으면 안 된다', () => {
    expect(ISSUE_KEY_PATTERN.global).toBe(false)
  })
})
```

실패 메시지 (예상). `Failed to resolve import "./issue-key"`

**GREEN**. 파일 `apps/web/src/lib/issue-key.ts`

```ts
// 이슈 키 형식의 단일 출처 — 팔레트 2곳 + 전역 검색이 공유한다 (FR-UX-12 F13)
//
// ★왜 lib/ 인가. 이 정규식은 F13 착수 시점에 command-palette/commands.ts 와
// palette-input.ts 두 곳에 복제돼 있었고, 후자의 주석이 "commands.ts 의
// ISSUE_KEY_PATTERN 과 같은 규칙"이라며 스스로 복제임을 자백하고 있었다.
// 전역 검색이 세 번째 사본을 만들면 3중 복제가 되므로 여기 한 곳으로 올린다.
// lib/aql-text-query.ts 가 소비처 둘을 이유로 lib/ 에 놓인 것과 같은 근거(F4 ADR D-2).

/**
 * 이슈 키 형식 — `프로젝트키-번호`. **대문자로 정규화한 뒤** 검사한다.
 *
 * `^…$` 앵커가 있으므로 `ATLAS-42 로그인` 처럼 뒤에 토큰이 붙으면 불일치다
 * (자유 텍스트로 흘러야 한다).
 *
 * ★전역 플래그를 붙이지 말 것 — `test()` 가 `lastIndex` 를 남겨 호출마다 결과가 달라진다.
 */
export const ISSUE_KEY_PATTERN = /^[A-Z][A-Z0-9]*-\d+$/
```

**REFACTOR**. 기존 2곳을 import 로 전환한다.

- `command-palette/commands.ts:6` — `const ISSUE_KEY_PATTERN = /^[A-Z][A-Z0-9]*-\d+$/` 삭제,
  상단에 `import { ISSUE_KEY_PATTERN } from '@/lib/issue-key'` 추가.
- `command-palette/palette-input.ts:8-9` — 같은 삭제 + import. 8행의 *"commands.ts 의
  ISSUE_KEY_PATTERN 과 같은 규칙"* 주석도 함께 제거한다(더 이상 사실이 아니다).

동작은 **한 글자도 바뀌지 않는다** — 정규식 리터럴이 동일하고 각 호출부의 trim/toUpperCase 는 그대로 둔다.

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input/apps/web
node_modules/.bin/vitest run src/lib/issue-key.test.ts src/components/command-palette > /tmp/t1.out 2>&1; echo "EXIT=$?"
grep -rn "ISSUE_KEY_PATTERN\s*=" src/   # 기대: 1건 (lib/issue-key.ts)
```

---

### Task 2. 전역 검색 입력 3갈래 판별 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/aql-natural.ts`, `apps/web/src/lib/aql-natural.test.ts`]
- depends-on: [1]
- 규율: **TDD red-first 적용** (순수 함수)

**RED**. 파일 `apps/web/src/lib/aql-natural.test.ts`

```ts
import { describe, it, expect } from 'vitest'
import { resolveGlobalSearchInput } from './aql-natural'

describe('resolveGlobalSearchInput', () => {
  it('E5/S4 — 빈 문자열과 공백만은 empty 다', () => {
    expect(resolveGlobalSearchInput('')).toEqual({ kind: 'empty' })
    expect(resolveGlobalSearchInput('   ')).toEqual({ kind: 'empty' })
  })

  it('S2/E1 — 이슈키는 대문자로 정규화해 issue-key 다', () => {
    expect(resolveGlobalSearchInput('atlas-42')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-42' })
    expect(resolveGlobalSearchInput('  ATLAS-42  ')).toEqual({ kind: 'issue-key', issueKey: 'ATLAS-42' })
  })

  it('E2 — 이슈키 뒤에 토큰이 붙으면 자유 텍스트다', () => {
    expect(resolveGlobalSearchInput('ATLAS-42 로그인')).toEqual({
      kind: 'text',
      query: 'text ~ "ATLAS-42 로그인"',
    })
  })

  it('S3/E10 — 알려진 필드 + 연산자면 AQL 로 보고 원문을 통과시킨다', () => {
    expect(resolveGlobalSearchInput('status = "열림"')).toEqual({ kind: 'aql', query: 'status = "열림"' })
    expect(resolveGlobalSearchInput('summary ~ 로그인')).toEqual({ kind: 'aql', query: 'summary ~ 로그인' })
    expect(resolveGlobalSearchInput('priority != HIGH')).toEqual({ kind: 'aql', query: 'priority != HIGH' })
    expect(resolveGlobalSearchInput('STATUS = "열림"')).toEqual({ kind: 'aql', query: 'STATUS = "열림"' })
  })

  it('E11 — 필드명만 있고 연산자가 없으면 자유 텍스트다', () => {
    expect(resolveGlobalSearchInput('status')).toEqual({ kind: 'text', query: 'text ~ "status"' })
  })

  it('알려지지 않은 필드는 연산자가 있어도 자유 텍스트다', () => {
    expect(resolveGlobalSearchInput('로그인 = 안됨')).toEqual({
      kind: 'text',
      query: 'text ~ "로그인 = 안됨"',
    })
  })

  it('S5/E3/E4 — 자유 텍스트의 역슬래시와 큰따옴표는 이스케이프된다', () => {
    expect(resolveGlobalSearchInput('C:\\Users')).toEqual({ kind: 'text', query: 'text ~ "C:\\\\Users"' })
    expect(resolveGlobalSearchInput('로그인"버그')).toEqual({ kind: 'text', query: 'text ~ "로그인\\"버그"' })
  })
})
```

실패 메시지 (예상). `Failed to resolve import "./aql-natural"`

**GREEN**. 파일 `apps/web/src/lib/aql-natural.ts`

```ts
// 상단바 전역 검색 입력의 3갈래 판별 — 이슈키 / AQL / 자유 텍스트 (FR-UX-12 F13 FR5~FR8)
//
// ★이 파일은 순수하다. 활성 프로젝트·네트워크·라우터에 의존하지 않는다
// (F4 의 palette-input.ts ADR D-5 와 같은 규율). 목적지 결정은 TopBar 가 한다.
// ★필드·연산자·이스케이프를 새로 선언하지 않는다 — 전부 기존 lib/ 자산을 재사용한다.
//   목록을 복제하면 백엔드 AqlFields.MVP_FIELDS 와의 drift 를 아무도 못 본다.
import { AQL_FIELDS, AQL_OPERATORS } from './aql-tokenizer'
import { buildTextQuery } from './aql-text-query'
import { ISSUE_KEY_PATTERN } from './issue-key'

/**
 * 전역 검색 입력의 판별 결과 — 판별 유니온.
 *
 * - `issue-key`: 이슈 키 형식. 대문자로 정규화된 키를 싣는다 → `/issues/$key`
 * - `aql`      : AQL 문법으로 보이는 입력. **원문 그대로** 싣는다 → `/search?q=<원문>`
 * - `text`     : 그 외. `text ~ "…"` 로 감싼 쿼리를 싣는다 → `/search?q=<래핑>`
 * - `empty`    : 공백만 또는 빈 문자열 — 아무 이동도 하지 않는다
 */
export type GlobalSearchIntent =
  | { kind: 'issue-key'; issueKey: string }
  | { kind: 'aql'; query: string }
  | { kind: 'text'; query: string }
  | { kind: 'empty' }

/**
 * AQL 판별 정규식 — `<알려진 필드> <연산자>` 로 **시작**하는지만 본다.
 *
 * ★판정 정본은 백엔드 렉서다. 여기는 「AQL 을 치려던 것인가」를 가르는 최소 휴리스틱이라
 * 보수적으로 잡는다 — 애매하면 자유 텍스트로 보내는 편이 안전하다(전문검색은 항상 성립하지만
 * 잘못 통과시킨 AQL 은 400 이 된다).
 * ★연산자는 긴 것부터 정렬해야 `!=` 가 `=` 에 먼저 먹히지 않는다.
 */
const AQL_OPERATOR_ALTERNATION = [...AQL_OPERATORS]
  .sort((a, b) => b.length - a.length)
  .map((op) => op.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  .join('|')

const AQL_PREFIX_PATTERN = new RegExp(
  `^(?:${AQL_FIELDS.join('|')})\\s*(?:${AQL_OPERATOR_ALTERNATION})`,
  'i',
)

/**
 * 상단바 전역 검색 입력을 3갈래로 판별한다.
 *
 * **순서가 계약이다.** 빈 값 → 이슈키 → AQL → 자유 텍스트. 이슈키를 AQL 보다 먼저 보는 이유는
 * `ATLAS-42` 가 어떤 필드명으로도 시작하지 않아 충돌하지 않지만, 순서를 뒤집으면 나중에 필드가
 * 늘었을 때 조용히 갈래가 바뀔 수 있기 때문이다.
 *
 * @param input 입력창의 원본 문자열
 * @returns 판별 유니온
 */
export function resolveGlobalSearchInput(input: string): GlobalSearchIntent {
  const trimmed = input.trim()
  if (trimmed === '') return { kind: 'empty' }

  const upper = trimmed.toUpperCase()
  if (ISSUE_KEY_PATTERN.test(upper)) {
    return { kind: 'issue-key', issueKey: upper }
  }

  if (AQL_PREFIX_PATTERN.test(trimmed)) {
    return { kind: 'aql', query: trimmed }
  }

  // buildTextQuery 는 공백만일 때 null 을 주지만 위에서 이미 걸렀다
  return { kind: 'text', query: buildTextQuery(trimmed) as string }
}
```

**REFACTOR**. `AQL_PREFIX_PATTERN` 조립부에 「왜 긴 연산자부터인지」 주석이 이미 있는지 확인하고,
`as string` 단언에 「위에서 empty 를 걸렀다」 근거 주석을 남긴다.

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input/apps/web
node_modules/.bin/vitest run src/lib/aql-natural.test.ts > /tmp/t2.out 2>&1; echo "EXIT=$?"
node_modules/.bin/tsc -p tsconfig.app.json --noEmit > /tmp/t2tsc.out 2>&1; echo "EXIT=$?"
```

---

### Task 3. `전역 검색` 이름표 신설

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/nav-labels.ts`, `apps/web/src/i18n/__tests__/nav-labels.test.ts`]
- depends-on: []
- 규율: **TDD red-first 적용** (순수 상수 · 시각 변화 없음)

**RED**. `apps/web/src/i18n/__tests__/nav-labels.test.ts` 에 추가

```ts
it('F13 — globalSearch 는 상단바 입력창 전용 이름이고 search 와 다르다', () => {
  expect(navLabels.globalSearch).toBe('전역 검색')
  expect(navLabels.search).toBe('검색')
  expect(navLabels.globalSearch).not.toBe(navLabels.search)
})
```

실패 메시지 (예상). `expected undefined to be '전역 검색'`

**GREEN**. `apps/web/src/i18n/nav-labels.ts` 의 `search` 항목 **바로 아래**에 추가

```ts
  /** 상단바 검색 버튼 aria-label (🔒 e2e 계약) — F13 이후 AQL 검색 페이지 제출 버튼 전용 */
  search: '검색',

  /**
   * 상단바 전역 검색 **입력창** aria-label (🔒 e2e 계약, FR-UX-12 F13).
   *
   * ★`search`(`'검색'`)와 반드시 분리한다 — Jira 패리티 계약 §2 「`검색` 이름 분리」
   * (Maxi 확정 2026-07-28 결정 4). 상단바=`전역 검색`, `검색`=AQL 페이지 제출 버튼 전용.
   * 둘을 합치면 `getByRole` strict mode 에서 상단바와 검색 페이지가 동시에 잡힌다.
   */
  globalSearch: '전역 검색',

  /**
   * 상단바 전역 검색 입력창 placeholder (FR-UX-12 F13).
   *
   * ★`aria-label` 이 있으므로 접근성 이름은 `globalSearch` 가 이긴다 — placeholder 는
   * 시각 힌트 전용이다. 여기에 `검색` 을 넣지 말 것(계약 §2 이름 분리를 흐린다).
   */
  globalSearchPlaceholder: '이슈 검색',
```

`search` 의 기존 주석 「상단바 검색 버튼 aria-label (🔒 e2e 계약, 상단바 단일)」은 F13 이후
사실과 달라지므로 위와 같이 정정한다.

**REFACTOR**. 파일 상단 blockquote 의 계약 문자열 목록(`mainNav`·`adminNav`·`projectViewNav`·`search`)에
`globalSearch` 를 추가한다.

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input/apps/web
node_modules/.bin/vitest run src/i18n > /tmp/t3.out 2>&1; echo "EXIT=$?"
```

---

### Task 4. TopBar 아이콘 버튼 → 전역 검색 입력창

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/layout/__tests__/TopBar.test.tsx`]
- depends-on: [2, 3]
- 규율: **ui 시각 검증 트랙** — red-first 순서 강제 없음. 아래 RED 는 「동반 테스트 명세」로 읽는다.

**동반 테스트**. `apps/web/src/components/layout/__tests__/TopBar.test.tsx:111-117` 의 기존
「검색 버튼이 aria-label="검색"으로 정확히 1개 존재하고 클릭 시 /search로 이동한다」 를 **교체**한다.

```ts
it('F13 — 전역 검색 입력창이 정확히 1개고 검색 버튼은 없다', () => {
  renderTopBar()
  expect(screen.getAllByRole('searchbox', { name: navLabels.globalSearch })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: navLabels.search })).toBeNull()
})

it('F13 S1 — 자유 텍스트 Enter 는 text ~ 로 감싸 /search 로 보낸다 (projectKey 미첨부)', async () => {
  const user = userEvent.setup()
  renderTopBar()
  const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
  await user.type(box, '로그인 버그{Enter}')
  expect(navigateMock).toHaveBeenCalledWith({ to: '/search', search: { q: 'text ~ "로그인 버그"' } })
})

it('F13 S2 — 이슈키 Enter 는 이슈 상세로 보낸다 (대소문자 무관)', async () => {
  const user = userEvent.setup()
  renderTopBar()
  await user.type(screen.getByRole('searchbox', { name: navLabels.globalSearch }), 'atlas-42{Enter}')
  expect(navigateMock).toHaveBeenCalledWith({ to: '/issues/$key', params: { key: 'ATLAS-42' } })
})

it('F13 S3 — AQL 문법은 원문 그대로 보낸다', async () => {
  const user = userEvent.setup()
  renderTopBar()
  await user.type(screen.getByRole('searchbox', { name: navLabels.globalSearch }), 'status = "열림"{Enter}')
  expect(navigateMock).toHaveBeenCalledWith({ to: '/search', search: { q: 'status = "열림"' } })
})

it('F13 S4 — 공백만 Enter 는 아무 데도 가지 않는다', async () => {
  const user = userEvent.setup()
  renderTopBar()
  await user.type(screen.getByRole('searchbox', { name: navLabels.globalSearch }), '   {Enter}')
  expect(navigateMock).not.toHaveBeenCalled()
})

it('F13 S6 — IME 조합 중 Enter 는 제출하지 않는다', () => {
  renderTopBar()
  const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
  fireEvent.change(box, { target: { value: '로그인' } })
  fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
  expect(navigateMock).not.toHaveBeenCalled()
})

it('F13 FR11 — 제출 후 입력값을 지우지 않는다', async () => {
  const user = userEvent.setup()
  renderTopBar()
  const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
  await user.type(box, '로그인{Enter}')
  expect(box).toHaveValue('로그인')
})
```

**구현**. `TopBar.tsx:67-76` 의 `<Button>` 을 아래로 교체한다.

```tsx
      {/* 폭은 남는 공간을 먹되 상·하한을 둔다 (design 리뷰 G2/G4).
          - flex-1  : 아래 `<div className="flex-1" />` 스페이서를 대체한다
          - max-w-md: 448px 초과는 한 줄 스캔이 어렵고 우측 액션과 균형이 깨진다
          - min-w-32: 128px. 한글 4~5자 + 돋보기가 들어가는 최소치 — 이보다 좁으면
                      placeholder 가 잘려 무슨 칸인지 알 수 없다
          ★컨트롤 종류는 어떤 폭에서도 바뀌지 않는다. 좁다고 아이콘 버튼으로 되돌리면
           `searchbox` 가 0개가 돼 유닛·E2E 단언이 뷰포트에 따라 깨지고, 그 버튼의
           접근성 이름을 무엇으로 할지 계약 §2 문제가 되살아난다. */}
      <div className="relative ml-2 min-w-32 max-w-md flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          value={query}
          aria-label={navLabels.globalSearch}
          placeholder={navLabels.globalSearchPlaceholder}
          className="pl-8"
          onChange={(e) => { setQuery(e.target.value) }}
          onKeyDown={handleSearchKeyDown}
        />
      </div>
```

★**`components/ui/input.tsx` 프리미티브를 쓴다** (계약 §4 재사용 자산 — design 리뷰 G1).
raw `<input>` 에 클래스를 직접 쓰면 프리미티브와 **충돌**한다. 실측 대조.

| 항목 | 프리미티브 정본 | 인라인으로 쓰면 |
|---|---|---|
| 라운드 | `rounded-lg` | `rounded-md` — 형제 입력과 어긋남 |
| 포커스 | `focus-visible:border-ring` + `ring-3 ring-ring/50` | `ring-2 ring-ring` — 두께·불투명도 불일치 |
| 다크 | `dark:bg-input/30` **내장** | `bg-background` — 다크에서 배경이 안 눌림 |
| 비활성·오류 | `disabled:` · `aria-invalid:` 전량 내장 | 없음 |

**덮어쓰는 것은 `pl-8` 하나뿐**이다 — 돋보기 아이콘 자리. `cn()` 이 뒤에 오는 className 을 이기므로
패딩만 안전하게 교체된다.

**hover 상태는 추가하지 않는다** (design 리뷰 G3). 프리미티브에 hover 배경이 없는 것이 의도다 —
입력창의 클릭 가능 어포던스는 I-beam 커서가 이미 준다. 상단바 형제 버튼들의 `hover:bg-accent` 를
입력창에 붙이면 다른 폼 입력 전부와 어긋난다.

**기존 `<div className="flex-1" />` 스페이서(`:78`)는 제거한다** — 검색 컨테이너가 `flex-1` 을
가져갔으므로 두 개면 공간을 나눠 갖는다.

컴포넌트 본문 상단(`const { collapsed, toggle } = useSidebarCollapsed()` 아래)에 추가.

```tsx
  // FR-UX-12 F13 — 상단바 전역 검색. 제출 시에만 이동하고 입력 자체는 네트워크를 부르지 않는다(NFR2).
  const [query, setQuery] = useState('')

  /**
   * Enter 제출 — 판별 결과대로 목적지를 고른다.
   *
   * ★`e.nativeEvent.isComposing` 을 먼저 본다(FR10/S6). 한글 조합 중의 Enter 는 조합 확정이지
   * 제출이 아니다. 이 가드가 없으면 「로그인」을 치는 도중 첫 Enter 에 검색이 나간다.
   * ★`projectKey` 를 싣지 않는다(FR12) — `/search` 가 4단 해소와 미해소 안내를 이미 소유한다
   * (`routes/search.tsx:480` `useResolvedActiveProject` · `:565` `<ActiveProjectGate>`).
   */
  function handleSearchKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return
    const intent = resolveGlobalSearchInput(query)
    if (intent.kind === 'empty') return
    if (intent.kind === 'issue-key') {
      void navigate({ to: '/issues/$key', params: { key: intent.issueKey } })
      return
    }
    void navigate({ to: '/search', search: { q: intent.query } })
  }
```

import 추가 3줄.

```ts
import { useState, type KeyboardEvent } from 'react'   // 기존 useState 줄을 이렇게 바꾼다
import { resolveGlobalSearchInput } from '@/lib/aql-natural'
import { Input } from '@/components/ui/input'
```

`Button` import 는 다른 버튼들이 계속 쓰므로 **제거하지 않는다**. `Search` 아이콘도 입력창 안
돋보기로 계속 쓴다. `navigate` 는 이미 있다(`:42`).

파일 헤더 JSDoc(`:26-27`)의 「검색(`aria-label="검색"`, 상단바 단일)」 서술도 정정한다.

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input/apps/web
node_modules/.bin/vitest run src/components/layout > /tmp/t4.out 2>&1; echo "EXIT=$?"
node_modules/.bin/tsc -p tsconfig.app.json --noEmit > /tmp/t4tsc.out 2>&1; echo "EXIT=$?"
```

**관련 기존 E2E (계약 §5 사전 grep 결과)** — Task 6 에서 함께 돌린다.
`context-shortcuts` · `keyboard-shortcuts` · `command-palette` · `search` · `detail-action-shortcuts` · `saved-filters`.

**브라우저 눈확인 (라이트/다크 양쪽)**.
1. 입력창이 `ProjectSwitcher` 와 우측 액션 사이에서 늘어나되 `max-w-md` 에서 멈춘다.
2. 창을 최소폭까지 좁혀도 **입력창이 아이콘 버튼으로 바뀌지 않고** `min-w-32` 에서 버틴다.
   상단바가 줄바꿈되거나 우측 액션을 밀어내지 않는다.
3. placeholder 가 양쪽 테마에서 읽힌다 — 계산 근거는 `DESIGN.md §10`
   (`muted-foreground on background` 라이트 5.08:1 · 다크 5.78:1, **AA 통과**). 눈으로는 잘림만 본다.
4. 포커스했을 때 프리미티브 기본 링(`focus-visible:border-ring` + `ring-3 ring-ring/50`)이 뜨고,
   상단바 테두리에 잘리지 않는다.
5. 마우스를 올려도 **배경이 변하지 않는다**(의도 — G3). 커서만 I-beam 으로 바뀐다.
6. 다크 모드에서 입력창 배경이 `dark:bg-input/30` 으로 눌려 상단바와 구분된다.
7. 돋보기 아이콘이 입력 텍스트와 겹치지 않는다 (`pl-8` + 아이콘 `left-2.5`).

---

### Task 5. 셸 계약 유닛 단언 3곳 교체

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`, `apps/web/src/components/layout/__tests__/ShellLayout.test.tsx`]
- depends-on: [4]
- 규율: ui 시각 검증 트랙 (기존 단언 교체)

**변경**.

`navigation-contract.test.tsx` 는 `'검색'` 을 **문자열 리터럴로** 쓰고 있으므로 정본 import 를
먼저 추가한다 (2026-05-26 교훈 — 셀렉터는 i18n 정본 참조).

```ts
import { navLabels } from '@/i18n/nav-labels'
```

`ShellLayout.test.tsx` 는 이미 `navLabels.search` 를 쓰므로 import 가 있다 — 추가 불필요.

```ts
  // F13 — 상단바는 이제 버튼이 아니라 입력창이다. role 과 name 이 둘 다 바뀌었으므로
  // 잔존 참조는 조용히 통과할 수 없다.
  it('전역 검색 — searchbox 는 정확히 1개다 (strict 단일, 사이드바 내 검색 항목 추가 금지)', async () => {
    // …기존 렌더 세팅 유지…
    expect(screen.getAllByRole('searchbox', { name: navLabels.globalSearch })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: navLabels.search })).toBeNull()
  })
```

`ShellLayout.test.tsx:145-149`.

```ts
  it('전역 검색 입력창(aria-label="전역 검색")이 정확히 1개다 — TopBar 단일 소유', () => {
    // …기존 렌더 세팅 유지…
    expect(screen.getAllByRole('searchbox', { name: navLabels.globalSearch })).toHaveLength(1)
  })
```

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input/apps/web
node_modules/.bin/vitest run src/components/layout > /tmp/t5.out 2>&1; echo "EXIT=$?"
node_modules/.bin/vitest run > /tmp/t5all.out 2>&1; echo "EXIT=$?"   # 전량 — 기준선 8,795 이상
```

---

### Task 6. E2E 5파일 sentinel 교체 + i18n 정본 import 전환

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/context-shortcuts.spec.ts`, `apps/web/e2e/keyboard-shortcuts.spec.ts`, `apps/web/e2e/command-palette.spec.ts`, `apps/web/e2e/search.spec.ts`, `apps/web/e2e/detail-action-shortcuts.spec.ts`]
- depends-on: [4]
- 규율: ui 시각 검증 트랙 (E2E 계약 갱신)

**배경**. 다섯 파일이 각자 `const HEADER_SEARCH_ARIA_LABEL = '검색'` 을 **하드코딩**해 두고
「상단바가 떴다 = 화면 준비 완료」 신호로 쓴다. i18n 정본을 import 하지 않아 2026-05-26 교훈
(「E2E 셀렉터는 i18n 정본 import — 하드코딩 금지」)을 어긴 상태다. **이번에 함께 해소한다.**

**변경 (5파일 공통)**.

```ts
// 삭제
const HEADER_SEARCH_ARIA_LABEL = '검색'

// 추가 — 정본 참조. 라벨이 바뀌면 E2E 가 자동으로 따라간다(2026-05-26 교훈).
import { navLabels } from '../src/i18n/nav-labels'
```

sentinel 셀렉터를 **role 과 name 을 둘 다** 바꾼다.

```ts
// before
page.getByRole('button', { name: HEADER_SEARCH_ARIA_LABEL, exact: true })
// after
page.getByRole('searchbox', { name: navLabels.globalSearch, exact: true })
```

| 파일 | 줄 | 처리 |
|---|---|---|
| `context-shortcuts.spec.ts` | 29 · 73 | 상수 삭제 + sentinel 교체 |
| `keyboard-shortcuts.spec.ts` | 43 · 70 | 상수 삭제 + sentinel 교체 |
| `keyboard-shortcuts.spec.ts` | 204 | **클릭 → 이 줄은 삭제한다.** 상단바가 더는 버튼이 아니다. 이 테스트가 검증하던 「검색 진입」은 S2(`/` → `/search`)가 이미 덮는다 |
| `command-palette.spec.ts` | 61 · 79 | 상수 삭제 + sentinel 교체 |
| `search.spec.ts` | 53 · 81 | 상수 삭제 + `:81` 클릭 → `fill` + `Enter` 제출로 교체 |
| `detail-action-shortcuts.spec.ts` | 51 · 78 | 상수 삭제 + sentinel 교체 |

**절대 건드리지 말 것**.
- `search.spec.ts:107,109` · `saved-filters.spec.ts:206,238,240` — **AQL 페이지 제출 버튼** `검색`. 계약 §2 가 이 이름을 이 버튼 전용으로 남기라고 못 박았다.
- `command-palette.spec.ts:292` — 팔레트 안 **option** `검색`. F4 산출물.
- `apps/web/src/components/keyboard-shortcuts/shortcuts.ts` — M1 로 불변.

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input/apps/web
node_modules/.bin/playwright test context-shortcuts keyboard-shortcuts command-palette search detail-action-shortcuts saved-filters > /tmp/t6.out 2>&1; echo "EXIT=$?"
# 2회 연속 통과 필요 (flaky 판별 — 실패 대상이 바뀌면 flaky 서명)
node_modules/.bin/playwright test context-shortcuts keyboard-shortcuts command-palette search detail-action-shortcuts saved-filters > /tmp/t6b.out 2>&1; echo "EXIT=$?"
```

**브라우저 눈확인**. Task 4 의 5항목 + 이슈키 제출로 실제 페이지 이동이 일어나는지.

---

### Task 7. 정본 동기화 + 최종 검증

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/design/jira-parity-roadmap.md`, `docs/design/jira-parity-contract.md`, `docs/decisions/2026-08-05-fr-ux-12-f13-global-search-input.md`, `CLAUDE.md`]
- depends-on: [5, 6]
- 규율: 문서 (테스트 없음, 판별식이 검증)

**변경**.

1. `personalization.md` §4.10 — **D6·D7 을 `[x]`** 로. F13 완료 + FR-UX-12 완주 표기.
2. `docs/plan/README.md:113,127` — personalization 행 상태와 FR-UX-12 서술 갱신.
3. `jira-parity-roadmap.md:40,64` — FR-UX-12 를 ✅ 로, F13 행에 PR 번호.
4. `jira-parity-contract.md` §2 「`검색` 이름 분리」 행 — 「상단바 입력창은 `전역 검색`」이
   **이제 구현됐다**는 사실을 반영(계약 자체는 불변, 상태만 갱신).
5. **신규 ADR** `docs/decisions/2026-08-05-fr-ux-12-f13-global-search-input.md` — M1(`/` 불변,
   ★Jira 근거가 실측으로 뒤집힌 경위 포함) · M2(3갈래) · M3(스코프) · FR9(정규식 단일 출처) ·
   FR12(게이트 비복제).
6. `CLAUDE.md` §프로젝트 한 줄 — FR-UX-12 완주 반영, 진척 카운트 갱신.

**검증**.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/fr-ux-12-f13-global-search-input
node scripts/build-doc-index.mjs > /tmp/t7idx.out 2>&1; echo "EXIT=$?"
bash scripts/verify-master-plan.sh > /tmp/t7verify.out 2>&1; echo "EXIT=$?"   # 기대 0
grep -c '^- \[x\] D' docs/plan/product/*.md   # 진척 949 → 951 확인
```

---

## Plan 메타

- task 수: **7**
- 예상 시간. 직렬 기준 약 25분 · 병렬 wave 적용 시 약 15분 (예상 wave 수 **4** — T1∥T3 → T2 → T4 → T5∥T6 → T7)
- 구현 규율. **ui 시각 검증 트랙** (bts-impl §타입별 규율). 단 **T1·T2·T3 은 TDD red-first 적용** —
  순수 로직이고 시각 변화가 없어 면제 사유가 없다. T4~T6 은 red-first 순서 강제 없이 동반 테스트로 읽는다.
- 병렬 dispatch. bts-impl 이 `depends-on` + `files` 로 wave 계산. T1·T3 은 파일 교집합이 없어 동시 가능.
- 추가 검증. typecheck(`tsc -p tsconfig.app.json --noEmit`) · lint(`eslint src`) ·
  vitest 전량(기준선 **8,795**) · playwright 6 spec **2회 연속** · `verify-master-plan.sh` EXIT 0.
- 환경. worktree `node_modules` 는 심볼릭 링크 — **`pnpm` 래퍼 금지**, `node_modules/.bin/*` 직접 호출.
  **파이프 금지** (`> /tmp/x 2>&1; echo "EXIT=$?"`). 미커밋 상태에서 `git checkout --` **금지**
  (뮤테이션 검증은 GREEN 커밋 후에만).

## 리뷰 결과

### plan-design-review (2026-08-05)

**타입 `ui` → 리뷰 체인은 plan-design-review 단독** (`/bts-review-plan` Step 2 표).

**범위.** Maxi 가 D1 에서 **「공백 4건 집중」**을 선택 — 7차원 전체 대신 초기 평가에서 6/10 을
만든 4개 공백만 판다. 확정된 M1·M2·M3 은 재검토하지 않았다.

**초기 평가 6/10 → 조치 후 9/10.**

| # | 발견 | 심각도 | 조치 |
|---|---|---|---|
| **G1** | raw `<input>` + 인라인 className 사용. **`components/ui/input.tsx` 프리미티브가 이미 존재**(계약 §4 위반). 게다가 내가 쓴 값이 프리미티브와 **충돌** — `rounded-md`↔`rounded-lg` · `ring-1 ring-foreground/10`↔`focus-visible:ring-3 ring-ring/50` · `bg-background`↔`dark:bg-input/30` | **BLOCKER** | ✅ 해소. `Input` 프리미티브 채택, 덮어쓰는 클래스는 `pl-8` 하나뿐 |
| **G2** | FR4 「아이콘 버튼으로 축약**될 수 있다**」 — 축약하면 좁은 뷰포트에서 `searchbox` 가 0개가 돼 완료 기준 1·유닛 4곳·E2E 5파일이 **뷰포트에 따라** 깨지고, 그 버튼 이름을 뭘로 할지 계약 §2 문제가 되살아난다 | **BLOCKER** | ✅ 해소. 「폭만 반응형, 컨트롤 종류 불변」 단정문으로 교체 (스펙 FR4 개정) |
| **G3** | hover 상태 미지정 | 중 | ✅ 해소. **추가하지 않는 것이 정답** — 프리미티브에 hover 배경이 없는 게 의도이고, 형제 폼 입력과의 일관성이 우선 (스펙 FR4-a 신설) |
| **G4** | `w-56` 고정폭이 근거 없는 매직 넘버 | 중 | ✅ 해소. `flex-1` + `max-w-md`(스캔 한계) + `min-w-32`(placeholder 잘림 한계). G2 와 한 처방으로 닫힘. 기존 `<div className="flex-1" />` 스페이서 제거 동반 |

**추가 확인 — 이미 답이 있어서 새 작업이 아닌 것 2건.**
- **다크 모드.** `Input` 프리미티브가 `dark:bg-input/30` 을 내장. 프리미티브를 쓰는 순간 해결된다.
- **placeholder 대비.** `DESIGN.md §10` 이 `muted-foreground on background` = 라이트 **5.08:1** ·
  다크 **5.78:1** 로 계산해 뒀고 **AA 통과**. 눈확인에서는 대비가 아니라 잘림만 본다.

**BLOCKER: 2건 발견, 2건 모두 이 리뷰에서 해소.** 미해결 BLOCKER 없음.

**실행하지 않은 것과 이유.**
- **시각 목업 생성** — 스킬 기본값이나 미실행. 상단바에 입력창 1개를 넣는 변경이고, BTS 계약 §3 이
  *「팔레트 스크린샷·블로그는 정본이 아니다, 토큰 값은 DESIGN.md 만 믿는다」*, §6 이 **실제 브라우저
  눈확인**을 요구한다. AI 목업은 이 둘 중 어느 것도 대체하지 못하고 거짓 시각 목표를 만들 위험이 있다.
  대신 눈확인 항목을 5개 → **7개**로 늘렸다(hover 무변화·다크 배경 눌림 신규).
- **outside voices 2종** — codex 미설치 + 에이전트 임의 호출 금지. **F4 #340 과 동일한 결손**이고,
  그때 **게이트 2 독립 리뷰가 BLOCKER 를 잡아냈다**. 이번에도 게이트 2 독립 리뷰가 유일한 외부 시각이다.
- **gstack 7-pass 리포트** — Maxi 가 D1 에서 범위를 좁혔으므로 미생성. 이 절이 BTS 정본 형식의 리뷰 결과다.
