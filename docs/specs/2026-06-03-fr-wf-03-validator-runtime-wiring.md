# FR-WF-03 워크플로우 전환 validator/guard/PostAction 런타임 결선 — 스펙

> slug: fr-wf-03-validator-runtime-wiring
> type: backend (기반 인프라) | BC: project-workflow 단일
> 작성: 2026-06-03
> 선행 of: FR-IS-07(옵션 A). 참조: 메모리 workflow-validator-framework-unwired, FR-WF-01 §2.1

## 개요

FR-WF-01이 SPI 인터페이스 + validator 4종 + PostAction 5종 구현체만 만들고 production 결선을 후속으로 미뤘다(SPI 주석 "실제 구현은 후속 task에서 등록"). 이 FR이 그 후속 — 팩토리/리포지토리/시드 경로/부팅을 결선해 워크플로우 전환 검증·후처리 프레임워크를 실제 동작시킨다.

**범위 결정(2026-06-03 Maxi).**
- D8=A: validator 적용 단계(availability/execution) 구분 포함.
- D9=A: PostAction은 **계산 배선까지만**(project-workflow가 PostActionPlan 반환). 실제 적용(GAP-2 호출자 BC)은 후속.
- 기존 4종 표준 워크플로우 YAML은 validator-free 유지(동작 변화 0). 배선은 테스트로 검증.

## 사용자 시나리오 (Given-When-Then)

> "사용자"는 이 FR에선 워크플로우를 호출하는 상위 BC(issue-tracking) + 워크플로우 정의를 시드하는 시스템.

### S1. validator 설정된 전환 — plan(실행) 시 평가
- **Given** 어떤 전환에 `RequiredField(field=resolution)` validator가 설정돼(workflow_validators) 있다.
- **When** issue-tracking이 그 전환을 `plan()`으로 실행 요청하고 issueFields에 resolution이 없다.
- **Then** WorkflowEngine이 WorkflowDefinitionRepository로 validator 설정을 읽고, WorkflowValidatorFactory로 인스턴스를 만들어 평가, Fail → `WorkflowValidatorFailureException` → `TransitionResult.ValidatorFailure`.

### S2. validator 설정된 전환 — availableTransitions(목록)에선 execution validator 건너뜀
- **Given** 같은 전환에 EXECUTION 단계 validator(RequiredField)가 설정돼 있다.
- **When** issue-tracking이 `availableTransitions()`로 가용 전환을 조회(resolution 미설정).
- **Then** 그 전환이 목록에 **포함**된다(EXECUTION validator는 목록 평가에서 건너뜀). AVAILABILITY validator(Permission/NotStatusCategory)는 여전히 평가돼 실패 시 제외.

### S3. PostAction 설정된 전환 — 계산되어 plan에 누적
- **Given** 어떤 전환에 `SetField`/`Notify` PostAction이 설정돼 있다.
- **When** issue-tracking이 그 전환을 `plan()`으로 실행.
- **Then** WorkflowEngine이 PostAction을 factory로 만들어 `evaluate(ctx)` 호출, 결과 PostActionPlan(FieldChange/DomainEvent)을 TransitionPlan에 누적해 반환. (적용은 호출자 책임 — 이 FR 범위 밖.)

### S4. YAML 시드 — validators/postActions 정의가 테이블에 반영
- **Given** 워크플로우 YAML의 전환에 `validators:`/`post_actions:` 리스트가 정의돼 있다.
- **When** YamlSeedService가 부팅 시 시드한다.
- **Then** workflow_validators / workflow_post_actions 테이블에 type/config/display_order/transition_id가 삽입되고, 런타임 조회로 평가에 사용된다.

### S5. production 컨텍스트 부팅
- **Given** 전체 Spring 컨텍스트(@SpringBootTest).
- **When** 앱이 부팅한다.
- **Then** WorkflowEngine + WorkflowValidatorFactory + WorkflowPostActionFactory + WorkflowDefinitionRepository 빈이 모두 결선돼 컨텍스트가 정상 기동한다(현재는 production 빈 부재로 미검증).

## 기능 요구사항 (FR)

- **FR1** `WorkflowValidatorFactory` production 구현(@Component). type 문자열 → 기존 validator 인스턴스. **타입별 생성 로직** 필요(validator가 생성자 인자 받음: RequiredFieldValidator(field), PermissionValidator(...), NotStatusCategoryValidator(...), CustomExpressionValidator(SpelEvaluator+expression)). config Map에서 인자 추출. CustomExpression은 기존 SpelEvaluator @Bean 주입. 미지원 type/필수 config 누락 → IllegalArgumentException.
- **FR2** `WorkflowPostActionFactory` production 구현(@Component). type(SetField/Notify/AddWatcher/RunAutomation/CallWebhook) → 기존 PostAction 인스턴스(생성자 인자 config에서 추출). (계산만, 적용 안 함.)
- **FR3** `WorkflowDefinitionRepository` production 구현(@Repository, jOOQ). findValidators/findPostActions(transition) — workflow_validators/workflow_post_actions를 transition_id로 조회, display_order asc, ValidatorConfig/PostActionConfig 매핑.
- **FR4** validator 적용 단계 구분. `WorkflowValidator`에 `phase: ValidatorPhase`(AVAILABILITY/EXECUTION). RequiredField=EXECUTION, Permission/NotStatusCategory/CustomExpression=AVAILABILITY(CustomExpression은 보수적으로 AVAILABILITY). `availableTransitions`는 AVAILABILITY만, `plan`은 전부 평가.
- **FR5** YAML 스키마 확장. 워크플로우 YAML 전환 항목에 optional `validators: [{type, config, display_order?}]` / `post_actions: [{type, config, display_order?}]`. YamlSeedService가 두 테이블에 시드.
- **FR6** config 파싱 계약. jsonb ↔ Map<String,Any?>. 직렬화/역직렬화 일관(시드 시 Map→jsonb, 조회 시 jsonb→Map).
- **FR7** WorkflowEngine production 부팅 — @SpringBootTest 전체 컨텍스트 기동 검증.

