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

## Plan

> 전 task agent: `security-engineer`. 모듈: identity-access(단일 BC, jdbc-only). 마이그레이션 0.
> 경로 약어: `M = backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity`, `T = backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity`.

### Task 1. SsoLinkingIntent 모델 + SsoLinkingIntentStore (HttpSession)

**메타**.
- agent: `security-engineer`
- files: [`M/account/SsoLinkingIntent.kt`, `M/account/SsoLinkingIntentStore.kt`, `T/account/SsoLinkingIntentStoreTest.kt`]
- depends-on: []

**RED**: `T/account/SsoLinkingIntentStoreTest.kt` — `put` 후 같은 MockHttpSession에서 `consume`이 동일 intent 반환 + 두 번째 consume은 null(1회용) + 만료(expiresAt 과거)면 consume null. LINK/REAUTH 모드 구분 보존.

**GREEN**: `SsoLinkingIntent`(sealed 또는 data class — `mode(LINK/REAUTH)`, `userId: UUID`, `sid: UUID?`, `registrationId: String`, `providerType: ProviderType`, `expiresAt: Instant`). `SsoLinkingIntentStore`(@Component, `clock` 주입) — `put(session, intent)` = `session.setAttribute(KEY, intent)`, `consume(session)` = get + `removeAttribute` + 만료 검사(null 반환). KEY 상수.

**REFACTOR**: KDoc(보존 메커니즘·1회용·단명 ≤5분). intent는 Serializable(세션 직렬화 대비).

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*SsoLinkingIntentStoreTest"`

### Task 2. ExternalAccountRepository — acquireSubjectLock + insertLink (race-safe 링크)

**메타**.
- agent: `security-engineer`
- files: [`M/provider/ldap/ExternalAccountRepository.kt`, `T/provider/ldap/ExternalAccountRepositoryTest.kt`]
- depends-on: []

**RED**: 기존 `ExternalAccountRepositoryTest`에 추가 — `insertLink(providerId, externalSubject, userId, groups)`가 신규 행 INSERT(RETURNING) + **중복 (provider_id, external_subject)면 예외**(UPSERT 아님 — DO UPDATE로 user_id 삼키기 차단). `acquireSubjectLock(providerId, externalSubject)`가 같은 tx 내 호출 가능(스모크).

**GREEN**: `insertLink` = 순수 `INSERT ... RETURNING`(ON CONFLICT 없음). `acquireSubjectLock` = `pg_advisory_xact_lock(hashtextextended(:key, 0))`, key = `"$providerId:$externalSubject"`(전폭 해시, 절단 금지 — [[advisory-lock-bigint-toctou]]).

**REFACTOR**: 기존 `acquireUserLock` 패턴과 KDoc 일관. SQL 상수 추출.

**검증**: `:backend:modules:identity-access:test --tests "*ExternalAccountRepositoryTest"`

### Task 3. OidcProviderConfigRepository.findEnabledByRegistrationId (B1 — 콜백 enabled 대칭)

**메타**.
- agent: `security-engineer`
- files: [`M/provider/oidc/OidcProviderConfigRepository.kt`, `T/provider/oidc/OidcProviderConfigRepositoryTest.kt`, `T/provider/oidc/DbClientRegistrationRepositoryTest.kt`, `T/provider/oidc/OidcAuthenticationSuccessHandlerTest.kt`]
- depends-on: []

**RED**: `findEnabledByRegistrationId(registrationId)`가 enabled=true만 반환, 비활성 row는 null(SAML `findEnabledByRegistrationId` 동형). 기존 `findByRegistrationId`(무관)는 무변경.

**GREEN**: SQL `WHERE registration_id = :registrationId AND enabled = TRUE`. **인터페이스 `OidcProviderConfigReader`에 추상 메서드 추가**(핸들러가 인터페이스 타입 의존이라 인터페이스에 올려야 호출 가능).

**⚠️ B3 컴파일 깨짐(plan리뷰)**: 인터페이스 확장은 모든 구현/fake를 깬다. `grep -rn "OidcProviderConfigReader" T/` 로 전수 후 — `DbClientRegistrationRepositoryTest.kt:32`의 `FakeConfigRepo`(익명 구현)·`OidcAuthenticationSuccessHandlerTest.kt`의 fake/mock에 신규 메서드 stub 추가. [[plan-files-constructor-injection-existing-tests]] 인터페이스 버전.

**REFACTOR**: SAML/OIDC enabled 조회 KDoc 일관.

**REFACTOR**: SAML/OIDC enabled 조회 KDoc 일관.

