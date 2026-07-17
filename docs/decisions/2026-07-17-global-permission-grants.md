<!-- 전역 권한 부여(global_permission_grants) 도입 — FR-PM-08 ADR D3 이 예고한 전역 권한 매트릭스 확장 결정 -->

# ADR — 전역 권한 부여 (`global_permission_grants`)

> 날짜: 2026-07-17
> 상태: 채택 (FR-PM-10, project-management-crud PR-1)
> BC: identity-access
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md) (12.3 시스템 권한 — 권한 코드 정본)
> 선행 ADR: [system-admin-role](2026-06-04-system-admin-role.md) (FR-PM-08 — 본 ADR 이 그 D3 을 확장) · [project-membership-model](2026-06-01-project-membership-model.md) (cross-BC FK 생략 · hard delete 선례)
> 스펙/plan: [spec](../specs/2026-07-17-project-management-crud.md) · [plan](../plans/2026-07-17-project-management-crud.md)
> 첫 소비자: FR-PJ-01 (프로젝트 생성 — `CREATE_PROJECT`)

## 맥락

BTS 의 전역 권한은 FR-PM-08(`2026-06-04-system-admin-role.md`)이 세운 **단일 역할 직접 판정**이다. 그 ADR 의 D3 원문.

> 프로젝트 권한처럼 `role_permissions` 매트릭스로 가지 **않는다**. 단일 역할이므로 "SYSTEM_ADMIN 역할을 가졌는가"로 전역 권한을 직접 판정한다.
> (…) 미래에 전역 권한이 세분화되면(예: 감사자는 `VIEW_AUDIT_LOG`만) 그때 전역 매트릭스를 도입한다. 단일 역할 단계에서 매트릭스는 과설계.

FR-PJ-01(프로젝트 생성)이 그 트리거를 당긴다. 프로젝트 생성 권한 `CREATE_PROJECT` 를 SYSTEM_ADMIN 전용으로 두면 사내 1,000명 규모에서 생성이 소수 관리자에 병목한다(Maxi 확정 — plan D7). 그룹/사용자에게 개별 부여하려면 "누가 어떤 전역 권한을 갖는가"를 행으로 저장할 자리가 필요하다.

**본 ADR 은 D3 위반이 아니라 D3 이 예고한 확장의 발동이다.** D3 이 매트릭스를 기각한 근거는 *"단일 역할 단계에서"* 라는 조건절이었고, `CREATE_PROJECT` 도입이 그 조건을 해제한다. SYSTEM_ADMIN 직접 판정은 **폐기되지 않고 판정식 안에 승계**된다 (D-2).

남은 질문은 그 행을 어디에 두는가인데, 기존 두 테이블은 구조적으로 그 자리가 될 수 없다 (D-1).

## 결정

### D-1. 신규 테이블 `global_permission_grants` — 기존 2개는 구조적으로 불가

| 후보 | 불가 사유 | 근거 |
|---|---|---|
| `role_permissions` | `role` 이 `CHECK (role IN ('PROJECT_ADMIN', 'MEMBER'))` 라 **전역 축이 없다**. 전역 권한을 넣으려면 프로젝트 역할에 전역 역할을 섞어야 하는데, 이는 FR-PM-08 **D2 가 이미 기각한 방향**이다 — *"`ProjectRole` enum 에 섞지 않는다. 프로젝트 역할과 전역 역할은 서로 다른 축이다."* | `V008__permission_schemes_and_role_permissions.sql:25` |
| `project_permission_scheme` | `project_id` 가 **PK** 다. `CREATE_PROJECT` 는 **프로젝트가 생기기 전에** 판정해야 하므로 판정 시점에 그 키가 존재하지 않는다 — 행을 넣을 수도 조회할 수도 없다. 권한을 프로젝트 축에 매다는 구조 자체가 "프로젝트를 만들 권한"과 맞지 않는다 | `V008:39` |

