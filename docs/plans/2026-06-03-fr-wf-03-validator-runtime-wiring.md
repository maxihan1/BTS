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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
