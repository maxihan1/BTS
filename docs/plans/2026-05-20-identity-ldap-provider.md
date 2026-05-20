<!-- BTS plan — identity-access FR-AU-02 LDAP/AD Provider 정식 구현 -->

# FR-AU-02 — LDAP/AD Provider 정식 구현

> slug: identity-ldap-provider
> type: auth
> agent: security-engineer (primary) + db-engineer + designer + frontend-engineer + qa-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 마스터플랜: `docs/plan/product/identity-access.md §2.2`
> 선행 PR: #3 (FR-AU-01 SPI 인프라)

## Brief

PR #3 가 도입한 `AuthenticationProvider` SPI 의 **첫 실제 구현**. 사내 LDAP/AD 서버를 통한 인증 + 사용자/그룹 매핑 + 계정 잠금 정책. 마스터플랜 §2.2 D1~D7.

본 PR 에 동반되는 첫 DB 마이그레이션 V002 — `authn_providers` (LDAP/SAML/OIDC config) + `user_external_accounts` (provider, externalId 매핑). FR-AU-01 가 보류했던 D3 도 함께 도입.

또한 PR #3 머지 후 잔여 작업: `scripts/verify/bootjar-no-fakes.sh` 실행 권한 부여 (Maxi 가 머지 후 `! chmod +x` 직접 실행, stash 보관 후 worktree 에서 pop) — chore 커밋 1건으로 묶음.

## 도메인 정리

- **BC**. identity-access (단독)
- **영향 엔티티 (신규)**.
  - DB. `authn_providers` (Provider 메타 + JSONB config), `user_external_accounts` (User ↔ external subject 매핑)
  - Kotlin VO. `LdapConfig` (server_url, base_dn, bind_dn, bind_password_env, user_search_filter/base, group_search_base/filter, group_mapping), `LockoutPolicy` (max_attempts, lockout_minutes, scope)
  - Kotlin 엔티티. `ExternalAccount(provider_id, external_subject, user_id, last_login_at)` — `user_external_accounts` 매핑
