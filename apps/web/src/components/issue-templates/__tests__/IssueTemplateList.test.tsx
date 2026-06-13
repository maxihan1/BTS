// 이슈 템플릿 목록 컴포넌트 단위 테스트 — 4분기 렌더 + 권한 게이팅 + Dialog 연동 (FR-TM-01 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueTemplateHandlers, resetIssueTemplateStore } from '@/mocks/issue-template-handlers'
import { projectPermissionHandlers } from '@/mocks/project-permission-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { useAuthStore } from '@/auth/authStore'
import { IssueTemplateList } from '@/components/issue-templates/IssueTemplateList'

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderList(projectKey = 'ATLAS') {
  return render(
    <QueryClientProvider client={makeClient()}>
      <IssueTemplateList projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

describe('IssueTemplateList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetIssueTemplateStore()
    server.use(...issueTemplateHandlers, ...projectPermissionHandlers, ...issueTypeHandlers)
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '10000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-TL-1. 로딩 중에는 스켈레톤이 렌더된다.
   */
  it('T-TL-1: 로딩 중 스켈레톤이 렌더된다', () => {
    renderList('ATLAS')
    expect(screen.getByRole('status', { name: /이슈 템플릿 목록 로딩 중/i })).toBeInTheDocument()
  })

  /**
   * T-TL-2. 템플릿이 없으면 빈 상태 메시지가 표시된다.
   */
  it('T-TL-2: 템플릿 없음 → 빈 상태 메시지가 표시된다', async () => {
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('아직 이슈 템플릿이 없습니다.')).toBeInTheDocument()
    })
  })

  /**
   * T-TL-3. 템플릿이 있으면 목록 행이 렌더된다 (이름 + 타입명 해석).
   * issueTypeId=1 → '버그' (issue-type-fixtures 기준)
   */
  it('T-TL-3: 템플릿 있음 → 행에 이름과 이슈 타입명이 표시된다', async () => {
    await fetch('/api/v1/projects/ATLAS/issue-templates', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ issueTypeId: 1, name: '버그 기본 템플릿', content: '## 재현 방법' }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('버그 기본 템플릿')).toBeInTheDocument()
      // issueTypeId=1 → useIssueTypes에서 '버그'로 해석
      expect(screen.getByText('버그')).toBeInTheDocument()
    })
  })

  /**
   * T-TL-4. API 에러 시 에러 메시지가 표시된다.
   */
  it('T-TL-4: API 에러 → 에러 메시지가 표시된다', async () => {
    server.use(
      http.get('/api/v1/projects/ERR/issue-templates', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
    )

    renderList('ERR')

    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })
  })

  /**
   * T-TL-5. ADMIN 권한이면 "템플릿 추가" 버튼이 활성화된다.
   */
  it('T-TL-5: ADMIN 권한 → 템플릿 추가 버튼 활성', async () => {
    renderList('ATLAS')

    await waitFor(() => {
      const btn = screen.getByRole('button', { name: '템플릿 추가' })
      expect(btn).not.toBeDisabled()
    })
  })

  /**
   * T-TL-6. 권한 없음(MEMBER)이면 "템플릿 추가" 버튼이 비활성화된다 (fail-closed).
   */
  it('T-TL-6: 권한 없음(MEMBER) → 템플릿 추가 버튼 비활성(disabled)', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({
          projectKey: 'ATLAS',
          permissions: {
            CREATE: true,
            MANAGE_COMPONENTS: false,
            MANAGE_VERSIONS: false,
            MANAGE_CUSTOM_FIELDS: false,
            MANAGE_FIELD_PERMISSIONS: false,
            MANAGE_TEMPLATES: false,
          },
        }),
      ),
    )

    renderList('ATLAS')

    await waitFor(() => {
      const btn = screen.getByRole('button', { name: '템플릿 추가' })
      expect(btn).toBeDisabled()
    })
  })

  /**
   * T-TL-7. ADMIN 권한이면 행의 수정/삭제 버튼이 활성화된다.
   */
  it('T-TL-7: ADMIN 권한 → 행의 수정/삭제 버튼 활성', async () => {
    await fetch('/api/v1/projects/ATLAS/issue-templates', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ issueTypeId: 2, name: '스토리 템플릿', content: '## 사용자 스토리' }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('스토리 템플릿')).toBeInTheDocument()
    })

    // 수정 버튼 활성 확인
    const editBtn = screen.getByRole('button', { name: /스토리 템플릿 수정/i })
    expect(editBtn).not.toBeDisabled()

    // 삭제 버튼 활성 확인
    const deleteBtn = screen.getByRole('button', { name: /스토리 템플릿 삭제/i })
    expect(deleteBtn).not.toBeDisabled()
  })

  /**
   * T-TL-8. 권한 없음(MEMBER)이면 행의 수정/삭제 버튼이 비활성화된다 (fail-closed).
   */
  it('T-TL-8: 권한 없음(MEMBER) → 행의 수정/삭제 버튼 비활성', async () => {
    await fetch('/api/v1/projects/ATLAS/issue-templates', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ issueTypeId: 3, name: '작업 템플릿', content: '## 작업 내용' }),
    })

    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({
          projectKey: 'ATLAS',
          permissions: {
            CREATE: true,
            MANAGE_COMPONENTS: false,
            MANAGE_VERSIONS: false,
            MANAGE_CUSTOM_FIELDS: false,
            MANAGE_FIELD_PERMISSIONS: false,
            MANAGE_TEMPLATES: false,
          },
        }),
      ),
    )

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('작업 템플릿')).toBeInTheDocument()
    })

    const editBtn = screen.getByRole('button', { name: /작업 템플릿 수정/i })
    expect(editBtn).toBeDisabled()

    const deleteBtn = screen.getByRole('button', { name: /작업 템플릿 삭제/i })
    expect(deleteBtn).toBeDisabled()
  })

  /**
   * T-TL-9. "템플릿 추가" 버튼 클릭 → Dialog(create 모드)가 열린다.
   */
  it('T-TL-9: 템플릿 추가 버튼 클릭 → Dialog가 열린다', async () => {
    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '템플릿 추가' })).not.toBeDisabled()
    })

    await user.click(screen.getByRole('button', { name: '템플릿 추가' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

  /**
   * T-TL-10. 수정 버튼 클릭 → Dialog(edit 모드, 기존값 프리필)가 열린다.
   */
  it('T-TL-10: 수정 버튼 클릭 → Dialog(edit 모드)가 열린다', async () => {
    await fetch('/api/v1/projects/ATLAS/issue-templates', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ issueTypeId: 1, name: '편집할 템플릿', content: '## 본문' }),
    })

    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('편집할 템플릿')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /편집할 템플릿 수정/i }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      // edit 모드 타이틀 확인
      expect(screen.getByText('이슈 템플릿 수정')).toBeInTheDocument()
    })
  })

  /**
   * T-TL-11. 삭제 버튼 클릭 → 인라인 확인 UI가 표시되고, 확인 시 템플릿이 삭제된다.
   */
  it('T-TL-11: 삭제 확인 → 목록에서 제거된다', async () => {
    await fetch('/api/v1/projects/ATLAS/issue-templates', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ issueTypeId: 1, name: '삭제될 템플릿', content: '## 삭제' }),
    })

    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('삭제될 템플릿')).toBeInTheDocument()
    })

    // 삭제 버튼 클릭 → 인라인 확인 표시
    await user.click(screen.getByRole('button', { name: /삭제될 템플릿 삭제/i }))

    // 인라인 확인 텍스트가 나타난다
    await waitFor(() => {
      expect(screen.getByText('정말 삭제하시겠습니까?')).toBeInTheDocument()
    })

    // 확인 버튼 클릭
    const confirmBtn = screen.getByRole('button', { name: '삭제' })
    await user.click(confirmBtn)

    // 목록에서 제거 확인
    await waitFor(() => {
      expect(screen.queryByText('삭제될 템플릿')).not.toBeInTheDocument()
    })
  })

  /**
   * T-TL-12. 행의 updatedAt(수정시각)이 표시된다.
   */
  it('T-TL-12: 행에 수정시각이 표시된다', async () => {
    await fetch('/api/v1/projects/ATLAS/issue-templates', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ issueTypeId: 1, name: '시각 표시 템플릿', content: '## 내용' }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('시각 표시 템플릿')).toBeInTheDocument()
      // 수정시각 표시 — time 요소 또는 날짜 텍스트
      const timeEl = screen.getByRole('time')
      expect(timeEl).toBeInTheDocument()
    })
  })
})
