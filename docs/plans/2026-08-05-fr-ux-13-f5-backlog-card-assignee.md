# FR-UX-13 F5 — 백로그 카드 담당자·에러 상태 봉합

> slug: fr-ux-13-f5-backlog-card-assignee
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning
> 생성: 2026-08-05

## Brief

**사용자 원문.** `FR-UX-13 F5 백로그 카드 담당자·에러 상태 봉합`

**정본 근거.** `docs/plan/product/personalization.md` §4.11 FR-UX-13 (백로그 사용성).
승계 PR 3건 중 **F5 단독** — F15(세로 스택·스프린트 다이얼로그·키보드 DnD)·F16(필터바·에픽 패널)은
로드맵 임계경로(`B2 → F14 → F15 → F16`)상 FR-UX-14 뒤라 이번 범위 밖.

**정본이 적시한 결함 2건.**
1. 백로그/스프린트 카드의 담당자가 **전원 `?`(이름 미확인)로 렌더** — 빈 `Map` 을 만들어 그대로
   넘기고 채우는 코드가 없다(`BacklogBoard.tsx:217`). 보드는 정상이고 백로그만 누락.
2. 조회 실패 시 **빈 `<div/>`** 반환 — 에러 안내도 재시도도 없다(`:214`).

**정본이 지목한 처방.** `board.tsx:333` 의 `useUsersByIds` 조립 패턴을 복제.

**classify 결과 + 컨트롤러 정정 2건.**
- type=ui · agent=frontend-engineer (정본 §4.11 D6 책임과 일치, 변경 없음)
- primary_bc. `project-workflow` → **`agile-planning`** ('상태' 가 워크플로우 신호로 오견인.
  데이터 소유자는 agile-planning)
- slug. `fr-ux-13-f5` → **`fr-ux-13-f5-backlog-card-assignee`** (정본 Plan slug
  `fr-ux-13-backlog-usability` 는 FR 전체용이라 후속 F15·F16 과 충돌. 선례
  `fr-ux-12-f13-global-search-input` 의 `fr-ux-NN-fM-<서술>` 규칙 승계)

**선행 읽기에서 고른 관련 교훈 4건** (`/bts-codereview` 발췌 주입에 재사용).
- 2026-05-31 **UI PR 이 E2E 를 후속으로 미루면 기존 E2E 회귀가 머지 시점에 잠복** — 같은 화면
  (`backlog.spec.ts`)을 이 PR 에서 반드시 함께 돌린다
- 2026-05-30 Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다 (계약갭 역방향)
- 2026-05-30 메타 mutation `setQueryData`(부분응답)가 본문을 placeholder 로 덮는 플리커
- 2026-05-26 E2E 셀렉터는 i18n 정본 import (hardcoded string drift 차단)

## 도메인 정리

**결론. `/bts-domain`(grill-with-docs) 스킵** — `type == "ui"` 스킵 조건 충족 (Maxi 확정
2026-08-03). 스킵 조건인 "신규 도메인 개념 없음" 을 실측으로 확인했다.

- BC. `agile-planning` (백로그/스프린트 카드. 프론트 전용 변경이라 백엔드 모듈 무변경)
- 영향 컴포넌트. `BacklogBoard.tsx` · `BacklogColumn.tsx` · `SprintColumn.tsx` · `BacklogCard.tsx`
- **새 엔티티 0 · 새 라우트 0 · 새 용어 0.** `glossary.md` 에 담당자/백로그/카드 관련 헤딩이
  없어 대조 대상 자체가 없다. 3-상태 표시 개념(`CardAssigneeDisplay`)은 **이미 보드에 존재**
  (`components/board/board-drop.ts` 정의 · 보드 8파일 사용)하므로 백로그 적용은 신규 개념이
  아니라 **기존 개념의 재사용**이다.
- 기존 결정 충돌. 없음
- 관련 ADR. **없음** (`docs/decisions/` 에 `assigneeNames`·`CardAssigneeDisplay`·`백로그 카드`
  grep 0건)

### ★ 실측이 정본을 정정한 3건 (spec 단계 입력)

| 항목 | 정본 §4.11 서술 | 실측 |
|---|---|---|
| 담당자 빈 Map 위치 | `BacklogBoard.tsx:217` | **`:102`** (파일 전체가 174줄) |
| 빈 `<div/>` 위치 | `BacklogBoard.tsx:214` | **`:99`** |
| 처방 출처 | `board.tsx:333` 의 `useUsersByIds` | **`useUsersByIds` 아님.** `projects.$projectKey.board.tsx:335` 은 `buildUserMap(usersRaw)` + `:339` `buildAssigneeNames(boardDetail, userMap)` 조립이다 |

**★ 타입 불일치 1건 (spec 이 판정할 갈림길).** 보드는
`Map<issueKey, CardAssigneeDisplay>` **3-상태**(`unassigned` / `named` / `unknown`)를 쓰는데
(`projects.$projectKey.board.tsx:164-181`), 백로그는 `Map<string, string>` **문자열**이다
(`BacklogBoard.tsx:102`). "보드 패턴 복제" 를 문자 그대로 하면 백로그 3파일의 prop 타입이 함께
바뀐다. 어느 쪽을 택할지는 `## 스펙` 에서 결정한다.

**★ 선재 증언 1건.** `src/mocks/user-fixtures.ts:10` 주석이 *"이름 대신 원시 UUID 를 그린다 —
그런데 어떤 테스트도 실패하지 않는다"* 라고 **이미 자백 중**이다. 테스트 눈멂의 기존 기록이므로
spec 의 테스트 설계에서 반드시 되짚는다.

## 스펙

