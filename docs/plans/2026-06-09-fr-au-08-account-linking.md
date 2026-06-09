# FR-AU-08 계정 통합 (Account Linking)

> slug: fr-au-08-account-linking
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

사용자 원문. "FR-AU-08 계정 통합 (Account Linking) 진행해줘"

classify 결과 (보정 적용).
- type: auth (classify 원판정 backend → 보정)
- agent: security-engineer (classify 원판정 backend-engineer → 보정)
- primary_bc: identity-access
- 보정 근거: identity-access BC는 CLAUDE.md상 security-engineer 담당. FR-AU-08은 인증 수단 연결을 다루는 auth 작업.

## 도메인 정리

- **BC**. identity-access (담당 security-engineer)
- **영향 엔티티**. `User`(기존), `user_external_accounts`/`ExternalAccount`(기존, V002), `StoredPasswordCredential`(기존 — 마지막 수단 카운트 시 존재 확인만). **신규 엔티티/스키마 없음**.
- **신규 코드 표면**. self-service 계정 연결 API(목록/연결/해제) + 연결 모드 분기(JIT 신규 user 생성과 분리). 기존 `AutoProvisionService`·SSO 성공 핸들러 일반 로그인 경로 무변경.
- **새 용어(glossary 갱신 대기, 머지 시 동기화)**. "계정 연결(Account Linking)", "재인증/step-up(민감 동작 전 자격증명 재확인)", "연결 해제(Unlink)".
- **현재 상태**. 명시적 연결 0건. 외부 로그인은 JIT 자동 프로비저닝(`ON CONFLICT username`)만 존재.
- **SDD 19.4 deviation**. SDD가 스케치한 `UserIdentity`(Long id + email/verifiedAt)는 stale. 실재는 `user_external_accounts`(UUID, email/verified_at 컬럼 없음). 구현은 실재 스키마 따름.

### 확정 결정 (Maxi 2026-06-09)

| # | 결정 | 값 |
|---|---|---|
| D1 | 연결 방식 | **명시적 수동 연결 전용** (이메일 자동 연결 미도입 — 계정 탈취 차단) |
| D2 | 기능 범위 | **연결 + 해제 + 목록 전체** |
| D3 | 연결 대상 | **외부 Provider(LDAP/SAML/OIDC) 한정**. LOCAL 비밀번호 추가/제거는 범위 밖 |
| D4 | 재인증 | **강제**(step-up). 구체 메커니즘은 spec 결정 |
| D5 | 충돌 처리 | 타계정 선점 **거부** / 동일계정 멱등 **no-op** / 마지막 수단 해제 **거부** |

- **기존 결정 충돌**. 없음. `2026-05-20-user-external-accounts-schema`(RESTRICT/CASCADE) 호환, 스키마 변경 없음.
- **관련 ADR**. [docs/decisions/2026-06-09-account-linking-policy.md](../decisions/2026-06-09-account-linking-policy.md) (생성됨)
- **spec 단계 미결**. 재인증 메커니즘(비밀번호 재입력 vs SSO 재수행 vs 세션 freshness 임계), 연결 모드 진입 방식(SSO/LDAP 성공 핸들러에 linking-intent 전달 경로), PAT 취급.

## 스펙

전체 스펙. [docs/specs/2026-06-09-fr-au-08-account-linking.md](../specs/2026-06-09-fr-au-08-account-linking.md)

핵심 요약.
- **1차 범위(Maxi 확정)**. 연결 목록 + 해제 + LDAP 동기 연결 + 재인증/충돌 규칙. SSO 리다이렉트 연결은 FR-AU-08b.
- **API**. `GET /api/v1/auth/account/links` · `POST /reauth`(step-up 윈도우) · `POST /links`(LDAP, step-up 필요) · `DELETE /links/{id}`(step-up 필요). 인증 필수, PAT→403.
- **재인증**. 보유 수단(LOCAL 비번/LDAP bind) 1회 → Caffeine `sid→expiry` 5분 윈도우.
- **충돌**. 타계정 선점 거부(409, 계정 열거 0) / 동일계정 멱등 / 마지막 수단 해제 거부(409, advisory lock TOCTOU 가드).
- **마이그레이션 0건**. `user_external_accounts`(V002) 재사용. LdapProvider bind-only 분리 surgical 리팩토링 + 기존 JIT/일반 로그인 무변경.

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 gap-hunt 5건 발견 → 전부 스펙 반영(기술 갭).
- 최우선 보안 갭. **마지막 수단 해제 TOCTOU self-lockout 우회** → userId advisory lock 직렬화(N9/EC10).
- 그 외. CSRF(N8) / LDAP bind brute-force(N10) / groups 저장(EC11) / 감사로그 FR-AU-10 위임.

## Plan

