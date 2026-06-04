# FR-PM-08 전역 시스템 관리자 역할/권한 인프라 — 스펙

> BC: identity-access | PR #75 | ADR: [2026-06-04-system-admin-role](../decisions/2026-06-04-system-admin-role.md)
> 범위: 인프라만 (D1~D5). 실제 관리 엔드포인트/UI는 FR-PM-04·FR-AU-05.

## 사용자 시나리오 (Given-When-Then)

본 PR은 토대(인프라)라 최종 사용자 화면이 없다. 시나리오는 "시스템·후행 FR 관점"으로 기술한다.

**S1. 최초 시스템 관리자 부트스트랩**
- Given `bts.bootstrap.admin-username=alice` 설정 + alice 사용자 존재 + SYSTEM_ADMIN 보유자 0명
- When 앱이 기동
- Then alice에게 `SYSTEM_ADMIN`이 부여되고, `system_role_assignments`에 1행 생성

**S2. 부트스트랩 멱등 (재기동)**
- Given 이미 SYSTEM_ADMIN 보유자가 1명 이상 존재
- When 앱이 다시 기동
- Then 아무 변경 없음 (중복 부여·예외 없음), 정보 로그만

**S3. JWT 전역 역할 클레임**
- Given alice가 SYSTEM_ADMIN 보유
- When alice가 로그인해 access token을 발급받음
- Then JWT에 전역 역할 클레임(`roles`)이 `["SYSTEM_ADMIN"]`로 포함되고, SecurityContext authority에 `ROLE_SYSTEM_ADMIN`이 들어감
- And SYSTEM_ADMIN이 아닌 bob의 토큰에는 `roles`가 빈 배열(또는 부재)

**S4. 전역 판정기 (후행 FR이 사용)**
- Given alice(SYSTEM_ADMIN) / bob(일반)
- When 전역 권한 판정기(`SystemPermissionResolver`)에 시스템 권한을 질의
- Then alice → `true`, bob → `false`

## 기능 요구사항 (FR)

- **FR1. 전역 역할 저장** — `system_role_assignments(user_id, role)` 테이블. `role ∈ {SYSTEM_ADMIN}` CHECK. `UNIQUE(user_id, role)`. `user_id` → `users(id)` FK ON DELETE CASCADE. `project_id` 없음(전역).
- **FR2. 도메인 모델** — `SystemRole` enum(`SYSTEM_ADMIN`), `ProjectRole`과 분리된 별개 타입. `SystemRoleAssignment` 값 객체.
- **FR3. Repository** — 부여(멱등 INSERT, ON CONFLICT DO NOTHING) · 사용자별 역할 조회 · 역할 보유자 존재 여부(부트스트랩 멱등 판정용).
- **FR4. 전역 판정기 포트** — shared-kernel `com.bts.shared.permission`에 인터페이스. identity-access에 구현. 시스템 관리자 여부를 판정. FR-PM-04 등 전역 권한 게이트가 소비.
- **FR5. JWT 전역 역할 클레임** — `JwtIssuer.issue()`가 발급 시 사용자의 전역 역할을 `roles` 클레임으로 포함. JWT converter가 `ROLE_<role>` authority로 변환.
- **FR6. 부트스트랩** — `ApplicationRunner`가 기동 시 `bts.bootstrap.admin-username` 설정값을 읽어 멱등 승격(S1/S2).

## 비기능 요구사항 (NFR)

- **NFR1. 멱등성** — 부트스트랩은 재기동·중복 실행에 안전(ON CONFLICT + 보유자 존재 시 skip).
- **NFR2. 성능** — 전역 판정은 JWT 클레임 기반(요청당 DB 조회 0). JWT 발급 시 전역 역할 조회 1회(인덱스 user_id).
- **NFR3. 안전 기본값** — 설정값 부재/대상 사용자 부재 시 승격 없이 경고 로그(부팅 실패 금지). SYSTEM_ADMIN 미지정 상태도 정상 부팅.
- **NFR4. 회귀 0** — 기존 인증/JWT/세션/PAT 동작 불변. `roles` 클레임 추가가 기존 검증 깨지 않음.
- **NFR5. 프로파일 안전** — 새 prod 한정 빈이 non-prod 통합테스트 컨텍스트 부팅을 깨지 않음(메모리 `profile-scoped-bean-boot-failure` 회귀 방지).

## API 인터페이스 (REST)

**신규 REST 엔드포인트 없음.** 본 PR은 내부 인프라(포트/판정기)만. 전역 역할 부여/조회 API는 FR-PM-04/FR-AU-05·관리자 화면 소관.

