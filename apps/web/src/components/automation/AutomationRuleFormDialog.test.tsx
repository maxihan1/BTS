// AutomationRuleFormDialog 단위 테스트 — 트리거 조건부 필드·cron 사전검증·직렬화 전달·수정모드 로드·웹훅 토큰 콜백·key 재마운트·액션/실행주체 배선 (FR-AT-01 D6 Task 6, FR-AT-02 D6 Task 6)
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach, afterAll } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { JSX, ReactNode } from 'react'
import { toast } from 'sonner'
import { AutomationRuleFormDialog } from './AutomationRuleFormDialog'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_ACTOR_ID,
  DEFAULT_AUTOMATION_PROJECT_KEY,
  resetAutomationRuleStore,
} from '@/mocks/automation-rule-fixtures'
import { projectMemberHandlers } from '@/mocks/project-member-handlers'
import { AUTOMATION_RULES_QUERY_KEY } from '@/api/useAutomationRules'
import type {
  AutomationRule,
  CreateAutomationRuleInput,
  PatchAutomationRuleInput,
  RuleConflict,
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

// 액션/실행 주체 피커는 use-project-members(React Query)에 의존한다 — 로컬 서버에
// projectMemberHandlers를 함께 등록해 이 파일의 모든 테스트에서 담당자 목록 GET이 항상 핸들된다
// (핵심 함정 — ActionListEditor/ProjectMemberSelect가 폼에 상시 렌더되므로 매 테스트가 대상).
const server = setupServer(...automationRuleHandlers, ...projectMemberHandlers)

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
/** ATLAS 프로젝트 멤버 fixture(project-member-fixtures.ts atlasInitialMembers)의 앨리스 userId */
const ALICE_ID = DEFAULT_AUTOMATION_ACTOR_ID

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
  condition: null,
  actions: [],
  actorUserId: ALICE_ID,
  hasWebhookToken: false,
  nextFireAt: '2026-07-15T09:00:00Z',
  createdBy: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  createdAt: '2026-07-10T10:00:00Z',
  updatedAt: '2026-07-10T10:00:00Z',
  version: 3,
}

/** 조건(`issue.priority > 3`)이 설정된 편집용 픽스처 — S4 로드/G1 조건부 전송 테스트 전용 */
const CONDITION_EDIT_RULE: AutomationRule = {
  ...SCHEDULED_EDIT_RULE,
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567892',
  name: '조건 있는 룰',
  condition: '{"and":[{">":[{"var":"issue.priority"},3]}]}',
}

