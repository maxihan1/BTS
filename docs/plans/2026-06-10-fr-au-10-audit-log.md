# FR-AU-10 인증 감사 로그 (백엔드 1차)

> slug: fr-au-10-audit-log
> type: auth
> agent: security-engineer
> 생성: 2026-06-10

## Brief

FR-AU-10 — 인증 감사 로그 (identity-access BC, 우선순위 필수, SDD §2.10).
선행 FR(FR-AU-05/08/09)이 "감사 로그 emit은 FR-AU-10 위임"으로 미뤄둔 인증 시스템의 누락 조각.

**이번 PR 범위 — 백엔드 1차 (D1~D5)**. Maxi 결정 (2026-06-10).
- D1. 도메인 — AuthEvent (로그인 성공/실패 · 세션종료 · 권한변경)
- D2. 명세 — 보존 1년 (SDD §2.3.3)
- D3. 데이터 모델 — `auth_audit_logs(event_type, ip, user_agent, ...)`
- D4. 백엔드 — 모든 인증/권한 변경 이벤트 emit
- D5. 백엔드 테스트 — 이벤트 누락 0

**후속 PR**. D6 관리자 감사 로그 조회 UI (designer → frontend-engineer) + D7 E2E (qa-engineer).
최근 인증 FR 분리 패턴(FR-AU-05/08, FR-IS-10)과 동일.

classify-task가 제목 끝 "조회 UI" 키워드로 `ui/frontend-engineer` 오분류 → product 문서 D1~D5 = security-engineer 책임이므로 `auth/security-engineer`로 정정.

## 도메인 정리

- **BC**: identity-access
- **핵심 발견**: FR-AU-10은 그린필드가 아님. 감사 시스템 골격이 이미 존재 (`audit/` 패키지).
  - `AuthEventType` (이벤트 12종 enum), `AuthAuditLog` (데이터 클래스, `userId: UUID`), `AuthAuditLogService` (record/findRecent 인터페이스), `InMemoryAuthAuditLogService` (`@Service` 인메모리 임시 구현)
  - 본 작업 = `InMemory`가 KDoc에 명시한 "후속 PR: `JdbcAuthAuditLogService` + DB 테이블"의 그 후속.
- **영향 엔티티**: AuthAuditLog (기존), 신규 `auth_audit_logs` 테이블, 신규 DB-backed 서비스 구현체.
- **새 용어**: 없음 (도메인 모델 이미 정립, glossary 추가 불필요).
- **emit 갭 (D4 핵심 작업량)**: enum 12종 중 현재 4종만 emit (PAT_USED, PROJECT_MEMBER_ADDED/REMOVED, PROJECT_ROLE_CHANGED). 나머지 8종 갭 (LOGIN_SUCCESS/FAILURE, LOGOUT, LOGOUT_ALL_DEVICES, TOKEN_REFRESHED, SUSPICIOUS_REFRESH_REPLAY, USER_PROVISIONED, LDAP_UNAVAILABLE).
- **SDD 모순 해소**: §19.9("월 단위 파티션") ↔ §5.14("1M 규모 파티셔닝 불필요"). → 단순 테이블 채택 (1K 규모).
- **Maxi 결정 (2026-06-10)**:
  1. D3 — **파티셔닝 없는 단순 테이블 + 인덱스 3종** ((user_id, created_at DESC), (event_type), (created_at)). SDD §19.9 일탈, §5.14 정합.
  2. D4 — **enum 12종 전수 emit 배선** (이벤트 누락 0). FR-AU-05/08이 위임한 비번변경/계정연결 이벤트는 enum 미정의 → 별도 후속.
  3. D2 — **보존 1년 enforcement = `@Scheduled` 1년 경과 행 삭제** (파티션 DROP 대신).
  4. **동기 record() 패턴 유지** (투기적 async 미도입).
- **기존 결정 충돌**: SDD §19.9 파티셔닝 일탈 (ADR로 근거 기록). 그 외 충돌 없음.
- **관련 ADR**: [docs/decisions/2026-06-10-auth-audit-log-persistence.md](../decisions/2026-06-10-auth-audit-log-persistence.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-10-fr-au-10-audit-log.md](../specs/2026-06-10-fr-au-10-audit-log.md)