내부 인터페이스(포트) 후보 시그니처 — plan에서 확정:
```kotlin
// shared-kernel com.bts.shared.permission
interface SystemPermissionResolver {
    fun isSystemAdmin(actorId: UUID): Boolean
    // 확장 후보: hasSystemRole(actorId, role) / hasSystemPermission(actorId, code)
}
```

## 데이터 모델 변경

- **신규 마이그레이션 V012** (identity-access) — `system_role_assignments` 생성. V번호는 구현 직전 동시 진행 브랜치와 충돌 재확인(현재 최신 V009).
- 컬럼 추가가 아닌 새 테이블이므로 jOOQ 미러(`init_codegen.sql`) 무관 — identity-access는 jOOQ 미사용(JdbcTemplate). (메모리 `jooq-init-codegen-mirror`는 issue-tracking 한정.)
- 시드 INSERT 없음 — 부트스트랩이 런타임에 처리(마이그레이션 고정 INSERT 기각, ADR D5).

## 엣지 케이스

- **EC1.** 설정 `admin-username` 비어있음 → 승격 시도 없음, 정보 로그.
- **EC2.** 설정된 username의 사용자 없음 → 경고 로그, 승격 없음, 부팅 정상.
- **EC3.** 이미 SYSTEM_ADMIN 1명 이상 → skip(멱등). 설정 username과 무관하게 추가 부여 안 함.
- **EC4.** 같은 (user, role) 중복 부여 시도 → `UNIQUE` + ON CONFLICT DO NOTHING, 예외 없음.
- **EC5.** 사용자 삭제 → `system_role_assignments` 행 CASCADE 삭제.
- **EC6.** JWT stale — 역할 박탈 후 access token(15분) 만료까지 클레임 잔존. 본 PR 수용(ADR D4). 즉시 무효화는 후속.
- **EC7. PAT(봇 토큰)에 전역 역할** — PAT는 전역 역할 클레임을 **포함하지 않는다**(JWT 로그인 전용). 메모리 `session-management-pat-exclusion`와 동형 — 시스템 관리 권한은 사람 세션 한정. → plan-review 확인 항목.
- **EC8. IssueScope.Global** — 현재 prod 하드거부 상태를 **유지**(본 PR 결선 안 함). 회귀 아님, FR-PM-04가 결선.
- **EC9. 다중 인스턴스 부팅 race** — 현재 단일 호스트(docker compose)라 인스턴스 1개지만, ON CONFLICT DO NOTHING으로 동시성 안전 확보.

## 제약 조건

- `ProjectRole`에 SYSTEM_ADMIN을 섞지 않는다(별개 축, ADR D2).
- 전역 매트릭스(`role_permissions` 전역판) 도입 안 함 — 단일 역할 직접 판정(ADR D3).
- 한 PR = 한 BC. identity-access만 수정. issue-tracking(IssueScope.Global 결선) 미접촉.
- prod 한정 빈은 non-prod fallback 또는 사용시점 fail-fast로 부팅 안전(메모리 패턴).
- **부여 경로 한정** — 본 PR 후 전역 역할 부여 수단은 부트스트랩(설정값)뿐. 추가 관리자 임명/박탈 API는 FR-PM-04·관리자 화면 소관. 즉 이 PR 직후엔 설정으로 지정한 관리자만 존재.
- **감사 로그(audit)** — 전역 역할 부여는 보안 민감 이벤트지만, audit 인프라는 FR-AU-10 후속이라 본 PR은 구조적 로그(정보/경고)만 남긴다. audit 이벤트 emit은 FR-AU-10에서 결선.

## 측정 가능한 완료 기준

- [ ] V012 마이그레이션 적용 + Testcontainers 통합테스트 그린
- [ ] 통합테스트(@ActiveProfiles "prod"): SYSTEM_ADMIN 사용자 → 판정기 `true`, 일반 사용자 → `false`
- [ ] 부트스트랩 테스트: S1(승격) + S2(멱등 skip) + EC2(대상 부재 경고)
- [ ] JWT 발급 테스트: SYSTEM_ADMIN 토큰에 `roles=["SYSTEM_ADMIN"]` 클레임 + `ROLE_SYSTEM_ADMIN` authority, 일반 사용자 부재
- [ ] 기존 identity-access 테스트 회귀 0 + ktlint/detekt 그린
- [ ] non-prod 통합테스트 컨텍스트 부팅 정상(프로파일 안전)

## Brainstorming Check

✅ 통과 (적대적 self-review, office-hours 스킵 — 정의된 FR 작업). 발견·보강: 감사 로그 처리(audit는 FR-AU-10 후속, 현재 로그만) · PAT 전역역할 제외(EC7) · 부여 경로 한정(추가 임명은 후속 FR). 미해소 설계 갈림길(전역 판정기 시그니처 isSystemAdmin vs 권한코드 기반)은 plan-review에서 code-reviewer 검토 위임.
