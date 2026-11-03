<!-- Auth_platform back-channel logout 수신 경로 permitAll — DEVELOPMENT.md §1.1-4 정식 예외 · 서명 검증 · 잔여위험 · 롤백 (S24) -->

# ADR — Auth_platform back-channel logout 수신 permitAll (DEVELOPMENT.md §1.1-4 정식 예외)

- 날짜: 2026-11-03
- 상태: 채택 (Accepted) — **2026-11-03 Maxi 게이트1 승인** (Auth 조정 세션의 S24 plan 승인과 함께 — 「로그인 없는
  POST /api/v1/auth/backchannel-logout (logout_token 서명 확인) 허락」)
- 관련 FR: FR-AU-04 (OIDC SSO) · FR-AU-09 (세션) — S24 Single Logout · 7일 상한
- Plan: Auth_platform `docs/superpowers/plans/2026-11-03-s24-single-logout.md` §1 계약 · §3 Issue Bunker
- 선례: `2026-07-15-slack-inbound-permitall-central.md` (slack) · `2026-07-17-git-webhook-inbound-permitall.md` (git · automation)

## 맥락 (Context)

S24 결정 D1 — **한 곳에서 로그아웃하면 세 앱 모두 로그아웃**. Auth 세션이 끝나면(로그아웃 · 7일 · 비밀번호 변경 ·
관리자 끄기 · 초기화 …) Auth 서버가 등록된 앱 서버마다 OIDC Back-Channel Logout 1.0 의 `logout_token` 을 POST 한다.

부르는 쪽은 브라우저가 아니라 **Auth 서버**다. BTS 가 발급한 JWT · 세션 쿠키 · CSRF 토큰이 없다. 지금 필터 체인은
`/api/**` 를 `authenticated()` 로 막으므로 그대로 두면 이 요청은 401 이고 Single Logout 은 Bunker 에서 죽는다.

## 결정 (Decision)

### D1. `INBOUND_WEBHOOK_PATHS` 에 `POST /api/v1/auth/backchannel-logout` 한 줄

같은 목록이 permitAll · CSRF-ignore · bearer skip 세 곳을 구동한다 (git ADR §D4). 메서드 고정 + 정확 경로 —
와일드카드 없음. `/api/**` 아래라 목록 순회가 `authenticated()` 보다 위인 기존 순서가 그대로 계약이다.

### D2. 필터가 비킨 자리에 RS256 서명 검증이 선다

permitAll 은 인증 제거가 아니라 **검증 주체 이관**이다 (slack ADR §D2). `LogoutTokenVerifier` 가 확인한다.

- 서명 — Auth JWKS(`<issuer>/oauth2/jwks`) 의 RS256 키. 비대칭이라 BTS 에는 서명할 비밀이 없다 (slack · git 의
  공유 HMAC 비밀보다 한 등급 위).
- header `typ = logout+jwt` 만 — Auth 가 발급한 access token · id token 을 logout token 으로 돌려 쓰지 못한다.
- `iss` = Auth · `aud` 에 BTS client_id · `exp` 있음 · 안 지남 · `iat` ±5분 · `events` 에 back-channel logout 키 ·
  `nonce` 없음 · `sid` 나 `sub`.
- 틀리면 400 `invalid_request`, 맞으면 200. 둘 다 `Cache-Control: no-store`.

decoder 는 컴포넌트 안에만 둔다. 두 번째 `JwtDecoder` @Bean 은 BTS 자신의 resource server 를 모호하게 만든다.

### D3. 할 수 있는 일은 세션 끊기뿐이다

맞는 token 이 하는 일은 `sessions` 폐기 + refresh chain 폐기뿐이다. 읽기 · 쓰기 · 권한 변경이 없다.
- `sid` — `sessions.auth_sid` 가 같은 살아 있는 세션 (V037).
- `sub` 만 — registration `auth` 의 authn provider(V011 OIDC 공용 행 — Auth 가 유일한 OIDC 등록이라 Auth 와 같다)로
  찾은 `user_external_accounts` 사용자의 `oidc` 세션 중 `created_at < iat + 1초`.
  로컬 비밀번호 세션은 남긴다. 기준이 `iat` 라 다시 보낸 token 이 그 뒤 로그인을 끊지 못한다 (`jti` 기억 불필요).

## 결과 (Consequences)

- Auth 「내 계정」 로그아웃 · 다른 앱 로그아웃 · 관리자 끄기가 Bunker 세션을 5초 안에 끊는다 (sid 검사 캐시 5초).
- Auth issuer 가 설정되지 않은 설치(로컬 · test)는 모든 token 을 400 으로 거절한다.

### 잔여 위험 (수용)

- **R1 놓침** — Auth 는 한 번만 보낸다 (5초 · 실패는 log). 놓치면 그 세션은 수명까지 남는다 — 최대 7일
  (`min(14일, auth_time + 7일)`). 계약 §1 `ponytail:` — 놓침이 잦으면 Auth 쪽 재시도 큐.
- **R2 Auth 키 탈취** — Auth 서명 키가 새면 임의 사용자의 Bunker 세션을 끊을 수 있다 (가용성만, 데이터 노출 없음).
  그 경우 Auth 로그인 자체가 무너진 상태라 이 경로가 더하는 위험은 작다.
- **R3 rate limit 없음** — 모든 비인증 경로 공통 기존 부채 (git ADR R4). 서명 확인 전 비용은 JWT 파싱 한 번.
  모르는 `kid` 로 Auth JWKS 를 다시 받게 만드는 길은 막았다 — JWKS 캐시 5분 · 다시 받기 30초에 한 번 ·
  연결 3초 · 읽기 5초 timeout (`LogoutTokenVerifier`). Auth 가 멈춰도 worker 는 최대 8초만 묶인다.
- **R4 끊긴 탭의 refresh 가 replay 감사로 남는다** — back-channel 은 브라우저 cookie 를 지울 수 없어, 끊긴 BTS 탭의
  다음 `/refresh` 가 `RefreshTokenService.rotate` 의 replay 분기(`SUSPICIOUS_REFRESH_REPLAY`)로 들어간다. 비밀번호 변경 ·
  관리자 끊기와 같은 기존 구조이고 사용자는 로그인 화면으로 간다. 감사 잡음 — 후속에서 활성 검사를 앞에 둔다.

### 롤백

`INBOUND_WEBHOOK_PATHS` 의 그 한 줄만 지우면 경로가 401 로 닫힌다. 세션 행의 `auth_sid` 는 남아도 해가 없다.
Auth 는 401 을 warn log 로만 남기고 로그아웃은 계속된다.

## 대안 (Rejected)

- **Auth 가 BTS 에 서비스 계정 Bearer 로 부른다** — OIDC 표준 밖이라 세 앱이 각자 발급 · 회전을 해야 하고,
  서명된 logout_token 이 이미 같은 일을 한다.
- **front-channel logout (iframe)** — 브라우저가 열려 있어야 하고 서드파티 쿠키 차단에 막힌다. 관리자 끄기처럼
  브라우저가 없는 경우를 못 덮는다.

## 승인 (Approval)

- `DEVELOPMENT.md §1.1-4` (인증 없는 엔드포인트 추가 금지) 의 정식 예외 — **2026-11-03 Maxi 게이트1 승인**.
- `DATA.md §1` 원칙 5 (필터 체인 변경 = 위험 경로) — `/bts` 위험 판정(MIGRATION · SEC_BE) + code-reviewer 리뷰.
