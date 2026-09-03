# 로그인 UX 개편 — 전역 모달 + 단일 화면 폼 (FR-AU-07 · FR-AU-09 UI deviation)

> 티어: T2
> slug: login-modal-ux
> type: auth
> agent: frontend-engineer
> 생성: 2026-09-03

## Brief

**사용자 원문.** `bts.maxihan.com 들어가면 로그인페이지가 뜨지 않는데 로그인 페이지가 뜨도록 수정
해주고 현재 페이지로 로그인이 뜨고 있는데 지라클라우드 처럼 모달로 처리 해줘 그리고 불필요하게
이메일을 묻는 단계가 있는데 그것도 삭제가 필요함. 지라 클라우드를 리서치 해서 로그인 페이지 제작 바람`

**이 PR 이 하는 것.** 세 가지다.
1. 미인증으로 **어느 경로에 들어와도** 로그인이 뜬다 (원 결함 해소)
2. 로그인을 **전역 모달**로 통일하고, 세션 만료 시에도 페이지 이동 없이 현재 화면 위에 띄운다
3. 이메일 선입력 1단계를 폐기하고 **단일 화면**으로 합친다. 도메인 SSO 라우팅 기능은 보존한다

**왜 T2 인가.** 보안 표면 13건을 건드린다 — `auth/**`(LoginForm · LoginDialog · routeGuard ·
loginPromptStore · AuthBackdrop) · `api/client.ts`(401 인터셉터) · `router.ts`(가드) ·
`routes/login.tsx`. `CLAUDE.md` §작업 티어의 "보안 표면은 T2 미만 불가" 가 적용된다.

**백엔드 변경 0.** `GET /api/v1/auth/route` 를 포함해 모든 엔드포인트를 그대로 쓴다.

## 원인 — 추측이 아니라 배포 번들 실측

`https://bts.maxihan.com/assets/index-DOByFogC.js` 를 받아 `grep -c "T13 가드 추가 전 placeholder"` → **1**.
미인증 진입 시 `routes/index.tsx` 의 placeholder 가 실제로 렌더된다.

원인은 `apps/web/src/router.ts:85-90` 의 `indexRoute` 다. 60여 라우트 중 **이 하나만**
`beforeLoad` 가드가 없고 `// T13 라우트 가드에서 dashboard / login 으로 리다이렉트 예정` TODO 만 있었다.

배포본은 `last-modified: Mon, 24 Aug 2026` 로 8/24 자다 — 머지해도 재배포 전까지 반영되지 않는다
(Maxi 확정: 이번 범위는 코드·PR 까지).

## Jira 대조

**실물 조회 출처** (계약 §1 인정 도메인).
- https://support.atlassian.com/atlassian-account/docs/log-in-to-your-atlassian-account/
- https://support.atlassian.com/atlassian-account/docs/log-in-with-a-third-party-account/
- https://atlassian.design/components/modal-dialog/usage
- https://support.atlassian.com/jira-cloud-administration/docs/configure-session-timeout/

조회로 확인한 실물. id.atlassian.com/login 은 **이메일 입력 → Continue → 비밀번호** 2단계이고
("Enter your email address and select Continue"), Remember me 체크박스 · Google/Microsoft/Slack/Apple
서드파티 · "Can't log in?" 하단 링크 · Passkey 를 갖는다. 세션 만료 시에는 모달이 아니라
**다음 요청에서 로그인 화면으로** 보낸다.

| # | Jira Cloud 실물 | 본 구현 | 판정 |
|---|---|---|---|
| J1 | 이메일 + 비밀번호로 로그인 | 식별자 + 비밀번호 | 준수 |
| J2 | 서드파티/SSO 진입 경로를 로그인 화면에 제공 | SAML/OIDC 버튼 상시 렌더 + 도메인 매칭 시 상단 강조 | 준수 |
| J3 | SAML SSO 강제 시 해당 경로로 유도 | 도메인 매칭 시 SSO 버튼 노출 | 준수(X2 참조) |
| J4 | 카드형 중앙 정렬 · 단일 진입점 | 중앙 모달 1개 (전역 단일 마운트) | 준수 |
| J5 | 2단계 인증은 비밀번호 검증 **이후** | MFA 단계는 로그인 성공 후에만 | 준수 |
| **X1** | 이메일 선입력 2단계 | **단일 화면** | 의도적 편차 — Maxi 지시 |
| **X2** | 도메인 매칭 시 SSO **자동 이동** | SSO **버튼만 노출** | 의도적 편차 — 수동 트리거로 풀 네비게이션은 비밀번호를 날린다 |
| **X3** | 만료 시 로그인 화면으로 **이동** | 이동 없이 현재 화면 위 모달 | 의도적 편차 — Maxi 지시 |
| **X4** | (별도 페이지라 해당 없음) | 닫기 3경로 봉인 | 의도적 편차 — 닫아도 갈 곳이 없다 |
| **X5** | 식별자 라벨 `Email` | `사용자명` 유지 | 의도적 편차 — LDAP 식별자는 `alice` 다 |
| **X6** | `Remember me` 체크박스 | 없음 (신뢰 디바이스 30일은 MFA 단계에 별도 존재) | 범위 밖 |

