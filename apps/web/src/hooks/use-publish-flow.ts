// 발행 흐름 상태기계 — 저장 flush → 미리보기 → 발행 · 복원 · 폐기의 순서와 실패를 한 곳에 둔다
import * as React from 'react'
import { toast } from 'sonner'
import { ZodError } from 'zod'
import { ApiError } from '@/api/client'
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
  /** 진행률 폴링 결과. 아직 시작하지 않았으면 null */
  operation: BulkOperationResponse | null
  /**
   * 진행률 파생값(0~1). `totalCount` 가 아직 0 이면 나눌 수 없어 null 이다.
   *
   * 폴링 훅이 이미 계산한 값을 그대로 실어 나른다 — 같은 두 숫자(`processedCount`·`totalCount`)
   * 에서 진행률을 각자 뽑는 자리가 늘수록 반올림과 0 나눗셈 처리가 서로 어긋난다.
   */
  progressRatio: number | null
  /**
   * 폴링이 에러로 **멈췄는가** — 정지 조건 자체는 `useBulkOperationPolling` 이 갖고 있다.
   *
   * 그 훅은 재시도 예산(`MAX_ERROR_RETRIES` 연속)을 다 쓴 뒤에야 쿼리를 `error` 로 넘기므로,
   * 스쳐 간 5xx 한 번으로는 켜지지 않는다 — 곧 알아서 재시도할 상황에 에러 배너를 띄우지
   * 않기 위해서다. 켜졌다면 이 id 로는 더 이상 진행률이 갱신되지 않는다.
   */
  pollFailed: boolean
  /**
   * `pollFailed` 인 상태에서, 다시 시도가 의미 있는 에러인가(concern 2).
   *
   * 4xx·`ZodError` 는 다시 불러도 같은 응답이 온다 — 그때 재시도 버튼을 주면 거짓 희망이다.
   * 5xx·네트워크로 재시도 예산까지 쓰고 멈췄을 때만 `true` 다.
   */
  pollRetryable: boolean
  /** `pollRetryable` 일 때만 의미 있는 재시도 — 폴링을 한 번 더 부른다 */
  retryPoll: () => void
  /**
   * 폴링 중이라 초안 폐기를 막아야 하는가(G-3).
   *
   * 폐기하면 이어지는 발행이 404 이고 이미 옮겨진 이슈는 되돌아오지 않는다(E5). `false` 가
   * 되는 경우는 셋이다 — 이관을 시작하지 않았거나(`operationId` 없음) · 종료 상태
   * (`COMPLETED`·`FAILED`)에 도달했거나 · **폴링이 실패로 멈췄을 때**(`pollFailed`).
   *
   * ★ 마지막 조건이 없으면 편집기가 잠긴다. 404·403 으로 폴링이 죽으면 상태를 영영 못 받아
   * 종료 판정이 계속 거짓이고, 소비처(`WorkflowEditorPage` → `DraftStatusBar`)가 이 값을
   * `busy` 로 빌려 써 기본값 복원·초안 폐기·발행을 **전부** 비활성으로 만든다. 초안 폐기는
   * 409 충돌 상태의 유일한 출구이므로 그 자리를 막으면 나갈 길이 없다.
   */
  discardDisabled: boolean
}

/** `WorkflowView` 를 이관 후보 셀렉터(`lib/workflow-draft.ts`)가 받는 형태로 좁힌다. */
function toPublishedDefinition(view: WorkflowView): DraftDefinition {
  return { key: view.key, name: view.name, description: view.description, states: view.states, transitions: [] }
}

// ─────────────────────────────────────────────────────────────────────────────
// 이관 진행률 URL 보관(G-2) — 새로고침·링크 공유에도 진행률이 살아남는다
// ─────────────────────────────────────────────────────────────────────────────

/** URL 쿼리 키. 상수 하나로 묶어 읽기·쓰기가 오탈자로 어긋나는 것을 막는다. */
const MIGRATION_QUERY_KEY = 'migration'

