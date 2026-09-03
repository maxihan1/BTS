// 미인증 진입 화면의 배경 장식 — 로그인 모달 뒤에 앱 셸의 실루엣만 그린다
/**
 * 로그인 모달 뒤 배경.
 *
 * 🛑 이 컴포넌트는 props · hook · fetch 가 **전부 0개**여야 한다.
 * 그것이 이 파일의 유일한 계약이고, 데이터 유출이 구조적으로 불가능하다는 증명이다 —
 * 받는 것도 읽는 것도 없는 컴포넌트는 보여줄 데이터를 가질 수 없다. 코드를 읽지 않고도 참이다.
 * 상태나 사용자 정보가 필요해지면 그것은 이 컴포넌트가 아니라 다른 컴포넌트의 일이다.
 *
 * 세션 만료 경우에는 이 배경이 쓰이지 않는다 — 그때는 사용자가 보던 진짜 화면이 그대로 배경이 된다.
 * 즉 배경을 만드는 것은 라우트이지 모달이 아니다.
 *
 * 치수는 실제 셸과 짝이다. `h-12` 는 `TopBar` 의 `h-12`(48px), `w-[264px]` 는 `Sidebar` 의
 * 모바일 드로어 폭과 같다 — 셸 치수를 바꾸면 여기도 같이 바꾼다.
 *
 * `animate-pulse` 는 쓰지 않는다. 이것은 로딩이 아니라 정적 장식이고,
 * `components/__tests__/skeleton-usage.test.ts` 가 인라인 재정의를 금지한다.
 */
export const AuthBackdrop = () => (
  <div aria-hidden="true" className="flex h-screen flex-col">
    <div className="h-12 shrink-0 border-b border-border bg-background" />
    <div className="flex min-h-0 flex-1">
      <div className="hidden w-[264px] shrink-0 border-r border-sidebar-border bg-sidebar md:block" />
      <div className="flex-1 bg-background" />
    </div>
  </div>
)
