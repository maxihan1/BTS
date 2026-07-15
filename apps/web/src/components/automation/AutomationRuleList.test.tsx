// AutomationRuleList 단위 테스트 — 목록·트리거/enabled 배지·토글·삭제확인·빈상태·편집위임 (FR-AT-01 D6 Task 5)
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach, afterAll } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { AutomationRuleList } from './AutomationRuleList'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_PROJECT_KEY,
  DEFAULT_AUTOMATION_RULES,
  resetAutomationRuleStore,
  seedAutomationRules,
} from '@/mocks/automation-rule-fixtures'
import { AUTOMATION_RULES_QUERY_KEY } from '@/api/useAutomationRules'
import type { PatchAutomationRuleInput } from '@/api/automation-rules.types'

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — useAutomationRules.test.tsx와 동형(전역 handlers.ts 미등록, 로컬 서버로 격리)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...automationRuleHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  server.resetHandlers()
  resetAutomationRuleStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})
afterAll(() => server.close())

const PROJECT_KEY = DEFAULT_AUTOMATION_PROJECT_KEY

/** DEFAULT_AUTOMATION_RULES[0] — noUncheckedIndexedAccess 가드 헬퍼 (ISSUE_CREATED, enabled) */
function issueCreatedRule() {
  const rule = DEFAULT_AUTOMATION_RULES[0]
  if (rule === undefined) throw new Error('fixture DEFAULT_AUTOMATION_RULES[0]이 비어있음')
  return rule
}

/** DEFAULT_AUTOMATION_RULES[1] — noUncheckedIndexedAccess 가드 헬퍼 (SCHEDULED, nextFireAt 있음) */
function scheduledRule() {
  const rule = DEFAULT_AUTOMATION_RULES[1]
  if (rule === undefined) throw new Error('fixture DEFAULT_AUTOMATION_RULES[1]이 비어있음')
  return rule
}

function renderList(
  onAddRule = vi.fn(),
  onEditRule = vi.fn(),
  onViewHistory = vi.fn(),
  onExportYaml = vi.fn(),
  onImportYaml = vi.fn(),
  isExportingYaml = false,
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AutomationRuleList
        projectKey={PROJECT_KEY}
        onAddRule={onAddRule}
        onEditRule={onEditRule}
        onViewHistory={onViewHistory}
        onExportYaml={onExportYaml}
        onImportYaml={onImportYaml}
        isExportingYaml={isExportingYaml}
      />
    </QueryClientProvider>,
  )
  return { ...utils, queryClient }
}

