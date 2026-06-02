# FR-WF-03 — 워크플로우 전이 validator/guard/PostAction 런타임 결선

> slug: fr-wf-03-validator-runtime-wiring
> type: backend (기반 인프라, 블래스트 반경 큼 — 리뷰 엄격)
> agent: backend-engineer (primary; db-engineer 시드 보조)
> BC: project-workflow
> 생성: 2026-06-03

## Brief

FR-WF-01(PR #10)이 워크플로우 전이 검증 프레임워크의 SPI 인터페이스(`WorkflowValidator`, `WorkflowValidatorFactory`, `WorkflowDefinitionRepository`)와 4종 validator 구현체만 만들고 **production 결선은 하지 않았다**(테스트 익명 object만 존재, YAML 시드 미지원, workflow_validators 테이블 런타임 미사용). 실동작은 FSM invariant 검증뿐.

이 FR은 그 비계를 완성한다 (Maxi 2026-06-03 결정, 전체 범위).
- WorkflowValidatorFactory / WorkflowDefinitionRepository production 구현 (workflow_validators / workflow_post_actions jOOQ 조회 + type→인스턴스 + jsonb config 파싱)
- 4종 validator(RequiredField/Permission/NotStatusCategory/CustomExpression) 런타임 연결
- PostAction(SetField/Notify) 런타임 결선
- YAML 워크플로우 정의에 validators/postActions 스키마 확장 + DB 시드 경로(YamlSeedService)
- WorkflowEngine production 부팅 검증(@SpringBootTest 전체 컨텍스트)

**선행 관계**: 이 FR이 FR-IS-07(이슈 Resolution 종료 전이 필수, 보류 중) 옵션 A의 선행. 완료 시 FR-IS-07 재개.

**참조**: 메모리 workflow-validator-framework-unwired. FR-IS-07 ADR docs/adr/2026-06-03-resolution-required-on-done-transition.md(보류). FR-WF-01 docs/plan/product/project-workflow.md §2.1.

## 도메인 정리

- **BC**: project-workflow 단일. 단 PostAction **실행/적용**은 호출자 BC(issue-tracking) 책임 — 기존 **GAP-2 결정**(WorkflowPostAction.kt SPI 주석: "적용은 호출자 BC 책임"). project-workflow는 PostActionPlan(FieldChange+DomainEvent) **계산만**.
- **새 용어**: 없음. 모든 개념(Validator, PostAction, ValidatorConfig, PostActionConfig, TransitionContext, TransitionPlan, PostActionPlan, FieldChange, GAP-2)이 FR-WF-01에서 이미 정의됨. glossary 추가 불필요.
- **핵심 발견 — 이미 있는 것 vs 빠진 것**.
  - **이미 존재(완성)**: validator 구현체 4종(RequiredField/Permission/NotStatusCategory/CustomExpression, `…/validator/`), PostAction 구현체 5종(SetField/Notify/AddWatcher/RunAutomation/CallWebhook, `…/postaction/`), SPI 인터페이스(WorkflowEngine.kt:30~96), ValidatorConfig/PostActionConfig DTO, 테이블 workflow_validators/workflow_post_actions(V200, type+config jsonb+display_order+transition_id), TransitionPlan(shared-kernel)/PostActionPlan(project-workflow).
  - **빠진 것(이 FR 범위)**: ① WorkflowValidatorFactory production 구현(type 문자열→기존 validator 인스턴스 + jsonb config 파싱), ② WorkflowPostActionFactory production 구현, ③ WorkflowDefinitionRepository production 구현(jOOQ로 두 테이블 조회), ④ YAML 정의에 validators/postActions 표현 + YamlSeedService가 두 테이블에 시드, ⑤ WorkflowEngine production 부팅 검증(@SpringBootTest 전체 컨텍스트).
  - **SPI 주석 명시**: "실제 구현은 후속 task 에서 등록"(WorkflowEngine.kt:30,73) — FR-WF-03이 그 후속.
- **기존 결정 충돌**: 없음. FR-WF-01의 의도된 후속 완성. GAP-2(PostAction 적용=호출자 BC) 준수.
- **관련 ADR**: 신규 후보(YAML validator/postaction 스키마 형태, config 파싱 계약) — spec/plan에서 결정.
- **spec으로 넘길 미결 질문**.
  - Q1. validator 적용 단계 구분(availability/execution) — availableTransitions가 EXECUTION validator(RequiredField)를 건너뛰어야 DONE 전이가 목록에서 안 사라짐(FR-IS-07 전제). FR-WF-03에 포함할지 FR-IS-07로 넘길지. (포함 권장 — 프레임워크 정확성)
  - Q2. PostAction 적용(GAP-2 호출자측) — issue-tracking이 PostActionPlan FieldChange를 실제 적용하는 배선이 이 FR 범위인지(cross-BC), 또는 project-workflow 계산 배선까지만.
  - Q3. YAML validators/postActions 스키마 형태 + jsonb config 파싱(Map<String,Any?>) 계약.
  - Q4. 시드 범위 — 기존 4종 표준 워크플로우 YAML에 실제 validator/postaction을 넣을지, 스키마/배선만 하고 시드는 비울지.
- **grill-with-docs 스킵**: 새 용어 0건, FR-WF-01 도메인 완비. 기술 배선 작업이라 직접 분석으로 충분(메모리 bts-spec-office-hours-mismatch 동일 논리).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
