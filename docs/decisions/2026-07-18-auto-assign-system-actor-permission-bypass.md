<!-- EC-1 D10 auto-assign 이 SYSTEM_ACTOR(nil UUID)로 권한 검사에 막혀 500 나던 선재 결함 수정 — 우회를 auto-assign 에만 국한 -->

# ADR — auto-assign SYSTEM_ACTOR 권한 우회

> 날짜: 2026-07-18
> 상태: 채택 (FR-PJ-01, project-management-crud PR-2 · T13 hot-fix)
> BC: project-workflow
> 관련 SDD: [07. 워크플로우 엔진](../sdd/07-workflow-engine.md)
> 스펙/plan: [spec](../specs/2026-07-17-project-management-crud.md) (S10) · [plan](../plans/2026-07-17-fr-pj-pr-2-r6-r6-b-fr-pj-01.md) (Task 13)
> 발견: FR-PJ-01 T12 S10 조립 부팅 검증(빈 DB → 프로젝트 생성 → 이슈 생성)

## 맥락 — FR-PJ-01 이 노출한 선재 결함

신규 프로젝트에 첫 이슈를 만들면, 그 프로젝트에 워크플로우 스킴 배정(`project_workflow_scheme_assignments`)이 없어 EC-1 D10 **auto-assign** 이 발동한다 — 시스템이 `software-scheme` 을 자동 배정한다. 그 배정을 실행하는 행위자가 `WorkflowSchemeApplicationService.SYSTEM_ACTOR`(nil UUID sentinel `00000000-...`)다.

그런데 prod 판정기 `IdentityAccessWorkflowSchemePermissionResolver.hasProjectPermission` 은 `membershipRepo.findByProjectAndUser(projectId, actorId)` 로 actor 의 **프로젝트 멤버십**을 먼저 요구한다. SYSTEM_ACTOR 는 `users` 에 존재하지 않는 합성 sentinel 이라 어떤 프로젝트의 멤버도 될 수 없어, auto-assign 이 prod 에서 **항상** `WorkflowSchemeAccessDeniedException` → 500 으로 실패한다.

**이 결함은 이미 알려져 있었다.** `infra/local/seed-project.sql:10-12` 가 그 500 을 문서화하며 "스킴 배정을 미리 심어 auto-assign 을 회피"한다. FR-PJ-01 이전에는 모든 프로젝트가 seed 로 만들어져 이 우회가 항상 적용됐다. **FR-PJ-01 이 프로젝트 생성을 REST 로 열면서, seed 우회 없이 새 프로젝트를 만드니 선재 결함이 신규 노출**됐다.

패턴은 R6-B(같은 PR)와 동형이다 — 경로를 열면 선재 결함이 신규 노출(`permitall-opens-preexisting-body-buffer-dos` 계열). R6(매핑 백필)와는 다른 층위다: R6 는 `workflow_scheme_issue_type_mappings`(매핑), 이 결함은 `project_workflow_scheme_assignments`(스킴 배정) + 권한 판정.

`WorkflowSchemeApplicationService.assignToProject` 의 KDoc 은 *"SYSTEM_ACTOR 도 허용"* 이라 **의도**를 이미 적었으나, 구현이 그 의도를 지키지 않았다(권한 검사를 무조건 수행). 본 ADR 은 그 의도를 실제 구현으로 만든다.

## 결정

`assignToProject` 가 **SYSTEM_ACTOR(nil UUID sentinel)일 때만** `requirePermission` 을 건너뛴다.

```kotlin
if (actorUuid != SYSTEM_ACTOR_UUID) {
    permissionResolver.requirePermission(actorUuid, ASSIGN_SCHEME, WorkflowSchemeScope.Project(projectKey))
}
// 이후 배정 INSERT (우회해도 배정 로직은 동일 실행)
```

