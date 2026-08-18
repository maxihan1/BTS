# ADR — 종료(DONE) 전환 시 Resolution 필수 강제는 워크플로우 게이트로 한다 (Jira 방식)

> 날짜: 2026-06-03
> 상태: 보류 (옵션 A는 최종 목표로 유지하되, 선행 FR "워크플로우 validator 런타임 결선" 완료에 의존)
> BC: issue-tracking (데이터) + project-workflow (게이트 설정)
>
> **2026-06-03 갱신 (plan 리뷰 BLOCKER).** 옵션 A의 전제였던 "기존 validator 프레임워크 재사용"이 성립하지 않음이 plan 리뷰에서 드러남 — `WorkflowValidatorFactory`/`WorkflowDefinitionRepository` production 구현이 없고(테스트 익명 object만), validator는 YAML로 시드 불가하며 `workflow_validators` 테이블은 런타임에 미사용. FR-WF-01이 남긴 미완성 비계. Maxi 결정(2026-06-03): 옵션 B로 우회하지 않고, **선행으로 "워크플로우 validator 런타임 결선" FR을 먼저 완성한 뒤 그 위에 옵션 A로 FR-IS-07을 재개**한다. 본 ADR의 옵션 A 설계는 그 선행 FR 완료 시점에 그대로 유효해진다.
> 관련: [[2026-05-29-issue-type-cross-bc-introduction]] (표준 세트 불변 + 커스텀 패턴) · [[2026-05-28-workflow-transition-identity-policy]] · [[2026-06-02-bulk-available-transitions-server-side]] (서버가 정답지 선례)

## 맥락

FR-IS-07은 이슈를 종료(StateCategory.DONE) 상태로 전환할 때 Resolution(해결 결과: Fixed / Won't Fix / Duplicate 등)을 필수로 선택하게 하고, 미설정 시 종료 전환을 거부한다.

이 "종료 시 Resolution 필수" 강제를 어디에 둘지가 핵심 갈림길이었다. project-workflow에 이미 전환 게이트 검증 프레임워크(`WorkflowValidator` SPI)가 존재하고, 그중 `RequiredFieldValidator`(`com.bts.workflow.validator.RequiredFieldValidator`)의 docstring 설정 예시가 **정확히 `{ "field": "resolution" }`** 이다 — 즉 이 검증기는 resolution 강제를 염두에 두고 설계됐다. 검증기는 전환 시 `TransitionContext.request.issueFields[field]`로 이슈 필드를 검사한다. `StateCategory.DONE` enum도 이미 존재한다.

issue-tracking의 전환 경로(`IssueApplicationService.transitionIssue`, `availableTransitions`)는 현재 `issueFields = mapOf("summary" to issue.summary)`로 제목만 워크플로우 포트에 전달한다.

## 결정

**옵션 A — 워크플로우 게이트.** 기존 `RequiredFieldValidator`를 재사용해 DONE 전환에 resolution 필수를 강제한다. Jira가 Resolve 전환에 Resolution 필수를 워크플로우 transition screen에서 설정하는 방식과 동일하며, "프론트/이슈가 하드코딩하지 않고 워크플로우가 정답지" 원칙([[2026-06-02-bulk-available-transitions-server-side]])과 일관된다.

- project-workflow: DONE 카테고리로 가는 전환의 워크플로우 정의(YAML/seed)에 `validators[].type = "RequiredField"`, config `{ "field": "resolution" }`를 설정한다.
- issue-tracking: 전환 시 워크플로우 포트로 보내는 `issueFields` 맵에 `"resolution"` 값을 추가 전달한다(`IssueApplicationService` 전환/가용전환 경로). resolution 미설정 시 검증기가 `ValidatorResult.Fail`을 반환 → 기존 `TransitionResult.ValidatorFailure` → `IssueTransitionNotAllowedException` 변환 경로를 그대로 탄다.
- Resolution 데이터 모델은 issue-tracking 소유: `resolutions` 테이블 + `issues.resolution_id`(nullable, FK 미적용 — BC 격리). 표준 세트(Fixed / Won't Fix / Duplicate / Cannot Reproduce / Done)는 불변 + 커스텀 추가 — IssueType 표준 5종 패턴과 동형([[2026-05-29-issue-type-cross-bc-introduction]]).

### 2 BC 걸침 처리

이 결정은 issue-tracking(데이터/전달)과 project-workflow(게이트 설정) 두 BC를 건드려 "한 PR = 한 BC" 규칙과 충돌한다. learning 2026-05-22의 선례(긴밀히 결합된 cross-BC 변경은 의도적으로 한 단위로 허용)를 적용하거나, plan 단계에서 PR을 분리(워크플로우 설정 → 이슈 데이터/전달)한다. 구체적 분리 여부는 bts-plan에서 결정한다.

## 대안 (기각)

- **옵션 B — issue-tracking 하드코딩 가드.** `transitionIssue`에서 목표 상태 `category == DONE && resolutionId == null → reject`를 issue-tracking 단독 구현. 단일 BC·단순·빠름. 그러나 (1) 워크플로우별 on/off 불가(항상 강제) — 어떤 프로젝트는 resolution을 선택으로 두고 싶을 수 있어 유연성 손실, (2) 이미 resolution을 위해 설계된 `RequiredFieldValidator` 프레임워크를 두고 유사 로직을 중복 구현, (3) Jira 실제 동작(워크플로우 transition 설정)과 불일치. 단일 BC 이점이 이 손실들을 넘지 못해 기각.

## 결과

- resolution 강제 로직이 워크플로우 게이트 단일 출처가 되어, 향후 다른 필수 필드(예: 종료 시 fix version) 추가도 같은 프레임워크로 확장 가능.
- issue-tracking 전환 경로는 `issueFields`에 resolution을 더하는 최소 변경만 받는다.
- 워크플로우별로 resolution 필수를 켜고 끌 수 있다(유연성 확보).
- 비용: project-workflow seed/YAML 변경 + 2 BC 동시 변경에 따른 PR 전략 결정이 필요(bts-plan에서 처리).