describe('AutomationRuleList', () => {
  it('로딩 중에는 상태 표시가 렌더된다', () => {
    renderList()
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('룰 목록 — 이름·트리거 배지·enabled 배지가 렌더되고, SCHEDULED만 nextFireAt을 표시한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const created = issueCreatedRule()
    const scheduled = scheduledRule()

    renderList()

    await waitFor(() => {
      expect(screen.getByText(created.name)).toBeInTheDocument()
    })

    const createdRow = screen.getByText(created.name).closest('li')
    const scheduledRow = screen.getByText(scheduled.name).closest('li')
    if (createdRow === null || scheduledRow === null) {
      throw new Error('행 요소를 찾지 못함')
    }

    // ISSUE_CREATED 행 — 트리거 배지 "생성" + enabled 배지 "활성", nextFireAt 없음
    expect(within(createdRow).getByText('생성')).toBeInTheDocument()
    expect(within(createdRow).getByText('활성')).toBeInTheDocument()
    expect(within(createdRow).queryByText(/다음 실행/)).not.toBeInTheDocument()

    // SCHEDULED 행 — 트리거 배지 "스케줄" + nextFireAt 표시
    expect(within(scheduledRow).getByText('스케줄')).toBeInTheDocument()
    expect(within(scheduledRow).getByText(/다음 실행/)).toBeInTheDocument()
  })

  it('액션 타입 배지 — 액션이 있는 룰은 타입별 배지를 순서대로, 없는 룰은 "액션 없음"을 표시한다 (FR11)', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const created = issueCreatedRule() // actions: []
    const scheduled = scheduledRule() // actions: [SET_FIELD(priority), ASSIGN(해제)]

    renderList()

    await waitFor(() => {
      expect(screen.getByText(created.name)).toBeInTheDocument()
    })

    const createdRow = screen.getByText(created.name).closest('li')
    const scheduledRow = screen.getByText(scheduled.name).closest('li')
    if (createdRow === null || scheduledRow === null) {
      throw new Error('행 요소를 찾지 못함')
    }

    // 액션 없는 룰 — 배지 대신 "액션 없음" 텍스트
    expect(within(createdRow).getByText('액션 없음')).toBeInTheDocument()

    // 액션 있는 룰 — 타입별 한국어 배지가 순서대로(SET_FIELD→ASSIGN) 렌더, 접근성 그룹 라벨 포함
    const actionsGroup = within(scheduledRow).getByRole('group', { name: '액션: 필드 변경, 담당자' })
    expect(within(actionsGroup).getByText('필드 변경')).toBeInTheDocument()
    expect(within(actionsGroup).getByText('담당자')).toBeInTheDocument()
  })

  it('빈 상태 — CTA 버튼 클릭 시 onAddRule이 호출된다', async () => {
    const user = userEvent.setup()
    const onAddRule = vi.fn()
    renderList(onAddRule)

    await waitFor(() => {
      expect(screen.getByText('아직 자동화 룰이 없습니다.')).toBeInTheDocument()
    })

    await user.click(screen.getByTestId('automation-rule-add-button'))
    expect(onAddRule).toHaveBeenCalledTimes(1)
  })

  it('토글 클릭 시 현재 version을 동봉해 PATCH 요청을 보낸다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = issueCreatedRule()
    const user = userEvent.setup()

    let capturedBody: PatchAutomationRuleInput | undefined
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', async ({ request }) => {
        capturedBody = (await request.json()) as PatchAutomationRuleInput
        return HttpResponse.json({ ...target, enabled: false, version: target.version + 1 })
      }),
    )

    renderList()

    await waitFor(() => screen.getByText(target.name))
    await user.click(screen.getByTestId(`automation-rule-toggle-${target.id}`))

    await waitFor(() => {
      expect(capturedBody).toEqual({ version: target.version, enabled: false })
    })
  })

  it('토글이 409(버전 충돌)로 실패하면 목록 쿼리를 invalidate하고 실패 토스트를 표시한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = issueCreatedRule()
    const user = userEvent.setup()

    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_RULE_VERSION_CONFLICT' }, { status: 409 }),
      ),
    )

    const { queryClient } = renderList()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await waitFor(() => screen.getByText(target.name))
    await user.click(screen.getByTestId(`automation-rule-toggle-${target.id}`))

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY) })
    })
    expect(toast.error).toHaveBeenCalledWith('변경에 실패했습니다.')
  })

  it('삭제 확인 흐름 — 삭제 → 확인 모달 → 확인 클릭 시 delete mutation이 호출되어 목록에서 사라진다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = issueCreatedRule()
    const user = userEvent.setup()

    renderList()

    await waitFor(() => screen.getByText(target.name))
    await user.click(screen.getByTestId(`automation-rule-delete-${target.id}`))

    const confirmButton = await screen.findByTestId(`automation-rule-delete-confirm-${target.id}`)
    await user.click(confirmButton)

    await waitFor(() => {
      expect(screen.queryByText(target.name)).not.toBeInTheDocument()
    })
  })

  it('삭제 취소 — 확인 모달에서 취소를 누르면 삭제되지 않는다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = issueCreatedRule()
    const user = userEvent.setup()

    renderList()

    await waitFor(() => screen.getByText(target.name))
    await user.click(screen.getByTestId(`automation-rule-delete-${target.id}`))

    const cancelButton = await screen.findByTestId('automation-rule-delete-cancel')
    await user.click(cancelButton)

    await waitFor(() => {
      expect(screen.queryByTestId('automation-rule-delete-cancel')).not.toBeInTheDocument()
    })
    expect(screen.getByText(target.name)).toBeInTheDocument()
  })

  it('편집 버튼 클릭 시 onEditRule(rule)이 호출된다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = issueCreatedRule()
    const onEditRule = vi.fn()
    const user = userEvent.setup()

    renderList(vi.fn(), onEditRule)

    await waitFor(() => screen.getByText(target.name))
    await user.click(screen.getByTestId(`automation-rule-edit-${target.id}`))

    expect(onEditRule).toHaveBeenCalledTimes(1)
    expect(onEditRule).toHaveBeenCalledWith(expect.objectContaining({ id: target.id }))
  })

  it('이력 버튼 클릭 시 onViewHistory(rule)이 호출된다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = issueCreatedRule()
    const onViewHistory = vi.fn()
    const user = userEvent.setup()

    renderList(vi.fn(), vi.fn(), onViewHistory)

    await waitFor(() => screen.getByText(target.name))
    await user.click(screen.getByRole('button', { name: `${target.name} 실행 이력` }))

    expect(onViewHistory).toHaveBeenCalledTimes(1)
    expect(onViewHistory).toHaveBeenCalledWith(expect.objectContaining({ id: target.id }))
  })

  it('403 응답 시 권한 없음 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 }),
      ),
    )

    renderList()

    await waitFor(() => {
      expect(screen.getByText('권한이 없습니다.')).toBeInTheDocument()
    })
  })

  it('기타 에러 응답 시 일반 에러 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules', () =>
        HttpResponse.json({ errorCode: 'UNKNOWN' }, { status: 500 }),
      ),
    )

    renderList()

    await waitFor(() => {
      expect(screen.getByText('자동화 룰을 불러오지 못했습니다.')).toBeInTheDocument()
    })
  })

  it('YAML 내보내기/가져오기 버튼을 렌더하고 클릭 시 콜백을 호출한다', async () => {
    const onExportYaml = vi.fn()
    const onImportYaml = vi.fn()
    const user = userEvent.setup()
    renderList(vi.fn(), vi.fn(), vi.fn(), onExportYaml, onImportYaml)

    await user.click(screen.getByTestId('automation-yaml-export-button'))
    await user.click(screen.getByTestId('automation-yaml-import-button'))
    expect(onExportYaml).toHaveBeenCalledOnce()
    expect(onImportYaml).toHaveBeenCalledOnce()
  })

  it('내보내기 진행 중이면 버튼이 disabled + "내보내는 중..." 이다 (EC6)', () => {
    renderList(vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn(), true)
    const button = screen.getByTestId('automation-yaml-export-button')
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('내보내는 중...')
  })
})
