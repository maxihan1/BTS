// 백로그 클라이언트 필터 순수 함수 테스트 — 축 4종·왕복 매핑·원본 불변·브랜드 타입 (FR-UX-13 F16 T1)
import { describe, it, expect, expectTypeOf } from 'vitest'
import type { BacklogIssue, BacklogView } from '@/api/backlog'
import {
  NO_EPIC,
  filterBacklogView,
  searchToFilter,
  filterToSearch,
  isEmptyFilter,
  emptyBacklogFilter,
  toFilterBarValue,
} from '@/lib/backlog-filter'
import type { BacklogFilter, FilteredBacklogView } from '@/lib/backlog-filter'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ALICE = '11111111-1111-4111-8111-111111111111'
const BOB = '22222222-2222-4222-8222-222222222222'

function issue(overrides: Partial<BacklogIssue> & { key: string }): BacklogIssue {
  return {
    summary: '기본 제목',
    currentStateKey: 'todo',
    assigneeId: null,
    priority: 3,
    rank: '0|hzzzzz:',
    version: 1,
    epicKey: null,
    typeKey: 'task',
    labels: [],
    originalEstimateSeconds: null,
    ...overrides,
  }
}

/**
 * 백로그 2건 + 스프린트 1개(이슈 2건).
 * 필터가 **백로그 섹션과 스프린트 섹션 양쪽에** 걸리는지 보려면 두 곳 모두에 대상이 필요하다.
 */
function view(): BacklogView {
  return {
    backlog: [
      issue({ key: 'A-1', summary: '결제 실패 로그', assigneeId: ALICE, epicKey: 'A-100' }),
      issue({ key: 'A-2', summary: '로그인 개편', assigneeId: null, epicKey: null }),
    ],
    sprints: [
      {
        sprint: {
          sprintId: '33333333-3333-4333-8333-333333333333',
          boardId: '10000000-0000-4000-8000-000000000001',
          name: '스프린트 1',
          goal: null,
          status: 'ACTIVE',
          startDate: null,
          endDate: null,
          version: 1,
        },
        issues: [
          issue({ key: 'A-3', summary: '결제 취소', assigneeId: BOB, epicKey: 'A-100' }),
          issue({ key: 'A-4', summary: '프로필 수정', assigneeId: ALICE, epicKey: 'A-200' }),
        ],
      },
    ],
    truncated: false,
  }
}

/** 필터 결과에서 남은 이슈 키를 섹션 구분 없이 평탄화한다. */
function keys(v: FilteredBacklogView): string[] {
  return [...v.backlog, ...v.sprints.flatMap((s) => s.issues)].map((i) => i.key)
}

function filter(overrides: Partial<BacklogFilter> = {}): BacklogFilter {
  return { ...emptyBacklogFilter(), ...overrides }
}

// ─────────────────────────────────────────────────────────────────────────────
// filterBacklogView — 축 4종
// ─────────────────────────────────────────────────────────────────────────────