핵심 3줄 요약.
- 기존 `audit/` 골격(enum 12종 + AuthAuditLog + service)을 `JdbcAuthAuditLogService`(raw JDBC, V021 `auth_audit_logs` 테이블)로 영속화.
- enum 12종 전부 emit 배선 — 현재 4종(PAT/PROJECT_*)만 emit, 8종 갭(LOGIN_*, LOGOUT_*, TOKEN_REFRESHED, REPLAY, PROVISIONED, LDAP_UNAVAILABLE). SSO 성공은 OIDC/SAML 핸들러 3곳까지.
- `AuthAuditLog.userId` nullable화(LOGIN_FAILURE/LDAP_UNAVAILABLE), @Scheduled 1년 보존, 무중복 규칙(EC-11), 신규-only USER_PROVISIONED(xmax).

## Brainstorming Check

✅ 통과 (직접 적대적 검토, gap 3건 발견 후 스펙 반영).
- 갭 A: SSO(OIDC/SAML) LOGIN_SUCCESS 누락 → 성공 핸들러 2곳 추가 배선(EC-12).
- 갭 B: LOGIN_FAILURE↔LDAP_UNAVAILABLE 이중기록 → 무중복 규칙(EC-11).
- 갭 C: 생성자 주입 파급 + JSONB 직렬화 + @EnableScheduling + 타입 실재검증 → 스펙 §9 구현 파급(G-1~G-7).
- office-hours/brainstorming 무거운 대화형 스킬은 완성도 높은 인프라 FR에 부적합(메모리 `bts-spec-office-hours-mismatch`) → 직접 기술 스펙 + 적대적 sanity check로 대체.

## Plan

> 모든 경로는 `backend/modules/identity-access/src/...` 기준. 검증: `./gradlew :backend:identity-access:test --tests <Class>` + 모듈 ktlint/detekt.
> 실재 확인됨(phantom 0): `AuthnResult.Success(principal)`/`Failure(reason)`, `Principal.userId:UUID`/`providerType:ProviderType`, spi `FailureReason{INVALID_CREDENTIALS,INVALID_INPUT,PROVIDER_UNAVAILABLE,ACCOUNT_LOCKED}`, `RotateResult`, `rotate(oldTokenHash)`, `SessionService.revokeAllOfUser`, `UserRepository.provisionFromExternal`. ⚠️ `rotate()`의 실패 enum은 spi가 아닌 **RefreshTokenService 자체 FailureReason(Replay/NotFound/Race)** — 컨텍스트별 올바른 enum 사용.

### Task 1. V021 `auth_audit_logs` 테이블 + 인덱스 3종

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V021__auth_audit_logs.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/audit/AuthAuditLogsSchemaTest.kt`, `docs/sdd/05-data-model.md`, `docs/sdd/19-authentication.md`]
- depends-on: []

**RED**. `AuthAuditLogsSchemaTest`(Testcontainers + Flyway) — `auth_audit_logs` 테이블 존재 + 컬럼 9종(id BIGINT IDENTITY, user_id UUID NULL, event_type, provider_id, ip_address, user_agent, device_fingerprint, metadata JSONB, created_at) + 인덱스 3종 조회. 실패: relation 없음.

**GREEN**. V021 마이그레이션 작성 (스펙 §4.1/4.2). FK 없음, 단순 테이블, 인덱스 3종(`idx_..._user_created`, `idx_..._event_type`, `idx_..._created_at`).

> ⚠️ **C-4 전수 동기화**(CLAUDE.md §명세/범위 변경 전수 동기화): SDD가 구현과 drift. **같은 PR에서 정정**:
> - `docs/sdd/05-data-model.md:250` 단수 `auth_audit_log` → 복수 `auth_audit_logs`, `:302` "월 단위 파티션" → 인덱스 3종(파티셔닝 없음), ADR 링크.
> - `docs/sdd/19-authentication.md:179` "월 단위 파티션, 1년 보존" → "단순 테이블 + 인덱스, **append-only 영구 보존**(DATA.md §3, '1년'은 최소 floor; 파티셔닝 일탈 — ADR 2026-06-10-auth-audit-log-persistence)". 19.9의 `id: Long`/`userId: Long?` 표기는 실제 UUID/UUID? 와 drift 명시(또는 정정).

**REFACTOR**. 컬럼/인덱스 COMMENT, 1줄 L1 한글 주석.

**검증**. `./gradlew :backend:identity-access:test --tests AuthAuditLogsSchemaTest`.

### Task 2. `AuthAuditLog.userId` nullable화

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/AuthAuditLog.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/audit/AuthAuditLogServiceTest.kt`]
- depends-on: []