**검증**: `:backend:modules:identity-access:test --tests "*OidcProviderConfigRepositoryTest"`

### Task 4. AccountLinkService.linkExternalSubject (lock+check+insert, provision 미호출)

**메타**.
- agent: `security-engineer`
- files: [`M/account/AccountLinkService.kt`, `T/account/AccountLinkServiceTest.kt`]
- depends-on: [2]

**RED**: `linkExternalSubject(userId, providerId, externalSubject, groups): LinkOutcome` — 없음→`Created`(insertLink 호출), 현재 user 소유→`AlreadyLinked`(멱등, insert 안 함), 타 user 소유→`AccountLinkConflictException`. `acquireSubjectLock` 선행 호출 검증(mock verify). **provision/AutoProvisionService 미호출**(verify exactly=0 — 신규 user 생성 금지).

**GREEN**: `acquireSubjectLock` → `findByProviderIdAndExternalSubject` 분기 → none: `insertLink`+Created / same user: AlreadyLinked / other: throw. 기존 `link()`(LDAP)의 충돌 분기 로직 공유 추출.

**REFACTOR**: `link()`와 공통 분기 헬퍼. KDoc(SSO는 bind 없음 — IdP 인증이 성공 핸들러서 선행).

**검증**: `:backend:modules:identity-access:test --tests "*AccountLinkServiceTest"`

### Task 5. ReauthService.reauthenticateSso (subject∈links → grant(sid))

**메타**.
- agent: `security-engineer`
- files: [`M/account/ReauthService.kt`, `T/account/ReauthServiceTest.kt`]
- depends-on: []

**RED**: `reauthenticateSso(userId, sid, providerId, externalSubject)` — `(providerId, externalSubject)`가 userId에 이미 연결(EC9 동형)→`stepUpService.grant(sid)`. 미연결/타 신원→`ReauthChallengeFailedException`(grant 안 함). bind 없음(이미 IdP 인증됨).

**GREEN**: `findByProviderIdAndExternalSubject(providerId, externalSubject)?.userId == userId` 확인 후에만 grant. 기존 `reauthenticateLdap` EC9 패턴 재사용(bind 부분만 제거).

**REFACTOR**: LDAP/SSO reauth 공통 EC9 검증 추출. KDoc.

**검증**: `:backend:modules:identity-access:test --tests "*ReauthServiceTest"`

### Task 6. SsoLinkingCallbackProcessor (모드 분기 + fail-closed + 복귀)

**메타**.
- agent: `security-engineer`
- files: [`M/account/SsoLinkingCallbackProcessor.kt`, `T/account/SsoLinkingCallbackProcessorTest.kt`]
- depends-on: [1, 4, 5]

**RED**: `process(session, providerType, registrationId, providerId, externalSubject, groups, response): Boolean`.
- intent 없음→false(일반 로그인 위임).
- LINK: `intent.registrationId == registrationId` 불일치→`?link=error`. linkExternalSubject 결과 Created→`?link=success`/AlreadyLinked→`?link=already_linked`/Conflict→`?link=conflict`. **새 세션/JWT/provision 미발생**(true 반환, 핸들러가 발급 경로 진입 안 함).
- REAUTH: registrationId 불일치→`?reauth=failed`. reauthenticateSso 성공→`?reauth=success`/실패→`?reauth=failed`.
- 복귀 대상 = **서버 고정 설정 경로**(예 `/settings/account-links`)+status 쿼리(open-redirect 0). 항상 true(intent 소비됨).

**GREEN**: 위 분기. `sendRedirect(fixedSettingsPath + "?...")`. intent.providerType==providerType도 검증.

**REFACTOR**: status 쿼리 빌더. KDoc(fail-closed 계약 — 호출 핸들러는 true면 즉시 return).

**검증**: `:backend:modules:identity-access:test --tests "*SsoLinkingCallbackProcessorTest"`

### Task 7. SAML/OIDC 성공 핸들러 intent-first 분기 (fail-closed + enabled 해소)

**메타**.
- agent: `security-engineer`
- files: [`M/provider/saml/Saml2AuthenticationSuccessHandler.kt`, `M/provider/oidc/OidcAuthenticationSuccessHandler.kt`, `T/provider/saml/Saml2AuthenticationSuccessHandlerTest.kt`, `T/provider/oidc/OidcAuthenticationSuccessHandlerTest.kt`, `T/config/SamlSecurityConfigTest.kt`, `T/config/OidcSecurityConfigTest.kt`]
- depends-on: [3, 6]