> 전 task `agent: security-engineer`, 모듈 `backend/modules/identity-access`. 마이그레이션 0건.
> 경로 약어 `IA = backend/modules/identity-access/src`. 패키지 `com.atlas.bts.identity`.
> TDD red→green→refactor 강제. LDAP bind 필요한 task는 `LdapTestcontainersBase` singleton(`.apply { start() }`) 사용(`Testcontainers 라이프사이클` learning).

### Task 1. StepUpService — Caffeine step-up 윈도우

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/account/StepUpService.kt`, `IA/test/kotlin/com/atlas/bts/identity/account/StepUpServiceTest.kt`]
- depends-on: []

**RED**. `StepUpServiceTest` — `grant(sid)` 후 `isValid(sid)`=true, TTL 경과(주입 `Clock`/`ticker`) 후 false, 미부여 sid는 false. 경계(정확히 5분)는 fail-safe(만료=false).
**GREEN**. Caffeine cache `sid(UUID)→expiresAt`, `expireAfterWrite=5분`(또는 `Clock` 기반 expiresAt 비교). `SidRevokeJwtConverter` Caffeine 선례 패턴.
**REFACTOR**. TTL 상수화 + KDoc(fail-safe 명시).
**검증**. `./gradlew :modules:identity-access:test --tests "*StepUpServiceTest"`

### Task 2. ExternalAccountRepository — 조회/삭제/카운트/advisory lock 추가

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccountRepository.kt`, `IA/test/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccountRepositoryTest.kt`]
- depends-on: []

**RED**. Testcontainers repo 테스트 — `findByUserId` 다건 반환, `deleteByIdAndUserId`가 소유자만 삭제(타인 id+userId → 0행), `countByUserId` 정확, `acquireUserLock(userId)`가 `pg_advisory_xact_lock(bigint)` 호출(같은 tx 직렬화). 기존 테스트(provisionUser 등) green 유지.
**GREEN**. `findByUserId`/`deleteByIdAndUserId(id,userId):Int`/`countByUserId(userId):Int` + `acquireUserLock(userId)`. **lock key 파생(리뷰 B2)** — UUID(128bit)를 절단하지 말고 `pg_advisory_xact_lock(hashtextextended(:userId::text, 0))`로 **전폭 해시→bigint** 일관 변환. 해시 충돌은 무관 사용자 거짓 직렬화일 뿐 안전(거짓 양성 락), 절단은 충돌 과다라 금지. NamedParameterJdbcTemplate.
**REFACTOR**. SQL 상수화 + KDoc(`advisory-lock-bigint-toctou` 선례 인용 + "절단 금지, 전폭 해시" 명시).
**검증**. `./gradlew :modules:identity-access:test --tests "*ExternalAccountRepositoryTest"`

### Task 3. LdapProvider — bind-only 추출 (provision 분리)

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapProvider.kt`, `IA/test/kotlin/com/atlas/bts/identity/provider/ldap/LdapProviderBindForLinkingTest.kt`]
- depends-on: []

**RED**. LDAP Testcontainers 테스트 — `bindForLinking(providerId, username, password)`가 bind 성공 시 `LdapProvisionAttrs`(externalSubject=DN + groups) 반환 + **provision 미호출**(user_external_accounts 신규 행 0). bind 실패 시 null. **기존 `authenticate`(provision 포함) 경로 무변경**(기존 LdapProvider/LdapAuthFlow 통합테스트 green 유지 — 회귀 가드).
**GREEN**. 기존 `authenticate` 내부의 bind+속성추출을 `private bindAndExtract(...)`로 추출 → `authenticate`=`bindAndExtract`+`provision`. 신규 public `bindForLinking`=`bindAndExtract`만(providerId로 enabled LDAP config 해소). PII(DN) 미로깅.
**REFACTOR**. KDoc — "연결(linking)은 bind만, provision 안 함" 명시 + 책임 경계.
**검증**. `./gradlew :modules:identity-access:test --tests "*LdapProvider*"`

### Task 4. AccountLinkService — 목록/연결/해제 + 충돌/멱등/마지막수단

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/account/AccountLinkService.kt`, `IA/test/kotlin/com/atlas/bts/identity/account/AccountLinkServiceTest.kt`, `IA/main/kotlin/com/atlas/bts/identity/provider/AuthnProviderConfigRepository.kt`, `IA/test/kotlin/com/atlas/bts/identity/provider/AuthnProviderConfigRepositoryTest.kt`]
- depends-on: [2, 3]
- **scope deviation(impl 중 발견)**. listLinks의 provider 이름/타입 표시 + 마지막 수단 enabled 카운트(C4)에 `providerId→(name,type,enabled)` 조회가 필요한데 `AuthnProviderConfigRepository`에 부재(`isEnabled(type)`/`listEnabledByTypes`만). read 메서드 `findByIds(ids): Map<UUID, AuthnProviderInfo>`(id,name,type,enabled) 추가 + 기존 테스트에 케이스. 정당한 소폭 read 추가.

