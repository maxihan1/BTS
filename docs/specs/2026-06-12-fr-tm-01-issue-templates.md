# FR-TM-01 프로젝트+타입별 본문 템플릿 — 스펙 (백엔드 D1~D5)

> BC: issue-tracking (+ identity-access 권한 판정) | 적용방식: 옵션 C
> 관련 ADR: docs/adr/2026-06-12-issue-template-model-and-application.md
> SDD: 05-data-model §5.7 IssueTemplate, 12-permissions §12.3 (권한코드)

## 사용자 시나리오 (Given-When-Then)

### S1. 템플릿 생성 (프로젝트 관리자)
- **Given** PROJECT_ADMIN 역할 사용자, 프로젝트 ATLAS, 타입 Bug
- **When** `POST /api/v1/projects/ATLAS/issue-templates` `{ issueTypeId, name, content }`
- **Then** 201, issue_templates 1행 생성. 같은 (ATLAS, Bug) 활성 템플릿이 이미 있으면 409.

### S2. 템플릿 수정/삭제
- **When** `PATCH .../issue-templates/{id}` (name/content 변경) → 200
- **When** `DELETE .../issue-templates/{id}` → 204, deleted_at 설정(소프트 삭제). 동일 (project,type) 재생성 허용.

### S3. 템플릿 목록/단건/resolve 조회 (권한 미게이트)
- **When** 프로젝트 조회 권한 사용자가 `GET .../issue-templates` → 200, 활성 템플릿 목록
- **When** `GET .../issue-templates/resolve?issueTypeId={id}` → 200 `{ content }` (활성 템플릿 있을 때) / 204 (없을 때)

### S4. 이슈 생성 시 서버 안전망 (옵션 C 핵심)
- **Given** (ATLAS, Bug) 템플릿 content="## 재현 절차\n" 존재
- **When** `POST /api/v1/issues` `{ projectKey: ATLAS, typeId: Bug, summary, description: null }` (또는 description 미전달/공백)
- **Then** 생성된 이슈 description = 템플릿 content (서버 주입)
- **When** 같은 요청에 `description: "이미 작성한 본문"` (non-blank)
- **Then** 생성된 이슈 description = "이미 작성한 본문" (요청 우선, 템플릿 무시)
- **When** (ATLAS, Bug) 템플릿이 없고 description 미전달
- **Then** description = null (현행 동작 유지)

### S5. 프론트 프리필 경로 (D6에서 소비, 이번 PR은 엔드포인트만)
- 프론트가 타입 선택 시 resolve 로 content 조회 → 생성폼 본문 에디터 프리필 → 사용자 편집 후 `POST /issues` 에 description 포함 제출

## 기능 요구사항 (FR)

- **FR-TM-01.1** issue_templates 테이블. (project_id UUID FK, issue_type_id BIGINT FK, name VARCHAR(100), content TEXT) + 소프트삭제 + 활성 기준 UNIQUE(project_id, issue_type_id).
- **FR-TM-01.2** 템플릿 CRUD API (`/api/v1/projects/{projectIdOrKey}/issue-templates`). CREATE/UPDATE/DELETE 는 MANAGE_TEMPLATES 권한, READ(목록/단건/resolve)는 미게이트(프로젝트 조회 권한으로 충족) — custom-fields 동형.
- **FR-TM-01.3** resolve 엔드포인트 — (project, type) 활성 템플릿 1건의 content 반환(없으면 204).
- **FR-TM-01.4** `CreateIssueRequest`(REST + application)에 `description: String?` 추가 → `Issue.create` 전달.
- **FR-TM-01.5** createIssue 서버 안전망 — `request.description.isNullOrBlank()` 이고 (projectId, resolvedTypeId) 활성 템플릿 존재 시 description = 템플릿 content.
- **FR-TM-01.6** 권한 판정 — shared-kernel `TemplatePermission`(CREATE/UPDATE/DELETE→MANAGE_TEMPLATES) + `TemplatePermissionResolver` port. 비-prod = AlwaysAllow(issue-tracking), prod = IdentityAccessTemplatePermissionResolver(identity-access) + role_permissions 시드(PROJECT_ADMIN).
- **FR-TM-01.7 (D6 연기)** UI 버튼 게이팅용 `MyProjectPermissionController` `manageTemplates` 요약 노출은 **D6 PR로 연기**(plan-eng-review BLOCKER-2 반영). 컨트롤러가 이미 8 파라미터+@Suppress 라 9번째 주입은 D1~D5 범위 밖 view 관심사. CRUD 권한 게이팅은 resolver(FR-TM-01.6)로 이미 완결. 메모리 ui-permission-gating-needs-summary-api-exposure 는 D6 에서 충족(resolver 이미 존재 + 요약 노출 추가).
- **FR-TM-01.8** 검증 — name NotBlank ≤100, content NotBlank. PATCH 는 부분 수정(name?/content?, null=무변경, custom-fields PATCH 동형). `Issue.create` 는 이미 `description: String? = null` 파라미터 보유(line 161) → 도메인 변경 불요, createIssue 가 resolvedDescription 전달만.

