# FR-AT-01 — 자동화 트리거 (automation BC 첫 기능)

> slug: fr-at-01-automation-triggers
> type: api
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-10

## Brief

FR-AT-01 자동화 트리거. automation BC(TCA — Trigger-Condition-Action 엔진, 7 FR)의 첫 기능이자
BTS 9번째 Gradle 모듈(`backend/modules/automation`) 착수 지점.

5종 트리거: CREATED / UPDATED / COMMENTED / SCHEDULED / WEBHOOK.
백엔드: pgmq consumer + Spring @Scheduled + Webhook 엔드포인트.
데이터 모델: automation_rules(trigger_type, config).
진입조건 §0: MANAGE_AUTOMATION 권한 동반 결선(ADR D1, dead 시드 회피).

D1~D7: 도메인·명세·데이터모델·백엔드·테스트·UI·E2E.

## 도메인 정리

- **BC**: automation (신규 9번째 모듈 `backend/modules/automation`, `com.bts.automation`, test-boot only)
- **영향 엔티티**: `AutomationRule`(신규, 트리거만 — 조건/액션은 FR-AT-02/03), `IssueCommented`(issue-tracking 신규 이벤트)
- **새 용어**: 자동화 룰(AutomationRule) — glossary 트리거/액션 항목으로 대부분 커버, 신규 용어 추가는 Maxi 확인 대기
- **이벤트 통합**: 전용 큐 `q_automation_events`(신규) + `IssueEventPublisher` fan-out(q_webhook_events 선례). automation 대상 = issue.created/updated/commented
- **발화 이음선**: 매칭된 트리거 → `q_automation_execution` 큐 enqueue(FR-AT-02 액션 executor가 소비, 이 FR은 이음선만)
- **cross-BC touch (BC 격리 예외)**: issue-tracking `IssueEventPublisher` 2건 — ① automation fan-out ② IssueCommented 이벤트+발행. FR-API-03 fan-out 선례 준거
- **기존 결정 충돌**: 없음. automation.md 도메인 노트(pgmq 비동기·체인깊이10)와 정합. 체인깊이10은 액션(FR-AT-02) 시점 도입
- **Maxi 확정 3건**: PR범위=백엔드코어 D1~D5 / 발화=실행큐 enqueue / COMMENTED=5종 모두
- **관련 ADR**: [docs/decisions/2026-07-10-fr-at-01-automation-triggers.md](../decisions/2026-07-10-fr-at-01-automation-triggers.md) (생성됨)
- **회귀 함정(memory)**: 신규BC 첫 @Repository test-boot 회귀 · 모듈 첫 @Scheduled/detektMain · pgmq consumer 생명주기 P0 · sealed 서브타입 추가→exhaustive when 전수 · 권한시드↔SchemaMigrationTest 카운트 · Flyway V번호 충돌

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
