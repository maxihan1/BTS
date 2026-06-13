# FR-MF-05 — 신뢰 디바이스 (30일 MFA 면제) 백엔드 슬라이스 (D1~D5)

> slug: fr-mf-05-trusted-devices
> type: auth
> agent: security-engineer
> 생성: 2026-06-13

## Brief

FR-MF-05 신뢰 디바이스 — 사용자가 MFA를 통과한 디바이스를 "신뢰"로 등록하면 30일 동안 해당 디바이스에서 MFA 재요구를 면제. 본 PR은 백엔드 슬라이스(D1~D5):
- D1. 도메인 — TrustedDevice
- D2. 명세 — 사용자 동의 + 30일 TTL + 취소
- D3. 데이터 모델 — `trusted_devices(device_fingerprint, expires_at)`
- D4. 백엔드 — fingerprint 발급 + MFA 우회 검증
- D5. 백엔드 테스트 — TTL 만료 + 명시적 취소

프론트 UI(D6) / E2E(D7)는 후속 PR로 분리 (FR-MF-01~04 동일 분할).

classify: type=auth, agent=security-engineer, primary_bc=identity-access

## 도메인 정리

- **BC**: identity-access (단일, cross-BC 없음)
- **영향 엔티티**: `TrustedDevice` (신규), `AuthController`(login/verifyMfa 결선), `MfaService.disable`/비밀번호 변경 서비스(자동폐기 훅)
- **신규 용어**:
  - **신뢰 디바이스(Trusted Device)** — 사용자가 MFA 통과 후 "30일 면제"에 동의한 디바이스. 서버 불투명 토큰으로 식별.
  - **신뢰 토큰(trusted device token)** — MFA verify 성공 시 서버가 발급하는 32바이트 난수. HttpOnly 쿠키로 클라이언트 보관, DB는 `SHA-256` 해시(`token_hash`)만 저장.
- **핵심 결정**(ADR `docs/decisions/2026-06-13-trusted-device-mfa-exemption.md`):
  - D1. 식별 = 서버 불투명 토큰(HttpOnly 쿠키), DB 해시만 (클라이언트 핑거프린트 기각 — 위조/충돌/프라이버시)
  - D2. `trusted_devices` V026 (init_codegen 미러 불요 — jdbc-only 모듈)
  - D3. 통합 = `completeLogin`(쿠키→우회, mfaVerified=true) + `verifyMfa`(trustDevice opt-in→등록+Set-Cookie)
  - D4. 고정 30일 만료(sliding 아님)
  - D5. 취소 = 수동(단건/전체) + 자동(비번변경·TOTP비활성)
  - D6. JWT 클레임 미도입 (SDD 스케치 일탈 — 로그인 시점 부재, mfa_verified 재사용)
  - D7. PR 범위 = 백엔드 D1~D5 먼저
- **명명 일탈**: SDD/product 스케치 `device_fingerprint` → 실제 `token_hash`(기존 `Session.deviceFingerprint`=약한 UA+IP 핑거프린트와 혼동 회피). product D3 인라인 메모로 동기화(머지 시).
- **기존 결정 충돌**: 없음. FR-MF-04(MFA 강제)와 직교 — 신뢰 우회는 MFA 활성이 전제라 enrollment 게이트 통과, 우회 세션도 mfaVerified=true.
- **관련 ADR**: docs/decisions/2026-06-13-trusted-device-mfa-exemption.md (생성됨), 선행 2026-06-12-mfa-enforcement-policy.md
- **glossary 갱신 대기**: "신뢰 디바이스", "신뢰 토큰" (§인증 — Maxi 승인 필요, 자동 갱신 안 함)

## 스펙

전체 스펙. docs/specs/2026-06-13-fr-mf-05-trusted-devices.md

