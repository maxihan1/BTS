// AutomationYamlImportDialog 단위 테스트 — 파일선택/크기차단/2단계확인/결과·에러·토큰·conflicts/닫기가드/리셋 (FR-AT-06 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { AutomationYamlImportDialog } from './AutomationYamlImportDialog'
import type { AutomationYamlImportDialogProps } from './AutomationYamlImportDialog'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_PROJECT_KEY,
  resetAutomationRuleStore,
  SCENARIO_KEY,
  VALID_GITOPS_YAML,
} from '@/mocks/automation-rule-fixtures'

const PROJECT_KEY = DEFAULT_AUTOMATION_PROJECT_KEY
const IMPORT_URL = '/api/v1/projects/:projectKey/automation/rules/import'
const RULE_ID = '550e8400-e29b-41d4-a716-446655440000'

// ─────────────────────────────────────────────────────────────────────────────
// 셋업 — 전역 `@/test/server`에 server.use()로 인라인 등록(로컬 setupServer 금지,
// RuleExecutionHistoryDialog.test.tsx 선례 — 이중 인스턴스 시 요청이 중복 dispatch됨).
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  // @/test/server 기본 handlers.ts는 auth/refresh 하나뿐(각 테스트 파일이 필요한 핸들러를
  // server.use()로 직접 등록하는 관례) — automation BC 실동작(시나리오 플래그 분기 포함)을
  // 재현하려면 automationRuleHandlers를 베이스라인으로 깐다(RuleExecutionHistoryDialog.test.tsx는
  // 손수 재구현하지만, 이 Dialog는 시나리오 플래그 의존이 많아 기존 핸들러를 그대로 재사용한다).
  server.use(...automationRuleHandlers)
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})

