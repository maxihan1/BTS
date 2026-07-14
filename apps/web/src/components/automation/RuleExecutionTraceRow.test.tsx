// RuleExecutionTraceRow 단위 테스트 — 요약 배지·펼침 상세(outcomes/triggerEvent)·인라인 재실행 확인 (FR-AT-05 D6/D7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { RuleExecutionTraceRow } from './RuleExecutionTraceRow'
import type { RuleExecutionSummary, RuleExecutionDetail } from '@/api/automation-executions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — useAutomationExecutions.test.tsx와 동형 v4 UUID 형식(Zod v4 uuid() 검증 통과)
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const RULE_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e'

/** n을 4자리 hex로 넣어 서로 다른 v4 형식 UUID를 생성 — 버전(4)/변형(8) 니블은 고정 */
function uuidFromIndex(n: number): string {
  const segment = n.toString(16).padStart(4, '0')
  return `aaaaaaaa-${segment}-4aaa-8aaa-aaaaaaaaaaaa`
}

const EXECUTION_ID = uuidFromIndex(1)
const NOT_FOUND_EXECUTION_ID = uuidFromIndex(2)
const REPLAYED_ID = uuidFromIndex(3)

function buildSummary(overrides: Partial<RuleExecutionSummary> = {}): RuleExecutionSummary {
  return {
    id: EXECUTION_ID,
    ruleId: RULE_ID,
    triggerType: 'ISSUE_CREATED',
    issueKey: 'ATLAS-100',
    status: 'SUCCESS',
    actionCount: 2,
    successCount: 2,
    startedAt: '2026-07-10T09:00:00Z',
    finishedAt: '2026-07-10T09:00:01Z',
    replayedFrom: null,
    ...overrides,
  }
}

const DETAIL_FIXTURE: RuleExecutionDetail = {
  id: EXECUTION_ID,
  ruleId: RULE_ID,
  projectKey: PROJECT_KEY,
  triggerType: 'ISSUE_CREATED',
  triggerEvent: { issueKey: 'ATLAS-100', type: 'ISSUE_CREATED' },
  issueKey: 'ATLAS-100',
  status: 'SUCCESS',
  outcomes: [
    { position: 0, actionType: 'SET_FIELD', success: true, error: null },
    { position: 1, actionType: 'ADD_COMMENT', success: false, error: 'ISSUE_LOCKED' },
  ],
  replayedFrom: null,
  startedAt: '2026-07-10T09:00:00Z',
  finishedAt: '2026-07-10T09:00:01Z',
}

