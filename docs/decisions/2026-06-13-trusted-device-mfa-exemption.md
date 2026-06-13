<!-- FR-MF-05 신뢰 디바이스(30일 MFA 면제)의 식별 방식·취소 범위·통합 지점을 기록하는 ADR -->
# ADR — 신뢰 디바이스 (30일 MFA 면제) (FR-MF-05)

> 날짜: 2026-06-13
> 상태: 채택
> 관련 FR: FR-MF-05
> 관련 PR: #131 (백엔드 D1~D5)
> BC: identity-access

## 맥락

FR-MF-01(TOTP)/FR-MF-02(백업 코드)/FR-MF-03(WebAuthn)으로 2단계 인증(MFA)이 갖춰졌다. MFA를 켠 사용자는 매 로그인마다 1단계(비밀번호) 통과 후 2차 요소를 입력해야 한다(`AuthController.completeLogin` → `mfa_required` 챌린지). SDD §19.7.3은 "2FA 통과 후 '이 기기 30일 면제' 옵션"을 규정한다 — 사용자가 신뢰한 디바이스에서는 30일 동안 2차 요소를 다시 묻지 않는다.

핵심 난점. 로그인 시점에는 **아직 정식 세션(JWT)이 없다**. 따라서 "이 디바이스가 신뢰된 디바이스인가"를 1단계(비밀번호) 통과 직후, 세션 발급 전에 판정할 수단이 필요하다. SDD 스케치의 "디바이스 핑거프린트 + JWT 클레임"에서 JWT 클레임은 로그인 시점 부재라 이 판정에 쓸 수 없다.

## 결정

### D1. 디바이스 식별 = 서버 발급 불투명 토큰(HttpOnly 쿠키), DB는 해시만 저장

MFA verify 성공 + "이 기기 신뢰" 동의 시, 서버가 암호학적 난수 토큰(32바이트)을 생성해 `Set-Cookie: trusted_device=<rawToken>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`로 클라이언트에 내려준다. DB(`trusted_devices`)에는 `SHA-256(rawToken)` hex만 저장하고 평문 토큰은 비영속한다(refresh_token·백업 코드 선례 — 탈취 시 DB에서 역산 불가).

다음 로그인 시 브라우저가 이 쿠키를 동반한다(로그인 경로 `/api/v1/auth/login`이 쿠키 Path 아래). 서버는 쿠키 raw 값을 SHA-256 해시해 조회하고, `user_id` 일치 + 미만료면 챌린지를 생략한다.

**대안(기각) — 클라이언트 브라우저 핑거프린트.** canvas/UA/해상도 해시는 (1) 값 탈취 시 위조 가능, (2) 브라우저 업데이트로 값 변동(신뢰 손실), (3) 핑거프린팅 프라이버시 우려가 있어 보안적으로 약하다. 서버 불투명 토큰은 위조 불가·서버 통제·즉시 취소 가능하다.

**명명 일탈 메모.** SDD/product 스케치는 컬럼을 `device_fingerprint`로 표기하나, identity-access에는 이미 `Session.deviceFingerprint = SHA-256(userAgent + ipAddress)`(세션 표시용 **약한** 핑거프린트)가 존재한다. 보안 우회용 신뢰 토큰과의 혼동을 막기 위해 신규 컬럼은 **`token_hash`**로 명명한다. product 문서 D3에 인라인 일탈 메모로 기록한다(SDD 본문은 스케치라 불변 — deviation은 product 인라인만 선례).

### D2. 데이터 모델 — `trusted_devices` (V026, init_codegen 미러 불요)

identity-access는 jdbc-only 모듈이라 jOOQ 코드 생성 미러(`init_codegen.sql`)가 없다.

```
trusted_devices(
  id            UUID PK,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash    TEXT NOT NULL UNIQUE,           -- SHA-256(rawToken) hex 64자
  label         TEXT,                            -- User-Agent 파생 표시명(D6 관리 화면용, nullable)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL,            -- created_at + 30일(고정)
  last_used_at  TIMESTAMPTZ                      -- 신뢰 우회 로그인 시 갱신(D6 표시용)
)
-- 인덱스: token_hash UNIQUE(조회), (user_id) (목록/전체취소)
```

