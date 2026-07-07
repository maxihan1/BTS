# FR-PR-04 — LDAP 동기화 필드 vs 사용자 편집 분리 (source 컬럼)

> slug: fr-pr-04-ldap-vs-source
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-07-07

## Brief

사용자 원문: "FR-PR-04 진행 (LDAP 소스 분리)"
classify: type=auth, agent=security-engineer, slug=fr-pr-04-ldap-vs-source, primary_bc=identity-access

FR-PR-04 (personalization §2.4): LDAP 동기화 필드 vs 사용자 편집 필드를 source 컬럼으로 분리.
핵심 충돌: 사용자가 프로필 이름(users.display_name)을 편집해도, 다음 LDAP 재로그인 시
AutoProvisionService가 ON CONFLICT (username) → displayName을 LDAP cn으로 덮어써 원복됨.
FR-PR-04 = "LDAP 동기화 시 USER 편집 필드는 덮어쓰지 않음" + 필드별 출처(source) 표시.

선행 사실:
- LDAP 동기화는 로그인 시점(JIT) 뿐. 별도 주기적 sync 잡 없음.
- LDAP 제공 필드 = username / email / displayName(cn). department는 LDAP 미제공.
- user_profiles(avatar/timezone/department)는 현재 순수 사용자 편집(LDAP 미접촉).
- ADR 2026-07-05: FR-PR-01이 source 컬럼 추가 여지 남김.

## 도메인 정리

- **BC**: identity-access (personalization 논리 BC의 물리 배치 — FR-PR-01 선례)
- **영향 엔티티**: User (`users.display_name_source` 컬럼 신설)
- **새 용어**: "필드 출처(Field Source)" — 프로필 필드 값의 출처(LDAP 동기화 vs 사용자 편집). glossary 추가 후보(Maxi 승인 대기)
- **source 모델** (ADR D1): `users.display_name_source` enum(`'LDAP'`|`'USER'`, DEFAULT `'LDAP'`). 범용 테이블/컬럼별 source 미채택(실 충돌 필드 display_name 하나뿐 → YAGNI)
- **충돌 해소** (ADR D2): 편집 시 source='USER' 전환(단일 tx) → 재로그인 UPSERT는 `CASE WHEN source='USER' THEN 보존 ELSE EXCLUDED` 게이트. email은 편집 대상 아님 → 계속 LDAP 동기화
- **재동기화** (ADR D4): source 'USER'→'LDAP' 되돌림 → 다음 로그인부터 cn 동기화 재개. UI "LDAP 값으로 재설정"
- **기존 결정 충돌**: 없음. FR-PR-01 ADR D3(source 컬럼 여지)를 실현. **product 문서 §2.4 D3 "user_profiles 컬럼별 source" 표기는 부정합**(display_name이 users에 있음) → 본 PR에서 정정
- **관련 ADR**: [docs/decisions/2026-07-07-fr-pr-04-ldap-field-source.md](../decisions/2026-07-07-fr-pr-04-ldap-field-source.md) (생성됨) · 선행 [2026-07-05-fr-pr-01-user-profile-placement.md](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md)

## 스펙

전체 스펙. [docs/specs/2026-07-07-fr-pr-04-ldap-vs-source.md](../specs/2026-07-07-fr-pr-04-ldap-vs-source.md)

핵심 시나리오.
- 사용자가 LDAP 이름을 편집하면 source=USER로 전환 → 이후 LDAP 재로그인이 덮어쓰지 않음(S2 핵심)
- source=LDAP 사용자는 재로그인에 계속 cn 동기화(기존 동작 유지, S3)
- "LDAP 값으로 재설정" → source=LDAP 복귀, 다음 로그인부터 지연 동기화(S4)
- 로컬 전용 사용자는 출처 라벨/재설정 미노출(S5)

핵심 API.
- GET /me/profile 응답에 `displayNameSource`·`ldapLinked` 추가
- PATCH /me/profile: displayName 편집 시 서버가 source=USER 자동 전환
- POST /me/profile/display-name/resync (신규): source=USER→LDAP (외부계정 없으면 409)

핵심 데이터. `users.display_name_source VARCHAR(8) NOT NULL DEFAULT 'LDAP' CHECK IN ('LDAP','USER')` (신규 V0NN).

## Brainstorming Check

✅ 통과 (1회). gap 2건 모두 plan에서 흡수(Maxi 결정 불요).
- Gap A: User RowMapper fanout 회피 → 프로필 전용 targeted 쿼리(`findDisplayNameSource`/`existsExternalAccount`)
- Gap B: 같은 값 저장도 USER 전환 수용 + 스키마 스냅샷 테스트 점검

