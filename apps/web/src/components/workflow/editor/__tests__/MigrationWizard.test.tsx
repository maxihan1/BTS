// 상태 이관 마법사 테스트 — 도착지 선택(J7) · 잔여 건수(J3) · 진행률 · 실패/제외 목록 · 고지 2종
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MigrationWizard } from '../MigrationWizard'
import { calculateProgressRatio } from '@/hooks/use-bulk-operation'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import { failureReasonLabels } from '@/i18n/bulk-operation-labels'
import type { EditableDraft } from '@/lib/workflow-draft'
import type { BulkOperationResponse } from '@/api/bulk-operations'
import type { DraftDefinition } from '@/api/workflows-draft.types'
import type { MigrationSelection } from '@/lib/workflow-migration'

const OPEN = { key: 'open', name: '열림', category: 'TODO', displayOrder: 1, layoutX: null, layoutY: null } as const
const DOING = { key: 'doing', name: '진행 중', category: 'IN_PROGRESS', displayOrder: 2, layoutX: null, layoutY: null } as const
const DONE = { key: 'done', name: '완료', category: 'DONE', displayOrder: 3, layoutX: null, layoutY: null } as const

/** 발행본 — `doing`·`done` 을 포함한 3개 상태(빼면 사라진다). */
function published(): DraftDefinition {
  return { key: 'wf', name: '워크플로우', description: null, states: [OPEN, DOING, DONE], transitions: [] }
}

/** 초안 — `doing`·`done` 두 상태를 뺐다(발행하면 사라질 후보 2개). */
function draft(): EditableDraft {
  return { key: 'wf', name: '워크플로우', description: null, states: [OPEN], transitions: [] }
}

function operation(over: Partial<BulkOperationResponse> = {}): BulkOperationResponse {
  return {
    id: '00000000-0000-4000-8000-000000000099',
    operationType: 'STATUS_MIGRATION',
    status: 'RUNNING',
    payload: { mappings: { doing: 'open', done: 'open' }, projectKeys: [] },
    totalCount: 10,
    processedCount: 4,
    succeededCount: 4,
    failedCount: 0,
    items: [],
    ...over,
  }
}

/**
 * ★ `progressRatio` 를 `operation` 에서 **파생**시킨다.
 *
 * 실제 화면에서 이 둘은 같은 폴링 응답에서 나온다(`useBulkOperationPolling`). 목이 그 관계를
 * 깨면 「4/10 인데 진행률은 셀 수 없음」 같은 도달 불가 조합을 재는 판정이 된다.
 */
function renderWizard(over: Partial<React.ComponentProps<typeof MigrationWizard>> = {}) {
  const onSelectionChange = vi.fn()
  const onStartMigration = vi.fn()
  const operationProp = over.operation ?? null
  const props: React.ComponentProps<typeof MigrationWizard> = {
    draft: draft(),
    published: published(),
    pendingIssueCounts: { doing: 5, done: 2 },
    selection: {},
    onSelectionChange,
    onStartMigration,
    starting: false,
    operation: operationProp,
    progressRatio: calculateProgressRatio(operationProp ?? undefined),
    ...over,
  }
  render(<MigrationWizard {...props} />)
  return { onSelectionChange, onStartMigration }
}

describe('상태 이관 마법사 — 도착지 선택 단계', () => {
  it('사라지는 상태마다 도착지 셀렉트가 각각 렌더된다 (J7)', () => {
    renderWizard()

    expect(screen.getByRole('combobox', { name: '진행 중' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '완료' })).toBeInTheDocument()
  })

  it('상태별 잔여 건수가 보인다 (J3)', () => {
    renderWizard()

    expect(screen.getByText(`5${labels.publish.issueCountSuffix}`)).toBeInTheDocument()
    expect(screen.getByText(`2${labels.publish.issueCountSuffix}`)).toBeInTheDocument()
  })

  it('전부 고르기 전에는 이관 시작이 비활성이다', () => {
    const selection: MigrationSelection = { doing: 'open' }
    renderWizard({ selection })

    expect(screen.getByRole('button', { name: labels.migration.start })).toBeDisabled()
  })

  it('전부 고르면 이관 시작이 활성화되고, 누르면 서버 계약 형태로 넘긴다', async () => {
    const selection: MigrationSelection = { doing: 'open', done: 'open' }
    const { onStartMigration } = renderWizard({ selection })

    const button = screen.getByRole('button', { name: labels.migration.start })
    expect(button).toBeEnabled()

    await userEvent.click(button)

    expect(onStartMigration).toHaveBeenCalledWith([
      { fromStatusKey: 'doing', toStatusKey: 'open' },
      { fromStatusKey: 'done', toStatusKey: 'open' },
    ])
  })

  it('이관 시작 요청 전송 중에는 전부 골라도 비활성이다', () => {
    const selection: MigrationSelection = { doing: 'open', done: 'open' }
    renderWizard({ selection, starting: true })

    expect(screen.getByRole('button', { name: labels.migration.start })).toBeDisabled()
  })
})