만료 정리(expires_at 지난 행 물리 삭제)는 별도 배치 없이 조회 시 `expires_at > now()` 술어로 무시한다(만료 행이 우연히 매칭돼도 무효). 물리 정리는 후속 운영 과제.

### D3. 통합 지점 — completeLogin(우회) + mfa/verify(등록)

- **로그인 우회** (`AuthController.completeLogin`). 1단계 Success 후 MFA 활성이면, `trusted_device` 쿠키가 있고 해당 user의 미만료 신뢰 디바이스와 일치하면 챌린지 대신 `issueTokens(mfaVerified=true)`로 정식 세션을 발급한다(`last_used_at` 갱신). 일치하지 않으면 기존대로 `mfa_required` 챌린지(회귀 0).
- **신뢰 등록** (`AuthController.verifyMfa`). `MfaVerifyRequest`에 `trustDevice: Boolean = false` 추가. 2차 요소 검증 성공 + `trustDevice=true`면 토큰 생성·저장 후 `issueTokens` 응답에 `trusted_device` Set-Cookie를 함께 내린다(refresh Set-Cookie와 병존). 기본값 false라 미동의 시 회귀 0.

신뢰 우회 세션도 `mfa_verified=true`다 — 과거 MFA 통과로 신뢰가 성립했으므로 동일 세션 효과(다운스트림 `mfa_verified` 가드 일관).

**보안 경계 — 신뢰 우회는 password(local/LDAP) 로그인 전용.** `AuthController.completeLogin`은 local/LDAP password 로그인 경로다. SSO(SAML/OIDC)는 `Saml2AuthenticationSuccessHandler`/`OidcAuthenticationSuccessHandler`의 독립 `issueTokens` 경로로 세션을 발급하며 `completeLogin`을 거치지 않는다(이 SSO issueTokens에는 `mfaVerified` 파라미터도 없다). SSO는 IdP가 2차 인증을 관할하므로 신뢰 우회 **의도적 비대상**이다 — SSO success handler에 신뢰 우회를 이식하지 않는다(회귀 표면 차단).

**trust 등록 best-effort.** verify 성공 시 trust 등록(`token_hash` UNIQUE)이 극저확률로 실패해도 정식 세션 발급을 막지 않는다 — 실패 시 쿠키만 누락하고 세션은 발급한다(로그인 가용성 우선, fail-safe).

### D4. 만료 = 고정 30일(sliding 아님)

`expires_at = created_at + 30일`로 고정한다. 신뢰 우회 로그인이 창을 연장하지 않는다("30일 면제" 문자 그대로 + 추적 상태 단순 + 무기한 신뢰 방지). `last_used_at`은 표시용으로만 갱신한다.

### D5. 취소 = 수동(단건+전체) + 보안 이벤트 자동 폐기

- **수동.** `DELETE /api/v1/auth/mfa/trusted-devices/{id}`(단건, 소유 검증 — 타인/미존재 404 IDOR 선례), `DELETE /api/v1/auth/mfa/trusted-devices`(본인 전체). 목록 `GET /api/v1/auth/mfa/trusted-devices`(D6 관리 화면용, JWT 전용·PAT 403 — MFA self-service 일관).
- **자동.** 비밀번호 변경 성공(`ChangePasswordService.change`의 `Success` 분기 — `SameAsCurrent`/`PolicyViolation`/`CurrentMismatch`는 비대상) + TOTP 비활성화(`MfaService.disable` `Success`) 시 해당 user의 신뢰 디바이스를 전량 revoke한다. SDD "분실/해킹 의심 시 모든 신뢰 디바이스 즉시 만료" 정신을 보안 이벤트에 결합한 것. 결합 방식은 서비스 메서드에 revoke 호출 추가(BC 내부, cross-BC 아님).