## Plan (← /bts-plan 채움)

## Plan

> 모든 identity-access 리포지토리/서비스 테스트는 Testcontainers(실 Postgres)로 마이그레이션을 적용해 돈다.
> 공유 `User` 도메인/`UserRowMapper`에 컬럼 추가 금지(8 SELECT fanout 회피) — 프로필 전용 targeted 쿼리 사용.

### Task 1. 마이그레이션 V030 — users.display_name_source 컬럼

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V030__user_display_name_source.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/UserDisplayNameSourceSchemaTest.kt`]
- depends-on: []

**RED**: `UserDisplayNameSourceSchemaTest` — Testcontainers로 마이그레이션 적용 후 `information_schema.columns` 조회.
- `users.display_name_source` 존재 · 타입 `character varying(8)` · NOT NULL · DEFAULT `'LDAP'`
- CHECK 제약이 `('LDAP','USER')`만 허용(INSERT 위반 시 예외) → 실패(컬럼 없음)

**GREEN**: V030 SQL 작성.
```sql
ALTER TABLE users
  ADD COLUMN display_name_source VARCHAR(8) NOT NULL DEFAULT 'LDAP'
    CHECK (display_name_source IN ('LDAP', 'USER'));
COMMENT ON COLUMN users.display_name_source IS 'FR-PR-04 ...';
```
기존 행은 DEFAULT로 자동 backfill='LDAP'.

**REFACTOR**: COMMENT 문구 정리. init_codegen 미러 불요(jdbc-only) 주석 명시.

**검증**: `./gradlew :backend:identity-access:test --tests "*UserDisplayNameSourceSchemaTest"`

---

### Task 2. UserRepository — CASE 게이트 + 편집 시 USER 전환 + resync + source 조회

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/UserRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/user/UserRepositoryTest.kt`]
- depends-on: [1]

**RED**: UserRepositoryTest에 4개 테스트 추가.
- `provisionFromExternal이 source=USER 행의 display_name을 보존한다`(S2): provision → updateDisplayName("앨리스")로 source=USER 전환 → provisionFromExternal(cn="Alice") 재호출 → display_name still "앨리스"
- `provisionFromExternal이 source=LDAP 행의 display_name을 동기화한다`(S3): provision → cn 변경 재provision → display_name 갱신
- `updateDisplayName이 display_name_source를 USER로 전환한다`
- `resyncDisplayNameSource가 source를 LDAP로 되돌린다` + `findDisplayNameSource가 현재 source를 반환한다`

**GREEN**:
- `SQL_PROVISION_UPSERT`의 `DO UPDATE` display_name을 `CASE WHEN users.display_name_source='USER' THEN users.display_name ELSE EXCLUDED.display_name END`로 변경. email·updated_at은 유지. source 컬럼은 동기화가 미변경. (`SQL_UPSERT` 로컬 경로는 손대지 않음)
- `SQL_UPDATE_DISPLAY_NAME`에 `display_name_source='USER'` 추가.
- 신규 `SQL_RESYNC_DISPLAY_NAME_SOURCE`(`SET display_name_source='LDAP', updated_at=NOW() WHERE id=:userId`) + `resyncDisplayNameSource(userId)`.
- 신규 `SQL_FIND_DISPLAY_NAME_SOURCE` + `findDisplayNameSource(userId): String?`.
- 인터페이스(UserRepository)에 신규 메서드 시그니처 추가.

**REFACTOR**: SQL 상수 KDoc(왜 CASE 게이트인지 — S2 보존). ktlint/detekt 라인길이 점검.

**검증**: `./gradlew :backend:identity-access:test --tests "*UserRepositoryTest"`

---

### Task 3. ExternalAccountRepository — existsByUserId (ldapLinked 판별)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccountRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/ldap/ExternalAccountRepositoryTest.kt`]
- depends-on: [1]

**RED**: `existsByUserId가 외부계정 있으면 true, 없으면 false`. provisionUser로 매핑 생성한 userId=true, 미매핑 userId=false.

**GREEN**: `SQL_EXISTS_BY_USER_ID`(`SELECT EXISTS(SELECT 1 FROM user_external_accounts WHERE user_id=:userId)`) + `existsByUserId(userId): Boolean`.

**REFACTOR**: KDoc — "외부 IdP 연결 = 디렉터리 동기화 대상". 책임 경계(user_external_accounts만) 유지.

