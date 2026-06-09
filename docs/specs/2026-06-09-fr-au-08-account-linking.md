# FR-AU-08 계정 통합 (Account Linking) — 스펙 (1차)

> slug: fr-au-08-account-linking · type: auth · BC: identity-access · 담당: security-engineer
> 작성: 2026-06-09 · ADR: [docs/decisions/2026-06-09-account-linking-policy.md](../decisions/2026-06-09-account-linking-policy.md)

## 범위 (Maxi 2026-06-09)

**1차(본 FR)**. 연결 목록 조회 + 연결 해제 + **LDAP 동기 연결** + 재인증(step-up) + 충돌 규칙 전체.
**2차(후속 FR-AU-08b)**. SAML/OIDC 리다이렉트 연결(보안 핵심 SSO 성공 핸들러를 "연결 모드"로 분기 + 리다이렉트 의도 전달). 본 FR 범위 밖.

연결 대상은 **외부 Provider(LDAP/SAML/OIDC) 신원**(`user_external_accounts`)만. LOCAL 비밀번호 추가/제거는 범위 밖(`StoredPasswordCredential` 영역).

## 용어

- **연결(Link)**. 로그인한 사용자가 자기 계정에 추가 외부 신원을 명시적으로 붙이는 것.
- **재인증/step-up**. 민감 동작(연결/해제) 직전 보유 로그인 수단으로 1회 재인증 → 짧은 step-up 윈도우 발급.
- **step-up 윈도우**. 재인증 성공 후 민감 동작이 허용되는 짧은 유효 기간(기본 5분).
- **로그인 수단**. 한 사용자가 로그인에 쓸 수 있는 것의 합 = 연결된 외부 계정(N) + LOCAL 비밀번호 보유 여부(0/1).

## 사용자 시나리오 (Given-When-Then)

### S1. 내 연결 계정 목록 조회
- **Given** 로그인한 사용자가 외부 계정 1개 이상 연결돼 있음
- **When** `GET /api/v1/auth/account/links` 호출
- **Then** 본인에게 연결된 외부 계정 목록(Provider 이름/타입, 마스킹된 식별자, 연결 시각, 마지막 로그인 시각) + LOCAL 비밀번호 보유 여부 반환

### S2. LDAP 계정 연결 (정상)
- **Given** LOCAL 계정으로 로그인 + step-up 유효 + 연결하려는 LDAP DN이 어디에도 연결 안 됨
- **When** `POST /api/v1/auth/account/links`에 LDAP providerId + username + password 제출
- **Then** LDAP bind 성공 → DN 추출 → 현재 userId에 `user_external_accounts` 행 INSERT → 201

### S3. 연결 해제 (정상)
- **Given** 로그인한 사용자가 외부 계정 2개 연결 + step-up 유효
- **When** `DELETE /api/v1/auth/account/links/{id}` (본인 소유 링크)
- **Then** 해당 행 삭제 → 204. 남은 로그인 수단 ≥ 1 보장됨

### S4. 재인증 챌린지
- **Given** 로그인한 사용자
- **When** `POST /api/v1/auth/account/reauth`에 보유 수단(LOCAL 비번 또는 LDAP 자격증명) 제출
- **Then** 검증 성공 시 현재 세션(sid)에 step-up 윈도우(5분) 부여 → 200. 이후 5분 내 연결/해제 허용

### S5. 충돌 — 타계정 선점 거부
- **Given** 연결하려는 (provider, DN)이 **다른 사용자**에 이미 연결됨
- **When** `POST .../links`
- **Then** 409 `account_already_linked` (어느 계정인지 식별 불가한 일반 메시지 — 계정 열거 0)

### S6. 충돌 — 동일계정 멱등
- **Given** 연결하려는 (provider, DN)이 **현재 사용자**에 이미 연결됨
- **When** `POST .../links`
- **Then** 200 (no-op, 기존 링크 반환). 중복 INSERT 없음

### S7. 충돌 — 마지막 수단 해제 거부
- **Given** 사용자의 로그인 수단이 정확히 1개(외부 계정 1 + LOCAL 비번 없음)
- **When** `DELETE .../links/{그 1개}`
- **Then** 409 `last_login_method` (self-lockout 방지). 행 보존

