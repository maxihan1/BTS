# FR-AT-05 실행 이력 + 디버깅 (재실행, 단계별 추적)

> slug: fr-at-05-execution-history
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-14

## Brief

FR-AT-05 실행 이력 + 디버깅 (automation BC). 자동화 룰이 언제·어떤 트리거로·어떤
결과(SUCCESS/PARTIAL/FAILED)로 실행됐는지 이력을 남기고(`rule_executions`), 단계별
추적(trace)과 재실행(replay) API를 제공.

- 선행. §2.1~§2.3 (AT-01 트리거 · AT-02 액션 · AT-03 조건) — 완료.
- D단계. D1 도메인(RuleExecution) → D2 명세(trace context + 재실행) → D3 마이그레이션
  (`rule_executions`) → D4 백엔드(이력 저장 + `POST /api/v1/automation/executions/{id}/replay`)
  → D5 테스트 → D6 UI → D7 E2E.
- 선례. automation BC는 백엔드(D1~D5) 먼저 PR → D6/D7 UI 후속 PR로 분할
  (AT-01 #251→#254, AT-04 #268→#269).

## 도메인 정리

- **BC**: automation (9번째 모듈 `com.bts.automation`, JdbcTemplate·V300~V304)
- **영향 엔티티**: RuleExecution (신규 애그리거트 — 한 번의 룰 실행 이력)
- **새 용어**: **RuleExecution** (실행 이력 1건. 룰이 언제·어떤 트리거로·어떤 결과로 실행됐는지 +
  단계별 액션 outcome). SDD §8.6 `AutomationRunLog` 스키마의 실현.
- **권위 있는 스키마 (SDD §8.6)**:
  `rule_id · issue_id · trigger_event · condition_result · actions_executed[{action,result,error}]
   · started_at · finished_at · status(SUCCESS/PARTIAL/FAILED)`.
- **핵심 접지 — 데이터 구조는 이미 존재**: `ActionExecutor.execute()` 가 반환하는
  `ActionExecutionResult(status, outcomes[ActionOutcome(position,actionType,success,error)])` 가
  SDD `actions_executed` 와 1:1 대응. 현재 `AutomationExecutionWorker.runExecution()`(line 203)이
  이 반환값을 **버리고** pgmq 아카이브에만 남긴다 → FR-AT-05 는 이 반환값을 포착해 `rule_executions` 에
  영속화 + 조회/replay 를 얹는 작업.

### Maxi 확정 결정 (2026-07-14 AskUserQuestion)

1. **Replay 의미론 = 동기 실제 재실행**. `POST /executions/{id}/replay` 가 저장된 triggerEvent 로
   `ActionExecutor.execute(rule, storedTriggerEvent, dryRun=false)` 를 **즉시** 호출 → 실제 이슈 변경
   발생 + 새 `rule_executions` row 저장 + 새 trace 를 응답으로 즉시 반환. 정상 워커의 루프가드(b)/억제창은
   우회한다(관리자 명시 재실행이므로). replay 실행 주체 권한 = 룰의 `actorUserId`(정상 실행과 동일).
2. **기록 범위 = 실행 시도분만**. `ActionExecutor.execute` 가 호출된 실행만 기록 →
   SUCCESS/PARTIAL/FAILED + 조건불충족 SKIPPED 캡처. 억제창 suppressed·depth 초과·룰 없음/비활성·
   malformed 는 기록하지 않는다(워커가 ActionExecutor 호출 전에 archive, 운영 노이즈 회피).
3. **PR 분할 = 백엔드 먼저 (D1~D5)**. 이번 PR = 도메인·명세·마이그레이션·이력저장·replay API·백엔드 테스트.
   D6/D7 UI 는 후속 PR (automation BC 선례 AT-01 #251→#254, AT-04 #268→#269).

### 권한/경로 접지 (기존 관례)

- **권한**: 기존 룰 엔드포인트와 동일하게 `MANAGE_AUTOMATION` 가드. 인가 순서 = actor 추출(401) →
  권한 판정(403) → 리소스 조회(404) ([[auth-extraction-before-resource-lookup]]).
  `AutomationActorExtractor`(SecurityContext UUID 추출) 재사용.
- **경로**: 실행 detail/replay 는 product doc 대로 실행 UUID 기반(`/api/v1/automation/executions/{id}`).
  execution → rule → projectKey 역도출해 `MANAGE_AUTOMATION` 가드 적용. 이력 목록은 기존 프로젝트 스코프
  관례를 따라 `/api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions` (SDD "이 이슈에 영향을
  준 자동화" 대응 위해 issueKey 필터 옵션) — 최종 엔드포인트 shape 는 bts-spec 에서 확정.

### 기존 결정 충돌 / 정정 후보

- 충돌: 없음.
- **정정 후보**: `AutomationExecutionWorker` KDoc(line 67)이 "전체 실행 체인 영속 추적/감사"를
  **FR-AT-04**로 위임한다고 적었으나, FR-AT-04 는 실제로 "규칙 충돌 정적 분석"이 됐고 실행 로그/감사는
  **FR-AT-05**(본 작업)다. 이 stale 참조를 본 PR 에서 정정.
- 관련 ADR: [docs/decisions/2026-07-14-fr-at-05-execution-history.md](../decisions/2026-07-14-fr-at-05-execution-history.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-14-fr-at-05-execution-history.md](../specs/2026-07-14-fr-at-05-execution-history.md)

핵심 시나리오 요약.
- 워커가 `ActionExecutor.execute()` 반환값(status + outcomes)을 포착해 `rule_executions`(V305)에 영속화. 기록 범위 = 실행 시도분(SUCCESS/PARTIAL/FAILED/SKIPPED), 억제·malformed 미기록.
- 조회 3종 — 룰별 이력 목록(projectKey 가드, 소프트삭제 룰도 조회, issueKey 필터, keyset) · 단건 trace · replay.
- `POST /executions/{id}/replay` = 저장된 triggerEvent로 동기 실제 재실행(dryRun=false) → 새 row(replayed_from) + trace 즉시 반환.
- 권한 = MANAGE_AUTOMATION(기존 룰 컨트롤러 관례). 신규 cross-BC 포트/큐 없음.
- 워커 KDoc stale "FR-AT-04" 참조 정정.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 3건 발견 후 스펙 보강.
- G1 소프트삭제 룰 이력 접근(NFR-4 정합) → FR-3 projectKey 가드 + 직접 조회로 수정.
- G2 keyset 커서 `(started_at, id)` 복합으로 명시.
- G3 retention/TTL 무제한 증가 → 후속 위임 명시.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
