// 인증 세션 상태를 관리하는 Zustand 스토어 — sessionStorage persist (localStorage 금지)
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import type { WhoamiResponse } from '@/api/schemas'

interface AuthState {
  accessToken: string | null
  user: WhoamiResponse | null
  /**
   * 아바타 캐시버스트 카운터 — 아바타 업로드/삭제 성공마다 1씩 증가한다.
   * 아바타 다운로드 URL(`/api/v1/users/{userId}/avatar`)은 userId에서만 파생되는
   * 고정 문자열이라 아바타를 교체해도 URL 자체는 바뀌지 않는다. Avatar 컴포넌트가
   * 이 값을 `cacheBust` prop으로 받아 fetch URL에 쿼리스트링(`?v=N`)으로 덧붙여
   * 강제 재fetch를 트리거한다.
   */
  avatarVersion: number
}

interface AuthActions {
  setSession: (payload: { accessToken: string; user: WhoamiResponse }) => void
  clearSession: () => void
  setAccessToken: (token: string | null) => void
  setUser: (user: WhoamiResponse) => void
  bumpAvatarVersion: () => void
}

export const useAuthStore = create<AuthState & AuthActions>()(
  persist(
    (set) => ({
      accessToken: null,
      user: null,
      avatarVersion: 0,
      setSession: ({ accessToken, user }) => set({ accessToken, user }),
      clearSession: () => {
        set({ accessToken: null, user: null })
        sessionStorage.removeItem('bts.auth')
      },
      setAccessToken: (accessToken) => set({ accessToken }),
      setUser: (user) => set({ user }),
      bumpAvatarVersion: () => set((s) => ({ avatarVersion: s.avatarVersion + 1 })),
    }),
    {
      name: 'bts.auth',
      storage: createJSONStorage(() => sessionStorage),
    },
  ),
)

// 컴포넌트가 store 변화의 정확한 슬라이스만 구독하도록 하는 셀렉터 헬퍼
export const useAuthUser = () => useAuthStore((s) => s.user)
export const useIsAuthenticated = () => useAuthStore((s) => s.accessToken !== null)
export const useAuthActions = () =>
  useAuthStore((s) => ({
    setSession: s.setSession,
    clearSession: s.clearSession,
    setAccessToken: s.setAccessToken,
  }))
