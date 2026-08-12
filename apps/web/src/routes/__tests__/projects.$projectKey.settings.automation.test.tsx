// 자동화 설정 페이지 라우트 단위 테스트 — RouteAdapter projectKey 전달·조립(List+FormDialog+WebhookTokenModal+RuleConflictWarningModal+RuleExecutionHistoryDialog)·웹훅 토큰 1회 노출 (FR-AT-01 D6 Task 8, 실행 이력 조립은 FR-AT-05 D6/D7 Task 8)
import { server } from '@/test/server'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { triggerBlobDownload } from '@/lib/download'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_PROJECT_KEY,
  DEFAULT_AUTOMATION_RULES,
  VALID_GITOPS_YAML,
  resetAutomationRuleStore,
  seedAutomationRules,
} from '@/mocks/automation-rule-fixtures'
import { automationExecutionHandlers } from '@/mocks/automation-execution-handlers'
import {
  DEFAULT_RULE_EXECUTIONS,
  resetAutomationExecutionStore,
  seedAutomationExecutions,
} from '@/mocks/automation-execution-fixtures'
import { gitWebhookHandlers } from '@/mocks/git-webhook-handlers'
import {
  ProjectAutomationSettingsPage,
  ProjectAutomationSettingsRouteAdapter,
} from '@/routes/projects.$projectKey.settings.automation'
import type { CreateAutomationRuleInput, RuleConflict } from '@/api/automation-rules.types'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router useParams mock — RouteAdapter 단위 테스트용 (custom-fields.test.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

const mockUseParams = vi.fn<() => { projectKey?: string }>(() => ({ projectKey: DEFAULT_AUTOMATION_PROJECT_KEY }))

vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockUseParams(),
}))

// sonner toast mock — 실제 DOM 없이 호출 여부로 검증 (settings.account-links.test.tsx 선례)
vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

