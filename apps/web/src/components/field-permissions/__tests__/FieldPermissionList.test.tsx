// 필드 권한 규칙 목록 컴포넌트 단위 테스트 — 4분기 렌더 + 권한 게이팅 + Dialog 연동 (FR-PM-07)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { fieldPermissionHandlers, resetFieldPermissionStore } from '@/mocks/field-permission-handlers'
import { groupHandlers } from '@/mocks/group-handlers'
import { projectPermissionHandlers } from '@/mocks/project-permission-handlers'
import { useAuthStore } from '@/auth/authStore'
import { FieldPermissionList } from '@/components/field-permissions/FieldPermissionList'

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
      <FieldPermissionList projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

describe('FieldPermissionList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetFieldPermissionStore()
    server.use(...fieldPermissionHandlers, ...groupHandlers, ...projectPermissionHandlers)
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '10000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
      },
    })
    // XSRF-TOKEN 쿠키 설정 — API client가 X-XSRF-TOKEN 헤더로 재전송하는 double submit cookie 패턴
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-FP-1. 로딩 중에는 스켈레톤이 렌더된다.
   */
  it('T-FP-1: 로딩 중 스켈레톤이 렌더된다', () => {
    renderList('ATLAS')
    expect(
      screen.getByRole('status', { name: /필드 권한 규칙 목록 로딩 중/i }),
    ).toBeInTheDocument()
  })

  /**
   * T-FP-2. 규칙이 없으면 빈 상태 메시지가 표시된다.
   */
  it('T-FP-2: 규칙 없음 → 빈 상태 메시지가 표시된다', async () => {
    renderList('ATLAS')
    await waitFor(() => {
      expect(screen.getByText('아직 필드 권한 규칙이 없습니다.')).toBeInTheDocument()
    })
  })

  /**
   * T-FP-3. 규칙이 있으면 목록 행이 렌더된다.
   * MSW 저장소에 규칙 1개를 POST로 추가한 후 렌더 확인.
   */
  it('T-FP-3: 규칙 있음 → 목록 행이 렌더된다', async () => {
    await fetch('/api/v1/projects/ATLAS/field-permissions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
        'X-XSRF-TOKEN': 'test-csrf',
      },
      body: JSON.stringify({
        fieldKind: 'CORE',
        fieldKey: 'summary',
        groupId: '11111111-0000-4000-8000-000000000001',
        accessLevel: 'VIEW',
      }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByTestId('field-permission-row')).toBeInTheDocument()
    })
  })

  /**
   * T-FP-4. API 에러 발생 시 에러 메시지가 표시된다.
   */
  it('T-FP-4: API 에러 → 에러 메시지가 표시된다', async () => {
    server.use(
      http.get('/api/v1/projects/ERR/field-permissions', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
    )
    renderList('ERR')
    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })
  })

  /**
   * T-FP-5. MANAGE_FIELD_PERMISSIONS 권한이 있으면 "규칙 추가" 버튼이 활성화된다.
   */
  it('T-FP-5: MANAGE_FIELD_PERMISSIONS 권한 → 규칙 추가 버튼 활성', async () => {
    renderList('ATLAS')
    await waitFor(() => {
      const btn = screen.getByRole('button', { name: '규칙 추가' })
      expect(btn).not.toBeDisabled()
    })
  })

  /**
   * T-FP-6. MANAGE_FIELD_PERMISSIONS 권한이 없으면 "규칙 추가" 버튼이 비활성화된다 (fail-closed).
   */
  it('T-FP-6: 권한 없음 → 규칙 추가 버튼 비활성(disabled)', async () => {
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
          },
        }),
      ),
    )
    renderList('ATLAS')
    await waitFor(() => {
      const btn = screen.getByRole('button', { name: '규칙 추가' })
      expect(btn).toBeDisabled()
    })
  })

  /**
   * T-FP-7. 권한 있는 사용자가 "규칙 추가" 버튼을 클릭하면 Dialog가 열린다.
   */
  it('T-FP-7: 규칙 추가 버튼 클릭 → Dialog가 열린다', async () => {
    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '규칙 추가' })).not.toBeDisabled()
    })

    await user.click(screen.getByRole('button', { name: '규칙 추가' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

  /**
   * T-FP-8. 행의 삭제 버튼 클릭 시 onDelete 콜백이 호출된다.
   */
  it('T-FP-8: 삭제 버튼 클릭 → 삭제 뮤테이션이 호출된다', async () => {
    await fetch('/api/v1/projects/ATLAS/field-permissions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
        'X-XSRF-TOKEN': 'test-csrf',
      },
      body: JSON.stringify({
        fieldKind: 'CORE',
        fieldKey: 'summary',
        groupId: '11111111-0000-4000-8000-000000000001',
        accessLevel: 'VIEW',
      }),
    })

    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByTestId('field-permission-row')).toBeInTheDocument()
    })

    const deleteBtn = screen.getByRole('button', { name: /삭제/i })
    await user.click(deleteBtn)

    // 인라인 삭제 확인 UI 등장
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '삭제 확인' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '삭제 확인' }))

    // 삭제 후 빈 상태 메시지
    await waitFor(() => {
      expect(screen.getByText('아직 필드 권한 규칙이 없습니다.')).toBeInTheDocument()
    })
  })

  /**
   * T-FP-9. Dialog에서 fieldKind·fieldKey·groupId·accessLevel 선택 후 저장하면 규칙이 생성된다.
   */
  it('T-FP-9: Dialog 폼 제출 → 규칙이 목록에 추가된다', async () => {
    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '규칙 추가' })).not.toBeDisabled()
    })

    await user.click(screen.getByRole('button', { name: '규칙 추가' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // fieldKind 선택 (CORE)
    const fieldKindSelect = screen.getByRole('combobox', { name: '필드 종류' })
    await user.selectOptions(fieldKindSelect, 'CORE')

    // fieldKey 선택 (CORE 화이트리스트 select)
    const fieldKeySelect = screen.getByRole('combobox', { name: '필드 키' })
    await user.selectOptions(fieldKeySelect, 'summary')

    // 그룹 목록이 로드되기를 기다림 (개발팀 옵션이 렌더되면 선택)
    await waitFor(() => {
      // 그룹 드롭다운에 개발팀 옵션이 있어야 함
      const groupSelect = screen.getByRole('combobox', { name: '그룹' })
      const devOption = Array.from(groupSelect.querySelectorAll('option')).find(
        (o) => o.value === '11111111-0000-4000-8000-000000000001',
      )
      expect(devOption).toBeDefined()
    })
    const groupSelect = screen.getByRole('combobox', { name: '그룹' })
    await user.selectOptions(groupSelect, '11111111-0000-4000-8000-000000000001')

    // accessLevel 선택 (VIEW)
    const accessLevelSelect = screen.getByRole('combobox', { name: '접근 수준' })
    await user.selectOptions(accessLevelSelect, 'VIEW')

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByTestId('field-permission-row')).toBeInTheDocument()
    })
  })
})
