# FR-PR-01 사용자 프로필 (이름/아바타/타임존/부서) — 스펙

> slug: fr-pr-01-user-profile
> 모듈: identity-access (논리 BC: personalization)
> 관련 ADR: [2026-07-05-fr-pr-01-user-profile-placement](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md)
> **스코프**: 백엔드 D1~D5 (도메인·마이그레이션·API·아바타·테스트). **D6~D7(프론트 프로필 페이지·E2E)는 후속 UI PR** — FR-TL-01/FR-RP-*/FR-IM-* 선례(백엔드 우선 분리).

## 스코프 경계

| 포함 (본 PR) | 제외 (후속) |
|---|---|
| `UserProfile` 도메인 | 프론트 프로필 페이지 (D6 → 후속 UI PR) |
| `user_profiles` 마이그레이션 (V027) | Playwright E2E (D7 → 후속 UI PR) |
| `GET/PATCH /api/v1/users/me/profile` | LDAP 동기화 vs 사용자 편집 **출처 분리** (FR-PR-04) |
| 아바타 업로드/삭제/조회 API + MinIO 배선 | 상태 메시지(FR-PR-02)·부재중(FR-PR-03) |
| 백엔드 단위/통합 테스트 (D5) | |

## 사용자 시나리오 (Given-When-Then)

### S1. 프로필 조회 (프로필 row 존재)
- **Given** 인증된 사용자 alice가 `user_profiles` row를 가짐 (timezone=Asia/Seoul, department=Platform)
- **When** `GET /api/v1/users/me/profile`
- **Then** 200 + `{ userId, username, email, displayName, avatarUrl, timezone, department }` 반환 (users JOIN user_profiles)

### S2. 프로필 조회 (프로필 row 없음 — 신규 사용자)
- **Given** 인증된 사용자 bob가 `user_profiles` row가 아직 없음 (LDAP 프로비저닝 직후)
- **When** `GET /api/v1/users/me/profile`
- **Then** 200 + `displayName`=users.display_name, `avatarUrl`=null, `timezone`=기본값('UTC'), `department`=null 반환 (row 없어도 defaults로 응답, row 강제 생성 안 함)

### S3. 이름 편집
- **Given** 인증된 사용자 alice
- **When** `PATCH /api/v1/users/me/profile` body `{ "displayName": "Alice Cooper" }`
- **Then** 200. `users.display_name` = 'Alice Cooper'로 갱신. 응답에 갱신값 반영

### S4. 타임존/부서 편집 (프로필 row lazy 생성)
- **Given** bob (user_profiles row 없음)
- **When** `PATCH /api/v1/users/me/profile` body `{ "timezone": "America/New_York", "department": "Sales" }`
- **Then** 200. `user_profiles` row가 **lazy upsert**로 생성(user_id=bob) + timezone/department 저장. 응답 반영

### S5. 부서 삭제 (3-state PATCH)
- **Given** alice (department=Platform)
- **When** `PATCH` body `{ "department": null }`
- **Then** 200. department = null (삭제). body에서 키 **부재** = 미변경, 키 **명시 null** = 삭제. (BTS DatePatch 3-state 선례)

### S6. 아바타 업로드
- **Given** 인증된 사용자 alice
- **When** `POST /api/v1/users/me/profile/avatar` (multipart, field `file` = alice.png, image/png, 200KB)
- **Then** 200 + `{ avatarUrl }`. MinIO에 저장(key `avatars/{userId}/{uuid}.png`), `user_profiles.avatar_url` = 안정 앱 URL(`/api/v1/users/{userId}/avatar`). row 없으면 lazy 생성

