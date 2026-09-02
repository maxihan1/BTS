// 초안 로드·로컬 편집·디바운스 자동저장 훅 — 낙관적 락 앵커를 고정해 들고 있는다
import * as React from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getDraft, saveDraft } from '@/api/workflows-draft'
import { WorkflowAdminApiError } from '@/api/workflows-admin.http'
import type { DraftResponse } from '@/api/workflows-draft.types'
import {
  draftReducer,
  initialDraftState,
  toWireDefinition,
  type DraftAction,
  type DraftEditorState,
} from '@/lib/workflow-draft'

/**
 * 자동저장 디바운스(ms).
 *
 * 타이핑 한 번마다 보내면 초안 저장이 발행 검증 전량을 태우는 무거운 경로라 부담이 크고,
 * 너무 길면 「목록으로」를 눌러 나갈 때 잃을 편집이 늘어난다. 테스트가 이 상수를 그대로
 * 쓰므로 값을 바꿔도 테스트가 따라온다 — 숫자를 두 곳에 적지 않는다.
 */
export const DRAFT_AUTOSAVE_DELAY_MS = 800

/** 자동저장 진행 상태. `error` 는 발행을 잠근다 — 저장 안 된 초안을 발행하면 옛 초안이 나간다. */
export type DraftSaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'

/** 초안 쿼리 키. 편집 중에는 자동 재조회를 막는다(앵커가 흔들린다). */
export const WORKFLOW_DRAFT_KEY = (key: string) => ['workflows', 'draft', key] as const

/**
 * 「이 서버 상태를 이미 리듀서에 실었는가」를 가리는 서명.
 *
 * 한 곳에 두는 이유 — 로드 경로와 자기 저장 경로가 **같은 규칙**으로 계산해야 한다.
 * 두 곳에서 따로 만들면 자기 저장이 쓴 캐시를 로드 경로가 「새 상태」로 오인해 다시 싣고,
 * 방금 「저장됨」이던 표시가 곧바로 사라진다.
 */
function signatureOf(key: string, anchor: number | undefined, exists: boolean | undefined): string {
  return `${key}:${String(anchor)}:${String(exists)}`
}

export interface UseWorkflowDraftResult {
  /** 리듀서 상태 — 정의·앵커·revision */
  state: DraftEditorState
  /** 편집 액션을 보낸다. 자동저장이 뒤따른다. */
  dispatch: React.Dispatch<DraftAction>
  /** 초안을 아직 못 읽었는가 */
  isLoading: boolean
  /** 초안 조회 자체가 실패했는가 */
  loadError: unknown
  saveState: DraftSaveState
  /** 저장 실패 사유. 서버 문장을 그대로 든다. */
  saveError: string | null
  /**
   * 디바운스를 기다리지 않고 지금 저장한다.
   *
   * 발행·이관은 **저장된 초안**을 대상으로 하므로 그 전에 반드시 불러야 한다. 초안이 없으면
   * 미리보기가 404 가 아니라 400(「발행할 초안이 없다」)이다.
   */
  flush: () => Promise<void>
}

/**
 * 워크플로우 초안을 읽고, 로컬에서 편집하고, 디바운스로 저장한다.
 *
 * ### ★ 앵커는 한 번 잡고 바꾸지 않는다
 * `baseVersion` 은 「편집기가 무엇을 보고 있었는가」이고 서버는 그것을 재구성할 수 없다.
 * 저장 응답이나 재조회로 갱신하면, A 가 초안을 뜨고 → B 가 발행하고 → A 가 저장하는 순서에서
 * A 의 앵커가 새 버전으로 올라가 **A 의 발행이 B 의 변경을 조용히 덮어쓴다**.
 *
 * 그래서 이 훅은 초안 쿼리를 `staleTime: Infinity` 로 두고 편집 중 재조회를 막으며, 앵커를
 * 바꾸는 액션은 `loadFromServer`·`resetToDefault` 둘뿐이다. 미리보기의 `currentVersion` 은
 * 이 훅 밖(`use-workflow-publish`)에 있어 **코드상 닿지 않는다**.
 */
