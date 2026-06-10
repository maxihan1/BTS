// VersionMultiSelect 순수 presentational 컴포넌트 단위 테스트 — FR-VR-03 Task-7
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Version } from '@/api/versions.types'
import { VersionMultiSelect } from '@/components/issue/VersionMultiSelect'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// Zod v4: UUID는 RFC4122 version/variant 형식 — 4그룹은 4로 시작, 5그룹은 8~b
// ─────────────────────────────────────────────────────────────────────────────

const versionFixtures: Version[] = [
  {
    id: 'a1b2c3d4-e5f6-4789-8abc-def012345678',
    projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
    name: 'v1.0.0',
    description: '첫 번째 안정 릴리스',
    startDate: null,
    releaseDate: null,
    status: 'UNRELEASED',
    releasedAt: null,
  },
  {
    id: 'b2c3d4e5-f6a7-4890-9bcd-ef0123456789',
    projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
    name: 'v1.1.0',
    description: null,
    startDate: null,
    releaseDate: null,
    status: 'RELEASED',
    releasedAt: '2026-01-15',
  },
  {
    id: 'c3d4e5f6-a7b8-4901-abcd-f01234567890',
    projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
    name: 'v0.9.0',
    description: '아카이브된 구버전',
    startDate: null,
    releaseDate: null,
    status: 'ARCHIVED',
    releasedAt: '2025-12-01',
  },
]

const [unreleasedVer, releasedVer, archivedVer] = versionFixtures as [Version, Version, Version]

