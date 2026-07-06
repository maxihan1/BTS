// 인증 세션 상태를 관리하는 Zustand 스토어 — sessionStorage persist (localStorage 금지)
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import type { WhoamiResponse } from '@/api/schemas'

interface AuthState {
  accessToken: string | null
  user: WhoamiResponse | null
}

interface AuthActions {
  setSession: (payload: { accessToken: string; user: WhoamiResponse }) => void
  clearSession: () => void
  setAccessToken: (token: string | null) => void
  setUser: (user: WhoamiResponse) => void
}

export const useAuthStore = create<AuthState & AuthActions>()(
  persist(
    (set) => ({
      accessToken: null,
      user: null,
      setSession: ({ accessToken, user }) => set({ accessToken, user }),
      clearSession: () => {
        set({ accessToken: null, user: null })
        sessionStorage.removeItem('bts.auth')
      },
      setAccessToken: (accessToken) => set({ accessToken }),
      setUser: (user) => set({ user }),
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
