# FR-AT-06 YAML 가져오기/내보내기 (GitOps) — 백엔드 (D1~D5)

> slug: fr-at-06-yaml-gitops
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-14

## Brief

자동화 규칙(트리거·조건·액션)을 YAML로 내보내고(`GET /api/v1/automation/export`) YAML을
올려 일괄 생성/갱신하는(`POST /api/v1/automation/import`) GitOps 기능. 이번 PR은 백엔드
D1~D5(도메인·YAML 스키마 명세·데이터 모델 활용·엔드포인트·round-trip 테스트). UI(D6/D7)는 후속 PR.

classify: type=backend, agent=backend-engineer (classify-task가 '스키마' 키워드로 migration
오분류 → product doc D3=활용/no migration, D4 책임=backend-engineer 근거로 교정).

## 도메인 정리

- **BC**: automation (`backend/modules/automation/`, `com.bts.automation`)
- **영향 엔티티**: 신규 없음. 기존 `AutomationRule`(+ `Trigger`/`Condition`/`Action`) 애그리게이트를 YAML로 직렬화/역직렬화. D3 = 활용 확인 (신규 마이그레이션 없음).
- **저장 구조**: 규칙 1개 = `automation_rules` 1행 + `automation_actions` N행(position 순) + `automation_conditions` 0..1행 (V300/V302/V304). YAML 문서 하나가 이 세 조각을 담아야 round-trip 성립.
- **새 유비쿼터스 개념**: "규칙 YAML (GitOps)" — 자동화 규칙을 사람이 읽는 YAML로 표현해 버전 관리·이식. glossary 추가 후보(Maxi 승인 대기).
- **기존 결정 충돌**: 없음. 프로덕션 YAML 선례 = project-workflow `YamlSeedService`(내부 전용 `ObjectMapper(YAMLFactory())`로 기본 JSON mapper 오염 방지 — 이 패턴 이식).
- **관련 ADR**: FR-AT-01~05 ADR 소비. 신규 ADR 후보 = FR-AT-06 import 시맨틱(스펙 확정 후 생성).

### 도메인 모델 참조 (스키마 설계 근거)

- `AutomationRule`: id(UUID·앱 생성), projectKey, name(≤200), enabled, triggerType(ISSUE_CREATED/ISSUE_UPDATED/ISSUE_COMMENTED/SCHEDULED/WEBHOOK), triggerConfig(JSON 문자열), actions(순차), condition(nullable sealed 트리), webhookTokenHash(WEBHOOK 전용·export 제외), nextFireAt(SCHEDULED 전용·파생), createdBy, actorUserId(기본=createdBy), createdAt/updatedAt, deletedAt(소프트삭제), version(OCC). **priority 필드 없음**.
- `Condition`: sealed And/Or/Not/Comparison. 와이어 = JSONLogic 부분집합. `MAX_DEPTH=10`·`MAX_NODES=100`·`FIELD_WHITELIST`(issue.key/type/status/priority/assignee/reporter/labels/summary/projectKey)·`fromJson(toJson())==this` 정규형.
- `Action`: sealed SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK. config wire **비대칭** — 요청=JSON 문자열, 응답=Map. trigger config·condition은 요청·응답 모두 문자열.
- 권한: `MANAGE_AUTOMATION`(shared-kernel `AutomationPermissionResolver.hasManageAutomation`, fail-closed). 기존 컨트롤러 인가 순서 = actor 추출(401) → 권한(403) → 리소스.

### 참고 패턴 (search-export-import BC)

- `ExportCellSanitizer` — formula injection 방어(YAML도 값에 `=+-@` 시작 시 방어 검토).
- `ImportJobService` — fail-closed 권한 게이트(파일 저장 이전 fail-fast).
- 단, search-export-import는 CSV/XLSX/Jira 대상 대용량 비동기(pgmq+MinIO). FR-AT-06은 규칙 수가 작아 **동기 처리**가 적합(대용량 잡 프레임워크 재사용 불필요).

### 스펙에서 확정할 행위 결정 (→ /bts-spec)

1. **규칙 식별 / import 시맨틱** — UUID id 기준 upsert vs name 기준 upsert vs create-only. (round-trip·GitOps 멱등성의 핵심)
2. **부분 실패 처리** — fail-closed 전량 롤백 vs 규칙별 보고.
3. **export 범위** — 프로젝트 단위 확정(기존 컨트롤러 이미 project-scoped). 비활성 규칙·webhook 규칙 포함 여부.
4. **엔드포인트 경로** — 기존 base가 `/api/v1/projects/{projectKey}/automation/rules` → export/import도 project-scoped 하위(`.../rules/export`, `.../rules/import`)로 정렬.