### S8. step-up 없음/만료
- **Given** step-up 미보유 또는 만료
- **When** `POST .../links` 또는 `DELETE .../links/{id}`
- **Then** 403 `step_up_required` (프론트는 재인증 유도)

## 기능 요구사항 (FR)

- **FR1**. 인증된 사용자는 본인에게 연결된 외부 계정 목록을 조회한다. 타인 계정 조회 불가.
- **FR2**. 인증된 사용자는 유효 step-up 하에 LDAP 신원을 본인 계정에 연결한다. 연결은 대상 LDAP bind 성공이 전제.
- **FR3**. 인증된 사용자는 유효 step-up 하에 본인 소유 링크를 해제한다.
- **FR4**. 연결 시 (provider, externalSubject)가 다른 사용자에 매핑돼 있으면 거부한다(계정 탈취 차단).
- **FR5**. 연결 시 (provider, externalSubject)가 현재 사용자에 이미 매핑돼 있으면 멱등 no-op.
- **FR6**. 해제 후 남은 로그인 수단이 0이 되면 거부한다(self-lockout 방지).
- **FR7**. 재인증 챌린지는 보유 수단(LOCAL 비번 / LDAP bind)으로만 성공하며, 검증 대상은 **현재 userId의 자격증명**이어야 한다.
- **FR8**. 모든 엔드포인트는 인증 필수 + 본인 자원만. **PAT principal은 403**(세션 관리 선례와 정합 — `session-management-pat-exclusion`).

## 비기능 요구사항 (NFR)

- **N1 (보안)**. `external_subject`/비밀번호/PII를 로그에 직접 출력 금지(`providerId`만). 기존 AutoProvisionService 관례.
- **N2 (보안)**. 연결/해제 충돌 응답은 본인 계정 한정. 타 사용자 매핑 존재를 식별 가능한 형태로 노출 금지(계정 열거 0).
- **N3 (보안)**. step-up 검증은 fail-safe — 윈도우 만료/저장소 손실 시 재인증 강제(기본 거부).
- **N4 (보안)**. LOCAL 재인증은 `LocalCredentialService.verifyForUser`(timing-attack 방어 + 평문 wipe) 그대로 재사용. 평문 비밀번호는 `CharArray`로 받고 사용 후 wipe.
- **N5 (트랜잭션)**. 연결/해제는 단일 `@Transactional` 경계. 부분 성공 금지.
- **N6 (SQL)**. NamedParameterJdbcTemplate 바인딩(현 repository 관례). 문자열 결합 금지.
- **N7 (무결성)**. 동시 연결 race는 기존 `UNIQUE(provider_id, external_subject)` 제약이 DB 차원 안전망.
- **N8 (CSRF)**. 신규 mutating 엔드포인트(POST `/reauth`, POST `/links`, DELETE `/links/{id}`)는 기존 `CookieCsrfTokenRepository` + `SameSite=Strict` CSRF 보호를 받는다. CSRF 예외 등록 금지(절대 규칙). 프론트는 BC별 CSRF 관례를 따른다(`frontend-api-convention-per-bc`).
- **N9 (동시성/TOCTOU)**. "마지막 수단 해제 거부"(FR6)는 *check(count) → delete* 사이 TOCTOU에 취약하다 — 서로 다른 링크 2개를 동시 해제하면 둘 다 count≥2를 보고 둘 다 삭제→0이 될 수 있다. **userId 기준 `pg_advisory_xact_lock`(bigint 시그니처)로 직렬화** 후 count 재조회→delete를 같은 트랜잭션에서 수행한다(`advisory-lock-bigint-toctou` 선례).
- **N10 (brute-force)**. 연결/재인증의 LDAP bind 반복 실패 보호 — DN이 **이미 연결된** 경우(재인증) 기존 LockoutPolicy(`failed_attempts`/`locked_until`)를 적용한다. **미연결 DN의 link-bind**는 아직 행이 없어 per-account lockout이 불가 → LDAP 서버 자체 lockout에 의존(잔여 위험). 엔드포인트 단위 공격적 rate-limit는 BTS에 전역 인프라 부재 시 후속 과제로 명시.

