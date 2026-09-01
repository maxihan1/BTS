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
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import type { PublishPreview } from '@/api/workflows-draft.types'

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
}

/**
 * 발행 전 마지막 확인.
 *
 * ### 이관이 필요하면 발행 버튼을 **주지 않는다**
 * 빠지는 상태에 이슈가 남아 있으면 서버가 409 로 막는다. 버튼을 두고 눌러 보게 하면 그 409 를
 * 사용자가 받아야 알게 되므로, 미리보기가 알려 준 사실을 화면이 먼저 반영한다. 실제 이관
 * 마법사는 로드맵 PR 10b 몫이라 여기서는 **무엇이 막고 있는지**까지 보여준다.
 */
function PublishDialog({
  open,
  onOpenChange,
  preview,
  stateNames,
  blockReason,
  onPublish,
  publishing,
}: PublishDialogProps): React.JSX.Element {
  const removed = preview.removedStatusKeys
  const pending = preview.pendingIssueCounts
  const needsMigration = Object.keys(pending).length > 0

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
            <p role="status" className="text-sm">
              {labels.migration.intro}
            </p>
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
          {/* 이관이 필요하면 발행 버튼 자체를 두지 않는다 — 눌러 보고 409 를 받게 하지 않는다. */}
          {needsMigration ? null : (
            <Button disabled={publishing || blockReason !== null} onClick={onPublish}>
              {labels.publish.confirm}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { PublishDialog }
export type { PublishDialogProps }
