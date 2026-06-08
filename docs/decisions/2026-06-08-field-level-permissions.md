# 필드 수준 권한 — 그룹 기반 프로젝트별 규칙 + 직렬화 필터/편집 거부 (FR-PM-07)

> 상태: 채택 | 날짜: 2026-06-08 | 영역: identity-access (권한) + issue-tracking (시행) | 관련 FR: FR-PM-07
> 선행: FR-PM-09(사용자 그룹) · FR-IS-10(커스텀 필드) · FR-PM-02(권한 스킴) · FR-PM-06(보안 수준, 패턴 선례)
> 후행: 없음

## 맥락

SDD §12.5는 필드 수준 권한을 예시로만 남겨 두었다.

```yaml
field_security:
  - field: salary_impact     # 커스텀 필드
    visible_to: [HR, Manager]
    editable_by: [HR]
```

즉 "특정 필드(코어 + 커스텀)를 특정 단위만 보거나 편집"하는 모델이 필요하나, 단위(역할 축)·그릇 구조·시행 지점이 모두 미정이었다. plan §4.7 D3는 `field_permissions(field_name, role, visibility)` 단순 테이블을 초안으로 두었다.

선행 인프라 실재 확인(코드 grep).
- 사용자 그룹 — `user_groups`/`group_memberships`(V015, FR-PM-09), `UserGroupRepository.isMemberOf(groupId, actorId)` 실재.
- 권한 판정 패턴 — shared-kernel 포트 + identity-access `@Profile("prod")` resolver + `@Profile("!prod")` AlwaysAllow stub + 멤버십 게이트 + `PermissionSchemeRepository.roleHasPermission` 매트릭스. FR-PM-02~06·FR-IS-10이 모두 이 패턴.
- 이슈 직렬화 — `IssueResponse.from(issue, ...)`(adapter/inbound/rest/IssueResponse.kt:117)가 코어 필드 + `customFields: Map<String, Any?>`를 채움. 단건·목록 모두 `IssueRepository`에서 fetch.
- 이슈 편집 — `PATCH /api/v1/issues/{key}` → `UpdateIssueRequest` → `IssueApplicationService.updateIssue()`. customFields는 `mergeCustomFieldsAndValidate()`로 키 단위 병합. 별도 `PATCH .../assignee`, `.../components`도 존재.
- 커스텀 필드 정의 — `CustomFieldDefinition`(key 불변, project_id 보유), `custom_field_definitions` 테이블(issue-tracking).

2026-06-08 Maxi 도메인 결정(AskUserQuestion 3종).

## 결정

**그룹 기반 + 프로젝트별 단순 테이블 + 열람/편집 둘 다 모델을 채택한다.**

### D1. 역할 축 = 사용자 그룹 (FR-PM-09)

필드별 열람/편집 단위는 **사용자 그룹**(`user_groups`)이다. SDD의 `visible_to: [HR, Manager]`를 그룹으로 직역. ProjectRole 2종은 "HR팀만" 같은 세분화 불가라 기각, 멤버 5타입 다형은 필드 권한에 과한 복잡도(보고자만 보는 필드 등은 드묾)라 기각. FR-PM-06 보안수준이 멤버 5타입을 쓴 것과 달리, 필드 권한은 그룹 단일 축으로 단순화한다.

### D2. 그릇 = 프로젝트별 단순 테이블

스킴 계층(FR-PM-02/06식 "스킴→여러 프로젝트 공유") 미채택. 필드 권한은 프로젝트 간 공유 수요가 적어 과설계 위험. 커스텀 필드(FR-IS-10)가 프로젝트별 직접 적용한 선례를 따른다.

```sql
-- identity-access V018 (권한은 identity-access 핵심 역량 — FR-PM-02/06 동일)
field_permissions(
  id            UUID PK,
  project_id    UUID,                    -- cross-BC, FK 없음(BTS 관례). ProjectDirectory로 해석
  field_key     VARCHAR(64),             -- 코어 필드명 또는 커스텀 필드 key. cross-BC라 FK 없음
  group_id      UUID FK → user_groups(id) ON DELETE CASCADE,
  access_level  VARCHAR CHECK(access_level IN ('VIEW','EDIT')),
  created_at, updated_at,
  UNIQUE(project_id, field_key, group_id, access_level)
)
```

- **EDIT ⊃ VIEW 함의** — EDIT 권한이 부여된 그룹은 자동으로 열람도 가능(편집하려면 봐야 함). 시행 시 editable 집합은 visible 집합의 부분집합으로 강제.
- **field_key** — 코어 필드(예: `summary`, `description`, `priority`, `assigneeId`, `environment`, `impact`, `labels`)와 커스텀 필드 key를 같은 문자열 축으로 통합. 불변 식별자(`key`, `id`, `reporterId`, `createdAt` 등)는 권한 대상에서 제외(spec에서 보호 필드 목록 확정).

### D3. 기본 동작 = opt-in 제한 (규칙 없는 필드는 자유)

