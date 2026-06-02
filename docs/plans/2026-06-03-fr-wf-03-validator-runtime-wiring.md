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

## 스펙

전체 스펙. [docs/specs/2026-06-03-fr-wf-03-validator-runtime-wiring.md](../specs/2026-06-03-fr-wf-03-validator-runtime-wiring.md)

핵심.
- WorkflowValidatorFactory/PostActionFactory/DefinitionRepository production 결선(@Component/@Repository) — 기존 validator 4종/postaction 5종은 이미 구현됨, factory가 타입별 생성(생성자 인자 config에서 추출, CustomExpression은 SpelEvaluator 주입).
- validator 단계 구분(D8=A): WorkflowValidator.phase(AVAILABILITY/EXECUTION). RequiredField=EXECUTION. availableTransitions는 AVAILABILITY만, plan은 전부.
- PostAction 계산까지만(D9=A): PostActionPlan 반환, 적용은 GAP-2 호출자 후속. 단일 BC.
- YAML validators/post_actions 스키마 확장 + YamlSeedService 시드(두 테이블). 기존 4종 워크플로우는 validator-free 유지(회귀 0).
- @SpringBootTest 전체 컨텍스트 부팅 검증(현재 production 빈 부재).

확정 결정.
- D8=A validator phase 구분 포함. D9=A PostAction 계산 배선까지(단일 BC).
- 마이그레이션 신규 없음(두 테이블 V200 기존). jOOQ 상수는 V200 직접 codegen이라 자동 생성 — init_codegen 미러 불필요(issue-tracking과 구조 다름).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 아님 — 오히려 함정 회피 2건 확인.
- (확인1) jOOQ 상수 WORKFLOW_VALIDATORS/POST_ACTIONS는 V200 직접 codegen으로 자동 생성. init_codegen 미러 트랩(jooq-init-codegen-mirror) 여긴 해당 없음.
- (확인2) validator/postaction 구현체가 생성자 인자를 받아 factory가 타입별 생성 필요(config Map→인자). CustomExpression은 SpelEvaluator 주입. 구현 가능, 스펙 FR1/FR2 반영.
- 미결 Q1(phase)/Q2(PostAction 범위)는 Maxi D8=A/D9=A로 해소. Q3(YAML 스키마)/Q4(시드 범위)는 스펙에서 확정(전이별 validators/post_actions 리스트, 표준 워크플로우는 시드 비움).

## Plan

> **단일 PR (project-workflow 단일 BC).** 7 TDD task. 모두 같은 Gradle 모듈이라 test 컴파일 공유 → wave 대체로 직렬(메모리 bts-plan-wave-gradle-module-compile).

### Task 1. WorkflowValidator에 적용 단계(phase) 추가 + 4종 분류

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/spi/WorkflowValidator.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/RequiredFieldValidator.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/PermissionValidator.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/NotStatusCategoryValidator.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/CustomExpressionValidator.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorPhaseTest.kt`]
- depends-on: []

**RED**: `ValidatorPhaseTest` — RequiredField.phase==EXECUTION, Permission/NotStatusCategory/CustomExpression.phase==AVAILABILITY. 컴파일 실패(phase 없음).

**GREEN**: `WorkflowValidator`에 `val phase: ValidatorPhase`(enum AVAILABILITY/EXECUTION). 각 구현체 override.

**REFACTOR**: ValidatorPhase KDoc(availability=목록 게이트, execution=실행 게이트, Jira transition screen 시맨틱). ktlint KDoc 특수문자 주의(메모리 ktlint-kdoc-brace-parse-failure).

**검증**: `./gradlew :modules:project-workflow:test --tests "*ValidatorPhaseTest"`

### Task 2. availableTransitions가 EXECUTION validator 건너뜀

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineAvailabilityPhaseTest.kt`]
- depends-on: [1]

**RED**: `WorkflowEngineAvailabilityPhaseTest`(MockK factory/repo) — RequiredField(EXECUTION) 걸린 전이가 availableTransitions에 포함, 동시에 plan은 resolution 없으면 WorkflowValidatorFailureException. 현재 availableTransitions가 제거 → 실패.

**GREEN**: WorkflowEngine.availableTransitions validator 루프(line ~303)에서 `phase==AVAILABILITY`만 평가. plan(line ~241) 전부 평가(불변).

**REFACTOR**: 두 루프 phase 분기 명료화. 과도 추상화 금지.

**검증**: `./gradlew :modules:project-workflow:test --tests "*WorkflowEngineAvailabilityPhaseTest"`

