<!-- 멤버 API 식별자를 Jira식 projectIdOrKey로 확장하는 결정 (FR-PM-01 D6/D7) -->

# ADR. 프로젝트 멤버 API — projectIdOrKey 수용 (Jira 정합)

> 날짜: 2026-06-01
> 상태: 수락(Accepted)
> 관련 FR: FR-PM-01 D6(프론트) / D7(E2E)
> 보강 대상: [2026-06-01-project-membership-model](./2026-06-01-project-membership-model.md) (멤버 백엔드 D1~D5, PR #48)

## 맥락

FR-PM-01 백엔드(PR #48)는 멤버 API path를 `/api/v1/projects/{projectId}/members`로 두고 `projectId`를 **UUID 전용**으로 파싱했다. 그러나 프론트 멤버 관리 화면을 붙이는 단계에서 두 가지 불일치가 드러났다.

1. **BC 간 식별자 불일치**. 같은 `projects` 자원을 project-workflow BC의 `ProjectWorkflowSchemeController`는 `/api/v1/projects/{projectKey}/...`(사람이 읽는 key, 예 `ATLAS`)로 받는데, identity-access의 멤버 API만 UUID로 받는다.
2. **프론트 라우트 관례**. 기존 프론트는 `/projects/$projectKey/settings/workflow-scheme`로 projectKey 기반 설정 경로를 쓴다. 멤버 화면도 같은 관례를 따르려면 URL에 projectKey가 와야 한다.
3. **projectKey→UUID 변환 경로 부재**. `GET /api/v1/projects` 같은 목록/조회 API가 없어, 프론트가 projectKey만으로 UUID를 얻을 방법이 없다.

## 결정

**멤버 API의 path 변수를 `{projectIdOrKey}`로 확장한다.** Jira의 프로젝트 REST(`/rest/api/3/project/{projectIdOrKey}`)와 동일한 정책 — 입력이 UUID 형식이면 `projects.id`로, 아니면 `projects.key`(UNIQUE)로 해석한다.

- `ProjectDirectory` 포트(cross-BC read-only, FK 없음)에 `resolveKeyToId(key): UUID?` 추가. `SELECT id FROM projects WHERE key = :key AND deleted_at IS NULL`.
- 컨트롤러는 path 값이 UUID로 파싱되면 그대로 사용, 실패하면 key로 간주해 `resolveKeyToId`로 변환한다. 변환 실패(미존재/soft-deleted)는 기존 `ProjectNotFound`(404 존재숨김)와 동일 처리 — 정보노출 방어 일관.
- 프론트 라우트는 `/projects/$projectKey/settings/members`, API 클라이언트는 projectKey를 그대로 path에 전달. 실 백엔드와 1:1 정합(별도 resolver 엔드포인트 invent 금지 — `frontend-zod-backend-dto-contract-gap` 교훈 회피).

`projects` 테이블은 `id UUID` + `key VARCHAR(10) UNIQUE`를 모두 보유(issue-tracking V001)하므로 변환은 단일 인덱스 조회로 가능하다.

## 결과

**긍정**.
- Jira 멘탈모델과 BTS 자체(project-workflow) 관례에 정합. 프론트 설정 경로가 BC 무관하게 projectKey로 통일.
- 프론트가 실 백엔드와 1:1 — MSW(Mock Service Worker, 브라우저 네트워크 가로채는 목) 위에서만 도는 가짜 정합 회피.
- 별도 projectKey→id 변환 엔드포인트를 새로 만들지 않음(자원 의미 중복 방지).

**부정/비용**.
- 멤버 백엔드(identity-access)에 작은 슬라이스가 추가됨 — 이번 PR이 순수 프론트가 아니라 frontend + backend(security-engineer) 혼합. 단 BC는 identity-access 하나로 유지(BC 격리 원칙 준수), cross-BC는 `projects` 읽기뿐.
- UUID 형식 판별을 path 파싱에 둠 — key 정규식(`^[A-Z][A-Z0-9]{1,9}$`)과 UUID 형식은 교집합이 없어 모호성 없음(key는 대문자 시작, UUID는 하이픈 포함 hex).

## 대안

- **A. 프론트만, UUID 라우트**(`/projects/$projectId/settings/members`). 백엔드 무변경이나 workflow-scheme의 projectKey 관례와 불일치하고 URL에 UUID 노출. 기각.
- **B. projectKey→id 변환 전용 엔드포인트 추가**. 자원 의미가 멤버 API와 중복되고 왕복 1회 증가. 기각.
- **C. 채택 — 멤버 API가 projectIdOrKey 수용**. Jira/BTS 정합 + 1왕복. 채택.

## 범위 밖 (이연)

- **부트스트랩/생성자-자동admin UI**. 멤버 0명 프로젝트의 첫 ADMIN 시동은 백엔드 부트스트랩 규칙(PR #48)으로 존재하나, 프론트에는 노출하지 않는다. 프로젝트 생성 FR 도입 시 생성자=자동 admin으로 연결. 이번 화면은 "이미 PROJECT_ADMIN인 사용자의 멤버 CRUD"만 다루고, 비멤버는 404→접근 권한 없음 화면.
