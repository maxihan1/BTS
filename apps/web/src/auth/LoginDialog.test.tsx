// 전역 로그인 모달 테스트 — 열림 조건 · 닫기 3경로 봉인 · 로그인 후 라우팅 우선순위
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LoginDialog } from './LoginDialog'
import { useAuthStore } from './authStore'
import { useLoginPromptStore } from './loginPromptStore'
import { makeWhoami } from '@/mocks/auth-fixtures'

// 라우터 의존은 pathname 읽기와 navigate 두 가지뿐이라 통째로 대체한다.
// vi.mock 팩토리는 호이스팅되므로 외부 변수를 참조할 수 없다 — vi.hoisted 로 먼저 만든다.
const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  pathname: { current: '/login' },
}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => routerMocks.navigate,
  useRouterState: ({ select }: { select: (s: unknown) => unknown }) =>
    select({ location: { pathname: routerMocks.pathname.current } }),
}))

// LoginForm 은 provider 조회·MFA·WebAuthn 을 오케스트레이션하는 무거운 컴포넌트다.
// 이 파일은 모달 껍데기의 계약만 검증하므로 onSuccess 를 즉시 트리거하는 스텁으로 교체한다.
vi.mock('@/auth/LoginForm', () => ({
  LoginForm: ({ onSuccess }: { onSuccess?: () => void }) => (
    <button onClick={() => onSuccess?.()}>성공 트리거</button>
  ),
}))

/** window.location.search 를 테스트별로 오버라이드하는 헬퍼 */
function setLocationSearch(search: string) {
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    search,
  } as unknown as Location)
}

let queryClient: QueryClient

function renderDialog() {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <LoginDialog />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  routerMocks.navigate.mockClear()
  routerMocks.pathname.current = '/login'
  useAuthStore.setState({ accessToken: null, user: null })
  useLoginPromptStore.getState().reset()
  setLocationSearch('')
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  useLoginPromptStore.getState().reset()
  vi.restoreAllMocks()
})

describe('LoginDialog — 열림 조건', () => {
  it('미인증 + /login 이면 열린다', async () => {
    renderDialog()

    expect(await screen.findByRole('dialog', { name: 'BTS 로그인' })).toBeInTheDocument()
  })

  it('미인증 + 세션 만료 플래그이면 현재 라우트를 유지한 채 열린다', async () => {
    routerMocks.pathname.current = '/issues/ATLAS-1'
    useLoginPromptStore.getState().promptSessionExpired()

    renderDialog()

    expect(await screen.findByRole('dialog', { name: 'BTS 로그인' })).toBeInTheDocument()
    // 세션 만료는 이동하지 않는 것이 요구사항이다 — navigate 가 불리면 작업 내용이 날아간다.
    expect(routerMocks.navigate).not.toHaveBeenCalled()
  })

  it('인증 상태이면 /login 이어도 열리지 않는다', () => {
    useAuthStore.setState({ accessToken: 'tok', user: makeWhoami() })

    renderDialog()

    expect(screen.queryByRole('dialog', { name: 'BTS 로그인' })).toBeNull()
  })

  it('미인증이어도 /login 이 아니고 만료 플래그도 없으면 열리지 않는다 (공개 라우트)', () => {
    // dashboards/shared/$token 같은 미인증 공개 라우트를 모달로 가리면 안 된다.
    routerMocks.pathname.current = '/dashboards/shared/abc123'

    renderDialog()

    expect(screen.queryByRole('dialog', { name: 'BTS 로그인' })).toBeNull()
  })
})

describe('LoginDialog — 닫기 3경로 봉인', () => {
  it('ESC 로 닫히지 않는다', async () => {
    const user = userEvent.setup()
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })

    await user.keyboard('{Escape}')

    // 닫으면 AuthBackdrop 만 남는 막다른 골목이 된다(D2).
    expect(screen.getByRole('dialog', { name: 'BTS 로그인' })).toBeInTheDocument()
  })

  it('오버레이 바깥 클릭으로 닫히지 않는다', async () => {
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })

    const overlay = document.querySelector('[data-slot="dialog-overlay"]')
    expect(overlay).not.toBeNull()
    fireEvent.pointerDown(overlay as Element)

    expect(screen.getByRole('dialog', { name: 'BTS 로그인' })).toBeInTheDocument()
  })

  it('X 닫기 버튼을 렌더하지 않는다', async () => {
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })

    // disabled X 는 "닫을 수 있는데 지금은 안 된다"는 거짓 신호라 아예 렌더하지 않는다(D2).
    expect(document.querySelector('[data-slot="dialog-close-button"]')).toBeNull()
  })
})

