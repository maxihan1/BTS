// 프로젝트 리드 셀렉터 ProjectLeadSelect 단위 테스트

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { UserSummary } from '../../api/users'
import { ProjectLeadSelect } from './ProjectLeadSelect'

// 테스트용 UserSummary fixture (RFC4122 v4 형식 UUID)
const USER_A: UserSummary = {
  id: '40000000-0000-4000-8000-000000000001',
  username: 'alice',
  displayName: 'Alice Kim',
  email: 'alice@example.com',
}

const USER_B: UserSummary = {
  id: '40000000-0000-4000-8000-000000000002',
  username: 'bob',
  displayName: null,
  email: 'bob@example.com',
}

describe('ProjectLeadSelect', () => {
  it('users props 옵션을 렌더한다', () => {
    render(
      <ProjectLeadSelect
        users={[USER_A, USER_B]}
        currentLead={null}
        onSearch={vi.fn()}
        onChange={vi.fn()}
      />,
    )

    // displayName 우선 표시
    expect(screen.getByRole('button', { name: 'Alice Kim' })).toBeInTheDocument()
    // displayName null이면 username 표시
    expect(screen.getByRole('button', { name: 'bob' })).toBeInTheDocument()
  })

  it('검색 input에 타이핑하면 onSearch가 호출된다', async () => {
    const onSearch = vi.fn()
    render(
      <ProjectLeadSelect
        users={[]}
        currentLead={null}
        onSearch={onSearch}
        onChange={vi.fn()}
      />,
    )

    const input = screen.getByRole('textbox', { name: /리드 검색/ })
    await userEvent.type(input, 'ali', { delay: null })

    expect(onSearch).toHaveBeenCalledWith('a')
    expect(onSearch).toHaveBeenCalledWith('al')
    expect(onSearch).toHaveBeenCalledWith('ali')
  })

  it('사용자 옵션 선택 시 onChange(userId)가 호출된다', async () => {
    const onChange = vi.fn()
    render(
      <ProjectLeadSelect
        users={[USER_A]}
        currentLead={null}
        onSearch={vi.fn()}
        onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Alice Kim' }))

    expect(onChange).toHaveBeenCalledWith(USER_A.id)
  })

  it('"미지정" 버튼 클릭 시 onChange(null)이 호출된다', async () => {
    const onChange = vi.fn()
    render(
      <ProjectLeadSelect
        users={[USER_A]}
        currentLead={USER_A}
        onSearch={vi.fn()}
        onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '미지정' }))

    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('currentLead가 있으면 표시명을 렌더한다', () => {
    render(
      <ProjectLeadSelect
        users={[]}
        currentLead={USER_A}
        onSearch={vi.fn()}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('lead-current-name')).toHaveTextContent('Alice Kim')
  })

  it('currentLead가 null이면 "미지정" 텍스트를 렌더한다', () => {
    render(
      <ProjectLeadSelect
        users={[]}
        currentLead={null}
        onSearch={vi.fn()}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('lead-current-name')).toHaveTextContent('미지정')
  })

  it('disabled=true이면 input과 버튼이 비활성화된다', async () => {
    render(
      <ProjectLeadSelect
        users={[USER_A]}
        currentLead={USER_A}
        onSearch={vi.fn()}
        onChange={vi.fn()}
        disabled
      />,
    )

    expect(screen.getByRole('textbox', { name: /리드 검색/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '미지정' })).toBeDisabled()
  })

  /**
   * T-unknown-1: unknownLeadId가 주어지고 currentLead가 null이면
   *              "알 수 없는 사용자 (앞8자)" 텍스트가 data-testid="lead-current-name"에 표시된다.
   */
  it('unknownLeadId 있고 currentLead null이면 알 수 없는 사용자 텍스트를 렌더한다', () => {
    const unknownId = '40000000-0000-4000-8000-deadbeef0001'
    render(
      <ProjectLeadSelect
        users={[]}
        currentLead={null}
        unknownLeadId={unknownId}
        onSearch={vi.fn()}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('lead-current-name')).toHaveTextContent(
      `알 수 없는 사용자 (${unknownId.slice(0, 8)})`,
    )
  })

  /**
   * T-unknown-2: unknownLeadId 상태에서 해제 버튼이 노출되고 클릭 시 onChange(null)이 호출된다.
   */
  it('unknownLeadId 있을 때 해제 버튼이 노출되고 클릭 시 onChange(null)이 호출된다', async () => {
    const onChange = vi.fn()
    const unknownId = '40000000-0000-4000-8000-deadbeef0001'
    render(
      <ProjectLeadSelect
        users={[]}
        currentLead={null}
        unknownLeadId={unknownId}
        onSearch={vi.fn()}
        onChange={onChange}
      />,
    )

    const unassignBtn = screen.getByRole('button', { name: '미지정' })
    expect(unassignBtn).toBeInTheDocument()
    await userEvent.click(unassignBtn)

    expect(onChange).toHaveBeenCalledWith(null)
  })

  /**
   * T-unknown-3: unknownLeadId가 null이고 currentLead도 null이면 기존대로 "미지정" 텍스트가 표시된다.
   *              즉, 둘 다 없는 경우와 unknownLeadId 있는 경우를 명확히 구분한다.
   */
  it('unknownLeadId가 null이고 currentLead도 null이면 미지정 텍스트가 표시된다', () => {
    render(
      <ProjectLeadSelect
        users={[]}
        currentLead={null}
        unknownLeadId={null}
        onSearch={vi.fn()}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('lead-current-name')).toHaveTextContent('미지정')
    // 해제 버튼은 없어야 한다
    expect(screen.queryByRole('button', { name: '미지정' })).not.toBeInTheDocument()
  })
})