### Task 3. WorkflowValidatorFactory production 구현

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/DefaultWorkflowValidatorFactory.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/DefaultWorkflowValidatorFactoryTest.kt`]
- depends-on: [1]

**RED**: `DefaultWorkflowValidatorFactoryTest` — create("RequiredField", {"field":"resolution"}) → RequiredFieldValidator(field=resolution). 4종 각각 + 미지원 type → IllegalArgumentException + 필수 config 누락 → 예외. 실패(클래스 없음).

**GREEN**: `@Component DefaultWorkflowValidatorFactory(spelEvaluator: SpelEvaluator) : WorkflowValidatorFactory`. type별 when 분기로 config Map에서 인자 추출해 기존 validator 생성. CustomExpression은 spelEvaluator 주입.

**REFACTOR**: config 추출 헬퍼(required key 검증), KDoc.

**검증**: `./gradlew :modules:project-workflow:test --tests "*DefaultWorkflowValidatorFactoryTest"`

### Task 4. WorkflowPostActionFactory production 구현

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/DefaultWorkflowPostActionFactory.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/DefaultWorkflowPostActionFactoryTest.kt`]
- depends-on: []

**RED**: `DefaultWorkflowPostActionFactoryTest` — create("SetField", {...}) → SetFieldPostAction, 5종 각각 + 미지원 type → 예외. 실패.

**GREEN**: `@Component DefaultWorkflowPostActionFactory : WorkflowPostActionFactory`. type별 생성(config에서 인자 추출). 계산만(적용 X).

**REFACTOR**: config 추출 공통화(T3과 중복 시 공유 헬퍼 검토), KDoc.

**검증**: `./gradlew :modules:project-workflow:test --tests "*DefaultWorkflowPostActionFactoryTest"`

### Task 5. WorkflowDefinitionRepository production 구현 (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/DefaultWorkflowDefinitionRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/DefaultWorkflowDefinitionRepositoryTest.kt`]
- depends-on: []

**RED**: Testcontainers `DefaultWorkflowDefinitionRepositoryTest` — workflow_validators/workflow_post_actions에 row 삽입 후 findValidators/findPostActions(transition)가 display_order asc로 ValidatorConfig/PostActionConfig 반환, config jsonb→Map 역직렬화. 실패.

**GREEN**: `@Repository DefaultWorkflowDefinitionRepository(dsl: DSLContext)`. jOOQ 상수 WORKFLOW_VALIDATORS/WORKFLOW_POST_ACTIONS(V200 직접 codegen 자동 생성) 사용. jsonb→Map 파싱.

**REFACTOR**: 매핑/파싱 헬퍼, transition_id 조회 경로 KDoc.

**검증**: `./gradlew :modules:project-workflow:test --tests "*DefaultWorkflowDefinitionRepositoryTest"`

### Task 6. YAML validators/post_actions 스키마 확장 + YamlSeedService 시드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedValidatorPostActionTest.kt`, `backend/modules/project-workflow/src/test/resources/workflows/test-validator-seed.yaml`]
- depends-on: [5]

**RED**: Testcontainers `YamlSeedValidatorPostActionTest` — validators/post_actions 정의된 테스트 YAML 시드 후 두 테이블에 type/config/display_order/transition_id 삽입 확인(DefinitionRepository로 조회). idempotency(재시드 중복 없음). 실패.

**GREEN**: WorkflowYamlDto/TransitionYamlDto에 optional `validators`/`postActions`(또는 `post_actions`) 필드 + YamlSeedService가 transition 시드 후 두 테이블 INSERT. config Map→jsonb.

**REFACTOR**: 시드 로직 분리, 기존 3테이블 시드 흐름과 일관.

**검증**: `./gradlew :modules:project-workflow:test --tests "*YamlSeedValidatorPostActionTest"`

### Task 7. WorkflowEngine production 부팅 + end-to-end 통합 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineWiringIntegrationTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/ProjectWorkflowContextBootTest.kt`]
- depends-on: [2, 3, 4, 5, 6]

**RED**: (a) `ProjectWorkflowContextBootTest`(@SpringBootTest) — 전체 컨텍스트 기동(WorkflowEngine 4빈 결선) 성공. (b) `WorkflowEngineWiringIntegrationTest`(Testcontainers, 실제 factory/repo) — validator 설정 전이를 plan 시 거부 + availableTransitions 시 포함, SetField/Notify PostAction plan 결과 TransitionPlan 누적. 실패(현재 production 빈 부재).

**GREEN**: T3/T4/T5 빈으로 결선 완료 상태에서 통과. 부팅 가드 필요 시 보강(메모리 profile-scoped-bean-boot-failure — 전 프로파일 부팅).

**REFACTOR**: 통합 테스트 픽스처 정리.