## API 인터페이스 (REST)

베이스 `/api/v1/auth/account`. 모두 인증 필수(JWT). PAT → 403.

| 메서드 | 경로 | 설명 | 성공 | 주요 실패 |
|---|---|---|---|---|
| GET | `/links` | 내 연결 계정 목록 + `hasLocalPassword` | 200 | 401(미인증)/403(PAT) |
| POST | `/reauth` | 재인증 챌린지 → step-up 윈도우 부여 | 200 | 401(자격증명 불일치)/403(PAT) |
| POST | `/links` | LDAP 신원 연결(step-up 필요) | 201(신규)/200(멱등) | 401(bind 실패)/403(step-up 없음)/409(타계정 선점)/503(LDAP 불가) |
| DELETE | `/links/{id}` | 연결 해제(step-up 필요) | 204 | 403(step-up 없음/PAT)/404(미소유)/409(마지막 수단) |

### 요청/응답 형태

```
// GET /links → 200
{ "links": [ { "id": "uuid", "providerId": "uuid", "providerName": "사내 LDAP",
               "providerType": "LDAP", "externalSubjectMasked": "uid=al***",
               "linkedAt": "2026-...", "lastLoginAt": "2026-..."|null } ],
  "hasLocalPassword": true }

// POST /reauth (LOCAL) → { "method": "LOCAL", "password": "..." }
// POST /reauth (LDAP)  → { "method": "LDAP", "providerId": "uuid", "username": "...", "password": "..." }
//   → 200 { "stepUpExpiresAt": "2026-..." }   // 윈도우는 서버가 현재 sid에 부여

// POST /links → { "providerId": "uuid(LDAP)", "username": "...", "password": "..." }
//   → 201 { "id": "uuid", "providerName": "...", "providerType": "LDAP", "linkedAt": "..." }

// DELETE /links/{id} → 204
```

## 데이터 모델 변경

**없음.** 기존 `user_external_accounts`(V002) 재사용. 마이그레이션 0건.

step-up 윈도우는 **Caffeine 인메모리 캐시**(`sid → expiresAt`, TTL 5분)로 보관 — 기존 `SidRevokeJwtConverter`의 Caffeine 선례와 동일 패턴. 단일 호스트(Naver Cloud Docker Compose)라 인메모리로 충분, 재시작 시 grant 소실은 재인증 강제(fail-safe). *(대안: 서명된 short-lived step-up 토큰 / `sessions.step_up_at` 컬럼 — plan 리뷰에서 확정.)*

## 필요한 코드 표면 (구현 가이드)

- **신규 `AccountLinkController`**(`/api/v1/auth/account`) — JWT principal → userId(`jwt.subject`), PAT(Jwt 아님) → 403. AuthController의 `resolveJwtClaims`/`PAT_FORBIDDEN_RESPONSE` 패턴 재사용.
- **신규 `AccountLinkService`** — 충돌/마지막수단/멱등 규칙 + 단일 트랜잭션.
- **신규 `StepUpService`**(Caffeine) — `grant(sid)` / `isValid(sid)`.
- **`ExternalAccountRepository` 추가** — `findByUserId(userId)`, `deleteByIdAndUserId(id, userId): Int`(소유 검증 겸), `countByUserId(userId)`. 충돌 조회는 기존 `findByProviderIdAndExternalSubject` 재사용.
- **`StoredPasswordCredentialRepository.findByUserId`** 재사용 — LOCAL 비번 보유 여부(`hasLocalPassword`/마지막 수단 카운트).
- **`LdapProvider` surgical 리팩토링** — "bind + 속성 추출"을 provision과 분리. 연결/LDAP-재인증은 **bind-only**로 DN(externalSubject)만 얻고 JIT provision은 호출하지 않는다(현 `authenticate`는 bind 후 곧장 provision → 신규 user 생성하므로 연결에 부적합). 기존 일반 로그인 경로(`authenticate` 전체)는 무변경.
- **`LocalCredentialService.verifyForUser`** 재사용 — LOCAL 재인증.
- 기존 `AutoProvisionService`/SSO 성공 핸들러 **무변경**(연결 모드는 본 FR에서 LDAP 동기 경로만).