스키마는 `system_role_assignments`(FR-PM-08 D1) 동형이다 — "누가 무엇을 갖는가"를 행으로 저장하고, 종류가 늘면 `CHECK` 에 값만 추가한다(구조 불변).

```sql
CREATE TABLE global_permission_grants (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    permission   VARCHAR(64) NOT NULL CHECK (permission IN ('CREATE_PROJECT')),
    grantee_type VARCHAR(16) NOT NULL CHECK (grantee_type IN ('USER','GROUP')),
    grantee_id   UUID        NOT NULL,
    granted_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (permission, grantee_type, grantee_id)
);
```

- **`permission` 에 CHECK 를 거는 것은 의도다** (plan D17). 이 테이블은 BTS 에서 **처음으로 권한 코드를 사용자 입력(REST 바디)으로 받는다**. `role_permissions` 가 `permission_code` 를 무제약으로 둔 것(`V008:26`)은 그 테이블이 **마이그레이션으로만 시드**되기에 성립했던 관례이고, 여기엔 그 전제가 없다. 오타(`CREATE_PROJET`)는 fail-closed 라 권한 상승 사고로는 이어지지 않으나, **아무도 원인을 모르는 쓰레기 grant** 를 남긴다. **DB CHECK + 서비스 400 이중 검증**으로 막는다. 권한 코드 정본은 SDD 12(`docs/sdd/12-permissions.md`) 이며, 코드 추가 시 이 CHECK 도 함께 확장한다.
- **세 컬럼이 모두 `NOT NULL` 인 것이 UNIQUE 멱등성의 전제다.** PostgreSQL UNIQUE 는 기본 `NULLS DISTINCT` 라 nullable 컬럼이 섞이면 같은 값이 중복 삽입된다. 여기엔 NULL 이 없어 안전하다.
- **중복 부여는 409 로 올린다** — `ON CONFLICT DO NOTHING` 으로 조용히 삼키지 않는다. 관리자는 "이미 부여돼 있다"를 알아야 한다.
- 별도 인덱스를 만들지 않는다. `UNIQUE (permission, grantee_type, grantee_id)` 가 정확히 같은 3컬럼·같은 순서의 btree 를 이미 만들고 판정 술어가 그것을 탄다. `V015__user_groups.sql:18-19` 가 세운 기준("복합 PK 선두는 인덱스 자동 생성 — 별도 불요")을 따른다.

### D-2. 전역 판정 = `grant 보유 OR isSystemAdmin`

SYSTEM_ADMIN 은 grant 없이도 모든 전역 권한을 보유한다 (Maxi 확정 — plan D14). 3중 근거.

1. **기존 ADR 연속성.** FR-PM-08 D3 이 정의한 *"전역 판정 = SYSTEM_ADMIN 보유 여부"* 가 **현재 모델 그 자체**다. FR-PM-10 은 그것을 **확장**하는 것이지 대체하는 것이 아니다. `grant OR isSystemAdmin` 이라야 기존 판정 결과가 하나도 뒤집히지 않는다.
2. **부트스트랩.** grant-only 로 두면 빈 DB 에서 `CREATE_PROJECT` 보유자가 0명이고, grant API 자체가 SYSTEM_ADMIN 게이트라 아무도 자신에게 첫 grant 를 줄 수 없다. 관리자의 수동 자기부여 1회를 강제하는 셈인데, 그것은 부트스트랩을 설정으로 해소한 FR-PM-08 D5 의 정신에 어긋난다.
3. **default 와의 포함 관계 (fail-safe 방향).** D-3 의 default 구현은 `= isSystemAdmin` 이고, 이것이 **안전한 상위집합**이 되려면 prod 판정 ⊇ default 여야 한다. prod 가 grant-only 면 grant 보유 비-SYSTEM_ADMIN 이 prod 는 통과하고 default 는 탈락해 포함 관계가 깨진다. `grant OR isSystemAdmin` 이라야 **prod ⊇ default** 가 성립해, 어댑터 override 를 잊었을 때 판정이 **좁아지는(거부하는) 방향**으로 무너진다.