**RED**: 두 핸들러 단위 테스트 —
- intent 있음 + 활성 provider → `processor.process` 호출 → **issueTokens/SessionService.create/JwtIssuer/provision 미호출**(verify exactly=0, EC12 fail-closed).
- intent 있음 + **비활성 provider(콜백 enabled 재해소 실패)** → `?link=error`/`?reauth=failed`(B1/EC16), 발급 0.
- intent 없음 → 기존 JIT 로그인 무변경(회귀, EC1).
- **C1(plan리뷰)**: 두 핸들러 setUp의 생성자 호출에 신규 의존(intentStore/processor/(OIDC)enabled reader) mock 추가. 신규 의존은 기존 `clock: Clock = systemUTC()` **기본값 앞**에 배치. 구성 사이트는 main 1 + test 1씩(grep 전수 확인).

**GREEN**: 핸들러 시작부에서 `intentStore`로 분기 판단. intent 있으면 enabled 해소(SAML `findEnabledByRegistrationId`, OIDC Task 3 신규) → providerId → `processor.process(...)` → return(발급 경로 진입 안 함). intent 없으면 기존 경로. externalSubject/registrationId 추출은 기존 코드 재사용.

**⚠️ B1 SSO 체인 활성 회귀가드(plan리뷰)**: `SamlSecurityConfig`/`OidcSecurityConfig`는 `@ConditionalOnBean(..., <Handler>::class)`로 핸들러 빈 존재 시에만 SSO 체인 활성. 핸들러 생성자에 신규 의존 추가 후에도 **핸들러 빈이 여전히 생성 + 두 SSO 체인 활성 유지**를 `SamlSecurityConfigTest`/`OidcSecurityConfigTest`(컨텍스트 로드)로 RED 검증. 빈 생성 실패 시 SSO 로그인 통째 404(가짜그린 위험). 신규 의존(`SsoLinkingIntentStore`/`SsoLinkingCallbackProcessor`)은 무조건 `@Component`라 wiring 정상 전망이나 부팅 실증 필수.

**REFACTOR**: 두 핸들러 공통 분기 흐름 KDoc. providerType 명시(SAML/OIDC).

**검증**: `:backend:modules:identity-access:test --tests "*Saml2AuthenticationSuccessHandlerTest" --tests "*OidcAuthenticationSuccessHandlerTest" --tests "*SamlSecurityConfigTest" --tests "*OidcSecurityConfigTest"`

### Task 8. SSO 체인 세션 구성 — session-fixation 명시 + JSESSIONID SameSite (B2/EC17/EC10)

**메타**.
- agent: `security-engineer`
- files: [`M/config/SamlSecurityConfig.kt`, `M/config/OidcSecurityConfig.kt`, `backend/modules/identity-access/src/main/resources/application.yml`, `T/config/SsoSessionCookieConfigTest.kt`]
- depends-on: []

**RED**: (a) SSO 인증 성공 시 session-fixation `changeSessionId`로 intent 세션 속성이 **이관**되는지(속성 보존) 검증. (b) JSESSIONID SameSite 속성이 SAML ACS cross-site POST 왕복을 깨지 않는 값(`None; Secure`)인지 설정 검증.

**GREEN**: 두 SSO 체인에 `.sessionManagement { it.sessionFixation { sf -> sf.changeSessionId() } }` 명시. base `application.yml`에 `server.servlet.session.cookie.same-site: none`(JWT API는 쿠키 인증 아님 + CSRF 토큰식이라 안전, JSESSIONID는 HttpOnly·SSO 왕복 전용). **CSRF 쿠키(SameSite=Strict)는 무변경**(ACS는 CSRF skip 경로).

**⚠️ C4 secure 범위(plan리뷰)**: `cookie.secure: true`는 **`application-prod.yml`에만**(base/dev/test는 http 부팅 — RANDOM_PORT 통합테스트 포함 — 이라 base에 secure:true 두면 JSESSIONID 미방출로 SSO 왕복 사망). base는 SameSite만.

**REFACTOR**: 보안 근거 KDoc(왜 None인가 — SAML POST cross-site, 토큰식 CSRF로 보완. secure는 prod 한정).

**검증**: `:backend:modules:identity-access:test --tests "*SsoSessionCookieConfigTest"`. (cross-site 왕복 자체는 E2E(D7) 위임 — C3 참조.)

### Task 9. SsoAccountLinkController — links/sso/start + reauth/sso/start

**메타**.
- agent: `security-engineer`
- files: [`M/web/SsoAccountLinkController.kt`, `T/web/SsoAccountLinkControllerTest.kt`]
- depends-on: [1, 3, 11]

