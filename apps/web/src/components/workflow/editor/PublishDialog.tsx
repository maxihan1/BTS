// 발행 다이얼로그 — 무엇이 사라지는지 보여주고, 이슈가 남으면 발행 대신 이관으로 보낸다
import * as React from 'react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import { workflowEditorLabels } from '@/i18n/workflow-editor-labels'
import { DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE } from '@/hooks/workflow-admin-error'
import type { UseMigrationWizardResult } from '@/hooks/use-publish-flow'
import type { EditableDraft } from '@/lib/workflow-draft'
import type { PublishPreview } from '@/api/workflows-draft.types'
import { MigrationWizard } from './MigrationWizard'

interface PublishDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** `POST /publish/preview` 결과. 열 때마다 새로 받은 값이어야 한다 — 캐시하면 거짓이 된다. */
  preview: PublishPreview
  /** 상태 키 → 이름. 키를 그대로 그리면 사용자가 `in_progress` 를 읽는다. */
  stateNames: Record<string, string>
  /** 로컬에서 아는 발행 차단 사유. 없으면 null. */
  blockReason: string | null
  /** 발행 실행 */
  onPublish: () => void
  publishing: boolean
  /**
   * 지금 편집 중인 초안 — 이관 마법사가 도착지 후보를 계산하는 데 쓴다.
   *
   * ★ **선택 prop 이다.** 옛 소비처(`PublishDialog.test.tsx`)는 이 값을 안 주고, 그때는
   * 마법사 대신 이전의 안내 문구로 대체한다. 실제 화면(`WorkflowEditorDialogs`)은 항상 준다.
   */
  draft?: EditableDraft
  /** 이관 마법사의 살아 있는 배선(`useMigrationWizard`). `draft` 와 짝으로만 쓴다. */
  migration?: UseMigrationWizardResult
}

interface MigrationSectionProps {
  readonly draft: EditableDraft | undefined
  readonly migration: UseMigrationWizardResult | undefined
  readonly pendingIssueCounts: Record<string, number>
}

/**
 * 이관 마법사 자리. `draft`·`migration` 이 없으면 옛 안내 문구로 대체한다 — 상태 3종(로딩·
 * 에러·본문)은 발행본 조회와 폴링 각각을 따로 본다.
 */
function MigrationSection({ draft, migration, pendingIssueCounts }: MigrationSectionProps): React.JSX.Element {
  if (draft === undefined || migration === undefined) {
    return (
      <p role="status" className="text-sm">
        {labels.migration.intro}
      </p>
    )
  }
  if (migration.published === null) {
    return migration.publishedFailed ? (
      <p role="alert" className="text-destructive text-sm">
        {workflowEditorLabels.editor.loadFailed}
      </p>
    ) : (
      <Skeleton className="h-24 w-full" />
    )
  }
  return (
    <div className="flex flex-col gap-2">
      {migration.pollFailed ? (
        <p role="alert" className="text-destructive text-sm">
          {DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE}
        </p>
      ) : null}
      {migration.startError !== null ? (
        <p role="alert" className="text-destructive text-sm">
          {migration.startError}
        </p>
      ) : null}
      <MigrationWizard
        draft={draft}
        published={migration.published}
        pendingIssueCounts={pendingIssueCounts}
        selection={migration.selection}
        onSelectionChange={migration.onSelectionChange}
        onStartMigration={migration.onStartMigration}
        starting={migration.starting}
        operation={migration.operation}
      />
    </div>
  )
}

