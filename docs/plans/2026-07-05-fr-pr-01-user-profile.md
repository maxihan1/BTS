# FR-PR-01 사용자 프로필 (이름/아바타/타임존/부서)

> slug: fr-pr-01-user-profile
> type: api
> agent: backend-engineer
> primary_bc: identity-access (domain 단계에서 확정)
> 생성: 2026-07-05

## Brief

FR-PR-01 사용자 프로필 (이름/아바타/타임존/부서) — `GET/PATCH /api/v1/users/me/profile` + MinIO 아바타 + 프로필 UI.

product 문서: `docs/plan/product/personalization.md §2.1`
- D1. 도메인 — UserProfile
- D2. 명세
- D3. 데이터 모델 — `user_profiles(user_id, display_name, avatar_url, timezone, department)`
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/profile`. MinIO 아바타
- D5. 백엔드 테스트
- D6. 프론트 UI — 프로필 페이지 + 아바타 업로드
- D7. E2E

**미결 결정**: personalization BC는 백엔드 모듈이 없음. user_profiles를 identity-access에 둘지, 신규 모듈을 만들지 domain 단계에서 결정.

## 도메인 정리

- **BC (논리)**: personalization / **모듈 (물리)**: identity-access (Maxi 확정 — 논리 BC ≠ 물리 모듈, FR-UX-01 선례)
- **영향 엔티티**: `UserProfile` (신규), `User` (기존 — display_name 재사용)
- **데이터 모델 (Option A, Maxi 확정)**:
  - `users.display_name` — '이름' 편집 대상, 여기 유지 (이관/중복 없음)
  - `user_profiles(user_id PK/FK CASCADE, avatar_url, timezone, department)` — 신규
  - 프로필 조회 = users JOIN user_profiles / 이름 편집 = users 업데이트 / 아바타·타임존·부서 편집 = user_profiles 업데이트
- **새 용어**: "UserProfile" (사용자 프로필 — 이름/아바타/타임존/부서). glossary 추가 후보 (Maxi 승인 대기)
- **아바타**: identity-access 자체 MinIO 배선 신설 (issue-tracking 첨부 보안 패턴 재사용 — MIME 화이트리스트·크기 제한·nosniff)
- **FR-PR-04 경계**: LDAP 동기화 vs 사용자 편집 **출처 분리(source 컬럼)** 는 FR-PR-04로 미룸. 본 테이블은 source 컬럼 추가 여지 유지
- **기존 결정 충돌**: 없음. `PreferencesController` stub(`/api/v1/users/me/preferences`)은 CSRF 시연용 — 경로 다름(`/profile`), 무충돌
- **관련 ADR**: [docs/decisions/2026-07-05-fr-pr-01-user-profile-placement.md](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-05-fr-pr-01-user-profile.md](../specs/2026-07-05-fr-pr-01-user-profile.md)

**스코프**: 백엔드 D1~D5. D6~D7(프론트 프로필 페이지·E2E)는 후속 UI PR(FR-TL-01 선례).

핵심 시나리오 요약.
- `GET /api/v1/users/me/profile` — users JOIN user_profiles. row 없으면 defaults(avatar null, tz 'UTC').
- `PATCH /api/v1/users/me/profile` — displayName→users, timezone/department→user_profiles(lazy upsert). 3-state(부재/null).
- 아바타 3종 — `POST/DELETE /me/profile/avatar` + `GET /users/{id}/avatar`. MinIO, MIME 화이트리스트, 5MB, nosniff, I/O tx밖.
- 테이블 `user_profiles(user_id PK/FK CASCADE, avatar_object_key, timezone, department, ts)`. **JdbcTemplate**(identity-access 관례, jOOQ 아님).

## Brainstorming Check

✅ 통과 (1회, 코드 대조 gap 6건 보강 — F1 JdbcTemplate 교정 / F2 avatar_object_key 파생 / F3 tz 'UTC' / F4 soft-delete 없음 / F5 OCC 불필요 / F6 마이그레이션 카운트).

## Plan

> 모듈 루트 = `backend/` (settings.gradle: `:modules:identity-access`). 검증 명령은 `backend/`에서 실행.
> DB 접근 = `NamedParameterJdbcTemplate` (identity-access 관례, jOOQ 아님). 신규 패키지 `com.atlas.bts.identity.profile`.

### Task 1. V027 user_profiles 마이그레이션 + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V027__user_profiles.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/UserProfilesSchemaTest.kt`]
- depends-on: []

**RED**:
- 파일: `.../profile/UserProfilesSchemaTest.kt`
- 패턴: `AuthAuditLogsSchemaTest`/`MfaBackupCodesSchemaTest` 복제 — Testcontainers PostgreSQL + Flyway `classpath:db/migration` 전체 적용 후 `user_profiles` 존재 + 컬럼(`user_id`, `avatar_object_key`, `timezone`, `department`, `created_at`, `updated_at`) + PK + FK(users CASCADE) 단언.
- 실패: `user_profiles` 테이블 없음 → SQL 예외.