전체 스펙. [docs/specs/2026-08-05-fr-ux-13-f5-backlog-card-assignee.md](../specs/2026-08-05-fr-ux-13-f5-backlog-card-assignee.md)
FR 8 · NFR 4 · 시나리오 S1~S8 · 엣지 E1~E10 · 신규 API 0 · 마이그레이션 0 · 백엔드 0줄.

**핵심 3줄.**
- 백로그·스프린트 이슈의 담당자 id 를 중복 제거해 **50개씩 나눠 전량 조회**하고 기존 빈 Map 을 채운다
- 조회 실패 시 빈 `<div/>` 대신 `role="alert"` 안내 + `다시 시도`(refetch) 를 낸다
- 담당자 조회만 실패하면 카드는 살리고 담당자만 `?` 로 떨어뜨린다 (fail-soft)

### Maxi 확정 2건 (2026-08-05)

- **M1. 담당자 조회 = 필요한 사람만 + 50 묶음 전량.** 「앞 50명만」은 백로그 상한이 1,000이라
  **고치려던 `?` 가 남는 반쪽 봉합**이고, 「전체 목록」(정본 문자)은 `MAX_RESULTS=50` 때문에
  1,000명 조직에서 대부분 `?` 라 **알면서 결함을 복제**한다.
- **M2. 보드의 동일 결함은 이번 PR 범위 밖** — 기록만. 한 PR = 한 관심사 + 보드 E2E 동반 비용.
- **★ 조합 되짚기.** 이 PR 이후 백로그(정확) ↔ 보드(낡음) 로 동작이 갈린다. **회귀가 아니라
  선행**이다. 후속 트랙에 비대칭을 명시해 "왜 보드만 다르냐" 가 버그로 재보고되지 않게 한다.

### ★ 스펙 단계에서 정본이 뒤집힌 지점

정본 §4.11 의 처방 "`board.tsx:333` 의 `useUsersByIds` 패턴 복제" 는 **두 군데가 틀렸다**.
보드 라우트는 `useUsersByIds` 가 아니라 `useQuery(['users'], fetchUsers)` **전체 목록**을 쓰고,
그 방식은 `UsersController.kt:53 MAX_RESULTS=50` 때문에 대규모 조직에서 동작하지 않는다.
**보드 화면이 지금 그 잠재 결함을 갖고 있다** (개발 시드 5명이라 안 드러남).

## Brainstorming Check

**스킵 — ui 경량 경로** (Maxi 확정 2026-08-03). 기존 화면 수정 · 신규 도메인 개념 0.
대체 수단으로 **Jira 대조(계약 §1 4단계) + 즉사 계약 실측 + 착수 전 사전 grep(§5)** 를 돌렸고,
그 결과 정본 서술 3건 정정 · Maxi 결정 2건 · 엣지 10건이 나왔다.

**즉사 계약 실측 — 저촉 0건.** `e2e/backlog.spec.ts` 에 `getByRole('alert')` 소비가 없어 신규
`role="alert"` 이 strict mode 를 깨지 않는다. `<h1>백로그</h1>`(`:513`)은 라우트 소유라
에러 상태에서도 유지된다. nav aria-label 4종·dialog·검색·단축키 레지스트리 무관.

## Plan

**Goal.** 백로그·스프린트 카드의 담당자 이름을 실제로 채우고, 조회 실패를 빈 화면이 아니라
안내 + 재시도로 바꾼다.

**Architecture.** 순수 함수 2개(수집·조립) + 청크 조회 훅 1개를 신설하고 `BacklogBoard` 가
그것을 배선한다. prop 타입·카드 컴포넌트·백엔드는 무변경.

**Tech Stack.** React 19 · TanStack Query v5 (`useQueries`) · vitest · Playwright · MSW.

**★ 훅 순서 함정 (모든 task 공통).** `BacklogBoard` 는 `if (isLoading) return …` 조기 반환을
갖고 있다. 신규 훅은 **반드시 조기 반환 앞**에서 호출해야 한다 — 뒤에 두면 렌더마다 훅 개수가
달라져 React 가 즉사한다. 그래서 `collectAssigneeIds` 는 `undefined` 를 받아 `[]` 를 돌려준다.

---

### Task 1. 담당자 id 수집 · 이름 Map 조립 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/backlog-assignee-names.ts`, `apps/web/src/components/backlog/backlog-assignee-names.test.ts`]
- depends-on: []

**RED**. `apps/web/src/components/backlog/backlog-assignee-names.test.ts`

```ts
// 백로그 담당자 이름 조립 순수 함수 테스트 (FR-UX-13 F5)
import { describe, it, expect } from 'vitest'
import { collectAssigneeIds, buildAssigneeNameMap } from './backlog-assignee-names'
import type { BacklogView } from '@/api/backlog'
import type { UserSummary } from '@/api/users'

const ALICE = '00000000-0000-4000-8000-000000000001'
const BOB = '00000000-0000-4000-8000-000000000002'

function issue(key: string, assigneeId: string | null) {
  return { key, summary: `${key} 제목`, currentStateKey: 'TODO', assigneeId, priority: 3 }
}

const VIEW = {
  backlog: [issue('ATLAS-1', ALICE), issue('ATLAS-2', null)],
  sprints: [{ sprint: { sprintId: 's1' }, issues: [issue('ATLAS-3', ALICE), issue('ATLAS-4', BOB)] }],
  truncated: false,
} as unknown as BacklogView

const USERS: UserSummary[] = [
  { id: ALICE, username: 'alice', displayName: '김앨리스', email: null },
  { id: BOB, username: 'bob', displayName: null, email: null },
]

describe('collectAssigneeIds', () => {
  it('T1-1: 백로그와 스프린트를 합쳐 중복 없이 모은다', () => {
    expect(collectAssigneeIds(VIEW).sort()).toEqual([ALICE, BOB].sort())
  })

  it('T1-2: assigneeId 가 null 인 이슈는 제외한다', () => {
    expect(collectAssigneeIds(VIEW)).not.toContain(null)
  })

  it('T1-3: view 가 undefined 면 빈 배열 (조기 반환 앞에서 호출되므로 필수)', () => {
    expect(collectAssigneeIds(undefined)).toEqual([])
  })
})

describe('buildAssigneeNameMap', () => {
  it('T1-4: issueKey → displayName 을 만든다', () => {
    expect(buildAssigneeNameMap(VIEW, USERS).get('ATLAS-1')).toBe('김앨리스')
  })

  it('T1-5: displayName 이 null 이면 username 으로 폴백한다', () => {
    expect(buildAssigneeNameMap(VIEW, USERS).get('ATLAS-4')).toBe('bob')
  })

  it('T1-6: 미배정 이슈는 Map 에 넣지 않는다 (카드가 "미배정" 을 판정)', () => {
    expect(buildAssigneeNameMap(VIEW, USERS).has('ATLAS-2')).toBe(false)
  })

  it('T1-7: 조회 결과에 없는 담당자는 Map 에 넣지 않는다 (카드가 "?" 를 판정)', () => {
    expect(buildAssigneeNameMap(VIEW, []).size).toBe(0)
  })
})
```

