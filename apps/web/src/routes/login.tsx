// 로그인 라우트 — main 랜드마크와 배경만 소유한다. 폼은 전역 LoginDialog 가 그린다
import { AuthBackdrop } from '@/auth/AuthBackdrop'

export const LoginPage = () => {
  // 로그인 라우트는 `_shell`(ShellLayout) 밖 rootRoute 직속이라 ShellLayout의 <main>을
  // 물려받지 못한다 — main 랜드마크를 이 컴포넌트가 직접 소유해야 한다(C3, D-D).
  // e2e landmark.spec.ts L2 가 "미인증 /login → main 1개" 를 계약으로 잡고 있다.
  return (
    <main>
      <AuthBackdrop />
    </main>
  )
}
