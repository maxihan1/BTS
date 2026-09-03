// 편집기 다이얼로그 조립 테스트 — 초안 폐기 확인이 폴링 정지 종류(5xx·4xx)를 가리는가
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WorkflowEditorDialogs } from '../WorkflowEditorDialogs'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import type { UseMigrationWizardResult } from '@/hooks/use-publish-flow'
import type { EditableDraft } from '@/lib/workflow-draft'

const DRAFT: EditableDraft = {
  key: 'wf',
  name: '워크플로우',
  description: null,
  states: [{ key: 'open', name: '열림', category: 'TODO', displayOrder: 1, layoutX: null, layoutY: null }],
  transitions: [],
}

/** 마법사 배선 목. 폐기 경고가 보는 것은 `pollRetryable` 하나뿐이다. */
function migrationMock(over: Partial<UseMigrationWizardResult> = {}): UseMigrationWizardResult {
  return {
    published: null,
    publishedFailed: false,
    selection: {},
    onSelectionChange: vi.fn(),
    onStartMigration: vi.fn(),
    starting: false,
    startError: null,
    operation: null,
    progressRatio: null,
    pollFailed: false,
    pollRetryable: false,
    retryPoll: vi.fn(),
    discardDisabled: false,
    ...over,
  }
}

/** 초안 폐기 확인만 열어 둔 조립 — 나머지 다이얼로그는 전부 닫힌 채로 둔다. */
function renderDialogs(over: Partial<React.ComponentProps<typeof WorkflowEditorDialogs>> = {}) {
  const props: React.ComponentProps<typeof WorkflowEditorDialogs> = {
    draft: DRAFT,
    catalog: [],
    stateNames: { open: '열림' },
    blockReason: null,
    pickerOpen: false,
    onPickerOpenChange: vi.fn(),
    onAddStatus: vi.fn(),
    statusToRemove: null,
    onStatusRemoveChange: vi.fn(),
    onConfirmRemoveStatus: vi.fn(),
    transitionFormOpen: false,
    onTransitionFormOpenChange: vi.fn(),
    panelTransitions: [],
    editingTransition: null,
    onSubmitTransition: vi.fn(),
    transitionToRemove: null,
    onTransitionRemoveChange: vi.fn(),
    onConfirmRemoveTransition: vi.fn(),
    flowDialog: 'discard',
    onFlowClose: vi.fn(),
    preview: null,
    onConfirmPublish: vi.fn(),
    onConfirmReset: vi.fn(),
    onConfirmDiscard: vi.fn(),
    busy: false,
    migration: migrationMock(),
    ...over,
  }
  render(<WorkflowEditorDialogs {...props} />)
}

/**
 * ★ 두 판정은 **짝**이다. 5xx 로 멈추면 잠금이 풀려 이 확인 창에 닿을 수 있는데, 그 정지는
 * 「이관이 끝났다」가 아니라 「서버 상태를 못 본다」이므로 도는 중일지 모르는 이관을 말해야 한다
 * (E5 — 이미 옮겨진 이슈는 되돌아오지 않는다). 4xx 는 죽은 id 라 진행 중일 가능성 자체가 없다.
 */
describe('초안 폐기 확인 — 폴링이 멈춘 이유에 따라 경고가 갈린다', () => {
  it('5xx·네트워크로 멈췄으면 「이관이 아직 진행 중일 수 있다」를 함께 말한다', () => {
    renderDialogs({ migration: migrationMock({ pollFailed: true, pollRetryable: true }) })

    expect(screen.getByRole('dialog', { name: labels.discard.dialogTitle })).toHaveTextContent(
      labels.discard.migrationRunningWarning,
    )
  })

  it('4xx 로 멈췄으면 그 경고를 붙이지 않는다 — 없는 위험을 알리면 다음 경고까지 무뎌진다', () => {
    renderDialogs({ migration: migrationMock({ pollFailed: true, pollRetryable: false }) })

    const dialog = screen.getByRole('dialog', { name: labels.discard.dialogTitle })
    // 기본 설명은 그대로 있다 — 위 판정이 「문구가 통째로 사라진 것」을 잡지 못하면 공허해진다.
    expect(dialog).toHaveTextContent(labels.discard.dialogDescription)
    expect(dialog).not.toHaveTextContent(labels.discard.migrationRunningWarning)
  })
})
