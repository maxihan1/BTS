# 프로젝트 소유 워크플로우 — PROJECT_ADMIN 이 자기 프로젝트의 워크플로우·스킴을 만들고 고친다

FR-WF-02 · FR-WF-04 · FR-PM-04 범위 확장. Jira Cloud team-managed 프로젝트 모델 채택.

## 1. 문제

Maxi 요구 3건.

1. 프로젝트 어드민도 워크플로우 생성 권한을 가져야 한다.
2. 그 대상은 **「프로젝트의」 워크플로우 스킴**이다 — 전역 자원이 아니다.
3. 프로젝트 설정 페이지에서 Jira Cloud 처럼 워크플로우를 **수정·저장**할 수 있어야 한다.

현재는 워크플로우 정의·스킴 CRUD 가 전부 SYSTEM_ADMIN 전용이고, 편집 화면도 `/admin/*` 관리 허브 아래에만 있다.

## 2. 지금 상태 — 배관은 이미 깔려 있다

조사 결과 권한 판정 계층은 **이미 프로젝트 스코프를 지원한다.** 빠진 것은 소유 컬럼 하나다.

| 조각 | 상태 | 근거 |
|---|---|---|
| `PROJECT_ADMIN` 에 `MANAGE_WORKFLOW` 부여 | 이미 됨 | `V013__manage_workflow_permission.sql:16` |
| `MANAGE_SCHEME` → `MANAGE_WORKFLOW` 매핑 | 이미 됨 | `IdentityAccessWorkflowSchemePermissionResolver.kt:98-100` |
| `WorkflowSchemeScope.Project` 판정(멤버십 + 매트릭스) | 이미 동작 | 같은 파일 `hasProjectPermission()` |
| `WorkflowEditorPage` 재사용성 | 자립 컴포넌트 | `workflowKey` 만 받는다. 라우트 어댑터는 32줄 껍데기 |

**막는 것.** `WorkflowSchemeController` 가 스킴 CRUD 에서 항상 `WorkflowSchemeScope.Global` 을 넘긴다(85·112·137·163·194·220·258행). Global 분기는 매트릭스를 건너뛰고 `isSystemAdmin` 만 본다. 그렇게 할 수밖에 없는 이유가 **스킴에 소유 프로젝트가 없어서 어느 프로젝트 스코프로 물을지 알 수 없다**는 것이다.

```
workflow_schemes (V201:24-30)  id · key · name · description · is_default · deleted_at
workflows        (V200:5-12)   id · key · name · description · created_at · updated_at
                                       ↑ 둘 다 project_id 없음
```

`WorkflowDefinitionPermissionResolver` 는 스코프 개념 자체가 없다 — `isSystemAdmin` 한 축이다(`IdentityAccessWorkflowDefinitionPermissionResolver.kt:38`).

V013 주석이 그 설계를 명시한다. 「워크플로우 스킴 CRUD(MANAGE_SCHEME/Global)는 시스템 관리자 전용」. **이 스펙은 그 결정을 뒤집는다.**

## Jira 대조

2026-09-08 실물 조회. **조회가 설계를 확증했고, 한 가지를 정정했다.**

