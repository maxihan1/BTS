// GitWebhookSection 통합 테스트 — 4상태·등록→URL모달 순차(FR2)·FR7 reset 닫기·삭제 3분기·재발급 도움말 상시 렌더 (FR-AT-07 PR-D Task 6)
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach, afterAll } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { GitWebhookSection } from './GitWebhookSection'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { gitWebhookHandlers } from '@/mocks/git-webhook-handlers'
import {
  DEFAULT_GIT_WEBHOOK_PROJECT_KEY,
  DEFAULT_GIT_WEBHOOKS,
  gitWebhookStore,
  generateUuidV4,
  resetGitWebhookStore,
  seedGitWebhooks,
} from '@/mocks/git-webhook-fixtures'
import { formatDateTimeByPreset } from '@/lib/date-preferences'

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — AutomationRuleList.test.tsx / useGitWebhooks.test.tsx 동형
// (전역 handlers.ts 미등록, 로컬 서버로 격리)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...gitWebhookHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  server.resetHandlers()
  resetGitWebhookStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  vi.clearAllMocks()
})
afterAll(() => server.close())

const PROJECT_KEY = DEFAULT_GIT_WEBHOOK_PROJECT_KEY
/** 백엔드 MIN_SECRET_LENGTH(16)를 만족하는 유효 secret — useGitWebhooks.test.tsx VALID_SECRET 동형 */
const VALID_SECRET = 'a'.repeat(32)

/** DEFAULT_GIT_WEBHOOKS[0] — noUncheckedIndexedAccess 가드 헬퍼 */
function firstSeedWebhook() {
  const webhook = DEFAULT_GIT_WEBHOOKS[0]
  if (webhook === undefined) {
    throw new Error('fixture DEFAULT_GIT_WEBHOOKS[0]이 비어있음')
  }
  return webhook
}

function renderSection() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <GitWebhookSection projectKey={PROJECT_KEY} />
    </QueryClientProvider>,
  )
  return { ...utils, queryClient }
}

/** "웹훅 등록" 버튼을 눌러 등록 Dialog를 연다 — 렌더될 때까지 기다린다. */
async function openRegisterDialog(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByTestId('git-webhook-add-button'))
  await screen.findByTestId('git-webhook-register-dialog')
}

/** 등록 Dialog가 열려있다고 가정하고 유효 secret을 입력 후 제출한다. */
async function submitRegisterForm(user: ReturnType<typeof userEvent.setup>, secret = VALID_SECRET): Promise<void> {
  await user.type(screen.getByLabelText('Secret'), secret)
  await user.click(screen.getByTestId('git-webhook-register-submit'))
}

