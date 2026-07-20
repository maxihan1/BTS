// ProjectListTable 단위 테스트 — 로딩/에러/빈상태/목록 4분기 + 아카이브 배지 + 행 클릭 콜백 (FR-PJ PR-5 Task 4)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Project } from '@/api/projects'
import { ProjectListTable } from '../ProjectListTable'
import { resolveProjectPath } from '../project-list-paths'

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const ACTIVE_PROJECT: Project = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  key: 'ATLAS',
  name: 'Atlas 프로젝트',
  archived: false,
}

const ARCHIVED_PROJECT: Project = {
  id: 'b2c3d4e5-f6a7-4901-bcde-f12345678901',
  key: 'OLDONE',
  name: 'Old 프로젝트',
  archived: true,
}

function baseProps() {
  return {
    projects: [] as readonly Project[],
    isLoading: false,
    isError: false,
    archived: false,
    canCreateProject: false,
    onNavigateToProject: vi.fn(),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// resolveProjectPath
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveProjectPath', () => {
  it('활성 프로젝트(archived:false) → /projects/{key}/board', () => {
    expect(resolveProjectPath(ACTIVE_PROJECT)).toBe('/projects/ATLAS/board')
  })

  it('archived 필드 부재(undefined) → 활성으로 간주해 /board', () => {
    const noArchivedField: Project = { id: ACTIVE_PROJECT.id, key: 'NOFIELD', name: '이름' }
    expect(resolveProjectPath(noArchivedField)).toBe('/projects/NOFIELD/board')
  })

  it('아카이브 프로젝트(archived:true) → /projects/{key}/settings/details (G3)', () => {
    expect(resolveProjectPath(ARCHIVED_PROJECT)).toBe('/projects/OLDONE/settings/details')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 / 에러 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListTable — 로딩/에러 분기', () => {
  it('isLoading=true면 role="status" 로딩 문구를 표시하고 테이블을 렌더하지 않는다', () => {
    render(<ProjectListTable {...baseProps()} isLoading />)

    expect(screen.getByRole('status')).toHaveTextContent('로딩 중')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('isError=true면 role="alert" 에러 배너를 표시하고 빈 상태로 은폐하지 않는다', () => {
    render(<ProjectListTable {...baseProps()} isError />)

    expect(screen.getByRole('alert')).toHaveTextContent('불러오지 못했습니다')
    expect(screen.queryByText('표시할 프로젝트가 없습니다')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 (EC-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListTable — 빈 상태 (EC-1)', () => {
  it('활성 목록이 비어 있고 canCreateProject=true면 "새 프로젝트 만들기" CTA가 표시된다', () => {
    render(<ProjectListTable {...baseProps()} canCreateProject archived={false} />)

    expect(screen.getByText('표시할 프로젝트가 없습니다')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '새 프로젝트 만들기' })).toHaveAttribute(
      'href',
      '/projects/new',
    )
  })

  it('활성 목록이 비어 있고 canCreateProject=false면 CTA가 표시되지 않는다', () => {
    render(<ProjectListTable {...baseProps()} canCreateProject={false} archived={false} />)

    expect(screen.getByText('표시할 프로젝트가 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '새 프로젝트 만들기' })).not.toBeInTheDocument()
  })

  it('아카이브 목록이 비어 있으면 canCreateProject=true여도 CTA를 표시하지 않는다', () => {
    render(<ProjectListTable {...baseProps()} canCreateProject archived />)

    expect(screen.getByText('아카이브된 프로젝트가 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '새 프로젝트 만들기' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 목록 렌더 + 아카이브 배지
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListTable — 목록 렌더', () => {
  it('컬럼 헤더(키/이름/상태)와 각 행의 key·name을 렌더한다', () => {
    render(<ProjectListTable {...baseProps()} projects={[ACTIVE_PROJECT, ARCHIVED_PROJECT]} />)

    expect(screen.getByRole('columnheader', { name: '키' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '이름' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '상태' })).toBeInTheDocument()

    expect(screen.getByText('Atlas 프로젝트')).toBeInTheDocument()
    expect(screen.getByText('Old 프로젝트')).toBeInTheDocument()
  })

  it('입력 순서를 그대로 렌더한다 (재정렬 없음, 백엔드 정렬 신뢰)', () => {
    // 일부러 이름 역순으로 전달 — 프론트가 재정렬한다면 순서가 뒤집혀야 하는데, 그러지 않아야 한다.
    render(<ProjectListTable {...baseProps()} projects={[ARCHIVED_PROJECT, ACTIVE_PROJECT]} />)

    const rows = screen.getAllByRole('row').slice(1) // 헤더 행 제외
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Old 프로젝트'),
      expect.stringContaining('Atlas 프로젝트'),
    ])
  })

  it('archived:true 프로젝트만 "아카이브" 배지를 렌더한다', () => {
    render(<ProjectListTable {...baseProps()} projects={[ACTIVE_PROJECT, ARCHIVED_PROJECT]} />)

    expect(screen.getAllByText('아카이브')).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 행 클릭 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListTable — 행 클릭 콜백', () => {
  it('키 링크 클릭 시 onNavigateToProject가 해당 프로젝트로 정확히 1번 호출된다', async () => {
    const user = userEvent.setup()
    const onNavigateToProject = vi.fn()
    render(
      <ProjectListTable {...baseProps()} projects={[ACTIVE_PROJECT]} onNavigateToProject={onNavigateToProject} />,
    )

    await user.click(screen.getByRole('link', { name: 'ATLAS' }))

    expect(onNavigateToProject).toHaveBeenCalledTimes(1)
    expect(onNavigateToProject).toHaveBeenCalledWith(ACTIVE_PROJECT)
  })

  it('키 링크의 href는 resolveProjectPath 결과와 일치한다', () => {
    render(<ProjectListTable {...baseProps()} projects={[ARCHIVED_PROJECT]} />)

    expect(screen.getByRole('link', { name: 'OLDONE' })).toHaveAttribute(
      'href',
      '/projects/OLDONE/settings/details',
    )
  })

  it('이름 셀(행의 다른 영역) 클릭도 onNavigateToProject를 호출한다', async () => {
    const user = userEvent.setup()
    const onNavigateToProject = vi.fn()
    render(
      <ProjectListTable {...baseProps()} projects={[ACTIVE_PROJECT]} onNavigateToProject={onNavigateToProject} />,
    )

    await user.click(screen.getByText('Atlas 프로젝트'))

    expect(onNavigateToProject).toHaveBeenCalledWith(ACTIVE_PROJECT)
  })
})