- **판정 불가·미부여·미주입은 전부 `false`** 다. 권한 판정 경로의 기본값은 거부로 수렴한다.
- prod 어댑터 `IdentityAccessSystemPermissionResolver` 는 이 override 를 **반드시 유지해야 한다.** 지우면 인터페이스 default 가 되살아나 판정이 SYSTEM_ADMIN 전용으로 조용히 되돌아간다 — **plan D7 에서 기각된 바로 그 안**이고, fail-closed 라 장애로 드러나지도 않는다. 회귀 가드는 *"grant 보유 비-SYSTEM_ADMIN 이 true 를 받는다"* 단언 하나뿐이며, 이 단언에서 **행위자가 SYSTEM_ADMIN 이 아님을 먼저 못박는 것**이 판별자다. 그 못을 빼면 테스트는 override 유무를 구분하지 못한다.

### D-3. 포트 확장은 default 메서드 — `SystemPermissionResolver.hasGlobalPermission`

shared-kernel 의 공유 인터페이스 `SystemPermissionResolver` 에 `hasGlobalPermission(actorId, permission): Boolean` 을 **default 메서드**로 추가한다. 본문은 `isSystemAdmin(actorId)` 위임.

**abstract 로 추가하면 구현체 6곳이 전부 컴파일 실패한다** (spec §2.7 실측 — plan 초안의 "3곳"은 오류였다).

| # | 구현체 | 위치 |
|---|---|---|
| 1 | `IdentityAccessSystemPermissionResolver` | identity-access **prod** |
| 2 | `NonProdAllowSystemAdminResolver` | issue-tracking **prod (`@Profile("!prod")`)** |
| 3 | `WebhookIntegrationConfig.StubSystemPermissionResolver` | search-export-import test |
| 4 | `TestPermissionConfig` 익명 object | notification test |
| 5 | `Require2faTestPermissionConfig` 익명 object | issue-tracking test |
| 6 | `StubSystemPermissionResolver` | slack-integration test |

- **default 본문이 `isSystemAdmin` 위임인 것도 의도다.** 확장 전 BTS 의 전역 판정이 정확히 그것이었으므로(FR-PM-08 D3), 미override 구현체는 **확장 이전 의미를 그대로 유지**한다. 그리고 D-2 에서 보였듯 그 의미는 prod 판정의 부분집합이라 fail-safe 방향이다.
- **`hasGlobalPermission` 을 abstract 로 바꾸지 않는다.** 공유 인터페이스에 메서드를 추가할 때 fail-safe default 를 두는 것은 BTS 규칙이다. 단 이 규칙은 **기존 인터페이스 확장**에만 적용되며, FR-PJ-01 이 새로 만들 포트(깨질 기존 구현체가 0개)에는 적용되지 않는다 — 그쪽은 fail-closed 를 명시 설계해 abstract 로 간다.

### D-4. `grantee_id` 에 FK 없음 — 다형 참조. 존재 검증은 서비스 층

`grantee_type = 'USER'` 면 `users.id`, `'GROUP'` 이면 `user_groups.id` 를 가리키는 **다형 참조**라 PostgreSQL 이 단일 FK 로 표현할 수 없다. 같은 모듈 안의 참조인데도 FK 를 걸지 않는다는 점에서 `project_memberships.project_id`(`V007:5` 주석 *"cross-BC(issue-tracking projects) 참조, FK 없음 (ADR D2)"*)와 **사유는 다르나 결과가 같은 선례**를 따른다.

**FK 가 없으므로 무결성은 애플리케이션이 진다.** `GlobalPermissionGrantService` 는 `granteeType` 에 따라 `UserRepository`(USER) 또는 `UserGroupRepository`(GROUP) 를 조회해 존재를 검증하고, 미존재면 404 로 거부한다. 이 검증은 **선택이 아니라 FK 생략의 대가**다 — 생략하면 본 결정이 거짓이 된다.

