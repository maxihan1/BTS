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
 * @param lockKey 잠금 공간 접두를 포함한 키 (`sprint-start:<boardId>` · `scrum-board:<projectKey>`).
 * @throws org.springframework.dao.CannotAcquireLockException 200ms 안에 락을 못 얻은 경우.
 */
internal fun DSLContext.acquireXactLockWithBudget(lockKey: String) {
    fetch("SELECT set_config('lock_timeout', ?, true)", LOCK_WAIT_BUDGET)
    fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)
    fetch("SELECT set_config('lock_timeout', ?, true)", LOCK_WAIT_UNLIMITED)
}
