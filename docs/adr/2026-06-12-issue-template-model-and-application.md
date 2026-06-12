# 이슈 본문 템플릿 모델 + 적용 방식 (FR-TM-01)

> 상태: Accepted
> 날짜: 2026-06-12
> BC: issue-tracking
> 관련 FR: FR-TM-01 (프로젝트+타입별 본문 템플릿)
> 관련: domain/issue-tracking.md (Issue/IssueType 엔티티), CustomFieldPermission(MANAGE_CUSTOM_FIELDS 선례), [[2026-06-02-issue-clone-semantics]] (생성 시 필드 채움 선례)

## 맥락

프로젝트와 이슈 타입 조합마다 이슈 본문(description)의 기본 템플릿을 정의하고, 이슈
생성 시 자동으로 채워야 한다(FR-TM-01). issue-tracking BC 의 신규 영역으로, 기존
IssueTemplate 엔티티·ADR 은 없다.

조사로 드러난 제약.
- 현재 이슈 생성 요청(`CreateIssueRequest`, REST/application 양쪽)에 **description 필드가 없다**. 생성 시 본문은 항상 null 이고 이후 `PATCH /issues/{key}` 로만 입력한다(`IssueApplicationService.createIssue` → `Issue.create` 가 description 미전달).
- CustomField/Component/Version 의 관리 CRUD 가 동형 선례. shared-kernel 의 `<도메인>Permission` enum(CREATE/UPDATE/DELETE → 단일 권한코드 `MANAGE_*`) + `<도메인>PermissionResolver`(issue-tracking 이 묻고 identity-access 가 prod 판정), READ 는 게이트하지 않음(프로젝트 조회 권한으로 충족).
- Jira Cloud 동작 조사: 권장 경로는 **생성 다이얼로그 프리필 + 사용자 편집**(팀 관리형 네이티브 + 마켓플레이스 앱 전부). description 은 생성 페이로드 정식 필드. 네이티브 우회책(Automation 사후 주입)은 Atlassian 스스로 "생성 후 적용, 프리필 아님"으로 열등하다 명시.

## 결정

### 1. 데이터 모델 — issue_templates, (project_id, type_id) 당 1개

- `issue_templates(id, project_id, type_id, body, created_at, updated_at)`.
- `UNIQUE(project_id, type_id)` — "프로젝트+타입별" = (프로젝트, 타입) 조합당 본문 템플릿 정확히 1개.
- `body` 는 Markdown 텍스트(이슈 description 과 동일 형식). FR-TM-02(변수 치환)는 후속 — 1차는 리터럴 텍스트.
- `type_id` 는 `issue_types.id`(BIGINT) 참조. `project_id` 는 프로젝트 참조.

### 2. 관리 권한 — MANAGE_TEMPLATES 신규 권한코드

CustomField 동형. `TemplatePermission` enum(CREATE/UPDATE/DELETE) + `TemplatePermissionResolver`,
세 항목 모두 단일 권한코드 `MANAGE_TEMPLATES` 로 매핑. READ(목록/resolve)는 게이트하지 않음
(프로젝트 조회 권한으로 충족). 권한코드 신설은 role_permissions 시드 + `PermissionSchemaMigrationTest`
카운트와 결합되므로 같은 PR 에서 시드/카운트 동기화.

### 3. 적용 방식 — 옵션 C (프론트 프리필 + 서버 안전망)

Jira 의 두 패턴(프리필-편집 / 사후 주입)의 장점만 합친 상위집합.

- **CreateIssueRequest 에 `description` 필드 추가**(REST + application + `Issue.create` 전달). 이슈를 본문과 함께 생성 가능해진다(템플릿 무관하게도 합리적인 기능).
- **resolve 조회 엔드포인트** — 프론트(D6)가 이슈 타입 선택 시 (project, type) 템플릿 본문을 가져와 생성 폼에 프리필. 사용자가 편집 후 제출.
- **서버 안전망** — `createIssue` 에서 `request.description` 이 null/blank 이고 (projectId, resolvedTypeId) 템플릿이 존재하면 서버가 `description = template.body` 로 채운다. UI 비경유(API 직접 생성)도 템플릿 혜택을 받는다. 사용자가 빈 본문을 명시(프리필 후 전부 지움)하면 빈 채로 둔다 — blank 가 곧 "비움 의도"는 아니므로, blank=서버가 템플릿 채움이 안전망 의도와 정합(빈 본문을 원하면 템플릿 미존재 타입을 쓰거나 생성 후 PATCH 로 클리어).

근거. (a) Jira 권장 UX(프리필-편집)를 그대로 제공, (b) "백엔드 먼저" 원칙상 D6 프론트가 요구할 description 입력 경로를 이번 PR 에서 확정, (c) 서버 안전망으로 비-UI 경로 누락 방지.

## 대안 (기각)

- **옵션 A (서버 사후 주입만, 생성 요청 불변)** — 가장 단순하나 생성 시점에 템플릿 본문 편집 불가. Jira 가 열등하다 명시한 Automation 우회책과 동일. 기각.
- **옵션 B (프리필만, 서버 안전망 없음)** — Jira 권장 경로 정확 일치이나 API 직접 생성 시 템플릿 미적용. C 의 부분집합이라 흡수. 기각.
- **타입당 복수 명명 템플릿** — Jira 마켓플레이스 앱 일부가 제공하나 FR-TM-01 은 "프로젝트+타입별"(타입당 1개)로 한정. 범위 외. 기각.

## 적용 범위 (이번 PR — 백엔드 D1~D5)

도메인(IssueTemplate) · 마이그레이션(issue_templates + MANAGE_TEMPLATES 시드) · CRUD API ·
resolve 엔드포인트 · createIssue 서버 안전망 + description 필드 · 백엔드 테스트.
프론트 관리 페이지 + 생성폼 프리필(D6) · E2E(D7)는 후속 PR.
