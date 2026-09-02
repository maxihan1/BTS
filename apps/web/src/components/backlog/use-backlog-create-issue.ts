// 백로그 화면의 이슈 생성 오케스트레이션 — 어느 칸이 열었는지 · 생성 후 배정 (FR-UX-09 F3)
import { useCallback, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { backlogKeys, useAssignToSprint } from '@/hooks/use-backlog'
import { issueCreateStrings } from '@/i18n/ko'

/** 백로그 칸을 뜻하는 대상 값 — 스프린트 id 와 섞이지 않는 예약어다. */
const BACKLOG_TARGET = 'backlog'

/** `useBacklogCreateIssue` 반환값 */
export interface BacklogCreateIssue {
  /** 모달 열림 여부 — 대상이 정해져 있으면 열려 있다 */
  isOpen: boolean
  /** 백로그 칸 진입점 콜백 (참조 안정) */
  openForBacklog: () => void
  /** 스프린트 칸 진입점 콜백을 id 별로 돌려준다 (참조 안정 — 칸이 `memo` 다) */
  openForSprint: (sprintId: string) => () => void
  /** 모달 닫힘 처리 — **가시성만** 끈다 */
  close: () => void
  /** 생성 성공 콜백 — 스프린트에서 열었으면 그 스프린트에 배정한다 */
  onCreated: (issueKey: string) => void
}

/**
 * 백로그 화면의 이슈 생성 흐름을 한 곳에 모은다.
 *
 * ### 모달은 화면당 1개다 (FR-15)
 * 칸마다 모달을 두면 `role="dialog"` 가 N개가 되어 조회가 strict mode 로 깨진다
 * (F2 가 겪은 164발생 함정과 같은 결). 그래서 「어느 칸이 열었는가」만 여기서 쥔다.
 *
 * ### 대상과 가시성의 수명을 분리한다
 * `CreateIssueDialog.handleSuccess` 는 `onOpenChange(false)` 를 부른 **다음** `onCreated(key)` 를 부른다.
 * - 대상을 **상태**에서 읽으면 「그 시점에 `null` 이 아직 반영되지 않았다」는 **React 배칭 동작에
 *   정확성을 의존**하게 된다.
 * - 그렇다고 **ref 를 닫힘과 함께 비우면 더 확실히 깨진다** — ref 는 동기라 뒤이은
 *   `onCreated` 가 이미 `null` 을 읽는다 (2026-08-03 E2E 가 이 실수를 즉시 잡았다).
 *
 * 그래서 **닫힘은 상태만 끄고, 대상은 `onCreated` 가 읽은 뒤에 비운다.**
 * 취소로 닫혔을 때 대상이 남지만 무해하다 — `onCreated` 는 생성 성공에만 불리고 다시 열 때 덮어쓴다.
 *
 * @param projectKey 백로그 조회·무효화 대상 프로젝트 키
 */
export function useBacklogCreateIssue(projectKey: string): BacklogCreateIssue {
  const queryClient = useQueryClient()
  const assignToSprint = useAssignToSprint(projectKey)

  const [target, setTarget] = useState<string | null>(null)
  const targetRef = useRef<string | null>(null)

  const openFor = useCallback((next: string): void => {
    targetRef.current = next
    setTarget(next)
  }, [])

  const openForBacklog = useCallback(() => { openFor(BACKLOG_TARGET) }, [openFor])

  /**
   * 스프린트별 콜백 캐시 — 칸이 `memo` 라 매 렌더 새 함수를 주면 재렌더 스킵이 무력화된다 (NFR-4).
   */
  const sprintCallbacks = useRef(new Map<string, () => void>())
  const openForSprint = useCallback((sprintId: string): (() => void) => {
    const cached = sprintCallbacks.current.get(sprintId)
    if (cached !== undefined) return cached
    const fn = (): void => { openFor(sprintId) }
    sprintCallbacks.current.set(sprintId, fn)
    return fn
  }, [openFor])

  const close = useCallback(() => { setTarget(null) }, [])

  /**
   * 생성 성공 직후 처리.
   *
   * ### 왜 2회 호출인가
   * `POST /issues` 계약에 `sprintId` 가 없다(2026-08-03 실측). 그래서 생성 후 기존 배정 API 를
   * 한 번 더 부른다 (ADR D-2). 백엔드 확장은 범위 2~3배라 별도 FR 후보다.
   *
   * ### 🛑 2차 실패를 1차 실패처럼 다루지 않는다
   * 배정이 실패해도 **이슈는 온전히 만들어졌다.** 빨간 실패 토스트는 「안 만들어졌다」로 읽혀
   * 사용자가 다시 만들고 **중복 이슈**가 생긴다. 경고 톤 + 이슈 키로 낸다 (FR-5).
   * 선례 — `backlogLabels.rerankFailedWarning`(이동은 됐고 순서만 실패)이 같은 형태다.
   */
  const onCreated = useCallback((issueKey: string): void => {
    const current = targetRef.current
    targetRef.current = null
    setTarget(null)

    // 백로그 칸이면 배정할 것이 없다 — 스프린트 미지정이 곧 백로그다.
    //
    // ★그래도 **목록 갱신은 필요하다** (FR-9). 스프린트 경로는 `useAssignToSprint` 의 성공
    // 콜백이 무효화를 걸어주지만, 백로그 경로는 아무도 걸지 않아 만든 이슈가 나타나지 않는다 —
    // E2E S1 이 실측으로 잡은 결함이다.
    // ★무효화는 **프로젝트 접두 키**다 (FR-BD-04). 새 이슈는 아직 어느 스프린트에도 없으므로
    // 이 프로젝트 **모든 보드**의 백로그 칸에 나타난다(E12) — 한 보드만 갱신하면 나머지 보드는
    // 캐시가 낡은 채로 남는다. 같은 이유로 `useAssignToSprint` 도 접두 키를 쓴다.
    if (current === null || current === BACKLOG_TARGET) {
      void queryClient.invalidateQueries({ queryKey: backlogKeys.project(projectKey) })
      return
    }

    assignToSprint.mutate(
      { sprintId: current, issueKey },
      { onError: () => toast.warning(issueCreateStrings.sprintAssignFailed(issueKey)) },
    )
  }, [assignToSprint, projectKey, queryClient])

  return { isOpen: target !== null, openForBacklog, openForSprint, close, onCreated }
}