**실패 메시지 (예상)**. `Failed to resolve import "./backlog-assignee-names"`

**GREEN**. `apps/web/src/components/backlog/backlog-assignee-names.ts`

```ts
// 백로그 카드 담당자 이름 조립 — 순수 함수 (FR-UX-13 F5)
import type { BacklogView } from '@/api/backlog'
import type { UserSummary } from '@/api/users'

/** 백로그 + 전 스프린트 이슈를 한 줄로 편다. view 가 없으면 빈 배열. */
function allIssues(view: BacklogView | undefined) {
  if (view === undefined) return []
  return [...view.backlog, ...view.sprints.flatMap((s) => s.issues)]
}

/**
 * 화면에 실제로 등장하는 담당자 UUID 를 중복 없이 모은다.
 *
 * `undefined` 를 받아내는 이유 — `BacklogBoard` 의 조기 반환보다 앞에서 호출되므로
 * 데이터가 아직 없는 렌더에서도 반드시 안전해야 한다.
 */
export function collectAssigneeIds(view: BacklogView | undefined): string[] {
  const ids = new Set<string>()
  for (const issue of allIssues(view)) {
    if (issue.assigneeId !== null) ids.add(issue.assigneeId)
  }
  return [...ids]
}

/**
 * issueKey → 담당자 표시이름 Map.
 *
 * 이름을 못 찾은 담당자는 **넣지 않는다** — `BacklogCard` 의 `AssigneeSlot` 이
 * 「이름 없음 + assigneeId 있음」을 `?` 로, 「이름 없음 + assigneeId 없음」을 `미배정` 로
 * 이미 가른다. 여기서 빈 문자열 같은 걸 넣으면 그 3상태가 깨진다.
 */
export function buildAssigneeNameMap(
  view: BacklogView | undefined,
  users: UserSummary[],
): Map<string, string> {
  const nameById = new Map(users.map((u) => [u.id, u.displayName ?? u.username]))
  const map = new Map<string, string>()
  for (const issue of allIssues(view)) {
    if (issue.assigneeId === null) continue
    const name = nameById.get(issue.assigneeId)
    if (name !== undefined) map.set(issue.key, name)
  }
  return map
}
```

**REFACTOR**. `allIssues` 를 파일 내부 헬퍼로 유지(외부 노출 금지 — 소비처 1곳).

**검증**.
- `cd apps/web && node_modules/.bin/vitest run src/components/backlog/backlog-assignee-names.test.ts > /tmp/t1.log 2>&1; echo "EXIT=$?"`
- 관련 기존 E2E. 없음 (순수 함수 신설, 렌더 무영향)
- 브라우저 눈확인. 해당 없음 (이 task 단독으로는 화면 변화 0)

---

### Task 2. 50 묶음 청크 사용자 조회 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-users.ts`, `apps/web/src/hooks/__tests__/use-users.test.tsx`]
- depends-on: []

> **★ 구현 중 정정 2건 (2026-08-05, implementer 보고 → controller 실측 확인).**
> 1. **테스트 파일 경로 정정.** plan 원안의 `src/hooks/use-users.test.tsx` 는 **이미 존재하는**
>    `src/hooks/__tests__/use-users.test.tsx` 와 같은 basename 의 두 번째 파일이 된다.
>    plan 작성 시 `ls src/hooks/*.test.*` 로만 확인해 **하위 디렉터리를 못 본 것**이 원인.
>    신규 테스트는 기존 `__tests__/use-users.test.tsx` 에 합친다.
> 2. **반환값 참조 안정성.** `results.flatMap(...)` 은 매 렌더 새 배열을 만든다.
>    `BacklogColumn` 은 `memo(BacklogColumnInner)` 이고 주석이 「`assigneeNames` 가 변하지
>    않으면 재렌더 스킵」이라 적고 있어, 그대로 두면 **기존 최적화가 통째로 죽는다**
>    (드래그 중 `overDroppableId` 변경으로 상시 재렌더). 처방은 `useQueries` 의
>    **`combine` 옵션** — 설치본 `@tanstack/react-query@^5.100.11` 이 지원한다
>    (`_tsup-dts-rollup.d.ts:869` 실측). 회귀 가드로 **T2-8 참조 유지 테스트**를 추가하고
>    비-공허 확인(combine 제거 → red)을 거친다.

**RED**. `apps/web/src/hooks/use-users.test.tsx` (신규 파일)

