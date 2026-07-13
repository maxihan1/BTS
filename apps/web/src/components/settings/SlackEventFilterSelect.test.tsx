// SlackEventFilterSelect 컴포넌트 단위 테스트 — 그룹 렌더·controlled 선택·토글 onChange·disabled (FR-SL-06 D6 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SLACK_EVENT_TYPE_CATALOG } from '@/lib/slack-event-types'
import { SlackEventFilterSelect } from './SlackEventFilterSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 그룹 렌더 — 10종 이벤트 + 그룹 헤더(이슈/스프린트/자동화)
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackEventFilterSelect — 그룹 렌더', () => {
  it('카탈로그 10종 이벤트가 모두 한글 라벨 체크박스로 렌더된다', () => {
    render(<SlackEventFilterSelect value={[]} onChange={vi.fn()} />)

    expect(screen.getAllByRole('checkbox')).toHaveLength(SLACK_EVENT_TYPE_CATALOG.length)
    SLACK_EVENT_TYPE_CATALOG.forEach((option) => {
      expect(screen.getByRole('checkbox', { name: option.label })).toBeInTheDocument()
    })
  })

  it('그룹 헤더(이슈/스프린트/자동화)가 렌더된다', () => {
    render(<SlackEventFilterSelect value={[]} onChange={vi.fn()} />)

    expect(screen.getByRole('group', { name: '이슈' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '스프린트' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '자동화' })).toBeInTheDocument()
  })

  it('이슈 그룹에는 이슈 관련 이벤트만 포함되고 다른 그룹 이벤트는 포함되지 않는다', () => {
    render(<SlackEventFilterSelect value={[]} onChange={vi.fn()} />)

    const issueGroup = screen.getByRole('group', { name: '이슈' })
    expect(within(issueGroup).getByRole('checkbox', { name: '이슈 생성' })).toBeInTheDocument()
    expect(within(issueGroup).getByRole('checkbox', { name: '멘션' })).toBeInTheDocument()
    expect(within(issueGroup).queryByRole('checkbox', { name: '스프린트 시작' })).not.toBeInTheDocument()
    expect(within(issueGroup).queryByRole('checkbox', { name: '자동화 실패' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// controlled 선택 상태 — value prop이 유일한 진실 출처
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackEventFilterSelect — controlled 선택 상태', () => {
  it('value에 포함된 wireValue의 체크박스만 checked 상태다', () => {
    render(<SlackEventFilterSelect value={['issue.created', 'sprint.started']} onChange={vi.fn()} />)

    expect(screen.getByRole('checkbox', { name: '이슈 생성' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '스프린트 시작' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '담당자 지정' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: '자동화 실패' })).not.toBeChecked()
  })

  it('value가 빈 배열이면 모든 체크박스가 미선택 상태다', () => {
    render(<SlackEventFilterSelect value={[]} onChange={vi.fn()} />)

    screen.getAllByRole('checkbox').forEach((checkbox) => {
      expect(checkbox).not.toBeChecked()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 체크박스 토글 → onChange(next: string[]) 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackEventFilterSelect — 체크박스 토글', () => {
  it('미선택 이벤트를 체크하면 onChange가 해당 wireValue를 포함한 배열로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<SlackEventFilterSelect value={['issue.created']} onChange={onChange} />)
    await user.click(screen.getByRole('checkbox', { name: '담당자 지정' }))

    expect(onChange).toHaveBeenCalledOnce()
    const next = onChange.mock.calls[0]?.[0] as string[]
    expect(next).toContain('issue.created')
    expect(next).toContain('issue.assigned')
    expect(next).toHaveLength(2)
  })

  it('선택된 이벤트를 해제하면 onChange가 해당 wireValue를 제외한 배열로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<SlackEventFilterSelect value={['issue.created', 'issue.assigned']} onChange={onChange} />)
    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))

    expect(onChange).toHaveBeenCalledOnce()
    const next = onChange.mock.calls[0]?.[0] as string[]
    expect(next).not.toContain('issue.created')
    expect(next).toContain('issue.assigned')
    expect(next).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// disabled — 모든 체크박스 비활성 + 클릭 무반응(fail-closed)
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackEventFilterSelect — disabled', () => {
  it('disabled=true이면 모든 체크박스가 비활성 상태다', () => {
    render(<SlackEventFilterSelect value={[]} onChange={vi.fn()} disabled />)

    screen.getAllByRole('checkbox').forEach((checkbox) => {
      expect(checkbox).toBeDisabled()
    })
  })

  it('disabled=true이면 클릭해도 onChange가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<SlackEventFilterSelect value={[]} onChange={onChange} disabled />)
    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))

    expect(onChange).not.toHaveBeenCalled()
  })

  it('disabled prop이 없으면(기본값) 체크박스가 활성 상태다', () => {
    render(<SlackEventFilterSelect value={[]} onChange={vi.fn()} />)

    screen.getAllByRole('checkbox').forEach((checkbox) => {
      expect(checkbox).not.toBeDisabled()
    })
  })
})
