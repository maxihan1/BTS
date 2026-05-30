<!-- BTS spec — FR-AU-05 로컬 계정 비밀번호 변경 (백엔드 PR-1) -->

# FR-AU-05 로컬 계정 비밀번호 변경 (백엔드 PR-1) — 스펙

> slug: fr-au-05-local-account · type: auth · BC: identity-access · 2026-05-30
> plan: `docs/plans/2026-05-30-fr-au-05-local-account.md`

## 배경 · 스코프

본 PR-1 = **인증된 로컬 계정 사용자의 비밀번호 변경**. 기존 `LocalCredentialService.rotate`
(현재 비번 검증 후 새 비번 저장)와 `verifyForUser`(timing attack 방어 포함)를 재사용하고,
그 위에 **PasswordPolicy 검증**과 **REST API**를 얹는다.

**본 PR 포함**: 비밀번호 변경 API + PasswordPolicy(최소 길이/복잡도) + 변경 성공 시 **다른 세션 무효화**
(FR-AU-09 세션 관리 재사용) + 백엔드 단위·통합 테스트(D5).

**본 PR 제외 (후속, Maxi 결정 2026-05-30)**:
- 관리자 계정 생성(가입) API — 전역 admin 권한 체계(FR-PM-01) 선행 필요
- 임시 비밀번호 / mustChangePassword — 가입과 함께 후속
- 비밀번호 리셋(분실 재설정) — 이메일 인프라 도입 후
- 프론트 UI(D6) / E2E(D7) — 후속 PR-2

## 사용자 시나리오 (Given-When-Then)

- **S1 정상 변경**. Given 로그인된 로컬 사용자(`alice`), When 현재 비번 일치 + 정책 만족 새 비번 제출,
  Then 200 OK + `local_credentials.password_hash` 갱신 + `updated_at` 갱신 + **현재 세션 제외 다른 세션 무효화**.
- **S7 다른 세션 무효화**. Given 같은 사용자가 2개 기기(세션 A=현재, 세션 B)에서 로그인,
  When 세션 A에서 비번 변경, Then 세션 B는 session 폐기 + refresh chain 폐기(access token ~5초 내
  401 + refresh 시도 거부) + 세션 A는 유지.
- **S2 현재 비번 불일치**. Given 로그인, When `currentPassword` 틀림, Then 400 + 에러 코드
  `CURRENT_PASSWORD_MISMATCH`, DB 변경 없음.
- **S3 정책 위반 — 길이**. When `newPassword` 11자, Then 400 + `POLICY_VIOLATION` + 위반 규칙 목록.
- **S4 정책 위반 — 복잡도**. When `newPassword` 12자이나 문자 종류 2종만, Then 400 + `POLICY_VIOLATION`.
- **S5 새 비번 = 현재 비번**. When `newPassword == currentPassword`, Then 400 + `SAME_AS_CURRENT`
  (현재 비번 검증은 통과하나 정책상 동일 비번 재설정 거부).
- **S6 로컬 credential 없는 사용자**. Given LDAP/외부 IdP 사용자(local_credentials row 없음),
  When 호출, Then 400 + `CURRENT_PASSWORD_MISMATCH` (존재 여부 누출 방지 — 기존 dummy verify로
  S2와 동일 응답 + 일정 응답시간).

## 기능 요구사항 (FR)

- **FR-1**. `POST /api/v1/users/me/password` — 인증된 사용자가 자기 비밀번호를 변경.
  요청 본문 `{ currentPassword: string, newPassword: string }`.
- **FR-2 (PasswordPolicy)**. `newPassword`가 정책을 만족해야 함 (SDD 19장 §3.2).
  - 최소 12자
  - 영문 대문자 / 영문 소문자 / 숫자 / 특수문자 중 **3종 이상** 포함
  - 위반 시 모든 위반 규칙을 응답에 열거 (사용자가 한 번에 교정 가능)
