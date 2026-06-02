# ADR: 이슈 권한 스킴 모델 — permission_schemes + role_permissions, adapter 통역, 멤버 게이트

> 날짜: 2026-06-02
> 상태: 채택 (PR #53 머지)
> 관련 FR: FR-PM-02 (이슈 등록/수정/삭제 권한 분리), 백엔드 D1~D5
> 관련 BC: identity-access (소유) + issue-tracking (stub/profile 배선)
> 관련 SDD: [12. 권한 모델](../sdd/12-permissions.md)
> 선행: FR-PM-01 [project-membership-model](2026-06-01-project-membership-model.md), [issue-permission-resolver-port](../adr/2026-05-22-issue-permission-resolver-port.md)

## 맥락

FR-PM-02는 "이슈 등록/수정/삭제를 역할별로 분리 통제"한다. 착수 시점 코드 조사 결과:

- issue-tracking BC에 `IssuePermissionResolver` 포트 + `AlwaysAllowIssuePermissionResolver` stub(`@Profile("!prod")`)이 실재. `IssueApplicationService`가 6 메서드에서 `permissionResolver.hasPermission(actor, IssuePermission, IssueScope)`를 명시 호출.
- `IssuePermission` enum 6종: VIEW/CREATE/UPDATE/TRANSITION/SOFT_DELETE/HARD_DELETE.
- 기존 ADR `issue-permission-resolver-port`는 실제 권한 판정 adapter 도입을 **FR-AU-12**로 적었다.
- FR-PM-01이 `ProjectRole`(PROJECT_ADMIN/MEMBER 2종) + `project_memberships`를 identity-access에 도입.
- SDD 12.6의 역할 8종은 FR-PM-01 ADR이 stale 폐기 선언.

## 결정

### D1. 권한 저장/평가 = 풀 Jira식 permission_schemes + role_permissions + 연결표

```
permission_schemes(id UUID PK, name, description, is_default BOOLEAN, created_at, updated_at)
role_permissions(id UUID PK, scheme_id UUID FK→permission_schemes ON DELETE CASCADE,
                 role VARCHAR CHECK(role IN ('PROJECT_ADMIN','MEMBER')),
                 permission_code VARCHAR,  -- SDD 12.3 명명: CREATE_ISSUE/EDIT_ISSUE/DELETE_ISSUE
                 UNIQUE(scheme_id, role, permission_code))
project_permission_scheme(project_id UUID PK, scheme_id UUID FK→permission_schemes, ...)
-- 여러 프로젝트가 같은 scheme_id 공유. 미매핑 프로젝트는 is_default 스킴 fallback.
```

스킴↔프로젝트는 **N:1 연결표**(여러 프로젝트가 한 스킴 공유) — Jira 권한 스킴 재사용 정신. 이번 시드는 기본 스킴 1개 + 연결표 빈 상태(전부 기본 fallback), 스킴 생성/할당 API는 후속. 그릇(풀 스킴 구조)은 크게, 초기 데이터는 기본 스킴 + 역할 2종.

### D2. FR-PM-02가 stub 교체를 흡수 (FR-AU-12 ≡ FR-PM-02)

`role_permissions`를 읽는 코드 없이 테이블만 머지하면 dead code + "실제로 막히는지" 검증 불가(CLAUDE.md §2). 따라서 본 PR이 `IdentityAccessIssuePermissionResolver`(`@Profile("prod")`)를 구현해 stub을 교체한다. `AlwaysAllow` stub은 기존 ADR대로 `@Profile("!prod")`로 dev/staging 유지.

**BC 격리 의식적 예외**: 본 PR이 issue-tracking의 stub/profile 배선에 손댐. 포트 ADR이 "adapter는 identity-access 소유"로 이미 설계 → 경계 위반 아님(learnings PR #13 옵션 C 동형).

### D3. 권한 어휘 2레이어 + adapter 통역

- issue-tracking `IssuePermission` enum(6종)은 그대로 유지 — 타입명이 이미 "Issue" 한정자, 호출자 무변경.
- identity-access 권한 카탈로그(role_permissions.permission_code) = SDD 12.3 명명(`CREATE_ISSUE` 등) 정본. 전 BC 공통 flat 카탈로그라 prefix 필수.
- adapter가 `IssuePermission` → permission_code를 Kotlin `when` exhaustive 매핑으로 통역. enum 값 추가/리네임 시 컴파일 에러로 drift 차단(코드-DB 문자열 silent 결합 회피).
- 매핑: CREATE→CREATE_ISSUE, UPDATE→EDIT_ISSUE, SOFT_DELETE→DELETE_ISSUE.

### D4. 매트릭스 역할 축 = ProjectRole 2종 재사용

role_permissions.role = FR-PM-01 `ProjectRole`(PROJECT_ADMIN/MEMBER). SDD 12.6 8종 미채택. "자기 이슈만 수정/삭제"(Reporter/Assignee 동적 역할)는 reporter_id 관계 평가라 본 FR 범위 외 → FR-PM-05/별도 확장. 미래 역할은 스키마 변경 없이 행 추가. 기본 스킴 매트릭스: PROJECT_ADMIN={CREATE/EDIT/DELETE}, MEMBER={CREATE/EDIT}.

### D5. 가드 방식 = 포트 명시 호출 유지

`@PreAuthorize` SpEL(plan/SDD 12.7 표기) 미도입. 실제 코드의 `permissionResolver.hasPermission()` 명시 호출 구조 유지(기존 포트 ADR 결정 + 호출자 무변경).

### D6. 범위 밖 권한 = 프로젝트 멤버 게이트

adapter는 6종 전부에 응답하되:
- 공통 1차 관문: actor가 해당 프로젝트 멤버인가(project_memberships) → 비멤버 전부 false(deny-by-default 하한).
- 범위 내 3종(CREATE/UPDATE/SOFT_DELETE): 멤버십 통과 후 유효 스킴 role_permissions 매트릭스 판정.
- 범위 밖 3종(VIEW/TRANSITION/HARD_DELETE): 멤버십 통과 시 true. FR-PM-04/05 도입 시 매트릭스 이관, 임시 게이트 제거.

`AlwaysAllow`("인증되면 누구나")보다 "비멤버 완전 차단"이 안전. scope=Issue는 issueKey prefix(==projectKey, IssueKey VO 형식 불변식) → `ProjectDirectory.resolveKeyToId`로 해석(이슈 데이터 미접근). scope=Global은 본 FR 미사용 → 보수적 false.

### D7. 계약 타입을 공용 모듈로 추출 (배선 후보 B, A 기각)

`IssuePermissionResolver`/`IssuePermission`/`IssueScope`를 shared-kernel `com.bts.shared.permission`으로 추출, issue-tracking과 identity-access 둘 다 그것만 의존. 인터페이스 시그니처는 `hasPermission(actorId: UUID, …)`로 — `ActorId`는 issue-tracking 도메인 VO로 잔류(호출부가 `actor.value` 전달).

- **기각된 A**(identity→issue 직접 의존): FR-PM-03~07 진행 시 권한팀이 모든 업무 모듈을 거꾸로 의존하는 god-dependency로 누적. 권한은 여러 BC가 의존할 공용 기반이어야 함(Jira "권한=공용 기반" 정신, learnings cross-BC 포트는 데이터 모듈 정의).
- shared-kernel이 이미 cross-BC 계약 모듈(WorkflowTransitionPort/UserLookupPort)이고 두 모듈이 이미 의존 → 추가 gradle 의존 0.

## 결과 / 트레이드오프

- **장점**: 권한 시스템이 표→adapter→이슈 API→통합테스트로 한 PR에 자기완결. BC 어휘 독립(통역층). 미래 역할/권한/스킴 확장 여지(연결표).
- **비용**: BC 격리 의식적 예외(issue-tracking 배선). adapter의 issueKey→project 역추적. 범위 밖 권한의 임시 멤버 게이트(FR-PM-04/05까지 한시적).
- **무효화**: 기존 `issue-permission-resolver-port` ADR의 "stub 교체 시점 = FR-AU-12"를 본 ADR이 "FR-PM-02 흡수"로 정정(해당 ADR에 정정 단락 추가).
- **검증**: 권한 매트릭스 전수 통합테스트 13케이스(@ActiveProfiles prod) + 스킴 공유 + Bean 배타. issue-tracking 회귀 0. PRE_EXISTING identity-access ktlint debt는 config/ktlint/baseline.xml 동결(우리 무관, neue 코드 미포함).