// ─────────────────────────────────────────────────────────────────────────────
// ★★ BLOCKER-0 2층 — 등록 Dialog→Section→api→MSW 전층 요청 본문 바이트 단언 (EC2)
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookSection — secret 원문 무손상(BLOCKER-0)', () => {
  it('등록 요청 본문의 secret 이 입력과 바이트 단위로 동일하다', async () => {
    const user = userEvent.setup({ delay: null })
    let capturedBody: unknown

    server.use(
      http.post('/api/v1/projects/:projectKey/automation/git-webhooks', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(
          { id: generateUuidV4(), provider: 'GITHUB', webhookUrl: '/api/v1/webhooks/git/tok', token: 'tok' },
          { status: 201 },
        )
      }),
    )

    renderSection()
    await openRegisterDialog(user)
    await submitRegisterForm(user, '  abcdefghijklmnop  ')

    await waitFor(() => {
      expect(capturedBody).toBeDefined()
    })
    expect((capturedBody as { secret: string }).secret).toBe('  abcdefghijklmnop  ')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR12 — 4상태 전부 (AutomationRuleList.tsx 4분기 동형)
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookSection — 4상태 (FR12)', () => {
  it('loading — role="status" + aria-label 을 낸다', () => {
    renderSection()
    const status = screen.getByRole('status')
    expect(status.getAttribute('aria-label')).toBeTruthy()
  })

  it('error 403 — accessDenied 문구를 렌더한다(백지 아님)', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/git-webhooks', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 }),
      ),
    )

    renderSection()

    await waitFor(() => {
      expect(screen.getByText('권한이 없습니다.')).toBeInTheDocument()
    })
  })

  it('error 그 외 — genericError 문구를 렌더한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/git-webhooks', () =>
        HttpResponse.json({ errorCode: 'UNKNOWN' }, { status: 500 }),
      ),
    )

    renderSection()

    await waitFor(() => {
      expect(screen.getByText('Git 웹훅을 불러오지 못했습니다.')).toBeInTheDocument()
    })
  })

  it('empty — "등록된 Git 웹훅이 없습니다." + 등록 CTA', async () => {
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('등록된 Git 웹훅이 없습니다.')).toBeInTheDocument()
    })
    expect(screen.getByTestId('git-webhook-add-button')).toBeInTheDocument()
  })

  it('list — provider·createdAt 으로 식별해 렌더한다', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()

    renderSection()
    const row = await screen.findByTestId(`git-webhook-row-${target.id}`)

    expect(within(row).getByText('GitHub')).toBeInTheDocument()
    expect(within(row).getByText(formatDateTimeByPreset(target.createdAt, 'iso'))).toBeInTheDocument()
  })

  it('createdBy UUID 를 화면에 표시하지 않는다', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()

    renderSection()
    await screen.findByTestId(`git-webhook-row-${target.id}`)

    expect(screen.queryByText(target.createdBy)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR2 — 등록 Dialog와 URL 모달은 겹치지 않는다(순차 가드)
// FR7 — URL 모달의 유일한 닫기 기전은 registerMutation.reset()
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookSection — 등록→URL 모달 순차(FR2)·reset 닫기(FR7)', () => {
  it('201 직후 등록 Dialog 가 먼저 닫히고 그 다음 URL 모달이 열린다', async () => {
    const user = userEvent.setup({ delay: null })
    renderSection()

    await openRegisterDialog(user)
    await submitRegisterForm(user)

    await waitFor(() => {
      expect(screen.getByTestId('git-webhook-url-dialog')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('git-webhook-register-dialog')).not.toBeInTheDocument()
  })

  it('URL 모달에서 닫기→확인 하면 모달이 사라진다', async () => {
    const user = userEvent.setup({ delay: null })
    renderSection()

    await openRegisterDialog(user)
    await submitRegisterForm(user)
    await screen.findByTestId('git-webhook-url-dialog')

    await user.click(screen.getByTestId('git-webhook-url-close-button'))
    await user.click(await screen.findByTestId('git-webhook-url-close-confirm'))

    await waitFor(() => {
      expect(screen.queryByTestId('git-webhook-url-dialog')).not.toBeInTheDocument()
    })
  })

  it('모달을 닫은 뒤 다시 등록하면 이전 URL 이 아니라 새 URL 이 뜬다', async () => {
    const user = userEvent.setup({ delay: null })
    renderSection()

    await openRegisterDialog(user)
    await submitRegisterForm(user)
    const firstDialog = await screen.findByTestId('git-webhook-url-dialog')
    const firstUrlText = within(firstDialog).getByText(/\/api\/v1\/webhooks\/git\//).textContent

    await user.click(screen.getByTestId('git-webhook-url-close-button'))
    await user.click(await screen.findByTestId('git-webhook-url-close-confirm'))
    await waitFor(() => {
      expect(screen.queryByTestId('git-webhook-url-dialog')).not.toBeInTheDocument()
    })

    await openRegisterDialog(user)
    await submitRegisterForm(user)
    const secondDialog = await screen.findByTestId('git-webhook-url-dialog')
    const secondUrlText = within(secondDialog).getByText(/\/api\/v1\/webhooks\/git\//).textContent

    expect(secondUrlText).not.toBeNull()
    expect(secondUrlText).not.toBe(firstUrlText)
    if (firstUrlText !== null) {
      expect(screen.queryByText(firstUrlText)).not.toBeInTheDocument()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR14 — 삭제 확인 문구 + 3분기(204/404/403) + isPending disabled
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookSection — 삭제 확인 문구 (FR14)', () => {
  it('삭제 확인 모달이 "연동이 끊기고 복구할 수 없습니다" + "provider 설정의 URL 도 함께 교체" 두 가지를 말한다', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()
    const user = userEvent.setup({ delay: null })

    renderSection()
    await screen.findByTestId(`git-webhook-row-${target.id}`)
    await user.click(screen.getByTestId(`git-webhook-delete-${target.id}`))

    expect(await screen.findByText(/연동이 끊기고 복구할 수 없습니다/)).toBeInTheDocument()
    expect(screen.getByText(/provider 설정의 URL 도 함께 교체/)).toBeInTheDocument()
  })
})

describe('GitWebhookSection — 삭제 3분기', () => {
  it('204 후 목록을 invalidate 해 행이 사라진다 (EC19)', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()
    const user = userEvent.setup({ delay: null })

    renderSection()
    await screen.findByTestId(`git-webhook-row-${target.id}`)
    await user.click(screen.getByTestId(`git-webhook-delete-${target.id}`))
    await user.click(await screen.findByTestId(`git-webhook-delete-confirm-${target.id}`))

    await waitFor(() => {
      expect(screen.queryByTestId(`git-webhook-row-${target.id}`)).not.toBeInTheDocument()
    })
  })

  it('삭제 404 는 토스트 없이 invalidate 로 조용히 사라진다 (EC10)', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()
    // 이미 다른 경로로 삭제된 상황(동시 삭제)을 재현 — store도 같이 비워 GET refetch가 빈 목록을 반환한다.
    server.use(
      http.delete('/api/v1/projects/:projectKey/automation/git-webhooks/:id', ({ params }) => {
        gitWebhookStore.delete(params['id'] as string)
        return new HttpResponse(null, { status: 404 })
      }),
    )
    const user = userEvent.setup({ delay: null })

    renderSection()
    await screen.findByTestId(`git-webhook-row-${target.id}`)
    await user.click(screen.getByTestId(`git-webhook-delete-${target.id}`))
    await user.click(await screen.findByTestId(`git-webhook-delete-confirm-${target.id}`))

    await waitFor(() => {
      expect(screen.queryByTestId(`git-webhook-row-${target.id}`)).not.toBeInTheDocument()
    })
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('삭제 403 은 에러 토스트 + 목록 유지(낙관적 제거 없음) (EC11)', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()
    server.use(
      http.delete('/api/v1/projects/:projectKey/automation/git-webhooks/:id', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    const user = userEvent.setup({ delay: null })

    renderSection()
    await screen.findByTestId(`git-webhook-row-${target.id}`)
    await user.click(screen.getByTestId(`git-webhook-delete-${target.id}`))
    await user.click(await screen.findByTestId(`git-webhook-delete-confirm-${target.id}`))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith('삭제에 실패했습니다.')
    })
    expect(screen.getByTestId(`git-webhook-row-${target.id}`)).toBeInTheDocument()
  })

  it('isPending 이면 삭제 확인/취소가 disabled 다 (EC12)', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()
    server.use(
      http.delete('/api/v1/projects/:projectKey/automation/git-webhooks/:id', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup({ delay: null })

    renderSection()
    await screen.findByTestId(`git-webhook-row-${target.id}`)
    await user.click(screen.getByTestId(`git-webhook-delete-${target.id}`))
    const confirmButton = await screen.findByTestId(`git-webhook-delete-confirm-${target.id}`)
    await user.click(confirmButton)

    await waitFor(() => {
      expect(confirmButton).toBeDisabled()
    })
    expect(screen.getByTestId('git-webhook-delete-cancel')).toBeDisabled()

    await settlePendingMutations()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR11 — 재발급 부재 도움말은 목록 상태와 무관하게 항상 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookSection — 재발급 부재 도움말 (FR11)', () => {
  const HELP_TEXT = /재발급할 수 없습니다/

  it('loading 상태에서도 도움말이 렌더된다', () => {
    renderSection()
    expect(screen.getByText(HELP_TEXT)).toBeInTheDocument()
  })

  it('403 에러 상태에서도 도움말이 렌더된다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/git-webhooks', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('권한이 없습니다.')).toBeInTheDocument()
    })
    expect(screen.getByText(HELP_TEXT)).toBeInTheDocument()
  })

  it('empty 상태에서도 도움말이 렌더된다', async () => {
    renderSection()

    await waitFor(() => {
      expect(screen.getByText('등록된 Git 웹훅이 없습니다.')).toBeInTheDocument()
    })
    expect(screen.getByText(HELP_TEXT)).toBeInTheDocument()
  })

  it('list 상태에서도 도움말이 렌더된다', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    renderSection()

    await screen.findByTestId(`git-webhook-row-${firstSeedWebhook().id}`)
    expect(screen.getByText(HELP_TEXT)).toBeInTheDocument()
  })

  it('도움말은 삭제 후 재등록 + provider 설정 갱신을 대안으로 안내한다', () => {
    renderSection()
    const help = screen.getByText(HELP_TEXT)
    expect(help.textContent).toMatch(/삭제/)
    expect(help.textContent).toMatch(/다시 등록/)
  })
})