**RED**. `AuthAuditLogServiceTest`에 `userId = null`로 `AuthAuditLog` 생성 + record/findRecent 케이스 추가. 실패: `UUID?` 아님(컴파일/타입).

**GREEN**. `userId: UUID` → `UUID?`. 그 외 필드 무변경. (기존 non-null 호출부 호환.)

**REFACTOR**. KDoc에 "null = 사용자 미상(LOGIN_FAILURE/LDAP_UNAVAILABLE)" 명시.

**검증**. `./gradlew :backend:identity-access:test --tests AuthAuditLogServiceTest`.

### Task 3. `JdbcAuthAuditLogService` 영속 구현 + 빈 전환

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/JdbcAuthAuditLogService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/InMemoryAuthAuditLogService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/audit/JdbcAuthAuditLogServiceIntegrationTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/PatAndConcurrencyIntegrationTest.kt`]
- depends-on: [1, 2]

> ⚠️ **C-1**: `PatAndConcurrencyIntegrationTest`(`:79,148,283`)는 `@Autowired AuthAuditLogService`로 빈을 받아 `findRecent`로 PAT_USED를 assert. InMemory→Jdbc 전환 시 이 테스트가 자동으로 DB(`auth_audit_logs`)를 읽게 됨 → 같은 tx 내 INSERT한 PAT_USED를 findRecent가 즉시 보는지 검증 + line 265/282 "InMemory에 기록" 주석 정정. files 포함.

**RED**. `JdbcAuthAuditLogServiceIntegrationTest`(Testcontainers) — record→findRecent 왕복, 최신순(created_at DESC, id DESC tiebreaker), limit, 사용자 격리, **userId=null 영속/조회**, metadata JSONB 왕복, 재조회 내구성. 실패: `JdbcAuthAuditLogService` 없음.

**GREEN**.
- `JdbcAuthAuditLogService(@Repository 패턴, NamedParameterJdbcTemplate)` — `record()`=INSERT, `findRecent(userId,limit)`=SELECT ORDER BY created_at DESC, id DESC LIMIT. SQL은 companion const, named param, RowMapper. **JSONB 직렬화는 기존 패턴 재사용**(G-3: LockoutPolicy/authn_providers config JSONB 핸들링 grep해 동일 방식, 신규 발명 금지).
- `@Service`를 `JdbcAuthAuditLogService`에 부착. `InMemoryAuthAuditLogService`에서 `@Service` 제거(단위테스트 헬퍼로 강등). `@Transactional` 서비스이므로 `@Service` 필수(ArchUnit 가드).
- **G-2 기존 빈 소비 테스트 점검**: InMemory를 auto-wire하던 통합테스트가 Jdbc로 전환돼도 green인지(WhoamiController/Pat/ProjectMembership 통합). 단위테스트는 InMemory 직접 생성 유지.

**REFACTOR**. SQL const 추출, MaxLineLength 주의(G-7 블록 단위), KDoc 갱신(현재→영속).

**검증**. `./gradlew :backend:identity-access:test --tests JdbcAuthAuditLogServiceIntegrationTest --tests PatAndConcurrencyIntegrationTest --rerun-tasks`.

### Task 4. AuthController emit — LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`]
- depends-on: [3]

**RED**. `AuthControllerTest`(mock AuthAuditLogService) — (a) 로그인 성공 시 LOGIN_SUCCESS(userId=principal.userId, providerId, ip/userAgent from request), (b) 자격증명 실패 시 LOGIN_FAILURE(userId=null, metadata.username/reason), (c) **PROVIDER_UNAVAILABLE 실패는 LOGIN_FAILURE emit 안 함**(EC-11), (d) 로그아웃 시 LOGOUT(metadata.sid), (e) **B-1 best-effort**: `record()`가 예외를 던져도 로그인/로그아웃 응답은 정상(200/204)이고 에러가 전파되지 않음(mock이 throw하도록 stub). 실패: emit 없음 / 예외 전파.

