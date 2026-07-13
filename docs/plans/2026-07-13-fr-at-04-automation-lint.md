# FR-AT-04 규칙 충돌 정적 분석

> slug: fr-at-04-automation-lint
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-13

## Brief

FR-AT-04 규칙 충돌 정적 분석 — automation 규칙 저장 전 사이클/우선순위 모호성/필드 충돌 검출 lint.

automation 규칙 여러 개가 서로 충돌하는지를 저장(생성/수정) 전에 정적으로 검사한다.
- 사이클: 규칙 A의 액션이 규칙 B의 트리거를 유발하고 B가 다시 A를 유발하는 정적 루프 (런타임 가드는 FR-AT-02, 정적 검출은 여기)
- 우선순위 모호성: 같은 트리거에 복수 규칙 매칭 + 실행 순서 미결정
- 필드 충돌: 두 규칙이 같은 필드를 다른 값으로 SET

product 문서: docs/plan/product/automation.md §2.4
D1 도메인(RuleConflict) · D2 명세 · D3 데이터(활용) · D4 백엔드(저장 전 lint) · D5 테스트 · D6 UI(경고 모달) · D7 E2E

## 도메인 정리

- **BC**: automation (단일). cross-BC는 shared-kernel 권한 조회 포트만 (AutomationBcArchTest 강제)
- **영향 엔티티** (전부 기존, 읽기 전용): AutomationRule / Action(SetField·Assign·AddComment·CallWebhook) / Condition(And·Or·Not·Comparison) / TriggerType(ISSUE_CREATED·ISSUE_UPDATED·ISSUE_COMMENTED·SCHEDULED·WEBHOOK)
- **신규 도메인 개념**:
  - `RuleConflict` (규칙 충돌, 값 객체) — `{ type, severity, ruleIds, detail }`
  - `ConflictType` enum **4종**: `CYCLE` / `FIELD_CONFLICT` / `PRIORITY_AMBIGUITY` / `PERMISSION_MISSING` (Maxi 확정 — product §2.4 3종 + SDD 8.7 3종의 합집합)
  - `RuleConflictAnalyzer` (application 서비스) — 프로젝트 규칙 집합을 정적 분석
- **강제성**: 전부 경고(soft). 어떤 충돌도 저장을 막지 않음. 저장 성공 + 응답 DTO에 `conflicts` 배열 포함 (Maxi 확정). `severity`는 UI 표현용이며 현재 전부 WARNING
- **분석 방식**: 별도 엔드포인트 없음. 기존 `POST`/`PATCH .../automation/rules` 저장 경로가 저장 후 lint 수행 → 응답에 `conflicts` 포함 (Maxi 확정)
- **신규 테이블**: 없음 (product D3 "활용"). 기존 `automation_rules`/`automation_actions`/`automation_conditions`를 읽어 분석, 결과 미영속
- **충돌 판정 규칙 (초안, spec에서 정밀화)**:
  - `CYCLE`: 규칙 그래프 DFS. 엣지 A→B = A의 액션이 B의 트리거를 유발. `SetFieldAction.field` ∩ `ISSUE_UPDATED.triggerConfig.fields` / `AssignAction`→`ISSUE_UPDATED`(assignee) / `AddCommentAction`→`ISSUE_COMMENTED`. `CallWebhookAction`은 외부 유입이라 정적 엣지 없음
  - `FIELD_CONFLICT`: 같은 `(projectKey, triggerType[+겹치는 fields])`에 매칭되는 규칙들의 `SetFieldAction` 중 `field` 동일 & `value(JsonNode)` 상이
  - `PRIORITY_AMBIGUITY`: 같은 `(projectKey, triggerType)`+겹치는 조건에 enabled 규칙 2개+ 이고 순서 결정 필드가 `created_at, id`뿐 (automation_rules에 priority 컬럼 부재가 근거)
  - `PERMISSION_MISSING`: rule actor(`actor_user_id`)가 액션 대상(필드 편집/담당 지정/댓글 작성)의 프로젝트 레벨 권한 부재. 저장 시점 구체 이슈 없음 → 프로젝트 권한으로 근사. cross-BC 권한 포트 필요 (기존 `AutomationPermissionResolver` 확장 또는 신규 포트 — spec 정밀화)