- **사용자 명시 배정은 권한 검사 유지.** 실 actor UUID(RFC 4122 V4)는 nil sentinel 과 구조적으로 충돌 불가라, `assignToProject` 를 사용자가 호출하는 경로는 그대로 `requirePermission` 을 통과해야 한다.
- **우회는 software-scheme auto-assign 에만.** 호출부는 `findAssignedScheme`·`WorkflowResolverImpl.resolveFor` 둘뿐이고, 둘 다 `SOFTWARE_SCHEME_KEY`(표준 스킴) 하드코딩. 커스텀 스킴을 SYSTEM_ACTOR 로 배정하는 경로는 없다.

### 기각안

- **prod 판정기(`IdentityAccessWorkflowSchemePermissionResolver`)가 SYSTEM_ACTOR 를 특수처리** — identity-access 를 건드려 이 PR 의 BC 가 **3개(D10 초과)**가 된다. 그리고 "nil = 통과" 매직을 권한 판정기에 심는 게 우회 지점보다 위험하다.
- **프로젝트 생성 시 software-scheme 을 함께 배정(auto-assign 회피)** — FR-PJ-01 생성 트랜잭션에 세 번째 쓰기(assignment)를 얹어 불변식 I1(생성 원자성)의 범위를 넓히고, auto-assign 결함 자체는 다른 경로로 남는다.

## 왜 안전한가 (악용 표면 없음)

load-bearing 방어는 3가지다 — 이 함수의 외부 도달 경로는 **project-workflow** 의 `ProjectWorkflowSchemeController` 이지 issue-tracking 이 아니다.

1. **외부 진입점이 우회 전에 무조건 권한을 검사한다.** 유일한 외부 도달 경로 `ProjectWorkflowSchemeController.assignScheme`(PUT `/{projectKey}/workflow-scheme`)은 서비스 호출 **전에** 실 actor 로 `requirePermission(ASSIGN_SCHEME, Project)` 를 수행한다. 이 우회는 그 컨트롤러 게이트를 건드리지 않는다.
   > ⚠️ **주의 — project-workflow `ActorId`(`port/outbound/PermissionResolver.kt`)는 nil UUID 를 거부하지 않는다** (UUID 정규식만 검사). issue-tracking `ActorId`(`require(value != ZERO_UUID)`)와 달리 nil 방어가 없다. 따라서 **컨트롤러의 `requirePermission` 선게이트가 load-bearing 이다** — "서비스가 권한을 처리하니 중복"이라며 제거하면 nil 관용 CurrentActor 가 노출돼 우회 근거가 무너진다.
2. **nil sentinel 은 실제 주체가 아니다.** prod 판정기가 비멤버를 거부하므로 nil 은 설령 검사에 도달해도 거부된다. 우회는 그 거부를 auto-assign 에만 통과시키는 것이지 실 사용자에게 권한을 부여하지 않는다.
3. **software-scheme 한정.** 내부 호출부 2곳 다 표준 스킴만 배정.

절대규칙 4(인증없는 엔드포인트)·5(CSRF)·DATA §1.5(인증우회) 위반 아님 — 엔드포인트는 여전히 인증+권한을 요구하고, 우회는 외부 입력으로 재현 불가능한 내부 sentinel 에만 국한된다. cross-model 리뷰(code-reviewer + security specialist)가 세 caller 를 전수 열거해 fail-open 없음을 독립 확인했다.

## 후속

- **`seed-project.sql:10-12` 우회 주석 정리** — 이제 auto-assign 이 SYSTEM_ACTOR 로 정상 동작하므로, "미리 배정해 회피"라는 주석의 전제가 바뀌었다(별건, 로컬 시드 문서).
- **project-workflow `ActorId` 의 nil 거부 강화(defense-in-depth 대칭)** — issue-tracking 처럼 nil 을 거부하면 컨트롤러 선게이트 의존이 줄어든다. 단 `SYSTEM_ACTOR = ActorId(nil UUID)` 생성이 그 강화와 충돌하므로 SYSTEM_ACTOR 를 별도 타입/특수 UUID 로 바꾸는 설계 변경이 선행돼야 한다(폭발 반경 큼, 별건).