```tsx
// 사용자 조회 훅 단위 테스트 — 50 묶음 청크 (FR-UX-13 F5)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

vi.mock('@/api/users')

import { fetchUsersByIds } from '@/api/users'
import { chunkUserIds, useUsersByIdsChunked } from './use-users'

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return createElement(QueryClientProvider, { client }, children)
}

const ids = (n: number) => Array.from({ length: n }, (_, i) => `id-${i}`)

describe('chunkUserIds', () => {
  it('T2-1: 50개는 묶음 1개다 (백엔드 상한 경계)', () => {
    expect(chunkUserIds(ids(50))).toHaveLength(1)
  })

  it('T2-2: 51개는 50 + 1 두 묶음이다 (초과 시 400 회피)', () => {
    const chunks = chunkUserIds(ids(51))
    expect(chunks.map((c) => c.length)).toEqual([50, 1])
  })

  it('T2-3: 중복 id 는 한 번만 조회한다', () => {
    expect(chunkUserIds(['a', 'a', 'b'])).toEqual([['a', 'b']])
  })

  it('T2-4: 빈 배열은 묶음 0개다', () => {
    expect(chunkUserIds([])).toEqual([])
  })
})

describe('useUsersByIdsChunked', () => {
  beforeEach(() => { vi.mocked(fetchUsersByIds).mockReset() })

  it('T2-5: 60개 id 는 fetch 를 2회 호출하고 결과를 합친다', async () => {
    vi.mocked(fetchUsersByIds)
      .mockResolvedValueOnce([{ id: 'id-0', username: 'a', displayName: null, email: null }])
      .mockResolvedValueOnce([{ id: 'id-50', username: 'b', displayName: null, email: null }])

    const { result } = renderHook(() => useUsersByIdsChunked(ids(60)), { wrapper })

    await waitFor(() => { expect(result.current.data).toHaveLength(2) })
    expect(fetchUsersByIds).toHaveBeenCalledTimes(2)
    expect(vi.mocked(fetchUsersByIds).mock.calls[0]?.[0]).toHaveLength(50)
    expect(vi.mocked(fetchUsersByIds).mock.calls[1]?.[0]).toHaveLength(10)
  })

  it('T2-6: 담당자가 0명이면 서버를 부르지 않는다', async () => {
    const { result } = renderHook(() => useUsersByIdsChunked([]), { wrapper })
    await waitFor(() => { expect(result.current.data).toEqual([]) })
    expect(fetchUsersByIds).not.toHaveBeenCalled()
  })

  it('T2-7: 한 묶음이 실패하면 isError 가 true 다 (소비처가 fail-soft 판정에 쓴다)', async () => {
    vi.mocked(fetchUsersByIds).mockRejectedValue(new Error('boom'))
    const { result } = renderHook(() => useUsersByIdsChunked(ids(3)), { wrapper })
    await waitFor(() => { expect(result.current.isError).toBe(true) })
  })
})
```

**실패 메시지 (예상)**. `chunkUserIds is not exported from './use-users'`

**GREEN**. `apps/web/src/hooks/use-users.ts` 에 **추가** (기존 `useUsers`·`useUsersByIds` 무변경)

```ts
// 파일 상단 import 에 추가
import { useMemo } from 'react'
import { useQueries } from '@tanstack/react-query'

/**
 * 사용자 다건 조회 1회 상한.
 *
 * 백엔드 `UsersController.MAX_RESULTS`(50) 와 **같은 값이어야 한다** —
 * 초과해서 보내면 `?ids=` 가 400 을 돌려준다(`UsersController.kt:71`).
 */
export const USERS_BY_IDS_CHUNK_SIZE = 50

/** id 목록을 중복 제거 후 상한 이하 묶음으로 자른다. */
export function chunkUserIds(
  ids: string[],
  size: number = USERS_BY_IDS_CHUNK_SIZE,
): string[][] {
  const unique = [...new Set(ids)]
  const chunks: string[][] = []
  for (let i = 0; i < unique.length; i += size) {
    chunks.push(unique.slice(i, i + size))
  }
  return chunks
}

/**
 * id 개수와 무관하게 **전량** 조회한다 — 50개씩 나눠 병렬로 부르고 결과를 합친다.
 *
 * 기존 {@link useUsersByIds} 를 고치지 않고 새로 두는 이유. 그 훅은 소비처가 5곳이고
 * queryKey 구조를 바꾸면 그 5곳의 캐시·테스트가 함께 흔들린다. 백로그만 청크가 필요하다.
 *
 * @param ids 담당자 UUID 목록 (중복 허용 — 내부에서 제거)
 */
export function useUsersByIdsChunked(ids: string[]) {
  const chunks = useMemo(() => chunkUserIds(ids), [ids])
  const results = useQueries({
    queries: chunks.map((chunk) => ({
      queryKey: ['users', 'byIds', chunk],
      queryFn: () => fetchUsersByIds(chunk),
      staleTime: 30_000,
    })),
  })
  return {
    data: results.flatMap((r) => r.data ?? []),
    isError: results.some((r) => r.isError),
  }
}
```

**REFACTOR**. `useUsersByIds` 의 KDoc 에 "50 초과가 필요하면 `useUsersByIdsChunked`" 한 줄 추가.

**검증**.
- `cd apps/web && node_modules/.bin/vitest run src/hooks/use-users.test.tsx > /tmp/t2.log 2>&1; echo "EXIT=$?"`
- **기존 소비처 회귀 확인 필수** — `node_modules/.bin/vitest run src/components/filters/FilterBar.test.tsx src/components/board/BoardFilterBar.test.tsx > /tmp/t2b.log 2>&1; echo "EXIT=$?"`
- 관련 기존 E2E. 없음 (훅 신설, 기존 훅 무변경)
- 브라우저 눈확인. 해당 없음

---

### Task 3. BacklogBoard 배선 — 담당자 Map 채우기 (G1 해소)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/components/backlog/BacklogBoard.test.tsx`]
- depends-on: [1, 2]

