# FR-AU-10 인증 감사 로그 (백엔드 1차 D1~D5) — 스펙

> slug: fr-au-10-audit-log | type: auth | agent: security-engineer | BC: identity-access
> 작성: 2026-06-10 | 선행 ADR: [2026-06-10-auth-audit-log-persistence](../decisions/2026-06-10-auth-audit-log-persistence.md)
> 범위: D1~D5 (도메인·명세·데이터모델·emit 배선·테스트). D6 관리자 조회 UI + D7 E2E는 후속 PR.

## 0. 한 줄 요약

이미 골격이 선 감사 시스템(`audit/` 패키지: `AuthEventType` 12종 + `AuthAuditLog` + `AuthAuditLogService` + `InMemoryAuthAuditLogService`)을 **DB 영속화**하고, enum 12종이 **전부 emit되도록 8개 갭을 배선**한다. 보존 1년은 `@Scheduled` 정리로 enforce.

## 1. 사용자 시나리오 (Given-When-Then)

이 PR은 백엔드 인프라이므로 "사용자"는 운영자/감사자(후속 UI 소비자)와 시스템이다.

- **S1 로그인 성공 기록**. Given 유효한 자격증명, When 로그인, Then `auth_audit_logs`에 `LOGIN_SUCCESS` 1행(userId, providerId, ip, userAgent) 영속.
- **S2 로그인 실패 기록**. Given 잘못된 자격증명(존재하지 않는 username 포함), When 로그인 시도, Then `LOGIN_FAILURE` 1행 영속. **userId는 null**(사용자 미상 또는 존재 probe 회피), 시도 username은 metadata.
- **S3 로그아웃 기록**. Given 로그인 세션, When 로그아웃, Then `LOGOUT` 1행(sid metadata).
- **S4 전체 로그아웃 기록**. Given 다중 세션, When `revokeAllOfUser()` 호출(현재/미래 호출자), Then `LOGOUT_ALL_DEVICES` 1행(revokedCount metadata).
- **S5 토큰 갱신 기록**. Given 유효 refresh token, When rotate 성공, Then `TOKEN_REFRESHED` 1행.
- **S6 리플레이 탐지 기록**. Given 이미 사용/교체된 refresh token 재사용, When rotate, Then `SUSPICIOUS_REFRESH_REPLAY` 1행 + 체인 폐기(기존 동작). **양성 race-loser(EC-22)는 제외**(아래 §6 EC-7).
- **S7 신규 프로비저닝 기록**. Given 외부 IdP(LDAP/OIDC/SAML) 최초 로그인, When 신규 사용자 INSERT, Then `USER_PROVISIONED` 1행. **기존 사용자 재로그인은 emit 안 함**.
- **S8 LDAP 불가 기록**. Given LDAP 서버 통신 불가/미설정, When 인증 시도, Then `LDAP_UNAVAILABLE` 1행(userId null, reason metadata).
- **S9 PAT 사용 기록**. (기존 유지) PAT 인증 시 `PAT_USED` 1행 — 영속 백엔드로 자동 전환.
- **S10 권한 변경 기록**. (기존 유지) 프로젝트 멤버 추가/제거/역할변경 시 `PROJECT_MEMBER_ADDED/REMOVED`·`PROJECT_ROLE_CHANGED` — 영속 백엔드로 자동 전환.
- **S11 보존 1년**. Given `created_at`이 1년 지난 행, When 정리 스케줄 실행, Then 해당 행 삭제.
- **S12 프로세스 재시작 내구성**. Given 기록된 감사 로그, When 애플리케이션 재시작, Then 로그 보존(인메모리와 달리 소실 없음).

## 2. 기능 요구사항 (FR)