describe('filterBacklogView', () => {
  it('T1-1: 빈 필터는 전량을 그대로 통과시킨다', () => {
    expect(keys(filterBacklogView(view(), emptyBacklogFilter()))).toEqual(['A-1', 'A-2', 'A-3', 'A-4'])
  })

  it('T1-2: 제목 부분일치로 거른다 — 백로그·스프린트 양쪽에 동일 적용', () => {
    // '결제'는 백로그 A-1 과 스프린트 A-3 에 각각 하나씩 있다.
    // 한쪽 섹션에만 필터를 걸면 이 단언이 깨진다 (FR F16-7).
    expect(keys(filterBacklogView(view(), filter({ query: '결제' })))).toEqual(['A-1', 'A-3'])
  })

  it('T1-3: 제목 일치는 대소문자를 무시한다', () => {
    const v: BacklogView = { ...view(), backlog: [issue({ key: 'A-9', summary: 'Payment Retry' })], sprints: [] }
    expect(keys(filterBacklogView(v, filter({ query: 'payment' })))).toEqual(['A-9'])
  })

  it('T1-4: 담당자 id 로 거른다 — 양쪽 섹션', () => {
    expect(keys(filterBacklogView(view(), filter({ assigneeIds: [ALICE] })))).toEqual(['A-1', 'A-4'])
  })

  it('T1-5: includeUnassigned 는 미배정만 남긴다', () => {
    expect(keys(filterBacklogView(view(), filter({ includeUnassigned: true })))).toEqual(['A-2'])
  })

  it('T1-6: 담당자 + 미배정은 합집합이다', () => {
    expect(keys(filterBacklogView(view(), filter({ assigneeIds: [BOB], includeUnassigned: true })))).toEqual([
      'A-2',
      'A-3',
    ])
  })

  it('T1-7: 에픽 키로 거른다', () => {
    expect(keys(filterBacklogView(view(), filter({ epicKeys: ['A-100'] })))).toEqual(['A-1', 'A-3'])
  })

  it('T1-8: NO_EPIC 센티널은 에픽 없는 이슈만 남긴다', () => {
    expect(keys(filterBacklogView(view(), filter({ epicKeys: [NO_EPIC] })))).toEqual(['A-2'])
  })

  it('T1-9: 에픽 키 + NO_EPIC 은 합집합이다', () => {
    expect(keys(filterBacklogView(view(), filter({ epicKeys: ['A-200', NO_EPIC] })))).toEqual(['A-2', 'A-4'])
  })

  it('T1-10: 서로 다른 축은 교집합(AND)이다', () => {
    // 담당자 ALICE ∩ 에픽 A-100 → A-1 만 (A-4 는 ALICE 지만 에픽이 A-200)
    expect(keys(filterBacklogView(view(), filter({ assigneeIds: [ALICE], epicKeys: ['A-100'] })))).toEqual(['A-1'])
  })

  it('T1-11: 스프린트 메타는 이슈가 0건이 되어도 보존된다', () => {
    // 섹션 자체가 사라지면 "스프린트가 없어졌다"로 보인다 — 섹션은 남고 카드만 빈다.
    const result = filterBacklogView(view(), filter({ query: '존재하지않는제목' }))
    expect(result.sprints).toHaveLength(1)
    expect(result.sprints[0]?.sprint.name).toBe('스프린트 1')
    expect(result.sprints[0]?.issues).toEqual([])
  })

  it('T1-12: truncated 플래그를 그대로 전달한다 — 필터가 잘림을 풀지 않는다', () => {
    // 스펙 §8 C2. 필터는 이미 잘려 도착한 응답 위에서 돌 뿐이다.
    const v: BacklogView = { ...view(), truncated: true }
    expect(filterBacklogView(v, filter({ query: '결제' })).truncated).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// R6 가드 — 원본 불변
// ─────────────────────────────────────────────────────────────────────────────

describe('filterBacklogView — 원본 불변 (R6 가드)', () => {
  it('T1-13: 입력 view 를 변형하지 않는다', () => {
    const original = view()
    const snapshotKeys = keys(original as unknown as FilteredBacklogView)

    filterBacklogView(original, filter({ query: '결제' }))

    expect(keys(original as unknown as FilteredBacklogView)).toEqual(snapshotKeys)
    expect(original.backlog).toHaveLength(2)
    expect(original.sprints[0]?.issues).toHaveLength(2)
  })

  it('T1-14: 결과는 입력과 다른 배열 인스턴스다 (참조 공유로 인한 사후 변형 차단)', () => {
    const original = view()
    const result = filterBacklogView(original, emptyBacklogFilter())
    expect(result.backlog).not.toBe(original.backlog)
    expect(result.sprints[0]?.issues).not.toBe(original.sprints[0]?.issues)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// BLOCKER-1 처방 — 브랜드 타입이 R6 를 타입으로 닫는다
// ─────────────────────────────────────────────────────────────────────────────

describe('FilteredBacklogView 브랜드 타입 (BLOCKER-1)', () => {
  it('T1-15: FilteredBacklogView 는 BacklogView 에 대입할 수 없다', () => {
    // 스프린트 완료·DnD 핸들러는 BacklogView 를 받는다. 필터 결과를 넘기면
    // **컴파일이 깨져야** 한다 — 규율이 아니라 타입이 R6 를 막는다.
    expectTypeOf<FilteredBacklogView>().not.toMatchTypeOf<BacklogView>()

    const filtered: FilteredBacklogView = filterBacklogView(view(), emptyBacklogFilter())
    // @ts-expect-error — 필터된 뷰를 원본 자리에 넘기는 것은 R6(이관 집합 누락) 그 자체다
    const asOriginal: BacklogView = filtered
    expect(asOriginal).toBeDefined()
  })

  it('T1-16: BacklogView 는 filterBacklogView 의 입력으로 받아들여진다', () => {
    // 짝 단언. 위 T1-15 만 있으면 "아무것도 대입 안 되는 타입"으로 만들어도 초록이 된다.
    expectTypeOf(filterBacklogView).parameter(0).toMatchTypeOf<BacklogView>()
    expect(filterBacklogView(view(), emptyBacklogFilter())).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// URL 왕복 매핑
// ─────────────────────────────────────────────────────────────────────────────

describe('searchToFilter / filterToSearch', () => {
  it('T1-17: 단일 문자열도 배열로 정규화한다', () => {
    expect(searchToFilter({ assignee: ALICE, epic: 'A-100', q: '결제' })).toEqual({
      query: '결제',
      assigneeIds: [ALICE],
      includeUnassigned: false,
      epicKeys: ['A-100'],
    })
  })

  it('T1-18: assignee 의 unassigned 센티널은 includeUnassigned 로 바뀐다', () => {
    const result = searchToFilter({ assignee: [ALICE, 'unassigned'] })
    expect(result.assigneeIds).toEqual([ALICE])
    expect(result.includeUnassigned).toBe(true)
  })

  it('T1-19: 빈 필터는 빈 search 객체가 된다 — 빈 파라미터 잔존 금지', () => {
    expect(filterToSearch(emptyBacklogFilter())).toEqual({})
  })

  it('T1-20: 왕복 변환이 값을 보존한다', () => {
    const f = filter({ query: '결제', assigneeIds: [ALICE], includeUnassigned: true, epicKeys: ['A-100', NO_EPIC] })
    expect(searchToFilter(filterToSearch(f))).toEqual(f)
  })

  it('T1-21: 알 수 없는 에픽 키도 그대로 실어 나른다 — 해석은 소비처 책임 (EC9)', () => {
    // 라우트가 throw 하지 않고 축만 비우는 동작(EC9)은 T8 소관이다.
    // 이 순수 함수는 값 판단을 하지 않는다.
    expect(searchToFilter({ epic: '없는키' }).epicKeys).toEqual(['없는키'])
  })
})

describe('isEmptyFilter', () => {
  it('T1-22: 아무 축도 없으면 true', () => {
    expect(isEmptyFilter(emptyBacklogFilter())).toBe(true)
  })

  it('T1-23: 축이 하나라도 있으면 false', () => {
    expect(isEmptyFilter(filter({ query: '결제' }))).toBe(false)
    expect(isEmptyFilter(filter({ assigneeIds: [ALICE] }))).toBe(false)
    expect(isEmptyFilter(filter({ includeUnassigned: true }))).toBe(false)
    expect(isEmptyFilter(filter({ epicKeys: [NO_EPIC] }))).toBe(false)
  })

  it('T1-24: 공백만 있는 query 는 조건으로 치지 않는다', () => {
    expect(isEmptyFilter(filter({ query: '   ' }))).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// CONCERN-1 처방 — labels/componentIds 는 FilterBar 경계에서만 생긴다
// ─────────────────────────────────────────────────────────────────────────────

describe('toFilterBarValue (CONCERN-1)', () => {
  it('T1-25: BacklogFilter 자체는 labels·componentIds 를 갖지 않는다', () => {
    // 백로그 응답에 두 필드가 없으므로 필터 모델에도 없어야 한다.
    // 모델이 들고 있으면 픽스처가 그것을 채울 수 있고, 도달 불가 상태를 지키는
    // 가짜 테스트가 생긴다 (memory: unreachable-state-fixture-is-fake-green).
    expectTypeOf<BacklogFilter>().not.toHaveProperty('labels')
    expectTypeOf<BacklogFilter>().not.toHaveProperty('componentIds')
  })

  it('T1-26: FilterBar 경계에서만 빈 배열을 붙인다', () => {
    expect(toFilterBarValue(filter({ assigneeIds: [ALICE], includeUnassigned: true }))).toEqual({
      assigneeIds: [ALICE],
      includeUnassigned: true,
      labels: [],
      componentIds: [],
    })
  })
})