/**
 * 발행 전 마지막 확인.
 *
 * ### 이관이 필요하면 발행 버튼을 **주지 않는다**
 * 빠지는 상태에 이슈가 남아 있으면 서버가 409 로 막는다. 버튼을 두고 눌러 보게 하면 그 409 를
 * 사용자가 받아야 알게 되므로, 미리보기가 알려 준 사실을 화면이 먼저 반영한다. 이관이 필요하면
 * 이 다이얼로그 **안에서** 마법사 단계로 들어간다(별도 다이얼로그가 아니다 — 즉사 계약 §2).
 *
 * ### ★ 이관 완료가 발행 버튼을 자동으로 되살리지 않는다(G-1)
 * 이관이 `COMPLETED` 이고 `failedCount === 0` 이어야 발행 버튼이 다시 보인다. 이관이 끝나도
 * **사용자가 다시 눌러야** 발행이 나간다 — 자동으로 이어 부르면 일부 실패가 섞였을 때 사용자가
 * 자기가 누르지 않은 조작의 실패(409)를 보게 된다.
 */
function PublishDialog({
  open,
  onOpenChange,
  preview,
  stateNames,
  blockReason,
  onPublish,
  publishing,
  draft,
  migration,
}: PublishDialogProps): React.JSX.Element {
  const removed = preview.removedStatusKeys
  const pending = preview.pendingIssueCounts
  const needsMigration = Object.keys(pending).length > 0
  // 이관이 COMPLETED · 실패 0 이어야 발행 버튼이 되살아난다(G-1 · E1). migration 이 없으면(옛
  // 소비처) 이 조건은 항상 거짓이라 needsMigration 만으로 종전 동작이 그대로 남는다.
  const migrationSucceeded =
    migration?.operation?.status === 'COMPLETED' && migration.operation.failedCount === 0
  const showPublishButton = !needsMigration || migrationSucceeded

  /** 키를 사람이 읽는 이름으로. 카탈로그에 없으면 키를 그대로 쓴다(숨기면 더 헷갈린다). */
  const nameOf = (key: string): string => stateNames[key] ?? key

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          {/* ★ 제목은 흐름이 어느 단계든 **그대로**다. 접근성 이름이 단계마다 바뀌면
              E2E 셀렉터가 굳지 못한다. */}
          <DialogTitle>{labels.publish.dialogTitle}</DialogTitle>
          <DialogDescription>{labels.publish.dialogDescription}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <section aria-label={labels.publish.removedHeading}>
            <h3 className="text-sm font-medium">{labels.publish.removedHeading}</h3>
            {removed.length === 0 ? (
              <p className="text-muted-foreground mt-1 text-sm">{labels.publish.noRemoved}</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {removed.map((key) => {
                  const count = pending[key] ?? 0
                  return (
                    <li key={key} className="flex justify-between gap-4">
                      <span>{nameOf(key)}</span>
                      <span className="text-muted-foreground">
                        {/* 「이슈 없음」을 명시한다 — 안 그리면 「못 셌다」와 구별이 안 된다. */}
                        {count > 0 ? `${String(count)}${labels.publish.issueCountSuffix}` : labels.publish.noIssues}
                      </span>
                    </li>
                  )
                })}
              </ul>
            )}
          </section>

          {removed.length > 0 ? (
            <p role="status" className="text-muted-foreground text-sm">
              {labels.publish.boardWarning}
            </p>
          ) : null}

          {needsMigration ? (
            <MigrationSection draft={draft} migration={migration} pendingIssueCounts={pending} />
          ) : null}

          {blockReason !== null ? (
            <p role="alert" className="text-destructive text-sm">
              {blockReason}
            </p>
          ) : null}

          {removed.length > 0 && !needsMigration ? (
            <p className="text-muted-foreground text-sm">{labels.publish.metaChangeNotice}</p>
          ) : null}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {labels.common.cancel}
          </Button>
          {/* 이관이 필요하면 이관이 성공적으로 끝나기 전까지 발행 버튼 자체를 두지 않는다 —
              눌러 보고 409 를 받게 하지 않는다(G-1 · E1). */}
          {showPublishButton ? (
            <Button disabled={publishing || blockReason !== null} onClick={onPublish}>
              {labels.publish.confirm}
            </Button>
          ) : null}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { PublishDialog }
export type { PublishDialogProps }
