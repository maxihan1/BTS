// 초안 로드·로컬 편집·디바운스 자동저장 훅 — 낙관적 락 앵커를 고정해 들고 있는다
import * as React from 'react'
import { useQuery } from '@tanstack/react-query'
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

  // 서버 응답이 오면 한 번만 싣는다. 의존성을 객체가 아니라 원시값으로 좁혀 refetch 동일성
  // 변화로 편집이 덮이지 않게 한다.
  const loadedKey = loaded?.definition.key
  React.useEffect(() => {
    if (loaded !== undefined) {
      dispatch({
        type: 'loadFromServer',
        definition: loaded.definition,
        baseVersion: loaded.baseVersion,
        exists: loaded.exists,
        canResetToDefault: loaded.canResetToDefault,
      })
      setSaveState('idle')
      setSaveError(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 최초 로드 1회만. loaded 를 넣으면 refetch 마다 편집이 덮인다
  }, [loadedKey])

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
      await saveDraft(key, toWireDefinition(current.draft), current.baseVersion)
      savedRevision.current = current.revision
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
  }, [key])

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