### S7. 아바타 조회 (동료 아바타 표시)
- **Given** alice가 아바타 보유
- **When** 인증된 사용자가 `GET /api/v1/users/{aliceId}/avatar`
- **Then** 200 + 이미지 바이트 스트림 (Content-Type=저장 MIME, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`). 아바타 없으면 404

### S8. 아바타 삭제
- **When** `DELETE /api/v1/users/me/profile/avatar`
- **Then** 204. `user_profiles.avatar_url` = null. MinIO 오브젝트 best-effort 삭제(실패해도 200 흐름 유지 — 고아 오브젝트는 무해)

## 기능 요구사항 (FR)

- **FR1** `GET /api/v1/users/me/profile` — 현재 인증 사용자(JWT subject=userId)의 프로필을 users JOIN user_profiles로 반환. row 없으면 defaults.
- **FR2** `PATCH /api/v1/users/me/profile` — 부분 업데이트. `displayName`→users, `timezone`/`department`→user_profiles(lazy upsert). 3-state(부재=미변경 / null=삭제, department 한정).
- **FR3** `POST /api/v1/users/me/profile/avatar` — 이미지 업로드. MIME 화이트리스트 + 크기 상한 검증. MinIO 저장(I/O는 트랜잭션 밖). avatar_url 갱신.
- **FR4** `GET /api/v1/users/{userId}/avatar` — 인증 사용자에게 아바타 이미지 스트림. nosniff.
- **FR5** `DELETE /api/v1/users/me/profile/avatar` — 아바타 제거(avatar_url null + MinIO best-effort 삭제).
- **FR6** `UserProfile` 도메인 + `UserProfileRepository`(**Spring `NamedParameterJdbcTemplate`** — identity-access 관례, `UserRepository`/`JdbcUserRepository` 패턴. **jOOQ 아님**) — user_profiles CRUD/upsert.

## 비기능 요구사항 (NFR)

- **NFR1** 프로필 조회 p95 < 100ms (product §NFR 측정표).
- **NFR2** 아바타 업로드 MIME 화이트리스트: `image/jpeg`, `image/png`, `image/gif`, `image/webp` (issue-tracking `AttachmentTypePolicy` 이미지 서브셋 재사용). 그 외 400.
- **NFR3** 아바타 크기 상한 5MB (첨부 100MB보다 작게 — 아바타 용도). 초과 400.
- **NFR4** 아바타 응답 항상 `X-Content-Type-Options: nosniff` (저장 콘텐츠 XSS/스니핑 방지, 첨부 선례).
- **NFR5** MinIO I/O는 DB 트랜잭션 밖에서 수행(memory: 첨부 I/O tx밖). 저장 성공 후 DB 커밋.
- **NFR6** 모든 엔드포인트 인증 필수(Spring Security 필터 우회 금지, DEVELOPMENT §보안). PAT로도 접근 가능한지는 whoami 선례 따름(JWT/PAT 공통 me-scope).

## API 인터페이스 (REST)

### GET /api/v1/users/me/profile → 200
```json
{
  "userId": "3f2...uuid",
  "username": "alice",
  "email": "alice@corp.com",
  "displayName": "Alice Kim",
  "avatarUrl": "/api/v1/users/3f2.../avatar",
  "timezone": "Asia/Seoul",
  "department": "Platform"
}
```
avatarUrl은 아바타 없으면 null.

### PATCH /api/v1/users/me/profile → 200 (갱신된 프로필, GET과 동일 형태)
```json
{ "displayName": "Alice Cooper", "timezone": "America/New_York", "department": null }
```
- 모든 필드 optional. 부재 필드 미변경.
- `displayName`: 명시 시 non-blank + ≤255. blank/초과 → 400.
- `timezone`: 명시 시 유효 IANA tz(`ZoneId.of` 파싱 성공). 무효 → 400.
- `department`: 명시 시 ≤255. `null` 허용(삭제).

### POST /api/v1/users/me/profile/avatar (multipart/form-data, `file`) → 200
```json
{ "avatarUrl": "/api/v1/users/3f2.../avatar" }
```
- 무효 MIME/크기 초과 → 400.

### GET /api/v1/users/{userId}/avatar → 200 (image bytes) | 404
### DELETE /api/v1/users/me/profile/avatar → 204

## 데이터 모델 변경

신규 마이그레이션 `V027__user_profiles.sql` (identity-access):
```sql
CREATE TABLE user_profiles (
    user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    avatar_object_key TEXT,                           -- MinIO 오브젝트 키 (없으면 NULL). 응답 avatarUrl은 이 값 유무로 파생
    timezone          VARCHAR(64)  NOT NULL DEFAULT 'UTC',  -- IANA tz
    department        VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```
- `users.display_name`은 유지 (이관 없음).
- **avatar_url 컬럼 없음** (F2): 응답 `avatarUrl`은 `avatar_object_key != null`이면 `/api/v1/users/{userId}/avatar`로 **파생**. URL은 user_id에서 100% 도출되므로 저장은 중복/drift. product 문서 illustrative 컬럼명(`avatar_url`)과 편차 → 머지 시 product 문서 한 줄 동기화.
- FR-PR-04를 위한 source 컬럼 여지 유지(본 PR 미도입).
- **identity-access는 jOOQ codegen 미사용** → init_codegen.sql 미러 대상 아님(이 모듈엔 파일 자체가 없음). Flyway V027만 추가.
- `updated_at`은 repository가 write마다 `NOW()` 세팅(기존 identity-access 관례 따름 — 트리거 유무는 plan에서 확인).

## 엣지 케이스

- **EC1** user_profiles row 없는 사용자 GET → defaults 반환(row 강제 생성 안 함). PATCH/아바타 업로드 시에만 lazy 생성.
- **EC2** PATCH 빈 body `{}` → 200, 아무 변경 없음(멱등).
- **EC3** timezone 무효 문자열("Mars/Phobos") → 400, 부분 적용 없음(displayName도 저장 안 함 — PATCH는 원자적, 검증 먼저).
- **EC4** 아바타 재업로드 → 기존 오브젝트 best-effort 삭제 후 새 오브젝트 저장(고아 방지). avatar_object_key 교체.
- **EC5** 아바타 GET 대상 userId가 존재하나 아바타 없음 → 404(500 아님).
- **EC6** users는 soft-delete 없음(코드 확인 완료). 하드 삭제 시 ON DELETE CASCADE로 user_profiles 자동 정리. 존재하지 않는 userId 아바타 GET = 404.
- **EC7** MIME은 image지만 실제 내용이 이미지 아님(위조) → 본 PR은 MIME + 크기 검증까지(첨부의 ClamAV/매직바이트는 스코프 밖, plan에서 재확인). nosniff로 브라우저 실행 차단.
- **EC8** 매우 긴 department(>255) → 400.
- **EC9** displayName을 빈 문자열로 편집 시도 → 400(이름은 whoami/멘션이 소비하므로 blank 금지).

## 제약 조건

- BC 격리: identity-access 내부에서만 작업. 타 모듈 import 금지.
- @Transactional 메서드는 `@Service` 필수(ArchUnit `TransactionalServiceArchTest`).
- MinIO 배선은 identity-access 자체(issue-tracking 어댑터 import 금지 — 모듈 격리). `MinioStorageConfig` 패턴 참조 복제.
- **DB 접근 = Spring `NamedParameterJdbcTemplate`**(identity-access 관례). jOOQ/init_codegen 아님.
- V027 추가가 identity-access 마이그레이션 카운트 가드(SchemaMigrationTest 류) 있으면 갱신 필요(memory: FR-PM 권한 시드↔마이그레이션테스트 결합).
- 완제품 품질(PoC/임시 코드 금지).

## 측정 가능한 완료 기준

- [ ] `GET/PATCH /api/v1/users/me/profile` + 아바타 3종 엔드포인트 구현·통합 테스트 통과
- [ ] V027 마이그레이션 + init_codegen 미러 + SchemaMigrationTest 카운트 갱신
- [ ] MIME 화이트리스트/크기/nosniff 검증 테스트
- [ ] 3-state PATCH(부재/null) 테스트
- [ ] lazy upsert(EC1/S4) 테스트
- [ ] ktlint/detekt/ArchUnit 통과
- [ ] D6~D7은 후속 UI PR로 명시(본 PR 제외)

## Brainstorming Check

✅ 통과 (1회 iteration, 코드 대조로 gap 6건 발견·보강).

- **F1 (교정)**: identity-access는 jOOQ가 아니라 Spring `NamedParameterJdbcTemplate` 사용(`UserRepository`/`JdbcUserRepository`). 스펙 초안의 "jOOQ raw SQL + init_codegen 미러" → JdbcTemplate 패턴으로 교정. init_codegen.sql은 이 모듈에 없음.
- **F2 (단순화)**: `avatar_url` 컬럼 제거 → `avatar_object_key`만 저장, `avatarUrl`은 응답에서 파생. URL은 user_id에서 도출되므로 저장은 중복/drift. product 문서 컬럼명과 편차 → 머지 시 동기화.
- **F3 (기본값)**: timezone 기본 `'UTC'`(중립). org 특정 기본값 대신 프론트(D6)가 브라우저 `Intl` timezone 자동 감지·제안.
- **F4 (엣지)**: users soft-delete 없음 확인 → EC6 단순화(하드 삭제 + CASCADE, 없는 userId 아바타 404).
- **F5 (스코프)**: OCC 불필요(단일 소유자 프로필, last-write-wins 허용). version 컬럼 미도입.
- **F6 (마이그레이션 카운트)**: V027 추가가 identity-access 마이그레이션 카운트 가드 깰 수 있음 → plan에 갱신 task 포함.