**RED**. `BacklogBoard.test.tsx` 에 describe 추가

```tsx
describe('FR-UX-13 F5 — 담당자 이름 표시', () => {
  it('T3-1: 담당자가 있는 카드는 이니셜 아바타를 표시한다', async () => {
    // useUsersByIdsChunked 가 김앨리스를 돌려주도록 mock
    renderBoard()
    expect(await screen.findByLabelText('담당자: 김앨리스')).toBeInTheDocument()
  })

  it('T3-2: 미배정 이슈는 "미배정" 텍스트를 유지한다', async () => {
    renderBoard()
    expect(await screen.findByText('미배정')).toBeInTheDocument()
  })

  it('T3-3: 사용자 조회가 실패해도 카드는 렌더된다 (fail-soft, 스펙 S8)', async () => {
    // useUsersByIdsChunked → { data: [], isError: true }
    renderBoard()
    expect(await screen.findByText('ATLAS-1 제목')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
```

**실패 메시지 (예상)**. T3-1 이 `Unable to find a label with the text of: 담당자: 김앨리스`
— **지금 빈 Map 이라 `?` 만 그려지는 것이 red 의 증인**이다.

**GREEN**. `BacklogBoard.tsx`

1. import 추가.
```tsx
import { useMemo } from 'react'
import { useUsersByIdsChunked } from '@/hooks/use-users'
import { collectAssigneeIds, buildAssigneeNameMap } from './backlog-assignee-names'
```

2. `const { data: backlogView, isLoading } = useBacklog(projectKey)` 를 아래로 교체
   (**조기 반환보다 위**에 둔다).
```tsx
  const { data: backlogView, isLoading } = useBacklog(projectKey)

  // 담당자 이름 — 화면에 등장하는 id 만 모아 50개씩 나눠 전량 조회한다 (스펙 M1).
  // ★조기 반환(`isLoading`)보다 **위**에 있어야 한다. 아래로 내리면 렌더마다 훅 개수가
  //   달라져 React 가 즉사한다. 그래서 두 순수 함수가 `undefined` 를 받아낸다.
  const assigneeIds = useMemo(() => collectAssigneeIds(backlogView), [backlogView])
  const { data: assigneeUsers } = useUsersByIdsChunked(assigneeIds)
  const assigneeNames = useMemo(
    () => buildAssigneeNameMap(backlogView, assigneeUsers),
    [backlogView, assigneeUsers],
  )
```

3. **기존 `const assigneeNames = new Map<string, string>()`(`:102`) 삭제.**

**REFACTOR**. 삭제한 줄 자리에 남는 빈 줄 정리. `assigneeNames` 를 넘기는 두 곳
(`BacklogColumn` `:136` · `SprintColumn` `:147`)은 **prop 이름·타입 그대로**라 무변경.

**검증**.
- `cd apps/web && node_modules/.bin/vitest run src/components/backlog/ > /tmp/t3.log 2>&1; echo "EXIT=$?"`
- `node_modules/.bin/tsc -p tsconfig.app.json --noEmit > /tmp/t3tsc.log 2>&1; echo "EXIT=$?"`
- 관련 기존 E2E (계약 §5 사전 grep 결과). `e2e/backlog.spec.ts` · `e2e/issue-create-entry-points.spec.ts`
- 브라우저 눈확인 (라이트/다크). 백로그 칸·스프린트 칸 카드에 이니셜 아바타가 뜬다 · 미배정 카드는 `미배정` 텍스트다

---

### Task 4. 조회 실패 → 안내 + 재시도 (G2 해소)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/components/backlog/BacklogBoard.test.tsx`, `apps/web/src/i18n/backlog-labels.ts`, `apps/web/src/i18n/__tests__/create-entry-point-names.test.ts`]
- depends-on: [3]

**RED**. `BacklogBoard.test.tsx`

```tsx
describe('FR-UX-13 F5 — 조회 실패 안내와 재시도', () => {
  it('T4-1: 조회 실패 시 role="alert" 안내가 뜬다 (빈 화면 아님)', async () => {
    // useBacklog → { data: undefined, isLoading: false, isError: true, refetch }
    renderBoard()
    expect(await screen.findByRole('alert')).toHaveTextContent('백로그를 불러올 수 없습니다.')
  })

  it('T4-2: "다시 시도" 버튼이 refetch 를 호출한다 (새로고침 금지)', async () => {
    renderBoard()
    // ★exact: true 필수 — 토스트 문구 "…다시 시도해 주세요." 가 부분 일치로 걸린다
    await userEvent.click(screen.getByRole('button', { name: '다시 시도', exact: true }))
    expect(mockRefetch).toHaveBeenCalledTimes(1)
  })

  it('T4-2b: 재조회 중에는 버튼이 비활성화되고 라벨이 바뀐다 (design review D3)', async () => {
    // useBacklog → { isError: true, isFetching: true, refetch }
    renderBoard()
    const button = screen.getByRole('button', { name: '다시 시도 중…', exact: true })
    expect(button).toBeDisabled()
  })

  it('T4-3: 로딩 중에는 에러 블록이 뜨지 않는다 (스펙 E8)', () => {
    renderBoard()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('T4-4: 정상 조회 + truncated 일 때 경고 배너는 1개뿐이다 (스펙 E10 상호 배타)', async () => {
    renderBoard()
    expect(await screen.findAllByRole('alert')).toHaveLength(1)
  })
})
```

**실패 메시지 (예상)**. T4-1 이 `Unable to find role="alert"` — 지금은 빈 `<div/>` 다.

**GREEN**.

