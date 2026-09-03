<!-- 로그인 UX 개편 스펙 — 전역 모달 통일 + 이메일 선입력 단계 폐기 (FR-AU-07 · FR-AU-09 deviation) -->

# 로그인 UX 개편 — 전역 모달 + 단일 화면 폼 (FR-AU-07 · FR-AU-09 deviation)

> BC. identity-access | type. ui | 담당. frontend-engineer (보안 렌즈 security-engineer)
> 스코프 확정(Maxi 2026-09-03).
> - 미인증으로 **어느 경로에 들어와도** 로그인이 뜬다.
> - 로그인은 전체 페이지 전환이 아니라 **모달**. 세션 만료 시에도 페이지 이동 없이 현재 화면 위에 뜬다.
> - 이메일 선입력 1단계 **폐기**. 단 도메인 기반 SSO 라우팅(FR-AU-07)의 **기능은 보존**한다.
> - 디자인은 Atlassian/Jira Cloud 로그인 화면 기준.
> 관련 FR. FR-AU-07(도메인 라우팅) · FR-AU-09(세션·토큰) · FR-AU-06(다중 provider) · FR-MF-01/03(MFA)
> plan. `docs/plans/2026-09-03-login-modal-ux/`

## 배경 — 무엇이 문제였는가

`bts.maxihan.com` 에 미인증으로 접속하면 로그인 화면이 뜨지 않았다. 배포 번들
(`/assets/index-DOByFogC.js`)에서 `홈 (T13 가드 추가 전 placeholder)` 문자열을 직접 확인했다.

원인은 `apps/web/src/router.ts` 의 `indexRoute` 다. 60여 라우트 중 **이 하나만** `beforeLoad`
가드가 없었고 `// T13 라우트 가드에서 dashboard / login 으로 리다이렉트 예정` TODO 만 남아 있었다.

## Jira 대조

