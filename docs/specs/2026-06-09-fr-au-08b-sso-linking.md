<!-- FR-AU-08b SSO(SAML/OIDC) 리다이렉트 계정 연결 백엔드 스펙 -->

# FR-AU-08b — SSO(SAML/OIDC) 리다이렉트 계정 연결 + SSO 재인증 — 스펙

> slug: fr-au-08b-sso-linking · BC: identity-access · agent: security-engineer · 2026-06-09
> 선행: FR-AU-08 1차(PR #103, LDAP 동기 연결) · FR-AU-03 SAML(#76) · FR-AU-04 OIDC(#80) · FR-AU-06 다중 Provider(#101)
> ADR: `account-linking-policy`(D1~D5) 계승 + 신규 `2026-06-09-sso-account-linking` 생성 예정

## 0. 배경 / 결정 요약

1차는 LDAP **동기** 연결만 지원(1요청 내 bind로 소유 증명). SSO(SAML/OIDC)는 IdP로 **리다이렉트 왕복**이라 동기 bind가 없어 후속(08b)으로 분리됐다. 본 스펙은 다음을 추가한다.

- **SSO 연결(CREATE)** — 로그인 사용자가 SSO 외부 신원을 자기 계정에 명시적 연결(리다이렉트 왕복).
- **SSO 재인증(step-up)** — LOCAL/LDAP 동기 자격이 없는 **SSO 전용 사용자**가 step-up을 얻는 경로(기존 연결 IdP로 왕복 → 본인 확인). 이로써 SSO 전용 사용자도 연결/해제 가능(1차 한계 완전 해소).
- 해제(unlink)·목록(list)은 **이미 provider 무관**으로 동작(1차) → 코드 변경 없이 SSO 전용 사용자가 step-up만 얻으면 사용 가능. 회귀 검증 대상.

**Maxi 결정(2026-06-09)**.
- D-a. **의도 보존 = HttpSession 피기백**(연결 시작 XHR가 JSESSIONID 세팅 + intent 저장 → SSO 왕복이 같은 세션 재사용). URL에 userId/intent 미노출(reflection/탈취 차단). 서명 티켓 in RelayState 안은 배제.
- D-b. **범위 = 전체 지원**(SSO 연결 + SSO 재인증 둘 다). SSO 전용 사용자 완전 지원.
- D-c. **콜백 결과 = 설정 페이지 리다이렉트 + 쿼리파라미터 상태**(일반화 메시지, 계정 열거 0).

## 1. 사용자 시나리오 (Given-When-Then)

### S1. LOCAL/LDAP 사용자가 SSO 로그인 추가 연결
- **Given** 비밀번호(LOCAL) 보유 + 최근 재인증(step-up 윈도우 유효)한 로그인 사용자.
- **When** "Google(OIDC) 연결" 클릭 → `POST /links/sso/start` → IdP 인증 성공 후 콜백.
- **Then** 그 OIDC 신원(sub)이 현재 사용자 계정에 연결(201). **새 세션/JWT 미발급**(현재 세션 유지). 설정 페이지로 `?link=success` 리다이렉트.

### S2. SSO 전용 사용자가 step-up 획득 후 두 번째 SSO 연결
- **Given** SSO(SAML) 1개로만 로그인하는 사용자(LOCAL 비번·LDAP 없음). step-up 없음.
- **When** "연결 추가" 시도 → step-up 없어 403 → "본인 확인 필요" → `POST /reauth/sso/start`(기존 SAML로 왕복) → 돌아온 신원이 본인 링크와 일치 → step-up 부여 → 이어서 `POST /links/sso/start`(OIDC) 왕복.
- **Then** step-up 부여됨. OIDC 신원이 계정에 연결(201). 두 번의 SSO 왕복.

### S3. SSO 전용 사용자가 링크 해제
- **Given** SSO 전용 사용자, SSO 링크 2개.
- **When** SSO 재인증으로 step-up 획득 → `DELETE /links/{id}`.
- **Then** 마지막 수단 아니면 해제(204). 마지막 1개면 거부(409, self-lockout 방지).

### S4. 타계정 선점 거부(역방향 탈취 차단)
- **Given** 연결하려는 SSO 신원이 **이미 다른 사용자**에 매핑됨.
- **When** 연결 콜백.
- **Then** 연결 거부(attach 안 함). 설정 페이지로 `?link=conflict`(일반 메시지, 어느 계정인지 노출 0).

### S5. 일반 SSO 로그인(회귀 가드)
- **Given** intent 없는 평범한 SSO 로그인.
- **When** SAML/OIDC 콜백.
- **Then** 기존 JIT 프로비저닝 + 로그인(JWT/세션 발급) **무변경**.

## 2. 기능 요구사항 (FR)

- **FR1. SSO 연결 시작 엔드포인트** — `POST /api/v1/auth/account/links/sso/start`. JWT 인증 + step-up 유효 필수(없으면 403 `step_up_required`). 바디 `{ registrationId, providerType(SAML|OIDC) }`. provider가 존재·enabled SSO인지 검증(아니면 404/400). HttpSession에 `LinkingIntent{mode=LINK, userId, registrationId, providerType, expiresAt}` 저장 + JSESSIONID Set-Cookie. 응답 `{ authorizeUrl }`(SAML `/saml2/authenticate/{reg}`, OIDC `/oauth2/authorization/{reg}`).
- **FR2. SSO 재인증 시작 엔드포인트** — `POST /api/v1/auth/account/reauth/sso/start`. JWT 인증 필수(step-up 불요 — 이게 step-up 획득 경로). 바디 `{ registrationId }`(현재 사용자가 이미 연결한 SSO여야 의미 있음, 미연결도 시작은 허용하되 콜백서 거부). HttpSession에 `ReauthIntent{mode=REAUTH, userId, sid, registrationId, expiresAt}` 저장. 응답 `{ authorizeUrl }`.
- **FR3. 연결 모드 성공 핸들러 분기 (fail-closed)** — SAML/OIDC 성공 핸들러가 콜백 시 HttpSession의 intent를 **issueTokens 진입 이전에 먼저 소비**(1회용). intent 있으면 일반 로그인 경로(`SessionService.create`/`JwtIssuer.issue`/`RefreshToken`/`AutoProvisionService.provision`)를 **물리적으로 진입 불가**(early return) — 분기 누락 시 fail-closed(발급 금지). intent 없으면 기존 JIT 로그인(무변경).
  - 콜백 providerId 해소는 **활성(enabled) provider로만**(C-B1 — OIDC도 SAML처럼 enabled 필터, start↔콜백 TOCTOU 차단). 비활성/미해소 → `?link=error`/`?reauth=failed`.
  - LINK 모드: intent.registrationId == 인증 registrationId 검증(EC4) → `AccountLinkService.linkExternalSubject(userId, providerId, externalSubject, groups)`(충돌 규칙) → `?link=success|already_linked|conflict|error`. **새 세션/JWT 미발급 + `provision` 미호출**(attach만, 신규 user 생성 금지 — confused-deputy 차단).
  - REAUTH 모드: **intent.registrationId == 인증 registrationId 검증(C-C3, LINK 동형)** → 돌아온 `(providerId, externalSubject)`가 intent.userId에 **이미 연결**됐는지 확인(EC9 동형) → 맞으면 `StepUpService.grant(intent.sid)`(grant 직전 `SessionService.lookup(sid)` 활성 재확인, C-C1) → `?reauth=success`. 불일치(타 신원/타 provider) → grant 안 함, `?reauth=failed`.
- **FR4. SSO 연결 충돌 규칙(1차 D5 계승) + 동시성 직렬화** — `linkExternalSubject`는 attach 전 `(providerId, externalSubject)` advisory lock으로 check-then-attach 구간 직렬화(C-C2, unlink TOCTOU 패턴 동형). lock 후 `findByProviderIdAndExternalSubject` 분기. 타 user→거부(`?link=conflict`)·현재 user→멱등(`?link=already_linked`)·없음→attach(201, `?link=success`). attach는 **충돌검사 통과 후 INSERT**(ON CONFLICT user_id 보존이 타계정 선점을 조용히 삼켜 거짓 success 나는 것 차단 — PR #8 learning + race window 봉쇄).
- **FR5. SSO 전용 사용자 해제/목록(회귀)** — `DELETE /links/{id}`·`GET /links`는 코드 변경 없음. step-up을 SSO 재인증으로 얻으면 SSO 전용 사용자도 사용 가능함을 통합테스트로 실증. 마지막 수단 카운트는 **enabled SSO 링크 + LOCAL**(비활성 SSO 링크 제외, C-C4).
- **FR6. 마이그레이션 0** — `user_external_accounts`(V002) 재사용. 신규 테이블/컬럼 없음.
- **FR7. start 입력 검증 매트릭스** — (a) `providerType=SAML`은 SAML config repo로만, `OIDC`는 OIDC reader로만 조회 + 일치 확인(불일치 404). (b) `LOCAL/LDAP` type → 400(SSO 아님). (c) 미존재/비활성 registration → 404. (d) registrationId 형식 화이트리스트(영숫자+하이픈)로 authorizeUrl 주입 전 검증. (e) authorizeUrl은 **검증 통과한 config 값으로만** 서버 구성(사용자 입력 echo 금지, EC14).
- **FR8. REAUTH intent sid/userId 동일 출처** — `reauth/sso/start`는 검증된 단일 JWT에서 `subject`(userId)와 `sid`를 **동시 추출**해 intent에 복사. 콜백 grant는 그 sid에만(위조 차단 — sid는 JWT 클레임 출처, 1차 불변식 계승).

## 3. API 인터페이스 (REST)

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/v1/auth/account/links/sso/start` | JWT + step-up | SSO 연결 시작 → `{authorizeUrl}` |
| POST | `/api/v1/auth/account/reauth/sso/start` | JWT | SSO 재인증 시작 → `{authorizeUrl}` |
| (기존) GET | `/api/v1/auth/account/links` | JWT | 목록(SSO 링크 포함, 마스킹) — 무변경 |
| (기존) DELETE | `/api/v1/auth/account/links/{id}` | JWT + step-up | 해제 — 무변경 |
| (프레임워크) POST | `/login/saml2/sso/{reg}` | — | SAML ACS 콜백 → 성공 핸들러 모드 분기 |
| (프레임워크) GET | `/login/oauth2/code/{reg}` | — | OIDC 콜백 → 성공 핸들러 모드 분기 |

- start 엔드포인트는 POST(CSRF 토큰 `X-XSRF-TOKEN` 필요, 기존 Cookie CSRF 모드). PAT → 403(JWT 전용, 1차 일관).
- SPA 흐름: XHR POST start(JSESSIONID 수신) → JS `window.location.assign(authorizeUrl)`(전체 페이지 GET 네비게이트, JSESSIONID 동반).

## 4. 데이터 모델 변경

없음. `user_external_accounts`(V002) 재사용. `LinkingIntent`/`ReauthIntent`는 **HttpSession 인메모리 속성**(영속 안 함, 단명 ≤5분 — step-up 윈도우와 정합, EC19). step-up 윈도우는 기존 `StepUpService` 인메모리.

## 5. 엣지 케이스

- **EC1** 일반 SSO 로그인(intent 없음) → 기존 JIT 로그인 무변경(회귀 가드, 필수 통합테스트).
- **EC2** LINK: 돌아온 신원이 이미 현재 user에 연결 → 멱등(`?link=already_linked`).
- **EC3** LINK: 타 user에 이미 매핑 → 거부, attach 안 함(`?link=conflict`, 계정 열거 0 일반 메시지).
- **EC4** LINK: registrationId 불일치(intent X, 인증 Y) → 거부(`?link=error`). 방어.
- **EC5** intent 만료(콜백 도착 시점) → 거부(`?link=error`/`?reauth=failed`). intent 단명 만료(예 10분).
- **EC6** step-up 없이 `links/sso/start` → 403 `step_up_required`.
- **EC7** disabled/미존재 provider로 start → 404/400.
- **EC8** REAUTH: 돌아온 신원이 현재 user 링크와 불일치(타 신원 인증) → step-up 부여 안 함(`?reauth=failed`). 타 신원으로 우회 grant 차단.
- **EC9** REAUTH: 돌아온 신원이 현재 user 링크와 일치 → `grant(sid)`(`?reauth=success`).
- **EC10** **세션 고정(session fixation) 보호로 intent 소실 금지** — Spring 인증 성공 시 `changeSessionId` 전략을 **SSO 체인에 명시 설정** + intent 속성 이관을 통합테스트로 실증(`newSession`이면 intent 유실).
- **EC11** IdP 인증 **실패**(linking/reauth 중) → intent는 단명 만료로 자동 무효(콜백 안 옴). 별도 실패 핸들러로 설정 페이지 복귀는 nice-to-have(intent 만료로 안전 폴백, 본 FR 필수 아님 — 명시).
- **EC12** 연결 모드 성공 핸들러는 **새 JWT/세션 미발급 + `provision` 미호출 + 현재 세션 무변경**(권한 변경/계정 전환/confused-deputy 차단, fail-closed, 필수 검증).
- **EC13** 동시 intent(link 시작 후 reauth 시작) → 세션 단일 슬롯 last-wins + 단명 만료. 허용.
- **EC14** open-redirect — linking/reauth 복귀 대상은 **서버 고정 설정 경로**(사용자 입력 아님) → open-redirect 없음.
- **EC15** PII — `external_subject`(sub/NameID) 로그 직접 출력 금지(현 관례).
- **EC16 (B1)** start 시 활성이던 provider가 IdP 왕복 중 비활성화 → **콜백 시점 enabled 재해소**로 거부(OIDC `findByRegistrationId`가 SAML과 달리 enabled 미필터인 코드 비대칭 보정). 비활성 링크가 attach돼 "마지막 수단 0" 영구 락 되는 것 차단.
- **EC17 (B2)** **JSESSIONID 쿠키 왕복 검증** — start 엔드포인트(STATELESS 체인 `getSession(true)`)가 만든 세션이 SAML ACS **cross-site POST** 콜백까지 동반되는지 필수 검증. CSRF 쿠키는 `SameSite=Strict`(SAML ACS에 미전송이 정상 — CSRF skip 경로). JSESSIONID의 SameSite/Path가 SAML(POST)·OIDC(GET) 왕복을 깨지 않는지 분리 검증(Strict면 SAML 깨짐 → `SameSite=None; Secure` 또는 SSO 한정 완화). 기존 SAML 로그인 동작이 JSESSIONID 왕복 생존을 시사하나, start-체인 세션 동반은 별개라 실증.
- **EC18 (C2)** 동시/중복 콜백(다중 탭·재시도) → `(providerId, externalSubject)` advisory lock으로 직렬화 + 충돌검사 후 INSERT → 두 콜백의 intent.userId가 달라도 거짓 success 없이 한쪽만 성공·다른쪽 conflict.
- **EC19 (N1)** step-up 검사는 **start XHR 시점 1회**. 콜백 attach는 JWT 부재라 intent 유효성(만료·registrationId 일치·충돌·enabled)만 검사. intent 만료는 step-up 윈도우(5분)와 정합되게 ≤5분 권장(reauth→link 연쇄 시 step-up 선만료 회피).

## 6. 비기능 요구사항 (NFR)

- 보안 — 평문 비밀 저장 0, CSRF 유지(start POST), 명시적 수동 연결만(이메일 자동연결 0), 타계정 선점 거부, self-lockout 거부(advisory lock TOCTOU 직렬화 — 기존), step-up 신선도 강제, 계정 열거 0, PII 미로깅.
- 회귀 — 일반 SAML/OIDC/LDAP/LOCAL 로그인 무변경(기존 테스트 green). `bindForLinking`·`authenticate` 무변경.
- 격리 — identity-access 단일 BC. cross-BC 호출 0. identity-access는 jdbc-only → init_codegen 미러 불요.
- 품질 — ktlint + detekt(`--rerun-tasks`) + 모듈 전체 test green. TDD red→green.

## 7. 측정 가능한 완료 기준

1. `POST /links/sso/start`(SAML·OIDC) → authorizeUrl 반환 + JSESSIONID 세팅 + intent 저장. step-up 없으면 403.
2. LINK 콜백: 신규 신원 attach(201 의미) + 새 세션/JWT 미발급 + 설정 페이지 `?link=success`. 통합테스트 실증.
3. 충돌: 타 user 매핑 → `?link=conflict`(attach 0). 현재 user 매핑 → `?link=already_linked`(멱등).
4. `POST /reauth/sso/start` → 콜백서 본인 링크 일치 시 step-up 부여, 불일치 시 미부여(EC8/EC9).
5. SSO 전용 사용자: SSO 재인증으로 step-up → 새 SSO 연결 + 기존 링크 해제 end-to-end 통합테스트 green.
6. 일반 SSO 로그인 회귀 0(EC1) — intent 없을 때 JIT 로그인 무변경.
7. 마이그레이션 0. ktlint/detekt/test green(--rerun-tasks). 마스터플랜 §2.8 D4·D6/D7 표기 동기화(SSO 후속 완료 반영) + verify-master-plan PASS.

## Brainstorming Check

✅ 통과 (1 iteration — 적대적 보안 갭 분석 서브에이전트). 코드 근거 발견 반영.
- **B1**(OIDC `findByRegistrationId` enabled 미필터 코드 비대칭) → FR3 콜백 enabled 재해소 + EC16.
- **B2**(start 세션↔SAML ACS cross-site POST 쿠키 왕복 + session-fixation) → EC17 + EC10 명시 검증 항목.
- **C2**(동시 콜백 race 거짓 success) → FR4 advisory lock 직렬화 + EC18.
- **C3**(REAUTH registrationId 일치 검증 누락) → FR3 REAUTH에 LINK 동형 추가.
- **C5**(fail-closed 발급 금지 + LINK provision 미호출) → FR3/EC12 강화.
- **C1/C6/N1** → FR8(sid·userId 동일 JWT 출처+세션 활성 재확인)·FR7(start 입력 검증 매트릭스)·EC19(step-up 1회 검사) 반영.
- 커버 인정: 타계정 선점 거부·회귀 가드·계정 열거 0·open-redirect·마이그레이션 0.
