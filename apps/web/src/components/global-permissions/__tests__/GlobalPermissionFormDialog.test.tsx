// 전역 권한 부여 폼 다이얼로그 단위 테스트 — 종류 토글 리셋(B-3) + 제출 payload + 에러 매핑 (FR-PM-10 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { globalPermissionHandlers, resetGlobalPermissionStore } from '@/mocks/global-permission-handlers'
import { groupHandlers, resetGroupStore } from '@/mocks/group-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { useAuthStore } from '@/auth/authStore'
import { GlobalPermissionFormDialog } from '@/components/global-permissions/GlobalPermissionFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const GRANT_PATH = '/api/v1/admin/global-permissions'

/** group-handlers.ts DEFAULT_GROUPS[0] 고정 id — '개발팀' */
const DEV_TEAM_GROUP_ID = '11111111-0000-4000-8000-000000000001'

const SEARCH_RESULTS = [
  { id: '00000000-0000-4000-8000-000000000003', username: 'carol', displayName: '캐럴', email: null },
]

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function renderDialog(overrides: { isOpen?: boolean; onClose?: () => void } = {}) {
  const onClose = overrides.onClose ?? vi.fn()
  render(
    <QueryClientProvider client={makeClient()}>
      <GlobalPermissionFormDialog isOpen={overrides.isOpen ?? true} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { onClose }
}

/** GROUP 종류로 전환 후 '개발팀'을 선택한다 (제출 가능 상태를 만드는 공통 시퀀스) */
async function selectDevTeamGroup(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('radio', { name: '그룹' }))
  await waitFor(() => {
    expect(screen.getByRole('option', { name: '개발팀' })).toBeInTheDocument()
  })
  await user.selectOptions(screen.getByLabelText('그룹 선택'), '개발팀')
}

beforeEach(() => {
  vi.clearAllMocks()
  resetGlobalPermissionStore()
  resetGroupStore()
  // 이 worktree 환경에서 setupServer 초기 핸들러 등록이 파일 단독 실행 시 유실되는 경우가 있어
  // (FieldPermissionList.test.tsx 선례와 동일하게) 각 테스트 전에 명시적으로 재등록한다.
  server.use(...groupHandlers, ...userHandlers, ...globalPermissionHandlers)
  useAuthStore.setState({
    accessToken: 'mock-access-token-alice',
    user: {
      userId: '00000000-0000-4000-8000-000000000001',
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      mustChangePassword: false,
      isSystemAdmin: true,
      mfaEnrollmentRequired: false,
    },
  })
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('GlobalPermissionFormDialog — 권한 select', () => {
  it('T-GPFD-1: GLOBAL_PERMISSION_CODES 옵션이 렌더되고 기본값은 CREATE_PROJECT다', () => {
    renderDialog()

    const select = screen.getByLabelText('권한') as HTMLSelectElement
    expect(select.value).toBe('CREATE_PROJECT')
    expect(screen.getByRole('option', { name: '프로젝트 생성' })).toBeInTheDocument()
  })
})

describe('GlobalPermissionFormDialog — 종류 토글 시 granteeId 리셋 (B-3, 핵심)', () => {
  it('T-GPFD-2: USER 선택 후 GROUP으로 전환하면 제출 버튼이 다시 비활성화된다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(SEARCH_RESULTS)),
    )
    const user = userEvent.setup()
    renderDialog()

    // USER 종류에서 대상 검색 후 선택 → 제출 버튼 활성화
    await user.type(screen.getByLabelText('대상 검색'), 'ca')
    await waitFor(() => {
      expect(screen.getByText('캐럴')).toBeInTheDocument()
    })
    await user.click(screen.getByText('캐럴'))
    expect(screen.getByRole('button', { name: '부여' })).toBeEnabled()

    // GROUP 으로 전환 — 새로 선택하지 않은 상태
    await user.click(screen.getByRole('radio', { name: '그룹' }))

    // granteeId가 리셋되지 않으면(=버그) 이전 사용자 id가 그대로 남아 버튼이 활성 상태로 남는다.
    // 리셋되면 새 대상을 고르기 전까지 비활성 상태여야 한다.
    expect(screen.getByRole('button', { name: '부여' })).toBeDisabled()
  })

  it('T-GPFD-3: GROUP 선택 후 USER로 전환하면 제출 버튼이 다시 비활성화된다 (역방향)', async () => {
    const user = userEvent.setup()
    renderDialog()

    await selectDevTeamGroup(user)
    expect(screen.getByRole('button', { name: '부여' })).toBeEnabled()

    await user.click(screen.getByRole('radio', { name: '사용자' }))

    expect(screen.getByRole('button', { name: '부여' })).toBeDisabled()
  })
})

