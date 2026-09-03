// advisory lock 대기에 200ms 예산을 걸고 획득 직후 원복하는 두 락 공용 헬퍼 — agile-planning BC (부채 166)

package com.bts.agileplanning.repository

import org.jooq.DSLContext

/** advisory lock 대기 상한. 초과하면 PostgreSQL 이 SQLSTATE `55P03` 으로 statement 를 취소한다. */
private const val LOCK_WAIT_BUDGET = "200ms"

/** 예산 원복 값 — PostgreSQL `lock_timeout` 의 기본값이자 「상한 없음」이다. */
private const val LOCK_WAIT_UNLIMITED = "0"

/**
 * [lockKey] 로 `pg_advisory_xact_lock` 을 **200ms 예산 안에서** 잡는다.
 *
 * ### 왜 필요한가
 * `pg_advisory_xact_lock` 은 **무한 대기**다. 홀더가 안 풀면 요청 스레드와 커넥션이 영원히 묶인다.
 * 저장소 전역에 `lock_timeout`·`statement_timeout` 설정이 0건이라 위에서 잡아 주는 것도 없다(부채 166 ②).
 *
 * ### 왜 `dsl.execute`/`dsl.fetch` raw SQL 인가 — `DATA.md §5` 정식 예외
 * 예외는 (i) `?` 바인딩과 (ii) jOOQ 미지원 PostgreSQL 함수를 **둘 다** 요구한다. 세 statement 모두
 * 값은 전부 `?` 로 바인딩하고(문자열 결합 0), `set_config(text,text,bool)`·`pg_advisory_xact_lock`·
 * `hashtextextended` 는 jOOQ DSL 에 대응이 없다.
 * 선례는 `docs/adr/2026-05-26-jooq-execute-advisory-lock-exception.md`.
 *
 * **`SET LOCAL lock_timeout = '200ms'` 은 쓰지 않는다** — `SET` 은 리터럴만 받아 바인딩이 불가하므로
 * 조건 (i) 를 못 채운다. 값이 상수라도 예외 조항을 반만 만족하는 형태를 남기지 않는다.
 *
 * ### 실측 근거 (2026-09-03)
 * 「`lock_timeout` 이 advisory lock 대기에 걸리는가」를 가정하지 않고 쟀다. 홀더가
 * `pg_advisory_xact_lock(987654321)` 을 쥔 `bts-postgres-dev`(`quay.io/tembo/pg16-pgmq`) 에서
 * `set_config('lock_timeout','200ms',true)` 뒤 같은 락을 잡으면 **206.5ms 에 취소**되고
 * SQLSTATE 는 **`55P03` (canceling statement due to lock timeout)** 이다.
 * 그것이 `JooqExceptionTranslator` 를 거쳐 [org.springframework.dao.CannotAcquireLockException] 으로
 * 도착하는 것도 `AdvisoryLockBudgetTest` red 단계에서 **찍어 확인**했다(cause 는 `PSQLException`).
 * ⇒ 폴백안(`pg_try_advisory_xact_lock` + 폴링 — 형제 BC 의 `WorkflowCache` 방식)은 채택하지 않았다.
 * 대기와 취소를 DB 가 관리하는 쪽이 단순하다.
 *
 * ### ★ 원복이 필수다 (N6)
 * `set_config(..., is_local = true)` 는 **트랜잭션 스코프**다. 되돌리지 않으면 락 획득 뒤에 이어지는
 * `findActiveByBoard`·`updateStatus` 의 **행 락 대기까지** 200ms 에 끊긴다. 부채 166 이 지적한 것은
 * advisory lock 무한 대기뿐이고, 행 락까지 끊으면 **정상 경합이 503 을 받는 신규 회귀**다.
 *
 * 세 번째 statement 를 `try/finally` 로 감싸지 않는 이유 — 두 번째가 `55P03` 을 내면 트랜잭션이 이미
 * abort 상태라 이어지는 어떤 statement 도 `25P02` 로 실패한다. 그리고 롤백이 GUC 를 함께 되돌리므로
 * 되돌릴 것도 남지 않는다.
 *
 * ### ★ 트랜잭션 계약은 호출자에 남는다 (N7)
 * 이 함수는 [DSLContext] 확장 함수라 **Spring 프록시를 타지 않는다** — 여기에 `@Transactional` 을
 * 달아도 아예 안 먹는다. `@Transactional(propagation = MANDATORY)` 는
 * [SprintRepository.acquireSprintStartLock] · [BoardRepository.acquireProjectScrumBoardLock] 에
 * **그대로 남긴다.** `REQUIRED` 로 새면 트랜잭션 없이 불렸을 때 자기 트랜잭션을 열고 즉시 커밋해
 * 락이 그 자리에서 풀리는데 예외 없이 조용히 성공한다.
 *
 * ### 키 계산은 `hashtextextended(text, 0)` 를 유지한다 (N4)
 * `WorkflowCache` 의 `key.hashCode().toLong()`(JVM int 폭)로 바꾸면 형제 락들과 **잠금 공간이 갈린다.**
 * `hashtextextended` 가 bigint 를 반환해 `pg_advisory_xact_lock(bigint)` 단일 시그니처와 정합한다
 * (`(bigint, bigint)` 시그니처는 없다 — memory `advisory-lock-bigint-toctou`).
 *
 * @param lockKey 잠금 공간 접두를 포함한 키 (`sprint-start:<boardId>` · `scrum-board:<projectKey>`).
 * @throws org.springframework.dao.CannotAcquireLockException 200ms 안에 락을 못 얻은 경우.
 */
internal fun DSLContext.acquireXactLockWithBudget(lockKey: String) {
    // ① 예산을 건다. is_local = true → 커밋/롤백과 함께 사라진다.
    fetch("SELECT set_config('lock_timeout', ?, true)", LOCK_WAIT_BUDGET)
    // ② 기존 blocking 락을 그대로 유지한다 — 결과 행은 소비만 하고 버린다(반환값이 void).
    fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)
    // ③ 즉시 원복한다. 이 줄이 없으면 뒤따르는 행 락 대기까지 200ms 에 끊긴다(N6).
    fetch("SELECT set_config('lock_timeout', ?, true)", LOCK_WAIT_UNLIMITED)
}