**검증**: `./gradlew :modules:project-workflow:test --tests "*ProjectWorkflowContextBootTest" --tests "*WorkflowEngineWiringIntegrationTest"` + 모듈 전체 test(회귀 0).

## Plan 메타

- task 수: 7 (단일 PR, project-workflow 단일 BC)
- 예상 wave: wave1=[T1, T4, T5] → wave2=[T2, T3](T1 의존, validator 파일 겹침으로 T2/T3 직렬 가능) → wave3=[T6](T5 의존) → wave4=[T7](전부 의존). 같은 모듈 test 컴파일 공유로 실제 직렬 경향(메모리 bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (test→feat 순서, controller가 git log 검증 — 메모리 subagent-ktlint-false-green / parallel-dispatch-precommit-hook-race)
- 추가 검증: ktlintMain+TestSourceSetCheck + detekt(4모듈) + @SpringBootTest 부팅
- 마이그레이션 신규 없음, jOOQ 상수 V200 자동 생성(미러 불필요)

## 리뷰 결과

### code-reviewer 적대적 리뷰 (2026-06-03) — 🛑 BLOCKER + 더 깊은 아키텍처 갭

**BLOCKER (plan 수정으로 해소 가능)**.
- **B1. validator/postaction `type` 문자열 불일치.** 실제 구현체 type은 `permission-check`/`not-status-category`(kebab), PostAction은 `SET_FIELD`/`NOTIFY`/…(SCREAMING_SNAKE). plan/spec의 PascalCase 가정 틀림. V200 주석은 또 다른 표기. → factory dispatch 키 = 구현체 `type` 프로퍼티 값으로 단일 계약 명시 + plan 예시 키 교체.
- **B2. WorkflowDefinitionRepository.findValidators(transition)가 transition_id 해석 불가.** `WorkflowTransition`엔 id도 workflow 식별자도 없음(fromStateKey/toStateKey/name뿐). (from,to)만으로 join하면 같은 from/to를 쓰는 여러 워크플로우 validator를 오매칭(silent 결함). → SPI 시그니처에 workflow 식별자/transition id 추가(WorkflowEngine 호출부 동반 수정) 선행 task 필요.
- **B3. phase 추가(T1)의 cross-BC 컴파일 회귀.** 구현체가 더 있음 — WorkflowPropertyTest StubValidator(project-workflow test) + IssueTransitionGuardFilterIntegrationTest 익명 object(**issue-tracking BC**). phase 추상 멤버면 `:modules:issue-tracking:compileTestKotlin` 깨짐(plan 검증이 project-workflow만 돌려 못 잡음). → phase에 기본값(`get() = AVAILABILITY`) 부여로 회귀 0 권장.

**CONCERN**.
- C1. SpelEvaluator가 production @Bean 미등록(ExecutorService 필요) → T3가 빈 정의 추가.
- C2. PermissionValidator의 PermissionResolver 주입 경로 미명시, prod 프로파일 resolver 부재.
- C4. YamlSeedService가 transition INSERT의 id를 버림(.execute) + isDirty가 validator/post_action 미비교 → T6 보강.

**🛑 C3 (더 깊은 갭 — Maxi 아키텍처 결정 필요)**.
- **어떤 배포 앱도 project-workflow를 스캔하지 않음.** `IssueTrackingApplication`(@SpringBootApplication, com.bts.issue)는 `com.bts.issue.**`만 스캔. project-workflow 빈(WorkflowEngine/Adapter/factory/repo)은 production에서 **0개 결선**. cross-BC workflow 결선은 **테스트 TestConfig가 수동 조립할 때만** 존재(IssueControllerTransitionIntegrationTest 등).
- 즉 FR-WF-03이 project-workflow 내부를 완벽히 결선해도, **그걸 실행하는 배포 앱이 없어** "production 부팅 검증"(T7)이 성립 불가.
- **3단계 의존 발견**: FR-IS-07 → FR-WF-03 → **BC 배포 조립 모듈(부재)**.
- 해석: Phase 1 중반(92/117 FR)이라 BC별 모듈만 만들고 전체 조립(bootstrap 앱이 com.bts.* + com.atlas.bts.* 스캔)은 아직 미구축. 모든 검증이 test-assembled 컨텍스트로 이뤄짐(현 BTS 표준).

**→ 게이트 1 진입 불가. Maxi 방향 결정 필요(D10).** 옵션: (A) FR-WF-03을 project-workflow 내부 결선 + 통합테스트(test-assembled, 현 표준과 동일) 검증으로 한정, "실배포 조립"은 후속 프로젝트 차원 마일스톤으로 분리. (B) BC 배포 조립 모듈을 먼저 구축 후 FR-WF-03. (C) 전체 재검토.