- **FR-1 (D3)**. `auth_audit_logs` 테이블 생성 (Flyway V021). 파티셔닝 없는 단순 테이블 + 인덱스 3종.
- **FR-2 (D4)**. `JdbcAuthAuditLogService` 구현 (NamedParameterJdbcTemplate + RowMapper). `record()` = INSERT, `findRecent(userId, limit)` = SELECT 최신순.
- **FR-3 (D4)**. 프로덕션 Bean을 `Jdbc` 구현으로 전환. `InMemoryAuthAuditLogService`는 단위테스트 헬퍼로 강등(`@Service` 제거).
- **FR-4 (D1/D4)**. `AuthAuditLog.userId`를 `UUID` → `UUID?`로 변경 (LOGIN_FAILURE·LDAP_UNAVAILABLE 수용, SDD 19.9 원안 `userId: Long?`과 정합).
- **FR-5 (D4)**. 8개 갭 이벤트 emit 배선 (§5 매핑표). enum 12종 전부 1개 이상 emit 경로 보유.
- **FR-6 (D4)**. `USER_PROVISIONED`은 신규 사용자에만 emit. `provisionFromExternal()`이 신규 여부를 노출(PostgreSQL `xmax = 0` RETURNING) → 호출자(AutoProvisionService)가 신규일 때만 record.
- **FR-7 (D2)**. 보존 1년 enforcement. `@Scheduled` 작업이 `created_at < now() - INTERVAL '1 year'` 행을 주기 삭제.
- **FR-8 (D5)**. enum 12종 전부에 대해 "emit → DB 영속 → findRecent 조회" 통합 테스트(Testcontainers). "이벤트 누락 0" 회귀 가드.

## 3. 비기능 요구사항 (NFR)

- **NFR-1 보안/PII**. logback `audit.auth` 로거에는 PII(ip/userAgent/deviceFingerprint) 미출력 유지(기존 InMemory 패턴). DB에는 저장하되 감사 목적상 허용(VIEW_AUDIT_LOG 권한자만 후속 조회).
- **NFR-2 계정 열거 차단**. LOGIN_FAILURE 기록 시 username→userId 역조회 **금지**(존재 probe 회피, 메모리 `auth-extraction-before-resource-lookup`). userId=null + metadata.username.
- **NFR-3 감사 무결성**. 감사 INSERT 실패가 인증 흐름을 깨선 안 되지만, "이벤트 누락 0"이 목표이므로 INSERT는 동일 트랜잭션 내 동기 수행(기존 동기 record 패턴 유지). best-effort 삼킴 금지(메모리 `best-effort-loop-permission-exception-nonprod-mask`).
- **NFR-4 동시성**. record() 동시 호출 안전(DB INSERT는 본질적으로 안전, IDENTITY PK).
- **NFR-5 내구성**. 영속(재시작 보존). InMemory 대체.
- **NFR-6 회귀 0**. 기존 4개 emit 지점(PAT/PROJECT_*) 및 로그인/세션/리프레시/프로비저닝 흐름의 동작 무변경(감사 기록 추가 외).

## 4. 데이터 모델 변경 (D3)

### 4.1 신규 테이블 `auth_audit_logs` (V021)

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | GENERATED ALWAYS AS IDENTITY, PK | 내부 PK(append-only 단조 증가, 정렬 tiebreaker) |
| user_id | UUID | NULL | 행위 주체. LOGIN_FAILURE/LDAP_UNAVAILABLE은 null. **FK 없음**(아래 근거) |
| event_type | VARCHAR(40) | NOT NULL | `AuthEventType` enum name |
| provider_id | VARCHAR(50) | NOT NULL | "local"/"ldap"/"oidc"/"saml"/"pat"/"project-membership" 등 |
| ip_address | VARCHAR(45) | NULL | IPv6 최대 45자. web 레이어 emit만 채움 |
| user_agent | TEXT | NULL | web 레이어 emit만 채움 |
| device_fingerprint | VARCHAR(255) | NULL | 현재 미사용(FR-MF-05 대비 컬럼만) |
| metadata | JSONB | NOT NULL DEFAULT '{}' | `Map<String,String>` (sid, targetUserId, username, reason 등) |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT now() | 발생 시각 |