export function useWorkflowDraft(key: string): UseWorkflowDraftResult {
  const [state, dispatch] = React.useReducer(draftReducer, initialDraftState)
  const client = useQueryClient()
  const [saveState, setSaveState] = React.useState<DraftSaveState>('idle')
  const [saveError, setSaveError] = React.useState<string | null>(null)

  const query = useQuery<DraftResponse>({
    queryKey: WORKFLOW_DRAFT_KEY(key),
    queryFn: () => getDraft(key),
    enabled: key.length > 0,
    // ★ 편집 중 재조회가 돌면 아래 effect 가 loadFromServer 를 다시 쏘아 편집이 통째로
    //   날아가고 앵커도 갈린다. 초안은 이 화면이 유일한 편집자다.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
  })

  const loaded = query.data

  /**
   * 서버 응답을 리듀서에 싣는 조건.
   *
   * ★ **`key` 만 보면 안 된다.** 한 편집 세션에서 key 는 절대 안 바뀌므로, 발행이
   * `workflows.version` 을 올리고 초안 행을 지운 뒤 재조회가 새 판을 받아도 리듀서가
   * **죽은 앵커를 계속 들고 있다.** 그 상태의 다음 편집은 과거 앵커로 초안을 만들고
   * (`requireAnchorNotAhead` 는 미래만 막는다) 그 발행은 서버 CAS
   * (`bumpVersionIfMatches` 의 `WHERE VERSION = ?`)에 0 rows 로 걸려 **영구 409** 다.
   * 화면을 떠났다 와야만 낫는다 — 「초안 폐기」라는 문서화된 출구까지 같은 이유로 막힌다.
   *
   * ★★ 그렇다고 응답이 올 때마다 실으면 편집 중 재조회가 편집을 통째로 덮는다. 그래서
   * **서버에 안 보낸 편집이 없을 때만** 싣는다.
   *
   * 판정에 `revision` 을 쓰면 안 된다 — 발행 직후에도 그 값은 0 이 아니라서(편집을 했으니까)
   * 가드가 영영 닫힌 채로 남는다. `saveState` 가 그 사실을 정확히 든다.
   * `saved`·`idle` 은 「보낼 것이 없다」이고 `dirty`·`saving`·`error` 는 「아직 있다」다.
   */
  const loadedKey = loaded?.definition.key
  const loadedAnchor = loaded?.baseVersion
  const loadedExists = loaded?.exists
  const hasPendingEdits = saveState === 'dirty' || saveState === 'saving' || saveState === 'error'

  /**
   * 마지막으로 리듀서에 실은 서버 상태.
   *
   * ★ 자동저장이 성공하면 캐시를 자기가 쓴 내용으로 갱신하는데(아래 `persist`), 그 쓰기가
   * 이 effect 를 다시 태우면 방금 「저장됨」이던 상태가 `idle` 로 되돌아간다. 같은 상태를
   * 두 번 싣지 않게 기록해 두고 비교한다 — 서버가 정말 다른 판을 줄 때만 다시 싣는다.
   */
  const appliedRef = React.useRef<string | null>(null)

  React.useEffect(() => {
    if (loaded === undefined || hasPendingEdits) {
      return
    }
    const signature = signatureOf(loadedKey ?? '', loadedAnchor, loadedExists)
    if (appliedRef.current === signature) {
      return
    }
    appliedRef.current = signature
    dispatch({
      type: 'loadFromServer',
      definition: loaded.definition,
      baseVersion: loaded.baseVersion,
      exists: loaded.exists,
      canResetToDefault: loaded.canResetToDefault,
    })
    setSaveState('idle')
    setSaveError(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 원시값으로 좁힌다. loaded 객체를 넣으면 refetch 동일성 변화마다 돈다
  }, [loadedKey, loadedAnchor, loadedExists, hasPendingEdits])

  /**
   * 최신 상태의 거울.
   *
   * 언마운트 cleanup 과 `flush` 는 렌더 시점의 클로저를 보므로, ref 없이는 **한 편집 전**
   * 정의를 보낸다. 그 자리가 정확히 「나가기 직전 편집이 사라지는」 자리다.
   */
  const latest = React.useRef(state)
  latest.current = state

  /** 마지막으로 서버에 보낸 revision. 같은 편집을 두 번 보내지 않는다. */
  const savedRevision = React.useRef(0)
  /** 지금 보내는 중인 revision. flush 와 타이머가 겹칠 때 중복을 막는다. */
  const inFlightRevision = React.useRef<number | null>(null)

  /**
   * ★ 리듀서가 revision 을 0 으로 되돌리면 이 ref 도 따라 되돌린다.
   *
   * `loadFromServer`·`resetToDefault` 는 편집 이력을 버리므로 revision 이 0 부터 다시 센다.
   * 그런데 `savedRevision` 은 ref 라 그 리셋을 모른다 — 되돌리지 않으면 **복원 뒤의 편집이
   * 옛 값에 도달하는 순간 「보낼 것이 없다」로 판정돼 조용히 저장이 스킵된다.**
   * 사용자는 저장됐다고 믿고 나가고, 그 편집은 사라진다.
   *
   * 디바운스가 여러 편집을 합칠 때 특히 잘 걸린다 — 중간 저장이 안 끼므로 `savedRevision` 이
   * 한 칸씩 따라오지 못하고 합쳐진 결과가 옛 값과 정면으로 같아진다.
   */
  React.useEffect(() => {
    if (state.revision === 0) {
      savedRevision.current = 0
      inFlightRevision.current = null
    }
  }, [state.revision])

  /** 실제 저장. 보낼 것이 없으면 아무 일도 하지 않는다. */
  const persist = React.useCallback(async (): Promise<void> => {
    const current = latest.current
    if (current.revision === 0 || current.revision === savedRevision.current) {
      return
    }
    if (inFlightRevision.current === current.revision) {
      return
    }
    inFlightRevision.current = current.revision
    setSaveState('saving')
    try {
      // ★ 앵커는 state 의 값을 그대로 싣는다 — 응답으로 갱신하지 않는다.
      const wire = toWireDefinition(current.draft)
      await saveDraft(key, wire, current.baseVersion)
      savedRevision.current = current.revision
      // ★ 캐시를 저장한 내용으로 갱신한다.
      //
      // 이 쿼리는 `staleTime: Infinity` 라 재진입해도 다시 안 읽는다. 갱신하지 않으면
      // 「목록으로 나갔다 다시 들어오면 방금 한 편집이 사라지는」 자리가 생긴다 — 서버에는
      // 저장돼 있는데 화면만 옛 응답을 그리므로 **저장이 실패한 것처럼 보인다**(E2E 가 잡았다).
      //
      // 앵커는 **우리가 보낸 값**을 그대로 싣는다. 서버를 다시 읽어 채우면 그 사이 남이
      // 발행했을 때 앵커가 새 버전으로 올라가 락이 풀린다.
      // ★ 서명도 함께 갱신한다. 이 쓰기는 **자기 저장**이라 리듀서에 다시 실을 이유가 없는데,
      //   `exists` 가 false→true 로 바뀌면 서명이 달라져 위 effect 가 다시 돌고 방금
      //   「저장됨」이던 상태를 `idle` 로 되돌린다. 화면에는 저장 표시가 한 순간 떴다 사라진다.
      appliedRef.current = signatureOf(key, current.baseVersion, true)
      client.setQueryData(WORKFLOW_DRAFT_KEY(key), {
        definition: wire,
        baseVersion: current.baseVersion,
        exists: true,
        canResetToDefault: current.canResetToDefault,
      })
      setSaveState('saved')
      setSaveError(null)
    } catch (error) {
      setSaveState('error')
      setSaveError(
        error instanceof WorkflowAdminApiError ? error.detail : '초안을 저장하지 못했습니다',
      )
    } finally {
      inFlightRevision.current = null
    }
  }, [key, client])

  // 편집이 생기면 디바운스 뒤 저장한다.
  React.useEffect(() => {
    if (state.revision === 0 || state.revision === savedRevision.current) {
      return
    }
    setSaveState('dirty')
    const timer = setTimeout(() => {
      void persist()
    }, DRAFT_AUTOSAVE_DELAY_MS)
    return () => {
      clearTimeout(timer)
    }
  }, [state.revision, persist])

  // 언마운트 시 미저장분을 보낸다. 결과를 기다릴 수 없으므로 fire-and-forget 이다 —
  // abort 하면 그 편집이 사라진다.
  React.useEffect(() => {
    return () => {
      void persist()
    }
  }, [persist])

  const flush = React.useCallback(async (): Promise<void> => {
    await persist()
  }, [persist])

  return {
    state,
    dispatch,
    isLoading: query.isPending,
    loadError: query.error,
    saveState,
    saveError,
    flush,
  }
}
