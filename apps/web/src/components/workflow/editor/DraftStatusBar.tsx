// 초안 상태 표시줄 — 저장 상태와 발행·복원·폐기 진입점. 「편집 ≠ 배포」를 항상 보이게 한다
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import type { DraftSaveState } from '@/hooks/use-workflow-draft'

/**
 * 진행 중인 이관의 처리 건수.
 *
 * 폴링 응답(`BulkOperationResponse`)을 그대로 받지 않는다 — 표시줄은 폴링도 일괄 작업도 모르는
 * 자리이고, 여기서 아는 것은 「몇 건 중 몇 건이 끝났나」뿐이면 충분하다.
 */
interface MigrationProgress {
  /** 이미 처리한 이슈 수 */
  readonly processed: number
  /** 전체 대상 이슈 수. 폴링 첫 응답 전에는 0 이고, 그때는 건수를 감춘다 */
  readonly total: number
}

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
  /**
   * 진행 중인 이관. null 이면 진행 표시를 그리지 않는다.
   *
   * ★ **선택 prop 이다.** 이관을 모르는 소비처(`DraftStatusBar.test.tsx`)가 그대로 돌아야 한다.
   */
  migration?: MigrationProgress | null
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
  migration = null,
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

        {/* ★ 이관 진행은 **다이얼로그 밖에도** 남는다(G-2). 마법사가 유일한 표시면 새로고침
            한 번에 「서버 작업은 도는데 화면만 모르는」 자리가 생긴다 — 그때 사용자가 보는
            것은 회색 버튼뿐이라 이관이 실패한 줄 안다. */}
        {migration !== null ? (
          <div role="status" className="mt-1 flex flex-col gap-0.5">
            <span className="text-sm">
              <span className="font-medium">{labels.migration.inProgress}</span>
              {/* 첫 폴 응답 전에는 건수를 모른다 — 0/0 을 그리면 「하나도 못 옮겼다」로 읽힌다. */}
              {migration.total > 0 ? (
                <span className="text-muted-foreground ml-2">
                  {`${String(migration.processed)} / ${String(migration.total)}`}
                </span>
              ) : null}
            </span>
            <span className="text-muted-foreground text-xs">{labels.migration.inProgressNotice}</span>
          </div>
        ) : null}
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
export type { DraftStatusBarProps, MigrationProgress }
