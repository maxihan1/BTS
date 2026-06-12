# FR-TM-01 — 프로젝트+타입별 본문 템플릿 (백엔드 D1~D5)

> slug: fr-tm-01-issue-templates
> type: backend
> agent: backend-engineer
> 생성: 2026-06-12

## Brief

FR-TM-01 (issue-tracking BC, §5.2.1) — 프로젝트와 이슈 타입 조합마다 이슈 본문 기본
템플릿을 정의하고, 이슈 생성 시 자동으로 적용한다.

이번 /bts 실행 범위 = **백엔드 D1~D5** (Maxi 확정).
- D1. 도메인 — IssueTemplate
- D2. 명세
- D3. 데이터 모델 — `issue_templates(project_id, type_id, body)`
- D4. 백엔드 — CRUD API + 이슈 생성 시 적용
- D5. 백엔드 테스트

프론트 관리 페이지(D6) + E2E(D7)는 후속 /bts로 별도 PR.

classify 결과: type=backend, agent=backend-engineer, primary_bc=issue-tracking.

## 도메인 정리

- **BC**: issue-tracking
- **신규 엔티티**: IssueTemplate — `issue_templates(id, project_id, type_id, body, created_at, updated_at)`, `UNIQUE(project_id, type_id)` (프로젝트+타입 조합당 본문 템플릿 1개). body = Markdown(이슈 description 형식). FR-TM-02 변수 치환은 후속.
- **신규 용어**: "이슈 템플릿 / IssueTemplate" — 프로젝트+타입별 이슈 본문 기본 템플릿. (glossary 추가 후보 — Maxi 승인 후 머지 시 동기화)
- **신규 권한코드**: `MANAGE_TEMPLATES` (CustomField `MANAGE_CUSTOM_FIELDS` 동형). `TemplatePermission`(CREATE/UPDATE/DELETE) + `TemplatePermissionResolver`. READ 미게이트. → role_permissions 시드 + `PermissionSchemaMigrationTest` 카운트 같은 PR 동기화 필요.
- **적용 방식 (Maxi 확정 = 옵션 C)**: 프론트 프리필(B) + 서버 안전망. CreateIssueRequest 에 `description` 추가 + resolve 조회 엔드포인트 + createIssue 에서 description blank 이고 (project,type) 템플릿 존재 시 서버가 template.body 주입. Jira Cloud 동작 조사 근거(프리필-편집 권장, 사후주입 열등).
- **기존 결정 충돌**: 없음 (신규 영역). createIssue 에 description 추가는 기존 생성 테스트 영향 → plan 에서 회귀 범위 명시.
- **관련 ADR**: [docs/adr/2026-06-12-issue-template-model-and-application.md](../adr/2026-06-12-issue-template-model-and-application.md) (생성됨)
- **선례 참조**: CustomFieldPermission.kt(권한 동형), IssueApplicationService.createIssue(적용 지점), 2026-06-02-issue-clone-semantics(생성 시 필드 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