## 비기능 요구사항 (NFR)

- 완제품 품질(CLAUDE.md §작업 기준). 절대규칙 19개 준수.
- 소프트 삭제(DATA.md §3), TIMESTAMPTZ(§4), FK 인덱스(§7) — custom_field_definitions 패턴 동형.
- 권한 prod resolver 빈 부재 시 fail-safe 방향(메모리 crossbc-resolver-nullable-fail-open). 단 템플릿 미적용(안전망 미동작)은 benign(보안 누출 아님).
- createIssue 추가 조회는 템플릿 1건 단순 lookup(인덱스 hit) — 생성 경로 지연 무시 가능.

## API 인터페이스 (REST)

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| GET | `/api/v1/projects/{projectIdOrKey}/issue-templates` | 미게이트 | 200 목록 |
| GET | `/api/v1/projects/{projectIdOrKey}/issue-templates/{id}` | 미게이트 | 200 / 404 |
| GET | `/api/v1/projects/{projectIdOrKey}/issue-templates/resolve?issueTypeId={id}` | 미게이트 | 200 `{content}` / 204 |
| POST | `/api/v1/projects/{projectIdOrKey}/issue-templates` | MANAGE_TEMPLATES | 201 / 409(중복) / 403 |
| PATCH | `/api/v1/projects/{projectIdOrKey}/issue-templates/{id}` | MANAGE_TEMPLATES | 200 / 404 / 403 |
| DELETE | `/api/v1/projects/{projectIdOrKey}/issue-templates/{id}` | MANAGE_TEMPLATES | 204 / 404 / 403 |
| POST | `/api/v1/issues` (변경) | CREATE(기존) | body 에 `description?` 추가 |

## 데이터 모델 변경

```sql
-- issue-tracking 모듈 V019 (머지 직전 V번호 재확인)
CREATE TABLE issue_templates (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    issue_type_id BIGINT       NOT NULL REFERENCES issue_types(id),
    name          VARCHAR(100) NOT NULL,
    content       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
CREATE INDEX idx_issue_templates_project_id    ON issue_templates(project_id);
CREATE INDEX idx_issue_templates_issue_type_id ON issue_templates(issue_type_id);
CREATE UNIQUE INDEX ux_issue_templates_project_type_active
    ON issue_templates(project_id, issue_type_id) WHERE deleted_at IS NULL;
-- + init_codegen.sql 미러 필수 (issue-tracking jOOQ)
```

```sql
-- identity-access 권한 시드 V024 (현재 최신 V023 → V024, 머지 직전 재확인). init_codegen 미러 불요(JdbcTemplate).
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_TEMPLATES');
```

## 엣지 케이스