- **기존 결정 충돌**: 없음. 오히려 FR-AT-02 `AutomationExecutionWorker` KDoc의 "견고한 사이클 검출은 FR-AT-04 위임"을 완성
- **문서 drift 해소 (이 PR에서 전수 동기화)**: product §2.4(3종)·SDD 8.7(3종) → **4종**으로 정렬 (CLAUDE.md §FR/범위 변경 전수 동기화 규칙 — verify-master-plan 통과 필수)
- **관련 ADR**: docs/decisions/2026-07-13-fr-at-04-conflict-analysis.md (spec 확정 후 생성)

## 스펙

전체 스펙. [docs/specs/2026-07-13-fr-at-04-conflict-analysis.md](../specs/2026-07-13-fr-at-04-conflict-analysis.md)

핵심 요약.
- 기존 저장 경로(POST/PATCH)가 저장 후 `RuleConflictAnalyzer`로 프로젝트 규칙을 정적 분석 → 응답에 `conflicts` 포함 (신규 엔드포인트·테이블 없음)
- 충돌 4종. CYCLE(액션→트리거 유발 그래프 DFS) / FIELD_CONFLICT(같은 트리거 동일필드 상충 SET) / PRIORITY_AMBIGUITY(같은 트리거 복수 규칙 순서 모호) / PERMISSION_MISSING(rule actor 프로젝트 레벨 UPDATE 권한 부재)
- 전부 soft WARNING(저장 무차단), fail-safe(분석 예외가 저장 훼손 안 함)
- cross-BC 새 소비. `IssuePermissionResolver`(shared-kernel) — `IssueScope.Project`로 프로젝트 레벨 권한 근사

## Brainstorming Check

✅ 통과 (직접 adversarial 검토 1회, 5 gap 전부 스펙 내 보완 — CYCLE 조건무시·updatedFields 필드명 impl확인·FIELD⊂PRIORITY 중복억제·권한조회 메모이제이션·non-prod stub 한계). Maxi 결정 필요 항목 0.

## Plan

> 경로 접두사. main = `backend/modules/automation/src/main/kotlin/com/bts/automation`,
> test = `backend/modules/automation/src/test/kotlin/com/bts/automation`

### Task 1. 도메인 모델 — ConflictType / ConflictSeverity / RuleConflict

**메타**.
- agent: `backend-engineer`
- files: [`{main}/domain/ConflictType.kt`, `{main}/domain/ConflictSeverity.kt`, `{main}/domain/RuleConflict.kt`, `{test}/domain/RuleConflictTest.kt`]
- depends-on: []

**RED**. `RuleConflictTest` — `RuleConflict(type=CYCLE, severity=WARNING, ruleIds=[a,b], detail="...")` 생성·필드 접근. `ConflictType` 4종 값 존재(CYCLE/FIELD_CONFLICT/PRIORITY_AMBIGUITY/PERMISSION_MISSING). 실패: 클래스/enum 없음.

**GREEN**. `ConflictType` enum(4종), `ConflictSeverity` enum(WARNING 단일), `RuleConflict` data class(`type`, `severity`, `ruleIds: List<UUID>`, `detail: String`).

**REFACTOR**. L1 한국어 헤더 주석 + KDoc(각 ConflictType 값의 의미). `ruleIds` 순서 정규화 헬퍼(정렬).

**검증**. `./gradlew :modules:automation:test --tests '*RuleConflictTest'`

### Task 2. RuleConflictAnalyzer — CYCLE 검출 (그래프 DFS)

**메타**.
- agent: `backend-engineer`
- files: [`{main}/application/RuleConflictAnalyzer.kt`, `{test}/application/RuleConflictAnalyzerCycleTest.kt`]
- depends-on: [1]

> **DRY 노트(eng-review)**. `ActionType`↔`Action` 매핑은 기존 3곳(repo/executor/response)에 존재 — analyzer는 4번째 매핑을 만들지 말고 기존 상수/헬퍼를 재사용한다.

**RED**. `RuleConflictAnalyzerCycleTest` — 규칙 리스트 입력 → `analyze()` 반환에서 CYCLE 검출 검증.
케이스. (a) self-loop(A의 SetField(priority) + A 트리거 ISSUE_UPDATED{fields:[priority]}) → CYCLE 1건. (b) 2-cycle(A↔B). (c) 3-cycle(A→B→C→A). (d) no-cycle(직선 체인) → 0건. (e) AddComment→ISSUE_COMMENTED 엣지. (f) CallWebhook 규칙은 엣지 없음. (g) 같은 사이클 중복 dedup. 실패: analyzer 없음.

