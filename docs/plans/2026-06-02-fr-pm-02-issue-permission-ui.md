# FR-PM-02 D6/D7 — 이슈 권한 기반 액션 버튼 비활성화 UI + E2E

> slug: fr-pm-02-issue-permission-ui
> type: ui
> agent: frontend-engineer (E2E는 qa-engineer 보조)
> 생성: 2026-06-02

## Brief

FR-PM-02 D6/D7 후속 PR. 이슈 권한 스킴(PR #53, 백엔드 D1~D5 머지 완료)에 따라
프론트엔드에서 권한 없는 액션(이슈 등록/수정/삭제) 버튼을 비활성화하는 권한 UI +
Playwright E2E. FR-PM-01 D6/D7(PR #50, `[ui]` 단일 PR, D6 프론트 + D7 E2E) 패턴 답습.

classify 원결과: type=qa(오분류, E2E 키워드) → FR-PM-01 선례로 type=ui 정정.

## 도메인 정리

### BC
- **주 BC: issue-tracking** — 권한 조회 엔드포인트 신설 + 이슈 화면 프론트(D6) + E2E(D7).
- **재사용: identity-access** — 실제 권한 평가기 `IdentityAccessIssuePermissionResolver`(PR #53). 직접 import 아님, shared-kernel 포트 `IssuePermissionResolver`로 호출 → BC 격리 유지.

### 핵심 도메인 결정 (Maxi 승인 2026-06-02)
**프론트의 권한 인지 방식 = Jira `mypermissions` 방식 (서버가 single source of truth).**

- 백엔드 PR #53은 권한 **강제(enforcement)** 만 구현했다. 권한 없으면 403 + `ACCESS_DENIED`(RFC 7807 ProblemDetail). 그러나 프론트가 미리 물어볼 **권한 조회 API는 없고**, whoami 응답에도 역할(ProjectRole)이 없다(username/email/authMethod/userId만).
- 따라서 "권한 없는 버튼 비활성화"를 하려면, **백엔드에 권한 조회 엔드포인트를 신설**하고 프론트는 그 응답으로만 버튼을 켜고 끈다. 프론트는 권한 매트릭스를 하드코딩하지 않는다(drift 차단).
- 이는 ADR `2026-06-02-issue-permission-scheme-model.md`가 채택한 "풀 Jira식 스킴"과 일관. 권한 조회도 Jira식(`mypermissions`)으로 통일.

### 영향
- **신규**: 권한 조회 REST 엔드포인트(예: `GET /api/v1/issue-permissions/mine?projectKey=&issueKey=`, 형태는 spec 확정). 응답은 권한별 boolean(예: `{ CREATE: true, UPDATE: true, SOFT_DELETE: false }`).
- **신규**: 프론트 권한 조회 훅 + 이슈 등록/수정/삭제 버튼 disabled 분기.
- **신규**: Playwright E2E(권한 있음/없음 시나리오).
- **resolver 포트**: 현재 `hasPermission(actorId, permission, scope): Boolean`만 존재. 조회 API가 권한별 N회 호출하면 포트 변경 불필요(issue-tracking 안에서 해결, BC 격리 유지). 포트에 `getPermissions(...)` 추가 여부 + N회 호출 성능은 plan에서 검토.
- IssuePermission enum: VIEW / CREATE / UPDATE / TRANSITION / SOFT_DELETE / HARD_DELETE(예약). UI 대상은 CREATE / UPDATE / SOFT_DELETE.

### 기존 결정 충돌
- 없음. ADR `2026-06-02-issue-permission-scheme-model.md`가 "권한 조회 API는 후속"으로 미뤘던 것을 이번에 **권한 조회만** 당겨 구현(스킴 CRUD API는 여전히 후속).
- 신규 ADR: `docs/decisions/2026-06-02-issue-permission-query-api.md` (Jira mypermissions 방식 결정 기록).

### 보안
- 권한 조회 API는 JWT 인증 필수, **본인 권한만** 조회(actorId = 인증 사용자). PAT 정책은 spec에서. security-engineer 검토 영역.

### 대안 (기각)
- **프론트 매트릭스 하드코딩(FR-PM-01 답습)** — 백엔드 무변경/scope 작으나 drift 위험. 기각(Jira식 채택).
- **낙관적 UI(버튼 노출+403 토스트)** — "비활성화" 요구 미충족. 기각.

### 관련 ADR
- 기존: [docs/decisions/2026-06-02-issue-permission-scheme-model.md](../decisions/2026-06-02-issue-permission-scheme-model.md)
- 신규: [docs/decisions/2026-06-02-issue-permission-query-api.md](../decisions/2026-06-02-issue-permission-query-api.md)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
