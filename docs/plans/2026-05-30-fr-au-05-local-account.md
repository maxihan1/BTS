<!-- BTS plan — identity-access FR-AU-05 로컬 계정 관리자 가입 + 비밀번호 변경 (백엔드 PR-1) -->

# FR-AU-05 로컬 계정(외부 협력사용) — 관리자 가입 + 비밀번호 변경 (백엔드 PR-1)

> slug: fr-au-05-local-account
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-30
> 마스터플랜: `docs/plan/product/identity-access.md §2.5`

## Brief

FR-AU-05 로컬 계정의 남은 작업 중 **백엔드 (D4 API + D5 정책 테스트)**. 기존 인프라
(LocalCredentialService hash/store/verify/rotate, LocalProvider, V003 local_credentials
— PR #6/#8)는 완성된 production 코드로 재사용. D1(LocalCredential VO)/D2(비밀번호 정책 명세)/
D3(local_credentials 테이블)은 완료 상태.

**본 PR (PR-1) 스코프 = 백엔드만**. 프론트 UI(D6) + E2E(D7)는 후속 PR (PR-2).

## 도메인 정리

- **BC**: identity-access (단독, cross-BC 없음)

- **확정 스코프 (Maxi 결정 2026-05-30)**:
  - **가입 = 관리자 생성 + 임시 비밀번호 모델**. 관리자가 User row + 임시 비밀번호를 생성하고,
    해당 사용자는 첫 로그인 후 비밀번호 변경을 강제받음. self-signup / 초대 토큰 아님.
    (근거: "외부 협력사용" 소수 계정 + 이메일 발송 인프라 부재 → 관리자 통제형이 단순·안전)
  - **리셋(분실 재설정) = 본 PR 제외, 후속 FR**. 이메일 토큰 리셋은 이메일 인프라 도입 후.
    분실 시엔 당분간 관리자 가입 모델로 임시 비번 재발급으로 커버. 본 PR은 **인증된 사용자의
    비밀번호 변경(현재 비번 → 새 비번)만**.
  - **스코프 분할 = 본 PR = 백엔드 only (D4 API + D5 정책 테스트)**. 프론트 UI(D6) + E2E(D7)는
    후속 PR-2 (FR-WF-01 옵션 B 분리 패턴과 일관).

- **영향 엔티티**:
  - `User` (기존, V001 — username UNIQUE / email nullable / display_name) — 관리자 가입 시 row 생성
  - `StoredPasswordCredential` (기존, V003) — store/verifyForUser/rotate 재사용.
    "비밀번호 변경 강제" 상태 저장 위치는 spec 에서 결정 (credential 컬럼 vs users 컬럼)
  - `PasswordPolicy` (신규 VO) — 가입/변경 시 비밀번호 규칙(최소 길이/복잡도) 검증

- **신규 도메인 개념 (glossary 등재 후보, Maxi 승인 대기)**:
  - **비밀번호 정책 (PasswordPolicy)** — 가입/변경 시 비밀번호가 만족해야 할 규칙 집합
    (최소 길이, 복잡도 — 대소문자/숫자/특수문자). 위반 시 거부.
  - **임시 비밀번호 (Temporary Password)** — 관리자가 가입 시 발급. 첫 로그인 후 변경 강제 대상.
  - **비밀번호 변경 강제 (mustChangePassword)** — 임시 비번 사용자가 변경 전까지 유지되는 상태.

- **기존 결정 충돌**: 없음. 기존 store/verifyForUser/rotate 인프라 위에 정책 검증 + 관리자
  가입 + 변경 API 를 얹는 구조. 기존 코드 시그니처 변경 최소화.

- **관련 ADR (기존, 재사용)**:
  - [docs/decisions/2026-05-20-stored-password-credential-schema.md](../decisions/2026-05-20-stored-password-credential-schema.md) — V003 스키마
  - [docs/decisions/2026-05-20-argon2id-parameters.md](../decisions/2026-05-20-argon2id-parameters.md) — Argon2id 파라미터

- **신규 ADR 후보 (spec 단계 구체화 후 작성)**:
  - `password-policy-rules` — 최소 길이/복잡도 구체 규칙 (NIST 800-63B / OWASP 참조)
  - `must-change-password-storage` — 변경 강제 플래그 저장 위치 + 마이그레이션 여부

- **인프라 제약 확인 (2026-05-30 grep)**: `JavaMailSender`/SMTP 0건, notification BC 미착수.
  → 이메일/토큰 기반 가입·리셋 흐름을 후속 위임한 근거.

## 스펙

전체 스펙. [docs/specs/2026-05-30-fr-au-05-local-account.md](../specs/2026-05-30-fr-au-05-local-account.md)

핵심 3줄.
- `POST /api/v1/users/me/password` — 인증된 로컬 사용자가 현재 비번 + 정책 만족 새 비번으로 변경 (기존 `LocalCredentialService.rotate` 재사용)
- `PasswordPolicy` 검증 — 최소 12자 + 영문 대소문자/숫자/특수문자 중 3종 (SDD 19장). 위반 규칙 열거 응답
- 변경 성공 시 현재 세션(JWT `sid`) 제외 다른 세션 무효화 (FR-AU-09 재사용). **데이터 모델 변경 0건** (마이그레이션 없음)

## Brainstorming Check

✅ 통과 (직접 1회 sanity check, FR-AU-01 패턴). 실질 gap 1건(비번 변경 후 세션 무효화 미정의) →
Maxi 결정(다른 세션 무효화 + 현재 유지)으로 FR-6/S7/EC-8 보강. minor 5건(CSRF/검증순서/200본문/
rate limit/LDAP 응답)은 spec·impl에서 닫음.

## Plan

직접 분해 (`superpowers:writing-plans` 대화형 우회 — FR-AU-01 패턴 일관성 + spec 결정 명확 + 1인 부담).
spec FR-1~FR-6 + 엣지케이스 EC-1~8 + 완료기준을 **4 task** 로 분해 (plan 리뷰 반영 — 세션 무효화는
신규 메서드 없이 기존 `findActiveByUser`+`revoke`+`revokeChainFromSession` 조합으로 ChangePasswordService
에 흡수). 전 task identity-access 모듈 (같은 Gradle test 컴파일 단위 공유 → wave 병렬성 제한적, bts-impl 계산).

### Task 1. PasswordPolicy VO + 검증 (TDD)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/PasswordPolicy.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/PasswordPolicyTest.kt`]
- depends-on: []

**RED**. `PasswordPolicyTest.kt`
```kotlin
@Test fun `11자는 MIN_LENGTH 위반`()                       // 경계값 11
@Test fun `12자 3종이상은 통과`()                          // 경계값 12 + 대소문자/숫자
@Test fun `12자 2종만은 COMPLEXITY 위반`()                 // 길이 OK, 복잡도 미달
@Test fun `짧고 단순하면 두 위반 모두 열거`()              // MIN_LENGTH + COMPLEXITY 동시
@Test fun `특수문자 포함 3종 통과`()
@Test fun `검증은 CharArray 입력, 호출 후 wipe 안 함(읽기 전용)`()  // policy 는 wipe 책임 없음
```
- 실패 (예상). `PasswordPolicy` 클래스/`validate` 없음

**GREEN**. `PasswordPolicy.kt`
- `enum class PasswordPolicyViolation { MIN_LENGTH, COMPLEXITY }`
- `object PasswordPolicy { fun validate(plain: CharArray): List<PasswordPolicyViolation> }`
- 규칙. 길이 < 12 → MIN_LENGTH. 문자종류(대문자/소문자/숫자/특수) < 3 → COMPLEXITY
- 한글 KDoc 1줄 헤더 (CLAUDE.md §6) + SDD 19장 §3.2 인용

**REFACTOR**. 규칙 상수(`MIN_LENGTH = 12`, `MIN_CHARACTER_CLASSES = 3`) 추출 + 문자종류 판정 함수 분리.
`validate` 는 입력 `plain` 을 읽기만 (wipe 는 호출자 책임 — KDoc 명시).

**검증**. `./gradlew :modules:identity-access:test --tests 'com.atlas.bts.identity.credential.PasswordPolicyTest'`

### Task 2. ChangePasswordService — 변경 + 다른 세션 무효화 (TDD)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/ChangePasswordService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/ChangePasswordServiceTest.kt`]
- depends-on: [1]

**RED**. `ChangePasswordServiceTest.kt` (LocalCredentialService / SessionService / RefreshTokenRepository mock)
```kotlin
// 결과 sealed: Success / PolicyViolation(list) / SameAsCurrent / CurrentMismatch
@Test fun `정책 위반 시 PolicyViolation, rotate·세션무효화 미호출`()         // EC-2/3
@Test fun `new==current 면 SameAsCurrent, rotate 미호출`()                 // EC-4
@Test fun `current 불일치(rotate=false)면 CurrentMismatch, 세션 무효화 미호출`()  // EC-1/6
@Test fun `성공 시 현재 sid 제외 각 세션마다 revoke + revokeChainFromSession 쌍 호출`()  // S7, CONCERN-1
@Test fun `성공 시 다른 세션 0개여도 정상 Success`()                       // EC-8
@Test fun `검증 순서 — policy → same → rotate → 세션무효화 (mock 호출 순서 검증)`()  // EC-7
@Test fun `early-return(policy위반·same) 경로에서도 CharArray wipe`()      // CONCERN-5: rotate 미호출 시 finally 가 유일 wipe
```
- 실패 (예상). `ChangePasswordService` 미존재

**GREEN**. `ChangePasswordService.kt` — `@Service`
- `fun change(userId: UUID, currentSid: UUID, current: CharArray, new: CharArray): ChangePasswordResult`
- 순서. (1) `PasswordPolicy.validate(new)` → 위반 시 PolicyViolation (rotate 전) (2) `new` vs `current` 평문 비교 → SameAsCurrent (3) `LocalCredentialService.rotate(userId, current, new)` → false 면 CurrentMismatch (4) 성공 시 **다른 세션 무효화** — `sessionService.findActiveByUser(userId).filter { it.id != currentSid }.forEach { sessionService.revoke(it.id, REASON_PASSWORD_CHANGED); refreshTokenRepository.revokeChainFromSession(it.id) }` → Success
- **세션 무효화는 기존 logout 패턴(`revoke` + `revokeChainFromSession` 쌍) 미러 — 신규 메서드 0개** (CONCERN-1: session 만 끊으면 dangling refresh token 발생, 모듈의 모든 revoke 경로와 일관)
- `@Transactional` (rotate + 세션/chain 무효화 단일 경계 — EC-8: **같은 트랜잭션 내**, 무효화 실패 시 비번 변경도 롤백)

**REFACTOR**. `ChangePasswordResult` sealed interface 분리 + CharArray wipe 를 `finally` 로 보장
(policy위반·same early-return 포함, CONCERN-5) + KDoc(검증 순서 / 세션·chain 쌍 무효화 사유 /
`SameAsCurrent` 는 평문 동일성 단축 — current 진위 미검증 명시, CONCERN-3).

**검증**. `./gradlew :modules:identity-access:test --tests 'com.atlas.bts.identity.credential.ChangePasswordServiceTest'`

### Task 3. PasswordController + DTO + 에러 매핑 (TDD, MVC)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/PasswordController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/ChangePasswordRequest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/PasswordControllerMvcTest.kt`]
- depends-on: [2]

**RED**. `PasswordControllerMvcTest.kt` (`@WebMvcTest` + ChangePasswordService mock, 기존 MVC 테스트 패턴)
```kotlin
@Test fun `POST 변경 성공 200 changed=true`()
@Test fun `정책 위반 400 POLICY_VIOLATION + violations 배열`()
@Test fun `current 불일치 400 CURRENT_PASSWORD_MISMATCH`()
@Test fun `same 400 SAME_AS_CURRENT`()
@Test fun `빈 입력 400 (@NotBlank)`()                          // EC-5
@Test fun `미인증 401`()                                        // 필터 체인
```
- 실패 (예상). `PasswordController` 미존재

**GREEN**.
- `ChangePasswordRequest(currentPassword: String, newPassword: String)` — `@field:NotBlank`
- `PasswordController` — `@PostMapping("/api/v1/users/me/password")`, `@AuthenticationPrincipal jwt: Jwt`
  에서 `userId = UUID.fromString(jwt.subject)` + `currentSid = UUID.fromString(jwt.getClaimAsString("sid"))`
  (기존 `AuthController.kt:173` getClaimAsString 패턴), String → CharArray 변환 후 service.change 호출
- 결과 → HTTP 매핑. Success→200 `{changed:true}`, PolicyViolation/SameAsCurrent/CurrentMismatch→400.
  **inline `ResponseEntity.status(400).body(...)` 패턴** (identity-access 에 `@RestControllerAdvice` 없음 확인 —
  AuthController 컨벤션, net-new 에러 DTO. CONCERN-4)

**REFACTOR**. 에러 코드 enum + 응답 DTO 분리 + KDoc. `@PreAuthorize` 이중 가드 검토(기존 SecurityConfig 패턴).

**검증**. `./gradlew :modules:identity-access:test --tests 'com.atlas.bts.identity.web.PasswordControllerMvcTest'`

### Task 4. 변경 통합 테스트 — S1/S7 (TDD, Testcontainers)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/ChangePasswordIntegrationTest.kt`]
- depends-on: [3]

**RED/GREEN** (구현이 이미 GREEN 이므로 통합 시나리오 검증).
```kotlin
// 기존 Testcontainers 통합 베이스 재사용 (singleton pattern — learnings 2026-05-21)
@Test fun `S1 정상 변경 — 이전 비번 verify 실패, 새 비번 verify 성공, updated_at 갱신`()
@Test fun `S7 다른 세션 무효화 — 세션 B revoked + refresh chain revoked, 현재 세션 A active 유지`()
@Test fun `정책 위반 변경 거부 — DB password_hash 불변`()
```

**검증**. `./gradlew :modules:identity-access:test --tests 'com.atlas.bts.identity.integration.ChangePasswordIntegrationTest'`

## Plan 메타

- **task 수**. 4 (전부 TDD RED→GREEN→REFACTOR)
- **depends-on 그래프**. 1 → 2 → 3 → 4 (직렬). 전 task 같은 identity-access 모듈 test 컴파일 단위
  공유 (learnings 2026-05-29 모듈 컴파일 직렬화). bts-impl 계산
- **마이그레이션**. 0건 (기존 V003 local_credentials + sessions + refresh_tokens 재사용, 새 컬럼/테이블 없음)
- **재사용 (전수 grep 확인, phantom 0)**. `LocalCredentialService.rotate/verifyForUser` (PR #6, rotate=false on mismatch),
  `SessionService.findActiveByUser/revoke` + `RefreshTokenRepository.revokeChainFromSession` (PR #8),
  `SidRevokeJwtConverter` (5s TTL Caffeine 캐시로 revoked 세션 per-request 차단). `PasswordPolicy` 신규.
- **추가 검증**. ktlintCheck, detekt, 기존 ArchUnit(@Transactional/@Service 가드), 전체 `:modules:identity-access:test` 회귀
- **절대 규칙 체크**. 평문 저장 금지(Argon2 기존)/로그 PII 금지(기존 정책 계승)/인증 필수/CSRF(기존 CookieCsrf)/입력검증(@NotBlank)
- **writing-plans 우회 사유**. spec 결정 명확 + FR-AU-01 패턴 일관성 + 1인 부담

## 리뷰 결과

### code-reviewer 독립 plan 리뷰 (2026-05-30, autoplan 4종 우회 — auth 백엔드 eng 집중)

`STATUS: PASS_WITH_CONCERNS`. phantom 엔티티 0건 (모든 재사용 가정을 실제 코드 grep 으로 검증).
절대 규칙 커버 확인 (Argon2id 기존 / 로그 PII 금지 / 인증 필수 / CSRF / `@NotBlank` / `@Service`+`@Transactional` ArchUnit 가드).

| # | 등급 | 내용 | 반영 |
|---|---|---|---|
| C-1 | 강(보안) | session 만 revoke, refresh chain 미무효화 → dangling token + access token 윈도우 | ✅ ChangePasswordService 가 logout 패턴(`revoke`+`revokeChainFromSession` 쌍) 미러. `SidRevokeJwtConverter` 5s 차단을 spec FR-6/EC-8/S7 에 명시 |
| C-2 | 중 | spec EC-8 "커밋 후 무효화" ↔ plan "단일 트랜잭션" 모순 | ✅ EC-8 "같은 `@Transactional` 경계 내"로 정정 |
| C-3 | 소 | SameAsCurrent 평문 비교 — current 진위 미검증 (정보 누출 없음) | ✅ Task 2 REFACTOR KDoc 에 평문 동일성 단축 명시 |
| C-4 | 소 | identity-access 에 `@RestControllerAdvice` 없음 — 에러 DTO net-new | ✅ Task 3 inline `ResponseEntity`(AuthController 패턴) 명시 |
| C-5 | 소 | early-return(policy/same) 경로 wipe 가 `finally` 유일 | ✅ Task 2 RED 테스트에 early-return wipe 케이스 추가 |

**BLOCKER 0건.** C-1~5 모두 plan/spec 반영 완료. auth 작업이라 BLOCKER 무시 옵션 없음 — 해당 없음.
리뷰 반영으로 task 5→4 재구성 (세션 무효화 기존 메서드 조합 흡수).