1. `i18n/backlog-labels.ts` 에 추가.
```ts
  /** 백로그 조회 실패 안내 (FR-UX-13 F5) */
  loadFailed: '백로그를 불러올 수 없습니다.',

  /**
   * 조회 실패 재시도 버튼.
   *
   * ⚠️ `moveFailedError`('…다시 시도해 주세요.')가 이 값을 **부분 문자열로 포함**한다.
   * 둘은 role 이 달라(button vs 토스트 텍스트) 공존해도 되지만, 테스트 조회는 반드시
   * `getByRole('button', { name: '다시 시도', exact: true })` 로 한다.
   */
  retry: '다시 시도',

  /**
   * 재조회 중 버튼 라벨 (design review D3).
   *
   * ⚠️ `retry`('다시 시도')를 부분 문자열로 포함하지만 **판별식 목록에 넣지 않는다** —
   * `create-entry-point-names.test.ts` §제외 3종 ②「같은 버튼의 다른 상태」에 해당한다
   * (`submitButton`/`submitButtonPending` 과 동형). 한 버튼이 둘 중 하나만 보이므로
   * 같은 순간에 두 이름이 화면에 있을 수 없다. **넣으면 판별식이 구조적으로 실패한다.**
   */
  retrying: '다시 시도 중…',
```

2. `create-entry-point-names.test.ts` 의 `BACKLOG_SCREEN_BUTTON_NAMES` 에 **`retry` 만** 등록.
```ts
  // ★FR-UX-13 F5 가 추가하는 진입점 1종 — 등록을 빠뜨리면 판별식이 조용히 공허해진다.
  // `retrying`('다시 시도 중…')은 **넣지 않는다** — 같은 버튼의 다른 상태(§제외 3종 ②).
  retry: backlogLabels.retry,
```
그리고 같은 파일의 「신규 진입점 3종이 화면 집합에 실제로 들어 있다」 it 에 한 줄 추가.
```ts
    expect(Object.values(BACKLOG_SCREEN_BUTTON_NAMES)).toContain(backlogLabels.retry)
```

3. `BacklogBoard.tsx` — `Button` import 추가 + `isError`·`isFetching`·`refetch` 소비 + 에러 블록.
```tsx
import { Button } from '@/components/ui/button'
...
  const { data: backlogView, isLoading, isError, isFetching, refetch } = useBacklog(projectKey)
...
  if (isError) {
    // 재시도 수단이 없으면 사용자는 새로고침 말고 탈출구가 없다 (ActiveProjectGate 선례).
    return (
      <div role="alert" className="flex flex-col items-start gap-3 p-8 text-destructive">
        <p>{backlogLabels.loadFailed}</p>
        {/* ★재조회 중에는 버튼이 스스로 상태를 말한다 (design review D3).
            `isLoading` 은 최초 1회만 true 라 재조회를 못 잡는다 — `isFetching` 이어야 한다.
            비활성화가 연타로 인한 중복 요청을 구조적으로 막는다. */}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={isFetching}
          onClick={() => { void refetch() }}
        >
          {isFetching ? backlogLabels.retrying : backlogLabels.retry}
        </Button>
      </div>
    )
  }

  if (backlogView === undefined) return <div />
```
**순서 고정** — `isLoading` → `isError` → `undefined` 순. `isError` 를 `isLoading` 앞에 두면
재조회 중에도 에러 화면이 깜빡인다.

**REFACTOR**. 없음 (12줄 추가, 추출할 중복 없음).

**검증**.
- `cd apps/web && node_modules/.bin/vitest run src/components/backlog/ src/i18n/__tests__/ > /tmp/t4.log 2>&1; echo "EXIT=$?"`
- `node_modules/.bin/eslint src > /tmp/t4lint.log 2>&1; echo "EXIT=$?"`
- 관련 기존 E2E. `e2e/backlog.spec.ts` (`<h1>백로그</h1>` 가 라우트 소유라 에러 상태에서도 살아야 한다)
- 브라우저 눈확인 (라이트/다크). 에러 안내 문구 + `다시 시도` 버튼 대비 · 버튼 클릭 시 정상 복귀

---

### Task 5. E2E 시나리오 2종 + MSW 실패 토글

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/backlog.spec.ts`, `apps/web/src/mocks/backlog-handlers.ts`]
- depends-on: [3, 4]

**RED**. `e2e/backlog.spec.ts` 에 추가

```ts
// LS_KEY_BACKLOG_FAIL 은 backlog-handlers.ts 정본 값의 미러다 (import.spec.ts 선례 동일 패턴).
const LS_KEY_BACKLOG_FAIL = '__bts_e2e_backlog_fail'

test('S9: 담당자가 있는 카드는 이니셜 아바타를 표시한다 (FR-UX-13 F5)', async ({ page }) => {
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/backlog')
  await expect(page.getByLabel('담당자: 김앨리스').first()).toBeVisible()
})

test('S10: 백로그 조회 실패 시 안내와 재시도가 보인다 (FR-UX-13 F5)', async ({ page }) => {
  await loginAsAlice(page)
  // addInitScript 는 로그인 뒤·goto 앞에 등록해야 첫 로드부터 먹는다
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_KEY_BACKLOG_FAIL)
  await page.goto('/projects/ATLAS/backlog')

  await expect(page.getByRole('alert')).toContainText('백로그를 불러올 수 없습니다.')
  await expect(page.getByRole('button', { name: '다시 시도', exact: true })).toBeVisible()
  // 계약 — h1 은 라우트 소유라 에러 상태에서도 살아 있다
  await expect(page.getByRole('heading', { name: '백로그', level: 1 })).toBeVisible()
})
```

**실패 메시지 (예상)**. S9 는 `?` 만 있어 라벨 미발견 · S10 은 토글이 없어 정상 렌더.

**GREEN**. `src/mocks/backlog-handlers.ts`

```ts
/**
 * E2E 시나리오 토글 — 이 플래그가 'true' 면 백로그 조회가 500 을 돌려준다.
 * 패턴 출처. e2e-msw-scenario-toggle-localstorage-flag (import-handlers.ts 선례).
 */
