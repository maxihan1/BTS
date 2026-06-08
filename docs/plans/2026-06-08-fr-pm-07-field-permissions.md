# FR-PM-07 — 필드 수준 권한 (Field-Level Permissions)

> slug: fr-pm-07
> type: auth
> agent: security-engineer
> 생성: 2026-06-08

## Brief

FR-PM-07 필드 수준 권한 — 특정 필드(코어+커스텀)를 특정 역할만 보거나 편집할 수 있게 하는 필드 수준 권한.
identity-access BC. SDD §12.5, plan §4.7. 선행 FR-IS-10(커스텀 필드)·FR-PM-06(이슈 보안 수준) 완료됨.

- type: auth
- agent: security-engineer
- primary_bc: identity-access

## 도메인 정리

- **BC**: identity-access(권한 규칙·판정 소유) + issue-tracking(시행: 직렬화 필터·편집 거부)
- **영향 엔티티**: `FieldPermission`(신규, identity-access), `IssueResponse`/`IssueApplicationService`(issue-tracking 시행), `UserGroup`/`CustomFieldDefinition`(소비 참조)
- **새 용어**:
  - 필드 수준 권한(Field-Level Permission) — 이슈의 특정 필드(코어+커스텀)를 특정 사용자 그룹만 열람/편집하게 제어
  - 필드 권한 규칙(FieldPermission) — (프로젝트, 필드 key, 그룹, 접근수준 VIEW/EDIT) 한 행
  - 접근 수준(FieldAccessLevel) — VIEW · EDIT (EDIT ⊃ VIEW 함의)
- **Maxi 도메인 결정(2026-06-08, AskUserQuestion)**:
  1. 역할 축 = 사용자 그룹 기반(FR-PM-09)
  2. 그릇 = 프로젝트별 단순 테이블(스킴 계층 미채택)
  3. 시행 범위 = 열람 + 편집 둘 다
  4. (ADR 기본값) 관리자 우회 없음 — 규칙 지정 그룹 멤버만 값 열람. 게이트1 검토 대상
- **기존 결정 충돌**: 없음. FR-PM-02/06·FR-IS-10 권한 패턴 재사용
- **관련 ADR**: [docs/decisions/2026-06-08-field-level-permissions.md](../decisions/2026-06-08-field-level-permissions.md) (생성됨)
- **glossary 갱신 대기**: "필드 수준 권한", "필드 권한 규칙", "접근 수준" 3종 (Maxi 승인 후 머지 시 반영)
- **grill-with-docs 스킵 사유**: 핵심 갈림길 3종을 AskUserQuestion으로 사전 정렬 + 선행 인프라(그룹/resolver/직렬화)를 코드 grep으로 실재 검증 완료. 명확해진 백엔드 FR이라 대화형 재론 불요(메모리: bts-spec-office-hours-mismatch 정신)

## 스펙

전체 스펙. [docs/specs/2026-06-08-fr-pm-07-field-permissions.md](../specs/2026-06-08-fr-pm-07-field-permissions.md)

핵심 시나리오 3줄 요약.
- 프로젝트별 필드 권한 규칙(field_permissions) — (필드, 그룹, VIEW/EDIT). MANAGE_FIELD_PERMISSIONS로 관리.
- 이슈 조회 시 actor가 VIEW 불가 필드 마스킹(커스텀=맵 키 제거, nullable 코어=null) + restrictedFields 응답.
- 이슈 편집 시 actor가 EDIT 불가 필드 변경 거부(403, no-op은 통과). 관리자 우회 없음.

## Brainstorming Check

✅ 통과 (자체 점검). gap 6건 사전 해소 — 코어 마스킹 타입 문제(non-null summary/priority는 편집 제어만)·field_kind 네임스페이스 분리·이중 통제 회피(securityLevelId/componentIds 제외)·cross-BC 커스텀 key 형식만 검증·N+1 배치 판정·관리자 우회 F7 명시.

## Plan