**RED**. 단위 테스트(mock repo/ldap/local-cred) — `listLinks(userId)` 본인것만 + provider 상태 동반(EC13) + `hasLocalPassword`(StoredPasswordCredentialRepository.findByUserId). `link(userId, providerId, username, password)`: bind **먼저**→그 후 멱등/충돌 판정(EC12), 미연결 DN INSERT(201), **타 user 매핑→ConflictException(409)**, **현재 user 이미 매핑→멱등 no-op(기존 반환)**, bind 실패→AuthException(401). `unlink(userId, id)`: `acquireUserLock`→**남은 수단 카운트 = enabled provider 링크 + LOCAL**(리뷰 C4, 비활성 provider 링크 제외)로 **0이면 LastMethodException(409)**, 타인 링크→NotFound(404), 정상→delete. groups 저장(EC11).
**GREEN**. `@Service @Transactional`. 충돌/멱등/마지막수단 + advisory lock 직렬화 후 카운트 재조회→delete(TOCTOU 가드 N9). 마지막 수단 카운트는 **enabled provider 링크만** 집계(EC13 영구 락 방지). 도메인 예외 3종(이름 충돌 회피 — `duplicate-exception-name-cross-package-status` 선례).
**REFACTOR**. 예외→메시지 일반화(계정 열거 0, N2) + KDoc.
**검증**. `./gradlew :modules:identity-access:test --tests "*AccountLinkServiceTest"`

