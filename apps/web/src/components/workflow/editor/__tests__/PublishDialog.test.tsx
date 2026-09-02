// 발행 다이얼로그 테스트 — 이관 필요 분기 · 보드 고지 · 발행 차단 사유
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PublishDialog } from '../PublishDialog'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import type { PublishPreview } from '@/api/workflows-draft.types'
import type { UseMigrationWizardResult } from '@/hooks/use-publish-flow'
import type { EditableDraft } from '@/lib/workflow-draft'

const NAMES = { done: '완료', open: '열림' }

function preview(over: Partial<PublishPreview> = {}): PublishPreview {
  return {
    baseVersion: 4,
    currentVersion: 4,
    removedStatusKeys: [],
    pendingIssueCounts: {},
    ...over,
  }
}

function renderDialog(over: Partial<React.ComponentProps<typeof PublishDialog>> = {}) {
  const onPublish = vi.fn().mockResolvedValue(undefined)
  const props: React.ComponentProps<typeof PublishDialog> = {
    open: true,
    onOpenChange: vi.fn(),
    preview: preview(),
    stateNames: NAMES,
    blockReason: null,
    onPublish,
    publishing: false,
    ...over,
  }
  render(<PublishDialog {...props} />)
  return { onPublish }
}

describe('발행 다이얼로그', () => {
  it('고유한 제목을 가진 다이얼로그다', () => {
    renderDialog()

    expect(screen.getByRole('dialog', { name: labels.publish.dialogTitle })).toBeInTheDocument()
  })

  it('사라지는 상태가 없으면 그 사실을 말하고 발행 버튼을 준다', async () => {
    const { onPublish } = renderDialog()

    expect(screen.getByText(labels.publish.noRemoved)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: labels.publish.confirm }))

    expect(onPublish).toHaveBeenCalledTimes(1)
  })

  it('사라지는 상태를 **이름**으로 보여준다', () => {
    // 키를 그대로 그리면 사용자가 `done` 을 읽는다.
    renderDialog({ preview: preview({ removedStatusKeys: ['done'] }) })

    expect(screen.getByText(/완료/)).toBeInTheDocument()
  })

  it('사라지는 상태가 있으면 보드 컬럼 고지가 무조건 뜬다', () => {
    // 조건 판정을 흉내 낼 데이터가 없다 — 없는 정보를 있는 척하지 않고 항상 알린다.
    renderDialog({ preview: preview({ removedStatusKeys: ['done'] }) })

    expect(screen.getByText(labels.publish.boardWarning)).toBeInTheDocument()
  })

  it('이슈가 남은 상태가 있으면 발행 대신 이관을 안내한다', async () => {
    const { onPublish } = renderDialog({
      preview: preview({ removedStatusKeys: ['done'], pendingIssueCounts: { done: 3 } }),
    })

    // 발행 버튼을 눌러 409 를 받게 두지 않는다.
    expect(screen.queryByRole('button', { name: labels.publish.confirm })).not.toBeInTheDocument()
    expect(screen.getByText(/3/)).toBeInTheDocument()
    expect(onPublish).not.toHaveBeenCalled()
  })

  it('이슈가 없는 사라지는 상태는 「이슈 없음」으로 구별한다', () => {
    // 안 그리면 「못 셌다」와 구별이 안 된다.
    renderDialog({ preview: preview({ removedStatusKeys: ['done'] }) })

    expect(screen.getByText(labels.publish.noIssues)).toBeInTheDocument()
  })

  it('로컬 발행 차단 사유가 있으면 그것을 띄우고 발행을 막는다', () => {
    renderDialog({ blockReason: '이슈 생성 시 진입할 상태를 정하는 전환이 없습니다' })

    expect(screen.getByText(/이슈 생성 시 진입할 상태/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: labels.publish.confirm })).toBeDisabled()
  })

  it('발행 중에는 버튼이 잠긴다', () => {
    renderDialog({ publishing: true })

    expect(screen.getByRole('button', { name: labels.publish.confirm })).toBeDisabled()
  })
})

/**
 * 폴링 실패 분기 전용 목(mock).
 *
 * `published` 를 채워야 마법사 본체 경로로 들어간다 — null 이면 스켈레톤/로드실패에서 끝난다.
 */
function migrationMock(over: Partial<UseMigrationWizardResult> = {}): UseMigrationWizardResult {
  return {
    published: { states: [], transitions: [] } as unknown as UseMigrationWizardResult['published'],
    publishedFailed: false,
    selection: {},
    onSelectionChange: vi.fn(),
    onStartMigration: vi.fn(),
    starting: false,
    startError: null,
    operation: null,
    pollFailed: false,
    pollRetryable: false,
    retryPoll: vi.fn(),
    discardDisabled: false,
    ...over,
  }
}

const MIGRATION_DRAFT = { states: [], transitions: [] } as unknown as EditableDraft

describe('발행 다이얼로그 — 폴링 실패 재시도 (T6 concern 2)', () => {
  it('5xx·네트워크로 멈췄으면 재시도 문구와 「다시 시도」 버튼을 준다', () => {
    renderDialog({
      preview: preview({ removedStatusKeys: ['done'], pendingIssueCounts: { done: 3 } }),
      draft: MIGRATION_DRAFT,
      migration: migrationMock({ pollFailed: true, pollRetryable: true }),
    })

    expect(screen.getByText(labels.migration.pollFailedRetryable)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: labels.migration.retryPoll })).toBeInTheDocument()
  })

  it('4xx 로 멈췄으면 재시도 버튼을 주지 않는다 — 다시 불러도 같은 답이라 거짓 희망이 된다', () => {
    renderDialog({
      preview: preview({ removedStatusKeys: ['done'], pendingIssueCounts: { done: 3 } }),
      draft: MIGRATION_DRAFT,
      migration: migrationMock({ pollFailed: true, pollRetryable: false }),
    })

    expect(screen.queryByRole('button', { name: labels.migration.retryPoll })).not.toBeInTheDocument()
    expect(screen.queryByText(labels.migration.pollFailedRetryable)).not.toBeInTheDocument()
  })

  it('폴링이 멀쩡하면 재시도 자리 자체가 없다', () => {
    renderDialog({
      preview: preview({ removedStatusKeys: ['done'], pendingIssueCounts: { done: 3 } }),
      draft: MIGRATION_DRAFT,
      migration: migrationMock(),
    })

    expect(screen.queryByRole('button', { name: labels.migration.retryPoll })).not.toBeInTheDocument()
  })
})