export const LS_KEY_BACKLOG_FAIL = '__bts_e2e_backlog_fail'
```
`getBacklogHandler` 본문 **맨 앞**에 삽입.
```ts
    if (globalThis.localStorage?.getItem(LS_KEY_BACKLOG_FAIL) === 'true') {
      return HttpResponse.json(
        { title: 'Internal Server Error', status: 500 },
        { status: 500 },
      )
    }
```

**REFACTOR**. 없음.

**검증**.
- ★**spec 파일로 한정** — `cd apps/web && node_modules/.bin/playwright test e2e/backlog.spec.ts > /tmp/t5.log 2>&1; echo "EXIT=$?"`
  (worktree 경로에 `backlog` 가 들어 있어 `playwright test backlog` 로 부르면 절대경로 정규식
  매칭으로 전 spec 668건이 걸린다 — #341 실측)
- 회귀 동반. `node_modules/.bin/playwright test e2e/issue-create-entry-points.spec.ts e2e/board-reorder.spec.ts > /tmp/t5b.log 2>&1; echo "EXIT=$?"`
- 브라우저 눈확인 (라이트/다크). S10 토글을 손으로 심어 에러 화면 → 재시도 → 복귀 4항목

---

### Task 6. 정본 정정 + ADR + 인덱스 동기화

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/decisions/2026-08-05-fr-ux-13-f5-backlog-card-assignee.md`, `docs/INDEX.md`, `docs/INDEX-fr.md`, `docs/INDEX-recent.md`]
- depends-on: [5]

**작업** (테스트 대상 아님 — 문서 정합).

1. `personalization.md` §4.11 F5 서술 정정.
   - `BacklogBoard.tsx:217` → `:102` · `:214` → `:99`
   - "`board.tsx:333` 의 `useUsersByIds` 조립 패턴을 복제한다" →
     **"보드 라우트는 전체 목록(`MAX_RESULTS=50`)을 쓰므로 복제 금지. 담당자 id 만 50 묶음으로
     전량 조회한다"** + 보드의 동일 잠재 결함을 후속 항목으로 명시 (M2)
   - D6·D7 체크박스는 **머지 시점에** 표시 (진척 카운트는 bts-merge 소관)
2. ADR 작성 — M1·M2 결정과 근거, 「보드↔백로그 비대칭」, 훅 순서 함정, 판별식 수동 등록 구멍.
3. `node scripts/build-doc-index.mjs` 재실행.

**검증**.
- `bash scripts/verify-master-plan.sh > /tmp/t6.log 2>&1; echo "EXIT=$?"` (0 이어야 함)
- `node scripts/build-doc-index.mjs > /tmp/t6idx.log 2>&1; echo "EXIT=$?"` 후 `git status` 로 drift 0
- 관련 기존 E2E. 없음 (문서만)
- 브라우저 눈확인. 해당 없음

---

## Plan 메타

- task 수: **6**
- 예상 시간: 직렬 약 25분 · wave 병렬 적용 시 약 18분 (예상 wave 수: **5**)
  - wave1. T1 ∥ T2 (파일 교집합 0) → wave2. T3 → wave3. T4 → wave4. T5 → wave5. T6
- 구현 규율: **ui 시각 검증 트랙** (red-first 순서 면제, 기존 E2E 동반 실행 + 브라우저 눈확인 필수)
  - 단 T1·T2 는 순수 함수/훅이라 **실제 red 를 먼저 본다** (시각 요소 없음)
  - T3 의 red(`?` 만 그려짐)는 **이 결함의 진짜 증인**이므로 반드시 실패를 눈으로 확인하고 넘어간다
- 병렬 dispatch: bts-impl 이 `depends-on` + `files` 교집합으로 wave 계산
- 추가 검증: `tsc --noEmit` · `eslint src` · vitest 전량 · playwright 3 spec · `verify-master-plan.sh`
- 신규 파일 3 · 수정 파일 6 · 백엔드 0줄 · 마이그레이션 0 · 신규 의존성 0

## 리뷰 결과

### plan-design-review (2026-08-05)

**리뷰 대상.** 이 plan 파일 (Maxi 확정 D1=A). **AI 시각 목업 생략** (Maxi 확정 D2=A —
신규 비주얼 결정 0, 두 요소 모두 코드베이스에 정본 존재, 계약 §6 실브라우저 눈확인이 더 강한 증거).

| 패스 | 점수 | 조치 |
|---|---|---|
| 1. 정보 구조 | 8 → **9** | F1 확정 기록 |
| 2. 상호작용 상태 | 7 → **10** | F2 해소 (D3=A) |
| 3. 사용자 여정 | 8 → **9** | 감정 곡선 기록 |
| 4. AI 슬롭 위험 | **10** | 신규 비주얼 0 — 해당 없음 |
| 5. 디자인 시스템 정합 | 9 → **10** | 토큰·프리미티브 전량 기존 자산 |
| 6. 반응형·접근성 | 6 → **8** | F3 눈확인 이월 · F4 TODO 후보 |
| 7. 미해결 결정 | 1 해소 / 2 이월 | 아래 |
| **종합** | **7 → 9** | |

#### 상태 매트릭스 (Pass 2 산출물)