**GREEN**:
- 파일: `V027__user_profiles.sql`
```sql
CREATE TABLE user_profiles (
    user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    avatar_object_key TEXT,
    timezone          VARCHAR(64) NOT NULL DEFAULT 'UTC',
    department        VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE user_profiles IS 'FR-PR-01 사용자 프로필 확장 — 아바타/타임존/부서 (display_name은 users 유지)';
```
- L1 주석: `-- FR-PR-01 사용자 프로필 (아바타/타임존/부서). display_name은 users 유지`

**REFACTOR**: 컬럼 COMMENT 정리.

**검증**: `./gradlew :modules:identity-access:test --tests "*UserProfilesSchemaTest"`

---

### Task 2. UserProfile 도메인 + Repository (JdbcTemplate)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/UserProfile.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/UserProfileRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/JdbcUserProfileRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/UserProfileRepositoryTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `.../profile/UserProfileRepositoryTest.kt` — `@JdbcTest + @AutoConfigureTestDatabase(NONE) + @Import(JdbcUserProfileRepository) + @Testcontainers` (`UserRepositoryTest` 복제). users 행 사전 INSERT(FK 충족).
- 테스트: `findByUserId 없으면 null` / `upsertProfile 신규 INSERT(lazy 생성)` / `upsertProfile 기존 UPDATE` / `setAvatarObjectKey` / `clearAvatar → null` / `department 3-state(null 저장)`.

**GREEN**:
- `UserProfile.kt` — `data class UserProfile(val userId: UUID, val avatarObjectKey: String?, val timezone: String, val department: String?)`.
- `UserProfileRepository` 인터페이스 — `findByUserId`, `upsertProfile(userId, timezone?, department 3-state)`, `setAvatarObjectKey(userId, key)`, `clearAvatar(userId)`.
- `JdbcUserProfileRepository` — `NamedParameterJdbcTemplate` + `INSERT ... ON CONFLICT (user_id) DO UPDATE`. `updated_at = NOW()`. 3-state는 서비스에서 해석 후 repo는 명시값만 받음(부재 필드는 SQL SET에서 제외 or 서비스가 현재값 병합).

**REFACTOR**: SQL 상수 추출 + RowMapper + KDoc.

**검증**: `./gradlew :modules:identity-access:test --tests "*UserProfileRepositoryTest"`

---

### Task 3. 아바타 검증 정책 + MinIO 저장 어댑터 (identity-access 자체 배선)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/avatar/AvatarTypePolicy.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/avatar/AvatarStoragePort.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/avatar/MinioAvatarStorageAdapter.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/avatar/MinioAvatarStorageConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/avatar/AvatarTypePolicyTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/avatar/MinioAvatarStorageAdapterTest.kt`]
- depends-on: []

**RED**:
- `AvatarTypePolicyTest` (순수 단위): 허용 MIME(`image/jpeg|png|gif|webp`) 통과 / 그 외 거부 / 5MB 초과 거부 / 확장자 매핑(png→.png).
- `MinioAvatarStorageAdapterTest` (Testcontainers MinIO, issue-tracking `MinioStorageAdapter` 테스트 복제): `put → get(스트림+contentType via stat) → delete` 왕복. 없는 key get = 도메인 예외(→ 컨트롤러 404).

**GREEN**:
- `AvatarTypePolicy` — `ALLOWED_MIME` set(issue-tracking `AttachmentTypePolicy` 이미지 서브셋), `MAX_BYTES = 5 * 1024 * 1024`, `validate(mime, size)` + `extensionFor(mime)`. 위반 시 `AvatarValidationException`(→ 400).
- `AvatarStoragePort` 인터페이스 — `put(objectKey, bytes/stream, contentType)`, `get(objectKey): (stream, contentType)`, `delete(objectKey)`.
- `MinioAvatarStorageConfig` — `@ConfigurationProperties(prefix="bts.minio")` 재사용(issue-tracking `MinioStorageConfig` 복제, **버킷은 avatars 전용** — 빈 이름 충돌 방지 위해 `@Qualifier`/구분 명명, memory: shared 유틸 BC별 빈이름 충돌).
- `MinioAvatarStorageAdapter` — MinIO put/statObject(contentType 회수)/getObject/removeObject. 버킷 없으면 생성(idempotent).

**REFACTOR**: 상수/버킷명 config화 + KDoc. nosniff는 컨트롤러 책임(주석 명시).

**검증**: `./gradlew :modules:identity-access:test --tests "*AvatarTypePolicyTest" --tests "*MinioAvatarStorageAdapterTest"`

---

### Task 4. UserProfileService (조회 병합 / 3-state PATCH / 아바타 오케스트레이션)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/UserProfileService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/UserProfileServiceTest.kt`]
- depends-on: [2, 3]

