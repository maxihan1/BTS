// AutomationRuleFormDialog 단위 테스트 — 트리거 조건부 필드·cron 사전검증·직렬화 전달·수정모드 로드·웹훅 토큰 콜백·key 재마운트 (FR-AT-01 D6 Task 6)
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach, afterAll } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { JSX, ReactNode } from 'react'
import { toast } from 'sonner'
import { AutomationRuleFormDialog } from './AutomationRuleFormDialog'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import { DEFAULT_AUTOMATION_PROJECT_KEY, resetAutomationRuleStore } from '@/mocks/automation-rule-fixtures'
import { AUTOMATION_RULES_QUERY_KEY } from '@/api/useAutomationRules'
import type {
  AutomationRule,
  CreateAutomationRuleInput,
  PatchAutomationRuleInput,
} from '@/api/automation-rules.types'

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — useAutomationRules.test.tsx / automation-rules.test.ts와 동형
// (전역 handlers.ts에 automationRuleHandlers 미등록, 로컬 서버로 격리)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...automationRuleHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  server.resetHandlers()
  resetAutomationRuleStore()
  vi.clearAllMocks()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})
afterAll(() => server.close())

const PROJECT_KEY = DEFAULT_AUTOMATION_PROJECT_KEY

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const SCHEDULED_EDIT_RULE: AutomationRule = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: PROJECT_KEY,
  name: '매일 오전 스캔',
  enabled: true,
  triggerType: 'SCHEDULED',
  triggerConfig: '{"cron":"0 0 9 * * *"}',
  hasWebhookToken: false,
  nextFireAt: '2026-07-15T09:00:00Z',
  createdBy: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  createdAt: '2026-07-10T10:00:00Z',
  updatedAt: '2026-07-10T10:00:00Z',
  version: 3,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderWithClient(ui: JSX.Element) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(ui, {
    wrapper: ({ children }: { readonly children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 트리거별 조건부 필드 전환
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 트리거별 조건부 필드', () => {
  it('기본(ISSUE_CREATED)에서는 cron/fields 입력이 없다', () => {
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />,
    )
    expect(screen.queryByTestId('automation-rule-cron-input')).not.toBeInTheDocument()
    expect(screen.queryByTestId('automation-rule-fields-input')).not.toBeInTheDocument()
  })

  it('SCHEDULED 선택 시 cron 입력과 형식 힌트가 나타난다', async () => {
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />,
    )
    const user = userEvent.setup()
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'SCHEDULED')

    expect(screen.getByTestId('automation-rule-cron-input')).toBeInTheDocument()
    expect(screen.getByText(/6필드 cron/)).toBeInTheDocument()
    expect(screen.queryByTestId('automation-rule-fields-input')).not.toBeInTheDocument()
  })

  it('ISSUE_UPDATED 선택 시 fields 입력이 나타난다', async () => {
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />,
    )
    const user = userEvent.setup()
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'ISSUE_UPDATED')

    expect(screen.getByTestId('automation-rule-fields-input')).toBeInTheDocument()
    expect(screen.queryByTestId('automation-rule-cron-input')).not.toBeInTheDocument()
  })

  it('SCHEDULED에서 ISSUE_CREATED로 되돌리면 cron 입력이 사라진다', async () => {
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />,
    )
    const user = userEvent.setup()
    const select = screen.getByTestId('automation-rule-trigger-select')

    await user.selectOptions(select, 'SCHEDULED')
    expect(screen.getByTestId('automation-rule-cron-input')).toBeInTheDocument()

    await user.selectOptions(select, 'ISSUE_CREATED')
    expect(screen.queryByTestId('automation-rule-cron-input')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// cron 빈값 프론트 사전검증
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — cron 사전검증', () => {
  it('SCHEDULED + cron 빈값 저장 시 인라인 에러가 표시되고 onOpenChange가 호출되지 않는다', async () => {
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={onOpenChange} />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '스케줄 룰')
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'SCHEDULED')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(screen.getByText('cron 표현식을 입력해주세요.')).toBeInTheDocument()
    })
    expect(onOpenChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 저장 시 triggerConfig 직렬화 결과가 mutation input으로 전달
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 생성 시 triggerConfig 직렬화 전달', () => {
  it('SCHEDULED 생성: POST body의 triggerConfig가 {"cron":"..."}로 직렬화된다', async () => {
    const capturedBodies: CreateAutomationRuleInput[] = []
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', async ({ request }) => {
        const body = (await request.json()) as CreateAutomationRuleInput
        capturedBodies.push(body)
        return HttpResponse.json({ rule: { ...SCHEDULED_EDIT_RULE, ...body }, webhookToken: null }, { status: 201 })
      }),
    )
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={onOpenChange} />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '스케줄 룰')
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'SCHEDULED')
    await user.type(screen.getByTestId('automation-rule-cron-input'), '0 0 9 * * *')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.triggerConfig).toBe(JSON.stringify({ cron: '0 0 9 * * *' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('ISSUE_UPDATED 생성: fields 태그 추가 후 POST body triggerConfig가 {"fields":[...]}로 직렬화된다', async () => {
    const capturedBodies: CreateAutomationRuleInput[] = []
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', async ({ request }) => {
        const body = (await request.json()) as CreateAutomationRuleInput
        capturedBodies.push(body)
        return HttpResponse.json({ rule: { ...SCHEDULED_EDIT_RULE, ...body }, webhookToken: null }, { status: 201 })
      }),
    )
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '필드변경 룰')
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'ISSUE_UPDATED')
    const fieldsInput = screen.getByTestId('automation-rule-fields-input')
    await user.type(fieldsInput, 'status{Enter}')
    await user.type(fieldsInput, 'assignee{Enter}')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.triggerConfig).toBe(JSON.stringify({ fields: ['status', 'assignee'] }))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 수정 모드 — editingRule 초기값 로드 + version 동봉
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 수정 모드', () => {
  it('editingRule 초기값을 로드하고(이름·cron) 트리거 셀렉트는 잠금된다', () => {
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={SCHEDULED_EDIT_RULE}
      />,
    )
    expect(screen.getByLabelText('이름')).toHaveValue(SCHEDULED_EDIT_RULE.name)
    expect(screen.getByTestId('automation-rule-cron-input')).toHaveValue('0 0 9 * * *')
    expect(screen.getByTestId('automation-rule-trigger-select')).toBeDisabled()
  })

  it('수정 저장 시 PATCH body에 version이 동봉되고 triggerConfig가 갱신된다', async () => {
    const capturedBodies: PatchAutomationRuleInput[] = []
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', async ({ request }) => {
        const body = (await request.json()) as PatchAutomationRuleInput
        capturedBodies.push(body)
        return HttpResponse.json({
          ...SCHEDULED_EDIT_RULE,
          ...body,
          version: SCHEDULED_EDIT_RULE.version + 1,
        })
      }),
    )
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingRule={SCHEDULED_EDIT_RULE}
      />,
    )
    const user = userEvent.setup()
    const cronInput = screen.getByTestId('automation-rule-cron-input')
    await user.clear(cronInput)
    await user.type(cronInput, '0 30 8 * * *')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.version).toBe(SCHEDULED_EDIT_RULE.version)
    expect(capturedBody.triggerConfig).toBe(JSON.stringify({ cron: '0 30 8 * * *' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 편집 저장 시 백엔드 미지 키 보존 (코드리뷰 SUGGESTION 2)
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 편집 저장 시 백엔드 미지 키 보존', () => {
  it('editingRule.triggerConfig의 미지 키(futureKey)를 PATCH triggerConfig에 보존한다', async () => {
    const ruleWithUnknownKey: AutomationRule = {
      ...SCHEDULED_EDIT_RULE,
      triggerConfig: JSON.stringify({ cron: '0 0 9 * * *', futureKey: 'fromBackend' }),
    }
    const capturedBodies: PatchAutomationRuleInput[] = []
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', async ({ request }) => {
        const body = (await request.json()) as PatchAutomationRuleInput
        capturedBodies.push(body)
        return HttpResponse.json({
          ...ruleWithUnknownKey,
          ...body,
          version: ruleWithUnknownKey.version + 1,
        })
      }),
    )
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={ruleWithUnknownKey}
      />,
    )
    const user = userEvent.setup()
    const cronInput = screen.getByTestId('automation-rule-cron-input')
    await user.clear(cronInput)
    await user.type(cronInput, '0 30 8 * * *')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    if (capturedBody.triggerConfig === undefined) {
      throw new Error('triggerConfig가 캡처되지 않음')
    }
    expect(JSON.parse(capturedBody.triggerConfig)).toEqual({
      cron: '0 30 8 * * *',
      futureKey: 'fromBackend',
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// WEBHOOK 토큰 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — WEBHOOK 토큰 콜백', () => {
  it('WEBHOOK 트리거 생성 성공 시 onWebhookToken이 원문 토큰과 함께 호출된다', async () => {
    const onWebhookToken = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        onWebhookToken={onWebhookToken}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '웹훅 룰')
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'WEBHOOK')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(onWebhookToken).toHaveBeenCalledWith(expect.stringMatching(/^whk_/))
    })
  })

  it('비-WEBHOOK 트리거 생성 성공 시 onWebhookToken이 호출되지 않는다', async () => {
    const onWebhookToken = vi.fn()
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        onWebhookToken={onWebhookToken}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '이슈생성 룰')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(onWebhookToken).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// controlled Dialog stale state 회피 — key 재마운트
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — key 재마운트(stale state 회피)', () => {
  it('open을 유지한 채 editingRule이 바뀌면 이전 입력이 아니라 새 editingRule 값으로 초기화된다', async () => {
    const { rerender } = renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} editingRule={null} />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '임시 입력값')
    expect(screen.getByLabelText('이름')).toHaveValue('임시 입력값')

    rerender(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={SCHEDULED_EDIT_RULE}
      />,
    )

    expect(screen.getByLabelText('이름')).toHaveValue(SCHEDULED_EDIT_RULE.name)
  })

  it('open을 닫았다가 다시 열면 이전 입력값이 초기화된다', async () => {
    const { rerender } = renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '임시 입력값')
    expect(screen.getByLabelText('이름')).toHaveValue('임시 입력값')

    rerender(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open={false} onOpenChange={vi.fn()} />,
    )
    rerender(<AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} />)

    expect(screen.getByLabelText('이름')).toHaveValue('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 409(OCC 버전 충돌) — 폼 자동 닫기 + 목록 invalidate + 토스트 (/review F1)
// (스펙 §4 FR-7 · §6 E5 · §2 S4: 409는 refetch 유도. 폼을 열어둔 채 재시도하면
// stale version으로 무한 409에 빠지므로, 폼을 닫고 사용자가 fresh 데이터로 재오픈하게 한다.)
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 409 버전 충돌 시 폼 자동 닫기', () => {
  it('수정 저장이 409로 실패하면 목록 쿼리를 invalidate하고 토스트를 표시하며 폼을 닫는다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', () =>
        HttpResponse.json(
          { errorCode: 'AUTOMATION_RULE_VERSION_CONFLICT', detail: '버전 충돌' },
          { status: 409 },
        ),
      ),
    )
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const onOpenChange = vi.fn()
    render(
      <QueryClientProvider client={queryClient}>
        <AutomationRuleFormDialog
          projectKey={PROJECT_KEY}
          open
          onOpenChange={onOpenChange}
          editingRule={SCHEDULED_EDIT_RULE}
        />
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY) })
    expect(toast.error).toHaveBeenCalledWith(
      '다른 곳에서 먼저 변경되었습니다. 최신 정보로 다시 열어 시도해주세요.',
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 비-409 에러 — 폼 유지 + submitError 표시 (/review F1 분기 확인 · F2 오류코드 매핑)
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 비-409 에러는 폼을 유지한다', () => {
  it('500(AUTOMATION_INTERNAL_ERROR) 실패 시 폼이 닫히지 않고 매핑된 오류 메시지가 표시된다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_INTERNAL_ERROR' }, { status: 500 }),
      ),
    )
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingRule={SCHEDULED_EDIT_RULE}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(screen.getByText('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')).toBeInTheDocument()
    })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('401(AUTOMATION_UNAUTHENTICATED) 실패 시 재로그인 안내 메시지가 표시된다', async () => {
    // apiFetch는 401을 가로채 /auth/refresh 후 1회 retry한다(client.ts 인터셉터) — retry도
    // 같은 401을 받도록 refresh는 성공시켜 두어야 최종 ApiError(401, errorCode)까지 도달한다.
    server.use(
      http.post('/api/v1/auth/refresh', () => HttpResponse.json({ access_token: 'fresh-token' })),
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_UNAUTHENTICATED' }, { status: 401 }),
      ),
    )
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingRule={SCHEDULED_EDIT_RULE}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(screen.getByText('세션이 만료되었습니다. 다시 로그인해주세요.')).toBeInTheDocument()
    })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 취소
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 취소', () => {
  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={onOpenChange} />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-cancel-button'))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
