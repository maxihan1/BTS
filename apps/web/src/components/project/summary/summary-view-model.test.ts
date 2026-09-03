// 요약 화면 순수 변환 단위 테스트 — 델타 문구 · 분포 행 · 활동 요약 (Jira 패리티 J4)
import { describe, it, expect } from 'vitest'
import type { ProjectActivityEntry } from '@/api/project-summary'
import {
  assigneeRows,
  formatActivityTime,
  formatOverdue,
  formatWindowDelta,
  priorityRows,
  statusRows,
  summarizeEntry,
  typeRows,
} from './summary-view-model'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

describe('formatWindowDelta', () => {
  it('증가는 부호를 붙인다', () => {
    expect(formatWindowDelta({ current: 12, previous: 8 })).toContain('+4')
  })

  it('감소는 음수 그대로 — 부호를 두 번 붙이지 않는다', () => {
    const text = formatWindowDelta({ current: 8, previous: 12 })
    expect(text).toContain('-4')
    expect(text).not.toContain('+-')
  })

  it('변동 없음은 「+0」이 아니라 전용 문구다', () => {
    expect(formatWindowDelta({ current: 5, previous: 5 })).toBe(labels.delta.flat)
  })
})

describe('formatOverdue', () => {
  it('지연 0 은 「지연 없음」', () => {
    expect(formatOverdue(0)).toBe(labels.delta.noOverdue)
  })

  it('지연이 있으면 건수를 싣는다', () => {
    expect(formatOverdue(2)).toBe(`2${labels.delta.overdueSuffix}`)
  })
})

describe('분포 행 변환', () => {
  it('상태 표시명이 없으면 상태 키로 폴백한다', () => {
    const rows = statusRows([{ statusKey: 'CUSTOM', category: 'TODO', count: 1 }])
    expect(rows[0]?.label).toBe('CUSTOM')
  })

  it('우선순위 표시명이 없으면 숫자를 쓴다', () => {
    expect(priorityRows([{ priority: 9, count: 1 }])[0]?.label).toBe('9')
  })

  it('작업 유형은 표시명을 그대로 쓴다', () => {
    expect(typeRows([{ typeKey: 'story', typeName: '스토리', count: 3 }])[0]?.label).toBe('스토리')
  })

  it('미할당과 「이름 조회 실패」를 다른 문구로 가른다 — 뭉치면 미할당이 부풀어 보인다', () => {
    const rows = assigneeRows([
      { count: 4 },
      { assigneeId: 'u-9', count: 2 },
    ])
    expect(rows[0]?.label).toBe(labels.distribution.unassigned)
    expect(rows[1]?.label).toContain(labels.distribution.unknownAssignee)
    expect(rows[1]?.label).not.toBe(labels.distribution.unassigned)
  })

  it('미할당 행은 고정 id 를 갖는다 — React key 충돌 차단', () => {
    expect(assigneeRows([{ count: 4 }])[0]?.id).toBe('unassigned')
  })
})

describe('summarizeEntry', () => {
  const base: ProjectActivityEntry = {
    issueKey: 'BTS-1',
    createdAt: '2026-09-03T10:00:00Z',
    items: [],
  }

  it('항목이 없으면 빈 문자열 — 호출자가 그 줄을 감춘다', () => {
    expect(summarizeEntry(base)).toBe('')
  })

  it('항목이 하나면 필드 표시명만', () => {
    const text = summarizeEntry({
      ...base,
      items: [{ field: 'status', fromValue: null, toValue: null, fromLabel: null, toLabel: null }],
    })
    expect(text).not.toBe('')
    expect(text).not.toContain(labels.activity.moreItemsSuffix)
  })

  it('항목이 여럿이면 「외 N건」을 붙인다', () => {
    const item = { field: 'status', fromValue: null, toValue: null, fromLabel: null, toLabel: null }
    const text = summarizeEntry({ ...base, items: [item, item, item] })
    expect(text).toContain(`${labels.activity.moreItemsPrefix}2${labels.activity.moreItemsSuffix}`)
  })

  it('알 수 없는 필드 키는 키 자체로 폴백한다 — 빈 라벨을 내지 않는다', () => {
    const text = summarizeEntry({
      ...base,
      items: [
        { field: 'customField:x', fromValue: null, toValue: null, fromLabel: null, toLabel: null },
      ],
    })
    expect(text).toBe('x')
  })
})

describe('formatActivityTime', () => {
  it('파싱 불가한 문자열은 원문을 그대로 낸다', () => {
    expect(formatActivityTime('not-a-date')).toBe('not-a-date')
  })

  it('ISO 시각은 원문과 다른 로컬 표기가 된다', () => {
    expect(formatActivityTime('2026-09-03T10:00:00Z')).not.toBe('2026-09-03T10:00:00Z')
  })
})
