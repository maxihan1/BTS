# 로그아웃이 `/login` 으로 이동하지 않아 로그인 모달이 뜨지 않는다

> 티어: T2 · type: auth · BC = 없음(`apps/web` 전용) · 마이그레이션 0건 · 신규 의존성 0
> FR — FR-AU-01(로그인/로그아웃)

## 왜 T2 인가

`apps/web/src/auth/**` 가 `surfaces.ts` 의 `SEC_FE` 다. 세션 종료 경로를 고치므로 보안 렌즈를
생략할 수 없다.

## Maxi 보고 (2026-09-07)

> 「로그아웃하면 로그아웃 되고 로그인 모달이 떠야 하는데 모달이 안뜨고 있는데 그것도 확인해줘」

## Jira 대조

**대응 없음 — ADS 준용 불필요(결함 수정).** 이 PR 은 새 화면·새 인터랙션을 만들지 않는다.
「로그아웃하면 로그인 화면으로 간다」는 Jira Cloud 도 같지만
([Log out of your Atlassian account](https://support.atlassian.com/atlassian-account/docs/log-out-of-your-atlassian-account/),
조회 2026-09-07), 이 PR 이 정하는 것은 그 동작의 **소유 위치**이지 동작 자체가 아니다.
UI 표면은 한 픽셀도 바뀌지 않는다.

## 증상과 실제 원인

`AccountMenu.handleLogout` 은 이렇게 적혀 있었다.

```ts
logoutMutation.mutate(undefined, {
  onSettled: () => { void navigate({ to: '/login' }) },   // ← 한 번도 실행되지 않았다
})
```

세션은 지워지는데(`sessionStorage['bts.auth']` 제거) URL 은 그대로라 로그인 모달이 뜨지 않는다.
`LoginDialog` 의 열림 조건이 `!isAuthenticated && (pathname === '/login' || sessionExpired)`
이기 때문이다.

**원인.** 훅의 `onSettled` 가 `clearSession()` 을 부르면 `ShellLayout` 의 `!isAuthenticated`
조기 반환이 켜지고 `TopBar → AccountMenu` 가 그 자리에서 언마운트된다. TanStack Query 는
**`mutate` 에 넘긴 콜백을 옵저버가 살아 있을 때만** 부르므로(`hasListeners()` 게이트) 이동
콜백이 통째로 버려진다. `useMutation` 에 준 콜백은 mutation 자신이 들고 있어 언마운트와
무관하게 돈다 — 그래서 세션 정리는 되고 이동만 안 됐다.

## 왜 한 달 넘게 안 잡혔나 — **우회가 결함을 가렸다**

`start-page.spec.ts` 헤더가 2026-08 에 이미 이렇게 적고 있었다.

> 실제로는 세션 클리어만 일어나고 navigate 가 발생하지 않는다(history.pushState/replaceState
> 호출 자체가 없음 — 직접 계측해 확인) … 이 워크어라운드는 테스트 전용이며 실제 로그아웃 버튼
> 결함 자체를 고치지 않는다(src 수정 금지, qa 영역 아님).

그 스펙과 `active-project.spec.ts` 는 `popstate` 를 **수동 재발행**해 로그인 폼에 도달했고,
둘 다 초록이었다. 결함을 정확히 알고도 **초록이 유지되는 구조**를 만든 것이다. 이 저장소가
이름 붙인 「가짜 그린」의 한 판본이고, 사용자 보고가 있고서야 드러났다.

## 처방

① **이동 소유권을 `useLogoutMutation` 으로 옮긴다.** 훅의 `onSettled` 안에서
   `clearSession()` → `navigate({ to: '/login' })` 순서로 부른다.
   🛑 순서를 뒤집으면 아직 인증 상태라 `/login` 의 `redirectIfAuth` 가 되돌려 보낸다.
② **호출자의 중복 콜백을 지운다.** `AccountMenu` 는 `logoutMutation.mutate()` 만 부른다.
③ **두 e2e 의 popstate 우회를 제거한다.** 남기면 같은 은폐가 재생산된다.
④ **우회 없는 판별식을 신설한다** — `e2e/logout-login-modal.spec.ts`.

## 보안 렌즈 (T2 필수)

| # | 물음 | 판정 |
|---|---|---|
| S1 | 세션 정리가 약해지나 | **아니다.** `clearSession()` 은 같은 `onSettled` 에 그대로 있고 성공·실패 무관하게 돈다. 이 PR 은 그 뒤에 한 줄을 **더할** 뿐이다 |
| S2 | 서버 로그아웃 실패 시 | 종전과 같이 클라이언트 세션을 지운다(사용자 의도 우선). 이제 이동까지 하므로 **로그인 화면에 갇힌 stale 화면**이 남지 않는다 — 오히려 개선이다. 판별식으로 고정(`서버가 실패해도 /login 으로 이동한다`) |
| S3 | 새 정보 노출 | 없다. 저장·전송하는 값이 늘지 않는다 |
| S4 | 열린 리다이렉트 | 목적지가 리터럴 `'/login'` 이다. 사용자 입력이 경로에 섞이지 않는다 |

## 체크리스트

- [x] 1. 우회 없는 재현 e2e (red 확인 — URL 이 `/dashboards` 에 머묾)
- [x] 2. `useLogoutMutation` 이 이동을 소유
- [x] 3. `AccountMenu` 중복 콜백 + 고아 `useNavigate` 제거
- [x] 4. 훅 단위 판별식 2건 추가 (성공·서버 실패 양쪽)
- [x] 5. e2e 2곳의 popstate 우회 제거 + 「되살리지 말 것」 명시
- [x] 6. 유닛 11,148 green · typecheck · lint 0 error · 로그아웃 관련 e2e 26 green

## 컨텍스트 노트

### D1 — 판별식을 호출자가 아니라 훅에 둔다 (2026-09-07)
결함은 「호출자가 이동을 소유했다」였다. 판별식을 호출자(`AccountMenu`)에 두면 다음에 호출자가
바뀌거나 늘어날 때 보증이 함께 사라진다. 소유가 훅으로 옮겨갔으므로 판별식도 훅에 둔다.

### D2 — 우회를 지우는 것이 수정의 일부다 (2026-09-07)
`popstate` 재발행을 남긴 채 src 만 고치면 두 스펙은 **고치기 전에도 초록이고 고친 뒤에도 초록**
이다. 즉 회귀가 돌아와도 못 잡는다. 우회 제거까지가 한 벌이다.