describe('LoginDialog — 로그인 성공 후 라우팅 우선순위', () => {
  async function triggerSuccess() {
    const user = userEvent.setup()
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })
    await user.click(screen.getByRole('button', { name: '성공 트리거' }))
  }

  it('returnTo 가 있고 안전한 내부 경로이면 returnTo 로 navigate 한다', async () => {
    setLocationSearch('?returnTo=/issues/PROJ-5')
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    await triggerSuccess()

    expect(routerMocks.navigate).toHaveBeenCalledWith({ to: '/issues/PROJ-5' })
  })

  it('returnTo 가 안전하지 않으면(외부 URL) 무시하고 start_page 매핑을 따른다', async () => {
    setLocationSearch('?returnTo=http://evil.com')
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    await triggerSuccess()

    expect(routerMocks.navigate).toHaveBeenCalledWith({ to: '/inbox' })
  })

  it('returnTo 가 없고 startPage="inbox" 이면 /inbox 로 navigate 한다', async () => {
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    await triggerSuccess()

    expect(routerMocks.navigate).toHaveBeenCalledWith({ to: '/inbox' })
  })

  it('returnTo 도 startPage 도 없으면 /dashboards 로 navigate 한다', async () => {
    useAuthStore.setState({ user: makeWhoami() })

    await triggerSuccess()

    expect(routerMocks.navigate).toHaveBeenCalledWith({ to: '/dashboards' })
  })

  it('startPage="my_issues" 이면 store 의 userId 를 실어 /issues?assignee=<userId> 로 navigate 한다', async () => {
    const userId = '00000000-0000-4000-8000-000000000099'
    useAuthStore.setState({ user: makeWhoami({ startPage: 'my_issues', userId }) })

    await triggerSuccess()

    expect(routerMocks.navigate).toHaveBeenCalledWith({
      to: '/issues',
      search: { assignee: userId },
    })
  })
})

describe('LoginDialog — 세션 만료 재로그인은 이동 없이 화면을 회복시킨다', () => {
  it('만료 트리거로 열린 모달의 성공은 navigate 대신 쿼리 무효화를 부른다', async () => {
    routerMocks.pathname.current = '/issues/ATLAS-1'
    useLoginPromptStore.getState().promptSessionExpired()
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    const user = userEvent.setup()
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: '성공 트리거' }))

    // 사용자가 보던 화면에 그대로 머무르고, 데이터만 다시 가져온다.
    expect(routerMocks.navigate).not.toHaveBeenCalled()
    expect(invalidateSpy).toHaveBeenCalled()
  })

  it('만료 플래그가 켜진 채 /login 에 있으면 로그인 후 목적지로 이동한다 (막다른 골목 금지)', async () => {
    // 만료로 모달이 뜬 뒤 사용자가 뒤로가기 등으로 이동하면 requireAuth 가 /login 으로 보낸다.
    // 그 상태에서 만료 분기를 타면 navigate 를 건너뛰어 AuthBackdrop 만 남은 /login 에 갇힌다.
    routerMocks.pathname.current = '/login'
    useLoginPromptStore.getState().promptSessionExpired()
    useAuthStore.setState({ user: makeWhoami({ startPage: 'inbox' }) })

    const user = userEvent.setup()
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })

    await user.click(screen.getByRole('button', { name: '성공 트리거' }))

    expect(routerMocks.navigate).toHaveBeenCalledWith({ to: '/inbox' })
  })

  it('성공 후 만료 플래그가 해제된다', async () => {
    routerMocks.pathname.current = '/issues/ATLAS-1'
    useLoginPromptStore.getState().promptSessionExpired()
    useAuthStore.setState({ user: makeWhoami() })

    const user = userEvent.setup()
    renderDialog()
    await screen.findByRole('dialog', { name: 'BTS 로그인' })

    await user.click(screen.getByRole('button', { name: '성공 트리거' }))

    expect(useLoginPromptStore.getState().sessionExpired).toBe(false)
  })
})
