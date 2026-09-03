<!-- ADR — 로그인 전역 모달 통일 + 이메일 선입력 단계 폐기: 닫기 3경로 봉인 · 자동 SSO 리다이렉트 폐기 -->

# ADR: 로그인 전역 모달 + 단일 화면 폼

> 결정일. 2026-09-03
> 상태. Accepted
> 컨텍스트. identity-access BC — FR-AU-07(도메인 라우팅) · FR-AU-09(세션·토큰) UI 표면
> 선행. ADR `2026-06-09-domain-based-provider-routing`(FR-AU-07 — identifier-first 2단계 + 자동 리다이렉트),
> `2026-06-09-multi-provider-explicit-selection`(FR-AU-06 — 명시 선택).
> 스펙. `docs/specs/2026-09-03-login-modal-ux.md` | plan. `docs/plans/2026-09-03-login-modal-ux/`

## 컨텍스트

`bts.maxihan.com` 에 미인증으로 접속하면 로그인 화면이 뜨지 않았다. 배포 번들에서
`홈 (T13 가드 추가 전 placeholder)` 문자열을 확인했고, 원인은 `router.ts` 의 `indexRoute` 가
`beforeLoad` 가드를 갖지 않은 것이었다.

> **★게이트 2 정정.** 초안은 「유일하게」라고 적었으나 **틀렸다.** 코드리뷰가 `workflowsKeyRoute` 도
> 같은 구멍임을 실측으로 잡았다. 실측 결과 62 라우트 중 가드 없는 것이 셋이고, 그중 둘이 진짜 구멍이었다.
> 이 정정이 D9(가드 커버리지 판별식)를 낳았다.

이 결함을 고치면서 Maxi 가 로그인 UX 방향 셋을 확정했다 — ① 미인증 전 경로에서 로그인이 뜬다
② 로그인을 모달로 통일하고 세션 만료 시에도 페이지를 이동하지 않는다 ③ 이메일 선입력 단계를 없앤다.

②와 ③은 Jira Cloud 실물과 **정반대**다. Atlassian 계정은 이메일 선입력 2단계이고, 세션 만료 시
다음 요청에서 로그인 화면으로 보낸다. 편차를 짚어 확인받았고 Maxi 가 그대로 진행을 선택했다.

## 결정

### D1. 인덱스 라우트는 자체 화면을 갖지 않는다

`indexRoute` 에 `composeGuards(requireAuthAndPasswordChanged, redirectToStartPage)` 를 달고
`component` 와 `routes/index.tsx` 를 삭제했다. `redirectToStartPage` 는 **항상 throw** 한다.

통과 가능한 가드로 만들면 다시 빈 화면이 남을 자리가 생긴다. 도달 불가를 코드 구조로 보장한다.

`returnTo` 는 보지 않는다. 인덱스는 로그인 후 착지점이지 되돌아갈 곳이 아니고, 미인증 처리는
앞에 체인된 `requireAuth` 가 이미 담당한다.

### D2. 모달은 배경을 만들지 않는다 — 배경을 만드는 것은 라우트다

| 경우 | 라우트 동작 | 배경 |
|---|---|---|
| 첫 진입·딥링크 | `requireAuth` 가 `/login` 으로 보낸다 | `routes/login.tsx` 의 `AuthBackdrop` |
| 세션 만료 | 이동 없음 | 사용자가 보던 진짜 화면 |

이것이 두 경우를 하나의 컴포넌트로 처리할 수 있는 이유다. `LoginDialog` 는 배경에 대해 아무것도 모른다.

`AuthBackdrop` 은 **props · hook · fetch 가 전부 0개**여야 한다. 이는 스타일 규칙이 아니라 보안 계약이다 —
받는 것도 읽는 것도 없는 컴포넌트는 보여줄 데이터를 가질 수 없고, 그 사실은 코드를 읽지 않고도 참이다.
미인증 배경으로의 데이터 유출을 리뷰에서 한 줄로 증명할 수 있게 하는 것이 설계 목적이다.

### D3. 닫기 3경로를 전부 봉인한다

ESC(`onEscapeKeyDown` preventDefault) · 오버레이(`onPointerDownOutside` preventDefault) ·
X(`showCloseButton={false}`).

첫 진입에서 닫으면 `AuthBackdrop` 만 남는 막다른 골목이고, 만료 상태에서 닫으면 stale 화면의
모든 조작이 401 을 낳는다. 두 경우의 정책을 가르지 않는다 — 정책이 갈리면 코드도 갈려
"하나의 모달" 이라는 요구가 깨지고, 만료 쪽을 닫게 해줘도 사용자가 얻는 것은 읽기 전용 stale 화면뿐이다.

