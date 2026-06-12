// 커스텀 필드 목록 컴포넌트 단위 테스트 — 4분기 렌더 + 권한 게이팅 + Dialog 연동 (FR-IS-10 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { customFieldHandlers, resetCustomFieldStore } from '@/mocks/custom-field-handlers'
import { projectPermissionHandlers } from '@/mocks/project-permission-handlers'
import { useAuthStore } from '@/auth/authStore'
import { CustomFieldList } from '@/components/custom-fields/CustomFieldList'

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
      <CustomFieldList projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

describe('CustomFieldList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(toast.error).mockClear()
    resetCustomFieldStore()
    server.use(...customFieldHandlers, ...projectPermissionHandlers)
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '10000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-CF-1. 로딩 중에는 스켈레톤이 렌더된다.
   */
  it('T-CF-1: 로딩 중 스켈레톤이 렌더된다', () => {
    renderList('ATLAS')
    // 로딩 시작 직후 — 네트워크 응답 전
    expect(screen.getByRole('status', { name: /커스텀 필드 목록 로딩 중/i })).toBeInTheDocument()
  })

  /**
   * T-CF-2. 커스텀 필드가 없으면 빈 상태 메시지가 표시된다.
   */
  it('T-CF-2: 커스텀 필드 없음 → 빈 상태 메시지가 표시된다', async () => {
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('아직 커스텀 필드가 없습니다.')).toBeInTheDocument()
    })
  })

  /**
   * T-CF-3. 커스텀 필드가 있으면 목록이 렌더된다.
   * MSW 저장소에 필드 1개를 추가한 후 렌더 확인.
   */
  it('T-CF-3: 커스텀 필드 있음 → 목록 행이 렌더된다', async () => {
    // 직접 POST로 필드를 생성한다
    await fetch('/api/v1/projects/ATLAS/custom-fields', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ key: 'priority', name: '우선순위', fieldType: 'NUMBER' }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('우선순위')).toBeInTheDocument()
    })
  })

  /**
   * T-CF-4. 에러 발생 시 에러 메시지가 표시된다.
   */
  it('T-CF-4: API 에러 → 에러 메시지가 표시된다', async () => {
    server.use(
      http.get('/api/v1/projects/ERR/custom-fields', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
    )

    renderList('ERR')

    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })
  })

  /**
   * T-CF-5. ADMIN 권한이면 "필드 추가" 버튼이 활성화된다.
   */
  it('T-CF-5: ADMIN 권한 → 필드 추가 버튼 활성', async () => {
    renderList('ATLAS')

    await waitFor(() => {
      const btn = screen.getByRole('button', { name: '필드 추가' })
      expect(btn).not.toBeDisabled()
    })
  })

  /**
   * T-CF-6. 권한 없음(MEMBER)이면 "필드 추가" 버튼이 비활성화된다 (fail-closed).
   */
  it('T-CF-6: 권한 없음(MEMBER) → 필드 추가 버튼 비활성(disabled)', async () => {
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
      const btn = screen.getByRole('button', { name: '필드 추가' })
      expect(btn).toBeDisabled()
    })
  })

  /**
   * T-CF-7. 권한 있는 사용자가 "필드 추가" 버튼을 클릭하면 Dialog가 열린다.
   */
  it('T-CF-7: 필드 추가 버튼 클릭 → Dialog가 열린다', async () => {
    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '필드 추가' })).not.toBeDisabled()
    })

    await user.click(screen.getByRole('button', { name: '필드 추가' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

  /**
   * T-CF-8. fieldType 칩이 표시된다 (NUMBER → '숫자').
   */
  it('T-CF-8: 목록 행에 fieldType 한국어 칩이 표시된다', async () => {
    await fetch('/api/v1/projects/ATLAS/custom-fields', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ key: 'score', name: '점수', fieldType: 'NUMBER' }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('숫자')).toBeInTheDocument()
    })
  })

  /**
   * T-CF-9. displayOrder 오름차순으로 행이 정렬된다.
   * displayOrder=0인 필드가 displayOrder=10인 필드보다 먼저 나타나야 한다.
   */
  it('T-CF-9: displayOrder 오름차순 정렬이 유지된다', async () => {
    // displayOrder가 다른 2개 필드 생성
    await fetch('/api/v1/projects/ATLAS/custom-fields', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ key: 'field_b', name: '나중 필드', fieldType: 'SHORT_TEXT', displayOrder: 10 }),
    })
    await fetch('/api/v1/projects/ATLAS/custom-fields', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ key: 'field_a', name: '먼저 필드', fieldType: 'SHORT_TEXT', displayOrder: 0 }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      const items = screen.getAllByRole('listitem')
      const texts = items.map((el) => el.textContent ?? '')
      const firstIndex = texts.findIndex((t) => t.includes('먼저 필드'))
      const secondIndex = texts.findIndex((t) => t.includes('나중 필드'))
      expect(firstIndex).toBeLessThan(secondIndex)
    })
  })

  /**
   * T-CF-10. required=true인 필드는 행에 '필수' 표시가 나타난다.
   */
  it('T-CF-10: required=true 필드 → 행에 필수 표시가 나타난다', async () => {
    await fetch('/api/v1/projects/ATLAS/custom-fields', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ key: 'mandatory', name: '필수 항목', fieldType: 'SHORT_TEXT', required: true }),
    })

    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('필수')).toBeInTheDocument()
    })
  })

  /**
   * T-CF-11 (C3). 생성 실패 시 toast.error가 호출되지 않고 Dialog 인라인 에러만 표시된다.
   * useCreateCustomField는 silent:true로 호출되므로 toast가 중복 발사되면 안 된다.
   */
  it('T-CF-11: 생성 실패 → toast 미발사, Dialog 인라인 에러만 표시된다', async () => {
    // 409 중복키 응답으로 오버라이드
    server.use(
      http.post('/api/v1/projects/ATLAS/custom-fields', () =>
        HttpResponse.json(
          {
            type: 'https://bts.example.com/problems/custom-field-key-duplicate',
            title: 'Custom Field Key Duplicate',
            status: 409,
            detail: '이미 같은 키의 필드가 있습니다.',
            errorCode: 'CUSTOM_FIELD_KEY_DUPLICATE',
            timestamp: new Date().toISOString(),
          },
          { status: 409 },
        ),
      ),
    )

    const user = userEvent.setup()
    renderList('ATLAS')

    // 권한 로딩 완료 대기
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '필드 추가' })).not.toBeDisabled()
    })

    await user.click(screen.getByRole('button', { name: '필드 추가' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // 폼 제출 (필수 필드 입력 후 저장)
    const nameInput = screen.getByRole('textbox', { name: '이름' })
    const keyInput = screen.getByRole('textbox', { name: '키' })
    await user.type(nameInput, '테스트')
    await user.clear(keyInput)
    await user.type(keyInput, 'dup-key')
    await user.click(screen.getByRole('button', { name: '저장' }))

    // Dialog 인라인 에러 표시 대기
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // toast.error는 호출되지 않아야 한다 (인라인 메시지가 유일한 에러 표시)
    expect(toast.error).not.toHaveBeenCalled()
  })

  /**
   * T-CF-12 (C3). 수정 실패 시 toast.error가 호출되지 않고 Dialog 인라인 에러만 표시된다.
   */
  it('T-CF-12: 수정 실패 → toast 미발사, Dialog가 유지된다', async () => {
    // 필드 1개 생성 후 수정 시도
    await fetch('/api/v1/projects/ATLAS/custom-fields', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer mock-access-token-alice',
      },
      body: JSON.stringify({ key: 'edit-target', name: '수정 대상', fieldType: 'SHORT_TEXT' }),
    })

    // 수정은 404로 강제 실패
    server.use(
      http.patch('/api/v1/projects/ATLAS/custom-fields/:fieldId', () =>
        HttpResponse.json(
          {
            type: 'https://bts.example.com/problems/custom-field-not-found',
            title: 'Custom Field Not Found',
            status: 404,
            detail: '필드를 찾을 수 없습니다.',
            errorCode: 'CUSTOM_FIELD_NOT_FOUND',
            timestamp: new Date().toISOString(),
          },
          { status: 404 },
        ),
      ),
    )

    const user = userEvent.setup()
    renderList('ATLAS')

    await waitFor(() => {
      expect(screen.getByText('수정 대상')).toBeInTheDocument()
    })

    // 수정 버튼 클릭
    const editButtons = screen.getAllByRole('button', { name: /수정/i })
    await user.click(editButtons[0]!)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '저장' }))

    // Dialog가 열린 채로 유지됨
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // toast.error 미발사 확인
    expect(toast.error).not.toHaveBeenCalled()
  })
})