**FK 없음 근거**. 감사 로그는 append-only 포렌식 기록. 사용자 생명주기(soft-delete)와 결합하지 않아야 하며, 삭제된 사용자의 과거 행위 기록도 보존돼야 한다. FK-check 쓰기 오버헤드도 회피.

**테이블명**. `auth_audit_logs`(복수, users/sessions/refresh_tokens/authn_providers 관례). SDD 05-data-model의 단수 `auth_audit_log` 표기는 drift — 복수로 통일.

### 4.2 인덱스 3종

- `idx_auth_audit_logs_user_created` ON (user_id, created_at DESC) — findRecent(userId) 조회.
- `idx_auth_audit_logs_event_type` ON (event_type) — 이벤트 유형 필터(후속 admin).
- `idx_auth_audit_logs_created_at` ON (created_at) — 보존 1년 정리 sweep(`DELETE WHERE created_at < ...`).

### 4.3 도메인 클래스 변경

- `AuthAuditLog.userId`: `UUID` → `UUID?`. (기존 4개 emit 지점은 non-null 전달이라 호환. 기존 테스트 호환.)
- 그 외 필드 무변경. `findRecent(userId: UUID, limit)` 시그니처 유지(특정 사용자 조회는 non-null).

## 5. emit 배선 매핑 (D4 핵심)

| 이벤트 | 위치(파일:대략라인) | 메서드 | emit 조건 | userId | ip/UA | metadata |
|---|---|---|---|---|---|---|
| LOGIN_SUCCESS (동기) | web/AuthController:~120 | login | AuthnResult.Success, issueTokens 직전 (Local/LDAP/PAT 동기 로그인) | principal.userId | ✓ request | — |
| LOGIN_SUCCESS (OIDC) | provider/oidc/OidcAuthenticationSuccessHandler:~119 | onAuthenticationSuccess | 프로비저닝 직후 | user.id | ✓ request | — |
| LOGIN_SUCCESS (SAML) | provider/saml/Saml2AuthenticationSuccessHandler:~114 | onAuthenticationSuccess | 프로비저닝 직후 | user.id | ✓ request | — |
| LOGIN_FAILURE | web/AuthController:~135 | login | AuthnResult.Failure **단, PROVIDER_UNAVAILABLE 제외**(§6 EC-11) | **null** | ✓ request | username, reason |
| LOGOUT | web/AuthController:~222 | logout | sessionService.revoke 직후 | JWT subject | ✓ request | sid |
| LOGOUT_ALL_DEVICES | session/SessionService:~123 | revokeAllOfUser | revokeAllByUserId 직후, revoked>0 | userId 파라미터 | null | revokedCount |
| TOKEN_REFRESHED | session/RefreshTokenService:~131 | rotate | RotateResult.Success 직전 | session.userId | null | oldTokenId,newTokenId |
| SUSPICIOUS_REFRESH_REPLAY | session/RefreshTokenService:~79 | rotate | **replay 분기만**(used/replaced 재사용) | session.userId | null | tokenId, reason |
| USER_PROVISIONED | provider/ldap/AutoProvisionService:~60 | provision | **신규 INSERT 시만** | user.id | null | username, providerType |
| LDAP_UNAVAILABLE | provider/ldap/LdapProvider:~95,~243 | authenticate/bindAndExtract | 미설정/통신불가 | **null** | null | reason, exception |
| PAT_USED | web/WhoamiController (기존) | — | (변경 없음) | — | — | — |
| PROJECT_MEMBER_ADDED/REMOVED/ROLE_CHANGED | project/ProjectMembershipService (기존) | — | (변경 없음) | — | — | — |

**ip/userAgent 정책**. web 레이어 emit(LOGIN_*, LOGOUT, PAT_USED)만 캡처. service 레이어 emit은 null(투기적 세션 역조회·시그니처 수술 회피, NFR-6 surgical). ip/userAgent는 이미 nullable best-effort 컨텍스트 필드.

