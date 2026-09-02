// 발행 흐름 상태기계 — 저장 flush → 미리보기 → 발행 · 복원 · 폐기의 순서와 실패를 한 곳에 둔다
import * as React from 'react'
import { toast } from 'sonner'
import { WorkflowAdminApiError, WorkflowPublishMappingRequiredError } from '@/api/workflows-admin.http'
import type { PublishPreview } from '@/api/workflows-draft.types'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import { notifyWorkflowAdminError } from './workflow-admin-error'
import type { UseWorkflowPublishResult } from './use-workflow-publish'
import type { UseWorkflowDraftResult } from './use-workflow-draft'

/** 지금 열려 있는 다이얼로그. 한 번에 하나뿐이다. */
export type PublishFlowDialog = 'none' | 'publish' | 'reset' | 'discard'

export interface UsePublishFlowResult {
  dialog: PublishFlowDialog
  /** 다이얼로그를 닫는다. */
  close: () => void
  /** 발행 미리보기 결과. `publish` 다이얼로그가 열려 있는 동안만 유효하다. */
  preview: PublishPreview | null
  /** 낙관적 락 충돌이 확정됐는가. 배너로 알리고 폐기만 남는다. */
  conflicted: boolean
  busy: boolean
  /** 발행 흐름을 연다 — 저장 flush 후 미리보기. */
  startPublish: () => void
  /** 미리보기 결과를 확인한 뒤 실제 발행. */
  confirmPublish: () => void
  openReset: () => void
  confirmReset: () => void
  openDiscard: () => void
  confirmDiscard: () => void
}

/**
 * 발행 경로의 순서와 실패를 한 곳에 모은다.
 *
 * ### ★ 발행은 저장부터 한다
 * `preview`·`publish` 는 **저장된 초안**을 대상으로 하고, 초안이 없으면 404 가 아니라
 * **400**(「발행할 초안이 없다」)이다. `GET /draft` 는 초안이 없어도 발행본을 돌려주므로
 * 화면은 멀쩡해 보이는데 발행에서만 터진다 — 그 자리를 `flush()` 가 닫는다.
 *
 * ### 충돌은 배너로 남는다
 * `WORKFLOW_VERSION_CONFLICT` 는 재시도로 풀리지 않는다(서버 앵커는 write-once). 토스트로
 * 알리면 관리자가 무한히 재시도하므로 상태로 들고 있다가 배너를 띄운다.
 */
export function usePublishFlow(
  draft: UseWorkflowDraftResult,
  publish: UseWorkflowPublishResult,
): UsePublishFlowResult {
  const [dialog, setDialog] = React.useState<PublishFlowDialog>('none')
  const [preview, setPreview] = React.useState<PublishPreview | null>(null)
  const [conflicted, setConflicted] = React.useState(false)
  const [busy, setBusy] = React.useState(false)

  const close = React.useCallback(() => {
    setDialog('none')
  }, [])

  /** 실패를 한 곳에서 옮긴다. 충돌만 배너로 승격하고 나머지는 토스트다. */
  const handleError = React.useCallback((error: unknown) => {
    if (error instanceof WorkflowAdminApiError && error.errorCode === 'WORKFLOW_VERSION_CONFLICT') {
      setConflicted(true)
      setDialog('none')
      return
    }
    // 이관 필요 409 는 미리보기 단계에서 이미 걸러진다. 여기 오면 preview 이후 이슈가
    // 새로 들어온 경우이므로, 건수를 살려 다시 미리보기 화면으로 돌린다.
    if (error instanceof WorkflowPublishMappingRequiredError) {
      setPreview((current) =>
        current === null ? null : { ...current, pendingIssueCounts: error.pendingIssueCounts },
      )
      setDialog('publish')
      return
    }
    notifyWorkflowAdminError(error)
  }, [])

  /** 흐름 하나를 감싼다 — busy 토글과 실패 처리를 반복해 적지 않는다. */
  const run = React.useCallback(
    async (action: () => Promise<void>) => {
      setBusy(true)
      try {
        await action()
      } catch (error) {
        handleError(error)
      } finally {
        setBusy(false)
      }
    },
    [handleError],
  )

  const startPublish = React.useCallback(() => {
    void run(async () => {
      // ★ 저장이 먼저다. 초안이 없으면 미리보기가 400 이다.
      await draft.flush()
      const result = await publish.preview()
      setPreview(result)
      setDialog('publish')
    })
  }, [run, draft, publish])

  const confirmPublish = React.useCallback(() => {
    void run(async () => {
      const result = await publish.publish(draft.state.baseVersion)
      setDialog('none')
      setPreview(null)
      toast.success(`${labels.publish.successPrefix} (${String(result.versionNo)})`)
    })
  }, [run, publish, draft.state.baseVersion])

  const openReset = React.useCallback(() => {
    setDialog('reset')
  }, [])

  const confirmReset = React.useCallback(() => {
    void run(async () => {
      const restored = await publish.resetToDefault(draft.state.baseVersion)
      // 복원 결과를 로컬 상태에 실어야 화면이 그 정의를 그린다. 서버가 새 앵커도 함께 준다.
      draft.dispatch({
        type: 'resetToDefault',
        definition: restored.definition,
        baseVersion: restored.baseVersion,
        canResetToDefault: restored.canResetToDefault,
      })
      setDialog('none')
    })
  }, [run, publish, draft])

  const openDiscard = React.useCallback(() => {
    setDialog('discard')
  }, [])

  const confirmDiscard = React.useCallback(() => {
    void run(async () => {
      await publish.discard()
      setConflicted(false)
      setDialog('none')
    })
  }, [run, publish])

  return {
    dialog,
    close,
    preview,
    conflicted,
    busy,
    startPublish,
    confirmPublish,
    openReset,
    confirmReset,
    openDiscard,
    confirmDiscard,
  }
}
