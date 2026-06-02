# ADR: 이슈 권한 조회 API (Jira mypermissions 방식)

- 날짜: 2026-06-02
- 상태: Accepted
- 관련 FR: FR-PM-02 (D6/D7)
- 관련 ADR: [2026-06-02-issue-permission-scheme-model.md](./2026-06-02-issue-permission-scheme-model.md)
- 관련 PR: #55 (이 작업), #53 (백엔드 D1~D5 enforcement)

## 컨텍스트

FR-PM-02 D6/D7은 "권한 없는 이슈 액션(등록/수정/삭제) 버튼을 비활성화"하는 프론트 권한 UI다.
그런데 현재 백엔드 상태는 다음과 같다.

- 백엔드(PR #53)는 권한 **강제(enforcement)** 만 구현했다. 이슈 생성=`POST /api/v1/issues`,
  수정=`PATCH /api/v1/issues/{key}`, 삭제=`DELETE /api/v1/issues/{key}`에 권한 검사가 걸려,
  권한 없으면 403 + `ACCESS_DENIED`(RFC 7807 ProblemDetail)를 반환한다.
- 프론트가 "내가 이 작업을 할 수 있는가"를 **미리 조회할 API는 없다**.
- whoami(`GET /api/v1/users/me/whoami`) 응답에 역할(ProjectRole)이 없다(username/email/authMethod/userId만).
  역할은 `GET /api/v1/projects/{key}/members`(멤버 목록)에서 자기 userId를 찾아야 알 수 있다.

따라서 버튼 비활성화의 "권한 근거"를 프론트가 어디서 얻을지 결정해야 한다.

## 결정

**프론트의 권한 인지 방식 = Jira `mypermissions` 방식. 서버가 유일한 정답지(single source of truth).**

백엔드에 권한 조회 엔드포인트를 신설하고, 프론트는 그 응답으로만 버튼을 켜고 끈다.
프론트는 "역할→권한" 매트릭스를 하드코딩하지 않는다.

- 엔드포인트(형태는 spec에서 확정): 현재 인증 사용자가 특정 프로젝트/이슈에 대해 가진
  IssuePermission 집합을 boolean 맵으로 반환. 예: `{ CREATE: true, UPDATE: true, SOFT_DELETE: false }`.
- 위치: **issue-tracking BC**. 이미 enforcement에 쓰는 shared-kernel 포트 `IssuePermissionResolver`를
  재사용해 권한을 평가한다(identity-access 직접 import 없음 → BC 격리 유지).
- 보안: JWT 인증 필수, 본인 권한만 조회(actorId = 인증 사용자).

이는 상위 ADR이 채택한 "풀 Jira식 권한 스킴 구조"와 일관된다. 스킴이 Jira식이면 권한 조회도
Jira식(`mypermissions`)으로 통일하는 것이 도메인 일관성에 맞다.

## 대안과 기각 사유

1. **프론트 매트릭스 하드코딩 (FR-PM-01 D6/D7 답습)**
   - 멤버 API의 역할을 받아 "역할→권한" 매트릭스를 프론트에 두는 방식.
   - 장점: 백엔드 무변경, scope 작음.
   - 기각 사유: 백엔드 `role_permissions` 매트릭스와 프론트 하드코딩이 어긋나면(drift) 버튼 상태가
     부정확해진다. 현재는 역할 2종·기본 스킴 1개라 위험이 낮지만, FR-PM-03~07로 권한이 늘면 커진다.
     권한 규칙을 두 곳에 두지 않는다는 원칙(과거 learnings의 contract-gap 교훈)에 반한다.

2. **낙관적 UI (버튼 항상 노출 + 403 토스트)**
   - 기각 사유: "권한 없는 버튼 비활성화"라는 요구사항 자체를 충족하지 못한다.

## 영향

- 신규 권한 조회 REST 엔드포인트(issue-tracking).
- 신규 프론트 권한 조회 훅 + 이슈 등록/수정/삭제 버튼 disabled 분기.
- 신규 Playwright E2E(권한 있음/없음).
- resolver 포트는 현재 `hasPermission(actorId, permission, scope): Boolean`만 제공한다. 조회 API가
  권한별 N회 호출하면 포트 변경 불필요(issue-tracking 안에서 해결). 포트에 일괄 조회 메서드를 추가할지,
  N회 호출 성능이 수용 가능한지는 plan에서 검토한다.
- 상위 ADR이 "후속"으로 미뤘던 권한 조회 API를 이번에 **권한 조회만** 당겨 구현한다(스킴 CRUD는 여전히 후속).

## 비고

프론트 권한 표시는 어디까지나 UX 힌트다. 진짜 보안 경계는 백엔드 enforcement(403)이며, 이 결정으로
바뀌지 않는다. 권한 조회 API가 일시적으로 부정확해도 보안 구멍은 아니다(서버가 최종 차단).
