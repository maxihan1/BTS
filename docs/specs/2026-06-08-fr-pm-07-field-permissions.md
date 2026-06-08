# FR-PM-07 필드 수준 권한 — 스펙

> slug: fr-pm-07 | type: auth | BC: identity-access(권한) + issue-tracking(시행)
> ADR: [2026-06-08-field-level-permissions](../decisions/2026-06-08-field-level-permissions.md)
> 작성: 2026-06-08 | 방식: 직접 기술 스펙(도메인 결정 확정·패턴 명확 — office-hours 스킵)

## 1. 개요

이슈의 특정 필드(코어 + 커스텀)를 특정 **사용자 그룹**만 열람/편집하게 제어한다. 규칙이 설정된 필드는 지정 그룹 멤버만 통과(관리자 우회 없음). 규칙이 없는 필드는 기존 동작(자유). 예: "급여 영향도(커스텀 필드)는 HR 그룹만 열람, 편집도 HR만".

## 2. 사용자 시나리오 (Given-When-Then)

- **S1 (커스텀 필드 열람 차단)**. Given 프로젝트에 `salary_impact` 커스텀 필드 + "VIEW=HR 그룹" 규칙, When HR 그룹이 아닌 사용자가 이슈 조회, Then 응답 `customFields`에서 `salary_impact` 키가 제외되고 `restrictedFields`에 명시.
- **S2 (커스텀 필드 열람 허용)**. Given 같은 규칙, When HR 그룹 멤버가 조회, Then `customFields.salary_impact` 값이 정상 노출.
- **S3 (편집 차단)**. Given `description`에 "EDIT=보안팀 그룹" 규칙, When 보안팀이 아닌 사용자가 `PATCH /issues/{key}`로 description 변경 시도, Then 403 거부.
- **S4 (편집 no-op 통과)**. Given 같은 규칙, When 편집 권한 없는 사용자가 description을 **기존과 동일 값**으로 PATCH, Then 통과(변경 없음).
- **S5 (관리자 우회 없음)**. Given `salary_impact`에 "VIEW=HR" 규칙, When PROJECT_ADMIN이지만 HR 그룹 비멤버가 조회, Then `salary_impact` 제외(관리자도 못 봄).
- **S6 (규칙 관리)**. Given MANAGE_FIELD_PERMISSIONS 보유 PROJECT_ADMIN, When `POST /projects/{key}/field-permissions`로 규칙 추가, Then 201. 비보유자는 403.
- **S7 (EDIT ⊃ VIEW)**. Given `salary_impact`에 "EDIT=HR" 규칙만(VIEW 행 없음), When HR 멤버 조회, Then 열람 가능(EDIT가 VIEW 함의).
- **S8 (프론트 렌더 차단)**. Given 응답 `restrictedFields=["salary_impact"]`, When 이슈 상세/목록 렌더, Then 해당 필드 UI 미표시. 편집 불가 필드는 입력 컨트롤 비활성/숨김.

## 3. 기능 요구사항 (FR)

- **F1**. 프로젝트별 필드 권한 규칙 CRUD(`field_permissions`). 한 규칙 = (project_id, field_kind, field_key, group_id, access_level).
- **F2**. `access_level ∈ {VIEW, EDIT}`. EDIT는 VIEW를 함의(시행 시 editable ⊆ visible 강제).
- **F3**. 규칙 관리 권한 = `MANAGE_FIELD_PERMISSIONS`(PROJECT_ADMIN 기본 시드). 비보유 403.
- **F4 (열람 시행)**. 이슈 조회 응답에서 actor가 VIEW 불가한 필드를 마스킹.
  - 커스텀 필드 — `customFields` 맵에서 키 제거.
  - nullable 코어 필드(description·environment·impact·assigneeId·labels) — null/빈 컬렉션으로 마스킹.
  - 마스킹된 필드 key를 `restrictedFields: List<String>`로 응답에 포함(프론트 차단용).
  - 단건·목록 양 경로 적용.