### Task 5. ReauthService — 재인증 챌린지 → step-up 부여

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/account/ReauthService.kt`, `IA/test/kotlin/com/atlas/bts/identity/account/ReauthServiceTest.kt`]
- depends-on: [1, 3]

**RED**. 단위 테스트(mock local-cred/ldap/stepup) — LOCAL: `verifyForUser(userId, plain)` true→`StepUpService.grant(sid)` 호출, false→실패(grant 미호출, 예외). LDAP: `bindForLinking` 성공 + **결과 DN이 현재 userId에 이미 연결됨** 확인 시에만 성공(타 신원으로 재인증 불가, EC9), 미연결/실패→실패. 평문은 `CharArray`로 받고 wipe(N4). 응답은 만료시각만(sid 미노출, FR9).
**GREEN**. `ReauthService.reauthenticate(userId, sid, method, creds)` — sid는 호출자(컨트롤러)가 JWT 클레임에서 추출해 전달(FR9). method 분기(LOCAL/LDAP), 검증 성공 시 `stepUpService.grant(sid)`. **LOCAL lockout 인프라 부재 — 새로 발명 금지(N10 정정), 기존 LOCAL 로그인과 동일 보호**. PII/비번 미로깅.
**REFACTOR**. method enum + KDoc(SSO 재인증은 FR-AU-08b 위임 명시).
**검증**. `./gradlew :modules:identity-access:test --tests "*ReauthServiceTest"`

### Task 6. AccountLinkController + DTO — 4 엔드포인트

**메타**.
- agent: `security-engineer`
- files: [`IA/main/kotlin/com/atlas/bts/identity/web/AccountLinkController.kt`, `IA/main/kotlin/com/atlas/bts/identity/web/dto/AccountLinkDtos.kt`, `IA/test/kotlin/com/atlas/bts/identity/web/AccountLinkControllerTest.kt`]
- depends-on: [1, 4, 5]

**RED**. 컨트롤러 테스트(MockMvc, 서비스 mock) — `GET /links`(200, PAT→403), `POST /reauth`(200/401, 응답에 sid/토큰 미노출·만료시각만 — FR9), `POST /links`(step-up 유효→201, step-up 없음→403 `step_up_required`), `DELETE /links/{id}`(step-up 유효→204, 없음→403). **sid는 JWT `sid` 클레임에서만 추출 — 바디에 위조 sid 주입 시 무시(리뷰 B1 테스트 케이스)**. JWT principal→userId(`jwt.subject`), PAT(Jwt 아님)→403(`PAT_FORBIDDEN_RESPONSE` 패턴). 503 직접 응답(catch-all 변질 방지, EC3).
**GREEN**. `@RestController("/api/v1/auth/account")`. step-up 게이팅(`StepUpService.isValid(sid)`, sid=JWT 클레임)을 mutating 경로에 적용. reauth 응답은 만료시각만(sid/토큰 미노출). DTO(LinkAccountRequest/ReauthRequest/AccountLinkResponse/AccountLinksResponse). externalSubject 마스킹.
**REFACTOR**. 에러코드 상수화 + KDoc + ktlint/detekt(라인길이는 멀티라인 인자, `ktlint-detekt-linelength` 선례).
**검증**. `./gradlew :modules:identity-access:test --tests "*AccountLinkControllerTest"`

### Task 7. 통합테스트 — HTTP end-to-end (S1~S8 + EC)

**메타**.
- agent: `security-engineer`
- files: [`IA/test/kotlin/com/atlas/bts/identity/account/AccountLinkIntegrationTest.kt`]
- depends-on: [6]

**RED→GREEN**. Testcontainers(postgres + LDAP) + 실 부팅(identity-access prod+RANDOM_PORT 레시피). 시나리오 — S2 LDAP 연결 성공, S3 해제, S5 타계정 선점 409, S6 멱등, S7 마지막수단 409, S8 step-up 없음 403, EC2 bind 실패 401, EC5 타인 링크 404, EC8 PAT 403, **EC10 동시 해제 TOCTOU**(병렬 2 DELETE → 한쪽만 성공, 0 안 됨). fixture userId 정합(`e2e-fixture-whoami-userid-alignment` 선례). 기존 LDAP/SAML/OIDC 통합테스트 green 유지(회귀 0).
**REFACTOR**. 헬퍼 추출 + KDoc.
**검증**. `./gradlew :modules:identity-access:test` (모듈 전체 green) + `ktlintMainSourceSetCheck` + `detekt`

## Plan 메타

- task 수: 7
- 예상 wave: 4 (W1: T1·T2·T3 / W2: T4·T5 / W3: T6 / W4: T7). 단일 모듈 test 컴파일 공유라 impl이 추가 직렬화 가능(`bts-plan-wave-gradle-module-compile`).
- TDD 강제: yes (red→green→refactor)
- 마이그레이션: 0건 (user_external_accounts V002 재사용)
- 프론트/E2E: 범위 밖 — D6(UI)·D7(Playwright)은 후속 PR(API 안정화 후)
- 보안 중점: TOCTOU advisory lock(T2·T4·T7), 계정 열거 0(T4), PAT 403(T6), PII 미로깅(T3·T5·T6), LdapProvider 회귀 가드(T3)

## 리뷰 결과

### security-engineer 독립 plan 리뷰 (2026-06-09)

타입=auth → eng+security 집중 독립 리뷰(autoplan overkill 회피). 독립 security-engineer sub-agent가 ADR/spec/plan을 적대적 검토. **BLOCKER 2 + CONCERN 6 → 전부 반영 완료** (auth라 BLOCKER 무시 옵션 없음).

| ID | 등급 | 항목 | 반영 |
|---|---|---|---|
| B1 | BLOCKER | step-up `sid` 신뢰 출처 미고정 (요청 페이로드 sid 위조 → confused-deputy/replay) | spec FR9 + plan T5/T6 (sid=JWT 클레임만, reauth 응답 sid 미노출, 위조 sid 무시 테스트) |
| B2 | BLOCKER | advisory lock UUID→bigint "상위 64bit 절단" (충돌 과다 + learning 위반) | plan T2 (`hashtextextended` 전폭 해시, 절단 금지) + ADR D5 |
| C1 | CONCERN | link-bind vs reauth-bind 검증 대상 혼동 위험 | spec FR7 (둘의 검증 대상 명시 구분) |
| C2 | CONCERN | 멱등 no-op의 bind 순서 미정 | spec EC12 (bind 먼저→멱등 판정) |
| C3 | CONCERN | link 충돌 409가 DN 점유 누설(계정 열거 비대칭) | spec N2 (잔여 위험 기록 + 메시지 일반화) |
| C4 | CONCERN | 비활성 provider 링크가 마지막 수단 카운트 오염 → 영구 락 | spec FR6/EC13 + plan T4 + ADR D5 (카운트=enabled provider 링크+LOCAL) |
| C5 | CONCERN | 윈도우형 step-up(범위 무제한) 의도 명시 필요 | spec 데이터모델(윈도우형 의도 명시) + ADR D4 |
| C6 | CONCERN | LOCAL reauth 비번 추측 lockout 사각 | spec N10 + plan T5 (per-user LockoutPolicy 카운트) |

**OK 확인(리뷰)** — 타계정 선점 거부+UNIQUE 이중 안전망, PAT 403 일관, LdapProvider bind-only 회귀 가드, catch-all 503 직접 응답, TOCTOU lock 후 재조회, @Transactional/Clock/CharArray wipe, step-up fail-safe 재시작 거부.

**eng 관점(controller)** — wave 구조(4) 의존성 정합, 단일 모듈 test 컴파일 직렬화 인지(`bts-plan-wave-gradle-module-compile`), ktlint 라인길이 멀티라인 인자 선례 명시. BLOCKER 없음.

종합. BLOCKER 0 (2건 해소) / CONCERN 0 (6건 반영). 게이트 1 진입 가능.