- **EC1** (project, type) 활성 템플릿 중복 생성 → 409 (부분 유니크 인덱스 위반 → jOOQ/Spring 409 변환, 메모리 jooq-exception-translator-409-dependency 두 경로 catch).
- **EC2** 미존재/타 프로젝트 템플릿 id 로 PATCH/DELETE → 404.
- **EC3** 존재하지 않는 issueTypeId 로 템플릿 생성 → 422/404 (FK 위반 전 검증).
- **EC4** createIssue 에서 description 공백("")이고 템플릿 존재 → 템플릿 주입(blank=비움 의도 아님, ADR 명시).
- **EC5** createIssue 에서 description 명시("내용")이고 템플릿 존재 → 요청 우선, 템플릿 무시.
- **EC6** 소프트 삭제된 템플릿은 resolve/목록/안전망에서 제외(deleted_at IS NULL).
- **EC7** 템플릿 없는 타입으로 생성 → description=null (현행 유지, 회귀 없음).
- **EC8** MEMBER(비관리자)가 CRUD → 403 (행정 권한, custom-fields 동형).
- **EC9** 이슈 이동(FR-MV) 시 템플릿은 프로젝트 종속 — 이동 후 재적용 안 함(생성 시 1회 적용, 본 PR scope 외).
- **EC10** 이슈 클론(FR-IS-06 cloneIssue) — 원본 description 을 그대로 복사. 안전망은 `createIssue` 에만 적용, `cloneIssue` 무영향(템플릿 미주입).
- **EC11** content="" (빈 문자열) 템플릿 생성 시도 → 422(content NotBlank). 빈 템플릿 무의미.

## 제약 조건

- **cross-BC**: identity-access prod resolver + 권한 시드 + PermissionSchemaMigrationTest 카운트(+1) 같은 PR 동기화(권한 resolver 패턴, FR-IS-10 #96 선례).
- **마이그레이션 V번호**: issue-tracking V019(최신 V018), identity-access V024(최신 V023) — 머지 직전 `ls .../migration | sort -V | tail -1` 재확인(메모리 migration-vnumber-concurrent-branch-collision). FR-MF-04(#123)는 identity-access 마이그레이션 미추가(실측 V023 동일).
- **init_codegen 미러**: issue_templates 테이블을 issue-tracking init_codegen.sql 에 미러(메모리 jooq-init-codegen-mirror).
- **생성자 주입**: IssueApplicationService 에 IssueTemplateRepository nullable-default 주입(customFieldDefinitionRepository 선례) → 기존 생성 테스트 호환. 단 prod 빈은 Spring 배선.
- **정본 동기화**: SDD §5.7(UNIQUE 명시 + 필드 타입 실제화 주석) ↔ product D3(`content` 필드명 + 1개 명시) ↔ fr-index ↔ ADR 같은 PR 동기화(§전수 동기화 규칙, verify-master-plan).

## 측정 가능한 완료 기준

- [ ] issue_templates 마이그레이션 + init_codegen 미러, Flyway migrate 성공.
- [ ] CRUD + resolve API 통합 테스트(201/409/404/403/204 + 권한 게이팅) 그린.
- [ ] createIssue 안전망 단위/통합 테스트(S4 5케이스) 그린.
- [ ] CreateIssueRequest description 추가 후 기존 생성 테스트 전부 그린(회귀 0).
- [ ] PermissionSchemaMigrationTest 카운트 +1 반영 후 그린.
- [ ] 4개 모듈 detekt/ktlint 그린, BC 격리(직접 import 0) 유지.

## Brainstorming Check

✅ 통과 (1회 iteration, 자체 gap 분석). 발견·보강 항목.
- MyProjectPermissionController `manageTemplates` 요약 노출 추가(FR-TM-01.7) — D6 재터치 회피.
- cloneIssue 무영향 명시(EC10), content NotBlank(EC11), Issue.create 기존 파라미터 재사용(FR-TM-01.8).
- Maxi 결정 필요 gap 없음(멀티플리시티·적용방식은 도메인 단계에서 이미 확정).
