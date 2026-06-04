// ComponentMultiSelect 순수 presentational 컴포넌트 단위 테스트 — FR-CM-02 Task-8
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Component } from '@/api/components'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// Zod v4: UUID는 RFC4122 version/variant 형식 — 4그룹은 4로 시작, 5그룹은 8~b
// ─────────────────────────────────────────────────────────────────────────────

const componentFixtures: Component[] = [
  {
    id: 'a1b2c3d4-e5f6-4789-8abc-def012345678',
    projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
    name: '인증 모듈',
    description: '로그인/회원가입 관련 컴포넌트',
    leadUserId: null,
  },
  {
    id: 'b2c3d4e5-f6a7-4890-9bcd-ef0123456789',
    projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
    name: 'API 게이트웨이',
    description: null,
    leadUserId: null,
  },
  {
    id: 'c3d4e5f6-a7b8-4901-abcd-f01234567890',
    projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
    name: '알림 서비스',
    description: '이메일/슬랙 알림 처리',
    leadUserId: null,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// T-CMS-1. 현재 할당 컴포넌트 칩 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentMultiSelect — 현재 할당 컴포넌트 칩 렌더', () => {
  it('T-CMS-1a: value에 있는 컴포넌트 id에 해당하는 이름이 칩으로 렌더된다', () => {
    render(
      <ComponentMultiSelect
        value={[componentFixtures[0]!.id, componentFixtures[1]!.id]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const chipList = screen.getByTestId('component-chip-list')
    expect(within(chipList).getByText('인증 모듈')).toBeInTheDocument()
    expect(within(chipList).getByText('API 게이트웨이')).toBeInTheDocument()
    // value에 없는 항목은 칩으로 표시되지 않는다
    expect(within(chipList).queryByText('알림 서비스')).not.toBeInTheDocument()
  })

  it('T-CMS-1b: value가 빈 배열이면 칩이 하나도 없다', () => {
    const { container } = render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('component-chip-list')).not.toBeInTheDocument()
    // 컴포넌트 섹션 자체는 렌더된다
    expect(container.firstChild).not.toBeNull()
  })

  it('T-CMS-1c: options에 없는 id가 value에 있어도 렌더 오류 없이 무시된다', () => {
    expect(() =>
      render(
        <ComponentMultiSelect
          value={['00000000-0000-4000-8000-000000000000']}
          options={componentFixtures}
          onChange={vi.fn()}
        />,
      ),
    ).not.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CMS-2. 체크박스 토글 → onChange 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentMultiSelect — 체크박스 토글', () => {
  it('T-CMS-2a: 미선택 컴포넌트를 체크하면 onChange가 해당 id를 포함한 배열로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <ComponentMultiSelect
        value={[componentFixtures[0]!.id]}
        options={componentFixtures}
        onChange={onChange}
      />,
    )

    // 'API 게이트웨이' 체크박스 클릭
    const checkbox = screen.getByRole('checkbox', { name: 'API 게이트웨이' })
    await user.click(checkbox)

    expect(onChange).toHaveBeenCalledOnce()
    const called = onChange.mock.calls[0]?.[0] as string[]
    expect(called).toContain(componentFixtures[0]!.id)
    expect(called).toContain(componentFixtures[1]!.id)
  })

  it('T-CMS-2b: 선택된 컴포넌트를 해제하면 onChange가 해당 id를 제외한 배열로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <ComponentMultiSelect
        value={[componentFixtures[0]!.id, componentFixtures[1]!.id]}
        options={componentFixtures}
        onChange={onChange}
      />,
    )

    // '인증 모듈' 체크박스 해제
    const checkbox = screen.getByRole('checkbox', { name: '인증 모듈' })
    await user.click(checkbox)

    expect(onChange).toHaveBeenCalledOnce()
    const called = onChange.mock.calls[0]?.[0] as string[]
    expect(called).not.toContain(componentFixtures[0]!.id)
    expect(called).toContain(componentFixtures[1]!.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CMS-3. disabled(canEdit=false) → 비활성 (fail-closed)
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentMultiSelect — disabled(canEdit=false)', () => {
  it('T-CMS-3a: disabled=true이면 모든 체크박스가 disabled 상태다', () => {
    render(
      <ComponentMultiSelect
        value={[componentFixtures[0]!.id]}
        options={componentFixtures}
        onChange={vi.fn()}
        disabled={true}
      />,
    )

    const checkboxes = screen.getAllByRole('checkbox')
    checkboxes.forEach((cb) => {
      expect(cb).toBeDisabled()
    })
  })

  it('T-CMS-3b: disabled=true이면 클릭해도 onChange가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={onChange}
        disabled={true}
      />,
    )

    const checkbox = screen.getByRole('checkbox', { name: '인증 모듈' })
    await user.click(checkbox)

    expect(onChange).not.toHaveBeenCalled()
  })

  it('T-CMS-3c: disabled prop이 없으면(기본값) 체크박스가 활성 상태다', () => {
    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const checkboxes = screen.getAllByRole('checkbox')
    checkboxes.forEach((cb) => {
      expect(cb).not.toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CMS-4. 검색 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentMultiSelect — 검색 필터', () => {
  it('T-CMS-4a: 검색어를 입력하면 이름에 포함된 컴포넌트만 표시된다', async () => {
    const user = userEvent.setup()

    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const searchInput = screen.getByRole('textbox')
    await user.type(searchInput, '인증')

    // '인증 모듈'만 보여야 한다
    expect(screen.getByRole('checkbox', { name: '인증 모듈' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'API 게이트웨이' })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: '알림 서비스' })).not.toBeInTheDocument()
  })

  it('T-CMS-4b: 검색어를 지우면 모든 컴포넌트가 다시 표시된다', async () => {
    const user = userEvent.setup()

    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const searchInput = screen.getByRole('textbox')
    await user.type(searchInput, '인증')
    await user.clear(searchInput)

    // 모든 컴포넌트가 보여야 한다
    expect(screen.getByRole('checkbox', { name: '인증 모듈' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'API 게이트웨이' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '알림 서비스' })).toBeInTheDocument()
  })

  it('T-CMS-4c: 검색 결과가 없으면 빈 목록이 표시된다', async () => {
    const user = userEvent.setup()

    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const searchInput = screen.getByRole('textbox')
    await user.type(searchInput, '존재하지않는컴포넌트')

    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('T-CMS-4d: 검색 필터는 대소문자를 구분하지 않는다', async () => {
    const user = userEvent.setup()

    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const searchInput = screen.getByRole('textbox')
    await user.type(searchInput, 'api')

    expect(screen.getByRole('checkbox', { name: 'API 게이트웨이' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CMS-5. 접근성 — 컨테이너 한정으로 중복 버튼 방지
// ─────────────────────────────────────────────────────────────────────────────

describe('ComponentMultiSelect — 접근성', () => {
  it('T-CMS-5a: 각 컴포넌트 체크박스는 aria-label 또는 연결된 label을 가진다', () => {
    render(
      <ComponentMultiSelect
        value={[]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    componentFixtures.forEach((comp) => {
      const checkbox = screen.getByRole('checkbox', { name: comp.name })
      expect(checkbox).toBeInTheDocument()
    })
  })

  it('T-CMS-5b: 선택된 컴포넌트 체크박스는 checked 상태다', () => {
    render(
      <ComponentMultiSelect
        value={[componentFixtures[0]!.id]}
        options={componentFixtures}
        onChange={vi.fn()}
      />,
    )

    const checkedBox = screen.getByRole('checkbox', { name: '인증 모듈' })
    expect(checkedBox).toBeChecked()

    const uncheckedBox = screen.getByRole('checkbox', { name: 'API 게이트웨이' })
    expect(uncheckedBox).not.toBeChecked()
  })

  it('T-CMS-5c: 컴포넌트가 없으면(options=[]) 체크박스가 없다', () => {
    render(
      <ComponentMultiSelect
        value={[]}
        options={[]}
        onChange={vi.fn()}
      />,
    )

    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })
})