- **F5 (편집 시행)**. `PATCH /issues/{key}`(및 customFields 병합)에서 actor가 EDIT 불가한 필드를 **변경**하려 하면 403. 기존 값과 동일(no-op)이면 통과.
- **F6**. 규칙 없는 필드는 제한 없음(opt-in). 대다수 필드 하위호환.
- **F7**. 관리자 우회 없음 — 규칙 지정 그룹 멤버가 유일 통과 경로(F4/F5 모두).

### 3.1 보호 대상 필드 매트릭스

| 필드 | field_kind | field_key | 열람 제어 | 편집 제어 | 비고 |
|---|---|---|---|---|---|
| 제목 | CORE | `summary` | ✗(non-null) | ✓ | 항상 노출, 편집만 제한 |
| 우선순위 | CORE | `priority` | ✗(non-null) | ✓ | 항상 노출, 편집만 제한 |
| 본문 | CORE | `description` | ✓ | ✓ | null 마스킹 |
| 환경 | CORE | `environment` | ✓ | ✓ | null 마스킹 |
| 영향도 | CORE | `impact` | ✓ | ✓ | null 마스킹 |
| 담당자 | CORE | `assigneeId` | ✓ | ✓ | null 마스킹. 편집은 `PATCH .../assignee`도 게이트 |
| 라벨 | CORE | `labels` | ✓ | ✓ | 빈 리스트 마스킹 |
| 커스텀 필드 | CUSTOM | `<정의 key>` | ✓ | ✓ | 맵 키 제거 마스킹 |

**제외 필드**(권한 대상 아님): `id`·`key`·`projectId`·`typeId`·`reporterId`·`currentStateKey`·`version`·`createdAt`·`updatedAt`·`deletedAt`(시스템/식별/불변), `resolutionId`(전이 통제), `componentIds`(MANAGE_COMPONENTS 별도), `securityLevelId`(SET_ISSUE_SECURITY 별도 — 이중 통제 회피).

## 4. API 인터페이스 (REST)

### identity-access — 규칙 관리 (MANAGE_FIELD_PERMISSIONS 게이트)

- `GET /api/v1/projects/{projectIdOrKey}/field-permissions` — 프로젝트 전체 규칙 목록(필드별·그룹별·access_level). 응답 `[{id, fieldKind, fieldKey, groupId, groupName, accessLevel}]`.
- `POST /api/v1/projects/{projectIdOrKey}/field-permissions` — 규칙 추가. body `{fieldKind, fieldKey, groupId, accessLevel}`. 멱등(ON CONFLICT DO NOTHING). 201. 코어 field_key는 화이트리스트 검증(3.1 표), 위반 시 422. groupId 미존재 시 422.
- `DELETE /api/v1/projects/{projectIdOrKey}/field-permissions/{id}` — 규칙 삭제. 204.

### issue-tracking — 시행(기존 엔드포인트 확장, 신규 엔드포인트 없음)

- `GET /api/v1/issues/{key}`, `GET /api/v1/issues?projectKey=` — 응답에 `restrictedFields` 추가 + 마스킹.
- `PATCH /api/v1/issues/{key}`, `PATCH /api/v1/issues/{key}/assignee` — EDIT 게이트.

## 5. 데이터 모델 변경

```sql
-- identity-access V018
CREATE TABLE field_permissions (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id   UUID NOT NULL,                                   -- cross-BC, FK 없음
  field_kind   VARCHAR(8)  NOT NULL CHECK (field_kind IN ('CORE','CUSTOM')),
  field_key    VARCHAR(64) NOT NULL,
  group_id     UUID NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
  access_level VARCHAR(8)  NOT NULL CHECK (access_level IN ('VIEW','EDIT')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, field_kind, field_key, group_id, access_level)
);
CREATE INDEX idx_field_permissions_project ON field_permissions (project_id);

-- role_permissions 기본 스킴 PROJECT_ADMIN 행 추가
INSERT INTO role_permissions (id, scheme_id, role, permission_code)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_FIELD_PERMISSIONS');
```

- `PermissionSchemaMigrationTest` 카운트 14→15 (PROJECT_ADMIN 10→11). MANAGE_FIELD_PERMISSIONS PROJECT_ADMIN 보유 검증 추가.
- identity-access는 jOOQ 미사용(JdbcTemplate) — init_codegen.sql 미러 불요.

