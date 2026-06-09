<!-- ADR — FR-AU-08b SSO(SAML/OIDC) 리다이렉트 계정 연결 + SSO 재인증: 연결 모드 성공 핸들러 분기(fail-closed) + LinkingIntent HttpSession 피기백 + SSO step-up -->

# ADR: SSO(SAML/OIDC) 계정 연결 + SSO 재인증 정책

> 결정일. 2026-06-09
> 상태. Accepted
> 컨텍스트. identity-access BC §2.8 FR-AU-08b (slug `fr-au-08b-sso-linking`)
> 선행. ADR `2026-06-09-account-linking-policy`(FR-AU-08 1차 — D1~D5 명시적 수동 연결·재인증 강제·충돌·마지막 수단), `2026-06-04-saml-sso-provider`(FR-AU-03 SAML SP-initiated), `2026-06-04-oidc-sso-provider`(FR-AU-04 OIDC), `2026-05-20-user-external-accounts-schema`(V002 매핑).

## 컨텍스트

FR-AU-08 1차(PR #103)는 **LDAP 동기 연결**만 지원했다. LDAP 은 1요청 안에서 `bindForLinking` 으로 즉시 소유를 증명하므로 동기 처리가 가능했다. 그러나 SSO(SAML/OIDC)는 IdP(Identity Provider — 외부 인증 제공자)로 **리다이렉트 왕복**(수 분)을 거치므로 동기 bind 가 없다. 1차는 이를 후속(FR-AU-08b)으로 분리하며 다음 두 한계를 남겼다.

1. SSO 외부 신원을 자기 계정에 명시적으로 연결하는 경로 부재.
2. **SSO 전용 사용자**(LOCAL 비밀번호·LDAP 없음)는 동기 재인증 수단이 없어 step-up 을 얻지 못해 연결/해제 자체가 불가.

FR-AU-08b 는 SSO 연결(CREATE)과 SSO 재인증(step-up)을 추가해 위 두 한계를 해소한다. 1차의 보안 기조(명시적 수동 연결만·이메일 자동연결 0·타계정 선점 거부·마지막 수단 보호·계정 열거 0)를 그대로 계승한다.

### LDAP 동기 연결과 본질적으로 다른 점

LDAP 은 1요청 동기라 "누가/무엇을 위해 연결을 시작했는가"를 요청 컨텍스트가 자연히 안다. SSO 는 IdP 왕복 동안 그 의도를 보존해야 하고, IdP 에서 돌아온 콜백을 **일반 로그인**(새 user JIT 프로비저닝 + 세션/JWT 발급)이 아니라 **연결 모드**(현재 userId 에 신원 attach, 새 세션/JWT 미발급)로 분기해야 한다. 이 분기가 보안 핵심이다 — 잘못하면 IdP 가 보낸 신원으로 새 로그인 세션이 발급되어 계정 전환/권한 변경(confused-deputy)이 일어난다.

## 결정

### D1. 연결 모드 성공 핸들러 분기 — fail-closed (보안 핵심)

SAML/OIDC 성공 핸들러는 콜백 시 HttpSession 의 연결 인텐트를 **issueTokens(세션/JWT 발급) 진입 이전에 먼저 소비**한다(1회용). 인텐트가 있으면 일반 로그인 발급 경로(`SessionService.create`/`JwtIssuer.issue`/`RefreshToken`/`AutoProvisionService.provision`)에 **물리적으로 진입하지 못하도록** early return 한다.

- **fail-closed**: 콜백 처리기([SsoLinkingCallbackProcessor])가 인텐트를 소비했다면 성공/충돌/실패/불일치 무관 **항상 true 를 반환**(모두 리다이렉트로 종결)하고, 핸들러는 true 면 즉시 return 한다. 따라서 분기 누락 시에도 발급이 일어나지 않는다(안전 측 실패).
- **새 세션/JWT/provision 미발생(EC12)**: LINK 모드는 attach 만 한다. 신규 user 생성 금지 — `userId` 는 이미 로그인한 본인의 id 이며 IdP 가 보낸 신원으로 새 계정을 만들지 않는다.
- 근거. IdP 의 새 인증은 "새 신원 소유"만 증명하지 "현재 세션이 일반 로그인이어야 함"을 의미하지 않는다. 발급을 fail-closed 로 막아야 탈취/혼동 경로를 봉쇄한다.

### D2. LinkingIntent 보존 — HttpSession 피기백 (Maxi 결정 D-a)

연결 의도(`LinkingIntent` — `mode`/`userId`/`sid`/`registrationId`/`providerType`/`expiresAt`)는 **HttpSession 인메모리 속성**으로만 보존한다. 연결 시작 XHR 가 `getSession(true)` 로 JSESSIONID 를 세팅하면서 인텐트를 저장하고, IdP 왕복 동안 같은 세션이 재사용되어 콜백 성공 핸들러가 1회용으로 소비한다.

- **URL 노출 금지**: userId/sid 를 RelayState/state 쿼리에 싣지 않는다(reflection/탈취 차단). 서명 티켓 in RelayState 안은 배제.
- **1회용·단명(≤5분)**: `consume` 은 읽은 즉시 속성을 제거(재소비 차단)하고 만료를 검사한다. 만료 윈도우는 step-up 윈도우(5분)와 정합(EC19) — reauth→link 연쇄 시 step-up 선만료를 피한다.
- **영속 안 함**: 인텐트는 DB 에 저장하지 않는다(마이그레이션 0). HttpSession 직렬화 대비 `Serializable` 구현.

### D3. SSO step-up — SSO 전용 사용자 지원 (Maxi 결정 D-b)

SSO 재인증 경로(`POST /reauth/sso/start` → IdP 왕복 → 콜백)를 추가해 **SSO 전용 사용자도 step-up 을 얻어 연결/해제할 수 있게** 한다(1차 한계 완전 해소).

- **EC9 동형 검증**: 콜백서 돌아온 `(providerId, externalSubject)` 신원이 **현재 user 에 이미 연결**돼 있을 때만 step-up 을 grant 한다(`ReauthService.reauthenticateSso`). 미연결·타 신원이면 grant 하지 않는다(EC8 — 임의 신원을 빌려 step-up 을 얻는 우회 차단). LDAP 재인증의 EC9 제약과 동형이며 공통 헬퍼로 단일화.
- **registrationId·providerType 일치(C3)**: LINK 와 동형으로, 인텐트가 시작한 registration 과 콜백 registration 이 일치할 때만 진행한다.
- **sid 신뢰 출처(FR8)**: grant 대상 sid 는 `reauth/sso/start` 의 인증 JWT `sid` 클레임에서만 복사한다. 요청 바디/헤더의 sid 는 일절 수용하지 않는다(위조 차단, 1차 불변식 계승). 해제·목록(`DELETE/GET /links`)은 1차에서 이미 provider 무관이라 코드 변경 없이 SSO 전용 사용자가 step-up 만 얻으면 사용 가능하다.

### D4. JSESSIONID 쿠키 정책 — SameSite=None (base) + secure (prod 전용) (B2)

SAML ACS(Assertion Consumer Service — IdP 가 인증 결과를 POST 로 돌려보내는 SP 측 엔드포인트)는 **cross-site POST** 왕복이다. 시작 XHR 가 만든 세션의 JSESSIONID 가 이 왕복까지 동반돼야 인텐트가 살아남는다.

- **SameSite=None (base `application.yml`)**: `SameSite=Strict` 면 SAML cross-site POST 에 JSESSIONID 가 미전송되어 왕복이 깨진다. JSESSIONID 는 HttpOnly·SSO 왕복 전용이고, JWT API 는 쿠키 인증이 아니며 토큰식 CSRF 로 보완되므로 안전하다. CSRF 쿠키(SameSite=Strict)는 무변경(ACS 는 CSRF skip 경로).
- **secure: true 는 `application-prod.yml` 에만**: base/dev/test 는 http 부팅(RANDOM_PORT 통합테스트 포함)이라 base 에 `secure:true` 를 두면 JSESSIONID 가 미방출되어 SSO 왕복이 사망한다(C4). 운영(prod)만 https 라 secure 를 켠다.
- **session-fixation `changeSessionId`**: SSO 체인에 명시 설정해 인증 성공 시 세션 ID 가 바뀌어도 인텐트 속성이 이관되게 한다(EC10). `newSession` 전략이면 인텐트가 유실되므로 금지.

### D5. 충돌/마지막 수단 — 1차(account-linking-policy D5) 계승

- **타계정 선점 거부**: 연결 대상 신원이 이미 다른 user 에 매핑돼 있으면 거부(`?link=conflict`, 계정 열거 0). attach 는 충돌검사 통과 후 **순수 INSERT**(ON CONFLICT user_id 보존이 타계정 선점을 조용히 삼켜 거짓 success 가 나는 것 차단).
- **동시 콜백 직렬화(EC18)**: `(providerId, externalSubject)` advisory lock(`pg_advisory_xact_lock(hashtextextended(...))` 전폭 해시 — 절단 금지)으로 check-then-insert 를 직렬화한다. 다중 탭/재시도가 같은 신원을 동시에 붙여도 한쪽만 Created·다른쪽 Conflict.
- **마지막 수단 보호 + 카운트(C4)**: 해제 후 남는 로그인 수단 = **enabled provider 링크 수 + LOCAL 비밀번호 보유(0/1)**. 비활성 provider 링크는 실제 로그인 불가이므로 카운트 제외 — 포함 시 "로그인 불가한데 해제도 막힌" 영구 락 발생. TOCTOU 는 userId advisory lock 직렬화로 차단(1차 동형).

## 결과

- **스키마 변경 없음**. `user_external_accounts`(V002) 재사용. 인텐트는 HttpSession 인메모리(마이그레이션 0, 신규 권한코드 0, cross-BC 0).
- **신규 엔드포인트 2종** — `POST /api/v1/auth/account/links/sso/start`(JWT + step-up) · `POST /api/v1/auth/account/reauth/sso/start`(JWT). 둘 다 XHR 로 HttpSession 에 인텐트 저장 + JSESSIONID 세팅 후 `{authorizeUrl}` 반환 → SPA 가 SSO 로 네비게이트(2단계 흐름).
- **성공 핸들러 수술** — SAML/OIDC 성공 핸들러가 콜백서 인텐트-first 로 분기(LINK=attach·REAUTH=grant·일반=JIT 무변경). 콜백 providerId 해소는 enabled provider 로만(OIDC `findEnabledByRegistrationId` 추가로 SAML 과 대칭, start↔콜백 TOCTOU 차단, B1/EC16).
- **공통 추출** — JWT subject+sid 추출·step-up 게이트·errorResponse 를 `AccountLinkJwtSupport` 로 단일화해 두 컨트롤러가 같은 보안 로직을 공유(drift 차단, B2).
- **백엔드 전용 1차** — 프론트 "계정 연결" 설정 페이지(D6) + E2E(D7, 진짜 브라우저 start→IdP→ACS POST 왕복)는 후속 단계. 통합테스트는 브라우저 왕복을 흉내내지 않고 실 Postgres + 서비스/processor 스택을 직접 구동해 연결 모드 분기·충돌·마지막수단·EC9 를 실증한다(C3 분해).

## 보안 (DEVELOPMENT.md §1)

- **confused-deputy 차단**. 연결 모드 fail-closed(D1) — IdP 신원으로 새 세션/JWT/계정이 발급되지 않는다.
- **역방향 탈취 차단**. 연결은 현재 세션 step-up 신선도도 요구(account-linking-policy D4 계승) — 탈취 세션이 공격자 SSO 신원을 피해자 계정에 붙이는 것을 막는다.
- **계정 탈취 차단**. 이메일 자동연결 미도입 + 타계정 선점 거부(D5).
- **self-lockout 방지**. enabled 링크 + LOCAL 카운트의 마지막 수단 해제 거부(D5/C4).
- **계정 열거 0**. 콜백 결과는 서버 고정 설정 경로(`/settings/account-links`) + 일반화 status 쿼리뿐. external_subject 등 PII 는 쿼리/로그에 싣지 않는다(open-redirect 0, EC14/EC15).
- **위조 차단**. step-up grant sid 는 인증 JWT 클레임 출처만(FR8). PAT 는 sid 가 없어 403(JWT 전용, 1차 일관).
- **쿠키 안전**. JSESSIONID HttpOnly + SameSite=None(SSO 왕복) + prod secure(D4). JWT 는 sessionStorage·토큰식 CSRF.

## 관련

- 마스터플랜 §2.8 (`docs/plan/product/identity-access.md`)
- 선행 ADR `docs/decisions/2026-06-09-account-linking-policy.md` (FR-AU-08 1차 D1~D5)
- 선행 ADR `docs/decisions/2026-06-04-saml-sso-provider.md` / `2026-06-04-oidc-sso-provider.md`
- SDD 19.4 (계정 통합)
