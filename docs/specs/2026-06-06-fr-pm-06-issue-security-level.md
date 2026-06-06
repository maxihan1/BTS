# FR-PM-06 이슈 보안 수준 (Issue Security Level) — 스펙

> BC: identity-access (스킴·등급·멤버·판정) + issue-tracking (issues.security_level_id·이슈 지정) | agent: security-engineer | 범위: 백엔드 D1~D5 (UI/E2E 후속)
> SDD §12.4 (명세 변경 — 단순 allowedRoles 모델 → Jira식 스킴 구조) | plan §4.6
> 선행: FR-PM-05(Browse/View, PR #85) · FR-PM-08(SYSTEM_ADMIN, PR #75) · FR-PM-09(사용자 그룹, PR #88)
> Maxi 결정(2026-06-06 도메인 grill): **Jira Cloud Issue Security와 동일하게** — 스킴 계층 포함 + `SET_ISSUE_SECURITY` 전용 권한.

## 개요

`VIEW_ISSUE` 권한을 통과해도, 이슈에 **보안 등급(Security Level)** 이 붙어 있고 그 등급의
허용 멤버가 아니면 이슈를 못 보게 하는 추가 차단 계층. Jira Cloud의 Issue Security를 그대로 따른다.

```
이슈 보안 스킴(Issue Security Scheme)   ── 등급들의 묶음 (전역, 여러 프로젝트가 공유)
   └─ 보안 등급(Security Level)          ── "임원만", "내부용" (스킴당 여러 개, 기본 등급 지정 가능)
        └─ 등급 멤버(Level Member)        ── 보고자 / 담당자 / 특정 사용자 / 프로젝트 역할 / 그룹
스킴을 프로젝트에 적용                     ── 한 프로젝트에 스킴 0~1개 (PROJECT_ADMIN)
이슈에 등급 지정                          ── SET_ISSUE_SECURITY 권한자가 생성/편집 시 선택
판정                                      ── 등급 있으면 그 등급 멤버만 VIEW. 없으면 기존 VIEW 매트릭스만.
```

## 도메인 결정 (확정)

| 항목 | 결정 |
|---|---|
| 구조 | 스킴 → 등급 → 멤버 (Jira 동일, 스킴 계층 포함) |
| 멤버 타입 | `REPORTER` / `ASSIGNEE` / `USER` / `PROJECT_ROLE` / `GROUP` (5종) |
| 스킴·등급·멤버 관리 | SYSTEM_ADMIN (전역 = Jira admin, `isSystemAdmin` 재사용, 신규 권한코드 0) |
| 프로젝트에 스킴 적용 | PROJECT_ADMIN (Jira 동일) — 기존 `role_permissions` 매트릭스 판정 |
| 이슈에 등급 지정 | `SET_ISSUE_SECURITY` 신규 권한코드 (role_permissions 시드 → 카운트 가드 변경) |
| 등급 미지정 이슈 | 기존 VIEW 매트릭스만 적용 (Browse/View 통과자 모두 — Jira 기본) |
| 미통과 시 응답 | **404 존재 숨김** (FR-PM-05 `assertViewIssueOrNotFound`와 일관, probe 차단) |
| 판정 위치 | identity-access `IdentityAccessIssuePermissionResolver` 확장 (그룹·역할·멤버십·이슈데이터 모두 read 가능) |
| 관리자 우회 | **없음** (Jira 동일, 2026-06-06 확정) — SYSTEM_ADMIN/PROJECT_ADMIN도 등급 멤버 아니면 404. 민감 이슈 진짜 격리. |
| PR 분할 | **2 PR** (Jira 무관, BTS 진행, 2026-06-06 확정) — ① identity-access 관리 인프라 ② issue-tracking 컬럼·지정·판정 결선. **이번 PR #86 = ①.** |

**제외 멤버 타입(명시)**: Jira의 `Project Lead`(BTS에 PROJECT_LEAD 역할 미구현, FR-CM-04 논의 중),
`User custom field value` / `Group custom field value`(BTS 커스텀 필드 미구현). 후속 필요 시 멤버 타입 추가.

## 사용자 시나리오 (Given-When-Then)

### 스킴/등급/멤버 관리 (SYSTEM_ADMIN)
- **S1 스킴 생성**. Given SYSTEM_ADMIN, When `POST /api/v1/issue-security-schemes {name,description}`, Then 201 + 스킴. name 중복 409.
- **S2 등급 생성**. Given SYSTEM_ADMIN, When `POST /api/v1/issue-security-schemes/{schemeId}/levels {name,description,isDefault}`, Then 201 + 등급. 같은 스킴 내 name 중복 409. 없는 스킴 404.
- **S3 멤버 추가**. Given SYSTEM_ADMIN, When `POST /api/v1/issue-security-levels/{levelId}/members {memberType, memberValue?}`, Then 201/204. 타입별 memberValue 검증(USER/GROUP=UUID 실재, PROJECT_ROLE=enum, REPORTER/ASSIGNEE=value 없음). 중복 멤버 멱등.
- **S4 멤버 제거**. Given SYSTEM_ADMIN, When `DELETE .../members/{memberId}`, Then 204(멱등).
- **S5 스킴/등급 조회·수정·삭제**. CRUD 일반. 삭제는 CASCADE(스킴 삭제→등급→멤버). 단, **프로젝트에 적용 중인 스킴 삭제는 409**(또는 적용 해제 강제). 이슈가 참조 중인 등급 삭제 정책 → 엣지 케이스 EC6.

### 프로젝트에 스킴 적용 (PROJECT_ADMIN)
- **S6 스킴 적용**. Given 프로젝트 X의 PROJECT_ADMIN, When `PUT /api/v1/projects/{key}/issue-security-scheme {schemeId}`, Then 204. 프로젝트당 스킴 0~1개(교체=덮어쓰기). 없는 스킴 404. 비-PROJECT_ADMIN 403.
- **S7 스킴 적용 해제**. Given PROJECT_ADMIN, When `DELETE /api/v1/projects/{key}/issue-security-scheme`, Then 204. 해제 후 그 프로젝트 이슈의 security_level_id 처리 → EC7.

### 이슈에 등급 지정 (SET_ISSUE_SECURITY)
- **S8 이슈 생성 시 등급 지정**. Given SET_ISSUE_SECURITY 보유 + 프로젝트에 스킴 적용됨, When 이슈 생성 본문에 `securityLevelId`, Then 그 등급으로 생성. 등급이 프로젝트 적용 스킴 소속이 아니면 422.
- **S9 이슈 편집 시 등급 변경/해제**. Given SET_ISSUE_SECURITY, When `PATCH .../issues/{key}/security-level {securityLevelId|null}`, Then 변경/해제. SET_ISSUE_SECURITY 없으면 403. 기본 등급(isDefault) 자동 적용은 EC8.

### 판정 (모든 사용자)
- **S10 등급 멤버 통과**. Given 이슈에 "임원만" 등급 + actor가 그 등급의 GROUP("임원") 멤버, When `GET .../issues/{key}`, Then 200(VIEW 매트릭스도 통과 전제).
- **S11 등급 멤버 아님 → 404**. Given 이슈에 "임원만" 등급 + actor가 멤버 아님(VIEW 매트릭스는 통과), When `GET .../issues/{key}`, Then **404**(존재 숨김). 목록(`GET .../issues`)에서도 그 이슈 제외.
- **S12 보고자/담당자 예외**. Given 이슈 등급 멤버에 REPORTER 포함 + actor가 그 이슈 reporter, Then 200. ASSIGNEE 동일.
- **S13 등급 없는 이슈**. Given security_level_id=null, When VIEW 매트릭스 통과자 접근, Then 200(추가 차단 없음).

## 기능 요구사항 (FR)

- **FR1**. `IssueSecurityScheme`(id, name UNIQUE, description?). 전역.
- **FR2**. `IssueSecurityLevel`(id, schemeId, name, description?, isDefault). 같은 스킴 내 name UNIQUE. 스킴당 isDefault 최대 1.
- **FR3**. `SecurityLevelMember`(id, levelId, memberType, memberValue?). 다형: REPORTER/ASSIGNEE(value 없음), USER/GROUP(UUID), PROJECT_ROLE(enum 문자열). 같은 (levelId, memberType, memberValue) 중복 불가.
- **FR4**. 프로젝트-스킴 적용 `project_issue_security_schemes`(projectId PK, schemeId). 프로젝트당 0~1.
- **FR5**. `issues.security_level_id`(UUID nullable, FK 없는 cross-BC 참조 — reporter_id 선례). issue-tracking 마이그레이션 + init_codegen 미러.
- **FR6**. 신규 권한코드 `SET_ISSUE_SECURITY`. 기본 스킴 role_permissions 시드(PROJECT_ADMIN에 부여 — Maxi 확인 대상, EC9).
- **FR7**. 판정 — identity-access resolver가 IssueScope.Issue + VIEW 시: (1) VIEW 매트릭스 통과 확인 (2) 이슈 security_level_id read (3) null→통과 (4) 등급 멤버 조회→actor 충족(REPORTER/ASSIGNEE/USER/PROJECT_ROLE/GROUP 중 1+) 시 통과, 아니면 거부.
- **FR8**. cross-BC `IssueSecurityLookup` 포트(identity-access) — 이슈 key/id로 `(security_level_id, reporter_id, assignee_id, project_id)` read-only 조회(ProjectDirectory 패턴, FK 없는 read).
- **FR9**. 목록(listIssues) 필터 — BROWSE 통과 프로젝트의 이슈 중, 등급이 있고 멤버 아닌 이슈는 결과에서 제외(per-issue 술어, `IssueRepository.listWithType` 확장 지점).

## 비기능 요구사항 (NFR)

- **NFR1 권한 일관**. 관리=isSystemAdmin 수동 가드(FR-PM-09 동형, @PreAuthorize hasRole 비사용, PAT/claim stale 일관). 적용=PROJECT_ADMIN 매트릭스. 지정=SET_ISSUE_SECURITY 매트릭스. 판정=resolver.
- **NFR2 404 일관**. 단건 미통과는 403 아닌 404(probe 차단). findByKey/availableTransitions/cloneIssue/exportPdf 전 경로 일관(FR-PM-05 `assertViewIssueOrNotFound` 헬퍼가 이미 보안수준 판정 결과를 흡수 — resolver가 false 반환하면 자동 404).
- **NFR3 성능**. 단건 판정 시 이슈 1행 + 등급 멤버 N행 read. 목록 판정은 N+1 회피 — 멤버십/그룹/역할을 actor 기준 1회 prefetch 후 in-memory 필터, 또는 SQL 조인 술어. 1K 사용자·등급 멤버 소수 가정.
- **NFR4 영속**. identity-access raw SQL(NamedParameterJdbcTemplate). issue-tracking은 jOOQ(security_level_id 컬럼 → init_codegen 미러 필수, jooq-init-codegen-mirror 교훈).
- **NFR5 검증 ground-truth**. prod 프로파일 Testcontainers 통합테스트(non-prod AlwaysAllow가 마스킹 — issue-scope-global-prod-hard-deny / FR-PM-05 동형). S10~S13 판정은 prod 실판정으로만 검증.
- **NFR6 BC 격리**. identity-access는 issue-tracking import 없이 DB read-only(IssueSecurityLookup). issue-tracking은 security_level_id 저장 + resolver 포트 호출만. 스킴·등급·멤버·판정은 identity-access 소유.

## API 인터페이스 (REST)

### 스킴/등급/멤버 관리 (identity-access, SYSTEM_ADMIN)
| 메서드 | 경로 | 성공 | 실패 |
|---|---|---|---|
| POST | `/api/v1/issue-security-schemes` | 201 | 400,409(name),401/403 |
| GET | `/api/v1/issue-security-schemes` | 200 [+levels] | 401/403 |
| GET/PATCH/DELETE | `/api/v1/issue-security-schemes/{id}` | 200/200/204 | 404,409(적용중 삭제),401/403 |
| POST | `/api/v1/issue-security-schemes/{schemeId}/levels` | 201 | 400,404,409(name),401/403 |
| PATCH/DELETE | `/api/v1/issue-security-levels/{levelId}` | 200/204 | 404,401/403 |
| POST | `/api/v1/issue-security-levels/{levelId}/members` | 201/204 | 400(타입/값),404(level/user/group),401/403 |
| GET | `/api/v1/issue-security-levels/{levelId}/members` | 200 | 404,401/403 |
| DELETE | `/api/v1/issue-security-level-members/{memberId}` | 204(멱등) | 401/403 |

### 프로젝트 스킴 적용 (PROJECT_ADMIN)
| PUT | `/api/v1/projects/{key}/issue-security-scheme {schemeId}` | 204 | 404(project/scheme),401/403 |
| DELETE | `/api/v1/projects/{key}/issue-security-scheme` | 204 | 404,401/403 |
| GET | `/api/v1/projects/{key}/issue-security-scheme` | 200 (현재 스킴+등급) | 404,401/403 |

### 이슈 등급 지정 (issue-tracking, SET_ISSUE_SECURITY)
| 이슈 생성/수정 본문 `securityLevelId` (기존 createIssue/updateIssue 확장) | - | 422(프로젝트 스킴 외 등급),403 |
| PATCH | `/api/v1/issues/{key}/security-level {securityLevelId\|null}` | 200 | 403,404,422 |

- **MemberType enum**: `REPORTER, ASSIGNEE, USER, PROJECT_ROLE, GROUP`. memberValue 규칙: USER/GROUP=UUID 문자열, PROJECT_ROLE=`PROJECT_ADMIN`|`MEMBER`, REPORTER/ASSIGNEE=null.
- **에러 코드(snake_case)**: `scheme_name_conflict`, `scheme_not_found`, `level_not_found`, `level_name_conflict`, `member_type_invalid`, `member_value_invalid`, `scheme_in_use`, `security_level_not_in_project_scheme`, `forbidden`, `unauthorized`.

## 데이터 모델 변경

### identity-access (V016)
```sql
CREATE TABLE issue_security_schemes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE issue_security_levels (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id   UUID NOT NULL REFERENCES issue_security_schemes(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scheme_id, name)
);
CREATE UNIQUE INDEX uq_security_level_one_default ON issue_security_levels(scheme_id) WHERE is_default;  -- 스킴당 기본 등급 최대 1
CREATE TABLE issue_security_level_members (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    level_id     UUID NOT NULL REFERENCES issue_security_levels(id) ON DELETE CASCADE,
    member_type  VARCHAR(20) NOT NULL CHECK (member_type IN ('REPORTER','ASSIGNEE','USER','PROJECT_ROLE','GROUP')),
    member_value VARCHAR(64),  -- USER/GROUP=UUID, PROJECT_ROLE=enum, REPORTER/ASSIGNEE=NULL
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (level_id, member_type, member_value)
);
CREATE TABLE project_issue_security_schemes (
    project_id UUID PRIMARY KEY,                  -- cross-BC 참조(FK 없음, projects 소유 issue-tracking)
    scheme_id  UUID NOT NULL REFERENCES issue_security_schemes(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- SET_ISSUE_SECURITY 권한코드 기본 스킴 시드(PROJECT_ADMIN) → PermissionSchemaMigrationTest 카운트 12→13
```

### issue-tracking (V20x)
```sql
ALTER TABLE issues ADD COLUMN security_level_id UUID;  -- nullable, FK 없는 cross-BC 참조(reporter_id 선례)
CREATE INDEX ix_issues_security_level ON issues(security_level_id) WHERE security_level_id IS NOT NULL;
-- init_codegen.sql에 동일 컬럼 미러(jOOQ 상수 생성, jooq-init-codegen-mirror 교훈) 필수
```

## 엣지 케이스

- **EC1 PROJECT_ROLE memberValue**. `PROJECT_ADMIN`/`MEMBER`만 허용(ProjectRole enum). 그 외 400 `member_value_invalid`.
- **EC2 USER/GROUP memberValue 실재**. 추가 시 user/group 존재 확인(cross-lookup) → 없으면 404. (사후 사용자/그룹 삭제는 판정 시 자연 미스로 처리, orphan 멤버 허용.)
- **EC3 REPORTER/ASSIGNEE에 memberValue 동반**. value 무시 또는 400. → 400 `member_value_invalid`(엄격).
- **EC4 ASSIGNEE 미할당 이슈**. assignee_id=null이면 ASSIGNEE 멤버는 아무도 충족 안 함(빈 집합). 통과 아님.
- **EC5 등급은 있으나 멤버 0**. 멤버 빈 등급 → 아무도 통과 못 함(SYSTEM_ADMIN도? — 판정은 actor 기준. SYSTEM_ADMIN 전역 우회 여부 → EC10).
- **EC6 이슈가 참조 중인 등급 삭제**. 등급 삭제 시 그 등급 쓰는 이슈 존재 → 막을지(409 `level_in_use`) vs 이슈 security_level_id를 cross-BC로 NULL화. **결정 필요** → Maxi/brainstorming. (Jira는 등급 삭제 시 이슈에서 제거.) FK 없는 cross-BC라 DB CASCADE 불가 → 애플리케이션 처리 또는 orphan 허용(판정 시 등급 미존재→통과? 거부?).
- **EC7 프로젝트 스킴 적용 해제**. 해제 후 그 프로젝트 이슈의 security_level_id가 더는 유효 스킴에 없음 → 판정 시 "등급이 프로젝트 스킴에 없음" → 통과(차단 해제) vs 거부. **결정 필요**.
- **EC8 기본 등급(isDefault) 자동 적용**. Jira는 스킴에 default 등급 있으면 신규 이슈에 자동. 본 FR 포함 여부 → brainstorming. (포함 시 createIssue가 프로젝트 스킴의 default 등급 자동 set, SET_ISSUE_SECURITY 무관.)
- **EC9 SET_ISSUE_SECURITY 시드 대상**. PROJECT_ADMIN만? MEMBER도? → 기본 PROJECT_ADMIN(보수적). Maxi 확인.
- **EC10 SYSTEM_ADMIN/PROJECT_ADMIN 보안수준 우회**. → **우회 없음 확정(2026-06-06 Maxi, Jira 동일)**. 전역/프로젝트 관리자도 등급 멤버 아니면 404. 등급 멤버십만이 유일한 통과 경로(REPORTER/ASSIGNEE/USER/PROJECT_ROLE/GROUP). 관리자가 자기 프로젝트 민감 이슈를 보려면 등급 멤버에 자신을 명시 추가하거나 PROJECT_ROLE 멤버를 등급에 추가.
- **EC11 이슈 이동(다른 프로젝트)**. security_level_id가 새 프로젝트 스킴에 없는 등급 → 이동 시 등급 해제 또는 차단. 이슈 이동 FR 미구현이면 범위 밖.
- **EC12 cloneIssue**. 클론 시 security_level_id 복사 여부. FR-IS-06이 core-only scope(보안수준 미정의) → 복사 안 함(null) 기본.

## 범위 밖 (명시 분리)
- **관리 UI / E2E**(D6/D7) 후속.
- **PROJECT_LEAD / 커스텀 필드 멤버 타입**(역할·필드 미구현).
- **감사 로그**(등급 변경 audit) → FR-AU-10 후속.
- **이슈 이동 시 등급 재평가**(이슈 이동 FR 미구현).
- **컨트롤러 actor 결선**(issue-tracking SYSTEM_ACTOR 하드코딩) — FR-PM-04 C2 동형 후속(있으면 명시).

## 제약 조건
- **2 PR 분할 확정(2026-06-06 Maxi)**. ① **PR-A = identity-access 관리 인프라**(이번 PR #86): V016(schemes/levels/members/project-scheme) + SET_ISSUE_SECURITY 시드 + 도메인·Repository·Service + 스킴/등급/멤버 CRUD API + 프로젝트 스킴 적용 API. issues 컬럼·판정 결선 **제외**(FR-PM-09가 그룹 CRUD만 먼저 한 리듬). ② **PR-B = issue-tracking 결선**(후속): issues.security_level_id 컬럼+init_codegen + 이슈 지정 API + IssueSecurityLookup 포트 + resolver 판정 확장 + 목록 필터. PR-A는 컬럼 의존 없이 self-contained → 순서 의존 최소. **이번 작업 범위 = PR-A. plan은 PR-A만 분해.**
- 명세 변경(SDD §12.4 단순 모델→스킴 구조) → 전수 동기화(fr-index/SDD/product/README/CLAUDE/ADR/Obsidian). verify-master-plan 통과 필수.
- 신규 권한코드 SET_ISSUE_SECURITY → PermissionSchemaMigrationTest 카운트 12→13(+1행) 갱신(fr-pm-permission-seed-migration-test-coupling 교훈).
- 완제품 기준(DEVELOPMENT.md §1): 입력 검증·권한·에러·테스트.

## 측정 가능한 완료 기준
- [ ] V016(identity-access) + issues.security_level_id(issue-tracking + init_codegen) 마이그레이션 적용.
- [ ] 스킴/등급/멤버 CRUD + 프로젝트 적용 + 이슈 지정 API 동작.
- [ ] resolver 판정 확장: 등급 멤버(5타입) 통과/거부, 미통과 404, 등급없음 통과.
- [ ] prod Testcontainers 통합: S10~S13(판정 ground-truth) + EC4/EC10(예외) + 거부 404.
- [ ] SET_ISSUE_SECURITY 시드 + PermissionSchemaMigrationTest 13 갱신.
- [ ] 백엔드 영향 모듈(shared-kernel·identity-access·issue-tracking) test + ktlint(Main/Test) + detekt --rerun-tasks 그린, 회귀 0.
- [ ] 목록 필터(FR9) N+1 회피 검증.

## Brainstorming Check

✅ 통과 (적대적 sanity check, EC 12건 도출). Maxi 결정 gap 2건 해소 — (EC10) 관리자 우회 **없음**(Jira 동일) 확정,
(제약) **2 PR 분할** 확정(PR-A=identity-access 인프라=이번 PR, PR-B=issue-tracking 결선). 나머지 gap(EC6/7/8/9)은
"Jira 동일" + 보수적 default로 확정. 추가 Maxi 결정 필요 gap 없음.

**적대적 검토가 잡은 핵심 위험(plan/impl 인계)**:
1. **cross-BC 순서 의존** — 판정기(identity-access)가 issues.security_level_id(issue-tracking) read. PR-A에 판정 넣으면 컬럼 부재로 통합테스트 RED. → 판정·Lookup은 PR-B로 분리(이번 범위 밖).
2. **권한 시드 카운트 가드** — SET_ISSUE_SECURITY 시드 시 PermissionSchemaMigrationTest 12→13 동반 갱신(fr-pm-permission-seed-migration-test-coupling 재현 예상).
3. **non-prod 마스킹** — 판정 동작은 PR-B에서 prod Testcontainers로만 ground-truth(issue-scope-global-prod-hard-deny 동형). PR-A는 관리 API라 FR-PM-09 부팅 레시피 재사용.
4. **명세 변경 전수 동기화** — SDD §12.4 단순 allowedRoles 모델 → 스킴 구조. fr-index는 FR 카운트 불변(121, FR-PM-06 기존)이나 SDD/product 본문 갱신 + verify-master-plan 통과.