## 스펙

전체 스펙. [docs/specs/2026-07-14-fr-at-06-yaml-gitops.md](../specs/2026-07-14-fr-at-06-yaml-gitops.md)

확정 결정(Maxi).
- import 시맨틱 = **UUID id 기준 upsert** (id 존재→갱신, 미존재→id 보존 생성, id 부재→새 UUID 생성).
- 부분 실패 = **atomic fail-closed** (단일 트랜잭션, 하나라도 실패→전량 롤백).
- 경로 = **프로젝트 스코프 하위** (`GET/POST /api/v1/projects/{projectKey}/automation/rules/export·import`). product doc flat 경로에서 deviation → 문서 동기화.

핵심 시나리오 3줄.
- export = 프로젝트 전 규칙(활성+비활성)을 YAML로(토큰 미포함·결정적 순서).
- import = YAML upsert(UUID 식별)·검증 도메인 재사용·원자성.
- round-trip + 멱등(동일 YAML 2회→2회차 전량 update).

도메인 확장 = `AutomationRule` 팩토리에 id·enabled 보존 변형(멱등성·비활성 round-trip 전제). 신규 마이그레이션 0.

## Brainstorming Check

✅ 통과 (1회 self-adversarial). gap 4건(비활성 round-trip·export 결정성·하이드레이션·id 보존) 반영. 테스트 함정 인계(크기상한 실서블릿·YAML mapper 격리·prod 조립 부팅).

## Plan

> 아키텍처. **순수 YAML codec**(`com.bts.automation.gitops`·DB 무관·단위테스트) + `AutomationRuleService`에 export/import 메서드 추가(기존 private 헬퍼 `mintWebhookToken`·`initialNextFireAt`·`toDomainAction`·repository·@Transactional 재사용) + `AutomationRuleController`에 2엔드포인트 추가(기존 `@RestControllerAdvice(assignableTypes=[AutomationRuleController])` 예외핸들러 확장). 신규 마이그레이션 0.
> 모든 경로는 repo 루트 기준. 모듈 = `backend/modules/automation`. 테스트 = `.../src/test/kotlin/com/bts/automation/...`.

### Task 1. YAML codec — DTO + toYaml/fromYaml + 순수 round-trip 단위테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/build.gradle.kts`, `backend/modules/automation/src/main/kotlin/com/bts/automation/gitops/AutomationYamlCodec.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/gitops/AutomationRulesYaml.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/gitops/AutomationYamlCodecTest.kt`]
- depends-on: []

**RED**.
- `AutomationYamlCodecTest.kt`.
  - `toYaml`이 알려진 규칙(트리거 config·조건 트리·SET_FIELD/ADD_COMMENT 액션)을 **기대 YAML 문자열**(하드코딩)로 방출. 규칙 정렬(createdAt→id), webhook 토큰/version/nextFireAt 미포함 확인.
  - `fromYaml`이 YAML 문서를 import 커맨드 목록으로 파싱. **wire 비대칭 흡수** — `trigger.config`/`action.config`/`condition` YAML 객체가 **JSON 문자열**로 직렬화됐는지 대조(기존 `TriggerConfig.validate`/`Action.fromJson`/`Condition.fromJson` 입력 형식).
  - **순수 round-trip** — `fromYaml(toYaml(rules))` 가 동등 커맨드 재현(주 단언). **하드코딩 전체 문자열 대조 지양**(Jackson 버전 취약·C1) — 대신 구조적 round-trip + 표적 단언(토큰 부재·규칙 순서·id 포함·config JSON 문자열 형태).
  - malformed YAML → 예외(원본 echo 금지). `version`≠1 → 예외. `projectKey` 접근자.
- 실패 예상: `AutomationYamlCodec` 클래스 없음.

**GREEN**.
- `build.gradle.kts`에 `implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")`(버전 생략=BOM).
- `AutomationRulesYaml.kt` — YAML DTO data class(`AutomationRulesYaml(version, projectKey, rules)`, `YamlRule(id?, name, enabled=true, actorUserId?, trigger, condition?, actions)`, `YamlTrigger(type, config)`, `YamlAction(type, config)`). 필드 선언순=방출순(결정성).
- `AutomationYamlCodec.kt` — 내부 전용 `ObjectMapper(YAMLFactory()).registerKotlinModule()`(전역 빈 노출 금지, `YamlSeedService` 선례). `toYaml(exported): String`·`fromYaml(raw): ParsedImportDocument`. config 객체↔JSON 문자열 변환은 별도 JSON ObjectMapper.

