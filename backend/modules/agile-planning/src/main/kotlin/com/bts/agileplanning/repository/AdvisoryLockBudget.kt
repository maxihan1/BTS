// advisory lock 대기에 200ms 예산을 걸고 획득 직후 **이전 값으로** 되돌리는 두 락 공용 헬퍼 — agile-planning BC (부채 166)

package com.bts.agileplanning.repository

import org.jooq.DSLContext

/**
 * advisory lock 대기 상한. 초과하면 PostgreSQL 이 SQLSTATE `55P03` 으로 statement 를 취소한다.
 *
 * `internal` 인 이유 — `AdvisoryLockBudgetTest` 가 **값 자체**를 못박는다. 시간 단언의 상한
 * (`MAX_WAIT_MS = 2s`)은 CI 편차를 흡수하느라 넓어서 예산을 `"1900ms"` 로 바꿔도 전부 초록이다
 * (게이트 2 리뷰 C5).
 */
internal const val LOCK_WAIT_BUDGET = "200ms"

/** 예산을 걸고 되돌릴 GUC 이름. `?` 로 바인딩해 §5 예외 (i) 를 네 statement 모두가 형태로 만족한다. */
private const val LOCK_TIMEOUT_SETTING = "lock_timeout"

/**
 * [lockKey] 로 `pg_advisory_xact_lock` 을 **200ms 예산 안에서** 잡는다.
 *
 * ### 왜 필요한가
 * `pg_advisory_xact_lock` 은 **무한 대기**다. 홀더가 안 풀면 요청 스레드와 커넥션이 영원히 묶인다.
 * 저장소 전역에 `lock_timeout`·`statement_timeout` 설정이 0건이라 위에서 잡아 주는 것도 없다(부채 166 ②).
 *
 * ### 왜 `dsl.execute`/`dsl.fetch` raw SQL 인가 — `DATA.md §5` 정식 예외
 * 예외는 (i) `?` 바인딩과 (ii) jOOQ 미지원 PostgreSQL 함수를 **둘 다** 요구한다. 네 statement 모두
 * 문자열 결합이 0이고 인자는 전부 `?` 로 바인딩하며(현재 값을 읽는 ① 은 바인딩할 값이 없어 GUC
 * 이름을 `?` 로 뺐다), `current_setting(text)`·`set_config(text,text,bool)`·`pg_advisory_xact_lock`·
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
 * ### ★ 원복이 필수다 — 그리고 **이전 값으로** 되돌린다 (N6)
 * `set_config(..., is_local = true)` 는 **트랜잭션 스코프**다. 되돌리지 않으면 락 획득 뒤에 이어지는
 * `findActiveByBoard`·`updateStatus` 의 **행 락 대기까지** 200ms 에 끊긴다. 부채 166 이 지적한 것은
 * advisory lock 무한 대기뿐이고, 행 락까지 끊으면 **정상 경합이 503 을 받는 신규 회귀**다.
 *
 * ★ 되돌릴 목적지를 `'0'` 으로 **하드코딩하지 않는다.** `'0'` 은 「상한 없음」이라 원복이 아니라
 * 덮어쓰기다. 지금은 저장소 전역 `lock_timeout` 설정이 0건이라 결과가 같지만, 누군가
 * `ALTER ROLE bts SET lock_timeout = '5s'` 나 전역 설정으로 상한을 걸면(ADR 이 전역
 * `statement_timeout` 을 범위 밖 후속으로 남겼으므로 실제로 올 수 있는 변경이다) 이 함수가 그것을
 * 무한 대기로 되돌려 놓는다 — 건 사람은 예외도 로그도 못 본다. 그래서 ① 이 현재 값을 먼저 읽고
 * ④ 가 그 값으로 되돌린다. 부채 166 이 등재된 사유(느린 트랜잭션이 커넥션 풀을 말린다)와 같은
 * 실패 양식을 부호만 바꿔 다시 심지 않는다(게이트 2 리뷰 C2).
 *
 * 마지막 statement 를 `try/finally` 로 감싸지 않는 이유 — 락 획득이 `55P03` 을 내면 트랜잭션이 이미
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
    // ① 지금 걸려 있는 상한을 먼저 읽는다 — ④ 가 되돌릴 목적지다. 안 읽고 '0' 을 쓰면 덮어쓰기다(N6).
    //   GUC 이름까지 `?` 로 뺀다 — 이 statement 는 바인딩할 「값」이 없어서, 그대로 두면 §5 예외 (i) 를
    //   형태로 만족하지 않는 statement 가 하나 생긴다.
    val previous = fetchSingle("SELECT current_setting(?)", LOCK_TIMEOUT_SETTING).get(0, String::class.java)
    // ② 예산을 건다. is_local = true → 커밋/롤백과 함께 사라진다.
    fetch("SELECT set_config('lock_timeout', ?, true)", LOCK_WAIT_BUDGET)
    // ③ 기존 blocking 락을 그대로 유지한다 — 결과 행은 소비만 하고 버린다(반환값이 void).
    fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)
    // ④ ① 이 읽은 값으로 되돌린다. 이 줄이 없으면 뒤따르는 행 락 대기까지 200ms 에 끊긴다(N6).
    fetch("SELECT set_config('lock_timeout', ?, true)", previous)
}