**고아 행의 운명은 grantee 종류마다 다르다. 정확히 기록한다.**

| 경로 | 삭제 시 동작 | 기전 |
|---|---|---|
| GROUP | **판정에서 탈락한다** | 판정 술어의 GROUP 가지가 `group_memberships` 를 경유하고(`grantee_id IN (SELECT gm.group_id FROM group_memberships gm WHERE gm.user_id = :actorId)`), `V015:12-13` 이 양 FK 를 `ON DELETE CASCADE` 로 걸어 그룹 삭제 시 멤버십 행이 사라진다. 남은 grant 행은 아무에게도 매칭되지 않는다 |
| USER | **탈락하지 않는다. 고아 행이 영구 잔존한다** | 판정 술어의 USER 가지는 `g.grantee_type = 'USER' AND g.grantee_id = :actorId` 로 **`users` JOIN 이 아예 없다**. 사용자 삭제와 grant 행 사이에 연결 기전이 없다 |

> 🛑 **"고아 행은 판정에서 자연 탈락한다"고 쓰지 않는다 — USER 경로에서 거짓이기 때문이다.**
> 실제 피해는 제한적이다. UUID 는 재사용되지 않으므로 잔존 행이 타인에게 붙는 권한 상승은 발생하지 않고, 삭제된 사용자는 인증 자체가 불가능하다.
> **문제는 이 ADR 이 앞으로 인용될 정본이라는 점이다.** 거짓 안전 성질을 FK 생략의 근거로 박아 두면 다음 사람이 그것을 믿고 방어를 뺀다.
> **USER 고아 행에 대한 방어는 서비스 층 존재 검증(부여 시점)과 목록 API 노출(사후 발견) 둘뿐이며, 자동 정리 기전은 없다** (잔여 위험 1).

### D-5. `granted_by` 로 부여 흔적을 남긴다 — 회수는 hard delete

**부여 축.** `granted_by`(`users.id`, NOT NULL)에 이 grant 를 부여한 SYSTEM_ADMIN 을 기록한다. 권한 부여 시스템에 *"누가 줬나"* 가 없으면 사후 추적이 불가능하다. 특히 GROUP grant 는 **그룹 멤버십 변경만으로 권한이 전파**되므로, 부여 체인의 시작점을 놓치면 전체가 무기록이 된다. 지금은 컬럼 하나지만 **나중에 붙이면 기존 행이 전부 NULL** 이라 초기 grant — 가장 민감한 것 — 의 출처가 영구 소실된다. FK 는 D-4 정신대로 생략한다.

**회수 축.** `revoke` 는 **hard DELETE** 다 — **Maxi 확정 (2026-07-17, 게이트 2)**.

> 근거 규칙은 **`DATA.md §1` 2번** — *"`DELETE` 는 항상 `WHERE` + 소프트 삭제 우선 — 하드 삭제는 **ADR + Maxi 확인** 필수"*. 본 항목이 ADR 요건을, 게이트 2 승인이 Maxi 확인 요건을 충족한다. 정본 등재는 `DATA.md §3` 하드 삭제 허용 영역.
>
> **초판이 이 규칙을 두 군데 틀리게 인용했다** (codereview data-migration·절대규칙 2중 적발). ①`§1 7번` → §1 은 **5원칙**이라 7번이 없다(하드 삭제는 **2번**). ②`ADR 필수` 로 축약해 **"Maxi 확인"을 떨어뜨렸다** — 하필 그 지운 부분이 당시 실제로 기록이 없던 항목이다. **정본이 될 문서에서 정본 규칙을 잘못 인용하면 다음 사람이 그 인용을 믿는다.**

