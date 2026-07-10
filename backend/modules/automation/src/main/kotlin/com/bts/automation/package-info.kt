// automation BC 루트 패키지 문서 — TCA(Trigger-Condition-Action) 자동화 엔진 Bounded Context 개요

/**
 * automation 바운디드 컨텍스트(BC).
 *
 * 이 모듈은 TCA(Trigger-Condition-Action) 자동화 엔진을 담당하는 automation BC의 루트 패키지다.
 * FR-AT-01(이 FR)은 그중 트리거 절반만 구현한다 — 조건(FR-AT-03)·액션(FR-AT-02)은 후행 FR.
 *
 * ## 패키지 구조
 * - `domain` — `AutomationRule` 애그리거트 · `TriggerType`/`TriggerConfig` 순수 도메인 로직 (Task 3)
 * - `application` — `AutomationRuleService`/`TriggerMatcher` (`@Service`, `@Transactional`) (Task 6/7)
 * - `adapter` — JdbcTemplate 기반 Repository·큐 enqueuer(`@Repository`/`@Component`, jOOQ 미도입) (Task 4)
 * - `adapter.web` — `AutomationRuleController`/`AutomationWebhookController`(`@RestController`) · DTO (Task 6/9)
 * - `worker` — `AutomationEventWorker`/`AutomationScheduleWorker`(pgmq consumer, `@Scheduled`) (Task 7/8)
 *
 * ## BC 격리 정책
 * 이 BC는 다른 BC(issue-tracking · identity-access · project-workflow)의 내부 패키지를 직접
 * import하지 않는다. cross-BC 통신은 shared-kernel 포트(`com.bts.shared.*`, 예: `AutomationPermissionResolver`)
 * 와 pgmq 이벤트 발행/구독을 통해서만 허용된다(Task 11 `BcIsolationArchTest`가 이 규칙을 자동 검증).
 *
 * ## Flyway 마이그레이션 범위
 * automation 전용 V번호 범위는 V300~V399다. 다른 BC와 번호 충돌을 피하기 위해
 * `classpath:db/migration/automation` 경로를 사용한다(Task 2).
 */
package com.bts.automation
