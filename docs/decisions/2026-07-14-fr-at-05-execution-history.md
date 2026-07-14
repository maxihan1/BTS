<!-- FR-AT-05 실행 이력 + 디버깅 — RuleExecution 영속화·동기 replay·기록 범위·권한/경로 접지 결정 -->

# ADR — FR-AT-05 실행 이력 + 디버깅 (재실행, 단계별 추적)

> 날짜. 2026-07-14 | PR. #270 (백엔드 D1~D5) | BC. automation | 상태. 채택

## 맥락

FR-AT-05는 TCA(Trigger-Condition-Action) 자동화 엔진의 관측/디버깅 계층이다. 트리거(FR-AT-01)·액션(FR-AT-02)·조건(FR-AT-03)이 완료되면서 룰이 실제로 실행되지만, 지금은 "이 룰이 언제·어떤 트리거로·어떤 결과로 돌았는지"를 조회할 방법이 없다.

핵심 접지 — **데이터 구조는 이미 존재한다**. `ActionExecutor.execute()`가 반환하는 `ActionExecutionResult(status, outcomes[ActionOutcome(position, actionType, success, error)])`는 SDD §8.6 `AutomationRunLog`의 `actions_executed[{action, result, error}]`와 1:1 대응한다. 그런데 `AutomationExecutionWorker.runExecution()`(현재 코드)이 이 반환값을 **버리고** pgmq 아카이브에만 남긴다 — 조회 가능한 이력 테이블이 없다. FR-AT-05는 이 반환값을 포착해 `rule_executions`에 영속화하고, 조회(trace)와 재실행(replay)을 얹는다.

문서 drift가 있었다 — `AutomationExecutionWorker` KDoc이 "전체 실행 체인 영속 추적/견고한 사이클 검출은 FR-AT-04에 위임"이라 적었으나, FR-AT-04는 실제로 "규칙 충돌 정적 분석"이 됐다. 실행 로그/감사는 FR-AT-05(본 작업)다. 이 stale 참조를 본 PR에서 정정한다.

권위 있는 스키마 (SDD §8.6 `AutomationRunLog`).

```
rule_id · issue_id · trigger_event · condition_result · actions_executed[{action, result, error}]
· started_at · finished_at · status(SUCCESS / PARTIAL / FAILED)
```

## 결정

### 1. RuleExecution 영속화 — 워커가 ActionExecutor 반환값 포착 (Maxi 확정)

`AutomationExecutionWorker.runExecution()`이 `ActionExecutor.execute()`의 반환값 `ActionExecutionResult`를 포착해 `rule_executions` row로 저장한다. 저장 필드는 SDD §8.6을 실현한다 — `rule_id`, `issue_key`(SDD `issue_id`, 우리 도메인은 이슈 키 문자열), `trigger_type`, `trigger_event`(JSONB), `status`, per-action outcomes(JSONB 배열 `[{position, actionType, success, error}]`), `started_at`, `finished_at`.

- **BC 격리 유지**: 워커/executor는 issue-tracking 타입을 import하지 않는다. triggerEvent는 JSONB로만 저장.
- **저장 실패 격리(fail-safe)**: 이력 저장은 액션 실행의 부수 관측이다. 이력 저장 예외가 pgmq archive/재전달 흐름(at-least-once)을 훼손하지 않도록 예외 격리한다(저장 실패 시 경고 로그, 액션 실행 결과는 그대로 archive).

### 2. Replay 의미론 = 동기 실제 재실행 (Maxi 확정)

`POST /api/v1/automation/executions/{id}/replay`가 저장된 과거 실행의 `trigger_event`로 `ActionExecutor.execute(rule, storedTriggerEvent, dryRun=false)`를 **즉시** 호출한다 → 실제 이슈 변경 발생 + 새 `rule_executions` row 저장(replay도 하나의 실행) + 새 trace를 응답으로 즉시 반환.