| 기능 | 로딩 | 빈 상태 | 에러 | 성공 | 부분 실패 |
|---|---|---|---|---|---|
| 백로그 목록 | `로딩 중...` 중앙 | 칸은 뜨고 `이슈 없음` | `role="alert"` + 재시도 버튼 | 칸 + 카드 렌더 | `truncated` 경고 배너 |
| 담당자 이름 | 카드 먼저 렌더, 이름은 나중에 채워짐 | 담당자 0명 → 조회 자체를 안 함 | **카드는 살고 담당자만 `?`** (fail-soft) | 이니셜 아바타 + `담당자: 이름` | 일부 id 미해석 → 그 카드만 `?` |
| 재시도 | 버튼 `다시 시도 중…` + 비활성 | — | 실패 시 에러 블록 유지 | 백로그 정상 렌더 | — |

#### 발견 4건

**F1 (Pass 1) — 에러 시 스프린트 생성 폼도 함께 사라진다.** `if (isError)` 조기 반환이
`CreateSprintForm` 보다 위에 있어 화면에는 `<h1>백로그</h1>`(라우트 소유) + 에러 블록만 남는다.
**의도로 확정.** 목록을 못 불러온 상태에서 스프린트만 만들면 결과를 확인할 방법이 없어 더 혼란스럽다.
`ActiveProjectGate` 의 전면 교체 선례와 동일하다.

**F2 (Pass 2) — 재시도 중 피드백 부재 → Maxi 확정 D3=A 로 해소.**
`isLoading` 은 최초 1회만 true 라 재조회를 못 잡는다. `isFetching` 으로 버튼을 비활성화하고
라벨을 `다시 시도 중…` 으로 바꾼다. **★부수 소득** — 그 라벨이 `다시 시도` 를 부분 문자열로
포함해 판별식이 깨질 뻔했으나, 「같은 버튼의 다른 상태」 제외 규칙(§제외 3종 ②)에 해당함을
확인해 **목록에 넣지 않는 것**으로 확정했다. 넣었으면 구조적 red 였다.

**F3 (Pass 6) — 로딩 ↔ 에러 전환 시 미세 레이아웃 점프.** 로딩은 `py-16` 중앙 정렬,
에러는 `p-8` 좌측 정렬이라 세로 위치가 튄다. **선례 준수로 그대로 둔다** — `ActiveProjectGate`
가 이미 `p-8`, 기존 백로그 로딩이 이미 `py-16` 이고, 여기서 한쪽을 맞추면 이 PR 밖 화면의
로딩 관례를 건드린다. **눈확인 항목에 전환 점프 관찰을 추가**해 실제로 거슬리면 후속으로 뺀다.

**F4 (Pass 6) — 재시도 버튼 터치 타깃 32px.** `size="sm"` 은 `h-8`(32px)로 모바일 권장
44px 미만이다. `ActiveProjectGate` 도 동일해 **프로젝트 전역 관례**이므로 이 PR 에서 바꾸지
않는다. **TODO 후보** — 게이트 1 에서 Maxi 판단.

#### 초기 평가 중 철회 1건

「이니셜 아바타의 긴 이름·다크모드 대비·오버플로우 미명시」를 초기에 공백으로 지목했으나
**실측으로 철회**한다. `AssigneeSlot` 은 `assigneeName[0]` **한 글자만** 고정 24px 원에 넣고
색은 `bg-primary` / `text-primary-foreground` 토큰이라 오버플로우도 대비 문제도 발생할 수 없다.
`username` 은 Zod `min(1)` 이라 빈 이니셜도 불가능하다.

#### NOT in scope (검토 후 명시적 이연)

- **보드 화면의 동일 결함** — Maxi 확정 M2. 별도 트랙.
- **로딩/에러 정렬 통일** — F3. 이 PR 밖 화면의 관례를 건드린다.
- **터치 타깃 44px** — F4. 전역 관례 변경이라 단독 PR 감.
- **`CardAssigneeDisplay` 3상태 타입 이식** — 스펙 FR-3 근거. 사용자에게 보이는 변화 0.

#### What already exists (재사용 확정)

| 필요한 것 | 이미 있는 것 |
|---|---|
| 담당자 아바타 3상태 | `BacklogCard.tsx:83` `AssigneeSlot` — 이니셜/`?`/미배정 완비 |
| 에러 + 재시도 UI | `ActiveProjectGate.tsx:34` — `role="alert"` + outline Button |
| 버튼 프리미티브 | `components/ui/button.tsx` (`button-primitive-usage.test.ts` 가 강제) |
| 사용자 다건 조회 | `hooks/use-users.ts` `fetchUsersByIds` |
| 병렬 쿼리 선례 | `FavoritesMenu.tsx:125` · `RecentIssuesMenu.tsx:73` `useQueries` |
| E2E 실패 주입 | `import-handlers.ts` localStorage 토글 패턴 |
| i18n 판별식 | `i18n/__tests__/create-entry-point-names.test.ts` |

#### 미해결 결정 (게이트 1 로 이월)

| 결정 필요 | 미루면 생기는 일 |
|---|---|
| F4 터치 타깃 44px 를 TODOS.md 에 올릴까 | 모바일에서 재시도 버튼이 계속 누르기 어렵다 |
| F3 전환 점프가 실제로 거슬리는가 | 눈확인에서 판정. 거슬리면 후속 PR |

**BLOCKER. 없음.**

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 0 | — | — (ui 타입은 `/bts-review-plan` 분기상 design review 단독) |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | CLEAR (FULL) | score: 7/10 → 9/10, 4 decisions |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** DESIGN CLEARED — 7/10 → 9/10, BLOCKER 0. `/bts` 게이트 2 의 codereview 가
구현 후 절대 규칙·구조 검증을 담당한다.

**UNRESOLVED DECISIONS:**
- F4 — 재시도 버튼 터치 타깃 32px(권장 44px). 전역 관례라 이 PR 밖. TODOS.md 등재 여부는 게이트 1 에서 Maxi 판단
- F3 — 로딩(`py-16` 중앙) ↔ 에러(`p-8` 좌측) 전환 점프. 브라우저 눈확인에서 판정, 거슬리면 후속 PR