X 는 `disabled` 로 두지 않고 **아예 렌더하지 않는다**. disabled X 는 "닫을 수 있는데 지금은 안 된다"는
거짓 신호다.

`DESIGN.md:482` 의 "Esc 로 드롭다운/모달/팝오버 닫기(Radix 기본)" 에 대한 의도적 편차다.
저장소 선례 2건이 같은 패턴을 쓴다 — `AutomationYamlImportDialog`(3경로 가로채기),
`ConfirmDialog`(확정 중 전면 잠금). 탈출구는 브라우저 주소창·뒤로가기이며 막지 않는다.

### D4. 만료 플래그는 신규 비영속 zustand 스토어에 둔다

배선 지점은 `api/client.ts` 의 refresh 실패 지점 — 이미 `clearSession()` 을 부르고 있는 자리다.

기각한 대안.

| 대안 | 기각 사유 |
|---|---|
| QueryClient 전역 `onError` | `verifyMfa`·`authenticateWithSecurityKey` 등 useQuery 를 안 거치는 `apiFetch` 호출과, 로컬 `onError` 를 가진 mutation 을 놓친다 |
| 라우터 `subscribe` | 라우터는 **이동**의 관측자인데 이 설계의 핵심은 **이동하지 않는 것**이다. 관측 대상이 없다 |
| `authStore` 에 필드 추가 | `persist`(sessionStorage) 라 일시 UI 플래그가 영속되면 새로고침 후 모달이 유령처럼 뜬다 |
| `clearSession()` 내부에서 세팅 | 로그아웃·whoami 롤백·`MfaCodeInput` 에서도 불려 전부 오탐 |

`hadSession` 가드를 둔다. `dashboards/shared/$token` 은 미인증 공개 라우트라
(`staticData:{requireAuth:false}`) 세션이 애초에 없던 401 로 공개 페이지를 모달로 가리면 안 된다.

`ReauthDialog`(403 step-up)는 재사용하지 않는다. 형태는 동형이지만 그것은 이미 인증된 사용자의
재확인이고 이것은 재로그인이다. 수단 결정 로직도 다르다.

### D5. 이메일 선입력 1단계 폐기 — 기능은 배경 조회로 보존

`step` 을 `'email'|'form'|'mfa'` → `'form'|'mfa'` 로 축소했다. 도메인 라우팅(FR-AU-07)은
사라지지 않고 식별자 입력의 **blur ‖ 500ms 디바운스 배경 조회**로 옮겼다. 도메인 단위 dedupe 와
순번 가드로 중복 요청·경합을 막는다.

blur 만으로는 필드를 떠나지 않고 Enter 로 제출하는 사용자를 놓치는데, 그게 정확히
NFR-A11Y-04(키보드만으로 로그인)가 보호하는 경로다. 디바운스만으로는 부분 도메인이 우연히
매칭될 때 오탐이 난다. 제출 시점 조회는 모든 LOCAL 로그인에 왕복 1회를 더하고, 실패 시 막으면
FR-07 S4 fail-safe 위반이며 통과시키면 조회가 무의미해진다.

### D6. 자동 SSO 리다이렉트 폐기 — 버튼만 노출한다

기존 2단계에서 `window.location.assign` 이 안전했던 것은 트리거가 "계속" 클릭이라는 **명시적 행위**
였기 때문이다. 단일 화면에서 트리거는 blur/디바운스로 **수동적**이라, 그 상태로 풀 네비게이션을
걸면 타이핑 중이던 비밀번호와 함께 화면이 통째로 사라지고 되돌릴 수 없다.

매칭 시 SSO 버튼을 비밀번호 **위**에 노출하고(비밀번호를 치기 전에 보게 한다), 로컬 제출 버튼은
`variant="outline"` 으로 강등하되 **살려둔다** — 매칭 도메인에 LOCAL/LDAP 계정이 공존할 수 있고
(FR-AU-06 명시 선택 보존), FR-07 S4 "끊긴 라우트가 사용자를 막지 않는다" 를 그대로 지킨다.

### D7. 다이얼로그 이름은 DialogTitle 하나로만 만든다

`jira-parity-contract.md` §2 는 "신규 다이얼로그마다 고유 `aria-label`" 을 요구하지만,
`confirm-dialog.tsx` 가 실측으로 확인한 규칙이 우선한다 — Radix 가 `DialogTitle` 을
`aria-labelledby` 로 자동 연결하고 그것이 `aria-label` 을 이긴다. 두 경로를 두면
"지정한 이름 ≠ 실제 이름" 자리가 생긴다. `BTS 로그인` 은 앱 전역에서 유일하다.