| BTS 항목 | Jira Cloud 실물 | 출처 | 채택 |
|---|---|---|---|
| 프로젝트 어드민이 워크플로우를 편집 | team-managed 는 **「Role: Administrator」**가 워크플로우 관리 권한이다 | [support.atlassian.com — Manage how work flows in your team-managed space](https://support.atlassian.com/jira-software-cloud/docs/manage-how-work-flows-in-your-team-managed-project/) | 채택 |
| 편집 진입 경로가 **프로젝트 설정** | `••• → Space settings → Work types → (work type) → Edit workflow` | 위와 같음 | 채택 (Maxi 요구 3 과 일치) |
| 공유 워크플로우는 프로젝트 어드민이 못 고친다 | 「Shared workflows can't be edited by users with the 'Edit Workflows' permission unless they have been assigned the **Administer Jira** global permission.」 | [support.atlassian.com — Use the Edit Workflows permission in company-managed projects](https://support.atlassian.com/jira/kb/use-the-edit-workflows-permission-in-company-managed-projects-in-jira-cloud/) | 채택 → §3 의 `project_id IS NULL`(공유·전역) = SYSTEM_ADMIN 전용 |
| 전역 템플릿을 복제해 프로젝트 소유로 만든다 | Atlassian 권장 우회 그대로다 — 「administrators can **duplicate the workflow and assign the copy exclusively to their project**, which then allows editing by users with the Edit Workflows permission」 | 위와 같음 | 채택 → `POST /workflows/{key}/duplicate` 재사용 |

### ★정정 — 「편집 가능 여부」의 판정 기준은 역할이 아니라 **공유 여부**다

처음에는 「PROJECT_ADMIN 이면 편집 가능」으로 적었는데, Jira 의 실제 규칙은 **「그 워크플로우가 다른 프로젝트와 공유되지 않았을 때만」**이다. 역할은 그 다음 조건이다.

이 스펙의 `project_id` 모델이 그 규칙과 **정확히 겹친다** — `project_id` 가 값이면 비공유(그 프로젝트 전용)라 프로젝트 어드민이 고칠 수 있고, `NULL` 이면 전 프로젝트 공유라 SYSTEM_ADMIN 전용이다. 즉 이 설계는 Jira 를 흉내 낸 것이 아니라 **같은 불변식에 도달한 것**이고, 그래서 §4 D4(전역 자원은 읽고 복제만)가 임의 제약이 아니라 근거 있는 제약이 된다.

### 대응 없음

- **워크플로우 스킴** 자체는 team-managed 에 대응 개념이 없다(그쪽은 work type 이 워크플로우를 직접 가리킨다). BTS 는 company-managed 의 스킴 구조를 유지하되 소유만 프로젝트로 내린다 — 기존 FR-WF-02 구조를 버리지 않기 위한 선택이며, Jira 두 모드의 절충이다.

## 3. 채택 모델 — nullable `project_id`

`workflows` · `workflow_schemes` 에 `project_id UUID NULL` 을 추가한다.

| `project_id` | 의미 | 만들 수 있는 사람 | 보이는 범위 |
|---|---|---|---|
| `NULL` | 전역 표준 템플릿 (V201 시드 4건 · V200 워크플로우) | SYSTEM_ADMIN | 전 프로젝트 |
| 값 있음 | 그 프로젝트 소유 | 그 프로젝트의 PROJECT_ADMIN + SYSTEM_ADMIN | 그 프로젝트만 |

Jira Cloud 대응 — company-managed 는 워크플로우가 전역이고 Jira 관리자만 만든다. team-managed 는 프로젝트가 소유한다. 이 스펙은 **두 모드를 한 테이블에 공존**시킨다(NULL = company-managed 템플릿, 값 = team-managed).

### 왜 별도 테이블이 아닌가

전역·프로젝트 워크플로우는 **같은 것**이다 — 상태·전환·초안·발행 구조가 동일하고, 프로젝트 워크플로우는 전역 템플릿을 복제해 만드는 것이 주 경로다(`POST /workflows/{key}/duplicate` 가 이미 있다). 테이블을 가르면 스킴 매핑·이슈 전환·이관 마법사가 전부 두 갈래가 된다.

## 4. 결정 사항 (D)

### D1. `key` UNIQUE 를 `(project_id, key)` 부분 유니크로 바꾼다

현재 `workflows.key TEXT NOT NULL UNIQUE`. 프로젝트마다 `my-flow` 를 만들면 두 번째부터 충돌한다.

- 전역(`project_id IS NULL`): `key` 단독 유니크 유지 — 기존 참조(`WorkflowKeyResolver` · 스킴 매핑 · 시드)가 그대로 산다.
- 프로젝트 소유: `(project_id, key)` 유니크.

`V206__soft_delete_partial_unique_keys.sql` 이 이미 부분 유니크 인덱스 관례를 만들어 뒀다 — 그 형태를 승계한다.

### D2. URL 경로에 워크플로우 키만 쓰던 자리를 프로젝트 스코프로 좁힌다

전역 `GET /api/v1/workflows/{key}` 는 유지하되, 프로젝트 소유분은 `GET /api/v1/projects/{projectKey}/workflows/{key}` 로 연다. 키가 프로젝트 간 중복 가능해지므로 **키 단독으로는 더 이상 워크플로우를 특정할 수 없다.**

★이 항목이 이 스펙에서 가장 넓게 번지는 변경이다. `WorkflowKeyResolver`(shared-kernel)를 쓰는 전 호출부를 열거해야 한다 — 그 열거를 판별식으로 고정한다(§6).

### D3. 편집 UI 는 새로 만들지 않는다

`WorkflowEditorPage` · `WorkflowSchemeSidebar` · `MappingTable` · `SchemeMetaPanel` 을 그대로 마운트한다. 프로젝트 설정 라우트는 `/admin/*` 어댑터와 같은 두께(30~40줄)여야 한다.

**금지.** 편집기 컴포넌트를 복제하거나 프로젝트용 분기를 그 안에 넣지 않는다. 프로젝트 스코프는 **props 로 내려간 키**로만 표현한다.

### D4. 표준 스킴·전역 워크플로우 보호는 그대로 둔다

`is_default=true` 스킴의 이름·설명·삭제 잠금(`WorkflowSchemeApplicationService.kt:182,225`)은 유지한다. PROJECT_ADMIN 도 전역 자원은 **읽고 복제만** 한다.

### D5. 이미 배정된 전역 스킴은 건드리지 않는다

기존 프로젝트는 전부 전역 스킴을 배정받아 있다(`software-scheme` 자동 배정). 마이그레이션은 **데이터를 옮기지 않는다** — 컬럼만 추가하고 전부 `NULL`(전역)로 남긴다. 프로젝트 소유 워크플로우는 사용자가 만들 때 처음 생긴다.

## 5. 범위 밖 (명시)

- 전역 워크플로우를 프로젝트 소유로 「이전」하는 기능. 필요해지면 별건.
- 프로젝트 간 워크플로우 공유·복사 UI. `duplicate` API 재사용으로 충분한지 먼저 본다.
- 워크플로우 정의의 **삭제** 권한을 PROJECT_ADMIN 에게 주는 것. 이번엔 생성·수정만 연다(§4 D6 참조).

### D6. PROJECT_ADMIN 에게 여는 권한 범위

`WorkflowDefinitionPermission` 기준.

| 권한 | PROJECT_ADMIN (자기 프로젝트 소유분) | 근거 |
|---|---|---|
| `CREATE` | 허용 | Maxi 요구 1 |
| `UPDATE` | 허용 | Maxi 요구 3 (수정·저장) |
| `PUBLISH` | 허용 | 저장이 곧 발행 흐름이다 — 초안만 만들고 못 켜면 반쪽이다 |
| `DELETE` | **불허** (SYSTEM_ADMIN 유지) | 이슈가 물려 있는 워크플로우 삭제는 파급이 크다. 요구에 없다 |

`WorkflowSchemePermission` 기준 — `MANAGE_SCHEME` 은 자기 프로젝트 소유 스킴에 한해 허용. 전역 스킴은 불허.

## 6. 회귀 판별식 (필수)

이 스펙이 만드는 **두 목록**을 서로 검사시킨다. 안 하면 이 저장소의 지배 결함 양식을 그대로 재현한다.

1. **스코프 결정 전수** — 스킴·워크플로우 CRUD 엔드포인트 전량이 `project_id` 유무로 스코프를 고르는지. 한 곳이라도 `Global` 하드코딩이 남으면 red.
2. **키 해석 호출부 전수** — `WorkflowKeyResolver` 를 쓰는 전 호출부가 프로젝트 스코프를 넘기는지(전역 의도면 명시적으로 `null`). 「키만으로 찾는」 잔존 호출부를 차집합으로 잡는다.
3. **권한 매트릭스 ↔ 엔드포인트 짝** — §4 D6 표의 각 행이 실제 엔드포인트 판정과 일치하는지. 표만 고치고 코드가 안 따라오는 drift 차단.
4. **비-공허 짝** — 위 3건 각각에 대해 판정을 끊어 red 를 1회 본다.

### 가짜 그린 주의

`is_default` 시드 4건은 전부 `project_id IS NULL` 이라, 프로젝트 스코프 경로를 **한 번도 타지 않고도** 기존 테스트가 전부 초록이다. 프로젝트 소유 픽스처를 만들지 않으면 이 스펙의 신규 경로는 검증 0 회로 머지된다.

## 7. 티어

**T3.** 마이그레이션(2테이블) + shared-kernel(`WorkflowKeyResolver` 계약 변경) + 보안 경로(권한 판정기 2종). ADR + plan + TDD + 마이그레이션 검증 + 리뷰 2종 + ceo 필요.

BC 는 `project-workflow` 주(主), `identity-access` 는 판정기만 종(從). 한 PR = 한 BC 원칙과 충돌하므로 **PR 을 가른다**(plan §분할 참조).