**GREEN**. `AuthController` 생성자에 `AuthAuditLogService` 주입(G-1). login Success 분기 issueTokens 직전 + Failure 분기(reason≠PROVIDER_UNAVAILABLE) 401 직전 + logout revoke 직후 record. ip=`request.remoteAddr`, userAgent=`request.getHeader("User-Agent")`. **B-1: emit을 try-catch로 감싸 실패 시 high-severity 에러 로그(+메트릭 자리) 후 흐름 계속 — silent 삼킴 아님, 로그인 가용성 우선.**

**REFACTOR**. best-effort emit 헬퍼 private 함수 추출(try-catch+로그 1곳, 중복 제거).

**검증**. `./gradlew :backend:identity-access:test --tests AuthControllerTest`.

### Task 5. SessionService emit — LOGOUT_ALL_DEVICES

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/SessionServiceTest.kt`]
- depends-on: [3]

**RED**. `SessionServiceTest`(mock 추가) — `revokeAllOfUser(userId)` 호출 시 revoked>0이면 LOGOUT_ALL_DEVICES emit(userId, metadata.revokedSessionCount), revoked==0이면 emit 안 함. 실패: emit 없음.

**GREEN**. SessionService 생성자에 `AuthAuditLogService` 주입(G-1). revokeAllByUserId 직후 조건부 record.

**REFACTOR**. 불필요.

**검증**. `./gradlew :backend:identity-access:test --tests SessionServiceTest`.

### Task 6. RefreshTokenService emit — TOKEN_REFRESHED + SUSPICIOUS_REFRESH_REPLAY

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/RefreshTokenService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/RefreshTokenServiceTest.kt`]
- depends-on: [3]

**RED**. `RefreshTokenServiceTest`(mock 추가) — (a) rotate 성공 시 TOKEN_REFRESHED(session.userId, metadata old/new tokenId), (b) **replay 분기**(used/replaced 재사용) SUSPICIOUS_REFRESH_REPLAY emit(metadata.reason=replay), (c) **race-loser 분기도** SUSPICIOUS_REFRESH_REPLAY emit(metadata.reason=race)(C-5). 실패: emit 없음.

**GREEN**. RefreshTokenService 생성자에 `AuthAuditLogService` 주입(G-1). RotateResult.Success 직전 TOKEN_REFRESHED. **replay 분기(FailureReason.Replay)와 race-loser 분기(FailureReason.Race) 둘 다** SUSPICIOUS_REFRESH_REPLAY emit, metadata.reason으로 구분(C-5) — **체인 폐기와 같은 트랜잭션 커밋**(G-6, rotate는 예외 아닌 Failure 반환).

**REFACTOR**. 불필요.

**검증**. `./gradlew :backend:identity-access:test --tests RefreshTokenServiceTest`.

### Task 7. AutoProvisionService emit — USER_PROVISIONED (신규 only)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/AutoProvisionService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/UserRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/AutoProvisionServiceTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/user/UserRepositoryTest.kt`]
- depends-on: [3]

**RED**. (a) `UserRepositoryTest` — `provisionFromExternal`이 신규 INSERT시 `isNew=true`, 기존 username 재호출시 `isNew=false` 노출. (b) `AutoProvisionServiceTest`(mock 추가) — 신규시만 USER_PROVISIONED emit(user.id, metadata.username/providerType), 기존 재로그인은 emit 안 함(EC-3). 실패: isNew 미노출/emit 없음.

**GREEN**. `provisionFromExternal` UPSERT를 `INSERT ... ON CONFLICT ... RETURNING (xmax = 0) AS is_new`로 확장, 반환에 신규 플래그 포함(호출자 흡수 — 외부 시그니처 영향 최소). AutoProvisionService 생성자에 `AuthAuditLogService` 주입(G-1), is_new시만 record.

**REFACTOR**. provisionFromExternal 반환 타입 정리(Pair 또는 결과 DTO).

**검증**. `./gradlew :backend:identity-access:test --tests AutoProvisionServiceTest --tests UserRepositoryTest`.

### Task 8. SSO 성공 핸들러 emit — LOGIN_SUCCESS (OIDC + SAML)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/oidc/OidcAuthenticationSuccessHandler.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/saml/Saml2AuthenticationSuccessHandler.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/oidc/OidcSuccessHandlerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/saml/Saml2AuthenticationSuccessHandlerTest.kt`]
- depends-on: [3]

