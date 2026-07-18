// 전역 권한 목록 컴포넌트 단위 테스트 — 목록 조합 + 빈 상태 + 부여/회수 플로우 + 로딩/에러 (FR-PM-10 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { globalPermissionHandlers, resetGlobalPermissionStore } from '@/mocks/global-permission-handlers'
import { groupHandlers, resetGroupStore } from '@/mocks/group-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { userAliceFixture } from '@/mocks/user-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { GlobalPermissionList } from '@/components/global-permissions/GlobalPermissionList'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const GRANT_PATH = '/api/v1/admin/global-permissions'

/** group-handlers.ts DEFAULT_GROUPS[0] 고정 id — '개발팀' */
const DEV_TEAM_GROUP_ID = '11111111-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function renderList() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <GlobalPermissionList />
    </QueryClientProvider>,
  )
}

/** userAliceFixture(user-fixtures.ts)에게 CREATE_PROJECT 권한을 부여하는 grant를 미리 생성한다. */
async function seedAliceGrant(): Promise<void> {
  await fetch(GRANT_PATH, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer mock-access-token-alice',
      'X-XSRF-TOKEN': 'test-csrf',
    },
    body: JSON.stringify({
      permission: 'CREATE_PROJECT',
      granteeType: 'USER',
      granteeId: userAliceFixture.id,
    }),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  resetGlobalPermissionStore()
  resetGroupStore()
  // 이 worktree 환경에서 setupServer 초기 핸들러 등록이 파일 단독 실행 시 유실되는 경우가 있어
  // (FieldPermissionList.test.tsx / GlobalPermissionFormDialog.test.tsx 선례와 동일하게)
  // 각 테스트 전에 명시적으로 재등록한다.
  server.use(...globalPermissionHandlers, ...userHandlers, ...groupHandlers)
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

describe('GlobalPermissionList — heading', () => {
  it('T-GPL-1: h1 heading "전역 권한 관리"가 렌더된다', async () => {
    renderList()
    expect(screen.getByRole('heading', { level: 1, name: '전역 권한 관리' })).toBeInTheDocument()
  })
})

describe('GlobalPermissionList — 목록 렌더', () => {
  it('T-GPL-2: 시드된 grant가 표시 이름으로 표에 렌더된다', async () => {
    await seedAliceGrant()
    renderList()

    await waitFor(() => {
      expect(screen.getByTestId('global-permission-row')).toBeInTheDocument()
    })

    expect(screen.getByText('프로젝트 생성')).toBeInTheDocument()
    expect(screen.getByText('사용자')).toBeInTheDocument()
    expect(screen.getByText(userAliceFixture.displayName ?? userAliceFixture.username)).toBeInTheDocument()
  })

  it('T-GPL-3: 시맨틱 table + th scope="col" 6개 컬럼 헤더가 렌더된다', async () => {
    await seedAliceGrant()
    renderList()

    await waitFor(() => {
      expect(screen.getByTestId('global-permission-row')).toBeInTheDocument()
    })

    const table = screen.getByRole('table')
    const headers = table.querySelectorAll('th[scope="col"]')
    expect(headers).toHaveLength(6)
  })
})

describe('GlobalPermissionList — 빈 목록 (FR-9)', () => {
  it('T-GPL-4: 빈 목록이면 안내 문구가 표시된다', async () => {
    renderList()

    await waitFor(() => {
      expect(screen.getByText('부여된 전역 권한이 없습니다')).toBeInTheDocument()
    })
    expect(
      screen.getByText('SYSTEM_ADMIN 은 모든 전역 권한을 자동 보유합니다'),
    ).toBeInTheDocument()
  })
})

describe('GlobalPermissionList — 부여 다이얼로그', () => {
  it('T-GPL-5: "권한 부여" 클릭 → 다이얼로그가 열린다', async () => {
    const user = userEvent.setup()
    renderList()

    await waitFor(() => {
      expect(screen.getByText('부여된 전역 권한이 없습니다')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '권한 부여' }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

  it('T-GPL-6: 다이얼로그에서 "취소" 클릭 → 다이얼로그가 닫힌다', async () => {
    const user = userEvent.setup()
    renderList()

    await waitFor(() => {
      expect(screen.getByText('부여된 전역 권한이 없습니다')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '권한 부여' }))
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '취소' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('T-GPL-7: 다이얼로그에서 그룹에 부여 제출 → 목록에 즉시 반영된다', async () => {
    const user = userEvent.setup()
    renderList()

    await waitFor(() => {
      expect(screen.getByText('부여된 전역 권한이 없습니다')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '권한 부여' }))
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('radio', { name: '그룹' }))
    await waitFor(() => {
      expect(screen.getByRole('option', { name: '개발팀' })).toBeInTheDocument()
    })
    await user.selectOptions(screen.getByLabelText('그룹 선택'), '개발팀')
    await user.click(screen.getByRole('button', { name: '부여' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByTestId('global-permission-row')).toBeInTheDocument()
    })
    expect(screen.getByText('개발팀')).toBeInTheDocument()
  })
})

describe('GlobalPermissionList — 회수 (FR-6/FR-7)', () => {
  it('T-GPL-8: 회수 확인 → 목록에서 제거된다(invalidate 경유 재조회)', async () => {
    await seedAliceGrant()
    const user = userEvent.setup()
    renderList()

    await waitFor(() => {
      expect(screen.getByTestId('global-permission-row')).toBeInTheDocument()
    })

    const granteeName = userAliceFixture.displayName ?? userAliceFixture.username
    await user.click(screen.getByRole('button', { name: `${granteeName} 전역 권한 회수` }))

    await waitFor(() => {
      expect(screen.getByText('삭제하시겠습니까?')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(screen.getByText('부여된 전역 권한이 없습니다')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('global-permission-row')).not.toBeInTheDocument()
  })

  it('T-GPL-9: 회수 확인 취소 → onRevoke 미호출, 목록 불변(EC-7)', async () => {
    await seedAliceGrant()
    const user = userEvent.setup()
    renderList()

    await waitFor(() => {
      expect(screen.getByTestId('global-permission-row')).toBeInTheDocument()
    })

    const granteeName = userAliceFixture.displayName ?? userAliceFixture.username
    await user.click(screen.getByRole('button', { name: `${granteeName} 전역 권한 회수` }))
    await waitFor(() => {
      expect(screen.getByText('삭제하시겠습니까?')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByText('삭제하시겠습니까?')).not.toBeInTheDocument()
    expect(screen.getByTestId('global-permission-row')).toBeInTheDocument()
  })
})

describe('GlobalPermissionList — 로딩/에러 상태 (FR-8, 방어적)', () => {
  it('T-GPL-10: 로딩 중에는 로딩 상태가 표시된다', () => {
    renderList()
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('T-GPL-11: 목록 조회 실패 시 에러 메시지가 표시된다', async () => {
    server.use(
      http.get(GRANT_PATH, () => HttpResponse.json({ error: 'server_error' }, { status: 500 })),
    )
    renderList()

    await waitFor(() => {
      expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument()
    })
  })
})