// triggerBlobDownload mock — jsdom에 URL.createObjectURL이 없으므로 mock 처리
// (ExportDialog.test.tsx/issues.$key.test.tsx 선례 — vi.mock + vi.mocked)
vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — AutomationRuleList.test.tsx / useAutomationRules.test.tsx와 동형
// (전역 handlers.ts에 automationRuleHandlers 미등록, 로컬 서버로 격리)
//
// automationExecutionHandlers도 이 같은 로컬 서버 하나에 합류시킨다 — 전역 `@/test/server`(setup.ts가
// 이미 listen 중)에 별도로 server.use()하면 두 서버가 동시에 활성화된 상태에서 한쪽이 매치하는 핸들러를
// 찾아도 다른 한쪽이 독립적으로 "unhandled request" 에러를 던져(onUnhandledRequest:'error' 양쪽 설정)
// 요청이 실패한다(실측 확인) — 이 파일은 이미 로컬 서버 단일 인스턴스로 격리된 상태라 그 안에 함께
// 등록하는 편이 안전하다.
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...automationRuleHandlers, ...automationExecutionHandlers, ...gitWebhookHandlers)
})
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
  mockUseParams.mockReturnValue({ projectKey: DEFAULT_AUTOMATION_PROJECT_KEY })
})
afterEach(() => {
  resetAutomationRuleStore()
  resetAutomationExecutionStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

const PROJECT_KEY = DEFAULT_AUTOMATION_PROJECT_KEY

/** DEFAULT_AUTOMATION_RULES[0] — noUncheckedIndexedAccess 가드 헬퍼 (ISSUE_CREATED, enabled) */
function issueCreatedRule() {
  const rule = DEFAULT_AUTOMATION_RULES[0]
  if (rule === undefined) throw new Error('fixture DEFAULT_AUTOMATION_RULES[0]이 비어있음')
  return rule
}

/** 저장 응답에 실을 결정적 충돌 1건 — RuleConflictWarningModal 조립 테스트 전용 픽스처 */
const SAMPLE_CONFLICTS: RuleConflict[] = [
  {
    type: 'CYCLE',
    severity: 'WARNING',
    ruleIds: [issueCreatedRule().id],
    detail: '이 룰과 "매일 오전 스캔" 룰이 서로를 트리거하는 순환 구조입니다.',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage(projectKey = PROJECT_KEY) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const user = userEvent.setup({ delay: null })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ProjectAutomationSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
  return { user, ...utils }
}

function renderAdapter() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectAutomationSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter — projectKey 전달
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsRouteAdapter', () => {
  it('useParams의 $projectKey를 Page에 전달해 페이지 헤더가 렌더된다', async () => {
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '자동화' })).toBeInTheDocument()
    })
  })

  it('projectKey가 없으면 ProjectNotFoundScreen이 렌더된다', async () => {
    mockUseParams.mockReturnValue({ projectKey: undefined })
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument()
    })
    expect(screen.queryByRole('heading', { name: '자동화' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — projectKey 빈 문자열
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — projectKey 없음', () => {
  it('projectKey가 빈 문자열이면 ProjectNotFoundScreen이 렌더된다', () => {
    renderPage('')

    expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '자동화' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — 조립 렌더 (List)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — 조립 렌더', () => {
  it('페이지 헤더와 AutomationRuleList가 렌더된다', async () => {
    seedAutomationRules([issueCreatedRule()])
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(issueCreatedRule().name)).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: '자동화' })).toBeInTheDocument()
  })

  it('페이지가 자동화 룰 섹션과 Git 웹훅 섹션을 형제로 조립한다', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '자동화 룰' })).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: 'Git 웹훅' })).toBeInTheDocument()
  })

  // FR1 — 문구 교체 판별자
  it('h1 설명문이 룰 전용 문구가 아니라 두 섹션을 포괄한다', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '자동화' })).toBeInTheDocument()
    })
    expect(screen.queryByText(/트리거 규칙을 관리/)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — 룰 추가/수정 다이얼로그 조립
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — 룰 추가/수정 다이얼로그 조립', () => {
  it('"룰 추가" 클릭 시 생성 모드 FormDialog가 열린다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-rule-add-button'))

    expect(await screen.findByRole('heading', { name: '자동화 룰 추가' })).toBeInTheDocument()
  })

  it('행 "수정" 클릭 시 수정 모드 FormDialog가 열린다', async () => {
    seedAutomationRules([issueCreatedRule()])
    const { user } = renderPage()
    await screen.findByText(issueCreatedRule().name)

    await user.click(screen.getByTestId(`automation-rule-edit-${issueCreatedRule().id}`))

    expect(await screen.findByRole('heading', { name: '자동화 룰 수정' })).toBeInTheDocument()
  })

  it('"취소" 클릭 시 FormDialog가 닫힌다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-rule-add-button'))
    await screen.findByRole('heading', { name: '자동화 룰 추가' })

    await user.click(screen.getByTestId('automation-rule-cancel-button'))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: '자동화 룰 추가' })).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — WEBHOOK 생성 → 토큰 모달 노출/닫힘
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — WEBHOOK 토큰 모달 조립', () => {
  it('WEBHOOK 트리거로 생성 성공 시 onWebhookToken → WebhookTokenModal이 노출되고, 닫으면 사라진다', async () => {
    const { user } = renderPage()
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-rule-add-button'))
    await screen.findByRole('heading', { name: '자동화 룰 추가' })

    await user.type(screen.getByLabelText('이름'), '웹훅 룰')
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'WEBHOOK')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    expect(await screen.findByRole('heading', { name: '웹훅 토큰이 발급되었습니다' })).toBeInTheDocument()

    // FormDialog는 저장 성공 시 스스로 닫힌다 — 두 다이얼로그가 동시에 남지 않는다.
    expect(screen.queryByRole('heading', { name: '자동화 룰 추가' })).not.toBeInTheDocument()

    await user.click(screen.getByTestId('webhook-token-close-button'))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: '웹훅 토큰이 발급되었습니다' })).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — 규칙 충돌 경고 모달 조립 (FR-AT-04 D6/D7 Task 4, WebhookTokenModal 조립부와 대칭)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — 규칙 충돌 경고 모달 조립', () => {
  it('FormDialog의 onConflicts 발화 시 RuleConflictWarningModal이 노출되고, 닫으면 사라진다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', async ({ request }) => {
        const body = (await request.json()) as CreateAutomationRuleInput
        return HttpResponse.json(
          { rule: { ...issueCreatedRule(), ...body, conflicts: SAMPLE_CONFLICTS }, webhookToken: null },
          { status: 201 },
        )
      }),
    )
    const { user } = renderPage()
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-rule-add-button'))
    await screen.findByRole('heading', { name: '자동화 룰 추가' })

    await user.type(screen.getByLabelText('이름'), '충돌 룰')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    expect(await screen.findByTestId('rule-conflict-warning-modal')).toBeInTheDocument()

    await user.click(screen.getByTestId('rule-conflict-close-button'))

    await waitFor(() => {
      expect(screen.queryByTestId('rule-conflict-warning-modal')).not.toBeInTheDocument()
    })
  })

  it('webhookToken과 conflicts가 동시에 세팅되면 토큰 모달만 노출되고 충돌 모달은 표시되지 않는다(토큰 우선 순차)', async () => {
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', async ({ request }) => {
        const body = (await request.json()) as CreateAutomationRuleInput
        return HttpResponse.json(
          {
            rule: { ...issueCreatedRule(), ...body, conflicts: SAMPLE_CONFLICTS },
            webhookToken: 'whk_test-token',
          },
          { status: 201 },
        )
      }),
    )
    const { user } = renderPage()
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-rule-add-button'))
    await screen.findByRole('heading', { name: '자동화 룰 추가' })

    await user.type(screen.getByLabelText('이름'), '웹훅 충돌 룰')
    await user.selectOptions(screen.getByTestId('automation-rule-trigger-select'), 'WEBHOOK')
    await user.click(screen.getByTestId('automation-rule-save-button'))

    expect(await screen.findByRole('heading', { name: '웹훅 토큰이 발급되었습니다' })).toBeInTheDocument()
    expect(screen.queryByTestId('rule-conflict-warning-modal')).not.toBeInTheDocument()

    // 토큰 모달을 닫으면 순차적으로 대기 중이던 충돌 모달이 노출된다(conflicts state는 이미
    // 세팅돼 있었고, webhookToken===null 가드가 풀리며 렌더된다).
    await user.click(screen.getByTestId('webhook-token-close-button'))

    expect(await screen.findByTestId('rule-conflict-warning-modal')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — 실행 이력 Dialog 조립 (FR-AT-05 D6/D7 Task 8, WebhookTokenModal/RuleConflictWarningModal
// 조립부와 대칭 — historyRule 식별값 세팅/null 리셋 관례 동형)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — 실행 이력 Dialog 조립', () => {
  it('행 "이력" 클릭 시 RuleExecutionHistoryDialog가 열려 실행 이력 목록을 표시하고, 닫으면 사라진다', async () => {
    seedAutomationRules([issueCreatedRule()])
    seedAutomationExecutions(
      DEFAULT_RULE_EXECUTIONS.filter((execution) => execution.ruleId === issueCreatedRule().id),
    )
    const { user } = renderPage()
    await screen.findByText(issueCreatedRule().name)

    await user.click(screen.getByTestId(`automation-rule-history-${issueCreatedRule().id}`))

    expect(await screen.findByTestId('rule-execution-history-dialog')).toBeInTheDocument()
    expect(screen.getByText(`${issueCreatedRule().name} 실행 이력`)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('ATLAS-201')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '닫기' }))

    await waitFor(() => {
      expect(screen.queryByTestId('rule-execution-history-dialog')).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Page — YAML GitOps 내보내기/가져오기 조립 (FR-AT-06 D6 Task 7)
// EC8(Dialog 재오픈 시 상태 초기화)은 AutomationYamlImportDialog.tsx(T5) 소유 — 여기서는
// open 상태 전달만 black-box로 관측한다(plan-eng-review §7).
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectAutomationSettingsPage — YAML GitOps 내보내기/가져오기 조립', () => {
  it('내보내기 클릭 → blob 다운로드를 트리거하고 성공 토스트를 낸다 (S1)', async () => {
    const { user } = renderPage('PROJ')
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-yaml-export-button'))

    await waitFor(() => {
      expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledTimes(1)
    })
    // msw가 undici Response에서 만든 Blob은 jsdom 전역 Blob과 realm이 달라 instanceof(expect.any(Blob))
    // 매칭이 깨진다 — 대신 파일명 + 실제 YAML 본문 내용으로 "blob 다운로드"를 검증한다.
    const call = vi.mocked(triggerBlobDownload).mock.calls[0]
    if (call === undefined) throw new Error('triggerBlobDownload 호출 기록이 없다')
    const [blob, filename] = call
    expect(filename).toBe('automation-rules-PROJ.yaml')
    await expect(blob.text()).resolves.toBe(VALID_GITOPS_YAML)
    expect(toast.success).toHaveBeenCalledWith('YAML을 내보냈습니다.')
  })

  it('내보내기 403 이면 권한 없음 토스트를 낸다 (S3)', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/export', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    const { user } = renderPage('PROJ')
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-yaml-export-button'))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith('권한이 없습니다.')
    })
  })

  it('가져오기 클릭 → Dialog 가 열린다 (S4)', async () => {
    const { user } = renderPage('PROJ')
    await screen.findByRole('heading', { name: '자동화' })

    await user.click(screen.getByTestId('automation-yaml-import-button'))

    expect(await screen.findByTestId('automation-yaml-import-dialog')).toBeInTheDocument()
  })
})