- **기존 엔티티 (영향)**.
  - `User` — PoC #2 의 `users` 테이블 그대로. 본 PR 은 새 행 INSERT 만 (LDAP 첫 로그인 시 자동 프로비저닝)
  - `UserCredential` — 영향 없음 (LDAP 인증은 외부 검증, 비밀번호 BTS DB 미저장)
  - `AuthenticationProvider` (SPI, PR #3) — `LdapProvider` 가 첫 실제 구현
- **새 용어** (glossary 추가 후보, Maxi 승인 필요).
  - `ExternalAccount` — User 의 외부 IdP 측 식별자 매핑 (provider × external_subject → user_id). DB 영속
  - `LdapConfig` — LDAP 연결 + 검색 + 그룹 매핑 설정 VO. JSONB 로 `authn_providers.config` 에 직렬화
  - `LockoutPolicy` — N회 실패 시 M분 잠금 정책 VO. BTS 측 자체 lockout (LDAP 서버 측 의존 안 함)
  - `BaseDN` — LDAP 검색 시작점 (예. `dc=bts,dc=local`). 표준 용어
  - `BindDN` — LDAP 인증 수행 계정의 DN (서비스 계정). 표준 용어
  - `External Subject` — 외부 IdP 가 발급한 사용자 고유 식별자 (LDAP DN, OIDC sub, SAML NameID). `user_external_accounts.external_subject` 컬럼명
- **기존 결정과의 정합**.
  - PR #3 ADR `2026-05-20-authentication-provider-spi-naming.md` 의 `Credential.LdapBind` sealed 변종 약속 → 본 PR 이 실제 구현 (placeholder 였던 sealed 변종을 실제 작동 코드로 변환). 충돌 아닌 약속 이행.
  - PR #3 spec §5 가 의도적으로 보류한 D3 (`authn_providers` 테이블) → 본 PR 에 통합. spec NFR 명시.
- **기존 ADR 영향**.
  - `argon2id-parameters` (PoC #2) — Local 비밀번호 해싱, LDAP 외부 검증과 무관 ✅
  - `csrf-cookie-mode` (PoC #2) — 모든 인증 흐름에 적용, LDAP 도 동일 ✅
  - `keycloak-image-selection` (PoC #2) — OIDC IdP, LDAP 와 별개 (다른 컨테이너) ✅
  - `testcontainers-docker-desktop-config` (PoC #2) — OpenLDAP Testcontainers 도 같은 docker socket 설정 적용 ✅
  - `authentication-provider-spi-naming` (PR #3) — 본 PR `LdapProvider` 가 `com.atlas.bts.identity.spi.AuthenticationProvider` 구현, 패키지 경계 + ArchUnit 룰 모두 준수 ✅
- **본 PR 신규 ADR 후보 (impl 단계에서 결정 시점에 작성)**.
  - `2026-05-20-ldap-testcontainers-image.md` — OpenLDAP 이미지 선정 (osixia / bitnami / 공식 후보 비교)
  - `2026-05-20-user-external-accounts-schema.md` — V002 스키마 (provider_id FK ON DELETE 정책, external_subject 인덱스, last_login_at 갱신 시점)
  - `2026-05-20-lockout-policy-location.md` — BTS 측 자체 lockout 결정 (LDAP 서버 측 의존 안 함, 이유 + 데이터 모델)
  - `2026-05-20-ldap-group-mapping-policy.md` — LDAP 그룹 DN → BTS role 매핑 (1:1 명시 매핑 vs 패턴 매칭)
- **grill-with-docs 우회 사유**. PR #3 ADR (`authentication-provider-spi-naming`) 가 본 PR 의 도메인 결정 (sealed Credential 확장 + Provider 패키지 규칙) 을 이미 설정함. LDAP 용어 (BaseDN/BindDN/ExternalSubject) 는 표준이라 모호성 적음. 1인 부담 + Auto mode 합리적 판단으로 직접 분석 (PoC 패턴 일관성).

## 스펙

전체 스펙. [docs/specs/2026-05-20-identity-ldap-provider.md](../specs/2026-05-20-identity-ldap-provider.md)

핵심 5줄.
- **`Credential.LdapBind` sealed 변종 추가** — PR #3 ADR 약속 이행
- **`LdapProvider` 구현** — Spring `LdapAuthenticationProvider` 위 BTS SPI 어댑터 + LockoutPolicy + 자동 프로비저닝 (단일 트랜잭션)
- **Flyway V002 마이그레이션** — `authn_providers` + `user_external_accounts` (FR-AU-01 보류 D3 통합)
- **Testcontainers OpenLDAP** — 7 시나리오 (S-01~S-07) 모두 통합 테스트
- **로그인 폼 UI + Playwright E2E 별도 PR** (§10 분할 정당화) — design-consultation 트리거 + 디자인 시스템 신중도

**PR 분할 결정**. D1~D5 본 PR / D6~D7 PR #5, #6 위임. 게이트 1 에서 Maxi 확정 필요.

명시적 비-스코프 (spec §9). FR-AU-06 다중 활성, FR-AU-07 도메인 라우팅, FR-AU-08 계정 통합, FR-PM-* 권한 매핑, UserCredential 리네임.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 10건 식별 — 4건 spec 본문 보강, 6건 impl 단계 결정).

### Phase B 직접 수행 — 발견된 gap

1. Spring Data JDBC vs JPA vs plain JDBC — impl 결정 (plain JDBC 권장)
2. **bind password env var 로딩 시점** — startup 1회 + restart 필요 (매 인증 syscall 회피). spec FR-2 보강 ✅
3. seed.ldif 위치 = `infra/ldap/seed.ldif` (PoC #2 패턴 일관성)
4. **authn_providers 0행 처리** — lazy init + Failure(PROVIDER_UNAVAILABLE). 부팅 실패 X (다른 Provider 만으로 동작 가능). spec FR-2 보강 ✅
5. **groups JSONB 인덱스** — 본 PR 검색 query 없음, 인덱스 X. FR-PM-01 도입 시 GIN 검토. spec NFR 보강 ✅
6. @Transactional 의존성 — Spring Boot starter 자동 활성. impl 결정
7. AuthenticationManager vs LdapAuthenticationProvider — impl 결정 (Spring 표준 LdapAuthenticationProvider 위 BTS SPI 어댑터)
8. ArchUnit 룰 — `provider.ldap` 패키지는 spi 아님, Spring Security 자유 import. plan-eng-review 검토 항목
9. @Component 자동 등록 + @Profile("test-spi") 가짜 Provider — production / test 프로필 분리 OK
10. **PoC #2 + PR #3 회귀 격리** — LdapProvider 가 통합 테스트 컨텍스트에 자동 로드됨, lazy init 분기로 안전. ProviderRegistryTest assertion 갱신 가능성 — impl 단계. spec NFR 보강 ✅

### iteration 결정

gap 4건 spec 본문 보강 + 6건 impl 단계 결정 가능. iteration 불필요. office-hours/brainstorming 대화형 우회 사유. PoC 패턴 일관성. `/bts-plan` 진입.

## Plan

직접 분해 (`superpowers:writing-plans` 대화형 우회 — PoC 패턴 일관성 + spec 결정 명확). spec FR-1~FR-6 + 시나리오 S-01~S-07 + §6 엣지 + §8 완료기준을 7 task 로 분해.

### Task 1. 의존성 추가 + Flyway V002 마이그레이션 (인프라 + DB, db-engineer 책임)

**파일**.
- `backend/modules/identity-access/build.gradle.kts` — `spring-boot-starter-data-ldap` + `spring-security-ldap` + `postgresql` 런타임 JDBC 드라이버 + `spring-boot-starter-jdbc` 추가 (PoC #2 가 이미 일부 도입했을 수 있음 — 실측 확인)
- `backend/modules/identity-access/src/main/resources/db/migration/V002__authn_providers_and_user_external_accounts.sql` — spec FR-4 SQL 그대로
- PoC #2 가 V001 (`users` 테이블) 을 도입했는지 실측 확인. 없으면 V001 도 본 PR 에 추가? — **결정**. V001 `users` 도입은 본 PR 스코프 외 (PoC #2 가 미도입했다면 별도 chore PR 또는 본 PR 첫 task 로 도입). impl 단계 실측 후 결정.

**RED**. `V002MigrationTest.kt` — Testcontainers postgres + Flyway 자동 적용 + `authn_providers` + `user_external_accounts` 테이블 존재 + 인덱스 존재 검증.

**GREEN**. V002 SQL 작성.

**REFACTOR**. SQL 주석 한글 보강 + 컬럼 순서 정렬.

**검증**. `./gradlew :modules:identity-access:test --tests V002MigrationTest`. 커밋 prefix `chore(deps):` + `feat(db):`.

### Task 2. `Credential.LdapBind` sealed 변종 추가 (TDD)

PR #3 의 `Credential.kt` 수정. 새 sealed 변종 + equals/hashCode CharArray 내용 비교 (UsernamePassword 패턴 동일).

**RED**.
- 파일. `src/test/kotlin/com/atlas/bts/identity/spi/CredentialLdapBindTest.kt`
- 테스트.
  ```kotlin
  @Test fun `LdapBind equals same content CharArray`()
  @Test fun `LdapBind hashCode same content CharArray`()
  @Test fun `LdapBind is a sealed Credential variant`()  // when exhaustive 검증
  @Test fun `LdapBind password CharArray separate from username String`()
  ```

**GREEN**.
- `Credential.kt` 에 `LdapBind(username, password: CharArray)` sealed 변종 추가. PR #3 ADR line 51~57 약속 이행.

**REFACTOR**.
- KDoc 에 `password.fill(' ')` wipe contract 명시 (UsernamePassword 동일 패턴)

**검증**. `./gradlew :modules:identity-access:test --tests CredentialLdapBindTest`.

### Task 3. `LdapConfig` + `LockoutPolicy` VO + Jackson 직렬화 (TDD)

**파일**. `src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapConfig.kt` (+ `LockoutPolicy`, `LockoutScope` 같은 파일 또는 분리).

**RED**.
- `LdapConfigJsonTest.kt` — Jackson round-trip + bindPasswordEnv (env var name) 만 저장 + bindPassword 자체 미저장 검증
- `LockoutPolicyDefaultsTest.kt` — `maxAttempts=5, lockoutMinutes=15, scope=PER_USER_PER_PROVIDER` 기본값

**GREEN**.
- spec FR-3 data class 그대로 작성
- Jackson `@JsonProperty` / `@JsonCreator` 필요 시

**REFACTOR**.
- bind password 로딩 함수 `LdapConfig.resolveBindPassword(): String?` 추출. `System.getenv(bindPasswordEnv)` 호출. null 처리.
- KDoc — bind password startup 1회 로딩 + restart 필요 명시 (spec FR-2 보강)

**검증**. `./gradlew :modules:identity-access:test --tests 'LdapConfigJsonTest|LockoutPolicyDefaultsTest'`.

### Task 4. `ExternalAccount` 엔티티 + Repository (TDD, db-engineer 협업)

**파일**. `provider/ldap/ExternalAccount.kt` + `ExternalAccountRepository.kt`.

**RED**.
- `ExternalAccountRepositoryTest.kt` — `@SpringBootTest @Testcontainers` postgres + Flyway V001+V002 적용 + 기본 query 검증.
- 테스트 케이스.
  - `findByProviderIdAndExternalSubject` — 매핑 X 면 `Optional.empty`, 매핑 O 면 entity 반환
  - `provisionUser(...)` — `users` INSERT + `user_external_accounts` INSERT 단일 트랜잭션. 부분 실패 (FK 위반) 시 rollback 검증
  - `incrementFailedAttempts(id)` — 카운터 +1
  - `resetFailedAttempts(id)` — 카운터 0
  - `markLockedUntil(id, until)` — locked_until 갱신
  - `updateLastLoginAt(id, now)` — 갱신
  - 동시성 — `SELECT FOR UPDATE` 잠금 검증 (1 트랜잭션이 locking, 다른 트랜잭션 대기)

**GREEN**.
- `ExternalAccount` data class
- `ExternalAccountRepository` — plain JDBC + `NamedParameterJdbcTemplate` (Spring Data JDBC 의 부하 회피 — 단순 5 query 만 필요)
- `@Repository @Transactional`

**REFACTOR**.
- 트랜잭션 격리 명시 — `@Transactional(propagation = REQUIRED, isolation = READ_COMMITTED)`
- KDoc — `provisionUser` 의 단일 트랜잭션 보장 강조 (DATA.md §6)
- SQL 상수 추출

**검증**. `./gradlew :modules:identity-access:test --tests ExternalAccountRepositoryTest`. Testcontainers postgres cold start 측정.

### Task 5. `LdapProvider` 구현 + Spring Security LDAP 어댑터 (TDD)

**파일**. `src/main/kotlin/com/atlas/bts/identity/provider/ldap/LdapProvider.kt`.

**RED**.
- `LdapProviderUnitTest.kt` — MockK 로 `LdapAuthenticationProvider`, `ExternalAccountRepository`, `AuthnProviderConfigService` (LdapConfig 로더) mocking.
- 7 시나리오 분기 + lazy init 분기 + bind password env var 분기.
- 테스트 케이스 (S-01~S-07 + 추가).
  - S-01 기존 사용자 성공 → Success(Principal)
  - S-02 자동 프로비저닝 → users + user_external_accounts 매핑 INSERT + Success
  - S-03 잘못된 비밀번호 → Failure(INVALID_CREDENTIALS) + failed_attempts +1
  - S-04 사용자 미존재 → Failure(INVALID_CREDENTIALS) — enumeration 방지
  - S-05 LockoutPolicy 적용 → Failure(ACCOUNT_LOCKED) + locked_until 갱신
  - S-06 LDAP 서버 장애 → Failure(PROVIDER_UNAVAILABLE)
  - S-07 그룹 정보 저장 → user_external_accounts.groups JSONB 업데이트
  - lazy init — authn_providers 행 0개 → Failure(PROVIDER_UNAVAILABLE) + 1회 WARN
  - bind password env var 미설정 → Failure(PROVIDER_UNAVAILABLE) + 1회 WARN
  - PII 마스킹 검증 — log 출력에 password / DN 미포함 (로그 캡처)

**GREEN**.
- `LdapProvider` 구현. `AuthenticationProvider` SPI 구현. type=LDAP, supports = Credential.LdapBind
- 내부 흐름. config 로드 → lockout 검사 → Spring LDAP authenticate → 성공 시 자동 프로비저닝 + lockout reset / 실패 시 lockout 카운터 → AuthnResult 반환
- `@Component` 부착 (production 자동 등록). `@Profile` 미부착.

**REFACTOR**.
- 변환 함수 분리 — `private fun LdapUserDetails.toPrincipal(): Principal`, `private fun LdapException.toAuthnFailure(): AuthnResult.Failure`
- 자동 프로비저닝 메서드 분리 — `private suspend fun provisionUser(externalSubject: String, attributes: ...): ExternalAccount` (또는 plain blocking 메서드)
- KDoc 한글 헤더 + spec FR-2 흐름 명시

**검증**. `./gradlew :modules:identity-access:test --tests LdapProviderUnitTest`.

### Task 6. Testcontainers OpenLDAP 통합 테스트 (TDD)

**파일**. `src/test/kotlin/com/atlas/bts/identity/provider/ldap/LdapProviderIntegrationTest.kt` + `infra/ldap/seed.ldif`.

**RED**.
- `@Testcontainers @SpringBootTest @AutoConfigureMockMvc(addFilters=false)` 통합 테스트.
- OpenLDAP 컨테이너 — 이미지 ADR 결정 (osixia/openldap vs bitnami/openldap vs 공식 — impl 단계 결정)
- Postgres 컨테이너 (PoC #2 패턴) + Flyway V001+V002 적용
- `infra/ldap/seed.ldif` — alice + bob + engineers 그룹
- `@DynamicPropertySource` — LDAP host:port + Postgres URL 주입
- `authn_providers` 행 INSERT 픽스처 (LdapConfig JSONB 포함)
- 7 시나리오 모두 통합 검증 (T5 mocked 와 다른 실제 동작)

**GREEN**.
- 위 테스트 통과. T5 의 LdapProvider 가 이미 구현했으므로 GREEN 단계는 통합 환경 검증 + 인프라 설정만.

**REFACTOR**.
- 베이스 클래스 `LdapTestcontainersBase` 추출 (다음 PR — SAML/OIDC 통합 테스트 재사용 가능성)

**검증**. `./gradlew :modules:identity-access:test --tests LdapProviderIntegrationTest` (cold start < 60초).

### Task 7. ADR 4건 작성 + PoC/PR3 회귀 검증 + ProviderRegistryTest 갱신 (인프라/문서, TDD 미적용)

**ADR 작성**.
- `docs/decisions/2026-05-20-ldap-testcontainers-image.md` — OpenLDAP 이미지 선정 (T6 실측 후 작성)
- `docs/decisions/2026-05-20-user-external-accounts-schema.md` — V002 스키마 결정 (ON DELETE 정책 + 인덱스)
- `docs/decisions/2026-05-20-lockout-policy-location.md` — BTS 측 자체 lockout 결정 (vs LDAP 서버 측)
- `docs/decisions/2026-05-20-ldap-group-mapping-policy.md` — 본 PR 은 그룹 DN 저장만, 권한 매핑 FR-PM-01 위임 결정

**PR #3 회귀 검증**.
- `ProviderRegistryTest` — 본 PR LdapProvider 가 `@Component` 자동 등록되면 Registry 의 가짜 Provider 카운트 변경 영향 가능. 검증 + 필요 시 assertion 갱신
- `@Profile("test-spi")` 가짜 Provider vs production LdapProvider 격리 검증

**PoC #2 회귀 검증**.
- `KeycloakIntegrationTest` — V001 의 `users` 테이블 영향 없음 확인 (본 PR V002 만 추가)

**검증**. `./gradlew :modules:identity-access:test` 전체 통과 + `./gradlew ktlintCheck detekt` 통과.

## Plan 메타

- **task 수**. 7 (T1/T7 인프라·DB·문서, T2~T6 TDD)
- **TDD 적용**. T2/T3/T4/T5/T6 (5 task) — RED→GREEN→REFACTOR
- **인프라·DB·문서**. T1/T7 (2 task) — `chore:` / `feat(db):` / `docs:` 커밋
- **예상 커밋 수**. 22~25 (T1 ~2, T2~T6 각 3 = 15, T7 ~5 ADR + 회귀 정리)
- **예상 작업 시간**. 4~5 시간 (V002 마이그레이션 첫 도입 + OpenLDAP Testcontainers 첫 도입 + Spring Security LDAP 학습 + ADR 4건)
- **추가 검증**. ktlintCheck, detekt, ArchUnit (PR #3 룰 유지), Flyway 마이그레이션 idempotent, Testcontainers OpenLDAP + Postgres
- **다중 sub-agent**. db-engineer (T1 V002 + T4 Repository 협업) + security-engineer (primary, T2~T7)
- **게이트 2 직전 수동 검증**. `./gradlew :modules:identity-access:test :modules:identity-access:bootJar` 전체 통과 + ArchUnit 회귀 + PR #3 ProviderRegistryTest 통과
- **writing-plans 우회 사유**. PoC 패턴 일관성. spec 결정 명확.
- **PR 분할 확정 (게이트 1)**. D6 (UI 로그인 폼) + D7 (Playwright E2E) PR #5 ~ #6 위임 — Maxi 확정 필요

## 리뷰 결과 (← /bts-review-plan 채움)