- grant 행은 **외부 시스템에 영구 인용되지 않는다.** 이슈 키(`DATA.md §1.1`)와 달리 Slack·이메일·외부 문서가 grant 의 `id` 를 참조하지 않으므로, 행이 사라져 깨지는 인용이 없다.
- **재부여는 새 행으로 충분**하고, 소프트 삭제로 남기면 `UNIQUE (permission, grantee_type, grantee_id)` 에 부분 인덱스 조건이 붙어 멱등성 근거(D-1)가 복잡해진다.
- `project_memberships` 의 hard delete(project-membership-model **D6**) 와 동형이다 — 같은 모듈, 같은 "권한 관계 행" 성격.
- **비대칭은 의도이나 부채다.** 부여는 `granted_by` 로 기록하고 회수는 기록하지 않는다. 이번 범위는 부여 측만 덮는다 (잔여 위험 2).
- 삭제 행 수가 0 이면 404 로 거부한다 — "지웠다고 믿었는데 대상이 없었다"를 조용히 성공으로 만들지 않는다.

## 결과 / 트레이드오프

- **산출물**. `global_permission_grants` 마이그레이션(identity-access V036) + `GranteeType` enum + `GlobalPermissionGrantRepository`(JdbcTemplate) + `SystemPermissionResolver.hasGlobalPermission` default 확장(shared-kernel) + `IdentityAccessSystemPermissionResolver` override + grant/revoke/list API(SYSTEM_ADMIN 게이트) + 통합테스트.
- **장점**. FR-PJ-01 의 생성 권한 병목 해소. FR-PM-08 D3 이 예고한 확장을 **모델 교체 없이** 수행 — 기존 SYSTEM_ADMIN 판정 결과가 하나도 뒤집히지 않는다. 전역 권한 코드가 늘어도 `CHECK` 에 값 추가로 확장된다(구조 불변).
- **비용**. `grantee_id` 무결성을 DB 가 아닌 서비스가 진다(D-4). 회수 이력 부재(D-5). 본 PR 시점에 `CREATE_PROJECT` 의 실제 소비자(`POST /projects`)는 PR-2 이므로, 판정기는 **판정기·어댑터 층 테스트로 검증**되고 end-to-end 실증은 PR-2 가 추가한다.
- **무효화 없음**. FR-PM-08 D1(`system_role_assignments`)·D2(`SystemRole` 분리)·D3(SYSTEM_ADMIN 직접 판정)·D5(부트스트랩)는 전부 유효하다. D3 은 **판정식의 한 항으로 승계**된다.

## 잔여 위험

1. **USER grant 고아 행 잔존** (D-4). 사용자 삭제 시 `global_permission_grants` 행이 남고 자동 정리 기전이 없다. 권한 상승으로는 이어지지 않으나(UUID 재사용 없음 + 삭제된 사용자는 인증 불가) 목록 API 에 유령 행으로 보인다. 정리 기전은 후속 대상이다.
2. **회수 이력 부재** (D-5). `revoked_at`/`revoked_by` 소프트 삭제는 후속이다. 이번엔 `granted_by` 로 부여 측만 덮으므로, "언제 누가 뗐나"는 재구성할 수 없다.
3. **override 소실 위험** (D-2). prod 어댑터가 `hasGlobalPermission` override 를 잃으면 FR-PM-10 이 무음으로 무력화된다. 유일한 가드가 판별자 있는 테스트 1건이므로, 그 테스트의 판별력 자체를 mutation(override 주석 처리 → fail 확인)으로 확인해야 한다.
4. **권한 코드 추가 시 3곳 동시 갱신**. SDD 12(정본) · `permission` CHECK · 서비스 검증 목록이 각자 코드를 안다. 하나만 놓치면 fail-closed 로 조용히 막힌다.

## 관련

- FR-PM-10 (전역 권한 부여) — identity-access
- FR-PJ-01 (프로젝트 생성) — `CREATE_PROJECT` 첫 소비자, PR-2
- FR-PM-08 [system-admin-role](2026-06-04-system-admin-role.md) — D3 확장 대상, D1/D2 스키마 선례
- FR-PM-01 [project-membership-model](2026-06-01-project-membership-model.md) — D2(FK 생략) · D6(hard delete) 선례
- FR-PM-09 `V015__user_groups.sql` — GROUP grantee 대상, CASCADE 기전