- **실행 주체 권한** = 룰의 `actor_user_id`(정상 실행과 동일, ActionExecutor가 이미 이 actor로 위임).
- **루프가드 우회**: 정상 워커의 억제창(b)/깊이 가드는 관리자 명시 재실행이므로 적용하지 않는다(replay는 큐를 거치지 않고 executor를 직접 호출).
- **대안 기각**: (b) 큐 재적재 — 결과를 즉시 못 받아 디버깅 UI의 "고치고 재시도 → 결과 바로 확인" 흐름과 안 맞음. (c) dry-run 미리보기 — "재실행"이 아니라 시뮬레이션이라 실패한 자동화를 실제로 재시도할 수 없음.

### 3. 기록 범위 = 실행 시도분만 (Maxi 확정)

`ActionExecutor.execute`가 호출된 실행만 `rule_executions`에 기록한다 → `SUCCESS` / `PARTIAL` / `FAILED` + 조건불충족 `SKIPPED`. 억제창 suppressed·깊이 초과·룰 없음/비활성·malformed는 기록하지 않는다.

- **근거**: 이들은 워커가 ActionExecutor 호출 **전에** archive하는 종결 분기라, 기록하려면 워커 각 분기에 훅을 흩뿌려야 하고 운영 노이즈가 커진다. 조건불충족 SKIPPED는 ActionExecutor가 이미 반환하므로 "왜 조건에 막혔나"까지는 보임.
- **status enum**: `ActionExecutionStatus`(SUCCESS/PARTIAL/FAILED/SKIPPED)를 그대로 이력 status로 쓴다. SDD §8.6은 SKIPPED를 안 적었으나, 조건 게이트(FR-AT-03)가 SKIPPED를 도입한 이후의 정합 확장(전수 동기화 대상 — SDD §8.6에 SKIPPED 추가).

### 4. 권한/경로 접지 = 기존 룰 컨트롤러 관례 (기존 결정 재사용)

- **권한**: 기존 룰 엔드포인트와 동일 `MANAGE_AUTOMATION` 가드. 인가 순서 = actor 추출(401) → 권한 판정(403) → 리소스 조회(404) ([[auth-extraction-before-resource-lookup]]). `AutomationActorExtractor` 재사용.
- **경로**:
  - 실행 detail/replay는 실행 UUID 기반(`/api/v1/automation/executions/{id}`, product doc D4). execution → rule → projectKey 역도출해 `MANAGE_AUTOMATION` 가드 적용.
  - 이력 목록은 기존 프로젝트 스코프 관례(`/api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions`). SDD "이 이슈에 영향을 준 자동화" 대응 위해 `issueKey` 필터 옵션.
  - 최종 엔드포인트 shape(페이지네이션·필터)는 bts-spec에서 확정.
- **예외 핸들러**: 실행 컨트롤러는 자체 `@RestControllerAdvice(assignableTypes = [...])`로 스코프 한정 ([[domain-exception-http-handler-basepackage-scope]]).

### 5. PR 분할 = 백엔드 먼저 (D1~D5) (Maxi 확정)

이번 PR = D1 도메인(RuleExecution) · D2 명세 · D3 마이그레이션(`rule_executions`, V305) · D4 백엔드(이력 저장 + 조회 + replay API) · D5 백엔드 테스트. D6/D7 UI는 후속 PR (automation BC 선례 AT-01 #251→#254, AT-04 #268→#269).

## 결과

- 신규 마이그레이션 V305(`rule_executions`). automation 모듈은 JdbcTemplate.
- 워커가 실행 결과를 관측·영속화하는 첫 소비 지점 확장(기존엔 archive만).
- replay는 executor 직접 호출 = 큐 우회. 신규 cross-BC 포트 없음(기존 `IssueMutationPort` 재사용).
- 전수 동기화 대상: SDD §8.6(SKIPPED 추가·issue_id↔issue_key 표기)·product §2.5 D박스·fr-index·progress.html·Obsidian.

## 관련

- 선행 ADR: [FR-AT-02 액션](2026-07-11-fr-at-02-automation-actions.md) · [FR-AT-03 조건](2026-07-12-fr-at-03-automation-conditions.md) · [FR-AT-04 충돌 분석](2026-07-13-fr-at-04-conflict-analysis.md)
- SDD: [08. 자동화 엔진 §8.6](../sdd/08-automation-engine.md)
- Learnings: [[condition-eval-chosen-actor-read-oracle]] · [[pgmq-consumer-message-lifecycle-p0]] · [[crossbc-resolver-nullable-fail-open]]
