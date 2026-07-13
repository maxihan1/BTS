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
- [ ] D6. 프론트 UI — 조건 빌더 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

> **D1~D5 완료 (2026-07-13, PR #262)**. 조건 분기 백엔드. **구조화 조건 모델**(sealed `Condition` And/Or/Not/Comparison 데이터 트리, JSONLogic류)을 SpEL 대신 채택 — 조건이 런타임 관리자 API로 유입되므로 SpEL 샌드박스 전제(관리자 편집 소스만) 부적합. 코드 실행 경로 구조적 부재. `var` 필드 화이트리스트(issue.key/type/status/priority/assignee/reporter/labels/summary/projectKey)·`MAX_DEPTH 10`/`MAX_NODES 100` DoS 상한·리터럴 배열 100개 상한. `ConditionEvaluator` 순수 트리워크 fail-safe. `V304__automation_conditions`(rule_id PK·expression JSONB·rule ON DELETE CASCADE). 신규 cross-BC 읽기 포트 `IssueSnapshotPort`(shared-kernel, fail-closed 주입) + issue-tracking `@Profile prod` 어댑터(기존 가시성 강제 read 재사용). `ActionExecutor` 조건 게이트 → `SKIPPED`(게이트 전체 fail-safe, 조건 미설정은 통과). **게이트2 보안 수정(P1)**: 조건 평가를 `actorUserId`(changeActor로 위조 가능)가 아닌 **`createdBy`(위조 불가 작성자) 가시성**으로 강제 — §12.4 관리자 우회 없음 read 오라클 차단. 조건 빌더 UI(D6)/E2E(D7)는 별개 후속(미구현). ADR [2026-07-12-fr-at-03-automation-conditions](../../decisions/2026-07-12-fr-at-03-automation-conditions.md). → **FR-AT-03 백엔드 완료(D1~D5)**, D6/D7 UI 남아 automation BC 2/7 유지.

### §2.4 FR-AT-04 — 규칙 충돌 정적 분석

**우선순위**. 필수 | **선행**. §2.1~§2.3 | **Plan slug**. `automation/conflict-analysis`

- [ ] D1. 도메인 — RuleConflict (책임. backend-engineer)
- [ ] D2. 명세 — 사이클/우선순위 모호성/필드 충돌 검출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 규칙 저장 전 lint (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 저장 전 경고 모달 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.5 FR-AT-05 — 실행 이력 + 디버깅 (재실행, 단계별 추적)

**우선순위**. 필수 | **선행**. §2.1~§2.3 | **Plan slug**. `automation/execution-history`

- [ ] D1. 도메인 — RuleExecution (책임. backend-engineer)
- [ ] D2. 명세 — trace context + 재실행 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `rule_executions(rule_id, trigger_event, result, ...)` (책임. db-engineer)
- [ ] D4. 백แอ — 실행 이력 저장 + `POST /api/v1/automation/executions/{id}/replay` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 실행 이력 + 단계별 trace + 재실행 버튼 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.6 FR-AT-06 — YAML 가져오기/내보내기 (GitOps)

**우선순위**. 높음 | **선행**. §2.1~§2.4 | **Plan slug**. `automation/yaml-gitops`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — YAML 스키마 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/automation/import` + `GET .../export` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — round-trip (책임. backend-engineer)
- [ ] D6. 프론트 UI — YAML 업로드/다운로드 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.7 FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정)

**우선순위**. 높음 | **선행**. §2.1 (WEBHOOK 트리거), §2.2 (액션) | **Plan slug**. `automation/pr-merge`

- [ ] D1. 도메인 — GitWebhookEvent (책임. backend-engineer)
- [ ] D2. 명세 — GitHub/GitLab Webhook 처리. 커밋 메시지에서 이슈 키 추출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용. webhook secret 저장) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 — 가짜 페이로드 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Webhook URL 생성 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

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