/**
 * URL 쿼리에서 이관 작업 id 를 읽는다.
 *
 * TanStack Router 의 `useSearch`/`useNavigate` 대신 raw `window.location`/`history` 를 쓴다 —
 * 이 훅은 라우터 컨텍스트 없이 렌더하는 테스트(`WorkflowEditorPage.test.tsx`)에서도 그대로
 * 동작해야 한다. `routes/login.tsx:18` 이 같은 이유로 같은 패턴(`window.location.search` 직접
 * 파싱)을 쓴다. `@tanstack/history` 는 `window.history.pushState/replaceState` 를 몽키패치해
 * 두므로, 여기서 raw 로 호출해도 실제 라우터가 떠 있을 때는 그 내부 상태와 어긋나지 않는다.
 */
function readMigrationIdFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get(MIGRATION_QUERY_KEY)
}

/**
 * URL 쿼리의 이관 작업 id 를 갈아 끼운다. 다른 쿼리 키는 건드리지 않는다.
 *
 * `replaceState` 를 쓴다 — 접수·종료마다 히스토리를 쌓으면 뒤로가기 한 번으로 편집기를 못
 * 벗어난다(`routes/issues.index.tsx` 의 `moveCursorTo` 가 같은 이유로 같은 선택을 했다).
 *
 * @param id 새 이관 작업 id. null 이면 쿼리에서 제거한다(종료 상태 정리)
 */
function writeMigrationIdToUrl(id: string | null): void {
  const params = new URLSearchParams(window.location.search)
  if (id === null) {
    params.delete(MIGRATION_QUERY_KEY)
  } else {
    params.set(MIGRATION_QUERY_KEY, id)
  }
  const query = params.toString()
  const url = `${window.location.pathname}${query.length > 0 ? `?${query}` : ''}`
  window.history.replaceState(null, '', url)
}

/** 이관이 끝난 상태인가 — `useBulkOperationPolling` 의 정지 조건(TERMINAL_STATUSES)과 같다. */
function isMigrationSettled(status: BulkOperationResponse['status'] | undefined): boolean {
  return status === 'COMPLETED' || status === 'FAILED'
}

/**
 * 폴링이 재시도 예산을 다 쓰고 멈췄을 때, 다시 시도가 의미 있는 에러인가(concern 2).
 *
 * 4xx·`ZodError` 는 다시 불러도 같은 응답이 온다(`useBulkOperationPolling.shouldRetryPoll` 의
 * 즉시 정지 조건과 같은 판단) — 그때 재시도 버튼을 주면 거짓 희망이다. 5xx·네트워크만 재시도할
 * 가치가 있다.
 */