afterEach(() => {
  for (const key of Object.values(SCENARIO_KEY)) {
    window.localStorage.removeItem(key)
  }
  resetAutomationRuleStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeFile(content: string, name = 'rules.yaml'): File {
  return new File([content], name, { type: 'application/x-yaml' })
}

function buildProps(overrides: Partial<AutomationYamlImportDialogProps> = {}): AutomationYamlImportDialogProps {
  return {
    open: overrides.open ?? true,
    onOpenChange: overrides.onOpenChange ?? vi.fn(),
    projectKey: overrides.projectKey ?? PROJECT_KEY,
  }
}

function renderDialog(overrides: Partial<AutomationYamlImportDialogProps> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const initialProps = buildProps(overrides)

  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AutomationYamlImportDialog {...initialProps} />
    </QueryClientProvider>,
  )

  function rerenderWith(nextOverrides: Partial<AutomationYamlImportDialogProps>): AutomationYamlImportDialogProps {
    const nextProps = buildProps({ ...initialProps, ...nextOverrides })
    utils.rerender(
      <QueryClientProvider client={queryClient}>
        <AutomationYamlImportDialog {...nextProps} />
      </QueryClientProvider>,
    )
    return nextProps
  }

  return { ...utils, rerenderWith, onOpenChange: initialProps.onOpenChange }
}

/** 파일 업로드 → 적용 클릭 → 확정 클릭(2단계 확인)까지 공통 플로우 */
async function applyFile(user: ReturnType<typeof userEvent.setup>, content: string): Promise<void> {
  await user.upload(screen.getByLabelText('YAML 파일'), makeFile(content))
  await user.click(screen.getByTestId('automation-yaml-import-apply-button'))
  await user.click(await screen.findByTestId('automation-yaml-import-confirm-button'))
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('AutomationYamlImportDialog', () => {
  it('파일 미선택이면 적용 버튼이 disabled 다 (EC1)', () => {
    renderDialog()
    expect(screen.getByTestId('automation-yaml-import-apply-button')).toBeDisabled()
  })

  it('파일 input 을 접근 가능한 이름으로 찾을 수 있다 (a11y)', () => {
    renderDialog()
    expect(screen.getByLabelText('YAML 파일')).toBeInTheDocument()
  })

  it('1MiB 초과 파일은 요청 없이 즉시 차단한다 (S7-a/EC3)', async () => {
    const user = userEvent.setup()
    const importSpy = vi.fn(() =>
      HttpResponse.json({ created: 0, updated: 0, total: 0, ruleIds: [], conflicts: [] }),
    )
    server.use(http.post(IMPORT_URL, importSpy))
    renderDialog()

    const oversized = makeFile('a'.repeat(1_048_577), 'big.yaml')
    await user.upload(screen.getByLabelText('YAML 파일'), oversized)

    expect(screen.getByText('파일이 너무 큽니다(최대 1MiB).')).toBeInTheDocument()
    expect(screen.getByTestId('automation-yaml-import-apply-button')).toBeDisabled()
    expect(importSpy).not.toHaveBeenCalled()
  })

  it('적용 → 2단계 확인("확정") → 성공 시 생성/갱신/총 을 표시한다 (S4)', async () => {
    const user = userEvent.setup()
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByTestId('automation-yaml-import-summary')).toHaveTextContent('생성 1 · 갱신 0 · 총 1')
    })
  })

  it('적용 진행 중이면 확정 버튼이 disabled + "적용 중..." 이다 (EC5)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, async () => {
        await new Promise((resolve) => setTimeout(resolve, 50))
        return HttpResponse.json({ created: 1, updated: 0, total: 1, ruleIds: [RULE_ID], conflicts: [] })
      }),
    )
    renderDialog()

    await user.upload(screen.getByLabelText('YAML 파일'), makeFile(VALID_GITOPS_YAML))
    await user.click(screen.getByTestId('automation-yaml-import-apply-button'))
    await user.click(await screen.findByTestId('automation-yaml-import-confirm-button'))

    await waitFor(() => {
      expect(screen.getByTestId('automation-yaml-import-confirm-button')).toBeDisabled()
    })
    expect(screen.getByTestId('automation-yaml-import-confirm-button')).toHaveTextContent('적용 중...')
  })

  it('failedIndex 를 +1 해 "3번째 룰" 로 표시하고 전량취소를 병기한다 (S6/FR10)', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_FAILED_INDEX, 'true')
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByText(/3번째 룰에서 실패했습니다\./)).toBeInTheDocument()
    })
    expect(screen.getByText(/적용된 변경이 없습니다\(전량 취소\)\./)).toBeInTheDocument()
  })

  it('failedIndex 없는 400 은 서버 detail + 전량취소만 표시한다 (S5)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_IMPORT_INVALID', detail: 'YAML 형식이 올바르지 않습니다.' }, { status: 400 })),
    )
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(
        screen.getByText('YAML 형식이 올바르지 않습니다. 적용된 변경이 없습니다(전량 취소).'),
      ).toBeInTheDocument()
    })
    expect(screen.queryByText(/번째 룰에서 실패했습니다/)).toBeNull()
  })

  it('409 도 서버 detail + 전량취소로 표시한다 — 프론트 고정문구 없음 (S9)', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_VERSION_CONFLICT, 'true')
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(
        screen.getByText(
          '다른 변경이 먼저 반영되었습니다. 최신 정보를 다시 불러온 뒤 시도해 주세요. 적용된 변경이 없습니다(전량 취소).',
        ),
      ).toBeInTheDocument()
    })
  })

  it('detail 이 없는 실패는 fallback 문구 + 전량취소를 표시한다 (NIT-11)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, () => HttpResponse.json({ errorCode: 'AUTOMATION_IMPORT_INVALID' }, { status: 400 })),
    )
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByText('가져오기에 실패했습니다. 적용된 변경이 없습니다(전량 취소).')).toBeInTheDocument()
    })
  })

  it('타 프로젝트 복사 안내를 에러와 무관하게 항상 표시한다 (S8 상시 도움말)', async () => {
    const user = userEvent.setup()
    const hint =
      '다른 프로젝트의 룰을 복사하려면 YAML에서 id: 줄을 제거하세요. 같은 프로젝트에 다시 적용하는 경우에는 그대로 두면 됩니다.'
    renderDialog()
    expect(screen.getByText(hint)).toBeInTheDocument()

    server.use(
      http.post(IMPORT_URL, () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_IMPORT_INVALID', detail: '깨진 YAML 입니다.' }, { status: 400 })),
    )
    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByText(/깨진 YAML 입니다\./)).toBeInTheDocument()
    })
    expect(screen.getByText(hint)).toBeInTheDocument()
  })

  it('conflicts 를 인라인 경고로 표시한다 — 중첩 모달 없이 (FR7)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, () =>
        HttpResponse.json({
          created: 1,
          updated: 0,
          total: 1,
          ruleIds: [RULE_ID],
          conflicts: [{ type: 'CYCLE', severity: 'WARNING', ruleIds: [RULE_ID], detail: '순환 참조가 감지되었습니다.' }],
        })),
    )
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByTestId('automation-yaml-import-conflicts')).toBeInTheDocument()
    })
    expect(screen.getByText('순환 참조가 감지되었습니다.')).toBeInTheDocument()
    expect(screen.queryByTestId('rule-conflict-warning-modal')).toBeNull()
  })

  it('conflicts 가 빈 배열이면 경고 영역을 렌더하지 않는다 (EC10)', async () => {
    const user = userEvent.setup()
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByTestId('automation-yaml-import-summary')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('automation-yaml-import-conflicts')).toBeNull()
  })

  it('webhookTokens 를 결과 카운트보다 먼저 렌더한다 (정보 계층)', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_WEBHOOK_TOKENS, 'true')
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    const tokensSection = await screen.findByTestId('automation-yaml-import-tokens-section')
    const summary = screen.getByTestId('automation-yaml-import-summary')
    const position = tokensSection.compareDocumentPosition(summary)
    expect(Boolean(position & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true)
  })

  it('webhookTokens 를 목록+복사로 표시하고 복사 실패 문구를 낸다 (S10/EC12)', async () => {
    const user = userEvent.setup()
    const clipboardWriteText = vi.fn().mockRejectedValue(new Error('clipboard denied'))
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: clipboardWriteText } })
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_WEBHOOK_TOKENS, 'true')
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)
    await screen.findByTestId('automation-yaml-import-tokens-section')

    expect(screen.getByText('웹훅 룰')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '복사' }))

    await waitFor(() => {
      expect(screen.getByText('복사에 실패했습니다. 직접 선택해 복사해 주세요.')).toBeInTheDocument()
    })
  })

  it('토큰을 localStorage/sessionStorage 에 기록하지 않는다 (NFR3)', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_WEBHOOK_TOKENS, 'true')
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)
    const tokenCode = await screen.findByText(/^whk_/)
    const rawToken = tokenCode.textContent ?? ''
    expect(rawToken.length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '복사' }))
    await waitFor(() => {
      expect(screen.getByText(/복사됨|복사에 실패했습니다/)).toBeInTheDocument()
    })

    const persisted = setItemSpy.mock.calls.some(([, value]) => typeof value === 'string' && value.includes(rawToken))
    expect(persisted).toBe(false)
  })

  it('webhookTokens 키가 생략되면 토큰 영역을 렌더하지 않는다 (EC11)', async () => {
    const user = userEvent.setup()
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(screen.getByTestId('automation-yaml-import-summary')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('automation-yaml-import-tokens-section')).toBeNull()
  })

  it('토큰 노출 중 ESC/오버레이/X 모두 2단계 확인을 거친다 (EC7)', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_WEBHOOK_TOKENS, 'true')
    const { onOpenChange } = renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)
    await screen.findByTestId('automation-yaml-import-tokens-section')

    // ESC
    await user.keyboard('{Escape}')
    expect(await screen.findByText('토큰은 다시 볼 수 없습니다. 닫을까요?')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    await user.click(screen.getByTestId('automation-yaml-import-close-cancel'))

    // 오버레이 클릭
    await user.click(screen.getByTestId('automation-yaml-import-overlay'))
    expect(await screen.findByText('토큰은 다시 볼 수 없습니다. 닫을까요?')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    await user.click(screen.getByTestId('automation-yaml-import-close-cancel'))

    // X(닫기) 버튼
    await user.click(screen.getByTestId('automation-yaml-import-close-button'))
    expect(await screen.findByText('토큰은 다시 볼 수 없습니다. 닫을까요?')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)

    // 확정해야만 실제로 닫힌다
    await user.click(screen.getByTestId('automation-yaml-import-close-confirm'))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('in-flight 중에는 닫기 버튼이 disabled 이고 ESC 로 닫히지 않는다 (review-fix CRITICAL-1)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, async () => {
        // EC5(50ms)보다 넉넉히 늘려 disabled 확인 + ESC 입력이 in-flight 창 안에서 안정적으로 끝나게 한다.
        await new Promise((resolve) => setTimeout(resolve, 300))
        return HttpResponse.json({ created: 1, updated: 0, total: 1, ruleIds: [RULE_ID], conflicts: [] })
      }),
    )
    const { onOpenChange } = renderDialog()

    await user.upload(screen.getByLabelText('YAML 파일'), makeFile(VALID_GITOPS_YAML))
    await user.click(screen.getByTestId('automation-yaml-import-apply-button'))
    await user.click(await screen.findByTestId('automation-yaml-import-confirm-button'))

    await waitFor(() => {
      expect(screen.getByTestId('automation-yaml-import-close-button')).toBeDisabled()
    })

    await user.keyboard('{Escape}')
    expect(await screen.findByText('토큰은 다시 볼 수 없습니다. 닫을까요?')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('토큰 표시 중에는 파일 input 이 disabled 다 (review-fix CRITICAL-2)', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem(SCENARIO_KEY.IMPORT_WEBHOOK_TOKENS, 'true')
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)
    await screen.findByTestId('automation-yaml-import-tokens-section')

    expect(screen.getByLabelText('YAML 파일')).toBeDisabled()
  })

  it('응답이 스키마에 맞지 않으면(ZodError) 전량취소 대신 결과 불확실 문구를 표시한다 (review-fix CRITICAL-3)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, () =>
        HttpResponse.json({
          created: 1,
          updated: 0,
          total: 1,
          ruleIds: [RULE_ID],
          webhookTokens: [{ ruleId: 'not-a-uuid', name: '웹훅 룰', token: 'whk_x' }],
          conflicts: [],
        })),
    )
    renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)

    await waitFor(() => {
      expect(
        screen.getByText(
          '가져오기 결과를 확인하지 못했습니다. 일부가 적용됐을 수 있으니 룰 목록을 확인한 뒤 다시 시도하세요.',
        ),
      ).toBeInTheDocument()
    })
    expect(screen.queryByText(/전량 취소/)).toBeNull()
  })

  it('file.text() 가 reject 하면 파일 읽기 실패 문구를 보이고 "적용 중"에 머물지 않는다 (review-fix CONCERN-4)', async () => {
    const user = userEvent.setup()
    const file = makeFile(VALID_GITOPS_YAML)
    Object.defineProperty(file, 'text', { value: () => Promise.reject(new Error('read failed')) })
    renderDialog()

    await user.upload(screen.getByLabelText('YAML 파일'), file)
    await user.click(screen.getByTestId('automation-yaml-import-apply-button'))
    await user.click(await screen.findByTestId('automation-yaml-import-confirm-button'))

    await waitFor(() => {
      expect(screen.getByText('파일을 읽지 못했습니다. 파일을 다시 선택해 주세요.')).toBeInTheDocument()
    })
    expect(screen.queryByText('적용 중...')).toBeNull()
    expect(await screen.findByTestId('automation-yaml-import-apply-button')).toBeInTheDocument()
  })

  it('open 이 false→true 로 재전이하면 파일·결과·에러 상태가 초기화된다 (EC8)', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(IMPORT_URL, () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_IMPORT_INVALID', detail: '깨진 YAML 입니다.' }, { status: 400 })),
    )
    const { rerenderWith } = renderDialog()

    await applyFile(user, VALID_GITOPS_YAML)
    await waitFor(() => {
      expect(screen.getByText(/깨진 YAML 입니다\./)).toBeInTheDocument()
    })

    rerenderWith({ open: false })
    rerenderWith({ open: true })

    expect(screen.queryByText(/깨진 YAML 입니다\./)).toBeNull()
    expect(screen.getByTestId('automation-yaml-import-apply-button')).toBeDisabled()
  })
})