- **FR-3 (현재 비번 검증 + 변경)**. `LocalCredentialService.rotate(userId, current, new)` 재사용.
  현재 비번 불일치 시 변경 거부.
- **FR-4 (동일 비번 거부)**. `newPassword == currentPassword` 시 거부 (S5).
- **FR-5 (에러 응답)**. 400 응답은 안정적 에러 코드 + 사용자 노출 한국어 메시지 포함.
  로그/응답 어디에도 평문 비번/해시 미포함.
- **FR-6 (세션 무효화)**. 변경 **성공 시**에만, 현재 세션(JWT `sid` claim) 제외 해당 사용자의
  다른 활성 세션을 무효화. **기존 logout 패턴 미러 — 각 세션마다 session 폐기
  (`SessionService.revoke`) + refresh token chain 폐기(`RefreshTokenRepository.revokeChainFromSession`)
  쌍** 적용 (모듈의 모든 revoke 경로 = logout / self-service / replay 탐지와 일관). session 만
  끊고 chain 을 남기면 dangling refresh token 발생 → 쌍 무효화 필수.
  `SidRevokeJwtConverter`(5s TTL Caffeine 캐시)가 폐기 세션의 access token 을 per-request 거부 →
  다른 기기 최대 5초 내 차단. 변경 실패(EC-1~6) 시 무효화 없음. 현재 세션은 유지.

## 비기능 요구사항 (NFR)

- **로그 정책**. password / hash / userId 를 로그에 출력 금지 (기존 `LocalCredentialService` 정책 계승).
  성공/실패 boolean + ms latency 만 INFO.
- **응답 시간**. 변경 p95 < 500ms (Argon2id verify 1회 + hash 1회 = 약 2× 해싱 비용).
- **메모리 위생**. 평문 비번은 `CharArray`로 다루고 사용 후 wipe (DEVELOPMENT.md §1.1).
  DTO 가 `String`으로 받으면 즉시 `CharArray` 변환 후 원본 참조 최소화 (heap 잔존은 기존 학습 한계 — KDoc 명시).
- **보안 규칙**. 인증 필수(`@AuthenticationPrincipal Jwt`), CSRF 활성(기존 CookieCsrf), 평문 저장 금지.

## API 인터페이스 (REST)

```
POST /api/v1/users/me/password
Authorization: (Access JWT, 기존 필터 체인)
Content-Type: application/json

Request:
  { "currentPassword": "<평문>", "newPassword": "<평문>" }

Responses:
  200 OK                       — 변경 성공 { "changed": true }
  400 Bad Request              — { "code": "POLICY_VIOLATION", "violations": ["MIN_LENGTH","COMPLEXITY"], "message": "..." }
                               — { "code": "CURRENT_PASSWORD_MISMATCH", "message": "현재 비밀번호가 일치하지 않습니다." }
                               — { "code": "SAME_AS_CURRENT", "message": "새 비밀번호가 현재 비밀번호와 같습니다." }
  401 Unauthorized             — 미인증 (기존 필터 체인)
```

- 사용자 식별. JWT `subject` = userId (UUID), 현재 세션 식별 `sid` claim (FR-AU-09), 기존 `WhoamiController`/`AuthController` 패턴.
- CSRF. POST 변경 요청은 기존 CookieCsrfTokenRepository 정책 적용 (X-XSRF-TOKEN 헤더). 프론트(PR-2)가 전송.
- 에러 본문 형식. identity-access 에 `@RestControllerAdvice` **없음** (확인됨) → 기존 `AuthController` 의
  inline `ResponseEntity.status(400).body(...)` 패턴 채택 (net-new 에러 DTO, 모듈 컨벤션 = 컨트롤러별 inline 매핑).

## 데이터 모델 변경

