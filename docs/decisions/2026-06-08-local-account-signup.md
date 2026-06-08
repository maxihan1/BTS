# ADR — 관리자 로컬 계정 회원가입 (FR-AU-05)

> 날짜: 2026-06-08
> 상태: 채택 (FR-AU-05 회원가입 슬라이스)
> BC: identity-access
> 관련 SDD: [19. 인증](../sdd/19-authentication.md)
> 선행 ADR: [system-admin-role](2026-06-04-system-admin-role.md)(SYSTEM_ADMIN 가드) · [stored-password-credential-schema](2026-05-20-stored-password-credential-schema.md)(local_credentials) · [argon2id-parameters](2026-05-20-argon2id-parameters.md)
> Plan: [docs/plans/2026-06-08-fr-au-05-signup.md](../plans/2026-06-08-fr-au-05-signup.md)

## 맥락

FR-AU-05(로컬 계정)는 비밀번호 변경(PR #44 API + PR #45 프론트/E2E)까지 완료됐으나 **회원가입(관리자가 외부 협력사용 로컬 계정을 생성)**이 남아 있었다. 가입에는 "누가 계정을 만들 수 있나"는 시스템 관리자 개념이 필요했고, 전역 역할이 어디에도 없어 막혀 있었다. FR-PM-08(PR #75)이 `SYSTEM_ADMIN` + `SystemPermissionResolver` + `ROLE_SYSTEM_ADMIN` authority를 도입해 선행을 해소했다.

로컬 계정은 `users` 행 + `local_credentials` 행으로 존재한다(외부 IdP의 `user_external_accounts` 불필요 — LDAP/SSO 전용). 기존 부품 재사용으로 조립한다.

| 재사용 부품 | 위치 |
|---|---|
| User 엔티티 + UPSERT | `user/User.kt`, `user/UserRepository.kt`(`save`) |
| 비밀번호 해시(Argon2id) | `credential/LocalCredentialService.kt`(`store`) |
| 비밀번호 정책(12자/3종) | `credential/PasswordPolicy.kt`(`validate`) |
| 비밀번호 변경 + 플래그 해제 지점 | `credential/ChangePasswordService.kt` |
| 시스템 관리자 판정 | `permission/IdentityAccessSystemPermissionResolver.kt`, `@PreAuthorize` |

## 결정

### D1 — 엔드포인트 = `POST /api/v1/users`, SYSTEM_ADMIN 전용

`UsersController`가 이미 `GET /api/v1/users`(목록)를 소유하므로 같은 컨트롤러에 `POST`를 추가한다(자원 일관). 가드는 선언적 보안 `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` — FR-PM-08이 결선한 `ROLE_SYSTEM_ADMIN` authority 경로를 그대로 소비한다.

- **기각: resolver 직접 호출.** `SystemPermissionResolver.isSystemAdmin(actorId)`를 컨트롤러에서 직접 부를 수도 있으나, `@EnableMethodSecurity`(SecurityConfig)가 켜져 있고 `ROLE_SYSTEM_ADMIN`이 SecurityContext에 들어오므로 선언적 보안이 더 단순하고 BTS 관례(`UsersController`의 `@PreAuthorize`)와 일관.

### D2 — 계정 생성 = INSERT 전용, 중복 username은 409 거부

`UserRepository.save`는 `ON CONFLICT (username) DO UPDATE` UPSERT다(프로비저닝 멱등성용). **회원가입에 이 UPSERT를 그대로 쓰면 기존 계정을 덮어쓴다** — learning [[ExternalAccountRepository 책임 침범]](PR #8, 두 곳 UPSERT → FK 위반)와 동류의 책임 침범. 따라서 가입은 **사전 존재 검사 후 INSERT 전용** 경로로 만들고, 이미 존재하는 username이면 `409 USERNAME_TAKEN`으로 거부한다.

- TOCTOU(검사-후-삽입 사이 경합)는 `users.username UNIQUE` 제약이 최종 방어 — INSERT 충돌 시도 `USERNAME_TAKEN`으로 변환.

### D3 — 임시 비밀번호 = 서버 랜덤 생성, 응답 1회 반환, 평문 미저장

가입 시 서버가 `PasswordPolicy` 충족(12자 이상 + 3종 복잡도) 랜덤 비밀번호를 생성해 `LocalCredentialService.store`로 Argon2id 해시만 저장하고, **평문은 생성 응답에 1회만 담아 반환**한다(관리자가 협력사에 전달). 평문은 DB·로그 어디에도 남기지 않는다.

- **기각: 관리자 직접 입력(Maxi 2026-06-08).** 관리자가 평문을 알게 되고 약한 비번 입력 여지. 서버 생성이 정책 보장 + 최소 노출.
- 응답 DTO는 `{ id, username, temporaryPassword }`. SYSTEM_ADMIN에게 HTTPS로만 전달.

### D4 — 강제 비밀번호 변경 = `local_credentials.must_change_password` 플래그 (V018)

임시 비번으로 첫 로그인 시 반드시 새 비번으로 바꾸게 강제한다(Maxi 2026-06-08, "더 안전").

```sql
ALTER TABLE local_credentials
  ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
```

- **가입 시** `must_change_password = TRUE`로 저장.
- **노출**: `whoami`(`GET /api/v1/auth/whoami` 또는 동등) 응답에 `mustChangePassword` 필드 추가 → 프론트 라우트 가드가 `true`면 `/settings/password`로 강제 리다이렉트.
- **해제**: 기존 `ChangePasswordService.changePassword` 성공 시 `must_change_password = FALSE`로 갱신. 임시 비번을 아는 사용자는 기존 변경 흐름(`currentPassword`=임시 비번)을 그대로 사용 — 새 엔드포인트 불필요.
- 기존 로컬 계정/외부 계정은 `DEFAULT FALSE`라 영향 없음(외부 계정은 `local_credentials` 행 자체가 없음).
- init_codegen 미러 규칙(메모리 `jooq-init-codegen-mirror`) — identity-access는 별도 init_codegen.sql 부재 확인됨, 마이그레이션만 추가.

### D4-b — whoami가 `isSystemAdmin`도 노출 (프론트 admin UI 게이팅)

가입 폼은 SYSTEM_ADMIN 전용인데, 프론트엔드에 시스템 관리자 판별 수단이 없었다(기존엔 프로젝트 단위 권한 게이팅만). whoami를 확장하는 김에 `isSystemAdmin: Boolean`도 함께 노출한다 — `SystemPermissionResolver.isSystemAdmin(userId)` 계산, PAT 인증은 false(전역역할 제외).

- **기각: 프론트 JWT roles 클레임 직접 디코드.** 프론트는 현재 JWT를 파싱하지 않고 whoami를 단일 진실원으로 쓴다. whoami 확장이 일관적이고 PAT 제외 규칙도 서버에서 일괄 적용된다.
- **기각: 프론트 게이팅 없이 백엔드 403만.** 비 admin에게 못 쓰는 폼을 노출하는 나쁜 UX. 백엔드 403은 최종 방어로 유지하되 프론트도 게이팅한다.
- 로그인 흐름이 이미 whoami 결과를 `authStore.user`에 저장하므로 `requireSystemAdmin` 가드가 `user.isSystemAdmin`을 동기 읽기.

### D5 — 리셋(비밀번호 재설정)은 본 슬라이스 제외

비밀번호 리셋은 이메일 발송 인프라(notification BC `JavaMailSender`)가 필요하나 부재 → 후속 FR. 본 PR은 가입 + 강제 변경까지.

## 결과 / 트레이드오프

- **산출물**: V018 마이그레이션 + `CreateLocalAccountService`(가입) + `POST /api/v1/users`(SYSTEM_ADMIN) + 임시 비번 생성기 + `must_change_password` 노출/해제 결선 + 가입 폼(D6) + E2E(D7).
- **장점**: 기존 부품 재사용. 협력사 계정 보안(서버 생성 비번 + 강제 변경). FR-AU-05 사실상 완결(리셋 제외).
- **비용**: V018 마이그레이션 1건. 로그인/whoami 흐름에 `mustChangePassword` 결선 + 프론트 가드 추가.
- **새 용어**: "임시 비밀번호(temporary password)", "강제 비밀번호 변경(must-change-on-first-login)" → glossary 추가 후보(Maxi 승인 대기).