근거 도메인. `support.atlassian.com` (Atlassian 공식 문서).
대조 원본. [Log in to your Atlassian account](https://support.atlassian.com/atlassian-account/docs/log-in-to-your-atlassian-account/) ·
[Log in with a third-party account](https://support.atlassian.com/atlassian-account/docs/log-in-with-a-third-party-account/)

| # | Jira Cloud 실물 | 본 구현 | 판정 |
|---|---|---|---|
| J1 | 이메일 + 비밀번호로 로그인 | 식별자 + 비밀번호 | 준수 |
| J2 | 서드파티/SSO 진입 경로를 로그인 화면에 제공 | SAML/OIDC 버튼을 폼 하단에 상시 렌더 + 도메인 매칭 시 상단 강조 | 준수 |
| J3 | SAML SSO 강제 시 해당 경로로 유도 | 도메인 매칭 시 SSO 버튼 노출 | 준수(X2 참조) |
| J4 | 카드형 중앙 정렬 · 단일 진입점 | 중앙 모달 1개 (전역 단일 마운트) | 준수 |
| J5 | 2단계 인증은 비밀번호 검증 **이후** | MFA 단계는 로그인 성공 후에만 | 준수 |
| **X1** | **이메일 선입력 2단계** ("Enter your email address and select Continue") | **단일 화면**. 이메일·비밀번호를 함께 받는다 | **의도적 편차** |
| **X2** | 도메인 매칭 시 SSO 로 **자동 이동** | SSO **버튼만 노출**, 이동은 사용자 클릭 | **의도적 편차** |
| **X3** | 세션 만료 시 다음 요청에서 **로그인 화면으로 이동** | **이동하지 않고** 현재 화면 위에 모달 | **의도적 편차** |
| **X4** | 로그인 모달을 닫을 수 있음(별도 페이지라 해당 없음) | ESC·오버레이·X **3경로 전부 봉인** | **의도적 편차** |
| **X5** | 식별자 라벨이 `Email` | `사용자명` 유지 | **의도적 편차** |
| **X6** | `Remember me` 체크박스 | 없음 (신뢰 디바이스 30일은 MFA 단계에 별도 존재) | 범위 밖 |

**X1 근거.** Maxi 지시. 단계가 늘면 로그인 완료까지의 왕복이 늘고, 사내 1,000명 워크스페이스는
Atlassian 계정처럼 전 세계 다중 테넌트를 상대하지 않아 identifier-first 의 이점이 작다.

**X2 근거.** 단일 화면에서 도메인 조회 트리거는 blur/디바운스로 **수동적**이다. 수동적 이벤트로
풀 네비게이션을 걸면 타이핑 중이던 비밀번호와 함께 화면이 통째로 사라지고 되돌릴 수 없다.
기존 2단계에서 자동 이동이 안전했던 것은 트리거가 "계속" 클릭이라는 명시적 행위였기 때문이다.
매칭 도메인에 LOCAL/LDAP 계정이 공존할 수 있다는 점(FR-AU-06 명시 선택 보존)도 근거다.

**X3 근거.** Maxi 지시. 작성 중이던 이슈·댓글을 잃지 않는 것이 재인증 UX 의 핵심이다.

**X4 근거.** 첫 진입에서 닫으면 배경만 남는 막다른 골목이고, 만료 상태에서 닫으면 stale 화면의
모든 조작이 401 을 낳는다. 저장소 선례 2건(`AutomationYamlImportDialog` 3경로 가로채기 ·
`ConfirmDialog` 확정 중 전면 잠금)과 같은 패턴이다. `DESIGN.md:482` 의 "Esc 로 모달 닫기(Radix 기본)"
에 대한 의도적 편차이며 탈출구는 브라우저 주소창·뒤로가기로 남긴다.

**X5 근거.** LDAP 로그인의 식별자는 이메일이 아니라 `alice` 다(`login-ldap.spec.ts` 가 계약).
`이메일` 라벨은 사실이 틀린다.

## 사용자 시나리오 (Given-When-Then)

### S1 — 미인증 인덱스 진입 → 로그인 모달
- **Given** 미인증 상태
- **When** `/` 진입
- **Then** `requireAuth` 가 `/login?returnTo=` 로 보내고, 그 위에 `role="dialog"` name `BTS 로그인` 모달이 뜬다

### S2 — 미인증 딥링크 → 모달 + 배경 무유출
- **Given** 미인증 상태
- **When** `/issues/ATLAS-1` 진입
- **Then** 모달이 뜨고, 배경에는 이슈 제목·사용자명 등 실데이터가 **한 글자도** 없다

### S3 — 인증 상태 인덱스 진입 → start_page
- **Given** 인증 상태, `startPage='inbox'`
- **When** `/` 진입
- **Then** `redirectToStartPage` 가 `/inbox` 로 보낸다. 인덱스는 자체 화면을 갖지 않는다

### S4 — 세션 만료 → 이동 없이 모달
- **Given** 인증 상태로 `/issues` 를 보는 중, refresh 토큰이 서버에서 만료
- **When** API 401 → 인터셉터가 `/refresh` 호출 → 그것도 401
- **Then** `clearSession()` + `sessionExpired` 플래그 → **URL 이 바뀌지 않고** 현재 화면 위에 모달

### S5 — 만료 재로그인 → 화면 회복
- **Given** S4 상태에서 모달로 재로그인 성공
- **Then** navigate 없이 `invalidateQueries()` 로 보던 화면이 새 데이터로 갱신된다

### S6 — 닫기 봉인
- **Given** 모달이 열린 상태
- **When** ESC · 오버레이 클릭
- **Then** 닫히지 않는다. X 버튼은 렌더되지 않는다

### S7 — 도메인 매칭 → SSO 버튼 (자동 이동 없음)
- **Given** `partner.com` 이 SAML `partner-saml` 로 등록
- **When** 식별자에 `alice@partner.com` 입력 후 blur
- **Then** 비밀번호 필드 **위**에 SSO 버튼이 나타나고, 이미 타이핑한 비밀번호는 보존된다.
  `window.location.assign` 은 호출되지 않는다

### S8 — SSO 매칭 후에도 로컬 로그인 가능 (fail-safe)
- **Given** S7 상태
- **Then** 로컬 제출 버튼이 `variant="outline"` 으로 강등되되 **enabled** 로 남는다 (FR-07 S4 승계)

### S9 — `@` 없는 식별자
- **Given** LDAP 사용자명 `alice`
- **When** 입력 후 blur
- **Then** route 조회가 **일어나지 않고** 로컬 폼만 남는다

### S10 — 공개 라우트 방어
- **Given** 세션이 애초에 없는 상태에서 `/dashboards/shared/<token>` 접근
- **When** API 401 → refresh 401
- **Then** `sessionExpired` 는 `false` 로 남고 모달이 뜨지 않는다

### S11 — MFA 단계 전환
- **Given** 모달에서 로그인 → `mfa_required`
- **Then** 같은 모달 안에서 MFA 화면으로 전환된다. 다이얼로그 이름은 `BTS 로그인` 으로 불변,
  코드 입력에 자동 포커스, 안내문이 `role="status"` 로 announce 된다

## 기능 요구사항 — LM (본 변경 고유 항목. 새 FR 이 아니라 FR-AU-07·09 의 UI 계약이다)

| ID | 내용 |
|---|---|
| LM-01 | `/` 는 `composeGuards(requireAuthAndPasswordChanged, redirectToStartPage)` 로 항상 throw 한다 — `component` 를 갖지 않는다 |
| LM-02 | 로그인 모달은 `RootLayout` 에 **단 하나** 마운트되고, 열림 판정을 스스로 소유한다 |
| LM-03 | `open = !isAuthenticated && (pathname === '/login' \|\| sessionExpired)` |
| LM-04 | 닫기 3경로(ESC·오버레이·X) 전부 봉인 |
| LM-05 | 로그인 폼은 provider·식별자·비밀번호·SSO 를 한 화면에 렌더한다 |
| LM-06 | 식별자 blur 또는 500ms 디바운스에 도메인 route 를 조회한다. 도메인 단위 dedupe + 순번 가드 |
| LM-07 | 매칭 시 SSO 버튼을 비밀번호 **위**에 노출한다. 자동 이동 금지 |
| LM-08 | 만료 재로그인은 navigate 대신 `invalidateQueries()` 로 회복한다 |
| LM-09 | `/login` 은 `<main>` 랜드마크와 `AuthBackdrop` 만 소유한다 |

## 비기능 요구사항 (NFR)

| ID | 내용 |
|---|---|
| NFR-LM-SEC-01 | `AuthBackdrop` 은 props · hook · fetch 가 **전부 0개**여야 한다. 데이터 유출이 구조적으로 불가능함의 증명이다 |
| NFR-LM-SEC-02 | 세션이 없던 401 은 만료 프롬프트를 발화시키지 않는다 (공개 라우트 방어) |
| NFR-LM-SEC-03 | 만료 플래그는 **비영속** 스토어에 둔다. `authStore`(sessionStorage persist)에 두면 새로고침 후 되살아난다 |
| NFR-LM-A11Y-01 | 다이얼로그 이름은 `DialogTitle` 하나로만 만든다. `aria-label` 병행 금지 (Radix 가 `aria-labelledby` 를 우선하므로 "지정한 이름 ≠ 실제 이름" 자리가 생긴다) |
| NFR-LM-A11Y-02 | 단계 전환을 `role="status"` 로 announce 하고 코드 입력에 자동 포커스한다 |
| NFR-LM-A11Y-03 | 키보드만으로 로그인 완주 가능 (FR-AU-09 NFR-A11Y-04 승계) |
| NFR-LM-TEST-01 | e2e 는 타입체크·린트 **양쪽 모두 밖**이다. 폐기된 i18n 심볼의 잔존을 `src` 안의 전수 스캔 vitest 로 강제한다 |

## 기존 스펙에 대한 deviation

| 원 스펙 | 원 규정 | 변경 |
|---|---|---|
| `2026-06-09-fr-au-07-provider.md` 스코프 확정 · S1 · S2 · S3 | identifier-first 2단계 + 매칭 시 자동 리다이렉트 | 단일 화면 + SSO 버튼 노출 (X1 · X2) |
| `2026-05-21-fr-au-09-login-form-ui-d6.md` S5 | refresh 만료 → `/login?returnTo=` **강제 리다이렉트** + toast | 이동 없이 인라인 모달 (X3) |

`FR-AU-09` S5 의 리다이렉트와 toast 는 **원래 구현된 적이 없었다** — `client.ts` 는 `clearSession()`
만 하고 화면 전환을 하지 않았다. 즉 이 deviation 은 스펙과 코드의 기존 drift 를 함께 해소한다.

## 측정 가능한 완료 기준

- [ ] 미인증으로 `/` · `/issues/ATLAS-1` · `/settings/profile` 진입 시 모두 모달, 배경 실데이터 0
- [ ] 세션 만료 시 URL 불변 + 모달, 재로그인 후 같은 화면 갱신
- [ ] ESC · 오버레이 · X 3경로 모두 닫히지 않음
- [ ] `grep -rn "loginStrings.continueButton\|loginStrings.emailLabel" apps/web/e2e` → 0건
- [ ] `pnpm verify` 통과 · e2e 전량 통과
- [ ] 라이트/다크 양쪽 눈확인 (`docs/design/jira-parity-contract.md` §6)