## API 인터페이스
- 신규 REST 없음. 내부 SPI 결선 + 시드만. 기존 `WorkflowTransitionPort`(plan/availableTransitions) 시맨틱 유지(이제 validator/postaction이 실제 평가됨).

## 데이터 모델 변경
- 테이블 신규 없음(workflow_validators/workflow_post_actions는 V200 기존).
- **jOOQ 상수 — 미러 불필요(검증 완료).** project-workflow codegen은 `V200__init_workflow.sql`을 직접 읽는다(build.gradle.kts `TC_INITSCRIPT=file:.../V200`). V200에 두 테이블이 있으므로 `WORKFLOW_VALIDATORS`/`WORKFLOW_POST_ACTIONS` 상수가 빌드 시 자동 생성된다(`src/generated/jooq/`는 gitignore, 매 빌드 재생성). issue-tracking의 init_codegen 미러 구조(메모리 jooq-init-codegen-mirror)와 **다름** — 여긴 해당 없음. FR-WF-03은 새 마이그레이션 없음(시드는 YamlSeedService 런타임).
- 기존 4종 표준 워크플로우 YAML은 validator/postaction 미추가(동작 변화 0, 회귀 방지).

## 엣지 케이스
- E1. 미지원 validator/postaction type → IllegalArgumentException(시드 시 빠른 실패 vs 런타임? 시드 시 검증 권장).
- E2. config 누락 필드(예 RequiredField에 field 없음) → 명확한 예외. validator 생성 시 검증.
- E3. transition에 validator 0건 → 빈 리스트, 전환 그대로 통과(현재 동작 유지).
- E4. CustomExpression(SpEL) — SpelEvaluator 기존 @Bean 존재. factory가 이를 주입해야. 타임아웃(WorkflowExpressionTimeoutException) 경로 유지.
- E5. PostAction 평가 중 예외 → plan 전체 실패? 또는 격리? 기존 WorkflowEngine plan 흐름 따름(검증).
- E6. availableTransitions의 syntheticRequest 경로(WorkflowEngine.kt:290)와 plan 경로가 phase 필터 도입 후 일관되게 동작(같은 워크플로우로 "목록 포함 + plan 거부" 동시 검증).
- E7. 시드 idempotency — 재부팅 시 중복 삽입 방지(기존 YamlSeedService 패턴 따름).

## 제약 조건
- BC 격리: project-workflow 단일. shared-kernel 타입(TransitionPlan)만 노출, 내부 타입 비노출.
- GAP-2 준수: PostAction 적용 안 함(계산만).
- 기존 전환 동작 회귀 0 — validator-free 워크플로우는 종전과 동일.
- 프로파일 한정 빈 부팅(메모리 profile-scoped-bean-boot-failure): 새 @Component/@Repository가 모든 프로파일에서 부팅되는지 모듈 전체 test로 확인.
- ktlint KDoc 중괄호/특수문자 주의(메모리 ktlint-kdoc-brace-parse-failure).

## 측정 가능한 완료 기준
- [ ] @SpringBootTest 전체 컨텍스트 기동(WorkflowEngine 4빈 결선) — S5.
- [ ] RequiredField 설정 전환: plan 시 거부 + availableTransitions 시 포함(phase 구분) — S1/S2, Testcontainers 통합.
- [ ] SetField/Notify PostAction: plan 결과 TransitionPlan에 FieldChange/DomainEvent 누적 — S3.
- [ ] YAML validators/post_actions 시드 → 테이블 반영 → 런타임 평가 — S4.
- [ ] 기존 워크플로우 전환 E2E/통합 회귀 0(validator-free 동작 불변).
- [ ] 4모듈 detekt + ktlint green(메모리 subagent-ktlint-false-green).

## 스펙 외 (후속)
- PostAction 실제 적용(GAP-2 호출자측, issue-tracking) — 그것을 쓰는 기능 FR과 함께.
- AddWatcher/RunAutomation/CallWebhook의 end-to-end(cross-BC: watcher/automation/webhook).
- product validator 시드(resolution 등) — 각 소유 FR(FR-IS-07)이 담당.