function isRetryablePollError(error: unknown): boolean {
  if (error instanceof ApiError) {
    return error.status < 400 || error.status >= 500
  }
  return !(error instanceof ZodError)
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
 * ### ★ 이관이 필요할 때만 발행본을 조회한다(concern 1)
 * `preview` 가 없거나 `pendingIssueCounts` 가 비어 있으면(= `PublishDialog` 가 마법사 자체를
 * 그리지 않는 경우) `useWorkflowDetail` 을 **스스로 끈다** — 호출부가 편집기 마운트마다 이
 * 훅을 불러도, 이관이 필요 없는 대다수 경우엔 추가 GET 이 나가지 않는다.
 *
 * @param workflowKey 대상 워크플로우 키. 빈 문자열을 넘기면(워크플로우 키를 아직 모르는 등)
 *   `needsMigration` 여부와 무관하게 발행본 조회가 꺼진다 — `useWorkflowDetail` 이 빈 키에서
 *   `enabled: false` 다.
 * @param preview 최신 미리보기. **참조가 바뀌면**(다이얼로그를 다시 열거나, 서버가 갱신된
 *   `pendingIssueCounts` 로 409 를 되돌려줄 때) 이전 세션의 선택·이관 상태를 지운다 — 안 지우면
 *   새 세션이 죽은 이관 id 의 진행률을 계속 든다. 단, **마운트 직후 1회는 지우지 않는다** —
 *   URL 에서 막 복원한 `operationId`(G-2)를 스스로 지워 버리면 안 되기 때문이다.
 */
export function useMigrationWizard(
  workflowKey: string,
  preview: PublishPreview | null,
): UseMigrationWizardResult {
  const needsMigration = preview !== null && Object.keys(preview.pendingIssueCounts).length > 0
  const detail = useWorkflowDetail(needsMigration ? workflowKey : '')
  const [selection, setSelection] = React.useState<MigrationSelection>({})
  // URL 에 남은 이관 id 를 그대로 이어받는다(G-2) — 새로고침·링크 공유로 재진입해도 그 id 로
  // 폴링이 다시 붙는다.
  const [operationId, setOperationId] = React.useState<string | null>(() => readMigrationIdFromUrl())
  const [starting, setStarting] = React.useState(false)
  const [startError, setStartError] = React.useState<string | null>(null)

  const skippedInitialResetRef = React.useRef(false)
  React.useEffect(() => {
    // 마운트 직후 1회는 건너뛴다 — 안 그러면 위에서 URL 로 복원한 operationId 를 곧바로
    // 지워 버린다(G-2). 이후 preview 참조가 실제로 바뀔 때만(다이얼로그 재진입 등) 리셋한다.
    if (!skippedInitialResetRef.current) {
      skippedInitialResetRef.current = true
      return
    }
    setSelection({})
    setOperationId(null)
    setStartError(null)
    // 지우는 operationId 가 URL 에도 남아 있을 수 있다(예: 폴링 중 다이얼로그를 닫았다 다시
    // 열어 새 preview 를 받은 경우) — 메모리 상태와 URL 을 같이 정리한다(G-2).
    writeMigrationIdToUrl(null)
  }, [preview])

  const poll = useBulkOperationPolling(operationId, operationId !== null)
  const { refetch: refetchPoll } = poll
  const operationStatus = poll.data?.status
  // 폴링이 멈췄는가. `useBulkOperationPolling` 은 재시도 예산을 다 쓴 뒤에만 `error` 로 넘어가므로
  // `isError` 는 「이 id 로는 더 이상 상태를 받지 못한다」와 같은 말이다.
  const pollStopped = poll.isError
  // ★ 멈춘 이유를 가른다. 5xx·네트워크는 「서버가 일시적으로 눈이 멀었다」라 이관 자체는 살아
  // 있을 수 있고, 4xx·`ZodError` 는 이 id 로 다시 물어도 같은 답이 온다(죽은 id).
  const pollRetryable = pollStopped && isRetryablePollError(poll.error)

  // 종료 상태(COMPLETED·FAILED)에 도달했거나 **되살아날 가망이 없는** 정지면 URL 을 정리한다(G-2).
  // 끝난 작업의 id 가 주소에 남으면 다음 진입에서 이미 끝난 진행률을 다시 그리고, **죽은 id
  // (404·403)는 새로고침할 때마다 같은 막다른 상태로 복귀시킨다** — 종료 상태만 정리하면 그쪽을
  // 못 치운다.
  //
  // ★ 5xx·네트워크 정지(`pollRetryable`)에서는 id 를 **남긴다.** 그 이관은 서버에서 계속 돌고
  //   있을 수 있는데 주소에서까지 지우면 새로고침이 살아 있는 작업을 표시줄도 잠금도 없이 잃는다
  //   — 추적 수단이 메모리뿐인 상태가 된다. 남겨 두면 재진입한 새 쿼리가 회복할 기회를 갖는다.
  //   잠금(`discardDisabled`)은 그와 별개로 **넓게 푼다** — 좁히면 새로고침 뒤 「다시 시도」
  //   버튼에 닿을 수 없어 편집기가 영구히 잠긴다(직전 BLOCKER).
  React.useEffect(() => {
    if (isMigrationSettled(operationStatus) || (pollStopped && !pollRetryable)) {
      writeMigrationIdToUrl(null)
    }
  }, [operationStatus, pollStopped, pollRetryable])

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
      void submitMigration(
        workflowKey,
        preview.baseVersion,
        mappings,
        (id) => {
          setOperationId(id)
          // 접수 즉시 URL 에 싣는다(G-2) — 폴링 첫 응답을 기다리지 않는다.
          writeMigrationIdToUrl(id)
        },
        setStartError,
      ).finally(() => {
        setStarting(false)
      })
    },
    [workflowKey, preview],
  )

  const retryPoll = React.useCallback(() => {
    void refetchPoll()
  }, [refetchPoll])

  return {
    published: detail.data !== undefined ? toPublishedDefinition(detail.data) : null,
    publishedFailed: detail.isError,
    selection,
    onSelectionChange,
    onStartMigration,
    starting,
    startError,
    operation: poll.data ?? null,
    progressRatio: poll.progressRatio,
    pollFailed: pollStopped,
    pollRetryable,
    retryPoll,
    discardDisabled:
      operationId !== null && !isMigrationSettled(operationStatus) && !pollStopped,
  }
}