핵심 시나리오 요약.
- MFA verify 성공 + `trust_device=true` → 서버 불투명 토큰 발급(HttpOnly 쿠키) + `trusted_devices` 행 INSERT(SHA-256 해시, 30일 만료).
- 다음 로그인 시 쿠키 유효(user 일치+미만료)면 챌린지 생략하고 정식 세션(mfa_verified=true), `last_used_at` 갱신.
- 취소 = 수동(단건 `DELETE /mfa/trusted-devices/{id}`·전체 `DELETE /mfa/trusted-devices`, 목록 `GET`) + 자동(비번변경·TOTP비활성 전량 revoke).
- 감사 로그 `TRUSTED_DEVICE_ADDED`/`TRUSTED_DEVICE_REVOKED` 2종(MFA self-service 일관).

엔드포인트. login/verify 수정 + trusted-devices GET/DELETE(단건)/DELETE(전체) 3 신규(JWT 전용·PAT 403).

## Brainstorming Check

✅ 통과 (1회 iteration). 감사 로그 누락 발견→FR-11 보강, Clock 출처 명시, user-bound 우회 차단·fail-safe 폴백 명시. 상세는 spec 파일 §Brainstorming Check.

## Plan

> 전 task agent 기본값: `security-engineer` (identity-access 인증 영역). 경로는 repo 루트 기준.
> 공통 패키지: `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/`, 테스트는 `.../src/test/kotlin/com/atlas/bts/identity/`.

### Task 1. V026 `trusted_devices` 마이그레이션 + 스키마 검증 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V026__trusted_devices.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/TrustedDeviceMigrationTest.kt`]
- depends-on: []

**RED**: `TrustedDeviceMigrationTest`(Testcontainers) — `information_schema`로 `trusted_devices` 테이블 + 컬럼(`id,user_id,token_hash,label,created_at,expires_at,last_used_at`) + `token_hash` UNIQUE + `idx_trusted_devices_user` 존재 단언. 마이그레이션 부재로 실패.

**GREEN**: V026 작성 (spec §데이터 모델 그대로). `user_id` FK `users(id) ON DELETE CASCADE`, `token_hash TEXT NOT NULL UNIQUE`, `expires_at TIMESTAMPTZ NOT NULL`, `label`/`last_used_at` nullable, `idx_trusted_devices_user(user_id)`. **init_codegen 미러 불요**(jdbc-only).

**REFACTOR**: 컬럼 주석(SQL `--`) + 마이그레이션 L1 역할 주석.

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*TrustedDeviceMigrationTest'`

### Task 2. TrustedDevice 도메인 + 토큰 생성/해시 + 만료 판정

**메타**.
- agent: `security-engineer`
- files: [`.../mfa/TrustedDevice.kt`, `.../mfa/TrustedDeviceToken.kt`, `src/test/.../mfa/TrustedDeviceTest.kt`, `src/test/.../mfa/TrustedDeviceTokenTest.kt`]
- depends-on: []

**RED**: 단위 테스트 —
- `TrustedDeviceToken.generate()` → rawToken(**hex 64자 = 32바이트**, RefreshToken `generateRawToken` 선례와 동일 인코딩 — base64url 혼용 금지) + `hash`(SHA-256 hex 64자). 동일 rawToken 재해시 동일값, 서로 다른 토큰 다른 해시.
- `TrustedDevice.isExpired(now)` — `expires_at == now`는 만료(`> now`만 유효, EC10 경계).

**GREEN**: `TrustedDevice` 데이터 클래스(id/userId/tokenHash/label/createdAt/expiresAt/lastUsedAt + `isExpired`). `TrustedDeviceToken`(난수 생성 `SecureRandom` + `sha256Hex`). rawToken 비로깅.

**REFACTOR**: 상수(토큰 바이트수·HASH_LENGTH) 추출 + KDoc(비밀값 미저장 §1.1.1).

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*TrustedDeviceTest' --tests '*TrustedDeviceTokenTest'`

### Task 3. TrustedDeviceRepository (JDBC) + 통합 테스트