**검증**: `./gradlew :backend:identity-access:test --tests "*ExternalAccountRepositoryTest"`

---

### Task 4. UserProfileService — ProfileView 확장 + resyncDisplayName + 예외

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/profile/UserProfileService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/profile/UserProfileServiceTest.kt`]
- depends-on: [2, 3]

**RED**: UserProfileServiceTest에 추가.
- `getProfile가 displayNameSource·ldapLinked를 채운다`(외부계정 있음/없음 두 케이스)
- `patchProfile로 displayName 편집 시 source가 USER로 반영된다`(loadView 재조회 값 확인)
- `resyncDisplayName이 외부계정 있는 사용자의 source를 LDAP로 되돌린다`
- `resyncDisplayName이 외부계정 없는 사용자에 DisplayNameNotLdapLinkedException을 던진다`

**GREEN**:
- `ProfileView`에 `displayNameSource: String`, `ldapLinked: Boolean` 추가.
- `loadView`가 `userRepository.findDisplayNameSource(userId)` + `externalAccountRepository.existsByUserId(userId)`로 채움.
- 생성자에 `ExternalAccountRepository` 주입.
- `resyncDisplayName(userId)`: `existsByUserId` false면 `DisplayNameNotLdapLinkedException` throw, true면 `userRepository.resyncDisplayNameSource(userId)` 후 `loadView` 반환. `@Transactional`.
- 신규 예외 클래스 `DisplayNameNotLdapLinkedException`.

**REFACTOR**: KDoc(source 채우기 경로). patchProfile은 로직 변경 없음(source 전환은 repo SQL) — loadView만 확장됨을 주석 명시.

**검증**: `./gradlew :backend:identity-access:test --tests "*UserProfileServiceTest"`

---

### Task 5. DTO + Controller — 응답 필드 + resync 엔드포인트 + 409

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/dto/ProfileResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/UserProfileController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UserProfileControllerTest.kt`]
- depends-on: [4]

**RED**: UserProfileControllerTest에 추가.
- `GET /me/profile 응답에 displayNameSource·ldapLinked가 포함된다`
- `POST /me/profile/display-name/resync가 200과 source=LDAP 응답을 반환한다`
- `resync가 외부계정 없는 사용자에 409(DISPLAY_NAME_NOT_LDAP_LINKED)를 반환한다`
- `resync가 PAT/비JWT 인증에 401`

**GREEN**:
- `ProfileResponse`에 `displayNameSource: String`, `ldapLinked: Boolean` 추가. `toResponse`가 view에서 매핑.
- `POST /me/profile/display-name/resync` 핸들러 → `userProfileService.resyncDisplayName(currentUserId(jwt))` → `toResponse`.
- 로컬 `@ExceptionHandler(DisplayNameNotLdapLinkedException)` → 409 `{code:"DISPLAY_NAME_NOT_LDAP_LINKED", message:"..."}`(내부정보 없음).

**REFACTOR**: 엔드포인트 KDoc + 에러코드 상수. `@Suppress("TooManyFunctions")` 유지.

**검증**: `./gradlew :backend:identity-access:test --tests "*UserProfileControllerTest"`

---