describe('GlobalPermissionFormDialog — USER 검색 분기', () => {
  it('T-GPFD-4: 2자 미만 입력에는 검색 결과를 표시하지 않는다', async () => {
    let requestCount = 0
    server.use(
      http.get('/api/v1/users', () => {
        requestCount++
        return HttpResponse.json(SEARCH_RESULTS)
      }),
    )
    const user = userEvent.setup()
    renderDialog()

    await user.type(screen.getByLabelText('대상 검색'), 'c')

    await waitFor(() => {
      expect(requestCount).toBe(0)
    })
  })

  it('T-GPFD-5: 2자 이상 입력 시 검색 결과에서 사용자를 선택할 수 있다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(SEARCH_RESULTS)),
    )
    const user = userEvent.setup()
    renderDialog()

    await user.type(screen.getByLabelText('대상 검색'), 'ca')
    await waitFor(() => {
      expect(screen.getByText('캐럴')).toBeInTheDocument()
    })
    await user.click(screen.getByText('캐럴'))

    expect(screen.getByRole('button', { name: '부여' })).toBeEnabled()
  })
})

describe('GlobalPermissionFormDialog — GROUP 드롭다운 분기', () => {
  it('T-GPFD-6: GROUP 종류에서 그룹 목록이 드롭다운으로 노출되고 선택 가능하다', async () => {
    const user = userEvent.setup()
    renderDialog()

    await selectDevTeamGroup(user)

    const select = screen.getByLabelText('그룹 선택') as HTMLSelectElement
    expect(select.value).toBe(DEV_TEAM_GROUP_ID)
    expect(screen.getByRole('button', { name: '부여' })).toBeEnabled()
  })
})

describe('GlobalPermissionFormDialog — 제출', () => {
  it('T-GPFD-7: 제출 시 올바른 payload로 POST 요청이 전송되고 성공하면 onClose가 호출된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post(GRANT_PATH, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(
          {
            id: '33333333-0000-4000-8000-000000000099',
            permission: 'CREATE_PROJECT',
            granteeType: 'GROUP',
            granteeId: DEV_TEAM_GROUP_ID,
            grantedBy: '00000000-0000-4000-8000-000000000001',
            createdAt: '2026-07-18T00:00:00Z',
          },
          { status: 201 },
        )
      }),
    )
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderDialog({ onClose })

    await selectDevTeamGroup(user)
    await user.click(screen.getByRole('button', { name: '부여' }))

    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
    expect(capturedBody).toEqual({
      permission: 'CREATE_PROJECT',
      granteeType: 'GROUP',
      granteeId: DEV_TEAM_GROUP_ID,
    })
  })
})

describe('GlobalPermissionFormDialog — 에러 매핑 (FR-8)', () => {
  it.each([
    [409, 'grant_already_exists', '이미 부여된 권한입니다'],
    [404, 'grantee_not_found', '대상을 찾을 수 없습니다'],
    [400, 'unknown_permission', '알 수 없는 권한입니다'],
  ])(
    'T-GPFD-8: %i 응답 코드 %s → "%s" 표시 + 다이얼로그 유지(onClose 미호출)',
    async (status, code, message) => {
      server.use(
        http.post(GRANT_PATH, () => HttpResponse.json({ error: code }, { status })),
      )
      const onClose = vi.fn()
      const user = userEvent.setup()
      renderDialog({ onClose })

      await selectDevTeamGroup(user)
      await user.click(screen.getByRole('button', { name: '부여' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(message)
      })
      expect(onClose).not.toHaveBeenCalled()
    },
  )

  it('T-GPFD-9: 알 수 없는 에러 코드 → 기본 실패 메시지를 표시한다', async () => {
    server.use(
      http.post(GRANT_PATH, () => HttpResponse.json({ error: 'something_unexpected' }, { status: 500 })),
    )
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderDialog({ onClose })

    await selectDevTeamGroup(user)
    await user.click(screen.getByRole('button', { name: '부여' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('권한 부여에 실패했습니다')
    })
    expect(onClose).not.toHaveBeenCalled()
  })
})
