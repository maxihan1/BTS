<!-- automation BC — TCA(Trigger-Condition-Action) 자동화 엔진 7 FR -->

# automation BC

**소속 FR**. 7개 (AT 7).
**책임**. 트리거-조건-액션 규칙, 실행 이력, 스케줄링, GitOps(YAML), PR 머지 연동.
**SDD 참조**. 08장 (자동화 엔진).
**다른 BC와의 경계**. 모든 BC에 액션 호출. 다른 BC의 pgmq 이벤트를 트리거로 수신. **import 금지 — 이벤트만**.

## §0 진입 조건

- [ ] identity-access §4.4 (FR-PM-04 자동화 관리 권한) 완료 — **워크플로우 부분은 완료(PR #73)**, `MANAGE_AUTOMATION`은 automation BC 착수 시 동반 결선(ADR D1, dead 시드 회피)
- [ ] issue-tracking §2~§6 (이슈 변경 이벤트 발행) 완료
- [ ] project-workflow §2 (상태 전이 이벤트) 완료
- [ ] notification-dashboard §1 (pgmq consumer 패턴 확립) 완료
- [ ] AT는 다른 BC의 후행 작업. 가능한 한 마지막 진입 권장.

## §1 기술 검증

이 BC 자체의 PoC는 없음. 다른 BC의 pgmq 패턴을 그대로 활용.

## §2 자동화 규칙 (FR-AT, 7개)

### §2.1 FR-AT-01 — 트리거 (생성/변경/댓글/스케줄/Webhook)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `automation/triggers`

- [x] D1. 도메인 — Trigger 다형성 (책임. backend-engineer)
- [x] D2. 명세 — 5종 트리거 (CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK) (책임. backend-engineer)
- [x] D3. 데이터 모델 — `automation_rules(trigger_type, config)` (책임. db-engineer)
- [x] D4. 백엔드 — pgmq consumer + Spring `@Scheduled` + Webhook 엔드포인트 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — Testcontainers (책임. backend-engineer)
- [x] D6. 프론트 UI — 트리거 선택 UI (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **D1~D5 완료 (2026-07-10, PR #251)**. automation BC 착수 — BTS 9번째 Gradle 모듈(`com.bts.automation`, test-boot only, JdbcTemplate). 5종 트리거(ISSUE_CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK) 감지 → 매칭 → `q_automation_execution` enqueue(액션 실행은 FR-AT-02 이음선). 감지 3경로 — pgmq consumer(`q_automation_events` fan-out) / `@Scheduled` cron(6필드·UTC·nextFireAt 중복억제) / 인바운드 웹훅 토큰(SHA-256·202·404·413). cross-BC 3곳 — issue-tracking(`IssueEventPublisher` fan-out + 신규 `IssueCommented` 이벤트 + V036 큐, producer 소유) · identity-access(`MANAGE_AUTOMATION` 시드 V035 + resolver, PROJECT_ADMIN) · shared-kernel(`AutomationPermissionResolver` 포트, consumer-owns-stub). **게이트2 옵션A(코드리뷰 발견)**: 신규 `IssueCommented`가 `q_issue_events`에도 실려 FR-NT-01 §9.1.2 사전시드 댓글 인앱 알림 경로(REPORTER/ASSIGNEE/WATCHER, 작성자 제외)를 producer 완성으로 활성화 — notification e2e 검증 테스트 동반, 새 FR 없음. BC 격리 ArchTest(cross-BC import 0)·@EnableScheduling opt-in(배포조립 후속). ADR [2026-07-10-fr-at-01-automation-triggers](../../decisions/2026-07-10-fr-at-01-automation-triggers.md).
>
> **D6/D7 완료 (2026-07-10, PR #254)**. 프로젝트 설정 `projects/$projectKey/settings/automation`에서 자동화 룰(트리거) CRUD 프론트 UI + E2E. 트리거 5종 선택 + 타입별 조건부 필드(SCHEDULED cron·ISSUE_UPDATED fields) + WEBHOOK 토큰 1회 노출 모달(PatTokenModal 선례). 백엔드 5 엔드포인트(bare DTO·XSRF·invalidate-only) 소비. 액션(FR-AT-02)/조건(FR-AT-03) 빌더는 별개 FR(미구현). 코드리뷰+/review 2관점으로 폼 409 무한루프(F1) 적발·수정(409 시 폼 자동닫기+토스트+refetch). E2E 8/8. FR 총수 123 불변(기존 FR-AT-01 완성). → **FR-AT-01 전체 완료(D1~D7)**.

### §2.2 FR-AT-02 — 액션 (필드 변경/담당자/댓글/API 호출)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `automation/actions`

- [x] D1. 도메인 — Action 다형성 (책임. backend-engineer)
- [x] D2. 명세 — 4종 액션 + 권한 가드 (다른 BC 권한 위반 금지) (책임. backend-engineer + security-engineer)
- [x] D3. 데이터 모델 — `automation_actions(action_type, config)` (책임. db-engineer)
- [x] D4. 백엔드 — Action executor + dry-run 모드 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 권한 부족 시 reject (책임. backend-engineer + security-engineer)
- [x] D6. 프론트 UI — 액션 빌더 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **D1~D5 완료 (2026-07-11, PR #256)**. automation BC 액션 실행 엔진. FR-AT-01이 `q_automation_execution`에 적재한 매칭 룰을 `AutomationExecutionWorker`(첫 소비자, @Scheduled pgmq consumer)가 소비 → `ActionExecutor`가 룰의 액션 리스트를 position 순 best-effort 실행(SUCCESS/PARTIAL/FAILED 집계). 액션 4종 — SET_FIELD/ASSIGN/ADD_COMMENT는 신규 shared-kernel **`IssueMutationPort`**(BTS 2번째 cross-BC 쓰기 포트, IssueTransitionPort 선례·동기·fail-closed)로 issue-tracking prod 어댑터(`@Profile("prod")` 위임, 도메인 우회 금지·OCC 현재version 재조회+1회 재시도·dryRun=트랜잭션 롤백)에 위임, CALL_WEBHOOK은 기존 `OutboundUrlValidator`(SSRF) + `WebhookActionClient`(RestClient redirect NEVER). **rule actor** = 룰의 `actor_user_id`(신규 컬럼, 기본=생성자, **생성·PATCH로 선택 가능** — 지라 Actor 모델, Maxi 확정) — 액션 권한 주체 + AddComment 작성자 결정, fail-closed(차단율 100%). **템플릿 변수** `{{ issue.key }}` 단순 치환(TemplateRenderer, 미정의→빈문자열, config는 스킴-prefix만 검증하고 렌더 후 실제 url을 OutboundUrlValidator가 SSRF 전수검증). **무한루프 2단 가드** — executionDepth>10(직접 체인) + (ruleId,issueKey) 60초 억제 창(issue-tracking 왕복 리셋 대비, 견고 사이클검출은 FR-AT-04 위임). at-least-once 중복은 best-effort 수용(강한 dedup은 FR-AT-05). 신규 마이그레이션 V302(automation_actions)·V303(actor_user_id backfill). ADR [2026-07-11-fr-at-02-automation-actions](../../decisions/2026-07-11-fr-at-02-automation-actions.md). D6(액션 빌더 UI)·D7(E2E)는 후속 PR(FR-AT-01 #251→#254 분할 선례).
>
> **D6/D7 완료 (2026-07-12, PR #260)**. 순수 프론트(apps/web·백엔드 변경0). 기존 `AutomationRuleFormDialog`에 4종 액션 편집(SET_FIELD 필드타입별 값위젯[summary/description/environment=텍스트·priority 1~5·impact 1~3·labels 태그]·ASSIGN `ProjectMemberSelect`·ADD_COMMENT 템플릿힌트·CALL_WEBHOOK url/method/헤더쌍/body) + 다중 액션 순서변경(위/아래·drag-drop 없이) + rule actor 피커(기본=미설정→백엔드 생성자 폴백). **config 비대칭**(응답=객체/요청=JSON문자열) `parseActionConfig`↔`serializeActionConfig` 분리, SET_FIELD priority/impact 숫자강제(EC9), 안정 key(crypto.randomUUID). 목록 액션 타입 배지. Zod 계약에 `actions`·`actorUserId` 추가. 게이트2 코드리뷰 CONCERNS 3건 수정(C1 빈 헤더키 필터·C2 헤더 쌍배열 모델로 중복소실 방지·N1 actor 기본값 복귀 UI). 단위/컴포넌트 회귀 0(automation 162)·E2E 신규 4+기존 8 회귀0. → **FR-AT-02 전체 완료(D1~D7)**, automation BC 2/7.

### §2.3 FR-AT-03 — 조건 분기 (if-else, 표현식)

**우선순위**. 필수 | **선행**. §2.1, §2.2 | **Plan slug**. `automation/conditions`

- [x] D1. 도메인 — Condition + Expression (책임. backend-engineer)
- [x] D2. 명세 — 표현식 문법 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `automation_conditions(expression)` (책임. db-engineer)
- [x] D4. 백엔드 — 표현식 평가 엔진 (Spring SpEL 또는 자체) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 표현식 케이스 50개 (책임. backend-engineer)
- [x] D6. 프론트 UI — 조건 빌더 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **D1~D5 완료 (2026-07-13, PR #262)**. 조건 분기 백엔드. **구조화 조건 모델**(sealed `Condition` And/Or/Not/Comparison 데이터 트리, JSONLogic류)을 SpEL 대신 채택 — 조건이 런타임 관리자 API로 유입되므로 SpEL 샌드박스 전제(관리자 편집 소스만) 부적합. 코드 실행 경로 구조적 부재. `var` 필드 화이트리스트(issue.key/type/status/priority/assignee/reporter/labels/summary/projectKey)·`MAX_DEPTH 10`/`MAX_NODES 100` DoS 상한·리터럴 배열 100개 상한. `ConditionEvaluator` 순수 트리워크 fail-safe. `V304__automation_conditions`(rule_id PK·expression JSONB·rule ON DELETE CASCADE). 신규 cross-BC 읽기 포트 `IssueSnapshotPort`(shared-kernel, fail-closed 주입) + issue-tracking `@Profile prod` 어댑터(기존 가시성 강제 read 재사용). `ActionExecutor` 조건 게이트 → `SKIPPED`(게이트 전체 fail-safe, 조건 미설정은 통과). **게이트2 보안 수정(P1)**: 조건 평가를 `actorUserId`(changeActor로 위조 가능)가 아닌 **`createdBy`(위조 불가 작성자) 가시성**으로 강제 — §12.4 관리자 우회 없음 read 오라클 차단. 조건 빌더 UI(D6)/E2E(D7)는 별개 후속(미구현). ADR [2026-07-12-fr-at-03-automation-conditions](../../decisions/2026-07-12-fr-at-03-automation-conditions.md). → **FR-AT-03 백엔드 완료(D1~D5)**, D6/D7 UI 남아 automation BC 2/7 유지.

> **D6/D7 완료 (2026-07-13, PR #265)**. 순수 프론트(apps/web·백엔드 변경0). 기존 `AutomationRuleFormDialog`에 **조건 섹션**(5번째) 추가 — And/Or/Not 그룹 + Comparison(필드 화이트리스트 9종×연산자 9종) 재귀 트리 편집기(`ConditionBuilder`+`ConditionComparisonRow`). **Maxi 확정 2건**: **[D1] 조건 제거=empty-AND**(빈 트리↔`{"and":[]}` 왕복, 백엔드 0 변경 — PATCH `condition=null`이 "미변경"이라 3-state 래퍼 대신 항등원-참으로 게이트 실질 제거) · **[D2] 값 위젯=재사용만 드롭다운**(priority 숫자select·assignee/reporter `ProjectMemberSelect`·나머지 텍스트, status/type 유효값 드롭다운은 후속). `parseConditionExpression`/`serializeConditionExpression` 왕복 — 백엔드 `Condition.kt` 와이어 shape(var 선두 이항·`in` 정규형·단항·빈그룹 prune·priority 숫자강제) **하드코딩 대조**로 계약갭 차단. **G1 플립 방지**(`resolveConditionPayload` 3분기: create 생략/patch-기존조건 clear/patch-원래null 생략) · **G2 빈-Or 항상거짓 footgun** serialize prune. Zod 계약에 `condition` 추가. 구현 중 버그 2건 자체 발견·TDD 수정(공유 객체참조 중복 React key·정적 id 중복/라벨 오포커스 C1). 단위/컴포넌트 회귀 0(automation 221)·E2E 신규 4+기존 12 회귀0·typecheck/lint 0. 코드리뷰 PASS(BLOCKER 0). ADR 재사용(백엔드 0). → **FR-AT-03 전체 완료(D1~D7)**, automation BC 3/7.

### §2.4 FR-AT-04 — 규칙 충돌 정적 분석

**우선순위**. 필수 | **선행**. §2.1~§2.3 | **Plan slug**. `automation/conflict-analysis`

- [x] D1. 도메인 — RuleConflict (책임. backend-engineer)
- [x] D2. 명세 — 사이클/우선순위 모호성/필드 충돌/권한 부족 검출 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [x] D4. 백엔드 — 규칙 저장 후 lint (응답 conflicts 포함) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 저장 후 경고 모달 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **D1~D5 완료 (2026-07-13, PR #268)**. 규칙 충돌 정적 분석 백엔드. 자동화 규칙 저장 시 프로젝트의 규칙 집합을 정적 분석해 **충돌 4종**(product 3종 + SDD 8.7 3종의 합집합, Maxi 확정)을 경고로 알린다 — **CYCLE**(액션이 다른 규칙의 트리거를 유발하는 방향 그래프의 사이클, DFS·self-loop 포함) / **FIELD_CONFLICT**(같은 트리거에 동시 매칭되는 규칙들이 같은 필드를 다른 값으로 SET, 규칙 내부 액션 간 포함) / **PRIORITY_AMBIGUITY**(같은 트리거에 매칭되는 규칙이 2개 이상이고 실행 순서가 `created_at,id`로만 결정 — priority 컬럼 부재) / **PERMISSION_MISSING**(rule actor가 액션 대상의 프로젝트 레벨 이슈 UPDATE 권한 부재). 신규 `RuleConflictAnalyzer`(순수 분석, `@Component`) + `RuleConflict`/`ConflictType`/`ConflictSeverity` 값 객체. **강제성 = 전부 soft WARNING**(어떤 충돌도 저장 무차단, Maxi 확정) — 기존 `POST`/`PATCH` 저장 경로가 저장 커밋 **후** lint하고 응답 DTO에 `conflicts` 배열을 담는다(별도 엔드포인트·테이블 없음, GET은 `@JsonInclude(NON_NULL)`로 미포함). 분석은 **fail-safe**(어떤 예외도 저장 성공 훼손 안 함). 신규 cross-BC 소비 `IssuePermissionResolver`(shared-kernel, `IssueScope.Project`로 프로젝트 레벨 근사·non-prod stub·`(actor,project,permission)` 메모이제이션) — AddComment 액션은 `IssuePermission`에 댓글 권한이 없어 권한 분석 제외. FR-AT-02 `AutomationExecutionWorker` KDoc의 "견고한 사이클 검출은 FR-AT-04 위임"을 완성(런타임 루프 가드는 유지, 상보). 성능 스모크 100규칙 0.876s(NFR 1s). 모듈 test 416(회귀 0)·BC 격리 ArchTest(cross-BC import 0). ADR [2026-07-13-fr-at-04-conflict-analysis](../../decisions/2026-07-13-fr-at-04-conflict-analysis.md). 충돌 경고 모달 UI(D6)/E2E(D7)는 별개 후속(미구현). → **FR-AT-04 백엔드 완료(D1~D5)**, D6/D7 UI 남아 automation BC 3/7 유지.

> **D6/D7 완료 (2026-07-14, PR #269)**. 순수 프론트(apps/web·백엔드 변경0). 규칙 저장(생성/수정) 응답의 `conflicts`(soft WARNING 4종)를 **저장 후 경고 모달**로 표시 — 저장은 이미 성공, 비차단 정보성. 신규 `RuleConflictWarningModal`(WebhookTokenModal 패턴 미러·amber·`role="alert"`·종류 한국어 배지+detail·`ruleIds`는 "관련 규칙 N개" 축약·UUID 비노출). Zod 계약에 `conflicts`(`.optional()` — GET 부재/create·patch 배열, `@JsonInclude(NON_NULL)`↔Zod 정합) + `ConflictType`/`ConflictSeverity`/`RuleConflict` 미러 추가(API 레이어 코드 무변경). `AutomationRuleFormDialog` `onConflicts` 콜백(create=`response.rule.conflicts` 중첩/patch=최상위 `conflicts`, `length>0`만 발화) + 페이지 배선. **WEBHOOK 토큰+충돌 동시=토큰 우선 순차**(Maxi 확정, `conflicts={webhookToken===null ? conflicts : null}` 가드). MSW conflicts 시나리오 토글(E2E 결정적, store 미저장 GET 계약 불변). 코드리뷰 PASS(BLOCKER 0)+SUGGESTION 적용(빈 배열 방어 가드). 유닛 회귀0(6837)·E2E 신규4+회귀8·typecheck/lint 0. FR 총수 123 불변. → **FR-AT-04 전체 완료(D1~D7)**, automation BC 4/7.

### §2.5 FR-AT-05 — 실행 이력 + 디버깅 (재실행, 단계별 추적)

**우선순위**. 필수 | **선행**. §2.1~§2.3 | **Plan slug**. `automation/execution-history`

- [x] D1. 도메인 — RuleExecution (책임. backend-engineer)
- [x] D2. 명세 — trace context + 재실행 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `rule_executions(rule_id, trigger_event, result, ...)` (책임. db-engineer)
- [x] D4. 백엔드 — 실행 이력 저장 + `POST /api/v1/automation/executions/{id}/replay` (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 실행 이력 + 단계별 trace + 재실행 버튼 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **D6/D7 완료 (2026-07-14, PR #271)**. 순수 프론트(apps/web·백엔드 변경0). #270 백엔드(D1~D5)가 노출한 3 엔드포인트를 소비 — 룰별 실행 이력 목록(`GET .../rules/{ruleId}/executions`)·단건 trace(`GET /api/v1/automation/executions/{id}`)·동기 재실행(`POST .../executions/{id}/replay`). 프로젝트 설정 룰 목록에 **이력** 버튼(`onViewHistory`) → 신규 `RuleExecutionHistoryDialog`(Radix Dialog)가 목록 + `issueKey` 필터(Enter 적용) + 더 보기(`useInfiniteQuery`, filter-aware queryKey) 렌더. 각 행 `RuleExecutionTraceRow`는 상태 배지(SUCCESS/PARTIAL/FAILED/SKIPPED 색상)·트리거 한국어 라벨·issueKey/"이슈 없음"·성공/총 액션·"재실행됨" 마크를 요약, 펼치면 액션별 결과(`outcomes`)+trigger 원문 JSON+인라인 2단계 replay 확인을 표시. replay 성공 시 토스트 + 새 실행 자동 펼침. **Zod 계약 = base 스키마 분리**(`ruleExecutionBaseSchema` 공통 8필드 → summary는 `actionCount`/`successCount` extend·detail은 `projectKey`/`triggerEvent`(`z.unknown()`)/`outcomes` extend) — 초기 spec이 detail을 summary로 extend해 집계값을 잘못 required로 만든 drift를 코드리뷰 전 자체 발견·수정(backend `RuleExecutionResponses.kt` detail DTO엔 집계값 없음, `@JsonInclude` 미설정→명시 직렬화라 `.nullable()`). `issueKey`/`replayedFrom`/`error` `.nullable()`. replay `onSuccess`가 detail 캐시 시드 + 열려있는 모든 필터 쿼리 첫 페이지에 prepend(filter-aware, `getQueriesData` prefix + 타입가드). MSW stateful 3핸들러(전역 `@/test/server` 단일 인스턴스 — 지역 `setupServer` 동시 존재 시 더블 디스패치 함정) + 시나리오 토글 2종(`EMPTY_EXECUTIONS`/`RULE_UNAVAILABLE`, addInitScript localStorage). 게이트2 코드리뷰 CONCERNS 4건 수정(C1~C3 Zod drift/`WireExecutionDetail` Omit 제거/문서 정리·C4 filter-aware replay prepend). typecheck/lint 0·유닛 회귀0(6933, 434파일)·build OK·E2E 신규 S1~S5(25). FR 총수 123 불변. → **FR-AT-05 전체 완료(D1~D7)**, automation BC 5/7.

### §2.6 FR-AT-06 — YAML 가져오기/내보내기 (GitOps)

**우선순위**. 높음 | **선행**. §2.1~§2.4 | **Plan slug**. `automation/yaml-gitops`

- [x] D1. 도메인 (책임. backend-engineer)
- [x] D2. 명세 — YAML 스키마 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [x] D4. 백엔드 — `POST .../rules/import` + `GET .../rules/export` (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — round-trip (책임. backend-engineer)
- [x] D6. 프론트 UI — YAML 업로드/다운로드 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **D1~D5 완료 (2026-07-14, PR #272)**. 백엔드만(UI D6/D7 후속). 자동화 규칙(트리거·조건·액션)을 YAML로 내보내고 올려 upsert하는 GitOps 백엔드. **경로 deviation**: 원안 flat 경로(`/api/v1/automation/import`)에서 **프로젝트 스코프 하위**로 정렬 — `GET/POST /api/v1/projects/{projectKey}/automation/rules/export·import`(기존 automation 5 엔드포인트가 전부 프로젝트 스코프·규칙이 프로젝트 소속). export = 프로젝트의 소프트삭제 안 된 전 규칙(활성+비활성)을 `application/yaml;charset=UTF-8`(Content-Disposition attachment)로, 결정적 순서(createdAt→id)·webhook 토큰/version/nextFireAt 미포함. import = `@RequestBody` YAML 텍스트를 upsert. **식별 = UUID id 기준**(Maxi 확정): id 있고 이 프로젝트에 존재→UPDATE / id 있고 전역 미존재→**id 보존 CREATE**(멱등성 전제) / id 있고 타 프로젝트·소프트삭제 소유→400 `AUTOMATION_IMPORT_INVALID`(EC4, PK 전역 유일성) / id 부재→새 UUID CREATE. **원자성 = atomic fail-closed**(단일 `@Transactional`, 하나라도 실패 시 전량 롤백·conflict 분석은 커밋 후 별도 호출로 rollback-only 오염 회피). 검증(name≤200·cron·조건 MAX_DEPTH=10/MAX_NODES=100/FIELD_WHITELIST·url) 전부 기존 도메인 파서 재사용. 신규 마이그레이션 0(V300/V302/V304 활용). 도메인 팩토리에 id·enabled 보존 파라미터 추가(비활성 규칙 round-trip). YAML mapper는 내부 전용 `ObjectMapper(YAMLFactory())`(전역 JSON 빈 오염 금지·`YamlSeedService` 선례). 응답 `AutomationImportResponse{created, updated, total, ruleIds, webhookTokens?(생성 WEBHOOK 1회 노출), conflicts?}`. 상한 `MAX_IMPORT_RULES=500`→413. **round-trip 시맨틱**: 동일 프로젝트 멱등 재적용(GitOps apply)·migrate/restore(원본 규칙 부재 상태)는 id 보존으로 재현. 원본을 살린 채 다른 프로젝트로 **복사**하려면 YAML에서 `id:` 제거(새 규칙으로 생성) — EC4가 살아있는 타 프로젝트 id 재사용을 거부. 7 TDD 태스크(codec·도메인 팩토리·export·import 서비스·import 엔드포인트·round-trip 실서블릿·문서) 직렬 dispatch. FR 총수 123 불변(D-step). → automation BC **5/7 유지**(FR-AT-06은 D6/D7 UI 완료 후 6/7 반영, FR-AT-01~05 선례).

> **D6/D7 완료 (2026-07-15, PR #273)**. **순수 프론트**(`apps/web`·백엔드/DB/마이그레이션/ADR 0). #272가 노출한 2 엔드포인트 소비 — export(`GET .../rules/export`)·import(`POST .../rules/import`). **배치 결정(Maxi 확정)**: `AutomationRuleList` 기존 헤더 행에 "룰 추가" 옆으로 버튼 2개(한 줄), 가져오기는 신규 `AutomationYamlImportDialog`(Radix 직접). **라우터 변경 0** — prop threading(`onExportYaml`/`onImportYaml`/`isExportingYaml`)은 기존 `onAddRule`/`onViewHistory` 관례 동형(#271 선례). **★ 프론트 함정 2건**: ① STATELESS JWT라 export를 `<a href download>`로 하면 401 → `apiFetch`→`blob()`→기존 `triggerBlobDownload`(`lib/download.ts:16`) 재사용, 파일명은 서버 `Content-Disposition` 파싱(`search.ts` 관례) ② import는 백엔드 `consumes` 화이트리스트가 YAML/텍스트 4종만이라 **multipart면 415** → `File.text()` 원문 + `Content-Type: application/yaml;charset=UTF-8`. 후자 때문에 **`api/client.ts`에 문자열 body pass-through 추가**(`isFormData`→`isRawBody`, 유일한 공유 인프라 변경) — `apiFetch` 호출자 160건 전수 확인해 문자열 body 0건·회귀 0 검증. **★ 에러 표시 단일 규칙**(errorCode 분기 없음): `failedIndex+1` 접두 + **서버 `detail` 그대로** + "적용된 변경 없음(전량 취소)" 항상 병기. 초안은 S5(깨진 YAML)/S8(projectKey 불일치)에 다른 UI를 요구했으나 **백엔드가 둘을 같은 `else` 분기로 처리**해 status/errorCode/type이 동일하고 한국어 `detail`로만 달라 **문자열 매칭 강요**([[crossbc-failure-classification-typed-not-name]] 위반) → 분기 제거하고 `id:` 제거 안내를 **Dialog 상시 도움말**로 승격(타 프로젝트 사용자는 ①projectKey 불일치→②id 귀속 충돌로 에러를 두 번 만나는데 정작 필요한 ②에서 안내가 사라지는 구조도 동시 해소). 409도 백엔드가 사용자용 한국어 안내를 주므로 프론트 고정문구 없음(예외 하나가 가짜 그린 위험을 만들던 것 제거). **토큰 1회 노출** — 결과 **최상단**에 목록+복사(`WebhookTokenModal.tsx:12-17` 문구 재사용, 컴포넌트는 미재사용), 닫기 **4경로**(X·ESC·오버레이·`onOpenChange`) 전부 2단계 확인(하나라도 빠지면 영구 분실). 클라 선제 1MiB 차단(백엔드 `MAX_IMPORT_BYTES` 미러). **MSW 등록 함정 구조적 회피** — 기존 `automation-rule-handlers.ts` 확장이라 `handlers.ts` 무수정(신규 핸들러 파일 미생성 → 등록 누락 원천 불가). MSW **라우트 순서 함정 실측**(`:id` 와일드카드가 `"export"`를 id로 오인해 404 → export/import를 앞에 배치 + 회귀 가드). 프론트는 **YAML 파싱 안 함**(신규 의존성 0, 원문 전달만). 권한 사전 게이팅은 **범위 밖**(`projectPermissionsSchema`에 `MANAGE_AUTOMATION` 키 부재 + automation UI 게이팅 선례 0건 → 기존 403 fail-closed 유지, 후속 FR 후보). **9 TDD 태스크 / W1만 3-병렬 후 나머지 직렬**(W1에서 [[parallel-dispatch-precommit-hook-race]] 4회차 재발 — files 교집합 0이어도 index 공유로 발생, quiescent 시점에 커밋 분리 복구·트리 해시 대조로 무손실 증명). 게이트1 리뷰(design 7.5/10 + eng)가 **구조 결함**(T7의 EC8이 T5 소유 파일을 건드려야 해 BLOCKED 귀결 → T5 이관)·**가짜 그린**(409 분기 무테스트)·**RED 실행불가**(존재하지 않는 `baseProps` 전제)·**모바일 오버플로**(헤더 행 wrap 부재, `AutomationRuleRow:197` 패턴 동형 적용) 사전 차단. 유닛 **6979 전량 통과**(회귀 0)·typecheck/lint/build 0·E2E 신규 4(E1 다운로드·E2 성공·E3 failedIndex 1-based·E4 토큰+ESC) + 기존 automation E2E 25 동시 통과. FR 총수 **123 불변**(D-step). → **FR-AT-06 전체 완료(D1~D7)**, automation BC **6/7**.

### §2.7 FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정)

**우선순위**. 높음 | **선행**. §2.1 (WEBHOOK 트리거), §2.2 (액션) | **Plan slug**. `automation/pr-merge`

- [x] D1. 도메인 — GitWebhookEvent (책임. backend-engineer)
- [x] D2. 명세 — GitHub/GitLab Webhook 처리. 커밋 메시지에서 이슈 키 추출 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용. webhook secret 저장) (책임. db-engineer)
- [x] D4. 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 (책임. backend-engineer + security-engineer)
- [x] D5. 백엔드 테스트 — 가짜 페이로드 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Webhook URL 생성 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

> **PR-B 완료 (2026-07-17, PR #276)**. DEC-11(Maxi 확정)에 따라 FR-AT-07은 **PR-A**(인바운드 웹훅 prod
> 도달 가능화 + 암호화 키 배포, #274/#275 완료) → **PR-B**(Fix Version 설정 통로, 본 PR) → **PR-C**(PR_MERGED
> 트리거 + Git webhook, 미착수) 3분할로 진행한다. **PR-B는 PR-C가 완성할 "PR 머지 시 Fix Version 자동
> 설정"의 설정 통로만 만든다 — 트리거 연결은 없다.** shared-kernel `IssueMutationPort`에 4번째 메서드
> `setFixVersions`(default 없음, fail-closed) 신설 + issue-tracking prod 어댑터(기존 `changeFixVersions`
> 유스케이스 위임, 자기 트랜잭션에서 OCC 버전 재조회) + automation `ActionType.SET_FIX_VERSIONS`/
> `Action.SetFixVersionsAction`(sealed class exhaustive `when` 12지점 전수 반영, 컴파일러 미강제 2지점은
> 회귀 테스트로 방어) + `V306` CHECK 제약 4종→5종 확장(V302 원본 편집 없이 재발행) + 프론트 Zod 계약
> 동기화 + **설정 UI**(`SetFixVersionsFields` — 교체/전체 해제 2모드, 교체+빈 목록은 저장 거부 가드).
> 자동화 규칙 화면에서 `SET_FIX_VERSIONS` 액션을 수동으로 구성해 실행할 수 있으나, **PR 머지로 자동
> 발화하는 경로는 아직 없다**(PR_MERGED 트리거 부재). 기존 automation/issue-tracking/slack-integration
> 회귀 전량 통과 + `:modules:app:test` 9BC prod 조립 재검증 완료.
>
> **D1(GitWebhookEvent)·D2(Git Webhook 처리)·D4(`POST /api/v1/webhooks/git`)·D6(Webhook URL 생성 페이지)와
> 그 테스트(D5/D7)는 전부 PR-C 몫으로 미착수** — 위 체크박스는 그 실체(Git 웹훅 수신·서명 검증·URL
> 발급 화면)가 실제로 구현되는 **PR-C 완료 시점에 마킹**한다. FR-AT-07 자체는 **미완료**로 유지.
> → automation BC **6/7 유지**(FR-AT-06 선례 동형 — D단계 일부 완료는 BC 카운트를 올리지 않음).

> **PR-C 완료 (2026-07-17, PR #278) — 백엔드 전용**. DEC-18(Maxi 확정)에 따라 PR-C는 **백엔드만**이며
> **D6/D7·FR-AT-07 완료 마킹·BC 7/7은 PR-D 몫**이다(automation 선례 6/6 준수). **D1~D5 마킹** — D3는
> 최초 열거에서 누락됐으나(`(활용. webhook secret 저장)` 문구가 기존 스키마 재사용을 전제했음) PR-C가
> **`V307 git_webhooks.secret_encrypted` 신규 테이블**을 만들었으므로 실물 기준으로 함께 마킹한다.
> **핵심 설계 — PR_MERGED는 제3의 경로**. `q_automation_events`(issue-tracking 소유)를 타지 않고
> 컨트롤러가 룰을 직접 조회해 동기 enqueue 한다. 따라서 `TriggerMatcher` wire 맵에 `pr.merged` 추가는
> **죽은 코드**이고, `AutomationRuleService` 의 PR_MERGED `else null` 이 정답이다(git 토큰은 프로젝트
> 단위 `git_webhooks` 소유). **401 응답 본문 단일화** — EC1~EC4·EC9·EC15 전부 같은 errorCode, 사유
> 구분은 구조화 로그만(응답이 토큰 존재 오라클이 되지 않게). **팬아웃 3중 상한** — 20키 / title·body
> 2KB 절단 / 룰×키 100. 마이그레이션 **V307**(git_webhooks)·**V308**(deliveries)·**V309**(CHECK 5→6).
> **dedup은 서명 검증 후** + 단일 트랜잭션(DEC-23) — 미인증 요청은 `git_webhook_deliveries` 에 흔적 0.
>
> **★ 평문 토큰 누출 2건 — 통로가 서로 다르다**. ① `AutomationWebhookController` 가 `ProblemDetail.instance`
> 를 비워 둬 Spring 이 **원문 토큰이 든 요청 URI 로 자동 채움** → 404·413·400 전 응답에 평문 토큰이
> 실려 나갔다(T12가 중앙 permitAll 을 열어 prod 노출). `INSTANCE_PATH` 고정으로 차단 + 회귀 테스트(RED
> 확인). ② **`BasicErrorController` 의 `path` 필드** — `@ExceptionHandler` 가 잡지 않는 415·405 는
> `sendError` → `/error` ERROR 디스패치로 가고, `/error` 가 permitAll 이면 기본 에러 본문의 `path` 에
> 원문 토큰이 실린다(①의 `instance` 수정으로는 **안 닫히는 별개 통로**). 현재는 `/error` 가
> `anyRequest().authenticated()` 에 걸려 도달 불가라 누출 0이지만, **그 안전은 컨트롤러 설계가 아니라
> "/error 가 인증 대상"이라는 간접 조건에 얹혀 있다** — `/error` permitAll 은 Spring Boot 의 흔한 관행이고
> 웹훅과 표면적 연관이 없으며, 뒤집으면 **공개 대시보드 공유 토큰·iCal 피드 토큰·웹훅 토큰이 동시에**
> 샌다(셋 다 경로 세그먼트에 토큰). Maxi 확정 — **능동 하드닝(ErrorAttributes 에서 path 제거) 대신
> 회귀 가드 + SecurityConfig 경고 주석**(T15-6 이 뒤집으면 fail).
>
> **T15 prod 조립 HTTP 검증이 이 PR의 유일한 진짜 관문**(`GitWebhookInboundPermitAllTest`). BC test-boot 의
> `AutomationTestSecurityConfig` 는 `@TestConfiguration` 이라 prod 조립에 없어 중앙과의 divergence 를
> 원리적으로 못 잡는다. HMAC 직접 재계산 → git·automation **양쪽 202**(필터 통과 + 서명 검증 통과 동시
> 증명), 음성 판별자는 **응답 본문**(상태코드만은 vacuous — permitAll 이 새도 컨트롤러가 401 을 던져 상태는
> 그대로다), EC1 본문 == S2 본문(오라클 부재), 서명 오류 N회 후 deliveries 행 수 불변(DB 쓰기 0).
> `INBOUND_WEBHOOK_PATHS` 뮤테이션 → **7 tests 2 failed** 확인 후 원복(T15-1·T15-3 이 permitAll 가드).
> **★ T15-4·T15-5 는 뮤테이션에서 안 깨진다** — permitAll 이 죽으면 둘 다 필터 401 로 수렴해 vacuous 하게
> 통과하므로 **permitAll 근거로 인용 금지**(각자 다른 축을 지킨다). `:modules:app:test` **19 tests 0 failures**.
> FR 총수 **123 불변** — `fr-index.md`·`README.md`·`CLAUDE.md` 미변경(#277 동시 PR 의 카운트 충돌 회피).
> → FR-AT-07 **미완료 유지**, automation BC **6/7 유지**. 남은 것은 **PR-D**(D6/D7).

## §NFR automation BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 트리거 → 액션 처리 지연 | 5s | ___ | pgmq consumer + Action |
| 규칙 충돌 정적 분석 | 1s | ___ | 100개 규칙 |
| 실행 이력 재실행 | 1s | ___ | 단일 규칙 |
| YAML import (100 규칙) | 10s | ___ | (대량 케이스) |
| Webhook 응답 | 200ms | ___ | (Git PR 머지) |
| 권한 위반 액션 차단율 | 100% | ___ | 보안 가드 |

### BC 완료 조건

- [ ] §2 (FR-AT 7개) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "automation BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "automation BC 완료"
