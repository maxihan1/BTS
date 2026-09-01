// 초안 상태 표시줄 — 저장 상태와 발행·복원·폐기 진입점. 「편집 ≠ 배포」를 항상 보이게 한다
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import type { DraftSaveState } from '@/hooks/use-workflow-draft'

interface DraftStatusBarProps {
  saveState: DraftSaveState
  /** 저장 실패 사유. 서버 문장을 그대로 든다. */
  saveError: string | null
  /** 저장된 초안이 있는가. 없으면 폐기할 것도 없다. */
  hasDraft: boolean
  /** 「기본값으로 되돌리기」를 띄울지. 서버 판정(`origin=SEED` + YAML 실재)을 그대로 쓴다. */
  canResetToDefault: boolean
  /** 발행 흐름을 연다 */
  onPublish: () => void
  onReset: () => void
  onDiscard: () => void
  /** 발행 흐름이 도는 중 */
  busy: boolean
}

/** 저장 상태별 문구. 표에 두어 분기와 문구가 한 곳에서 대응한다. */
const SAVE_LABEL: Record<DraftSaveState, string> = {
  idle: labels.draft.idle,
  dirty: labels.draft.dirty,
  saving: labels.draft.saving,
  saved: labels.draft.saved,
  error: labels.draft.error,
}

/**
 * 초안 상태와 발행 진입점.
 *
 * ### 저장 실패면 발행을 잠근다
 * 저장이 안 된 초안을 발행하면 **서버에 있는 옛 초안**이 나간다. 화면에는 새 정의가 보이는데
 * 발행된 것은 다른 것이라, 관리자가 무엇을 발행했는지 알 수 없는 자리다.
 *
 * ### 「저장됨」을 「발행 가능」으로 읽히게 쓰지 않는다
 * `PUT /draft` 는 앵커를 검사하지 않고 `POST /publish` 만 검사한다. 그래서 저장은 계속
 * 성공하는데 발행만 영구 409 인 상태가 **정상 동작**이다. 그래서 저장 상태와 별개로
 * 「발행해야 반영된다」를 항상 띄운다.
 */
function DraftStatusBar({
  saveState,
  saveError,
  hasDraft,
  canResetToDefault,
  onPublish,
  onReset,
  onDiscard,
  busy,
}: DraftStatusBarProps): React.JSX.Element {
  const blockedBySave = saveState === 'error'

  return (
    <div
      aria-label={labels.draft.bar}
      role="group"
      className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
    >
      <div className="flex flex-col gap-1">
        <span className="text-sm" role="status">
          {SAVE_LABEL[saveState]}
        </span>
        {saveError !== null ? (
          <span role="alert" className="text-destructive text-sm">
            {saveError}
          </span>
        ) : null}
        {/* 「편집 ≠ 배포」가 이 화면의 핵심 계약이라 항상 보이는 자리에 둔다. */}
        <span className="text-muted-foreground text-xs">{labels.draft.unpublishedNotice}</span>
      </div>

      <div className="flex items-center gap-2">
        {/* 서버가 판정한 값을 그대로 쓴다 — 화면이 origin 규칙을 복제하지 않는다. */}
        {canResetToDefault ? (
          <Button variant="ghost" size="sm" onClick={onReset} disabled={busy}>
            {labels.draft.reset}
          </Button>
        ) : null}
        {hasDraft ? (
          <Button variant="ghost" size="sm" onClick={onDiscard} disabled={busy}>
            {labels.draft.discard}
          </Button>
        ) : null}
        <Button size="sm" onClick={onPublish} disabled={busy || blockedBySave}>
          {labels.draft.publish}
        </Button>
      </div>
    </div>
  )
}

export { DraftStatusBar }
export type { DraftStatusBarProps }