## 6. cross-BC 포트

```kotlin
// shared-kernel — com.bts.shared.permission.FieldPermissionResolver
interface FieldPermissionResolver {
    /** actor가 열람 가능한 필드 key 집합(candidate 중). 규칙 없는 key는 항상 포함. */
    fun visibleFields(actorId: UUID, projectId: UUID, candidates: Set<FieldRef>): Set<FieldRef>
    /** actor가 편집 가능한 필드 key 집합(candidate 중). 규칙 없는 key는 항상 포함. EDIT⊃VIEW. */
    fun editableFields(actorId: UUID, projectId: UUID, candidates: Set<FieldRef>): Set<FieldRef>
}
data class FieldRef(val kind: FieldKind, val key: String)
enum class FieldKind { CORE, CUSTOM }
```

- prod 구현 `IdentityAccessFieldPermissionResolver`(@Profile prod) — actor 그룹 멤버십(`UserGroupRepository`) + `field_permissions` 규칙으로 집합 계산.
- non-prod `AlwaysAllowFieldPermissionResolver`(@Profile !prod) — candidates 그대로 반환(전 필드 허용). issue-tracking 통합테스트 부팅 보장.
- issue-tracking이 포트 소비(`IssueResponse.from` 직렬화 + `updateIssue` 게이트).

## 7. 엣지 케이스

- **EC1**. 규칙 0건 필드 → 제한 없음(F6).
- **EC2**. actor 다중 그룹 소속, 일부만 VIEW → 열람 가능(그룹 OR).
- **EC3**. EDIT 행만 있고 VIEW 행 없음 → 열람·편집 모두 가능(EDIT⊃VIEW, S7).
- **EC4**. 편집 불가 필드 no-op PATCH(기존값 동일) → 통과(S4).
- **EC5**. 편집 불가 필드 값 변경 PATCH → 403(S3).
- **EC6**. 열람·편집 불가 커스텀 필드를 PATCH로 설정 시도 → 403.
- **EC7**. 그룹 삭제 → `field_permissions` CASCADE 삭제.
- **EC8**. 커스텀 필드 정의 소프트삭제 → 해당 규칙 orphan 잔류(무해, 키 미출현). 정리 불요.
- **EC9**. 비멤버 actor → VIEW_ISSUE 미통과로 이슈 자체 404(필드 권한 도달 전). 필드 권한은 이슈 열람 가능 actor에만 의미.
- **EC10**. SYSTEM_ADMIN/PROJECT_ADMIN이나 그룹 비멤버 → 제한 필드 못 봄(F7, S5).
- **EC11**. MANAGE_FIELD_PERMISSIONS 미보유 규칙 CRUD → 403(S6).
- **EC12**. 미존재 group_id 규칙 생성 → 422.
- **EC13**. 미정의 코어 field_key → 화이트리스트 위반 422. 커스텀 key는 형식만 검증(존재 확인 생략, orphan 허용 — EC8 일관).
- **EC14**. 목록 조회 N+1 — 규칙셋·actor 그룹은 페이지당 1회 조회 후 메모리 판정(페이지 내 이슈 공통). NFR 가드오버헤드 10ms 준수.

## 8. 비기능 요구사항 (NFR)

- 권한 가드 오버헤드 ≤10ms(identity-access NFR). 필드 필터는 조회당 규칙 1회 + actor 그룹 1회 조회, 필드별 판정은 메모리.
- prod 거부가 ground-truth — 통합테스트 @ActiveProfiles("prod")로 실제 차단 검증(non-prod AlwaysAllow 마스킹 금지).
- 보안 경계는 백엔드(F4/F5). 프론트(S8)는 UX 힌트일 뿐 — 숨김 필드라도 백엔드가 마스킹/거부.

## 9. 측정 가능한 완료 기준