**없음.** 기존 `local_credentials`(V003) + `LocalCredentialService.rotate` 재사용. 마이그레이션 0건.
(mustChangePassword 컬럼은 가입 모델과 함께 후속 PR에서 도입.)

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| EC-1 | currentPassword 불일치 | 400 `CURRENT_PASSWORD_MISMATCH`, DB 무변경 (rotate=false) |
| EC-2 | newPassword 길이 미달 | 400 `POLICY_VIOLATION` [MIN_LENGTH] — rotate 호출 전 정책 선검증 |
| EC-3 | newPassword 복잡도 미달 | 400 `POLICY_VIOLATION` [COMPLEXITY] |
| EC-4 | newPassword == currentPassword | 400 `SAME_AS_CURRENT` — rotate 호출 전 검증 |
| EC-5 | currentPassword / newPassword 빈 문자열 / null | 400 입력 검증 (Bean Validation `@NotBlank`) |
| EC-6 | 로컬 credential 없는 사용자(LDAP) | 400 `CURRENT_PASSWORD_MISMATCH` (dummy verify, 존재 누출 방지) |
| EC-7 | 검증 순서 | (1) 입력 비어있음 → (2) `POLICY_VIOLATION`(new) → (3) `SAME_AS_CURRENT`(평문 비교) → (4) rotate(현재 비번 검증+변경) → (5) 성공 시 세션 무효화 |
| EC-8 | 세션 무효화 시점 | rotate 성공 직후 **같은 `@Transactional` 경계 내**에서 다른 세션 + refresh chain 폐기 (무효화 실패 시 비번 변경도 함께 롤백). 무효화 대상 0건이어도 정상 200 |

## 제약 조건

- **절대 규칙 (DEVELOPMENT.md §1)**. 평문 저장 금지(Argon2id) / 로그 PII 금지 / 인증 필수 / CSRF 활성 / 입력 검증.
- **BC 격리**. identity-access 단독. cross-BC 호출 없음.
- **기존 시그니처 보존**. `LocalCredentialService.rotate/verifyForUser` 변경 최소화 (재사용).
- **TDD 강제**. PasswordPolicy → 변경 서비스 → Controller 순 RED→GREEN→REFACTOR.

## 측정 가능한 완료 기준

1. `POST /api/v1/users/me/password` 가 S1~S6 시나리오대로 동작 (MVC 통합 테스트).
2. `PasswordPolicy` 단위 테스트 — 경계값(11자/12자, 2종/3종) + 각 위반 코드 검증.
3. 변경 후 `local_credentials.password_hash` 갱신 + 이전 비번으로 verify 실패, 새 비번으로 verify 성공 (통합 테스트, Testcontainers).
4. 로그에 평문/해시/userId 미출력 (로그 캡처 검증 또는 코드 리뷰).
5. `./gradlew :modules:identity-access:test ktlintCheck detekt` 통과 + 기존 ArchUnit 룰(@Transactional/@Service) 통과.
6. S7 — 비번 변경 후 다른 세션 무효화 + 현재 세션 유지 (통합 테스트, FR-AU-09 세션 재사용).

## Brainstorming Check

✅ 통과 (직접 1회 sanity check, FR-AU-01 패턴).

**발견 gap**.
- **세션 무효화 누락 (실질 gap)** — 비번 변경 후 기존 세션 처리 미정의. Maxi 결정(다른 세션 무효화 +
  현재 유지) → FR-6 / S7 / EC-8 보강 완료.
- minor (spec/impl에서 닫음).
  - CSRF — POST 변경은 기존 CookieCsrfTokenRepository 헤더 정책 (API 섹션 명시).
  - 검증 순서 — EC-7로 명확화 (policy → same → rotate → 세션 무효화).
  - 200 응답 본문 — `{ changed: true }` 확정.
  - rate limit / lockout — 기존 LockoutPolicy ADR 존재, 비번 변경 API 적용은 본 PR 범위 외(후속).
  - LDAP 사용자 응답 — EC-6, 존재 누출 방지 위해 `CURRENT_PASSWORD_MISMATCH` 통일.
