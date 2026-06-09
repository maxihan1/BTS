# FR-AU-08b — SSO(SAML/OIDC) 리다이렉트 방식 계정 연결

> slug: fr-au-08b-sso-linking
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

FR-AU-08 1차(PR #103)에서 LDAP 동기 연결만 완료. SAML/OIDC 리다이렉트 방식 계정 연결은 후속(FR-AU-08b)으로 분리됨.

로그인된 사용자가 자신의 SSO 외부 신원(SAML/OIDC)을 자기 계정에 **명시적 수동 연결**. 이메일 자동 연결 미도입(계정 탈취 차단, FR-AU-06/07/08 1차 보안 기조 일관). 보안 핵심 SSO 성공 핸들러를 "연결 모드"로 분기 필요.

classify 결과 — type=auth, agent=security-engineer, primary_bc=identity-access.

## 도메인 정리 (/bts-domain)

- **BC**: identity-access. 담당 security-engineer.
- **grill-with-docs 처리**: 정의된 FR(product §2.8 D4의 SSO 후속 위임 + ADR `account-linking-policy` D1~D5 확정)이라 대화형 grill-with-docs 스킵. 직접 도메인 분석 + 진짜 갈림길은 spec 단계 AskUserQuestion으로 Maxi 위임(프로젝트 관례 "office-hours 스킵(정의된 FR)"과 일관).

### 재사용 엔티티 (전부 기존, 신설 0 — git grep 실재 확인)

- `user_external_accounts`(V002) — provider_id × external_subject UNIQUE, user_id FK CASCADE. 다대일 이미 지원. **마이그레이션 0 예상**.
- `AutoProvisionService.provision(providerId, attrs)` — users UPSERT + user_external_accounts UPSERT 단일 TX. **일반 로그인 JIT 경로 (무변경 회귀가드 대상)**.
- `AccountLinkService`(account/) — link/unlink/listLinks. **unlink·listLinks는 이미 provider 무관** (SSO 링크도 해제·표시 가능). link()만 LDAP 동기 bind 전제.
- `ReauthService` + `StepUpService`(account/) — Caffeine 아닌 인메모리 `ConcurrentHashMap<sid, expiresAt>` 5분 윈도우. reauth는 LOCAL(verify)/LDAP(bind+EC9) 동기만.
- `ExternalAccountRepository` — provisionUser(UPSERT)/findByProviderIdAndExternalSubject/deleteByIdAndUserId(소유검증)/acquireUserLock(advisory). 공통(LDAP 전용 아님).
- `Saml2AuthenticationSuccessHandler` / `OidcAuthenticationSuccessHandler` — nameId/sub → LdapProvisionAttrs → provision → 세션/JWT 발급 → RelayState/returnTo 복귀. **이 두 핸들러가 '연결 모드' 분기 수술 대상**.
- 필터체인 3개 — SAML(Order1)/OIDC(Order2) IF_REQUIRED `HttpSession`(왕복 생존, SSO 동작이 증명) + STATELESS API(Order3) JWT.

### 핵심 도메인 난점 (LDAP 동기 연결과 본질적으로 다른 점)

LDAP 연결은 1요청 동기(bindForLinking로 즉시 소유 증명). SSO는 IdP로 **리다이렉트 왕복**이라 동기 bind가 없다. 따라서:
1. "**누가/무엇을 위해** 연결을 시작했는가"(현재 로그인 userId + 연결 의도)가 IdP 왕복(수 분)을 살아남아야 한다.
2. IdP에서 돌아왔을 때 성공 핸들러가 **일반 로그인(JIT 새 user 생성/로그인)** 이 아니라 **연결 모드(현재 userId에 external_subject attach, 새 세션/JWT 미발급)** 로 분기해야 한다 — 이것이 "보안 핵심 성공 핸들러 수술".
3. JWT(sessionStorage)는 전체 페이지 네비게이션 시 전송 안 됨 → 연결 시작은 XHR(JWT+step-up)로 의도를 서버에 심고, 그 뒤 브라우저가 SSO authorize로 네비게이트하는 2단계 필요.

### 신규 도메인 개념 (glossary 후보 — Maxi 승인 후 추가)

- **연결 인텐트 (LinkingIntent)** — 로그인 사용자가 SSO 연결을 개시할 때 만드는 단명 서버측 상태(`linkingUserId` + 만료 + 대상 registrationId). IdP 왕복 동안 보존, 콜백서 1회용 소비. URL 노출 금지(reflection/탈취 차단) → `HttpSession` 보존이 후보.
- **연결 모드 성공 핸들러 (linking-mode success handler)** — 성공 핸들러가 LinkingIntent 존재 시 일반 로그인 대신 연결 동작으로 분기. **새 세션/JWT 미발급**(권한 변경/confused-deputy 차단).
- **SSO 재인증 (SSO step-up)** — LOCAL/LDAP 동기 자격이 없는 SSO 전용 사용자가 step-up을 얻는 경로(이미 연결된 IdP로 리다이렉트 → 돌아온 subject가 현재 user의 링크와 일치하는지 EC9 동형 확인 → grant). 스코프 포함 여부는 Maxi 결정(아래).

### 보안 모델 (1차 기조 계승)

- 명시적 수동 연결만(이메일 자동연결 금지) — IdP 인증 성공 = 새 신원 소유 증명(D1).
- 타계정 선점 거부(409·계정열거0)·동일계정 멱등·마지막수단 해제거부(409·advisory lock TOCTOU) — D5 그대로.
- **연결은 현재 세션의 step-up 신선도도 요구**(D4). IdP 새 인증은 "새 신원 소유"만 증명하지 "현재 계정 주인임"은 증명 못 함 → 탈취 세션이 공격자 SSO 신원을 피해자 계정에 붙이는 역방향 탈취 차단 위해 현재 세션 step-up 필수.

### 기존 결정 충돌

- 없음. ADR `account-linking-policy`(D1~D5)·`saml-sso-provider`·`oidc-sso-provider`(SSO 전용 체인+JIT 재사용) 위에 증분. D6 후속(SDD 19.4 deviation는 1차서 이미 기록).
- 관련 ADR: 신규 `docs/decisions/2026-06-09-sso-account-linking.md` 생성 예정(연결 모드 핸들러 + LinkingIntent 보존 메커니즘 + SSO step-up 범위 결정 기록).

### spec 단계로 넘길 Maxi 결정 (갈림길)

1. **연결 의도 보존 메커니즘** — (A·권장) HttpSession 피기백(link-start XHR가 JSESSIONID 세팅+intent 저장→SSO 왕복 재사용, URL 미노출) vs (B) 서명 티켓 in RelayState/state(reflection 위험).
2. **SSO step-up 범위** — SSO 전용 사용자가 연결/해제하려면 SSO 재인증 경로가 필요. (A) SSO step-up 포함(SSO-only 사용자 연결·해제 완전 지원) vs (B) 이번엔 SSO 연결 CREATE만(step-up은 기존 LOCAL/LDAP 보유자 한정, SSO-only는 다음으로) — 범위/크기 트레이드오프.
3. **연결 모드 콜백 결과 전달** — 설정 페이지로 리다이렉트 시 성공/실패(409 등) 상태를 쿼리파라미터로(계정열거0 일반 메시지).

## 스펙

전체 스펙. [docs/specs/2026-06-09-fr-au-08b-sso-linking.md](../specs/2026-06-09-fr-au-08b-sso-linking.md)

핵심 요약.
- 신규 엔드포인트 2 — `POST /links/sso/start`(JWT+step-up)·`POST /reauth/sso/start`(JWT). 둘 다 XHR로 HttpSession에 의도 저장+JSESSIONID 세팅 후 authorizeUrl 반환 → SPA가 SSO로 네비게이트.
- SAML/OIDC 성공 핸들러 **연결 모드 분기**(fail-closed) — intent 있으면 일반 로그인(JWT/세션/provision) 진입 불가, LINK=현재 userId attach·REAUTH=본인 링크 일치 시 step-up grant. 새 세션/JWT 미발급.
- 충돌/마지막수단/계정열거0/step-up 1차 기조 계승. 마이그레이션 0(user_external_accounts 재사용).
- Maxi 결정 — 의도 보존=HttpSession, 범위=SSO 연결+SSO 재인증(SSO 전용 사용자 완전 지원), 콜백=쿼리파라미터 상태.

## Brainstorming Check

✅ 통과 (1 iteration, 적대적 보안 갭 분석). B1(OIDC enabled 콜백 비대칭)·B2(SAML ACS 쿠키 왕복+session-fixation)·C2(동시 콜백 race)·C3(REAUTH registrationId 일치)·C5(fail-closed) 등 코드 근거 갭 전부 스펙 반영.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