**RED**:
- `UserProfileServiceTest` (mockk: UserRepository, UserProfileRepository, AvatarStoragePort):
  - `getProfile` — users + user_profiles 병합. profile row 없으면 defaults(avatarUrl 파생 null, tz 'UTC', dept null), displayName은 users에서.
  - `patchProfile` — displayName→users.save, timezone/department→upsert. **3-state**: 부재=미변경, null=삭제(department). timezone 무효(`ZoneId.of` 실패) → `ProfileValidationException`(400), 부분 적용 없음(검증 먼저).
  - `uploadAvatar` — 정책 검증 → **MinIO put(tx 밖)** → 기존 key best-effort delete(교체) → repo.setAvatarObjectKey. avatarUrl 파생 반환.
  - `deleteAvatar` — repo.clearAvatar → MinIO delete(best-effort, 실패 무시).
  - `getAvatar(userId)` — key 없으면 도메인 404 신호.

**GREEN**: 서비스 구현. `@Service`(ArchUnit `TransactionalServiceArchTest`). DB 쓰기만 `@Transactional`, MinIO I/O는 트랜잭션 경계 밖(memory: 첨부 I/O tx밖). displayName 편집은 `UserRepository.save` 재사용(username/email 보존).

**REFACTOR**: avatarUrl 파생 헬퍼(`/api/v1/users/{id}/avatar`) 추출, 예외 메시지 일반화(권한/경로 누출 금지).

**검증**: `./gradlew :modules:identity-access:test --tests "*UserProfileServiceTest"`

---

### Task 5. DTO + UserProfileController (5 엔드포인트)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/ProfileResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/ProfilePatchRequest.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/AvatarUploadResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/UserProfileController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UserProfileControllerTest.kt`]
- depends-on: [4]

**RED**:
- `UserProfileControllerTest` (`@WebMvcTest` 슬라이스 + service mock):
  - `GET /me/profile` → 200 JSON 형태.
  - `PATCH /me/profile` — 3-state 바디, displayName blank→400, timezone 무효→400, department null 허용.
  - `POST /me/profile/avatar` (multipart) → 200 `{avatarUrl}`, 무효 MIME/크기→400.
  - `GET /users/{id}/avatar` → 200 이미지 + `X-Content-Type-Options: nosniff` + `Content-Disposition: inline`, 없으면 404.
  - `DELETE /me/profile/avatar` → 204.
  - **3-state 역직렬화**: `ProfilePatchRequest`는 `JsonNullable` 또는 `Map` 기반(부재 vs 명시 null 구분). BTS DatePatch 선례 패턴 재사용.

**GREEN**:
- DTO 3종. `ProfilePatchRequest`는 부재/null 구분 가능한 표현(선례 grep: DatePatch 3-state).
- `UserProfileController` — 현재 userId = `@AuthenticationPrincipal Jwt`.subject(WhoamiController 패턴). 5 엔드포인트를 서비스에 위임. 아바타 GET은 `StreamingResponseBody`/`ByteArray` + 헤더. **인증 필수**(우회 금지).

**REFACTOR**: 헤더 상수화 + KDoc(인증 방식·nosniff 사유).

**검증**: `./gradlew :modules:identity-access:test --tests "*UserProfileControllerTest"`

---

### Task 6. 프로필/아바타 HTTP 통합 테스트 (full-boot prod)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/UserProfileFlowIntegrationTest.kt`]
- depends-on: [5]

**RED/GREEN** (통합 — 구현은 T1~T5, 본 task는 end-to-end 검증):
- `@SpringBootTest(RANDOM_PORT) + prod 프로파일` (memory: identity-access prod+RANDOM_PORT 부팅 레시피) + Testcontainers PostgreSQL + MinIO.
- 시나리오: 인증 없이 접근 → 401 / JWT로 GET(신규 사용자 defaults) / PATCH(displayName+timezone+department) 반영 / 아바타 업로드→GET(nosniff, 바이트 일치)→삭제(204)→GET 404 / 무효 MIME 400 / 5MB 초과 400 / 타 사용자 아바타 GET 가능(동료 표시).
- **JWT actor 추출을 리소스 조회보다 먼저**(memory: auth-extraction-before-resource-lookup).

**검증**: `./gradlew :modules:identity-access:test --tests "*UserProfileFlowIntegrationTest"` + 전체 `./gradlew :modules:identity-access:test ktlintCheck detekt`

## Plan 메타

- task 수: 6
- 예상 wave: Wave1[T1,T3] → Wave2[T2] → Wave3[T4] → Wave4[T5] → Wave5[T6] (레이어 의존 직렬. 단일 모듈이라 컴파일도 직렬)
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산
- 추가 검증: ktlintCheck, detekt, ArchUnit(`TransactionalServiceArchTest`)
- 스코프: 백엔드 D1~D5. D6(프론트 프로필 페이지)·D7(E2E)는 후속 UI PR
- 담당: db-engineer(T1), backend-engineer(T2·T4·T5), security-engineer(T3·T6 — 파일 업로드/인증)

## 리뷰 결과 (← /bts-review-plan 채움)
