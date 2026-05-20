<!-- BTS plan — StoredPasswordCredential 엔티티 도입 (PoC #2 미완성 마무리) -->

# StoredPasswordCredential 엔티티 도입 — PoC #2 비밀번호 저장 부분 마무리

> slug: identity-stored-password-credential
> type: auth (security-engineer 책임 + plan-eng + plan-ceo 리뷰)
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 선행 PR. #2 (PoC AuthN) / #3 (FR-AU-01 SPI) / #4 (FR-AU-02 LDAP), 모두 머지됨
> 트리거. PR #4 머지 후 정리 작업 점검 중 phantom 발견 — 다수 ADR/spec/plan 이 `UserCredential` 엔티티를 "PoC #2 도입" 으로 기술했으나 실제 코드에는 `LocalCredentialService` (해시 계산 로직만) 뿐. DB 저장부 미도입

## Brief

PoC #2 가 빠뜨린 비밀번호 영속 저장 계층을 정식 도입.

1. **V003 마이그레이션** — `user_credentials` 테이블 (user_id PK, password_hash, algo_version, updated_at, 인덱스/제약)
2. **`StoredPasswordCredential` JPA 엔티티 + Repository** — 다수 ADR 가 "UserCredential" 로 가설한 이름을 SPI 의 `Credential` 과 헷갈리지 않도록 `StoredPasswordCredential` 로 처음부터 명명
3. **`LocalCredentialService` 통합** — 현재 Argon2 해시 계산만 함. 본 PR 이 해시 저장 + 검증 시 조회 메서드 연결
4. **DEVELOPMENT.md / DATA.md 갱신** — 비밀번호 저장 정책 (해시 알고리즘 / pepper / 키 회전 / 평문 미저장 / 마이그레이션 정책) 명시
5. **회귀 검증** — PoC #2 OIDC 흐름 (Keycloak) 과 충돌 없는지. LDAP 흐름 (해시 저장 안 함) 과 분리 명확

본 PR 은 새로 도입된 `/bts-impl` wave 병렬 dispatch 의 dogfood 대상이지만, auth 타입이라 fast-track 미적용 — `/bts-domain` + `/bts-spec` + `/bts-review-plan` (plan-eng + plan-ceo) 거쳐서 진입.

## 도메인 정리

- **BC**. identity-access (단독)
- **영향 엔티티**.
  - 신규. `StoredPasswordCredential` (JPA @Entity, BTS DB 영속). PoC #2 가 가설한 "UserCredential" 의 실제 도입 + 명명 정정 (SPI `Credential` 과 헷갈리지 않게)
  - 신규 테이블. `user_credentials` (V003). 컬럼 후보. `user_id PK FK → users.id`, `password_hash TEXT NOT NULL`, `algo_version TEXT NOT NULL`, `created_at`, `updated_at`
  - 기존 수정. `LocalCredentialService` — 현재 Argon2 해시 계산만. 본 PR이 해시 저장 + 조회 메서드 추가 + Repository 의존
- **신규 용어 후보** (Maxi 승인 후 glossary 추가).
  - `StoredPasswordCredential` — BTS DB 영속. 한 사용자 × Argon2 해시 + algo_version + updated_at. **vs `Credential` (sealed 입력 VO, 메모리 only)** 명확히 구분
- **기존 결정과의 정합**.
  - ADR `argon2id-parameters` (PoC #2) — 그대로 재사용. memory=64MiB / iterations=3 / parallelism=4. 신규 ADR 불필요 ✅
  - ADR `authentication-provider-spi-naming` — 라인 68-73 의 "UserCredential = PoC #2 도입 엔티티" 가설 정정 필요. 본 PR 이 처음 도입함을 보강 단락 추가 ✏️
  - ADR `user-external-accounts-schema` (PR #4) — `user_external_accounts.user_id → users.id` CASCADE DELETE 패턴 (GDPR). 본 PR `user_credentials` 도 같은 패턴 적용 ✅
  - ADR `csrf-cookie-mode` / `keycloak-image-selection` / `testcontainers-docker-desktop-config` — 영향 없음 ✅
- **Provider 흐름과의 분리** (어떤 Provider 가 해시 저장하는가).
  - **Local** (`LocalCredentialService`) — 본 PR 의 `StoredPasswordCredential` 사용 ✅ 유일
  - **LDAP** (PR #4) — 외부 LDAP 서버 bind 검증, BTS 해시 미저장 ✅
  - **OIDC** (PoC #2, Keycloak) — Keycloak 자체 저장, BTS 해시 미저장 ✅
  - **SAML** (FR-AU-03 미구현) — 외부 IdP, BTS 해시 미저장 ✅
- **신규 ADR 후보** (impl 단계에서 결정 시점에 작성).
  - `2026-05-20-stored-password-credential-schema.md` — V003 스키마 결정 (user_credentials 컬럼/제약/FK CASCADE/algo_version 사용 방식)
  - 옵션. `2026-05-20-password-history-policy.md` — 비밀번호 이력 보존 안 함 (단방향 갱신만). spec 단계에서 결정 후 ADR 작성 여부 판단
- **glossary / domain 노트 갱신 후보** (Phase 0 수동, Maxi 승인 후).
  - `glossary.md` 인증 섹션 — `StoredPasswordCredential` 신규 추가
  - `glossary.md` 인증 섹션 — `Credential`/`Principal`/`AuthnResult`/`ProviderRegistry` 4건도 (PR #3 ADR 가 약속했으나 누적 미반영, 본 PR 묶음 처리 가능)
  - `domain/identity-access.md` 라인 17 — `UserCredential` → `StoredPasswordCredential` 정정. ADR 링크 추가
- **grill-with-docs 우회 사유**. 이전 3개 PR (PoC #2 / FR-AU-01 / FR-AU-02) 모두 직접 분석으로 갈음. 본 PR도 동일 패턴. (1) 도메인 모델 명확 — DB 영속 엔티티 도입 + 기존 service 통합, (2) 신규 용어 1건만 추가, (3) ADR 재사용 충분, (4) 1인 + Auto mode 합리적 판단.

**관련 ADR (영향 받음)**.
- 영향 받는 ADR. `argon2id-parameters` (재사용), `authentication-provider-spi-naming` (정정), `user-external-accounts-schema` (패턴 재사용)
- 본 PR 신규 ADR. 1건 예상 — `stored-password-credential-schema`

## 스펙

전체 스펙. [docs/specs/2026-05-20-identity-stored-password-credential.md](../specs/2026-05-20-identity-stored-password-credential.md)

핵심 5줄.
- V003 마이그레이션. `local_credentials` 테이블 (user_id PK FK → users.id CASCADE, password_hash TEXT, algo_version, timestamps)
- `StoredPasswordCredential` JPA 엔티티 + Repository (UPSERT / find / delete)
- `LocalCredentialService` 신규 3 메서드 — `store` / `verifyForUser` / `rotate`. timing attack 방어 dummy hash + 평문 wipe
- ADR 1건 신규 (`stored-password-credential-schema`) + 1건 정정 (`authentication-provider-spi-naming` phantom 단락)
- 회귀 검증. PoC OIDC + LDAP Provider 흐름 본 테이블 미사용 확인

## Brainstorming Check

✅ 통과 (1회 iteration, 직접 분석).

발견된 gap 2건 스펙 본문 보강.
- timing attack 방어용 dummy hash 상수 명시
- password / hash / userId 로그 출력 금지 NFR 추가

수용 안 한 검토 항목 3건 (algo_version 컬럼 redundancy / rotate race 정밀 방어 / username 변경 시나리오) — 사유 spec 본문 R1~R3 참조.

## Plan

### Task 1. V003 마이그레이션 — `local_credentials` 테이블 (TDD)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V003__local_credentials.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/LocalCredentialsMigrationTest.kt`]
- depends-on: []

**RED**.
- 파일. `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/LocalCredentialsMigrationTest.kt`
- 테스트. Testcontainers Postgres + Flyway 자동 적용 후 `information_schema.columns` 조회로 5 컬럼 (user_id / password_hash / algo_version / created_at / updated_at) 존재 + 타입/NOT NULL/PK/FK CASCADE 검증.
- 실패 메시지 (예상). `Migration V003 not found` 또는 `relation "local_credentials" does not exist`

**GREEN**.
- 파일. `V003__local_credentials.sql`
- spec §5 SQL 그대로 + COMMENT 2건 + algo_version 컬럼

**REFACTOR**. SQL 주석 정리 + Flyway 명명 규칙 확인.

**검증**. `./gradlew :backend:identity-access:test --tests LocalCredentialsMigrationTest`

### Task 2. `StoredPasswordCredential` 데이터 클래스 (TDD)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/StoredPasswordCredential.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/StoredPasswordCredentialTest.kt`]
- depends-on: []

**RED**.
- 파일. `StoredPasswordCredentialTest.kt`
- 테스트 3건. (1) 인스턴스 생성 + 필드 5개 정상 노출, (2) `toString()` 이 `passwordHash=***` 로 마스킹, (3) `equals()`/`hashCode()` 가 userId 기반 (다른 필드 변경 시도 equal)
- 실패 메시지. `unresolved reference: StoredPasswordCredential`

**GREEN**.
- 파일. `StoredPasswordCredential.kt`
- `data class` + custom `toString()` override
- userId 기반 equals/hashCode = data class default override (userId 만 in equals)

**REFACTOR**. KDoc 추가. SPI `Credential` 과의 구분 인라인 명시.

**검증**. `./gradlew :backend:identity-access:test --tests StoredPasswordCredentialTest`

### Task 3. `Argon2Params.DUMMY_HASH` 상수 (TDD)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/Argon2Params.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/Argon2ParamsTest.kt`]
- depends-on: []

**RED**.
- 파일. `Argon2ParamsTest.kt`
- 테스트 2건. (1) `DUMMY_HASH` 가 `$argon2id$` prefix 로 시작, (2) `Argon2.verify("not-real-password".toCharArray(), DUMMY_HASH) == false` (timing 일정 verify 호출 + false 결과)
- 실패 메시지. `unresolved reference: DUMMY_HASH`

**GREEN**.
- 파일. `Argon2Params.kt` (기존, 본 PR 신규 상수 추가)
- companion `DUMMY_HASH` 선언 — JVM static init 시 `Argon2.hash(빈 비밀번호)` 결과 사전 인코딩 (또는 hardcoded encoded string)

**REFACTOR**. 주석 — "verifyForUser 가 row 없는 경우 timing attack 방어 목적. 사용 시 절대 평문 비교 안 함" 명시.

**검증**. `./gradlew :backend:identity-access:test --tests Argon2ParamsTest`

### Task 4. ADR 정정 — `authentication-provider-spi-naming` phantom 단락 (문서, TDD 미적용)

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-05-20-authentication-provider-spi-naming.md`]
- depends-on: []

**작업**. 기존 ADR 끝에 "2026-05-20 정정 (PR #6/7 후속)" 단락 추가. 라인 68-73 의 "UserCredential = PoC #2 도입 엔티티" 가설이 phantom 이었음을 명시. 본 PR 이 `StoredPasswordCredential` 이름으로 처음 도입했음을 인용.

**검증**. `grep -n "정정" docs/decisions/2026-05-20-authentication-provider-spi-naming.md` 로 단락 추가 확인.

### Task 5. `StoredPasswordCredentialRepository` (TDD, Testcontainers Postgres)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/StoredPasswordCredentialRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/StoredPasswordCredentialRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**.
- 파일. `StoredPasswordCredentialRepositoryTest.kt`
- Testcontainers Postgres (PR #4 패턴 재사용 — `KeycloakIntegrationBase` 와 별개 Postgres 베이스 또는 동일 컨테이너 공유)
- 테스트 6건. (1) save 신규 INSERT, (2) save UPSERT (같은 user_id 두 번), (3) findByUserId 존재, (4) findByUserId 없음 → null, (5) deleteByUserId, (6) users CASCADE 삭제 시 자동 정리
- 실패 메시지. `unresolved reference: StoredPasswordCredentialRepository`

**GREEN**.
- 파일. `StoredPasswordCredentialRepository.kt`
- `NamedParameterJdbcTemplate` 기반. PR #4 `ExternalAccountRepository` 패턴 재사용. **본 PR이 INSERT ... RETURNING + getObject(UUID) 패턴 처음 적용** (PR #4 SAVE-2/3 부채 본 Repository 에 선반영, ExternalAccountRepository 자체 refactor 는 별도 chore PR)
- SQL_UPSERT. `INSERT INTO local_credentials (...) VALUES (...) ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash, algo_version = EXCLUDED.algo_version, updated_at = now() RETURNING *`

**REFACTOR**. SQL 상수 추출 + RowMapper 분리.

**검증**. `./gradlew :backend:identity-access:test --tests StoredPasswordCredentialRepositoryTest`

### Task 6. `LocalCredentialService` 신규 메서드 — `store` / `verifyForUser` / `rotate` (TDD)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/LocalCredentialService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/LocalCredentialServiceTest.kt`]
- depends-on: [3, 5]

**RED**.
- 파일. `LocalCredentialServiceTest.kt` (기존, 본 PR 새 테스트 추가)
- 테스트 9건. store(정상/같은user 갱신) / verifyForUser(정상/실패/row없음 dummy verify timing) / rotate(정상/old 불일치/new = old)
- Repository mock (mockk) 사용. timing 검증은 ms 비교 안 함 (불안정), dummy verify 호출 횟수만 검증
- 실패 메시지. `LocalCredentialService 에 store/verifyForUser/rotate 메서드 없음`

**GREEN**.
- 파일. `LocalCredentialService.kt`
- 의존성. `StoredPasswordCredentialRepository`, `Argon2Params.DUMMY_HASH`, `Clock`
- 3 메서드 구현. plain CharArray 는 finally 블록 wipe. 로그는 boolean + ms latency 만 (NFR 로그 정책)

**REFACTOR**. KDoc — Contract 명시 (예외 throw 안 함, dummy verify 보장 등). 로그 정책 주석.

**검증**. `./gradlew :backend:identity-access:test --tests LocalCredentialServiceTest`

### Task 7. 신규 ADR — `stored-password-credential-schema` (문서, TDD 미적용)

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-05-20-stored-password-credential-schema.md`]
- depends-on: [1, 5]

**작업**. V003 스키마 + Repository 구현 결정 사항 ADR.

섹션. 컨텍스트 (phantom 발견 + PoC #2 spec 마저 완성) / 선택지 (테이블명 local_credentials vs user_credentials, algo_version 컬럼 vs prefix-only, UPSERT vs INSERT+UPDATE 분리) / 결정 (local_credentials + algo_version 보존 + UPSERT 단일 SQL) / 영향 (V003 적용 + LocalCredentialService 통합) / 관련 ADR 링크 (`argon2id-parameters` 재사용).

**검증**. ADR 파일 존재 + plan 의 도메인 정리 섹션과 일관.

## Plan 메타

- task 수: 7
- 예상 시간: task × 4분 = 약 28분 (직렬 기준). 병렬 wave 적용 시 약 16분 (3 wave)
- TDD 강제: yes (T4 / T7 문서 task 제외)
- 병렬 dispatch: 본 PR 이 새 도입된 wave 계산 첫 dogfood
- 추가 검증: ktlint, detekt, Testcontainers Postgres (PR #4 회귀 검증 포함)

### Wave 계산 (bts-impl 이 자동 계산하나 미리 명시)

- **Wave 1** = [T1, T2, T3, T4] — 의존성 없음, 파일 겹침 없음. **4 task 병렬 dispatch**
  - db-engineer (T1) + security-engineer 3건 (T2, T3, T4) 동시 발행
- **Wave 2** = [T5] — T1 (테이블) + T2 (엔티티) 완료 후
- **Wave 3** = [T6, T7] — T5 (Repository) 완료 후. T6 추가로 T3 필요 (wave 1 졸업), T7 추가로 T1 필요 (wave 1 졸업)
  - T6 + T7 병렬 dispatch (security-engineer 2건 동시)

직렬 7 단계 → 3 wave. 약 57% 시간 단축 예상.

## 리뷰 결과 (← /bts-review-plan 채움)

(아직 비어 있음)

## phantom 발견 컨텍스트 (Learnings 후보)

**무엇이 잘못됐나**. 다수 문서 (`docs/decisions/2026-05-20-authentication-provider-spi-naming.md`, `docs/specs/2026-05-20-identity-authn-provider.md`, `docs/plans/2026-05-20-identity-*.md`) 가 `UserCredential` 을 "PoC #2 도입 엔티티" 로 기재. 실제 코드는 service 만 있고 entity 없음.

**근본 원인**. PoC #2 plan 이 "활용 엔티티 (기존, 신설 없음)" 라 표기 — implementer 가 신규 도입 작업 아니라고 해석. 그러나 PoC 이전 상태 = repo 비어 있음 (Phase 0 PoC 진입 직전). 따라서 "기존" 은 사실상 거짓. 후속 PR (#3 / #4) 의 ADR 이 phantom 을 "있는 것"으로 가정하고 작성.

**예방**. plan 의 "활용 엔티티" 항목은 `grep -rn "class <Name>"` 로 실재 검증. ADR / spec 작성 시 참조 코드 경로 (`backend/modules/.../<File>.kt:<line>`) 명시.

(merge 시 `Maxi_wiki/BTS/learnings.md` 에 정식 등록 예정)
