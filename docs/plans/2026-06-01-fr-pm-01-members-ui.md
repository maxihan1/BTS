# FR-PM-01 프로젝트 행정 (멤버 관리) — D6 프론트 UI + D7 E2E

> slug: fr-pm-01-members-ui
> type: ui (classifier qa 오분류 교정)
> agent: frontend-engineer (+ qa-engineer for E2E)
> primary BC: identity-access (프론트는 apps/web)
> 생성: 2026-06-01

## Brief

FR-PM-01 남은 작업. 백엔드 D1~D5는 PR #48 완료(ProjectMembership/ProjectRole + CRUD API).
이번 PR 범위는 **D6(프론트 UI: 프로젝트 설정 → 멤버 관리 화면) + D7(E2E)**.

백엔드 API. `/api/v1/projects/{projectId}/members` — POST(추가)/GET(목록)/PATCH(역할)/DELETE(제거).
규칙. 부트스트랩(멤버0명 첫멤버 자동 ADMIN, JWT+자기자신), CRUD PAT 허용, 비멤버 404 존재숨김, 마지막 admin 보호.

**범위 변경(2026-06-01 Maxi 결정)**. 이번 PR은 D6/D7(프론트)에 더해, Jira식 `projectIdOrKey` 정합을 위한 **작은 백엔드 슬라이스**(멤버 API가 projectKey도 받게 확장)를 포함한다.

## 도메인 정리

- **BC**: identity-access (멤버 백엔드) + apps/web 프론트. cross-BC read는 issue-tracking `projects`(읽기 전용).
- **이번 PR 범위 (Maxi 결정 3건)**:
  1. **식별자 = Jira식 projectIdOrKey** — 멤버 백엔드 API path `{projectId}` → `{projectIdOrKey}`로 확장. UUID 형식이면 id로, 아니면 projects.key로 해석. `ProjectDirectory`에 `resolveKeyToId(key)` 추가(cross-BC read, FK 없음). 프론트 라우트는 `/projects/$projectKey/settings/members`로 workflow-scheme 관례와 일관. → 백엔드 슬라이스 + 프론트.
  2. **이름 표시 + 검색 추가** — 멤버 응답은 userId(UUID)만 → `GET /api/v1/users?query=`(FR-IS-03 디렉토리, `[{id,username,displayName?,email?}]`, 최대 50건)로 목록의 userId→이름 변환 + 추가 시 typeahead 검색.
  3. **부트스트랩 제외** — 이번엔 PROJECT_ADMIN의 멤버 CRUD만. 비멤버는 404→"접근 권한 없음" 화면. 생성자-자동admin은 프로젝트 생성 FR으로 이연.
- **검증된 백엔드 계약(PR #48, 실코드 인용)**:
  - GET `{members:[ProjectMemberResponse]}` 200 / POST 201 단건 / PATCH `/{userId}` 200 단건 / DELETE `/{userId}` 204
  - `ProjectMemberResponse{ projectId:UUID, userId:UUID, role:string("PROJECT_ADMIN"|"MEMBER"), createdAt:ISO, updatedAt:ISO }` (camelCase, displayName/email 없음 — Zod 1:1)
  - 에러 `{error:snake_case}` — `project_not_found`(404 존재숨김), `user_not_found`(404), `member_not_found`(404), `membership_already_exists`(409), `not_project_admin`(403), `last_admin_protected`(409), `bootstrap_requires_jwt`(403), `invalid_role`(422), `unauthorized`(401)
- **실재 검증(phantom 방지)**:
  - `projects` 테이블 `id` UUID + `key` VARCHAR UNIQUE 둘 다 존재 ✅ (issue-tracking V001) → key→id 변환 가능
  - `GET /api/v1/users` 디렉토리 ✅ (UsersController, FR-IS-03) / 프론트 users 디렉토리 클라이언트 미존재 ❌ (이번 신규)
  - 프론트 `/projects/$projectKey/settings/workflow-scheme` 라우트 패턴 실재 ✅ → 멤버 라우트가 따를 선례
  - `ProjectMemberController`가 projectKey 수용 ❌ (이번 백엔드 슬라이스로 확장)
- **새 용어**: 없음 (프로젝트 멤버십/역할은 백엔드 plan에서 이미 도입). glossary 변경 없음.
- **기존 결정 충돌**: 백엔드 ADR `2026-06-01-project-membership-model`이 "projectId UUID path"로 명시 → 이번 projectKey 확장으로 보강. 신규 ADR로 결정 기록.
- **관련 ADR**: [docs/decisions/2026-06-01-project-member-projectidorkey.md](../decisions/2026-06-01-project-member-projectidorkey.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## 스펙

전체 스펙. [docs/specs/2026-06-01-fr-pm-01-members-ui.md](../specs/2026-06-01-fr-pm-01-members-ui.md)

핵심 요약.
- **백엔드 슬라이스(security-engineer)** — ① 멤버 API path `{projectIdOrKey}` 수용(`ProjectDirectory.resolveKeyToId`, key 해석 실패=404 존재숨김) ② `ProjectMemberResponse`에 `displayName?`+`username` 동봉(users 같은-BC 조인). 마이그레이션 0건, UUID 경로 회귀 0.
- **프론트(frontend-engineer)** — `/projects/$projectKey/settings/members`(requireAuth) 라우트. 목록(displayName 표시 + 역할 배지) + 추가(typeahead `/users?query=` 검색→선택→역할) + 역할변경(낙관적) + 제거(확인). 에러코드 8종→한국어 토스트, 비멤버 404→접근권한없음 화면. 라벨은 i18n 파일(E2E 셀렉터 정본). Zod 1:1.
- **E2E(qa-engineer)** — S1~S6 Playwright, MSW stateful refetch(가짜 그린 방지).
- 범위 밖: 부트스트랩/생성자-자동admin UI(생성 FR), 전체 페이지네이션.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회). gap2건 해소 — currentUserId는 WhoamiResponse로 확보(검증), 멤버 이름변환은 결정 C(응답 displayName 동봉, Jira식)로 해소. BLOCKER 0.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