// ─────────────────────────────────────────────────────────────────────────────
// T-VMS-1. 현재 연결 버전 칩 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — 현재 연결 버전 칩 렌더', () => {
  it('T-VMS-1a: value에 있는 버전 id에 해당하는 이름이 칩으로 렌더된다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[unreleasedVer.id, releasedVer.id]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    const chipList = screen.getByTestId('version-chip-list')
    expect(within(chipList).getByText('v1.0.0')).toBeInTheDocument()
    expect(within(chipList).getByText('v1.1.0')).toBeInTheDocument()
    expect(within(chipList).queryByText('v0.9.0')).not.toBeInTheDocument()
  })

  it('T-VMS-1b: value가 빈 배열이면 칩이 하나도 없다', () => {
    const { container } = render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('version-chip-list')).not.toBeInTheDocument()
    expect(container.firstChild).not.toBeNull()
  })

  it('T-VMS-1c: options에 없는 id가 value에 있어도 렌더 오류 없이 무시된다', () => {
    expect(() =>
      render(
        <VersionMultiSelect
          variant="affects"
          value={['00000000-0000-4000-8000-000000000000']}
          options={versionFixtures}
          onChange={vi.fn()}
        />,
      ),
    ).not.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-VMS-2. ARCHIVED 버전 기본 숨김 + 연결된 ARCHIVED 버전 표시 (Jira 정석)
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — ARCHIVED 버전 가시성', () => {
  it('T-VMS-2a: ARCHIVED 버전은 value에 없으면 드롭다운 목록에서 숨겨진다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    // UNRELEASED/RELEASED는 표시
    expect(screen.getByRole('checkbox', { name: 'v1.0.0' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'v1.1.0' })).toBeInTheDocument()
    // ARCHIVED는 숨겨짐
    expect(screen.queryByRole('checkbox', { name: 'v0.9.0' })).not.toBeInTheDocument()
  })

  it('T-VMS-2b: value에 이미 포함된 ARCHIVED 버전은 드롭다운 목록에서 표시된다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[archivedVer.id]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    // 연결된 ARCHIVED 버전은 표시
    expect(screen.getByRole('checkbox', { name: 'v0.9.0' })).toBeInTheDocument()
    // 연결되지 않은 ARCHIVED가 추가로 있다면 숨겨짐 — 이 케이스는 현재 fixture에 없음
  })

  it('T-VMS-2c: 연결된 ARCHIVED 버전은 칩으로도 표시된다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[archivedVer.id]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    const chipList = screen.getByTestId('version-chip-list')
    expect(within(chipList).getByText('v0.9.0')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-VMS-3. 체크박스 토글 → onChange 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — 체크박스 토글', () => {
  it('T-VMS-3a: 미선택 버전을 체크하면 onChange가 해당 id를 포함한 배열로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <VersionMultiSelect
        variant="affects"
        value={[unreleasedVer.id]}
        options={versionFixtures}
        onChange={onChange}
      />,
    )

    const checkbox = screen.getByRole('checkbox', { name: 'v1.1.0' })
    await user.click(checkbox)

    expect(onChange).toHaveBeenCalledOnce()
    const called = onChange.mock.calls[0]?.[0] as string[]
    expect(called).toContain(unreleasedVer.id)
    expect(called).toContain(releasedVer.id)
  })

  it('T-VMS-3b: 선택된 버전을 해제하면 onChange가 해당 id를 제외한 배열로 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <VersionMultiSelect
        variant="affects"
        value={[unreleasedVer.id, releasedVer.id]}
        options={versionFixtures}
        onChange={onChange}
      />,
    )

    const checkbox = screen.getByRole('checkbox', { name: 'v1.0.0' })
    await user.click(checkbox)

    expect(onChange).toHaveBeenCalledOnce()
    const called = onChange.mock.calls[0]?.[0] as string[]
    expect(called).not.toContain(unreleasedVer.id)
    expect(called).toContain(releasedVer.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-VMS-4. disabled(canEdit=false) → 비활성 (fail-closed)
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — disabled(canEdit=false)', () => {
  it('T-VMS-4a: disabled=true이면 모든 체크박스가 disabled 상태다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[unreleasedVer.id]}
        options={versionFixtures}
        onChange={vi.fn()}
        disabled={true}
      />,
    )

    const checkboxes = screen.getAllByRole('checkbox')
    checkboxes.forEach((cb) => {
      expect(cb).toBeDisabled()
    })
  })

  it('T-VMS-4b: disabled=true이면 클릭해도 onChange가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={versionFixtures}
        onChange={onChange}
        disabled={true}
      />,
    )

    const checkbox = screen.getByRole('checkbox', { name: 'v1.0.0' })
    await user.click(checkbox)

    expect(onChange).not.toHaveBeenCalled()
  })

  it('T-VMS-4c: disabled prop이 없으면(기본값) 체크박스가 활성 상태다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={versionFixtures}
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
// T-VMS-5. 검색 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — 검색 필터', () => {
  it('T-VMS-5a: 검색어를 입력하면 이름에 포함된 버전만 표시된다', async () => {
    const user = userEvent.setup()

    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    const searchInput = screen.getByRole('textbox')
    await user.type(searchInput, 'v1.0')

    expect(screen.getByRole('checkbox', { name: 'v1.0.0' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'v1.1.0' })).not.toBeInTheDocument()
  })

  it('T-VMS-5b: 검색어를 지우면 ARCHIVED 제외 모든 버전이 다시 표시된다', async () => {
    const user = userEvent.setup()

    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    const searchInput = screen.getByRole('textbox')
    await user.type(searchInput, 'v1.0')
    await user.clear(searchInput)

    expect(screen.getByRole('checkbox', { name: 'v1.0.0' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'v1.1.0' })).toBeInTheDocument()
    // ARCHIVED는 value에 없으면 여전히 숨겨짐
    expect(screen.queryByRole('checkbox', { name: 'v0.9.0' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-VMS-6. variant='fix' — 동일 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — variant="fix"', () => {
  it('T-VMS-6a: variant="fix"도 동일하게 동작한다', () => {
    render(
      <VersionMultiSelect
        variant="fix"
        value={[releasedVer.id]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    const chipList = screen.getByTestId('version-chip-list')
    expect(within(chipList).getByText('v1.1.0')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'v1.0.0' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'v1.1.0' })).toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-VMS-7. 접근성
// ─────────────────────────────────────────────────────────────────────────────

describe('VersionMultiSelect — 접근성', () => {
  it('T-VMS-7a: 각 버전 체크박스는 aria-label 또는 연결된 label을 가진다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={[unreleasedVer, releasedVer]}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByRole('checkbox', { name: 'v1.0.0' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'v1.1.0' })).toBeInTheDocument()
  })

  it('T-VMS-7b: 선택된 버전 체크박스는 checked 상태다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[unreleasedVer.id]}
        options={versionFixtures}
        onChange={vi.fn()}
      />,
    )

    const checkedBox = screen.getByRole('checkbox', { name: 'v1.0.0' })
    expect(checkedBox).toBeChecked()

    const uncheckedBox = screen.getByRole('checkbox', { name: 'v1.1.0' })
    expect(uncheckedBox).not.toBeChecked()
  })

  it('T-VMS-7c: 버전이 없으면(options=[]) 체크박스가 없다', () => {
    render(
      <VersionMultiSelect
        variant="affects"
        value={[]}
        options={[]}
        onChange={vi.fn()}
      />,
    )

    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })
})