**GREEN**. `RuleConflictAnalyzer.analyze(rules: List<AutomationRule>, ...): List<RuleConflict>` 중 CYCLE 파트.
- enabled 규칙만 노드. 액션→유발 트리거 매핑(스펙 FR-3 표)으로 방향 그래프 구성.
- `SetFieldAction.field` ∩ `ISSUE_UPDATED.triggerConfig.fields`(비면 전체 매칭 = `TriggerMatcher.matchesFieldFilter` 시맨틱 재사용). `AssignAction`→ISSUE_UPDATED(assignee). `AddCommentAction`→ISSUE_COMMENTED.
- DFS로 back-edge 사이클 검출. self-loop 포함. 정규화(최소 ruleId 회전) 후 dedup.
- 조건 무시(보수적, 스펙 Brainstorming #1).

**REFACTOR**. 그래프/DFS를 private 헬퍼로 분리(MaxLineLength·NestedBlockDepth·ReturnCount 회피 — @Suppress 대신 메서드 추출). `triggerConfig`(JSON 문자열) 파싱은 기존 `TriggerConfig` 유틸 재사용.

**검증**. `./gradlew :modules:automation:test --tests '*RuleConflictAnalyzerCycleTest'`

### Task 3. RuleConflictAnalyzer — FIELD_CONFLICT + PRIORITY_AMBIGUITY (중복 억제)

**메타**.
- agent: `backend-engineer`
- files: [`{main}/application/RuleConflictAnalyzer.kt`, `{test}/application/RuleConflictAnalyzerFieldPriorityTest.kt`]
- depends-on: [2]

**RED**. `RuleConflictAnalyzerFieldPriorityTest`.
FIELD. (a) 같은 트리거 A·B가 같은 field 다른 value SET → FIELD_CONFLICT. (b) 같은 값(멱등) → 미검출. (c) 규칙 내부 액션 간 같은필드 다른값 → 검출(ruleIds=[self]). (d) 다른 트리거 → 미검출.
PRIORITY. (e) 같은 트리거 부수효과 규칙 2개(assignee·priority 서로 다름) → PRIORITY_AMBIGUITY. (f) CallWebhook만인 조합 → 미검출. (g) **중복 억제**. 같은 쌍이 FIELD_CONFLICT면 그 쌍 PRIORITY 억제(스펙 Brainstorming #3). 실패: 미구현.

**GREEN**. analyze()에 FIELD_CONFLICT·PRIORITY_AMBIGUITY 파트 추가.
- 동시 매칭 판정. 같은 triggerType + (ISSUE_UPDATED면 fields 겹침/한쪽 empty).
- FIELD_CONFLICT. 매칭 쌍 중 같은 field·다른 value(JsonNode.equals) SetField. 규칙 내부 액션도 검사.
- PRIORITY_AMBIGUITY. 매칭 enabled 규칙 2+개 중 관측 가능 부수효과(SetField/Assign/AddComment) 보유 조합. FIELD_CONFLICT 걸린 쌍은 억제.

**REFACTOR**. 트리거 매칭 판정을 CYCLE과 공유하는 private 헬퍼로 통합(중복 제거). detail 한국어 메시지 빌더.

**검증**. `./gradlew :modules:automation:test --tests '*RuleConflictAnalyzerFieldPriorityTest'`

### Task 4. PERMISSION_MISSING + cross-BC IssuePermissionResolver 소비 배선

**메타**.
- agent: `backend-engineer` (권한 근사 매핑은 codereview에서 security 관점 확인)
- files: [`{main}/application/RuleConflictAnalyzer.kt`, `{main}/config/AutomationIssuePermissionStubConfig.kt`, `{test}/application/RuleConflictAnalyzerPermissionTest.kt`, `{test}/config/AutomationTestContextConfig.kt`(있으면 확장)]
- depends-on: [3]

**RED**. `RuleConflictAnalyzerPermissionTest` — mock `IssuePermissionResolver` 주입.
케이스. (a) actor가 UPDATE 없음 + SetField → PERMISSION_MISSING. (b) actor UPDATE 없음 + Assign → 검출. (c) UPDATE 있음 → 미검출. (d) AddComment는 권한 분석 제외(검출 안 함). (e) CallWebhook 제외. (f) **메모이제이션** — 같은 (actor,project,permission) 중복 호출 시 resolver 1회만(Mockito verify times(1)). 실패: 미구현.

**GREEN**. analyze() 시그니처에 `issuePermissionResolver: IssuePermissionResolver` 주입.
- 권한 매핑(스펙 FR-6 표). SetField/Assign→UPDATE, `IssueScope.Project(projectKey)`. AddComment/CallWebhook 제외.
- `(actorId, projectKey, permission)` 키 메모이제이션(분석 1회 내 Map 캐시).
- non-prod stub. `AutomationIssuePermissionStubConfig` — automation 컨텍스트 `@Profile("!prod")` `AlwaysAllow` 성격 stub 빈 제공(consumer-owns-stub, `AutomationPermissionResolver` 선례 동형). prod는 :modules:app의 identity-access 어댑터 주입.

**REFACTOR**. analyzer 생성자 주입 정리. stub KDoc(prod 어댑터 위임 명시).

**검증**. `./gradlew :modules:automation:test --tests '*RuleConflictAnalyzerPermissionTest'`

### Task 5. AutomationRuleService 저장 후 lint 통합 + 응답 DTO 확장

**메타**.
- agent: `backend-engineer`
- files: [`{main}/application/AutomationRuleService.kt`, `{main}/adapter/web/dto/AutomationRuleResponses.kt`, `{test}/application/AutomationRuleServiceTest.kt`(있으면 확장)]
- depends-on: [4]

**RED**. Service 단위 테스트(mock repo + mock analyzer). (a) create 후 analyzer 호출 → 응답에 conflicts 포함. (b) patch 동일. (c) **fail-safe** — analyzer가 예외 던져도 저장 성공 + conflicts 빈 배열. (d) analyzer는 저장 커밋 후 호출(순서). 실패: 미구현.

**GREEN**. `create`/`patch`가 저장(커밋) 후 `RuleConflictAnalyzer.analyze(findByProject(projectKey) hydrate, resolver)` 호출 → `RuleConflictResponse` 매핑해 응답 DTO에 담음. 분석은 try/catch fail-safe(예외→빈 리스트+경고 로그). `RuleConflictResponse` DTO + `AutomationRuleResponse.conflicts` 필드(기본 []). GET 응답 매핑엔 미포함.

**REFACTOR**. lint 호출을 private `analyzeConflicts(projectKey)` 헬퍼로. DTO 매핑 함수.

**검증**. `./gradlew :modules:automation:test --tests '*AutomationRuleServiceTest'`

### Task 6. 통합 테스트 (Testcontainers) — end-to-end

**메타**.
- agent: `backend-engineer` (E2E성 통합이나 automation은 backend가 Testcontainers 통합 담당, 선례 동일)
- files: [`{test}/integration/RuleConflictAnalysisIntegrationTest.kt`]
- depends-on: [5]

**RED/GREEN**(통합은 실서버 경로 검증). 실 DB(Testcontainers)에 규칙 시드 → 저장 API 경로로 충돌 유발 규칙 저장 → 응답 conflicts 검증(4종 각 1 시나리오). GET 응답엔 conflicts 없음 확인. 저장은 항상 성공(soft). `IssuePermissionResolver`는 @MockBean으로 특정 actor false.

**성능 스모크(eng-review 보강)**. 100개 규칙 시드 후 저장 1회 → 분석이 임계(1s 여유 상한, 예: 3s 테스트 타임아웃) 내 완료 확인 1건. 경계 초과 시 hydrate를 IN절 배치 조회로 전환(조기최적화 회피 — 측정 후 판단).

**REFACTOR**. 시드 헬퍼 정리.

**검증**. `./gradlew :modules:automation:test --tests '*RuleConflictAnalysisIntegrationTest'` + 모듈 전체 `./gradlew :modules:automation:test`

### Task 7. 문서 4종 동기화 + ADR

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/automation.md`, `docs/sdd/08-automation-engine.md`, `docs/plan/fr-index.md`, `docs/decisions/2026-07-13-fr-at-04-conflict-analysis.md`, `docs/plan/README.md`(카운트 영향 시), `CLAUDE.md`(카운트 영향 시)]
- depends-on: []

**작업**(TDD 대상 아님 — 문서). product §2.4 & SDD §8.7의 충돌 "3종"→"4종" 정렬(4종 명시), D1~D5 체크박스 `[x]`(D6/D7 후속 미체크), automation BC 카운트(3/7→4/7) 갱신, ADR 생성(도메인/스펙 결정 정리 — 4종/soft/저장응답/cross-BC 권한 근사/AddComment 권한 제외 한계). `bash scripts/verify-master-plan.sh` 통과 확인.

**검증**. `bash scripts/verify-master-plan.sh` (종료 0) + `git grep -n "3종\|3개" docs/plan/product/automation.md docs/sdd/08-automation-engine.md`로 잔여 drift 0.

## Plan 메타

- task 수: 7
- 예상 wave: analyzer 단일 파일(T2·T3·T4 직렬 chain) + Service(T5) + 통합(T6) 직렬. T1·T7은 독립(wave 1 병렬 가능). automation Gradle 모듈 컴파일도 직렬화([[bts-plan-wave-gradle-module-compile]]) → 실질 대부분 직렬.
- TDD 강제: yes (T1~T6). T7은 문서(TDD 예외).
- 추가 검증: ktlint/detekt(analyzer 복잡도 — 메서드 추출로 MaxLineLength/ReturnCount/NestedBlockDepth 회피), `:modules:automation:test` 전체.

## 리스크 / 함정 (learnings 대조)

- **cross-BC 새 포트 소비**. automation이 `IssuePermissionResolver` 신규 소비 → full-boot NoSuchBean 위험([[new-crossbc-dep-openapi-mockbean-regression]]). automation 컨텍스트 non-prod stub(T4) + 통합테스트 @MockBean(T6) 동반. 빈-컨텍스트 test-boot 배선([[new-bc-first-repository-testboot-context-regression]]).
- **prod 조립 부팅 재검증 필수**. cross-BC 의존 추가 PR은 머지 전 `:modules:app` rebase + `:modules:app:test`로 9BC 조립 부팅 확인([[prod-assembly-boot-verification-required]]). identity-access 어댑터가 issue-tracking 소비로 이미 존재하나, automation도 non-null 주입 충족되는지 검증.
- **BC 격리**. cross-BC는 shared-kernel 포트만. issue-tracking 직접 import 금지(`AutomationBcArchTest`). analyzer의 필드 정규화 매핑도 automation 내부 상수로.
- **문서 4종 동기화**. product/SDD 3종→4종 + BC 카운트 3/7→4/7. `verify-master-plan.sh` 통과 필수([[fr-scope-change-full-sync-rule]]). 새 카운트 표기 verify 미포착 시 스크립트 확장.
- **detekt/ktlint**. analyzer 그래프/DFS 복잡도 → 메서드 추출로 위반 회피(모듈 ktlintFormat 금지 [[ktlint-detekt-linelength-and-baseline-traps]]).
- **updatedFields 필드명**. AssignAction 유발 필드명(`assignee` 가정)은 T2 GREEN 시 issue-tracking 실제 이벤트 필드로 검증(스펙 Brainstorming #2).
- **git stash 금지**. sub-agent impl 시 git stash 사용 금지([[subagent-git-stash-worktree-shared-collision]]). 자기 파일만 `git add <file>`([[parallel-dispatch-precommit-hook-race]]).

## 리뷰 결과

### plan-eng-review (2026-07-13, backend 집중 리뷰 — autoplan 4-phase 대체)

**Architecture** ✅
- analyzer 단일 파일 4종 검출 = right-sized. detector 클래스 분리는 단일 use case에 과설계 — private 헬퍼 분리로 복잡도 관리(boring-by-default).
- cross-BC는 기존 `IssuePermissionResolver` 포트 재사용 = proven, innovation token 안 씀.
- 저장 후 lint(별도 엔드포인트 없음) = blast radius 최소. 응답 `conflicts` 필드 추가는 하위호환(기존 프론트 무시 가능, reversible).
- 분석을 fail-safe로 감쌈 = 분석 실패가 저장 훼손 안 함(3am 안전, systems-over-heroes).

**Tests** ✅ — 충돌 4종별 케이스 충실(self/2/3-cycle·no-cycle·멱등·규칙내부·중복억제·메모이제이션·fail-safe·통합 end-to-end).

**주의 (BLOCKER 아님, 2건 plan 반영 완료)**:
1. ✅반영 **성능 검증**. NFR "100규칙 1s" → T6에 100규칙 성능 스모크 1건 추가.
2. ⚠️ **hydrate N+1**. plan이 "필요시 IN절 배치"로 열어둠 = 측정 후 판단(조기최적화 회피) 적절. T6 스모크가 경계 넘으면 배치 전환.
3. ✅반영 **ActionType 매핑 DRY**. T2에 "기존 3곳 매핑 재사용, 4번째 만들지 말 것" 노트 추가.
4. ⚠️ **PERMISSION_MISSING 실효 범위**. non-prod stub이라 dev 무의미·prod 전용. cross-BC 부팅 결합 추가 대비 실질 가치는 Maxi가 4종으로 확정 — 진행. blast radius(조립 부팅)는 §리스크에 잡힘([[prod-assembly-boot-verification-required]]).

**BLOCKER: 없음.** cross-BC 권한 근사 매핑의 정확성은 게이트 2 codereview에서 security 관점 재확인.