const REPLAYED_DETAIL: RuleExecutionDetail = {
  ...DETAIL_FIXTURE,
  id: REPLAYED_ID,
  replayedFrom: EXECUTION_ID,
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — 전역 `@/test/server`에 server.use()로 인라인 등록(로컬 setupServer 금지,
// useAutomationExecutions.test.tsx 선례 — 이중 인스턴스 시 요청이 중복 dispatch됨).
// ─────────────────────────────────────────────────────────────────────────────

let replayCallCount = 0

function registerHandlers(): void {
  server.use(
    http.get('/api/v1/automation/executions/:id', ({ params }) => {
      if (params['id'] === EXECUTION_ID) {
        return HttpResponse.json(DETAIL_FIXTURE)
      }
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }),
    http.post('/api/v1/automation/executions/:id/replay', async ({ params }) => {
      replayCallCount += 1
      if (params['id'] === EXECUTION_ID) {
        return HttpResponse.json(REPLAYED_DETAIL)
      }
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }),
  )
}

beforeEach(() => {
  registerHandlers()
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
  replayCallCount = 0
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderRow(overrides: {
  execution?: RuleExecutionSummary
  onReplaySuccess?: (detail: RuleExecutionDetail) => void
  onReplayError?: (error: unknown) => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const execution = overrides.execution ?? buildSummary()
  const onReplaySuccess = overrides.onReplaySuccess ?? vi.fn()
  const onReplayError = overrides.onReplayError ?? vi.fn()

  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ul>
        <RuleExecutionTraceRow
          execution={execution}
          projectKey={PROJECT_KEY}
          ruleId={RULE_ID}
          onReplaySuccess={onReplaySuccess}
          onReplayError={onReplayError}
        />
      </ul>
    </QueryClientProvider>,
  )

  return { ...utils, onReplaySuccess, onReplayError, execution }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('RuleExecutionTraceRow', () => {
  describe('요약 행', () => {
    it.each([
      ['SUCCESS', '성공'],
      ['PARTIAL', '부분 성공'],
      ['FAILED', '실패'],
      ['SKIPPED', '조건 불충족'],
    ] as const)('status=%s는 배지에 텍스트 라벨 "%s"를 색상과 함께 표시한다', (status, expectedLabel) => {
      renderRow({ execution: buildSummary({ status }) })
      // 색상 단독 금지(NFR1) — 텍스트 라벨이 실제로 DOM에 존재해야 한다.
      expect(screen.getByText(expectedLabel)).toBeInTheDocument()
    })

    it('트리거 타입 한국어 라벨을 표시한다', () => {
      renderRow({ execution: buildSummary({ triggerType: 'ISSUE_UPDATED' }) })
      expect(screen.getByText('수정')).toBeInTheDocument()
    })

    it('미지 트리거 타입은 원문을 그대로 fallback한다', () => {
      renderRow({ execution: buildSummary({ triggerType: 'FUTURE_TRIGGER' }) })
      expect(screen.getByText('FUTURE_TRIGGER')).toBeInTheDocument()
    })

    it('issueKey가 없으면 "이슈 없음"을 표시한다', () => {
      renderRow({ execution: buildSummary({ issueKey: null }) })
      expect(screen.getByText('이슈 없음')).toBeInTheDocument()
    })

    it('issueKey가 있으면 그대로 표시한다', () => {
      renderRow({ execution: buildSummary({ issueKey: 'ATLAS-100' }) })
      expect(screen.getByText('ATLAS-100')).toBeInTheDocument()
    })

    it('성공/전체 액션 수를 표시한다', () => {
      renderRow({ execution: buildSummary({ successCount: 1, actionCount: 2 }) })
      expect(screen.getByText('1/2 성공')).toBeInTheDocument()
    })

    it('replayedFrom이 있으면 "재실행됨" 표식을 표시한다', () => {
      renderRow({ execution: buildSummary({ replayedFrom: EXECUTION_ID }) })
      expect(screen.getByText('재실행됨')).toBeInTheDocument()
    })

    it('replayedFrom이 없으면 "재실행됨" 표식을 표시하지 않는다', () => {
      renderRow({ execution: buildSummary({ replayedFrom: null }) })
      expect(screen.queryByText('재실행됨')).not.toBeInTheDocument()
    })

    it('행 전체가 펼침 토글 버튼이다 (aria-expanded, button semantics)', () => {
      renderRow()
      const toggle = screen.getByRole('button', { expanded: false })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
    })
  })

  describe('펼침 상세', () => {
    it('클릭하면 펼쳐지고(aria-expanded=true) 로딩 표시 후 outcomes·triggerEvent를 렌더한다', async () => {
      const user = userEvent.setup()
      renderRow()

      const toggle = screen.getByRole('button', { expanded: false })
      await user.click(toggle)

      expect(screen.getByRole('button', { expanded: true })).toBeInTheDocument()

      await waitFor(() => {
        expect(screen.getByText('SET_FIELD')).toBeInTheDocument()
      })

      // 성공 액션 ✓, 실패 액션 ✗ + error 코드
      expect(screen.getByText('ADD_COMMENT')).toBeInTheDocument()
      expect(screen.getByText('ISSUE_LOCKED')).toBeInTheDocument()

      // triggerEvent JSON pretty-print
      const pre = document.querySelector('pre')
      expect(pre).not.toBeNull()
      expect(pre?.textContent).toContain('"issueKey"')
      expect(pre?.textContent).toContain('ATLAS-100')
    })

    it('detail 로딩 중에는 펼침 영역 내 로딩 표시가 나타난다', async () => {
      const user = userEvent.setup()
      renderRow()

      const toggle = screen.getByRole('button', { expanded: false })
      await user.click(toggle)

      expect(screen.getByRole('status')).toBeInTheDocument()

      await waitFor(() => {
        expect(screen.queryByRole('status')).not.toBeInTheDocument()
      })
    })

    it('detail 조회가 404면 인라인 에러를 표시하고 행은 유지된다', async () => {
      const user = userEvent.setup()
      renderRow({ execution: buildSummary({ id: NOT_FOUND_EXECUTION_ID }) })

      const toggle = screen.getByRole('button', { expanded: false })
      await user.click(toggle)

      await waitFor(() => {
        expect(screen.getByText('실행 이력을 찾을 수 없습니다')).toBeInTheDocument()
      })
      expect(screen.getByRole('button', { expanded: true })).toBeInTheDocument()
    })

    it('다시 클릭하면 접힌다', async () => {
      const user = userEvent.setup()
      renderRow()

      const toggle = screen.getByRole('button', { expanded: false })
      await user.click(toggle)
      await waitFor(() => {
        expect(screen.getByText('SET_FIELD')).toBeInTheDocument()
      })

      await user.click(screen.getByRole('button', { expanded: true }))
      expect(screen.queryByText('SET_FIELD')).not.toBeInTheDocument()
    })
  })

  describe('재실행', () => {
    async function expandRow(): Promise<ReturnType<typeof userEvent.setup>> {
      const user = userEvent.setup()
      const toggle = screen.getByRole('button', { expanded: false })
      await user.click(toggle)
      await waitFor(() => {
        expect(screen.getByText('SET_FIELD')).toBeInTheDocument()
      })
      return user
    }

    it('재실행 버튼 클릭 시 인라인 확인이 나타난다 (모달 아님)', async () => {
      renderRow()
      const user = await expandRow()

      await user.click(screen.getByRole('button', { name: '재실행' }))

      expect(screen.getByText('실제 이슈 변경이 발생합니다')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '확정' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument()
    })

    it('확정 클릭 시 mutate가 호출되고 성공하면 onReplaySuccess가 새 detail로 호출된다', async () => {
      const onReplaySuccess = vi.fn()
      renderRow({ onReplaySuccess })
      const user = await expandRow()

      await user.click(screen.getByRole('button', { name: '재실행' }))
      await user.click(screen.getByRole('button', { name: '확정' }))

      await waitFor(() => {
        expect(onReplaySuccess).toHaveBeenCalledWith(REPLAYED_DETAIL)
      })
      expect(replayCallCount).toBe(1)
    })

    it('취소 클릭 시 확인 UI가 닫히고 mutate는 호출되지 않는다', async () => {
      renderRow()
      const user = await expandRow()

      await user.click(screen.getByRole('button', { name: '재실행' }))
      await user.click(screen.getByRole('button', { name: '취소' }))

      expect(screen.queryByText('실제 이슈 변경이 발생합니다')).not.toBeInTheDocument()
      expect(replayCallCount).toBe(0)
    })

    it('진행 중에는 확정/취소 버튼이 disabled된다', async () => {
      // POST 응답을 수동으로 제어해(resolveReplay 호출 전까지 미해결) pending 구간을 결정적으로 관측한다.
      let resolveReplay: (() => void) | undefined
      server.use(
        http.post('/api/v1/automation/executions/:id/replay', async () => {
          await new Promise<void>((resolve) => {
            resolveReplay = resolve
          })
          return HttpResponse.json(REPLAYED_DETAIL)
        }),
      )

      renderRow()
      const user = await expandRow()

      await user.click(screen.getByRole('button', { name: '재실행' }))
      await user.click(screen.getByRole('button', { name: '확정' }))

      await waitFor(() => {
        expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
      })
      expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()

      resolveReplay?.()
    })

    it('실패하면 onReplayError가 호출된다', async () => {
      const onReplayError = vi.fn()
      renderRow({ execution: buildSummary({ id: NOT_FOUND_EXECUTION_ID }), onReplayError })

      const user = userEvent.setup()
      const toggle = screen.getByRole('button', { expanded: false })
      await user.click(toggle)
      await waitFor(() => {
        expect(screen.getByText('실행 이력을 찾을 수 없습니다')).toBeInTheDocument()
      })

      await user.click(screen.getByRole('button', { name: '재실행' }))
      await user.click(screen.getByRole('button', { name: '확정' }))

      await waitFor(() => {
        expect(onReplayError).toHaveBeenCalled()
      })
    })
  })
})
