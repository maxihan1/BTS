// 프로젝트 danger zone 컴포넌트 단위 테스트 — 아카이브/해제 버튼 분기 + 권한 게이팅 (FR-PJ PR-5 Task 6 REFACTOR)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const mockArchiveMutate = vi.fn()
const mockUnarchiveMutate = vi.fn()

vi.mock('@/hooks/use-project-mutations', () => ({
  useArchiveProject: vi.fn(() => ({ mutate: mockArchiveMutate, isPending: false, isError: false })),
  useUnarchiveProject: vi.fn(() => ({ mutate: mockUnarchiveMutate, isPending: false, isError: false })),
}))

import { ProjectDangerZone } from '@/components/project/ProjectDangerZone'
import { useArchiveProject, useUnarchiveProject } from '@/hooks/use-project-mutations'

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(useArchiveProject).mockReturnValue({
    mutate: mockArchiveMutate,
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useArchiveProject>)
  vi.mocked(useUnarchiveProject).mockReturnValue({
    mutate: mockUnarchiveMutate,
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useUnarchiveProject>)
})

describe('ProjectDangerZone', () => {
  it('활성 프로젝트(archived:false)이면 "아카이브" 버튼만 표시된다', () => {
    render(<ProjectDangerZone projectKey="ATLAS" archived={false} canManage={true} />)
    expect(screen.getByRole('button', { name: '아카이브' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '아카이브 해제' })).not.toBeInTheDocument()
  })

  it('아카이브된 프로젝트(archived:true)이면 "아카이브 해제" 버튼만 표시된다', () => {
    render(<ProjectDangerZone projectKey="ATLAS" archived={true} canManage={true} />)
    expect(screen.getByRole('button', { name: '아카이브 해제' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '아카이브' })).not.toBeInTheDocument()
  })

  it('"아카이브" 클릭 → 인라인 확인 → "확인" 클릭 시 archive.mutate(projectKey, ...)가 호출된다', async () => {
    const user = userEvent.setup()
    render(<ProjectDangerZone projectKey="ATLAS" archived={false} canManage={true} />)

    await user.click(screen.getByRole('button', { name: '아카이브' }))
    expect(mockArchiveMutate).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '확인' }))
    expect(mockArchiveMutate).toHaveBeenCalledWith('ATLAS', expect.anything())
  })

  it('인라인 확인 중 "취소" 클릭 시 mutate 호출 없이 원래 버튼으로 되돌아간다', async () => {
    const user = userEvent.setup()
    render(<ProjectDangerZone projectKey="ATLAS" archived={false} canManage={true} />)

    await user.click(screen.getByRole('button', { name: '아카이브' }))
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(mockArchiveMutate).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '아카이브' })).toBeInTheDocument()
  })

  it('"아카이브 해제" 클릭 시 즉시 unarchive.mutate(projectKey)가 호출된다(확인 단계 없음)', async () => {
    const user = userEvent.setup()
    render(<ProjectDangerZone projectKey="ATLAS" archived={true} canManage={true} />)

    await user.click(screen.getByRole('button', { name: '아카이브 해제' }))
    expect(mockUnarchiveMutate).toHaveBeenCalledWith('ATLAS')
  })

  it('canManage=false면 "아카이브" 버튼이 disabled 상태다', () => {
    render(<ProjectDangerZone projectKey="ATLAS" archived={false} canManage={false} />)
    expect(screen.getByRole('button', { name: '아카이브' })).toBeDisabled()
  })

  it('canManage=false면 "아카이브 해제" 버튼이 disabled 상태다', () => {
    render(<ProjectDangerZone projectKey="ATLAS" archived={true} canManage={false} />)
    expect(screen.getByRole('button', { name: '아카이브 해제' })).toBeDisabled()
  })

  it('archive mutation이 isError면 에러 문구가 표시된다', () => {
    vi.mocked(useArchiveProject).mockReturnValue({
      mutate: mockArchiveMutate,
      isPending: false,
      isError: true,
    } as unknown as ReturnType<typeof useArchiveProject>)

    render(<ProjectDangerZone projectKey="ATLAS" archived={false} canManage={true} />)
    expect(screen.getByRole('alert')).toHaveTextContent('아카이브 처리에 실패했습니다')
  })

  it('unarchive mutation이 isError면 에러 문구가 표시된다', () => {
    vi.mocked(useUnarchiveProject).mockReturnValue({
      mutate: mockUnarchiveMutate,
      isPending: false,
      isError: true,
    } as unknown as ReturnType<typeof useUnarchiveProject>)

    render(<ProjectDangerZone projectKey="ATLAS" archived={true} canManage={true} />)
    expect(screen.getByRole('alert')).toHaveTextContent('아카이브 해제에 실패했습니다')
  })
})