**메타**.
- agent: `security-engineer`
- files: [`.../mfa/TrustedDeviceRepository.kt`, `.../mfa/JdbcTrustedDeviceRepository.kt`, `src/test/.../mfa/JdbcTrustedDeviceRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: Testcontainers 통합 테스트 — `insert`, `findByTokenHash`(존재/부재), `updateLastUsedAt`, `listByUser`(미만료만 — 만료행 제외), `deleteByIdAndUser`(소유/타인 0행), `deleteAllByUser`(반환 count). seed로 users 행 사전 INSERT(FK 충족).

**GREEN**: 포트 인터페이스 + JDBC 구현(`NamedParameterJdbcTemplate`, 기존 `Jdbc*Repository` 선례). 조회는 `expires_at > :now` 술어(만료 제외, Clock는 호출측 전달).

**REFACTOR**: SQL 상수화 + RowMapper 분리.

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*JdbcTrustedDeviceRepositoryTest'`

### Task 4. TrustedDeviceService (Clock 주입·감사 emit) + AuthEventType 2종 + 카운트가드 갱신

**메타**.
- agent: `security-engineer`
- files: [`.../mfa/TrustedDeviceService.kt`, `.../audit/AuthEventType.kt`, `src/test/.../mfa/TrustedDeviceServiceTest.kt`, `src/test/.../audit/AuthAuditLogServiceTest.kt`, `src/test/.../audit/AuthEventEmitCoverageTest.kt`]
- depends-on: [2, 3]

**RED**: 서비스 테스트(MockK repo + 주입 Clock + 감사) —
- `trust(userId, userAgent)` → rawToken 반환 + repo.insert(hash, `expiresAt=clock.now()+30일`, `createdAt=clock.now()`, label=userAgent) + `TRUSTED_DEVICE_ADDED` emit.
- `verifyAndTouch(userId, rawToken)` → 유효(user 일치+미만료) true + `updateLastUsedAt`; 만료/타인user/미상 false(touch 없음).
- `list(userId)`, `revoke(userId,id)`(소유 true/타인 false), `revokeAll(userId)`(count>0이면 `TRUSTED_DEVICE_REVOKED` emit, 0이면 미emit).
- `AuthAuditLogServiceTest` 기대 enum 집합에 2종 추가, `AuthEventEmitCoverageTest`가 2종 emit 사이트 인식.

**GREEN**: `@Service` `TrustedDeviceService`(repo+Clock+AuthAuditLogService 주입). `AuthEventType`에 `TRUSTED_DEVICE_ADDED`/`TRUSTED_DEVICE_REVOKED` 추가. **`TRUSTED_DEVICE_ADDED`는 `trust()`에서, `TRUSTED_DEVICE_REVOKED`는 `revokeAll()`/수동 revoke 경로에서 main 소스에 실제 record 호출**(AuthEventEmitCoverageTest 자동 통과 조건). 감사 metadata엔 count만(비밀값 X).
**카운트 주석/단언 전수 갱신** (현재 "20종" → "22종"):
  - `AuthEventType.kt` L1 주석 + L6 KDoc "20종"
  - `AuthAuditLogServiceTest.kt` L1/L19 주석 · 테스트 제목 "20종" · 명시 expected 집합(L23~47에 2종 추가)
  - `AuthEventEmitCoverageTest.kt` L1/L14 주석(현재 stale "18종" → 정확한 카운트로 정정, drift 해소)
  - 전 모듈 grep 확인: `grep -rn "AuthEventType.entries\|AuthEventType.values\|[0-9]\+종" backend/` (enum-add-breaks-crossmodule-count-guard).

**REFACTOR**: 30일 상수(`TRUST_TTL_DAYS=30`) + KDoc(fail-safe·user-bound).

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*TrustedDeviceServiceTest' --tests '*AuthAuditLogServiceTest' --tests '*AuthEventEmitCoverageTest'`

### Task 5. TrustedDeviceController — 목록/취소 엔드포인트 (JWT 전용)

**메타**.
- agent: `security-engineer`
- files: [`.../web/TrustedDeviceController.kt`, `src/test/.../web/TrustedDeviceControllerTest.kt`]
- depends-on: [4]

**RED**: web 테스트 — **`@WebMvcTest` 슬라이스 + Mockito `@MockBean TrustedDeviceService`**(MfaControllerTest/AuthControllerTest 선례 — companion object 보유 Bean MockK 등록 시 ByteBuddy 문제 회피 KDoc 일관). MockK 직접 생성 금지.
- `GET /api/v1/auth/mfa/trusted-devices` → 200 `{devices:[{id,label,createdAt,lastUsedAt,expiresAt}]}`(token_hash 비노출), PAT(jwt null) 403 `session_management_requires_interactive_login`.
- `DELETE /…/{id}` → 204(소유), 404(타인/미존재 IDOR), PAT 403.
- `DELETE /…` → 204(전체), PAT 403.

**GREEN**: 별도 `TrustedDeviceController`(`@RequestMapping("/api/v1/auth/mfa/trusted-devices")`). `userIdOrNull(jwt)?:PAT_FORBIDDEN`(MfaController 선례). 도메인 결과 직접 ResponseEntity 매핑(catch-all 변질 회귀 가드). 응답 DTO(비밀값 제외).

**REFACTOR**: 응답 DTO KDoc + PAT 상수 재사용.

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*TrustedDeviceControllerTest'`

### Task 6. AuthController 결선 — completeLogin 우회 + verifyMfa trust_device opt-in + 쿠키

**메타**.
- agent: `security-engineer`
- files: [`.../web/AuthController.kt`, `src/test/.../web/AuthControllerTest.kt`]
- depends-on: [4]

**RED**: AuthController 테스트 — **`@WebMvcTest` 슬라이스라 Mockito `@MockBean TrustedDeviceService` 추가**(MockK 금지 — AuthControllerTest.kt L101 KDoc 회피 사유). 기존 `@MockBean` 목록에 1종 추가.
- `completeLogin`: MFA 활성 + 유효 `trusted_device` 쿠키(`verifyAndTouch` true stub) → 챌린지 미발급, `issueTokens(mfaVerified=true)` + TokenResponse 200. 쿠키 무효/부재(false stub) → 기존 `mfa_required`(회귀 0).
- `verifyMfa`: `trust_device=true` + 성공 → 응답에 `Set-Cookie trusted_device`(refresh 쿠키 병존) + `trustedDeviceService.trust` 1회 호출. `trust_device` 생략/false → trust 미호출·쿠키 없음(기존 동작 회귀 0).

**GREEN** (B1 — 시그니처 전파 명시):
1. `MfaVerifyRequest`에 `@JsonProperty("trust_device") trustDevice: Boolean = false` 추가(기본 false=회귀 0).
2. `issueTokens`에 파라미터 추가: `issueTokens(request, userId, providerId, mfaVerified=false, trustedDeviceCookie: String? = null)`. 본문에서 `trustedDeviceCookie != null`이면 `.header(SET_COOKIE, it)` 추가(refresh Set-Cookie와 복수 헤더 병존).
3. **3개 매퍼 시그니처에 `body`(또는 미리 계산한 쿠키) 전파**: `mapTotpResult`/`mapBackupResult`/`mapWebauthnResult`가 성공 시 — `body.trustDevice`면 `trustedDeviceService.trust(userId, request UA)`로 rawToken 받아 `buildTrustedDeviceCookie(rawToken)` 생성해 `issueTokens(...,trustedDeviceCookie=cookie)`. `verifyByMethod`도 `body` 전파 유지.
4. `completeLogin`: `request.cookies`에서 `trusted_device` 읽어 `verifyAndTouch(principal.userId, raw)` true면 `issueTokens(...,mfaVerified=true)`(우회), 아니면 기존 챌린지.
5. `buildTrustedDeviceCookie`(refresh `buildRefreshCookie` 선례 — `Path=/api/v1/auth;HttpOnly;Secure;SameSite=Strict;Max-Age=2592000`). `TrustedDeviceService` 생성자 주입.
6. **C3 — trust 등록 best-effort**: `trustedDeviceService.trust` 실패(예: token_hash UNIQUE 극저확률 충돌)가 정식 세션 발급을 막지 않도록 — 실패 시 쿠키만 누락하고 세션은 발급(로그인 가용성 우선, fail-safe). KDoc 명시.
7. **B3 경계**: 우회는 `completeLogin`(local/LDAP password 경로) 전용. SSO success handler는 본 task 비대상(건드리지 않음).

**REFACTOR**: 쿠키 상수(`TRUSTED_DEVICE_COOKIE`, `TRUST_MAX_AGE=2592000`) + KDoc(우회 user-bound·fail-safe·best-effort 등록·SSO 비대상).

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*AuthControllerTest'`

### Task 7. 자동폐기 훅 — 비밀번호 변경 + TOTP 비활성화 시 전량 revoke

**메타**.
- agent: `security-engineer`
- files: [`.../credential/ChangePasswordService.kt`, `.../mfa/MfaService.kt`, `src/test/.../credential/ChangePasswordServiceTest.kt`, `src/test/.../mfa/MfaServiceTest.kt`]
- depends-on: [4]

**RED**: 서비스 테스트(MockK TrustedDeviceService — 두 서비스 모두 생성자 직접 생성 패턴) —
- `ChangePasswordService.change` Success → `trustedDeviceService.revokeAll(userId)` 1회. **실패 3종 전부 미호출**: `PolicyViolation`/`CurrentMismatch`/**`SameAsCurrent`**(ChangePasswordResult 4종 — rotate 미발생 케이스 누락 금지).
- `MfaService.disable` Success → `revokeAll(userId)` 1회. 실패(InvalidCode/NotEnabled/TooManyAttempts) → 미호출.

**GREEN**: 두 서비스에 `TrustedDeviceService` 생성자 주입 + **Success 분기에만** `revokeAll` 호출 (`ChangePasswordService.change` Success 직전 L108 지점, `LocalCredentialService.rotate`가 아님 — ADR D5 정정 반영). **생성자 주입 fanout** — `grep -rn "MfaService(\|ChangePasswordService(" backend/modules/identity-access/src/test/`로 모든 인스턴스화(real/MockK) 갱신(plan-files-constructor-injection-existing-tests). 통합 테스트 부팅 빈도 확인.

**REFACTOR**: 호출부 KDoc(보안 이벤트 자동 폐기 사유 + ADR D5 링크).

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*ChangePasswordServiceTest' --tests '*MfaServiceTest'`

### Task 8. prod Testcontainers 통합 시나리오 (ground-truth)

**메타**.
- agent: `security-engineer`
- files: [`src/test/.../integration/TrustedDeviceFlowIntegrationTest.kt`]
- depends-on: [5, 6, 7]

**RED**: prod 프로파일 통합 테스트(identity-access prod+RANDOM_PORT 부팅 레시피, 주입 Clock) — spec §완료 기준 전수:
- S1→S2: verify(trust_device=true)로 받은 쿠키로 다음 login → 챌린지 없이 200 TokenResponse + `mfa_verified=true`.
- S3: Clock +31일 → 쿠키 login 시 `mfa_required`.
- S4: 단건/전체 취소 후 쿠키 login → `mfa_required`.
- EC2: 타인 user 쿠키 → 우회 불가.
- S5: 비번 변경 → 목록 empty + 우회 불가.
- S6: TOTP disable → 우회 불가.
- FR-7: 타인 디바이스 단건 취소 → 404.
- PAT: 목록/취소 403.

**GREEN**: 5/6/7 결선이 완료라 대부분 통과. 갭 발견 시 해당 task 코드 정정(test는 본 task에서 불변 유지).

**REFACTOR**: 헬퍼(쿠키 추출·trust 등록) 추출 + Clock 주입 패턴 KDoc.

**검증**: `./gradlew :backend:modules:identity-access:test --tests '*TrustedDeviceFlowIntegrationTest'` → 이어서 모듈 전체 `:backend:modules:identity-access:test --rerun-tasks` + `ktlintCheck detekt`.

## Plan 메타

- task 수: 8
- 예상 wave: 5 (W1: T1·T2 / W2: T3 / W3: T4 / W4: T5·T6·T7 / W5: T8). 단일 모듈이라 test 컴파일 직렬화 요인 있음(bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (각 task RED→GREEN→REFACTOR, `test:` 커밋 선행)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산
- 추가 검증: 모듈 전체 test(--rerun-tasks, false-green 회피) + ktlint + detekt. 프론트/E2E 없음(D6/D7 후속)
- 주의: ① enum 2종 추가 카운트가드 동반(T4) ② 생성자 주입 fanout(T6/T7) ③ ktlint/detekt baseline 라인시프트(import/주석 추가 시 블록만 교체) ④ prod 통합테스트 non-prod AlwaysAllow 마스킹 주의 — ground-truth는 prod 프로파일

## 리뷰 결과

### 독립 eng+보안 리뷰 (security-engineer, 2026-06-13)

autoplan 대신 독립 리뷰어가 실제 코드 grep/read로 통합 지점 검증(bts-review-plan-autoplan-overkill). **종합: plan 수정 후 진행** → 아래 전부 반영 완료.

**BLOCKER (3건, 모두 해소)**.
- B1 — `issueTokens`가 추가 Set-Cookie/trustDevice 전파 구조 미정의(mapper 4종 시그니처). → T6 GREEN에 `issueTokens(...,trustedDeviceCookie)` 파라미터 + 3 매퍼 `body` 전파 명시.
- B2 — `AuthControllerTest`/web 테스트는 `@WebMvcTest`+Mockito `@MockBean` 패턴(MockK 회피 KDoc)인데 plan이 "MockK"라 가짜 RED 위험. → T5/T6 RED를 `@MockBean`으로 정정(서비스 테스트 T4/T7만 MockK).
- B3 — SSO(SAML/OIDC) success handler는 `completeLogin` 미경유 → 신뢰 우회 password 전용 경계 미명문. → ADR D3 + spec NFR-보안-6 + EC13에 "SSO 비대상" 명문화.

**CONCERN (해소/기록)**.
- C1 — `ChangePasswordResult` 4종 중 `SameAsCurrent` 누락. → T7 RED에 실패 3종(SameAsCurrent 포함) 미호출 명시 + ADR D5 훅 위치를 `ChangePasswordService.change` Success로 정정.
- C2 — enum "20종" 주석 전수 갱신 대상(AuthEventType.kt L1/L6, AuthAuditLogServiceTest, AuthEventEmitCoverageTest stale "18종"). → T4 GREEN에 전수 명시.
- C3 — token_hash UNIQUE 충돌 시 trust 등록 실패가 세션 발급 막으면 안 됨. → T6 best-effort 명시 + spec EC14 + ADR.
- C4 — 로그아웃 시 신뢰 쿠키 비해제(의도). → ADR 위험/후속 + spec EC12 명문화.
- C5 — 쿠키 Path=/api/v1/auth는 refresh 선례 동일 최선(변경 불요, 기록).

**NIT**. T2 토큰 인코딩 hex 64자로 통일(base64url 혼용 제거, RefreshToken 선례) → T2 정정. mfa_verified 우회/정상 구분 불가는 의도(동일 세션 효과).

**보안 정합 판정**. fail-safe·user-bound 조회·SHA-256 해시 저장·IDOR 404·PAT 403 견고. depends-on 순환 없음.