이름은 단계 전환 중에도 바뀌지 않는다. 다이얼로그의 접근 가능한 이름이 상호작용 중에 바뀌면 안 된다.

### D8. 식별자 라벨은 `사용자명` 을 유지한다

Jira Cloud 는 `Email` 을 쓰지만 LDAP 로그인의 식별자는 이메일이 아니라 `alice` 다
(`login-ldap.spec.ts` 가 계약). `이메일` 라벨은 사실이 틀린다.

바꿀 경우의 비용도 측정했다 — e2e 는 `loginStrings.usernameLabel` 심볼 참조 23건이라 무편집이지만,
리터럴 `'사용자명'` 이 src 유닛 27건 + e2e 5건 = **32곳**이고 그중 상당수가 로그인과 무관한 화면이다.

### D9. 가드 커버리지를 판별식으로 강제한다 (게이트 2 리뷰가 낳은 결정)

D1 은 `indexRoute` 라는 **인스턴스**를 고쳤다. 게이트 2 리뷰가 `workflowsKeyRoute` 도 같은 구멍임을
실측으로 잡으면서 드러난 것은, 진짜 문제가 인스턴스가 아니라 **두 목록이 서로를 검사하지 않는 것**
이라는 사실이다 — 라우트 정의와 가드 배선은 각자 자라고, 새 라우트에 가드를 안 달아도 아무도 안 본다.
이 저장소가 이미 이름 붙인 지배 결함 양식(`two-lists-never-check-each-other`)이다.

그래서 `apps/web/src/router.guard-coverage.test.ts` 를 세웠다. `routeTree` 를 순회해 **모든 경로
라우트**가 둘 중 하나를 만족함을 강제한다.

- `beforeLoad` 가 있다 (보호 라우트)
- `staticData.requireAuth === false` 를 **명시**한다 (의도적 공개)

세 가지 설계 선택이 있다.

1. **개수가 아니라 목록 전수 비교.** 개수 상한은 새 위반이 늘어도 숫자만 올리면 통과한다.
2. **비-공허 짝.** `PATH_ROUTES.length > 50` 을 함께 재지 않으면 트리 순회가 실패해 빈 배열이 됐을 때
   판별식이 조용히 통과한다.
3. **공개는 명시로만.** `staticData` 자체가 없는 것과 `requireAuth:false` 를 적은 것이 코드에서
   같은 모양이면, 「공개하기로 정했다」와 「아무도 안 봤다」가 구분되지 않아 판별식이 무의미해진다.

red 를 1회 확인했다 — 가드를 달기 전 이 판별식은 정확히 `/workflows/$key` 하나만 짚었고,
비-공허 짝 2건은 그 상태에서도 통과했다(공허 red 가 아님).

## 결과

- `bts.maxihan.com` 미인증 진입에서 로그인이 뜬다 (원 결함 해소)
- 세션 만료가 작업 손실을 일으키지 않는다
- 로그인 왕복이 2단계에서 1단계로 줄었다
- FR-AU-07 스펙의 S1·S2 와 FR-AU-09 스펙의 S5 가 deviation 으로 갱신됐다.
  FR-AU-09 S5 의 리다이렉트·toast 는 **원래 구현된 적이 없어**, 이 변경이 스펙-코드 drift 도 함께 해소한다
- `DialogContent` 에 `overlayClassName` · `showCloseButton` 가산 prop 이 생겼다 (기본값 = 기존 동작)
- **가드 없는 라우트가 0임을 기계가 매번 재계산한다** — 인스턴스 2건보다 이 판별식이 이 PR 의 실질 산출이다

## 잔여 위험

| # | 위험 | 대응 |
|---|---|---|
| R1 | 세션 만료 시 배경 화면이 각 페이지의 에러 UI 로 뒤바뀔 수 있다 | 수용. 재로그인 성공 시 `invalidateQueries()` 가 회복시킨다. 쿼리 일시정지 배선은 범위 밖 |
| R2 | e2e 가 타입체크·린트 밖이라 폐기된 i18n 심볼이 조용히 가짜초록을 만든다 | `src/i18n/__tests__/login-strings-usage.test.ts` 전수 스캔으로 강제 |
| R3 | 닫기 봉인이 사용자를 가둔다는 인상 | 브라우저 주소창·뒤로가기를 막지 않는다. 만료 모달은 설명문으로 상황을 알린다 |
| R4 | 「이동하지 않는다」가 **새로고침에는 적용되지 않는다** | 의도된 한계다. 새로고침은 부팅 경로라 `requireAuth` 가 먼저 잡아 `/login` 으로 보내고, 그게 옳다 — 잃을 작성분이 없다. 스펙 S4 에 범위를 명시했다 |
