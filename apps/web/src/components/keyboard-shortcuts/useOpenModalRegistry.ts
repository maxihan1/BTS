// 화면에 열려 있는 모달을 한 신호로 모으는 zustand 레지스트리 — 단축키 게이트가 열거 대신 이 신호를 읽는다 (FR-UX-10 F11 리뷰 봉합 C-1)
//
// ★왜 「열거」가 아니라 「신호」인가.
// 원래 상세 라우트의 단축키 게이트는 모달 4종을 손으로 나열했다. 그런데 그 페이지가 실제로
// 렌더하는 모달은 6종이었고, 빠진 2종(댓글 삭제 확인 · 첨부 미리보기)은 입력 요소가 없어
// `shouldIgnoreEvent` 도 통과했다 — 확인 다이얼로그가 떠 있는데 `i` 가 담당자 PATCH 를 실제로
// 발행했다. 열거를 2건 늘리는 처방은 **다음 모달이 생기는 순간 똑같이 뚫린다**. 두 목록이
// 서로를 검사하지 않는 지배 결함 양식이다(`two-lists-never-check-each-other`).
//
// 그래서 판정을 뒤집었다. 게이트는 「내가 아는 모달이 열려 있나」를 묻지 않고 「무엇이든 열려
// 있나」를 묻는다. 모달 쪽이 자기 열림을 **보고**하고, 게이트는 그 합만 읽는다. 새 모달이
// 생겨도 보고 한 줄이면 자동으로 막히고, 그 보고를 빠뜨렸는지는
// `routes/__tests__/issue-detail-modal-gate.test.ts` 가 소스 전수로 되잰다.
//
// Context API 가 아니라 zustand 인 이유는 `useContextShortcuts.ts` 와 같다 — 같은 파이프라인의
// 형제 신호라 저장 방식을 갈라 둘 이유가 없다.
import { useEffect, useId } from 'react'
import { create } from 'zustand'

/** 열림 보고를 담는 스토어 상태 */
interface OpenModalRegistryState {
  /** 지금 열려 있다고 보고된 모달의 인스턴스 id 목록 */
  readonly openIds: readonly string[]
  /** 모달이 열렸음을 보고한다 (같은 id 중복 보고는 무시) */
  readonly open: (id: string) => void
  /** 모달이 닫혔거나 언마운트됐음을 보고한다 */
  readonly close: (id: string) => void
}

/**
 * 열린 모달 레지스트리.
 *
 * 직접 구독하는 대신 {@link useHasOpenModal} · {@link useReportModalOpen} 을 쓴다.
 * 스토어를 직접 노출하는 것은 테스트가 `setState({ openIds: [] })` 로 상태를 끊기 위해서다 —
 * 모듈 전역이라 리셋 없이는 앞 테스트의 열림이 다음 테스트로 샌다.
 */
export const useOpenModalRegistry = create<OpenModalRegistryState>((set) => ({
  openIds: [],
  open: (id): void =>
    set((state) => (state.openIds.includes(id) ? state : { openIds: [...state.openIds, id] })),
  close: (id): void =>
    set((state) =>
      state.openIds.includes(id) ? { openIds: state.openIds.filter((x) => x !== id) } : state,
    ),
}))

/**
 * 이 컴포넌트가 소유한 모달의 열림 여부를 레지스트리에 보고한다.
 *
 * 인스턴스 식별자는 `useId()` 로 **내부에서** 만든다 — 호출자가 문자열을 지어내면 두 모달이
 * 같은 이름을 골랐을 때 한쪽이 닫히며 다른 쪽의 열림까지 지운다. 그 충돌은 화면에서
 * 「모달이 떠 있는데 단축키가 발화한다」로만 드러나 원인을 찾기 어렵다.
 *
 * 언마운트 cleanup 이 닫힘 보고를 겸한다. `open` 상태 그대로 사라지는 모달(부모가 조건부로
 * 렌더를 끊는 경우)이 레지스트리에 영원히 남아 그 화면의 단축키를 통째로 죽이는 것을 막는다.
 *
 * @param isOpen 이 모달이 지금 열려 있는가
 */
export function useReportModalOpen(isOpen: boolean): void {
  const id = useId()

  useEffect(() => {
    if (!isOpen) return undefined

    const { open, close } = useOpenModalRegistry.getState()
    open(id)
    return () => {
      close(id)
    }
  }, [id, isOpen])
}

/**
 * 지금 열려 있다고 보고된 모달이 하나라도 있는가.
 *
 * @returns 하나라도 열려 있으면 true
 */
export function useHasOpenModal(): boolean {
  return useOpenModalRegistry((state) => state.openIds.length > 0)
}
