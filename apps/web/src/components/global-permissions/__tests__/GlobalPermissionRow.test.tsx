// 전역 권한 행 컴포넌트 단위 테스트 — 필드 렌더 + 인라인 회수 확인 플로우 (FR-PM-10 D6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GlobalPermissionRow } from '@/components/global-permissions/GlobalPermissionRow'

function renderRow(overrides: Partial<Parameters<typeof GlobalPermissionRow>[0]> = {}) {
  const onRevoke = vi.fn()
  const props = {
    id: 'grant-1',
    permissionLabel: '프로젝트 생성',
    granteeTypeLabel: '사용자',
    granteeName: '홍길동',
    grantedByName: '관리자',
    createdAtLabel: '2026-07-18',
    onRevoke,
    isPending: false,
    ...overrides,
  }
  render(
    <table>
      <tbody>
        <GlobalPermissionRow {...props} />
      </tbody>
    </table>,
  )
  return { onRevoke, props }
}

describe('GlobalPermissionRow', () => {
  /**
   * T-GPR-1. 전달된 각 필드 값이 화면에 렌더된다.
   */
  it('T-GPR-1: 각 필드가 렌더된다', () => {
    renderRow()
    expect(screen.getByText('프로젝트 생성')).toBeInTheDocument()
    expect(screen.getByText('사용자')).toBeInTheDocument()
    expect(screen.getByText('홍길동')).toBeInTheDocument()
    expect(screen.getByText('관리자')).toBeInTheDocument()
    expect(screen.getByText('2026-07-18')).toBeInTheDocument()
  })

  /**
   * T-GPR-2. 회수 버튼 클릭 시 인라인 확인 UI("삭제하시겠습니까?" + 확인/취소)가 노출된다.
   */
  it('T-GPR-2: 회수 버튼 클릭 → 인라인 확인 UI가 노출된다', async () => {
    const user = userEvent.setup()
    renderRow()

    await user.click(screen.getByRole('button', { name: '홍길동 전역 권한 회수' }))

    expect(screen.getByText('삭제하시겠습니까?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '확인' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument()
  })

  /**
   * T-GPR-3. 확인 클릭 시 onRevoke(id)가 정확히 1회 호출된다.
   */
  it('T-GPR-3: 확인 클릭 → onRevoke(id) 1회 호출', async () => {
    const user = userEvent.setup()
    const { onRevoke } = renderRow({ id: 'grant-42' })

    await user.click(screen.getByRole('button', { name: '홍길동 전역 권한 회수' }))
    await user.click(screen.getByRole('button', { name: '확인' }))

    expect(onRevoke).toHaveBeenCalledTimes(1)
    expect(onRevoke).toHaveBeenCalledWith('grant-42')
  })

  /**
   * T-GPR-4. 취소 클릭 시 onRevoke는 호출되지 않고 확인 UI가 닫힌다.
   */
  it('T-GPR-4: 취소 클릭 → onRevoke 미호출 + 확인 UI 닫힘', async () => {
    const user = userEvent.setup()
    const { onRevoke } = renderRow()

    await user.click(screen.getByRole('button', { name: '홍길동 전역 권한 회수' }))
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(onRevoke).not.toHaveBeenCalled()
    expect(screen.queryByText('삭제하시겠습니까?')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '홍길동 전역 권한 회수' })).toBeInTheDocument()
  })

  /**
   * T-GPR-5. 회수 버튼 aria-label에 대상 이름(granteeName)이 포함된다.
   */
  it('T-GPR-5: 회수 버튼 aria-label에 granteeName이 포함된다', () => {
    renderRow({ granteeName: '개발팀' })
    expect(screen.getByRole('button', { name: '개발팀 전역 권한 회수' })).toBeInTheDocument()
  })

  /**
   * T-GPR-6. isPending=true이면 확인 버튼이 disabled 상태다.
   */
  it('T-GPR-6: isPending 시 확인 버튼이 disabled', async () => {
    const user = userEvent.setup()
    renderRow({ isPending: true })

    await user.click(screen.getByRole('button', { name: '홍길동 전역 권한 회수' }))

    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled()
  })
})