> **PR 분할(Maxi 2026-06-08)**: 2 PR. **PR-A = 백엔드 전체(이번, T1~T8)** / PR-B = 프론트 UI + E2E(후속 워크플로우).
> PR-A는 API 레벨에서 열람 마스킹·편집 거부가 prod 통합테스트로 자기완결 검증(dead code 아님). PR-B는 PR-A API 확정 후.

### Task 1. shared-kernel `FieldPermissionResolver` 포트 + `FieldKind`/`FieldRef`

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/FieldPermissionResolver.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/permission/FieldRefTest.kt`]
- depends-on: []

**RED**: `FieldRefTest` — `FieldRef(CORE, "summary")` 동등성/해시, `FieldKind` 2종(CORE/CUSTOM) 존재. 실패: 클래스 없음.

**GREEN**:
- `enum class FieldKind { CORE, CUSTOM }`, `data class FieldRef(val kind: FieldKind, val key: String)`.
- `interface FieldPermissionResolver { fun visibleFields(actorId: UUID, projectId: UUID, candidates: Set<FieldRef>): Set<FieldRef>; fun editableFields(...): Set<FieldRef> }`. KDoc — 규칙 없는 key는 항상 포함, EDIT⊃VIEW.

**REFACTOR**: KDoc에 spec §6 링크 + 계약(opt-in·관리자 우회 없음) 명시.

**검증**: `./gradlew :backend:shared-kernel:test --tests "*FieldRefTest"`

---

### Task 2. identity V018 마이그레이션 + `MANAGE_FIELD_PERMISSIONS` 시드 + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V018__field_permissions.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/PermissionSchemaMigrationTest.kt`]
- depends-on: []

**RED**: `PermissionSchemaMigrationTest` 카운트 14→15 + `PROJECT_ADMIN이 MANAGE_FIELD_PERMISSIONS 보유` 검증 추가(기존 파일 수정 — learnings: fr-pm-permission-seed-migration-test-coupling). 실패: V018 부재로 15≠14.

**GREEN**:
- `V018__field_permissions.sql` — spec §5 DDL(`field_permissions` 테이블 + UNIQUE + idx_project) + `role_permissions` PROJECT_ADMIN MANAGE_FIELD_PERMISSIONS INSERT(기본 스킴 `00000000-...-001`).
- `group_id REFERENCES user_groups(id) ON DELETE CASCADE`, `field_kind`/`access_level` CHECK.

**REFACTOR**: SQL 주석(필드 권한 규칙 테이블, opt-in 의미) + V016/V017 헤더 스타일 일치.

**검증**: `./gradlew :backend:identity-access:test --tests "*PermissionSchemaMigrationTest"` (Testcontainers Flyway)

---

### Task 3. identity `FieldPermission` 도메인 + `FieldPermissionRepository`

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/domain/FieldPermission.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/repository/FieldPermissionRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/fieldpermission/FieldPermissionRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: `FieldPermissionRepositoryTest`(Testcontainers, @ActiveProfiles prod) — `save`(멱등 ON CONFLICT) / `findByProject`(필드별·그룹별 행) / `deleteById` / 그룹 삭제 시 CASCADE 검증. 실패: 클래스 없음.

**GREEN**:
- `data class FieldPermission(id: UUID?, projectId: UUID, fieldKind: FieldKind, fieldKey: String, groupId: UUID, accessLevel: FieldAccessLevel)` + `enum FieldAccessLevel { VIEW, EDIT }`. 도메인 팩토리(fieldKey trim/64자 require, CORE key 화이트리스트 검증은 ApplicationService 책임).
- `FieldPermissionRepository`(JdbcTemplate, raw SQL — identity-access 관례) — save(ON CONFLICT DO NOTHING)/findByProject/deleteById. `FieldKind`는 shared-kernel 재사용.

**REFACTOR**: SQL 상수 분리 + KDoc.

**검증**: `./gradlew :backend:identity-access:test --tests "*FieldPermissionRepositoryTest"`

---