- [ ] V018 마이그레이션 + `field_permissions` 테이블 + MANAGE_FIELD_PERMISSIONS 시드.
- [ ] `PermissionSchemaMigrationTest` 14→15 + MANAGE_FIELD_PERMISSIONS 보유 검증.
- [ ] shared-kernel `FieldPermissionResolver` 포트 + prod 구현 + non-prod stub.
- [ ] 규칙 CRUD API 3종 + MANAGE_FIELD_PERMISSIONS 게이트(prod 통합테스트 S6).
- [ ] 이슈 응답 열람 마스킹 + `restrictedFields`(단건·목록, S1/S2/S5/S7 prod 통합).
- [ ] 이슈 편집 EDIT 게이트(S3/S4, no-op 통과).
- [ ] 프론트 숨김 필드 렌더 차단 + 편집 불가 컨트롤 비활성(S8).
- [ ] E2E — 열람 차단/허용 + 편집 차단 시나리오.
- [ ] 3모듈(identity-access·issue-tracking·shared-kernel) test+ktlint+detekt 그린, 프론트 test/typecheck/lint/build + E2E 그린, 회귀 0.
- [ ] FR 전수 동기화(fr-index·SDD·product·README·CLAUDE) + verify-master-plan.sh 통과.

## Brainstorming Check

다음 gap을 자체 sanity check로 점검·해소했다.
- **코어 필드 마스킹의 타입 문제** — non-null `summary`/`priority`는 열람 제어 불가(null 불가) → 편집 제어만, 3.1 매트릭스에 명시.
- **field_key 네임스페이스 충돌**(코어 vs 커스텀 동명 key) — `field_kind` 컬럼으로 분리.
- **이중 통제 회피** — securityLevelId(SET_ISSUE_SECURITY)·componentIds(MANAGE_COMPONENTS)는 제외.
- **cross-BC 커스텀 key 검증 불가** — identity-access가 issue-tracking 정의를 못 봄 → 형식만 검증, orphan 허용(EC8/EC13).
- **N+1** — 목록 배치 판정(EC14).
- **관리자 우회 모호성** — F7로 명시(게이트1 Maxi 검토 대상).

✅ 통과 (자체 점검, gap 6건 사전 해소).

---

## PR-B (프론트 D6/D7) 명세 보강 (2026-06-08)

PR-B 착수 시 Maxi 결정 — **편집 불가 필드는 입력칸을 미리 비활성**(§S8 "편집 불가 컨트롤 비활성" 완전 충족). 이를 위해 백엔드 이슈 응답에 편집 가능 정보 추가(PR-A restrictedFields=열람만 제공했음).

### 백엔드 보강 (issue-tracking view layer)
- `IssueResponse`에 `noneditableFields: List<String> = emptyList()` 추가 — actor가 **보이지만 편집 불가**한 필드 key 목록(restrictedFields=열람 불가 숨김과 별개·비중복). ApplicationService가 PR-A `FieldPermissionResolver.editableFields(actor, projectId, visibleCandidates)`로 계산. 단건·목록 양 경로.
- restrictedFields(숨김) ⊇ 우선 — restrictedFields에 든 키는 noneditableFields에 중복 포함 안 함(이미 안 보임).

### 프론트 (D6)
- IssueResponse Zod에 `restrictedFields: string[]` + `noneditableFields: string[]` 추가(백엔드 계약 정합).
- 이슈 화면 — restrictedFields 키는 렌더 차단(숨김), noneditableFields 키는 입력 컨트롤 `disabled`. 기존 `useIssuePermissions`(UPDATE) 게이팅과 AND.
- 규칙 관리 화면 — 프로젝트 설정 `/projects/{key}/settings/field-permissions`. 커스텀 필드 관리(FR-IS-10 #98) 선례 복제. 규칙 = `{fieldKind, fieldKey, groupId, accessLevel}`. 그룹 선택 드롭다운(그룹 목록 조회 client 신규). MANAGE_FIELD_PERMISSIONS 게이팅(useProjectPermissions 확장).
- API 계약(백엔드 PR-A): `GET/POST/DELETE /api/v1/projects/{key}/field-permissions`. 응답 `FieldPermissionResponse{id, fieldKind, fieldKey, groupId, groupName, accessLevel}`.

### E2E (D7)
- 규칙 생성/삭제(그룹×필드×VIEW/EDIT) + 이슈 화면 제한 필드 숨김/비활성 시나리오.