**SUSPICIOUS_REFRESH_REPLAY semantics**. rotate()에는 (a) replay 분기(EC-23, 이미 used/replaced된 토큰 재사용 = 공격 의심)와 (b) race-loser 분기(EC-22, 동시 갱신 경쟁 패자 = 양성)가 있다. **(a)만 emit**. (b)는 정상 동시성으로 false alarm 회피.

**USER_PROVISIONED 신규 판정**. `UserRepository.provisionFromExternal()`의 UPSERT를 `INSERT ... ON CONFLICT ... RETURNING (xmax = 0) AS is_new`로 확장해 신규 INSERT 여부 노출. AutoProvisionService가 `is_new == true`일 때만 record. 호출자(LdapProvider/OIDC/SAML 성공핸들러) 반환 타입 변경 없음(provision 내부 흡수).

## 6. 엣지 케이스

- **EC-1 (존재하지 않는 username 로그인 실패)**. userId=null, metadata.username=시도값. 역조회 금지(NFR-2).
- **EC-2 (LDAP 미설정/통신불가)**. userId=null. 시스템 더미 UUID 사용 금지(매직 sentinel 회피) → nullable로 해결.
- **EC-3 (기존 사용자 외부 재로그인)**. USER_PROVISIONED emit 안 함(xmax≠0). 회귀 가드 테스트.
- **EC-4 (race-loser refresh)**. SUSPICIOUS_REFRESH_REPLAY emit 안 함(§5 semantics).
- **EC-5 (감사 INSERT 실패)**. 동기 트랜잭션 내 발생 시 인증 트랜잭션과 함께 처리. 권한 예외 등을 best-effort로 삼키지 않음.
- **EC-6 (Bean 모호성)**. `JdbcAuthAuditLogService`만 `@Service`. `InMemoryAuthAuditLogService`는 `@Service` 제거(단위테스트 직접 생성). 동일 인터페이스 2 빈 충돌 방지.
- **EC-7 (통합테스트 부팅 시 V021 부재)**. identity-access 통합테스트는 Testcontainers + Flyway 전 마이그레이션 적용 → V021 자동 반영, 테이블 존재. Jdbc 빈이 DB에 써도 정상.
- **EC-8 (V번호 충돌)**. 동시 브랜치(FR-VR-03 등)는 issue-tracking 모듈 → identity-access V번호 무관. 단 머지 직전 V021 재확인(메모리 `migration-vnumber-concurrent-branch-collision`).
- **EC-9 (metadata 직렬화)**. `Map<String,String>` → JSONB. 빈 map은 `'{}'`.
- **EC-10 (findRecent 정렬 안정성)**. created_at 동률 시 id DESC tiebreaker.
- **EC-11 (LOGIN_FAILURE ↔ LDAP_UNAVAILABLE 무중복)**. provider-unavailable로 인한 로그인 실패는 `LDAP_UNAVAILABLE`로만 기록(LdapProvider 깊은 지점). AuthController는 `FailureReason == PROVIDER_UNAVAILABLE`이면 LOGIN_FAILURE를 **추가 emit하지 않음**(이중 기록 회피). 그 외 자격증명/잠금 실패만 LOGIN_FAILURE.
- **EC-12 (SSO 로그인 성공 커버리지)**. LOGIN_SUCCESS는 동기 `/login`(AuthController) + OIDC/SAML 성공 핸들러 **3곳**에서 emit. 리다이렉트 SSO도 누락 0.
- **EC-13 (SSO 로그인 실패)**. 현재 SSO 실패 핸들러 미배선 가능성 → 본 PR은 동기 `/login` 실패만 LOGIN_FAILURE 보장. SSO 실패 감사는 실패 핸들러 존재 시에만, 없으면 후속(plan에서 실재 확인 후 판단).

## 7. 제약 조건

