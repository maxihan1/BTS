// 세션 만료로 인한 재로그인 요구를 전역 모달에 알리는 비영속 UI 스토어
import { create } from 'zustand'

interface LoginPromptState {
  /**
   * refresh 실패로 세션이 끊겨 재로그인이 필요한 상태.
   *
   * `authStore` 에 두지 않는 이유. authStore 는 `persist`(sessionStorage) 라
   * 이 일시 UI 플래그가 영속되면 새로고침 후 true 로 되살아나 모달이 유령처럼 뜬다.
   * `partialize` 로 특정 키만 제외하는 수술보다 별도 비영속 스토어가 싸고 안전하다.
   */
  sessionExpired: boolean
}

interface LoginPromptActions {
  /** 세션 만료를 알린다 — `api/client.ts` 의 refresh 실패 지점에서만 호출한다 */
  promptSessionExpired: () => void
  /** 재로그인 완료 또는 테스트 초기화 */
  reset: () => void
}

export const useLoginPromptStore = create<LoginPromptState & LoginPromptActions>()((set) => ({
  sessionExpired: false,
  promptSessionExpired: () => set({ sessionExpired: true }),
  reset: () => set({ sessionExpired: false }),
}))