**REFACTOR**. 상수(version=1·max nesting)·KDoc(wire 비대칭 근거·mapper 격리 이유).

**검증**. `./gradlew :backend:modules:automation:test --tests '*AutomationYamlCodecTest'`

### Task 2. 도메인 팩토리 확장 — id + enabled 보존

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/AutomationRule.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/AutomationRuleTest.kt`]
- depends-on: []

**RED**.
- `AutomationRuleTest.kt`.
  - 명시 `id`·`enabled=false`로 생성 → 규칙이 그 id 보유 + 비활성. version=0.
  - 기존 `create(...)` 기본 호출은 무영향(id 랜덤·enabled=true) — 기존 테스트 회귀 0.
  - 검증(name≤200·blank·nil actorUserId·TriggerConfig)은 그대로 강제.
- 실패 예상: 팩토리에 id/enabled 파라미터 없음.

**GREEN**. 기존 `create` 시그니처에 `id: UUID = UUID.randomUUID()`·`enabled: Boolean = true` 선택 파라미터 추가(또는 `restore(...)` 변형). 기본값으로 기존 호출부 무영향. import-create의 멱등성(S3)·비활성 round-trip 전제.

**REFACTOR**. KDoc — "import(FR-AT-06) id/enabled 보존용. 일반 생성은 기본값" 명시.

**검증**. `./gradlew :backend:modules:automation:test --tests '*AutomationRuleTest'`

### Task 3. Export 서비스 + 엔드포인트 — 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/AutomationRuleService.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationRuleController.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationRuleExportIntegrationTest.kt`]
- depends-on: [1]

**RED**.
- Testcontainers 통합테스트(기존 automation 통합테스트 base 재사용).
  - 프로젝트에 규칙 3개(활성2+비활성1·조건 있는 것 1·액션 다수) 시드 → `GET /api/v1/projects/{projectKey}/automation/rules/export` → 200 `application/yaml` + `Content-Disposition: attachment; filename="automation-rules-{projectKey}.yaml"`.
  - YAML 본문에 3규칙 전부(활성+비활성)·**결정적 순서**·webhook 토큰/해시 미포함.
  - MANAGE_AUTOMATION 없음 → 403 `AUTOMATION_ACCESS_DENIED`.
- 실패 예상: 엔드포인트 404.

**GREEN**.
- `AutomationRuleService.exportRules(actor, projectKey): List<ExportedRule>` — `assertManageAutomation` 최우선 → 미삭제 규칙 조회 → 규칙별 `actionRepository.findByRuleId`·`conditionRepository.findByRuleId` **하이드레이션** → createdAt→id 정렬.
- 컨트롤러 `export` 핸들러 — 서비스 호출 → `codec.toYaml` → `ResponseEntity` produces `application/yaml` + Content-Disposition(projectKey 패턴 검증으로 헤더 인젝션 차단).

**REFACTOR**. ExportedRule 뷰 타입 KDoc. 하이드레이션 N+1은 프로젝트 규칙 수 소규모라 허용(NFR1) 주석.

**검증**. `./gradlew :backend:modules:automation:test --tests '*AutomationRuleExportIntegrationTest'`

### Task 4. Import 서비스 — upsert 루프 + 원자성 — 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/AutomationRuleService.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/AutomationImportServiceIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**.
- Testcontainers 통합테스트.
  - **id 보존 생성** — id 있는 커맨드(프로젝트에 미존재) → 그 id로 CREATE. enabled=false 커맨드 → 비활성 생성.
  - **멱등(S3)** — 같은 커맨드 2회 importRules → 2회차 전량 UPDATE·규칙 수 불변.
  - **원자성(S4)** — 5커맨드 중 1개 조건 MAX_DEPTH 초과 → 전량 롤백(DB 규칙 수 불변)·예외에 실패 인덱스.
  - **triggerType 변경(EC3)** — 기존 규칙과 다른 type → 예외. **id 귀속 충돌(EC4)** — 타 프로젝트/삭제 id → 예외(전역 존재 확인은 **프로젝트 무관 + 소프트삭제 포함** finder 필요·C4).
  - **동시성 OCC(C2)** — import-update 중 다른 트랜잭션이 같은 규칙 버전 변경 → repository.update 0-row → OptimisticLockingFailure → 409 표면화(원자성으로 전량 롤백).
  - **검증 재사용(FR5)** — 비화이트리스트 var·잘못된 cron·잘못된 url·name 초과 각각 예외.
  - MANAGE_AUTOMATION 없음 → 403(루프 이전).
- 실패 예상: `importRules` 메서드 없음.