- BC 격리. identity-access 단일 모듈. cross-BC 호출 없음.
- 절대 규칙(DEVELOPMENT.md §보안) — 인증 우회 엔드포인트 추가 금지(본 PR은 신규 엔드포인트 0, emit 배선만). `@Transactional` 서비스는 `@Service`(ArchUnit 가드).
- 신규 의존성 0(JdbcTemplate/Flyway 기존재).
- 본 PR에 HTTP 조회 API 없음(D6 후속). `findRecent`는 인터페이스/테스트용으로만.

## 8. 측정 가능한 완료 기준

1. `auth_audit_logs` 테이블 V021 마이그레이션 적용(Testcontainers 부팅 green).
2. `JdbcAuthAuditLogService` 통합테스트 — record→findRecent 왕복, 최신순/limit/사용자격리/nullable userId.
3. **enum 12종 전부 emit 통합테스트 green**(이벤트 누락 0 회귀 가드).
4. USER_PROVISIONED 신규-only / race-loser 비-emit / LOGIN_FAILURE userId-null 회귀 가드.
5. 보존 정리 작업 테스트(1년 경과 행 삭제, 미경과 행 보존).
6. identity-access 모듈 전체 test + ktlint + detekt green(`--rerun-tasks`).
7. 기존 로그인/세션/리프레시/프로비저닝 E2E·통합 회귀 0.

## 9. 구현 파급 (plan 단계 필수 주의)

Brainstorming sanity check에서 도출. plan task 분해 시 반드시 포함.

- **G-1 생성자 주입 파급 (메모리 `plan-files-constructor-injection-existing-tests`)**. `AuthAuditLogService`를 신규 주입하는 빈 = AuthController·SessionService·RefreshTokenService·AutoProvisionService·LdapProvider·OIDC/SAML 성공핸들러. 각 빈의 **기존 단위테스트(mock 추가)** + **통합 TestConfig 배선**이 함께 깨지므로, 해당 task의 `files`에 기존 테스트도 포함. (WhoamiController·ProjectMembershipService는 이미 주입됨.)
- **G-2 기존 빈 소비 테스트 점검**. `InMemoryAuthAuditLogService`의 `@Service` 제거 시, 이 빈을 auto-wire해 findRecent를 assert하던 기존 테스트(WhoamiControllerTest/PatAndConcurrencyIntegrationTest/ProjectMembershipServiceTest 등)가 Jdbc 빈으로 전환됨 → DB 기반 assert로 동작하는지 확인. 단위테스트는 InMemory 직접 생성 유지.
- **G-3 JSONB↔Map 직렬화**. `metadata: Map<String,String>` ↔ JSONB. 신규 직렬화 발명 금지 — 기존 JSONB 패턴 재사용(LockoutPolicy JSONB / authn_providers config JSONB 핸들링 grep해 동일 방식, 메모리 `lockout-policy-location`).
- **G-4 `@EnableScheduling` 실재 확인**. `@Scheduled` 보존 작업 추가 전 모듈에 스케줄링 활성화 존재 여부 grep. 없으면 추가(다른 @Scheduled 빈 부작용 확인). 테스트는 purge 메서드 직접 호출(스케줄 트리거 의존 금지), 테스트 프로파일에서 스케줄 실행 억제.
- **G-5 타입 실재 검증 (메모리 phantom 엔티티)**. plan/impl 전 `AuthnResult`(sealed Success/Failure), `principal.userId`/`principal.providerType`, `FailureReason.PROVIDER_UNAVAILABLE`, `RotateResult`, `provisionFromExternal` 시그니처를 `git grep`으로 실재 확인 후 인용. 추측 금지.
- **G-6 replay 분기 트랜잭션 커밋**. SUSPICIOUS_REFRESH_REPLAY는 체인 폐기와 **같은 트랜잭션에서 커밋**돼야(rotate가 예외 아닌 Failure 반환 = 커밋). 감사 row가 폐기와 함께 남는지 확인.
- **G-7 라인 길이 (메모리 `ktlint-detekt-linelength-and-baseline-traps`)**. SQL const 문자열·RowMapper·긴 record() 호출은 detekt MaxLineLength 위반 주의. baseline 라인시프트 회피 위해 블록 단위 작성.