**요청 둘이 Jira 실물과 반대다**(X1 · X3). 편차를 짚어 Maxi 확인을 받았고 근거와 함께 기록했다.
전문은 스펙 `docs/specs/2026-09-03-login-modal-ux.md` §Jira 대조.

## 설계 결정

정본은 ADR `docs/decisions/2026-09-03-login-modal-and-single-screen-form.md` D1~D8.

| # | 결정 | 한 줄 근거 |
|---|---|---|
| D1 | 인덱스 라우트는 자체 화면을 갖지 않는다 (가드가 항상 throw) | 통과 가능한 가드면 빈 화면이 남을 자리가 다시 생긴다 |
| D2 | 모달은 배경을 만들지 않는다 — 배경은 라우트가 만든다 | 그래서 첫 진입과 만료를 한 컴포넌트로 처리할 수 있다 |
| D3 | 닫기 3경로 전부 봉인 | 첫 진입은 막다른 골목, 만료는 stale 화면이라 닫아도 얻는 게 없다 |
| D4 | 만료 플래그는 신규 **비영속** zustand | `authStore` 는 persist 라 새로고침 후 모달이 되살아난다 |
| D5 | 이메일 단계 폐기, 도메인 조회는 blur ‖ 500ms 디바운스 | 기능 보존. `@` 없으면 조회 자체를 안 한다 |
| D6 | 자동 SSO 리다이렉트 폐기, 버튼만 노출 | 수동적 트리거로 풀 네비게이션을 걸면 타이핑 중이던 비밀번호가 날아간다 |
| D7 | 다이얼로그 이름은 `DialogTitle` 하나로만 | Radix 가 `aria-labelledby` 를 우선해 `aria-label` 을 이긴다 |
| D8 | 식별자 라벨 `사용자명` 유지 | LDAP 식별자는 `alice` 다. 바꾸면 리터럴 32곳이 걸린다 |

`AuthBackdrop` 은 **props · hook · fetch 가 전부 0개**여야 한다. 스타일 규칙이 아니라 보안 계약이다 —
받는 것도 읽는 것도 없는 컴포넌트는 데이터를 유출할 수 없고, 코드를 읽지 않고도 참이다.

## TDD

`test:` → `feat:` 대조. red 9건을 먼저 커밋하고 실제로 빨간 것을 확인했다(기존 73건은 그대로 초록).

커밋 순서. `test:`(red 9) → dialog 가산 prop → loginPromptStore + client.ts → LoginDialog +
AuthBackdrop → 인덱스 가드 → 단일 화면 폼(e2e 17파일 동반) → 문서 동기화 → 부채 등재.

e2e 를 UI 와 같은 커밋에 둔 이유는, 나누면 그 사이 커밋에서 17 파일이 동시에 빨개지기 때문이다.

## ★ e2e 는 타입체크·린트 양쪽 모두 밖이다

실측. `tsconfig.app.json:29` 의 `"include": ["src"]` · `package.json:10` 의 `"lint": "eslint src"`.
`apps/web/tsconfig.json` 은 app/node 두 프로젝트만 참조하고 **e2e 는 어디에도 없다**.

따라서 `i18n/ko.ts` 에서 `continueButton` 을 지워도 **컴파일이 통과하고**, Playwright 런타임에
`getByRole('button', { name: undefined })` 가 되어 **아무 버튼이나 매칭**된다 — 조용한 가짜초록.

처방으로 `src/i18n/__tests__/login-strings-usage.test.ts` 를 넣었다. 개수 상한이 아니라
**목록 전수 비교**다(`skeleton-usage.test.ts` 선례). 이 PR 에서 가장 중요한 안전망이다.

## 문서 동기화 (`docs/rules/fr-sync-checklist.md`)

FR 추가·삭제가 아니라 기존 FR 의 UI deviation 이므로 **카운트 불변**이다
(`verify-master-plan.sh` EXIT 0 · 144/144 유지).

- 신규. 스펙 `docs/specs/2026-09-03-login-modal-ux.md` · ADR `docs/decisions/2026-09-03-login-modal-and-single-screen-form.md`
- 갱신. FR-AU-07 스펙(스코프 확정 취소선 + S1~S4 재작성 + S6 추가) · FR-AU-09 스펙 S5 ·
  `docs/plan/product/identity-access.md` §2.7 · `CHANGELOG.md [Unreleased]` · `TODOS.md`
- 재생성. `build-doc-index.mjs` · `build-dashboard.mjs`

FR-AU-09 S5 의 `/login` 강제 리다이렉트 + toast 는 **원래 구현된 적이 없다** — `client.ts` 는
`clearSession()` 만 했다. 이 deviation 이 스펙-코드 drift 도 함께 해소한다.

## 범위 밖

- **배포.** Maxi 확정 "코드·PR 까지만". 재배포 전까지 `bts.maxihan.com` 에 반영되지 않는다
- e2e 로그인 헬퍼 사본 17벌 통합 — 152 spec 이 걸린 리팩터링이라 기능 PR 에 섞지 않는다. `TODOS.md` 등재
- 쿼리 일시정지(suspend-on-401) 배선
- provider 드롭다운 제거 (Jira 엔 없지만 FR-AU-06 산출물이고 요청 범위 밖)

## 상세

- checklist. `docs/plans/2026-09-03-login-modal-ux/checklist.md`
- 작업 중 결정과 근거. `docs/plans/2026-09-03-login-modal-ux/context-notes.md`