## 엣지 케이스

- **EC1**. step-up 만료(경계: 정확히 5분) → 403. `Clock` 주입으로 테스트(time-bomb 회귀 방지).
- **EC2**. LDAP bind 실패(잘못된 비번) → 401, 행 미생성.
- **EC3**. LDAP Provider 불가(서버 다운) → 503 `provider_unavailable`(catch-all이 500으로 변질 안 되게 직접 응답 — `catch-all-exceptionhandler-swallows-responsestatusexception` 선례).
- **EC4**. 연결 대상 providerId가 LDAP 타입이 아니거나 비활성/미존재 → 400/404(잘못된 라우팅 차단).
- **EC5**. 해제 대상 링크가 타인 소유 → 404(존재 probe 방지 — `auth-extraction-before-resource-lookup` 선례: actor 추출을 리소스 조회보다 먼저).
- **EC6**. 동시 동일 (provider, DN) 연결 → UNIQUE 제약 위반 → 409로 정규화(`account_already_linked`).
- **EC7**. **SSO 전용 사용자**(LOCAL/LDAP 자격증명 없음)는 1차에서 동기 재인증 불가 → 연결/해제 불가. **문서화된 1차 한계**(SSO 재인증은 리다이렉트 필요 → FR-AU-08b).
- **EC8**. PAT로 호출 → 403(모든 엔드포인트).
- **EC9**. step-up 재인증을 **다른 userId의 자격증명**으로 시도 → 실패(검증 대상은 현재 userId 한정).
- **EC10**. **동시 해제 TOCTOU**(N9) — 같은 사용자의 서로 다른 링크 2개를 동시에 DELETE → advisory lock 직렬화로 한쪽만 마지막 수단 검사를 통과/거부. 0으로 떨어지지 않음. 통합테스트로 검증.
- **EC11**. LDAP 연결 시 bind 추출의 **groups**도 `user_external_accounts.groups`에 저장(provision과 동일, FR-PM-01 role 매핑 소비). 빈 groups면 `[]`.

## 제약 조건

- DEVELOPMENT.md §1 절대 규칙(평문 비번 미저장/미로깅, CSRF 유지, 인증 없는 엔드포인트 금지, @Transactional+@Service).
- ADR `2026-05-20-user-external-accounts-schema`(RESTRICT/CASCADE) 호환 — 행 단위 DELETE는 provider RESTRICT와 무관(provider 삭제가 아님).
- BC 격리 — identity-access 단일 BC. cross-BC 호출 없음.
- **범위 밖(명시)**. 감사 로그 emit는 **FR-AU-10**(인증 감사 로그) 위임 — FR-AU-09 선례("audit emit은 FR-AU-10 위임")와 정합. SSO(SAML/OIDC) 연결은 **FR-AU-08b** 위임. LOCAL 비밀번호 추가/제거는 FR-AU-05/비밀번호 관리 영역.

## 측정 가능한 완료 기준

1. S1~S8 시나리오가 통합테스트(Testcontainers + 실 LDAP container)로 green.
2. 충돌 3종(타계정 거부 / 멱등 / 마지막수단 거부)과 step-up 만료(EC1) 각각 테스트 존재.
3. PAT 403(EC8), 타인 링크 404(EC5), LDAP bind 실패 401(EC2) 테스트 존재.
4. 마이그레이션 0건. 기존 일반 로그인/JIT 경로 회귀 0(기존 LDAP/SAML/OIDC 통합테스트 green 유지).
5. identity-access 모듈 `test` + `ktlintMainSourceSetCheck` + `detekt` green.
6. PII(외부 식별자/비밀번호) 로그 미출력 — 코드 리뷰 확인.

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 gap-hunt에서 5건 발견 → 전부 스펙 반영(기술 갭, Maxi 결정 불요).
- G1 (CSRF) → N8
- G2 (LDAP bind brute-force) → N10
- G3 (마지막 수단 해제 TOCTOU self-lockout 우회 — 최우선 보안 갭) → N9 + EC10 (advisory lock 직렬화)
- G4 (LDAP groups 저장 일관성) → EC11
- G5 (감사 로그 FR-AU-10 위임 명시) → 제약 조건 §범위 밖