### Task 4. identity `IdentityAccessFieldPermissionResolver` (prod) + actor 그룹 배치 조회

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/IdentityAccessFieldPermissionResolver.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/repository/FieldPermissionRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/fieldpermission/IdentityAccessFieldPermissionResolverTest.kt`]
- depends-on: [1, 3]

**RED**: `IdentityAccessFieldPermissionResolverTest`(prod 통합) — (a) 규칙 없는 필드 항상 visible/editable(EC1), (b) VIEW 규칙 그룹 멤버만 visible·비멤버 제외(S1/S2), (c) EDIT⊃VIEW(S7/EC3), (d) 관리자라도 그룹 비멤버 제외(S5/EC10, isSystemAdmin 미호출 ground-truth), (e) 다중 그룹 OR(EC2). 실패: 클래스 없음.

**GREEN**:
- `@Component @Profile("prod") class IdentityAccessFieldPermissionResolver(fieldPermissionRepo, userGroupRepo) : FieldPermissionResolver`.
- 알고리즘: 프로젝트 규칙 1회 조회(`findByProject`) + actor group_ids 1회 배치 조회(`UserGroupRepository` 확장 `findGroupIdsByUser` 또는 FieldPermissionRepository 헬퍼 — N+1 회피, EC14). candidate별: 규칙 없으면 포함, VIEW/EDIT 행의 group_id ∩ actor groups ≠ ∅ 판정. editable은 EDIT 행만, visible은 VIEW∪EDIT 행.
- 관리자 우회 없음 — `isSystemAdmin`/PROJECT_ADMIN 미참조.

**REFACTOR**: 순수 판정 로직을 I/O 없는 함수로 분리(IssueSecurityDecider 선례) — 단위 테스트 용이.

**검증**: `./gradlew :backend:identity-access:test --tests "*IdentityAccessFieldPermissionResolverTest"`

---

### Task 5. identity 규칙 CRUD API + `MANAGE_FIELD_PERMISSIONS` 게이트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/application/FieldPermissionApplicationService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/web/FieldPermissionController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/fieldpermission/web/dto/FieldPermissionDtos.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/fieldpermission/FieldPermissionControllerTest.kt`]
- depends-on: [2, 3]

**RED**: `FieldPermissionControllerTest`(prod 통합) — POST/GET/DELETE happy(S6) + MANAGE_FIELD_PERMISSIONS 미보유 403(EC11) + 미인증 401 + 미존재 group_id 422(EC12) + 미정의 CORE field_key 422(EC13). 실패: 엔드포인트 없음.

**GREEN**:
- `FieldPermissionApplicationService` — CRUD + 게이트(인증주체 추출 후 `membershipRepo.findByProjectAndUser` + `schemeRepo.roleHasPermission(projectId, role, "MANAGE_FIELD_PERMISSIONS")`. FR-PM-09 그룹 CRUD 이중가드 패턴). CORE field_key 화이트리스트(spec §3.1) 검증, 커스텀은 형식만(EC13). group 존재 검증(`userGroupRepo`).
- `FieldPermissionController` — `GET/POST/DELETE /api/v1/projects/{projectIdOrKey}/field-permissions`. `@PreAuthorize("isAuthenticated()")` + 핸들러 DB 게이트. projectIdOrKey 해석(기존 ProjectDirectory/관례).
- DTO — 요청/응답(spec §4), Bean Validation.

**REFACTOR**: 게이트 예외 message 일반화(learnings: fr-pm-04-guard-exception-message-http-leak) + DTO `from` 매핑.

**검증**: `./gradlew :backend:identity-access:test --tests "*FieldPermissionControllerTest"`

---

### Task 6. issue-tracking non-prod stub + 부팅 가드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/fieldpermission/adapter/AlwaysAllowFieldPermissionResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/fieldpermission/FieldPermissionResolverBootTest.kt`]
- depends-on: [1]

**RED**: `FieldPermissionResolverBootTest`(!prod 컨텍스트 부팅) — `FieldPermissionResolver` 빈 1개 주입 가능 + candidates 그대로 반환. 실패: 빈 없음(부팅 실패). (learnings: profile-scoped-bean-boot-failure, FR-PM-02 non-prod resolver 빈 부재 회귀 방지)