### D6. JWT 클레임 미도입 (SDD 스케치 일탈)

SDD 스케치의 "디바이스 핑거프린트 + JWT 클레임"에서 JWT 클레임은 로그인 시점 부재라 우회 판정에 무용하다. 신뢰 우회 세션은 기존 `mfa_verified=true` 재사용으로 충분하다. 별도 `trusted_device` JWT 클레임을 도입하지 않는다(불필요한 클레임·발급 chokepoint 변경 회피).

### D7. PR 범위 = 백엔드 먼저(D1~D5)

도메인 + 스키마 + fingerprint(토큰) 발급/우회 검증 + 취소(수동/자동) + 통합 테스트(TTL 만료·명시 취소·자동 폐기)까지 이 PR. 프론트 UI("이 기기 신뢰" 체크박스 + 디바이스 관리 페이지) + Playwright E2E는 D6/D7 후속 PR. FR-MF-01~04와 동일한 분할.

## 결과

- identity-access: V026 마이그레이션 1건(`trusted_devices`) + `TrustedDevice` 도메인 + `TrustedDeviceRepository`(JDBC) + `TrustedDeviceService`(발급/조회/취소) + 쿠키 헬퍼 + `MfaController`(또는 별도) 취소·목록 엔드포인트 + `AuthController.completeLogin`/`verifyMfa` 결선 + `MfaVerifyRequest.trustDevice` 필드.
- 자동 폐기 결합: `MfaService.disable` + 비밀번호 변경 서비스에 `TrustedDeviceService.revokeAll(userId)` 호출 추가.
- 회귀 표면: `MfaVerifyRequest`에 필드 추가(기본값 false라 기존 호출 무영향), verify 200 응답에 Set-Cookie 하나 추가(기존 refresh 쿠키와 병존), login 흐름에 쿠키 조회 분기 추가(쿠키 부재 시 기존 경로 그대로 — fail-safe).
- 테스트: prod 통합(신뢰 등록→다음 로그인 우회, TTL 만료→챌린지 복귀, 명시 취소→우회 불가, 비번변경/MFA비활성→자동폐기), 단위(토큰 해시·만료 판정).

## 위험 / 후속

- **쿠키 부재/차단 환경** — 쿠키를 막은 클라이언트는 신뢰 우회가 동작하지 않고 매번 챌린지로 자연 폴백한다(fail-safe, 보안 강화 방향). 의도된 동작.
- **자동 폐기 누락 위험** — 비번 변경/TOTP 비활성 경로에 revoke 호출을 빠뜨리면 stale 신뢰가 잔존한다. 통합 테스트로 양 경로 revoke를 ground-truth 검증.
- **쿠키 Path/SameSite** — `trusted_device` 쿠키는 refresh 선례와 동일하게 `Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict`. login·verify가 모두 이 path 아래라 정상 전송. SameSite=Strict라 cross-site 자동전송 차단(CSRF 표면 최소).
- **만료 행 물리 정리** — 조회 술어로 무효화하므로 기능상 문제 없으나, 누적 시 테이블 비대. 정리 배치는 후속 운영 과제.
- **로그아웃 시 신뢰 쿠키 비해제(의도)** — `logout`은 refresh 쿠키만 만료시키고 `trusted_device` 쿠키는 건드리지 않는다. 로그아웃은 신뢰를 해제하는 행위가 아니다(다음 로그인에 우회 의도). 신뢰 해제는 명시 취소/보안 이벤트 자동 폐기로만 일어난다. "logout인데 쿠키 잔존" 혼란 방지를 위해 명문화.
- **WebAuthn-only 사용자의 TOTP disable 자동폐기** — TOTP disable 시 전량 revoke하나, 사용자가 WebAuthn 키를 별도 보유하면 MFA는 여전히 활성이다. 보수적으로 TOTP disable도 전량 revoke한다(신뢰의 근거가 된 요소 변경 = 재신뢰 요구). spec에서 정밀화.
- D6/D7 프론트 체크박스 + 관리 페이지 + E2E 후속.