**RED**(MockMvc):
- `POST /api/v1/auth/account/links/sso/start` — JWT 없음/PAT→403, step-up 없음→403 `step_up_required`, 유효 step-up+활성 SAML/OIDC registrationId→200 `{authorizeUrl}`(SAML `/saml2/authenticate/{reg}`·OIDC `/oauth2/authorization/{reg}`) + intent 저장 + **JSESSIONID Set-Cookie 헤더 실제 존재 assert**(C2 — 가짜그린 회피, getSession(true) 방출 실증).
- `POST /api/v1/auth/account/reauth/sso/start` — JWT 필수(step-up 불요), 활성 registrationId→200 `{authorizeUrl}` + ReauthIntent(userId+sid 동일 JWT 출처, FR8).
- 입력 검증 매트릭스(FR7): providerType×registrationId 불일치→404, LOCAL/LDAP type→400, 미존재/비활성→404, registrationId 형식 위반→400.

**GREEN**: 컨트롤러 — Task 11의 **공유 헬퍼**(`AccountLinkJwtSupport`)로 subject+sid 동시 추출 + step-up 게이트(link만) + errorResponse(B2 — private 복붙 금지). 검증 매트릭스, `request.getSession(true)`+`intentStore.put`, authorizeUrl 구성(검증 통과 config 값만). 로컬 `@ExceptionHandler`+에러코드(기존 상수 재사용+신규 `provider_not_found`).

**REFACTOR**: 검증 로직 헬퍼. KDoc(2단계 흐름 — XHR start→SPA 네비게이트).

**검증**: `:backend:modules:identity-access:test --tests "*SsoAccountLinkControllerTest"`

### Task 10. 통합테스트(S1~S5/EC) + ADR + product 동기화

**메타**.
- agent: `security-engineer`
- files: [`T/integration/SsoAccountLinkingIntegrationTest.kt`, `docs/decisions/2026-06-09-sso-account-linking.md`, `docs/plan/product/identity-access.md`]
- depends-on: [4, 5, 6, 7, 8, 9]

**RED/GREEN**(prod 프로파일 + RANDOM_PORT, identity-access 통합테스트 부팅 레시피 [[identity-access-prod-randomport-boot-recipe]]):
- S1 LINK 신규 attach(새 세션/JWT 미발급 확인) · S4 타계정 선점 거부(`?link=conflict`, attach 0) · EC2 멱등.
- S2/EC9 REAUTH 성공 grant · EC8 타 신원 grant 안 함 · C3 registrationId 불일치 grant 안 함.
- S3/C4 SSO-only 사용자: SSO 재인증 step-up→새 SSO 연결+기존 링크 해제(enabled 2개중 1해제 204, 마지막 1개 409).
- EC1 일반 SSO 로그인 회귀 0(intent 없음→JIT 무변경) · EC16 비활성 provider 콜백 거부 · EC18 동시 콜백 직렬화.
- **C3 분해(plan리뷰 — SAML ACS cross-site POST 왕복은 브라우저 의존이라 JVM 통합테스트 불가, 기존 `SamlAuthFlowIntegrationTest`도 제외함)**: B2/EC17을 (a) **session-fixation 속성 이관**은 성공 핸들러 + MockHttpSession 단위 검증(`changeSessionId` 후 intent 생존), (b) **cross-site 쿠키 동반**은 Task 8 SameSite=None 설정값 검증으로 분해. 진짜 브라우저 왕복(start→IdP→ACS POST→intent 소비)은 **E2E(D7) 위임** — 본 task에 "통합테스트 왕복 실증" 단일 항목 두지 말 것(가짜그린/미구현 회피).

**문서**: ADR `2026-06-09-sso-account-linking`(연결 모드 핸들러·LinkingIntent HttpSession·SSO step-up·B2 쿠키(SameSite=None+prod secure) 결정 기록). product §2.8 D4 마커 갱신("SSO(SAML/OIDC) 연결 FR-AU-08b 완료, D6 UI·D7 E2E 후속"). **FR 카운트 무변경**(D6/D7 미완→33/122 유지). verify-master-plan PASS.

**검증**: `:backend:modules:identity-access:test` 전체 + `bash scripts/verify-master-plan.sh`

### Task 11. AccountLink JWT/step-up 공통 추출 (B2 — private 헬퍼 재사용)

**메타**.
- agent: `security-engineer`
- files: [`M/web/AccountLinkJwtSupport.kt`, `M/web/AccountLinkController.kt`, `T/web/AccountLinkControllerTest.kt`, `T/web/AccountLinkJwtSupportTest.kt`]
- depends-on: []

