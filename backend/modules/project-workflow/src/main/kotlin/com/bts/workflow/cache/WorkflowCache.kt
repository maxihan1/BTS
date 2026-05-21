// 워크플로우 캐시 — ConcurrentHashMap 메모리 + pg_advisory_xact_lock 갱신 보호 (200ms timeout)

package com.bts.workflow.cache

import com.bts.workflow.domain.Workflow
import com.bts.workflow.repository.WorkflowRepository
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

/**
 * 워크플로우 메모리 캐시.
 *
 * [ConcurrentHashMap] 으로 인메모리 캐시를 유지하고, 갱신(seed 재적용 등) 시에는
 * PostgreSQL advisory lock (`pg_try_advisory_xact_lock`) 으로 동시 갱신을 직렬화한다.
 *
 * ### Advisory lock 키 산출
 * `key.hashCode().toLong()` 을 lock 식별자로 사용한다.
 * Kotlin `String.hashCode()` 는 JVM 표준 알고리즘(31-multiplier)이므로 동일 문자열에 대해
 * 같은 JVM 프로세스 내에서 항상 동일한 값을 반환한다.
 *
 * ### 다중 인스턴스 한계
 * `pg_try_advisory_xact_lock` 은 DB 세션(커넥션) 단위로 작동하므로 동일 DB 를 공유하는
 * 다중 애플리케이션 인스턴스 간 lock contention 은 올바르게 동작한다.
 * 그러나 인메모리 캐시는 인스턴스별로 독립적이므로, 한 인스턴스의 invalidate 는
 * 다른 인스턴스의 캐시에 전파되지 않는다.
 * 다중 인스턴스 환경에서 완전한 캐시 일관성이 필요한 경우 Redis 등 외부 캐시를 도입해야 한다.
 * (현재 단일 호스트 배포이므로 허용 가능한 제약)
 *
 * @property repo 워크플로우 DB 조회 Repository
 * @property dsl jOOQ DSLContext — advisory lock SQL 실행에 사용
 */
@Service
class WorkflowCache(
    private val repo: WorkflowRepository,
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val cache = ConcurrentHashMap<String, Workflow>()

    /**
     * 워크플로우를 key 로 조회한다.
     *
     * cache hit 시 즉시 반환, miss 시 DB 에서 적재 후 cache 에 보존.
     *
     * @param key 워크플로우 식별 키
     * @return 조회된 [Workflow], 부재 시 null
     */
    fun findByKey(key: String): Workflow? {
        cache[key]?.let {
            log.debug("WorkflowCache hit: key={}", key)
            return it
        }
        log.debug("WorkflowCache miss: key={}, loading from DB", key)
        return repo.findByKey(key)?.also { cache[key] = it }
    }

    /**
     * 지정 key 의 캐시 항목을 무효화한다.
     *
     * 다음 [findByKey] 호출 시 DB 에서 재적재된다.
     *
     * @param key 무효화할 워크플로우 식별 키
     */
    fun invalidate(key: String) {
        cache.remove(key)
        log.debug("WorkflowCache invalidated: key={}", key)
    }

    /**
     * Advisory lock 으로 보호되는 갱신 블록 실행 (예. YAML seed 재적용).
     *
     * `pg_try_advisory_xact_lock(lockKey)` 를 호출해 lock 을 시도한다.
     * 획득 성공 시 [block] 을 실행하고 cache 를 무효화한다.
     * 획득 실패 시 최대 200ms 동안 20ms 간격으로 재시도하며, 그 후에도 실패하면
     * [WorkflowCacheLockTimeoutException] 을 던진다.
     *
     * lock 은 트랜잭션 종료 시 자동 해제된다.
     *
     * @param key 갱신 대상 워크플로우 식별 키
     * @param block lock 보호 하에 실행할 갱신 로직
     * @throws WorkflowCacheLockTimeoutException 200ms 내 lock 획득 실패 시
     */
    @Transactional
    fun withWriteLock(key: String, block: () -> Unit) {
        val lockKey = key.hashCode().toLong()

        if (tryAcquireLock(lockKey)) {
            log.debug("WorkflowCache advisory lock acquired on first try: key={}", key)
            block()
            invalidate(key)
            return
        }

        val deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(LOCK_RETRY_INTERVAL_MS)
            if (tryAcquireLock(lockKey)) {
                log.debug("WorkflowCache advisory lock acquired after retry: key={}", key)
                block()
                invalidate(key)
                return
            }
        }

        log.warn("WorkflowCache advisory lock timeout: key={}, timeoutMs={}", key, LOCK_TIMEOUT_MS)
        throw WorkflowCacheLockTimeoutException(key, LOCK_TIMEOUT_MS)
    }

    /**
     * 테스트 전용 — cache 에 직접 값을 삽입한다.
     *
     * 프로덕션 코드에서 호출 금지.
     */
    internal fun injectForTest(key: String, workflow: Workflow) {
        cache[key] = workflow
    }

    private fun tryAcquireLock(lockKey: Long): Boolean =
        dsl.fetchValue("SELECT pg_try_advisory_xact_lock(?)", lockKey) as Boolean

    companion object {
        private const val LOCK_TIMEOUT_MS = 200L
        private const val LOCK_RETRY_INTERVAL_MS = 20L
    }
}

/**
 * Advisory lock 대기 시간 초과 예외.
 *
 * [WorkflowCache.withWriteLock] 에서 [timeoutMillis] 내 lock 획득에 실패한 경우 발생한다.
 *
 * @property workflowKey lock 대상 워크플로우 식별 키
 * @property timeoutMillis 대기한 최대 시간 (밀리초)
 */
class WorkflowCacheLockTimeoutException(
    val workflowKey: String,
    val timeoutMillis: Long,
) : RuntimeException("WorkflowCache advisory lock timeout (${timeoutMillis}ms) for key '$workflowKey'")