> ⚠️ **B-3 정정**: OIDC 테스트 실제 파일명은 `OidcSuccessHandlerTest.kt`(`...Test.kt:131`에서 핸들러 생성자 호출). `OidcAuthenticationSuccessHandlerTest.kt`는 미존재. SAML 쪽 이름은 정확.

**RED**. 각 핸들러 테스트(mock 추가) — (a) **일반 로그인** 경로(`issueTokens(request, response, account.userId)` 직후)에서 LOGIN_SUCCESS emit(user.id, providerId=oidc/saml, ip/userAgent from request). (b) **C-3 가드**: 연결 모드(linking) early-return 분기(`OidcAuthenticationSuccessHandler.kt`의 `if (linkingIntent != null) { handleLinkingMode; return }`, SAML 동형 `:103-105`)에서는 LOGIN_SUCCESS emit **안 함** — vacuous green 회피 위해 연결 모드 비-emit을 명시 assert. 실패: emit 없음.

**GREEN**. 각 핸들러 생성자에 `AuthAuditLogService` 주입(G-1). 컴포넌트 스캔(`@Component`) 자동 주입이라 SecurityConfig 수정 불요(검증7 OK). **emit 위치 = 연결 모드 early-return 아래의 일반 로그인 경로 `issueTokens` 직후**(C-3). (USER_PROVISIONED는 Task 7 AutoProvisionService 내부 별도 emit — 중복 아님.)

**REFACTOR**. 불필요.

**검증**. `./gradlew :backend:identity-access:test --tests Oidc* --tests Saml2*`.

### Task 9. LdapProvider emit — LDAP_UNAVAILABLE

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapProvider.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/LdapProviderUnitTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/LdapProviderBindForLinkingTest.kt`]
- depends-on: [3]

> ⚠️ **B-2 정정**: `LdapProviderTest.kt`는 미존재. LdapProvider 생성자(`LdapProvider.kt:64-69`)에 6번째 인자 `AuthAuditLogService` 추가 시 깨지는 실제 테스트는 `LdapProviderUnitTest.kt:77`(5인자 직접 호출) + `LdapProviderBindForLinkingTest.kt:169,194`(2곳). 둘 다 files 포함, 생성자 호출부 갱신 필수.

**RED**. `LdapProviderUnitTest`(mock 추가) — (a) LDAP 미설정/bind password 미설정시, (b) CommunicationException(통신 불가)시 LDAP_UNAVAILABLE emit(userId=null, metadata.reason/exception). 실패: emit 없음.

**GREEN**. LdapProvider 생성자에 `AuthAuditLogService` 주입(G-1) — `LdapProviderUnitTest`·`LdapProviderBindForLinkingTest`의 생성자 호출 3곳 mock 인자 추가. config null / bind password null / CommunicationException catch 지점에서 record. userId=null(EC-2, 더미 UUID 금지).

**REFACTOR**. 불필요.

**검증**. `./gradlew :backend:identity-access:test --tests LdapProviderTest`.

### Task 10. ~~보존 1년 — @Scheduled 정리 작업~~ (드롭 — DATA.md §3 충돌)

> ❌ **드롭 (Maxi 결정 2026-06-10)**. DATA.md §3 "감사 로그 절대 삭제 금지(append-only)"와 정면 충돌. "보존 1년"(SDD §2.3.3)은 **최소 보존 floor**로 해석 — 영구 보존이 1년 floor를 충족(∞ ≥ 1년). 삭제 작업 불필요. `purgeOlderThan`·`@Scheduled`·`SchedulingConfiguration` 전부 미도입. spec D2/FR-7은 "append-only 영구 보존"으로 갱신. 헌법 준수.

### Task 11. "이벤트 누락 0" 커버리지 캡스톤 테스트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/audit/AuthEventEmitCoverageTest.kt`]
- depends-on: [4, 5, 6, 7, 8, 9]

**RED**. `AuthEventEmitCoverageTest` — `AuthEventType` 12종 enum 전부가 코드베이스에 1개 이상 emit 경로(또는 테스트 커버)를 가지는지 회귀 가드. enum에 새 값 추가 시 fail해 누락 방지(vacuous 회피 — 일부러 미배선 값 넣으면 fail 확인, 메모리 `archunit-vacuous-rule-silent-pass`). 실패: 일부 미커버.