**GREEN**.
- `AutomationRuleService.importRules(actor, projectKey, commands: List<ImportRuleCommand>): ImportOutcome` `@Transactional`.
  - `assertManageAutomation` 1회 최우선.
  - 커맨드별. id 해석(이 프로젝트 미삭제 존재→UPDATE / 전역 미존재→CREATE(id 보존) / 타프로젝트·삭제→예외 / id 부재→CREATE 새 UUID). actorUserId = 커맨드값 ?: actor(createdBy=actor).
  - CREATE — 확장 팩토리(id·enabled) + `toDomainAction` + `Condition.fromJson` + WEBHOOK시 `mintWebhookToken`(생성 토큰 수집) + SCHEDULED시 `initialNextFireAt` → save + actions replace + condition replace.
  - UPDATE — 로드 → triggerType 일치 검증(EC3) → rename/enable·disable/updateConfig/updateActions/(condition 있으면)updateCondition/changeActor → update(OCC 현재버전) + actions replace + condition replace. **토큰 재mint 안 함**(보존). condition 생략=미변경(EC6).
  - 실패는 catch-continue 금지(예외 전파→롤백=원자성). **conflict 분석은 여기서 호출 금지**(rollback 오염, T5가 커밋 후).
- `ImportRuleCommand`·`ImportOutcome`(created/updated 카운트·ruleIds·webhookTokens) 커맨드/결과 타입.

**REFACTOR**. id 해석 로직 private 헬퍼 추출. 원자성=rollback-only 오염 회귀 방지 KDoc(`workflowstatecatalog-mandatory-rollback-poison` 링크).

**검증**. `./gradlew :backend:modules:automation:test --tests '*AutomationImportServiceIntegrationTest'`

### Task 5. Import 엔드포인트 + 응답 DTO + 예외매핑 + 크기상한 + 커밋후 conflicts — 통합테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationRuleController.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/AutomationRuleResponses.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationRuleImportIntegrationTest.kt`]
- depends-on: [3, 4]

**RED**.
- Testcontainers 통합테스트(MockMvc 웹 계층).
  - happy path — YAML 본문 POST → 200 `AutomationImportResponse`(created/updated/total·ruleIds 입력순·conflicts). 생성 WEBHOOK 규칙 → `webhookTokens` 1회 노출.
  - malformed YAML → 400 `AUTOMATION_IMPORT_INVALID`(ProblemDetail에 **실패 규칙 인덱스+사유** 포함·C3). projectKey 불일치(EC2) → 400.
  - 규칙 수 > `MAX_IMPORT_RULES` → 413 `AUTOMATION_IMPORT_TOO_LARGE`.
  - 동시성 OCC 충돌(C2) → 409 `AUTOMATION_RULE_VERSION_CONFLICT`(기존 코드 재사용).
  - import 후 conflicts 채워짐(커밋 후 `analyzeProjectConflicts`).
- 실패 예상: 엔드포인트 404.

**GREEN**.
- 컨트롤러 `import` 핸들러 — consumes yaml/text, `@RequestBody rawYaml: String`. actor 추출(401) → `codec.fromYaml`(파싱실패→400) → projectKey 일치 검증 → 규칙 수 상한(초과→413) → `service.importRules` → **커밋 후** `service.analyzeProjectConflicts(projectKey)` → `AutomationImportResponse` 조립.
- `AutomationImportResponse` DTO(`Responses.kt`) — created/updated/total/ruleIds/`webhookTokens`(@JsonInclude NON_NULL)/`conflicts`(NON_NULL·기존 `RuleConflictResponse` 재사용).
- 예외핸들러 확장 — `AutomationImportException`류 → 400/413(RFC7807 ProblemDetail+errorCode).

**REFACTOR**. 상한 상수. KDoc — 본문 크기 실제 강제는 코드 레벨(서블릿 우회 가짜그린 회피는 T6 실서블릿 검증).

**검증**. `./gradlew :backend:modules:automation:test --tests '*AutomationRuleImportIntegrationTest'`

### Task 6. round-trip + 멱등 + 크기상한 실서블릿 — 통합테스트 (D5 핵심)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/web/AutomationGitOpsRoundTripTest.kt`]
- depends-on: [3, 5]

**RED**(=이 태스크의 GREEN은 상위 태스크 구현이 이미 충족, 여기선 end-to-end 계약 검증).
- **round-trip(D5)** — 프로젝트 A에 규칙 시드 → `GET export` → YAML의 projectKey를 B로 치환 → `POST import`(B) → B 규칙이 A와 동등(id 보존·조건·액션·enabled).
- **멱등** — 같은 YAML 2회 import → 2회차 created=0.
- **크기상한 실서블릿** — `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`로 과대 본문 → 413(MockMvc 서블릿 우회 가짜그린 회피 [[multipart-default-limit-app-policy-false-green]]).