필드에 권한 규칙(`field_permissions` 행)이 **하나도 없으면** 기존 동작 그대로(모두 열람/편집). 규칙이 **하나라도 있으면** 그 필드는 제한 모드로 전환 — 지정된 그룹 멤버만 열람(VIEW/EDIT 행), 편집은 EDIT 행 그룹 멤버만. 대다수 필드는 무규칙이라 하위호환 유지. Jira field-level security·SDD 의도(`salary_impact`만 제한)에 부합.

### D4. 관리자 우회 없음 (값 격리)

필드 권한 규칙이 있는 필드는 지정 그룹 멤버가 **유일한 통과 경로**. PROJECT_ADMIN/SYSTEM_ADMIN도 그룹 멤버가 아니면 값을 못 본다(FR-PM-06 "관리자 우회 없음" 일관, "급여 영향도는 HR만"의 진짜 격리). 단 규칙 자체의 CRUD(누가 보는지 설정)는 관리 권한 보유자가 수행 — 값 열람과 규칙 관리는 분리.

### D5. 관리 권한 = MANAGE_FIELD_PERMISSIONS 신규 권한코드

규칙 CRUD는 신규 권한코드 `MANAGE_FIELD_PERMISSIONS`(PROJECT_ADMIN 기본 시드). MANAGE_CUSTOM_FIELDS(필드 *정의*) 재사용 대신 별도 — 필드 정의와 필드 *권한*은 다른 관심사. V018에서 role_permissions 기본 스킴 PROJECT_ADMIN 행 추가, `PermissionSchemaMigrationTest` 카운트 14→15.

### D6. 시행 지점 = issue-tracking, 판정 = identity-access 포트

- **읽기 필터** — `IssueResponse.from()` 직렬화 시 actor가 열람 불가한 코어/커스텀 필드를 응답에서 제거. 단건·목록 양 경로.
- **쓰기 거부** — `updateIssue()`(및 customFields 병합) 시 actor가 편집 불가한 필드 변경 시도를 거부(403). 미변경(기존 값과 동일) 요청은 통과.
- **cross-BC 포트** — shared-kernel `FieldPermissionResolver`(신규). identity-access prod 구현이 actor의 그룹 멤버십 + `field_permissions` 규칙으로 visible/editable 필드 집합 계산. non-prod AlwaysAllow stub(전 필드 visible/editable). N+1 회피 위해 배치 시그니처:
  - `visibleFields(actorId, projectId, candidateKeys): Set<String>`
  - `editableFields(actorId, projectId, candidateKeys): Set<String>`

### D7. cross-BC PR 구조

권한 규칙 테이블·도메인·Repository·관리 API·권한코드 시드·판정 prod 구현 = identity-access. 직렬화 필터·편집 거부·포트 소비 = issue-tracking. 프론트(D6 숨김 필드 렌더 차단)·E2E(D7). FR-PM-06(PR-A/PR-B)·FR-IS-10(권한 결선 cross-BC) 선례. **PR 분할 여부는 plan 단계에서 확정**(규모 따라 1 PR 또는 2 PR).

## 대안과 기각 사유

- **역할 축 = ProjectRole 2종** — 가장 단순하나 "HR팀만" 세분화 불가, SDD 의도 미달. 기각.
- **역할 축 = 멤버 5타입 다형(FR-PM-06식)** — 가장 강력하나 필드 권한엔 과한 복잡도(REPORTER/ASSIGNEE 동적 역할은 필드 단위에서 드묾). 기각.
- **스킴 계층(FR-PM-02/06식)** — 일관성은 높으나 필드 권한 공유 수요 적어 과설계. 기각.
- **deny-by-default(규칙 없으면 아무도 못 봄)** — 모든 필드에 규칙 강제, 하위호환 깨짐. 기각(D3 opt-in 채택).
- **field_permissions를 issue-tracking에 배치** — 권한은 identity-access 핵심 역량(FR-PM-02/06 모두 권한 테이블 identity-access). group_id FK도 identity-access. 기각.
- **관리자 우회 허용** — 민감 데이터(급여 등) 격리 약화. 기각(D4).

## 영향

- 신규 테이블 1종(identity-access V018): `field_permissions`. group_id FK → user_groups CASCADE.
- 신규 권한코드 `MANAGE_FIELD_PERMISSIONS`(role_permissions 기본 스킴 PROJECT_ADMIN 시드). `PermissionSchemaMigrationTest` 14→15(learnings: fr-pm-permission-seed-migration-test-coupling, enum-add-breaks-crossmodule-count-guard — 전 모듈 카운트 가드 grep).
- 신규 shared-kernel 포트 `FieldPermissionResolver` + identity-access prod 구현 + non-prod AlwaysAllow stub.
- issue-tracking 직렬화(`IssueResponse.from`)·편집(`updateIssue`) 경로에 필드 필터/거부 결선.
- 보호 필드 목록(권한 대상 코어 필드 vs 불변 제외 필드) spec에서 확정.
- 프론트(숨김 필드 렌더 차단) + E2E.
- SDD §12.5 재작성. §12.3에 `MANAGE_FIELD_PERMISSIONS` 추가.
- FR/범위 변경 전수 동기화(CLAUDE.md §명세/범위 변경): fr-index·SDD(02 + §12)·product/identity-access(§4.7 D단계)·README·CLAUDE 카운트. FR 총수는 불변(FR-PM-07은 기존 등록 FR).