**GREEN**. (선행 task가 모두 배선했으므로) 12종 전부 커버 확인.

**REFACTOR**. 가드 메시지에 "새 AuthEventType 추가시 emit 배선 + 본 테스트 갱신" 안내.

**검증**. `./gradlew :backend:identity-access:test --tests AuthEventEmitCoverageTest`.

## Plan 메타

- task 수: 10 (T10 드롭 — DATA.md §3 충돌)
- wave 예상 (depends-on + files 기반):
  - wave 1: T1(db), T2(security) — 병렬(다른 파일)
  - wave 2: T3 — 영속+빈전환(T1,T2 의존)
  - wave 3: T4~T9 — emit 배선 6종, 모두 T3 의존 + 서로 다른 파일 → 병렬 후보
  - wave 4: T11 — 캡스톤(T4~T9 의존)
- ⚠️ **모듈 단일 test 컴파일 직렬화**(메모리 `bts-plan-wave-gradle-module-compile`): wave 3의 6 task가 파일은 disjoint여도 identity-access 단일 test 컴파일 단위 + Gradle 프로젝트 빌드 락이라 test 실행은 직렬화됨. **실 dispatch는 Gradle 락 충돌 회피 위해 직렬/소batch로 진행**(메모리 `parallel-dispatch-precommit-hook-race`).
- **G-1 주입 파급**: T4~T9 각 task의 `files`에 해당 빈의 기존 단위테스트 포함. 통합테스트는 Jdbc @Service 자동 배선이라 공유 TestConfig 수술 불요(공유 파일 충돌 회피) — 단위테스트 mock은 각 task 자기 테스트 파일에만.
- TDD 강제: yes (red→green→refactor, `test:` 커밋 선행 검증)
- 추가 검증: 모듈 ktlint/detekt(`--rerun-tasks`), 기존 로그인/세션/리프레시/프로비저닝 회귀 0.

## 리뷰 결과

### security-engineer 독립 적대적 리뷰 (2026-06-10)

autoplan overkill 회피(메모리 `bts-review-plan-autoplan-overkill`) → 보안 관점 독립 리뷰. CEO 리뷰는 범위 확정됨으로 생략.

**BLOCKER 3건**.
- **B-1 (Maxi 정책 결정 — 게이트1 상정)**. NFR-3 "동기 트랜잭션 내 INSERT, 삼킴 금지"가 web 레이어 emit(LOGIN_*, LOGOUT)에서 불가. `AuthController.kt:52`는 의도적 무-트랜잭션. 감사 DB 장애 시 가용성 vs 무결성 trade-off. **→ 게이트1 결정 후 spec NFR-3 + T4 GREEN 확정.**
- **B-2 (정정 완료)**. T9 files `LdapProviderTest.kt`(미존재) → `LdapProviderUnitTest.kt:77` + `LdapProviderBindForLinkingTest.kt:169,194`.
- **B-3 (정정 완료)**. T8 files `OidcAuthenticationSuccessHandlerTest.kt`(미존재) → `OidcSuccessHandlerTest.kt:131`.

**CONCERN 5건 (plan 반영 완료)**.
- C-1. Pat 통합테스트 Jdbc 전환 점검 → T3 files/검증 추가.
- C-2. @EnableScheduling 부재 → T10에 `SchedulingConfiguration` 격리 패턴 + 테스트 cron 비활성.
- C-3. SSO 연결 모드 early-return에선 LOGIN_SUCCESS 비-emit → T8 RED 가드.
- C-4. SDD 05/19.9 테이블명·파티션 drift → T1에 전수 동기화 추가.
- **C-5 (Maxi 확인 — 게이트1 상정)**. race-loser refresh를 SUSPICIOUS_REFRESH_REPLAY로 감사할지. 코드 저자는 race-loser도 "탈취 위험"으로 보고 동일 REFRESH_REPLAY 사유로 revoke. plan은 양성으로 보고 제외. **→ 게이트1 확인.**

**OK (검증 완료)**. 계정열거 timing(LocalProvider dummy Argon2), userId nullable 파급(기존 4 emit non-null 호환), xmax 기법(ON CONFLICT 이미 존재), SSO user.id/request 접근, LDAP_UNAVAILABLE 위치, bean 모호성, V021 충돌 없음.
