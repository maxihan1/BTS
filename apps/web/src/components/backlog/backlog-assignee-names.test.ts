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