**RED**(plan리뷰 B2): `resolveJwtClaims`(subject+sid 동시·JWT 출처만)·`requireStepUp`·`errorResponse`가 현재 `AccountLinkController` **private**(`:266`/`:285`/`:316`)이고 `AccountLinkJwtClaims`는 web 패키지 **private top-level**(`:354`) → 새 `SsoAccountLinkController`에서 재사용 불가. 공유 컴포넌트 `AccountLinkJwtSupport`(@Component 또는 internal) 단위 테스트 — JWT subject/sid 추출(sid는 클레임만, 1차 불변식), step-up 판정, 표준 errorResponse. PAT(jwt=null)→null.

**GREEN**: `AccountLinkJwtSupport`로 추출 + `requireStepUp`(StepUpService 주입) + `errorResponse`. `AccountLinkClaims` 공유 data class(기존 private 제거·치환). `AccountLinkController`를 이 헬퍼로 **리팩토링**(동작 보존 — 기존 4 엔드포인트 테스트 그대로 green, 보안 로직 단일화·drift 차단).

**REFACTOR**: KDoc(sid 신뢰출처·step-up 윈도우). 기존 컨트롤러 테스트 회귀 0 확인.

**검증**: `:backend:modules:identity-access:test --tests "*AccountLinkControllerTest" --tests "*AccountLinkJwtSupportTest"`

## Plan 메타

- task 수: 11 (plan리뷰 B2로 Task 11 공통추출 추가)
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: 전 task identity-access 단일 모듈(동일 test 컴파일 단위 → wave 직렬화 요인 [[bts-plan-wave-gradle-module-compile]]). 의존 그래프: T1·T2·T3·T5·T8·T11 무의존(초기 wave) → T4(←2)·T6(←1,4,5)·T9(←1,3,11) → T7(←3,6) → T10(←4,5,6,7,8,9).
- 추가 검증: ktlint + detekt(`--rerun-tasks`, false-green 회피 [[backend-detekt-lint-debt-unmasked]]) + 모듈 전체 test + verify-master-plan. 신규 파일 다수 — detekt/ktlint baseline은 코드/@Suppress로 해소(신규 baseline 동결 금지).
- 마이그레이션 0. 신규 권한코드 0. cross-BC 0.

## 리뷰 결과

### 독립 plan 리뷰 (code-reviewer ground-truth, 2026-06-09)

메모리 `bts-review-plan-autoplan-overkill`(백엔드는 autoplan 대신 ground-truth) + auth 독립 보안 리뷰. 실제 코드 전수 대조.

**환각 0** — 전 task 참조 자산 실재(link() 충돌 122~146·LinkOutcome·EC9·acquireUserLock 전폭해시·새 경로 STATELESS 라우팅). 1차 보안가드 계승 확인(sid JWT출처·advisory lock 전폭해시·fail-closed exactly=0).

**BLOCKER 3 (전부 plan 반영 완료 — auth 무시 옵션 없음)**.
- B1. 핸들러 생성자 변경이 `@ConditionalOnBean` SSO 체인 활성을 깰 위험(SSO 로그인 통째 404) → Task 7에 SamlSecurityConfigTest/OidcSecurityConfigTest 부팅 회귀가드 추가.
- B2. `resolveJwtClaims`/`requireStepUp`/`errorResponse`가 AccountLinkController **private** → 새 컨트롤러 재사용 불가, 보안로직 복붙 위험 → **Task 11 공통추출(AccountLinkJwtSupport)** 신설, Task 9 depends-on=[1,3,11].
- B3. Task 3 인터페이스 확장이 fake(DbClientRegistrationRepositoryTest 등) 컴파일 깸 → Task 3 files에 깨지는 테스트 전수 추가.

**CONCERN 4 (반영)**.
- C1 핸들러 setUp 생성자 갱신 RED 명시(신규 의존 clock 기본값 앞). C2 JSESSIONID Set-Cookie 실제 헤더 assert(getSession@STATELESS 실증, 미확인→RED로 못박음). C3 B2/EC17 분해(session-fixation 단위 + SameSite 설정값 + 왕복은 E2E D7 위임). C4 cookie.secure prod 전용(base/test는 http).

**잘한 부분** — insertLink 순수INSERT(타계정 선점 삼킴 차단)·의존그래프(T6/T3) 정합·새 경로 STATELESS 라우팅 갭 0.

**BLOCKER 무시 없음**. 3건 모두 plan 수정 반영(Task 3/7/9/11). 잔여 미확인 = C2 STATELESS getSession JSESSIONID 방출(Spring STATELESS는 서블릿 세션 생성 미차단 → 동작 개연 높음, Task 9 RED 실증).