describe('상태 이관 마법사 — 고지 2종은 상시·무조건부', () => {
  it('규칙 미발화 고지가 상시 보인다 (J8)', () => {
    renderWizard()

    expect(screen.getByText(labels.migration.rulesNotTriggeredNotice)).toBeInTheDocument()
  })

  it('보드 컬럼 고지가 무조건부로 보인다 (X4 — 사라지는 상태가 있으면 조건 없이)', () => {
    renderWizard()

    expect(screen.getByText(labels.migration.boardColumnsNotice)).toBeInTheDocument()
  })

  it('진행 중 단계에서도 규칙 미발화 고지가 계속 보인다', () => {
    renderWizard({ operation: operation() })

    expect(screen.getByText(labels.migration.rulesNotTriggeredNotice)).toBeInTheDocument()
  })
})

describe('상태 이관 마법사 — 진행률·결과 단계', () => {
  it('진행 중에는 진행률이 role="progressbar" + aria-valuenow 로 보인다', () => {
    renderWizard({ operation: operation({ status: 'RUNNING', processedCount: 4, totalCount: 10 }) })

    const bar = screen.getByRole('progressbar', { name: labels.migration.progressLabel })
    expect(bar).toHaveAttribute('aria-valuenow', '4')
    expect(bar).toHaveAttribute('aria-valuemin', '0')
    expect(bar).toHaveAttribute('aria-valuemax', '10')
  })

  it('퍼센트는 훅이 준 progressRatio 를 그대로 옮긴다 — 화면이 다시 나누지 않는다', () => {
    renderWizard({ operation: operation({ status: 'RUNNING', processedCount: 4, totalCount: 10 }) })

    expect(screen.getByText('4 / 10 (40%)')).toBeInTheDocument()
  })

  it('아직 셀 수 없으면(총 건수 0 · progressRatio null) 0% 로 그린다', () => {
    // 「못 셌다」를 NaN% 로 흘리지 않는다 — 0 나눗셈 처리는 훅 한 곳에만 있다.
    renderWizard({ operation: operation({ status: 'PENDING', processedCount: 0, totalCount: 0 }) })

    expect(screen.getByText('0 / 0 (0%)')).toBeInTheDocument()
  })

  it('완료되면 완료 문구가 보인다', () => {
    renderWizard({
      operation: operation({ status: 'COMPLETED', processedCount: 10, totalCount: 10, succeededCount: 10 }),
    })

    expect(screen.getByText(labels.migration.completed)).toBeInTheDocument()
  })

  it('failedCount > 0 이면 이슈 키 + 실패 사유가 나열된다', () => {
    renderWizard({
      operation: operation({
        status: 'COMPLETED',
        failedCount: 1,
        items: [{ issueKey: 'PROJ-1', status: 'FAILED', failureReasonCode: 'TRANSITION_NOT_ALLOWED' }],
      }),
    })

    const region = screen.getByRole('region', { name: labels.migration.failuresHeading })
    expect(region).toHaveTextContent('PROJ-1')
    expect(region).toHaveTextContent(failureReasonLabels.TRANSITION_NOT_ALLOWED)
  })

  it('PROJECT_ARCHIVED 는 「오류」로 보이지 않는다 — 실패 목록에서 제외됨으로 분리한다 (E10)', () => {
    renderWizard({
      operation: operation({
        status: 'COMPLETED',
        failedCount: 2,
        items: [
          { issueKey: 'PROJ-1', status: 'FAILED', failureReasonCode: 'TRANSITION_NOT_ALLOWED' },
          { issueKey: 'PROJ-2', status: 'FAILED', failureReasonCode: 'PROJECT_ARCHIVED' },
        ],
      }),
    })

    const failures = screen.getByRole('region', { name: labels.migration.failuresHeading })
    expect(failures).toHaveTextContent('PROJ-1')
    expect(failures).not.toHaveTextContent('PROJ-2')

    const excluded = screen.getByRole('region', { name: failureReasonLabels.PROJECT_ARCHIVED })
    expect(excluded).toHaveTextContent('PROJ-2')
  })

  it('failedCount 가 0 이면 실패 목록 자체를 그리지 않는다', () => {
    renderWizard({ operation: operation({ status: 'COMPLETED', failedCount: 0, items: [] }) })

    expect(screen.queryByRole('region', { name: labels.migration.failuresHeading })).not.toBeInTheDocument()
  })
})
