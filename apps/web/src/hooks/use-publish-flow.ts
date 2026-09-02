// 발행 흐름 상태기계 — 저장 flush → 미리보기 → 발행 · 복원 · 폐기의 순서와 실패를 한 곳에 둔다
import * as React from 'react'
import { toast } from 'sonner'
import { WorkflowAdminApiError, WorkflowPublishMappingRequiredError } from '@/api/workflows-admin.http'
import { migrateStatuses } from '@/api/workflows-draft'
import type { PublishPreview, DraftDefinition, StatusMappingInput } from '@/api/workflows-draft.types'
import type { WorkflowView } from '@/api/workflows'
import type { BulkOperationResponse } from '@/api/bulk-operations'
import type { MigrationSelection } from '@/lib/workflow-migration'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'
import { notifyWorkflowAdminError, mapWorkflowAdminError, DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE } from './workflow-admin-error'
import { useWorkflowDetail } from './use-workflows-admin'
import { useBulkOperationPolling } from './use-bulk-operation'
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

// ─────────────────────────────────────────────────────────────────────────────
// 상태 이관 마법사 — 발행 다이얼로그의 한 단계 (FR-WF-07 D6b)
// ─────────────────────────────────────────────────────────────────────────────

/** `useMigrationWizard` 가 돌려주는 마법사 배선. */
export interface UseMigrationWizardResult {
  /** 이관 후보 계산의 기준이 되는 발행본. 아직 못 불러왔으면 null(로딩·에러) */
  published: DraftDefinition | null
  /** 발행본 조회 자체가 실패했는가 */
  publishedFailed: boolean
  /** 사라지는 상태 키 → 옮겨 갈 상태 키 */
  selection: MigrationSelection
  onSelectionChange: (removedKey: string, targetKey: string) => void
  /** 이관 시작 — 202 수신 뒤 폴링을 붙인다(F6) */
  onStartMigration: (mappings: StatusMappingInput[]) => void
  /** 이관 접수 요청 전송 중 */
  starting: boolean
  /** 접수 실패 사유. 서버 문장을 한국어로 옮긴 것 */
  startError: string | null
  /** 진행률 폴링 결과. 시작 전이면 null */
  operation: BulkOperationResponse | null
  /** 폴링이 에러로 멈췄는가 — 정지 조건 자체는 `useBulkOperationPolling` 이 갖고 있다 */
  pollFailed: boolean
}

/** `WorkflowView` 를 이관 후보 셀렉터(`lib/workflow-draft.ts`)가 받는 형태로 좁힌다. */
function toPublishedDefinition(view: WorkflowView): DraftDefinition {
  return { key: view.key, name: view.name, description: view.description, states: view.states, transitions: [] }
}

/** 이관 접수 하나를 감싼다 — 성공·실패 콜백으로 나눠 훅 쪽 setState 를 그대로 잇는다. */
async function submitMigration(
  workflowKey: string,
  baseVersion: number,
  mappings: StatusMappingInput[],
  onAccepted: (bulkOperationId: string) => void,
  onError: (message: string) => void,
): Promise<void> {
  try {
    const accepted = await migrateStatuses(workflowKey, baseVersion, mappings)
    onAccepted(accepted.bulkOperationId)
  } catch (error) {
    onError(
      error instanceof WorkflowAdminApiError
        ? mapWorkflowAdminError(error.errorCode, error.detail)
        : DEFAULT_WORKFLOW_ADMIN_ERROR_MESSAGE,
    )
  }
}

/**
 * 상태 이관 마법사의 살아 있는 배선 — 발행본 조회 · 도착지 선택 · 이관 접수 · 진행률 폴링.
 *
 * `usePublishFlow` 와 별도 훅인 이유. 이관은 `preview` 하나만으로 독립적으로 그릴 수 있는
 * 하위 흐름이고, 발행 다이얼로그의 `'none'|'publish'|'reset'|'discard'` 상태기계에 얹으면
 * 그 파일이 두 배로 커진다(리뷰 P3 taste call 과 같은 축). 마법사는 별도 다이얼로그가 아니라
 * **기존 발행 다이얼로그의 단계**라 `flowDialog` 는 이관 중에도 계속 `'publish'` 다.
 *
 * ### ★ 이관 완료가 발행을 자동으로 부르지 않는다(G-1)
 * 이 훅은 `operation` 을 반환만 한다 — 종단 상태를 감지해 `publish` 를 대신 호출하지 않는다.
 * 일부 실패가 섞이면 이어지는 발행이 반드시 409(`MappingRequired`)로 죽고, 그때 사용자는
 * 자기가 누르지 않은 조작의 실패를 보게 된다. 다시 누르는 것은 소비처(`PublishDialog`)의 몫이다.
 *
 * @param workflowKey 대상 워크플로우 키. 마법사가 필요 없으면 빈 문자열을 넘겨 발행본 조회를
 *   끈다 — `useWorkflowDetail` 은 빈 키에서 `enabled: false` 다.
 * @param preview 최신 미리보기. **참조가 바뀌면**(다이얼로그를 다시 열거나, 서버가 갱신된
 *   `pendingIssueCounts` 로 409 를 되돌려줄 때) 이전 세션의 선택·이관 상태를 지운다 — 안 지우면
 *   새 세션이 죽은 이관 id 의 진행률을 계속 든다.
 */
export function useMigrationWizard(
  workflowKey: string,
  preview: PublishPreview | null,
): UseMigrationWizardResult {
  const detail = useWorkflowDetail(workflowKey)
  const [selection, setSelection] = React.useState<MigrationSelection>({})
  const [operationId, setOperationId] = React.useState<string | null>(null)
  const [starting, setStarting] = React.useState(false)
  const [startError, setStartError] = React.useState<string | null>(null)

  React.useEffect(() => {
    setSelection({})
    setOperationId(null)
    setStartError(null)
  }, [preview])

  const poll = useBulkOperationPolling(operationId, operationId !== null)

  const onSelectionChange = React.useCallback((removedKey: string, targetKey: string) => {
    setSelection((current) => ({ ...current, [removedKey]: targetKey }))
  }, [])

  const onStartMigration = React.useCallback(
    (mappings: StatusMappingInput[]) => {
      if (preview === null) {
        return
      }
      setStarting(true)
      setStartError(null)
      void submitMigration(workflowKey, preview.baseVersion, mappings, setOperationId, setStartError).finally(() => {
        setStarting(false)
      })
    },
    [workflowKey, preview],
  )

  return {
    published: detail.data !== undefined ? toPublishedDefinition(detail.data) : null,
    publishedFailed: detail.isError,
    selection,
    onSelectionChange,
    onStartMigration,
    starting,
    startError,
    operation: poll.data ?? null,
    pollFailed: poll.isError,
  }
}