### Task 6. 프론트 — 출처 배지 + override + LDAP 재설정

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/features/profile/*` (프로필 페이지 컴포넌트), `apps/web/src/features/profile/api.ts`(또는 해당 BC api), `apps/web/src/features/profile/*.test.tsx`]
- depends-on: [5]

**RED**: 컴포넌트 테스트(vitest + MSW).
- `displayNameSource=LDAP + ldapLinked=true면 "LDAP에서 동기화됨" 배지 + "직접 편집" 노출`
- `displayNameSource=USER면 "LDAP 값으로 재설정" 노출, 클릭 시 resync 호출 후 배지가 LDAP로 갱신`
- `ldapLinked=false(로컬 사용자)면 출처 배지·재설정 미노출`

**GREEN**:
- Zod 스키마에 `displayNameSource`('LDAP'|'USER'), `ldapLinked`(boolean) 추가(backend DTO와 정합 — 스펙 grep).
- 프로필 페이지 display_name 필드 근처에 출처 상태 UI. override(직접 편집)/재설정 어포던스.
- resync mutation(`POST /me/profile/display-name/resync`) + 성공 시 프로필 invalidate.

**REFACTOR**: 문자열 상수/aria-label. 기존 프로필 페이지 스타일 관례 준수.

**검증**: `pnpm --filter web test`(해당 스펙) + `pnpm --filter web typecheck`

---

### Task 7. E2E — 출처 배지 / override / 재설정 플로우

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/profile-ldap-source.spec.ts`, `apps/web/src/mocks/**`(MSW 핸들러 — resync + source 필드 시나리오 토글)]
- depends-on: [6]

**RED/GREEN**: Playwright + MSW로 S1/S4/S5 해피패스.
- LDAP 소싱 사용자: 배지 표시 → 이름 편집(override) → USER 배지 → "재설정" → LDAP 배지
- 로컬 사용자: 배지/재설정 미노출
- MSW mutation stateful(재설정 후 재조회 반영) — msw-mutation-stateful-refetch 패턴

**검증**: `pnpm --filter web test:e2e --grep "profile-ldap-source"`

---

### Task 8. 문서 전수 동기화 — product §2.4 D1~D7 + D3 정정

**메타**.
- agent: `security-engineer` (문서 작업, TDD 없음)
- files: [`docs/plan/product/personalization.md`]
- depends-on: [5]

**작업**(테스트 없음 — 문서).
- `§2.4 FR-PR-04` D1~D7 체크박스 `[x]` 마킹(실제 구현 반영).
- D3 표기 "user_profiles 컬럼별 source 표시" → "users.display_name_source 단일 컬럼(ADR 2026-07-07 D1, 실 충돌 필드 display_name)"로 정정.
- FR 카운트 무변(상태만) — verify-master-plan.sh 통과 확인.

**검증**: `bash scripts/verify-master-plan.sh`

## Plan 메타

- task 수: 8
- 예상 wave: 약 6 (Task1 → {2,3} → 4 → 5 → 6 → 7). Task 8은 depends-on [5]라 5 이후 아무 wave.
- 예상 시간: 직렬 기준 약 24분, wave 병렬 적용 시 약 15분(backend→frontend→e2e 계약 의존 체인이 본질적 직렬).
- TDD 강제: yes (Task 8 문서 제외)
- 병렬 dispatch: Task 2·3만 동시(Task1 이후, 파일 무겹침). 나머지는 계약 의존으로 직렬.
- 추가 검증: ktlint/detekt(backend), typecheck/vitest(front), playwright(qa), verify-master-plan(docs)
- 주의(메모리): User RowMapper fanout 금지 · 스키마 스냅샷 테스트 점검 · MSW stateful refetch · frontend Zod↔backend DTO 정합 · V030 머지 직전 재확인

## 리뷰 결과

### plan-eng-review (2026-07-07, auth 집중)
- ✅ **게이트 우회 경로 없음** (코드 확인): `users.display_name` 쓰기는 `provisionFromExternal`(CASE 게이트, Task 2) + `updateDisplayName`(source=USER, Task 2) 둘뿐. `save()`/`upsert()`는 main 호출처 0 → 게이트 우회 write 없음.
- ✅ **ArchUnit 위반 없음** (코드 확인): SpiBoundaryArchTest는 `..spi..`/Spring `AuthenticationProvider`만 제약. Task 4의 `profile → provider/ldap`(ExternalAccountRepository 주입) 의존은 허용.
- ✅ **동시성 안전** (EC-5): 편집(source=USER)과 재로그인 UPSERT는 동일 users 행 락으로 직렬화됨(ON CONFLICT DO UPDATE가 편집 커밋 대기 후 커밋된 source 읽음) → lost update 없음.
- ✅ **마이그레이션 안전**: `ADD COLUMN ... DEFAULT 'LDAP'`은 PG11+ 메타데이터 변경. CHECK는 backfill='LDAP' 전행 통과. 1K 규모 락 무시 가능.
- ⚠️ 주의(비블로커) 1: `ldapLinked`=외부계정 보유(향후 SAML/OIDC 포함)인데 라벨은 "LDAP에서 동기화됨". 현재 LDAP+Local만 결선이라 무해. SAML/OIDC 결선 시 라벨 일반화 필요.
- ⚠️ 주의(비블로커) 2: users 컬럼 추가가 스키마 스냅샷/카운트 테스트에 걸리는지 Task 1/impl에서 전체 identity-access suite로 확인(제약 조건 반영됨).
- **BLOCKER: 없음**

### plan-ceo-review (2026-07-07)
- ✅ 스코프 최소·FR 정합: 실 충돌 필드 display_name 하나만 추적, 단일 컬럼. gold-plating 없음.
- ✅ 올바른 유예: email source·shadow-column 즉시재설정·범용 field-source 테이블은 후속(YAGNI).
- ✅ taste decision(지연 재설정 semantics)은 Maxi 사전 확정.
- **BLOCKER: 없음**