/** 액션 2건(SET_FIELD·ADD_COMMENT) + actor가 설정된 편집용 픽스처 — S7 로드/직렬화 round-trip 테스트 전용 */
const ACTIONS_EDIT_RULE: AutomationRule = {
  ...SCHEDULED_EDIT_RULE,
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567891',
  name: '액션 있는 룰',
  actions: [
    { type: 'SET_FIELD', config: { field: 'priority', value: 3 } },
    { type: 'ADD_COMMENT', config: { body: '자동 처리됨' } },
  ],
  actorUserId: ALICE_ID,
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
// 생성 시 액션·실행 주체가 POST 바디에 반영(FR1·FR8·FR9, S1·S6) — FR-AT-02 D6 Task 6
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 생성 시 액션/실행 주체 POST 반영', () => {
  it('액션 추가 + 실행 주체 선택 후 저장하면 POST body에 actions·actorUserId가 직렬화되어 포함된다(EC9 숫자 강제)', async () => {
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
    await user.type(screen.getByLabelText('이름'), '액션 있는 룰')

    await user.click(screen.getByRole('button', { name: '액션 추가' }))
    const row = screen.getAllByRole('listitem')[0]
    if (row === undefined) {
      throw new Error('액션 행이 렌더되지 않음')
    }
    await user.selectOptions(within(row).getByLabelText('필드'), 'priority')
    await user.selectOptions(within(row).getByLabelText('값'), '3')

    const actorOption = await screen.findByRole('option', { name: '앨리스' })
    await user.selectOptions(screen.getByLabelText('실행 주체'), actorOption)

    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.actions).toEqual([
      { type: 'SET_FIELD', config: JSON.stringify({ field: 'priority', value: 3 }) },
    ])
    expect(capturedBody.actorUserId).toBe(ALICE_ID)
  })

  it('실행 주체를 선택하지 않으면 POST body에 actorUserId가 포함되지 않는다(FR8 기본값=미설정)', async () => {
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
    await user.type(screen.getByLabelText('이름'), '기본 실행주체 룰')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.actions).toEqual([])
    expect(capturedBody.actorUserId).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 다중 액션 순서변경이 저장 순서에 반영(S5, FR7) — FR-AT-02 D6 Task 6
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 다중 액션 순서변경 반영', () => {
  it('두 액션의 순서를 위로 이동한 뒤 저장하면 POST body actions 순서가 반영된다', async () => {
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
    await user.type(screen.getByLabelText('이름'), '순서변경 룰')

    const addActionButton = () => screen.getByRole('button', { name: '액션 추가' })
    await user.click(addActionButton())
    await user.click(addActionButton())

    const rowsBeforeEdit = screen.getAllByRole('listitem')
    const firstRow = rowsBeforeEdit[0]
    const secondRow = rowsBeforeEdit[1]
    if (firstRow === undefined || secondRow === undefined) {
      throw new Error('액션 행이 2개 렌더되지 않음')
    }
    await user.type(within(firstRow).getByLabelText('값'), 'first')
    await user.type(within(secondRow).getByLabelText('값'), 'second')

    const rowsAfterEdit = screen.getAllByRole('listitem')
    const secondRowAfterEdit = rowsAfterEdit[1]
    if (secondRowAfterEdit === undefined) {
      throw new Error('두 번째 액션 행이 렌더되지 않음')
    }
    await user.click(within(secondRowAfterEdit).getByRole('button', { name: '위로' }))

    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.actions).toEqual([
      { type: 'SET_FIELD', config: JSON.stringify({ field: 'summary', value: 'second' }) },
      { type: 'SET_FIELD', config: JSON.stringify({ field: 'summary', value: 'first' }) },
    ])
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
// 편집 모드 — 액션·실행 주체 로드(S7, parseActionConfig) 및 저장 시 재직렬화(FR9) — FR-AT-02 D6 Task 6
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 편집 모드 액션/실행 주체 로드', () => {
  it('editingRule.actions(응답 객체 config)를 각 행 타입·값으로 로드하고 actorUserId를 실행 주체 select에 로드한다(S7)', async () => {
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={ACTIONS_EDIT_RULE}
      />,
    )

    const rows = screen.getAllByRole('listitem')
    const firstRow = rows[0]
    const secondRow = rows[1]
    if (firstRow === undefined || secondRow === undefined) {
      throw new Error('액션 행이 2개 렌더되지 않음')
    }

    expect(within(firstRow).getByLabelText('액션 유형')).toHaveValue('SET_FIELD')
    expect(within(firstRow).getByLabelText('필드')).toHaveValue('priority')
    expect(within(firstRow).getByLabelText('값')).toHaveValue('3')

    expect(within(secondRow).getByLabelText('액션 유형')).toHaveValue('ADD_COMMENT')
    expect(within(secondRow).getByLabelText('댓글 본문')).toHaveValue('자동 처리됨')

    await waitFor(() => {
      expect(screen.getByLabelText('실행 주체')).toHaveValue(ALICE_ID)
    })
  })

  it('편집 저장 시 로드된 actions·actorUserId를 그대로 재직렬화해 PATCH body에 담는다(round-trip)', async () => {
    const capturedBodies: PatchAutomationRuleInput[] = []
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', async ({ request }) => {
        const body = (await request.json()) as PatchAutomationRuleInput
        capturedBodies.push(body)
        return HttpResponse.json({
          ...ACTIONS_EDIT_RULE,
          ...body,
          version: ACTIONS_EDIT_RULE.version + 1,
        })
      }),
    )
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={ACTIONS_EDIT_RULE}
      />,
    )

    await waitFor(() => {
      expect(screen.getByLabelText('실행 주체')).toHaveValue(ALICE_ID)
    })

    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.actions).toEqual([
      { type: 'SET_FIELD', config: JSON.stringify({ field: 'priority', value: 3 }) },
      { type: 'ADD_COMMENT', config: JSON.stringify({ body: '자동 처리됨' }) },
    ])
    expect(capturedBody.actorUserId).toBe(ALICE_ID)
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
// 규칙 충돌 콜백 — onWebhookToken과 대칭 패턴(FR-AT-04 D6/D7 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 규칙 충돌 콜백', () => {
  it('생성 저장 성공 응답(rule.conflicts)에 1건 이상 있으면 onConflicts가 그 배열로 호출된다', async () => {
    const conflicts: RuleConflict[] = [
      {
        type: 'CYCLE',
        severity: 'WARNING',
        ruleIds: ['a1b2c3d4-e5f6-4890-abcd-ef1234567890'],
        detail: '순환 감지',
      },
    ]
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', async ({ request }) => {
        const body = (await request.json()) as CreateAutomationRuleInput
        return HttpResponse.json(
          { rule: { ...SCHEDULED_EDIT_RULE, ...body, conflicts }, webhookToken: null },
          { status: 201 },
        )
      }),
    )
    const onConflicts = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        onConflicts={onConflicts}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '충돌 있는 룰')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(onConflicts).toHaveBeenCalledWith(conflicts)
    })
  })

  it('생성 저장 성공 응답 rule.conflicts가 빈 배열이면 onConflicts가 호출되지 않는다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', async ({ request }) => {
        const body = (await request.json()) as CreateAutomationRuleInput
        return HttpResponse.json(
          { rule: { ...SCHEDULED_EDIT_RULE, ...body, conflicts: [] }, webhookToken: null },
          { status: 201 },
        )
      }),
    )
    const onConflicts = vi.fn()
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        onConflicts={onConflicts}
      />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '충돌 없는 룰')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(onConflicts).not.toHaveBeenCalled()
  })

  it('수정 저장 성공 응답(최상위 conflicts)에 1건 이상 있으면 onConflicts가 그 배열로 호출된다', async () => {
    const conflicts: RuleConflict[] = [
      {
        type: 'FIELD_CONFLICT',
        severity: 'WARNING',
        ruleIds: ['a1b2c3d4-e5f6-4890-abcd-ef1234567891'],
        detail: '필드 충돌',
      },
    ]
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', async ({ request }) => {
        const body = (await request.json()) as PatchAutomationRuleInput
        return HttpResponse.json({
          ...SCHEDULED_EDIT_RULE,
          ...body,
          version: SCHEDULED_EDIT_RULE.version + 1,
          conflicts,
        })
      }),
    )
    const onConflicts = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={SCHEDULED_EDIT_RULE}
        onConflicts={onConflicts}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(onConflicts).toHaveBeenCalledWith(conflicts)
    })
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

  it('open을 유지한 채 editingRule이 바뀌면 액션 목록/실행 주체도 새 editingRule 값으로 초기화된다(EC6)', async () => {
    const { rerender } = renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} editingRule={null} />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '액션 추가' }))
    expect(screen.getAllByRole('listitem')).toHaveLength(1)

    rerender(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={ACTIONS_EDIT_RULE}
      />,
    )

    await waitFor(() => {
      expect(screen.getAllByRole('listitem')).toHaveLength(ACTIONS_EDIT_RULE.actions.length)
    })
    await waitFor(() => {
      expect(screen.getByLabelText('실행 주체')).toHaveValue(ALICE_ID)
    })
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
// 조건 편집 — 로드(S4)·조건부 전송(G1)·제거(S5/[D1])·null 유지(EC11)·오류(FR9)·409(EC8)·
// key 재마운트(EC9) — FR-AT-03 D6/D7 Task 5
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationRuleFormDialog — 조건 편집(FR-AT-03 D6/D7 Task 5)', () => {
  it('editingRule.condition을 조건 빌더에 로드한다(S4)', () => {
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={CONDITION_EDIT_RULE}
      />,
    )
    expect(screen.getByTestId('condition-field-select')).toHaveValue('issue.priority')
    expect(screen.getByTestId('condition-operator-select')).toHaveValue('GREATER_THAN')
    expect(screen.getByLabelText('값')).toHaveValue('3')
  })

  it('생성 모드에서 조건을 만들지 않고 저장하면 POST body에 condition이 생략된다(EC11/G1-create)', async () => {
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
    await user.type(screen.getByLabelText('이름'), '조건 없는 룰')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.condition).toBeUndefined()
  })

  it('조건 추가 후 저장하면 POST body에 condition이 직렬화되어 포함된다', async () => {
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
    await user.type(screen.getByLabelText('이름'), '조건 있는 룰')
    await user.click(screen.getByTestId('condition-add-comparison'))
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.condition).toBe(JSON.stringify({ and: [{ '==': [{ var: 'issue.key' }, ''] }] }))
  })

  it('조건이 있던 룰에서 조건을 전부 제거하고 저장하면 PATCH body에 빈 조건 정규형이 전송된다(S5/[D1]/G1-b)', async () => {
    const capturedBodies: PatchAutomationRuleInput[] = []
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', async ({ request }) => {
        const body = (await request.json()) as PatchAutomationRuleInput
        capturedBodies.push(body)
        return HttpResponse.json({
          ...CONDITION_EDIT_RULE,
          ...body,
          version: CONDITION_EDIT_RULE.version + 1,
        })
      }),
    )
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={CONDITION_EDIT_RULE}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '조건 삭제' }))
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.condition).toBe('{"and":[]}')
  })

  it('condition이 null인 룰에서 조건을 건드리지 않고 이름만 변경해 저장하면 PATCH body에 condition이 생략된다(EC11/G1-c)', async () => {
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
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={SCHEDULED_EDIT_RULE}
      />,
    )
    const user = userEvent.setup()
    const nameInput = screen.getByLabelText('이름')
    await user.clear(nameInput)
    await user.type(nameInput, '이름만 변경')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => expect(capturedBodies).toHaveLength(1))
    const capturedBody = capturedBodies[0]
    if (capturedBody === undefined) {
      throw new Error('capturedBodies[0]이 캡처되지 않음')
    }
    expect(capturedBody.condition).toBeUndefined()
  })

  it('저장이 INVALID_CONDITION_EXPRESSION(400)로 실패하면 한국어 메시지가 표시되고 폼이 유지된다(FR9)', async () => {
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', () =>
        HttpResponse.json({ errorCode: 'INVALID_CONDITION_EXPRESSION', detail: '조건 오류' }, { status: 400 }),
      ),
    )
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={onOpenChange} />,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이름'), '조건 오류 룰')
    await user.click(screen.getByTestId('condition-add-comparison'))
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(
        screen.getByText('조건 표현식이 올바르지 않습니다. 필드/연산자/값을 확인해주세요.'),
      ).toBeInTheDocument()
    })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('조건이 있는 룰의 수정 저장이 409로 실패해도 기존 handleSubmitFailure 흐름과 동일하게 처리된다(EC8)', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_RULE_VERSION_CONFLICT', detail: '버전 충돌' }, { status: 409 }),
      ),
    )
    const onOpenChange = vi.fn()
    renderWithClient(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={onOpenChange}
        editingRule={CONDITION_EDIT_RULE}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('automation-rule-save-button'))

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
    expect(toast.error).toHaveBeenCalledWith(
      '다른 곳에서 먼저 변경되었습니다. 최신 정보로 다시 열어 시도해주세요.',
    )
  })

  it('open을 유지한 채 editingRule이 바뀌면 조건 상태도 새 editingRule 값으로 초기화된다(EC9)', async () => {
    const { rerender } = renderWithClient(
      <AutomationRuleFormDialog projectKey={PROJECT_KEY} open onOpenChange={vi.fn()} editingRule={null} />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByTestId('condition-add-comparison'))
    expect(screen.getByTestId('condition-field-select')).toHaveValue('issue.key')

    rerender(
      <AutomationRuleFormDialog
        projectKey={PROJECT_KEY}
        open
        onOpenChange={vi.fn()}
        editingRule={CONDITION_EDIT_RULE}
      />,
    )

    await waitFor(() => {
      expect(screen.getByTestId('condition-field-select')).toHaveValue('issue.priority')
    })
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