**GREEN**. 상위 태스크로 충족(신규 prod 코드 없으면 없음). 필요 시 미세 보정.

**REFACTOR**. 테스트 헬퍼(시드·YAML projectKey 치환) 정리.

**검증**. `./gradlew :backend:modules:automation:test --tests '*AutomationGitOpsRoundTripTest'`

### Task 7. 문서 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/automation.md`, `docs/sdd/08-automation-engine.md`]
- depends-on: []

**RED/GREEN**(문서, TDD 예외 — 코드 아님).
- `product/automation.md` §2.6 FR-AT-06 D1~D5 [x] + 완료 주석(경로 deviation 명시·D6/D7 UI 후속).
- `sdd/08-automation-engine.md` §8.5 인근에 YAML import/export 스키마(v1)·엔드포인트(프로젝트 스코프)·upsert(UUID)·atomic 반영.
- FR 총수 불변 123(D-step 완료). `bash scripts/verify-master-plan.sh` 통과 확인.

**검증**. `bash scripts/verify-master-plan.sh`

## Plan 메타

- task 수: 7
- 예상 시간: 직렬 기준 약 25~35분(통합테스트 Testcontainers 빌드 포함).
- TDD 강제: yes (T7 문서 제외).
- 병렬 dispatch: **대부분 직렬 권장**. T3/T4/T5가 `AutomationRuleService.kt`·`AutomationRuleController.kt`를 공유 → 파일 겹침 자동 직렬화 + 단일 Gradle 모듈 컴파일 직렬화([[bts-plan-wave-gradle-module-compile]]). 단일 worktree git-race([[parallel-dispatch-precommit-hook-race]]) 회피 위해 순차 커밋. 독립 가능: Wave1 = T1·T2·T7(파일 무겹침).
- 의존 그래프: T1[]·T2[]·T7[] → T3[1] → T4[1,2] → T5[3,4] → T6[3,5].
- 추가 검증: ktlint·detekt(`--rerun-tasks` 캐시 false-green 방지)·`:modules:app:test` prod 조립 부팅([[prod-assembly-boot-verification-required]] — 신규 컨트롤러/서비스 빈 배선).

## 리뷰 결과

### 집중 엔지니어링 리뷰 (2026-07-14, api 타입 = eng+devex 관점)

메모리 학습(`bts-review-plan autoplan overkill`)에 따라 대화형 autoplan 대신 집중 eng 리뷰.

- ✅ **원자성/rollback 오염** — importRules가 create/patch(@Transactional REQUIRED)를 호출하지 않고 private 헬퍼+repository 직접 사용 → 중첩 트랜잭션 오염 회피. 실패 예외 전파=의도된 롤백(catch-continue 금지). conflict 분석은 커밋 후(T5) → rollback-only 오염([[workflowstatecatalog-mandatory-rollback-poison]]) 회피. 설계 건전.
- ✅ **권한 게이트 배치** — export/import 모두 `assertManageAutomation` 최우선(리소스 접근 이전, [[auth-extraction-before-resource-lookup]]). actor 추출 401은 컨트롤러.
- ✅ **비밀 미노출** — export webhook 토큰/해시 제외. id 보존이 새 특권 부여 아님(PK 유일성+귀속 검증).
- ✅ **YAML mapper 격리** — 내부 전용 ObjectMapper(YAMLFactory), 전역 빈 노출 금지([[custom-objectmapper-bean-yaml-response-regression]] 회귀 방지).
- ✅ **테스트 false-green** — 크기상한 실서블릿(T6·TestRestTemplate), detekt --rerun-tasks, prod 조립 부팅 모두 plan 메타 반영.
- **BLOCKER: 없음.**

반영한 concern 4건(plan 태스크에 인라인 반영).
- C1 — codec 단위테스트 하드코딩 문자열 대조 취약 → 구조적 round-trip + 표적 단언(T1).
- C2 — import-update 동시성 OCC 충돌 → 409 표면화(T4/T5·에러 계약).
- C3 — import 400 ProblemDetail에 실패 규칙 인덱스+사유(T5).
- C4 — EC4 id 전역 존재 확인 finder(프로젝트 무관+소프트삭제 포함)(T4).

### devex 관점(경량)

- export `application/yaml` + Content-Disposition = `curl > rules.yaml` CLI 친화. import 원문 본문 = `curl --data-binary @rules.yaml` CLI 친화. GitOps 워크플로우 적합.
- 에러 계약 RFC 7807 + errorCode, 기존 automation 컨트롤러 일관.