**GREEN**: `@Component @Profile("!prod") class AlwaysAllowFieldPermissionResolver : FieldPermissionResolver` — visibleFields/editableFields가 candidates 그대로 반환(전 필드 허용). `AlwaysAllowComponentPermissionResolver` 동형.

**REFACTOR**: KDoc — non-prod 전용·prod는 identity-access 구현·실차단 검증은 identity prod 통합테스트 위임.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*FieldPermissionResolverBootTest"`

---

### Task 7. issue-tracking 이슈 응답 열람 마스킹 + `restrictedFields`

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueFieldVisibilityTest.kt`]
- depends-on: [1, 6]

**RED**: `IssueFieldVisibilityTest`(통합, non-prod stub + 포트 테스트 더블로 visible 집합 주입) — (a) 열람 불가 커스텀 키 `customFields`에서 제거(S1), (b) 열람 불가 nullable 코어(description 등) null 마스킹, (c) `restrictedFields`에 마스킹 key 명시, (d) non-null 코어(summary/priority)는 마스킹 안 됨(spec §3.1), (e) 단건·목록 양 경로. 실패: restrictedFields 필드 없음/마스킹 안 됨.

**GREEN**:
- `IssueResponse`에 `restrictedFields: List<String> = emptyList()` 추가. `from(...)`에 actor visible 집합 인자 추가 — 커스텀 맵 키 제거 + nullable 코어 null/빈컬렉션 마스킹 + restrictedFields 채움.
- `IssueApplicationService.findByKey`/`listIssues`가 `FieldPermissionResolver.visibleFields(actor, projectId, candidates)` 호출(목록은 페이지당 1회 배치, EC14) → `IssueResponse.from`에 전달. candidates = 코어 마스킹 대상 + 이슈의 customFields 키.

**REFACTOR**: 마스킹 헬퍼 분리 + KDoc(보호 필드 매트릭스 spec §3.1 링크).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueFieldVisibilityTest"`

---

### Task 8. issue-tracking 이슈 편집 EDIT 게이트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueFieldEditGateTest.kt`]
- depends-on: [1, 7]

**RED**: `IssueFieldEditGateTest`(통합) — (a) EDIT 불가 필드 값 변경 PATCH → 403(S3/EC5), (b) no-op(기존값 동일) 통과(S4/EC4), (c) EDIT 불가 커스텀 필드 설정 → 403(EC6), (d) assignee PATCH도 게이트, (e) EDIT 가능 그룹 멤버 통과. 실패: 게이트 없음(전부 통과).

**GREEN**:
- `IssueApplicationService.updateIssue`(+ changeAssignee) — patch에 포함된 변경 필드 집합 계산(기존값과 비교, no-op 제외) → `editableFields(actor, projectId, changed)` 호출 → 변경 필드 ⊄ editable 이면 403(ResponseStatusException FORBIDDEN). customFields는 `mergeCustomFieldsAndValidate` 전에 변경 키 게이트.

**REFACTOR**: 변경 감지 + 게이트 헬퍼 분리 + KDoc.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueFieldEditGateTest"`

---

## Plan 메타

- task 수: 8 (PR-A 백엔드. PR-B 프론트+E2E는 후속)
- 모듈: shared-kernel(T1) · identity-access(T2~T5) · issue-tracking(T6~T8)
- 예상 wave(코드 의존성 + Gradle 모듈 컴파일 직렬화 — learnings: bts-plan-wave-gradle-module-compile):
  - wave1: T1(shared-kernel, 다운스트림 선행), T2(db, 독립)
  - wave2: T3, T6
  - wave3: T4, T5, T7
  - wave4: T8
  - (bts-impl이 depends-on + files 교집합으로 최종 wave 계산)
- TDD 강제: yes (전 task RED→GREEN→REFACTOR)
- prod 통합테스트 ground-truth: T4/T5(identity prod 거부 실측, non-prod 마스킹 금지)
- 추가 검증: 3모듈 ktlint+detekt(--rerun-tasks, learnings: backend-detekt-lint-debt-unmasked) + verify-master-plan.sh + FR 전수 동기화

## 리뷰 결과 (← /bts-review-plan 채움)
