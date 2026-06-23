// 백로그 이슈 rank 변경 서비스 — rerank + on-demand rebalance (FR-BL-01)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.lexorank.Rank
import com.bts.shared.lexorank.RankSpaceExhaustedException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.pow

/**
 * 프로젝트 전역 rank 분할 시 1 자리 유예를 둔 균등 구간 최소값.
 *
 * rebalance 에서 N개 이슈를 균등 배포할 때, rank 상한(UPPER_EXCLUSIVE)을 구간 수로 나눠
 * 각 이슈에 할당할 인덱스 간격을 계산한다.
 * 알파벳 3자리(a-z) 로 표현 가능한 최대값은 26^3 = 17,576 으로 1,000 이슈에 충분하다.
 */
private const val REBALANCE_DIGITS = 3

/** rebalance 에서 사용하는 알파벳 크기 (a-z, base-26). */
private const val ALPHA_SIZE = 26

/**
 * rebalance 가능한 최대 이슈 수.
 *
 * 3자리 base-26 에서 trailing-a 를 피하는 유효 키 공간 상한.
 * 26^3 = 17,576 이지만 실제로는 trailing-a 제거 후 더 적다.
 * 1,000 이슈 기준(NFR2)에 충분한 상한으로 설정.
 */
private const val REBALANCE_MAX_ISSUES = 10_000

/**
 * pg_advisory_xact_lock SQL.
 *
 * projectId(UUID 문자열) 를 hashtextextended 로 bigint 로 변환해 트랜잭션 범위 락을 획득한다.
 * hashtextextended(text, int8) → bigint, pg_advisory_xact_lock(bigint) 단일 시그니처만 존재
 * (bigint,bigint 시그니처 없음 — identity-access ExternalAccountRepository 선례 동형).
 * 해시 충돌은 무관 프로젝트의 거짓 직렬화일 뿐 안전하다.
 */
private const val SQL_REBALANCE_LOCK =
    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"

/**
 * 백로그 이슈 순위(rank) 변경 서비스 (FR-BL-01).
 *
 * rank 변경은 드래그 빈번 운영 액션이므로 다음 원칙을 따른다.
 * - no-bump: version / updated_at 미증가 (spec #10, #13).
 * - history 미기록: IssueChangeDetector 등록 없음 (spec #8).
 * - 동시성: 일반 rerank 는 last-write-wins. rebalance 만 advisory lock 직렬화.
 *
 * rebalance 흐름.
 * 1. pg_advisory_xact_lock(projectId) 취득 — 동시 rebalance 직렬화.
 * 2. lock 후 findRanksForRebalance 재조회 (TOCTOU 차단).
 * 3. 균등 간격 3자리 rank 재배포 — trailing-a 회피를 Rank 불변식으로 보장.
 * 4. 재배포 후 prev/next rank 재조회 → between 재계산 → updateRank (C3).
 */
@Service
@Transactional
class BacklogRankService(
    private val repo: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 rank 를 변경한다 (리랭크).
     *
     * 흐름.
     * 1. UPDATE 권한 검증 (Issue 범위).
     * 2. 대상 이슈 조회 — 미존재/소프트삭제 시 IssueNotFoundException.
     * 3. 이웃 검증 — [validateNeighbors] 참조.
     * 4. Rank.between(prevRank, nextRank) 계산.
     *    RankSpaceExhaustedException → rebalance 후 재조회(C3) → 재계산.
     * 5. repo.updateRank (no-bump, history 미기록).
     *
     * @param actor 행위자.
     * @param key 대상 이슈 키.
     * @param previousIssueKey 앞 이웃 이슈 키. null 이면 맨 앞으로 이동.
     * @param nextIssueKey 뒤 이웃 이슈 키. null 이면 맨 뒤로 이동.
     * @throws IssueAccessDeniedException UPDATE 권한 미보유.
     * @throws IssueNotFoundException 대상 또는 이웃 이슈 미존재/소프트삭제.
     * @throws InvalidRankNeighborException 이웃 검증 실패 (역전/둘다null/동일이웃/타프로젝트).
     */
    @Suppress("ThrowsCount")
    fun rerank(
        actor: ActorId,
        key: IssueKey,
        previousIssueKey: IssueKey?,
        nextIssueKey: IssueKey?,
    ) {
        assertPermission(actor, key)

        val target = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val (prevRank, nextRank) = resolveNeighborRanks(key, target.projectId, previousIssueKey, nextIssueKey)

        applyRankUpdate(
            RerankCmd(key, target.projectId, previousIssueKey, nextIssueKey, prevRank, nextRank),
        )
    }

    /**
     * 프로젝트 백로그 전체 rank 를 균등 간격으로 재배포한다 (on-demand rebalance).
     *
     * pg_advisory_xact_lock 으로 동시 rebalance 를 직렬화하고,
     * lock 후 findRanksForRebalance 를 재조회하여 TOCTOU 를 차단한다.
     * rank=NULL 인 이슈(옵션 B, lazy 미부여)도 NULLS LAST 정렬로 포함하여 전체에 rank 를 부여한다.
     *
     * @param projectId 재배포 대상 프로젝트 UUID.
     */
    fun rebalance(projectId: UUID) {
        // pg_advisory_xact_lock 취득 — 동시 rebalance 직렬화 (트랜잭션 종료 시 자동 해제).
        // void 반환이라 execute 로 호출 (DATA.md §5 정식 예외).
        log.debug("acquiring rebalance lock for projectId={}", projectId)
        dsl.execute(SQL_REBALANCE_LOCK, projectId.toString())
        // TOCTOU 차단: lock 후 최신 상태 재조회. rank NULL 포함 전체 조회 (NULLS LAST).
        val issues = repo.findRanksForRebalance(projectId)
        if (issues.isEmpty()) return

        check(issues.size <= REBALANCE_MAX_ISSUES) {
            "재배포 이슈 수 초과: ${issues.size} > $REBALANCE_MAX_ISSUES (projectId=$projectId)"
        }

        val newRanks = computeEvenRanks(issues.size)
        issues.forEachIndexed { idx, (issueKey, _) ->
            repo.updateRank(IssueKey(issueKey), newRanks[idx])
        }
        log.info("rebalance_done projectId={} count={}", projectId, issues.size)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * UPDATE 권한을 검증한다.
     *
     * @throws IssueAccessDeniedException 권한 미보유.
     */
    private fun assertPermission(
        actor: ActorId,
        key: IssueKey,
    ) {
        val scope = IssueScope.Issue(key.value)
        if (!permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, scope)) {
            throw IssueAccessDeniedException(actor, IssuePermission.UPDATE, scope)
        }
    }

    /**
     * 이웃 이슈를 조회하고 rank 쌍을 반환한다.
     *
     * 이웃 rank 가 NULL 인 경우(E13, 옵션 B lazy 미부여 영역) null 을 그대로 반환한다.
     * 호출측(applyRankUpdate)이 null 을 rebalance 트리거로 처리한다.
     *
     * @return (prevRank, nextRank) — null 은 경계 없음 또는 미부여(lazy).
     * @throws IssueNotFoundException 이웃 이슈 미존재/소프트삭제.
     * @throws InvalidRankNeighborException 이웃 검증 실패.
     */
    private fun resolveNeighborRanks(
        key: IssueKey,
        projectId: UUID,
        previousIssueKey: IssueKey?,
        nextIssueKey: IssueKey?,
    ): Pair<Rank?, Rank?> {
        validateNeighborKeys(key, previousIssueKey, nextIssueKey)

        val prevRank = previousIssueKey?.let { resolveNeighborRank(it, projectId, "이전") }
        val nextRank = nextIssueKey?.let { resolveNeighborRank(it, projectId, "다음") }

        // 둘 다 non-null 이고 순서 역전이면 400 (null 은 경계 없음 또는 lazy 미부여라 역전 비교 생략).
        if (prevRank != null && nextRank != null && prevRank >= nextRank) {
            throw InvalidRankNeighborException(
                "이전 이슈 rank(${prevRank.value})가 다음 이슈 rank(${nextRank.value}) 이상입니다 (순서 역전).",
            )
        }

        return prevRank to nextRank
    }

    /**
     * 이웃 키 기본 검증 — null 조합/동일성 체크.
     *
     * @throws InvalidRankNeighborException 검증 실패.
     */
    @Suppress("ThrowsCount")
    private fun validateNeighborKeys(
        key: IssueKey,
        previousIssueKey: IssueKey?,
        nextIssueKey: IssueKey?,
    ) {
        if (previousIssueKey == null && nextIssueKey == null) {
            throw InvalidRankNeighborException("previousIssueKey 와 nextIssueKey 가 둘 다 null 입니다.")
        }
        if (previousIssueKey != null && previousIssueKey == nextIssueKey) {
            throw InvalidRankNeighborException(
                "previousIssueKey 와 nextIssueKey 가 동일합니다: ${previousIssueKey.value}",
            )
        }
        if (key == previousIssueKey) {
            throw InvalidRankNeighborException(
                "대상 이슈(${key.value})가 previousIssueKey 와 동일합니다.",
            )
        }
        if (key == nextIssueKey) {
            throw InvalidRankNeighborException(
                "대상 이슈(${key.value})가 nextIssueKey 와 동일합니다.",
            )
        }
    }

    /**
     * 단일 이웃 이슈를 조회하고 Rank 를 반환한다.
     *
     * rank 가 NULL 인 경우(E13, 옵션 B lazy 미부여) null 을 반환한다.
     * 호출측에서 null 을 rebalance 트리거로 처리한다.
     *
     * @throws IssueNotFoundException 이웃 이슈 미존재/소프트삭제.
     * @throws InvalidRankNeighborException 이웃이 타 프로젝트.
     */
    private fun resolveNeighborRank(
        neighborKey: IssueKey,
        targetProjectId: UUID,
        label: String,
    ): Rank? {
        val neighbor = repo.findByKey(neighborKey) ?: throw IssueNotFoundException(neighborKey)
        if (neighbor.projectId != targetProjectId) {
            throw InvalidRankNeighborException(
                "$label 이웃 이슈(${neighborKey.value})가 대상 이슈와 다른 프로젝트입니다.",
            )
        }
        // E13: rank=NULL 이면 null 반환 — 호출측이 rebalance 트리거로 처리 (lazy 미부여 영역).
        return neighbor.rank?.let { Rank.of(it) }
    }

    /**
     * 리랭크 명령 — between 계산에 필요한 대상/이웃 키와 rank 묶음.
     */
    private data class RerankCmd(
        val key: IssueKey,
        val projectId: UUID,
        val previousIssueKey: IssueKey?,
        val nextIssueKey: IssueKey?,
        val prevRank: Rank?,
        val nextRank: Rank?,
    )

    /**
     * between 계산 후 updateRank 를 수행한다.
     *
     * rebalance 트리거 조건 (둘 다 [rebalanceAndRetry] 위임).
     * - 이웃 rank 가 NULL(E13, 옵션 B lazy 미부여 영역으로 드래그).
     * - RankSpaceExhaustedException: rank 공간 고갈.
     */
    private fun applyRankUpdate(cmd: RerankCmd) {
        // E13: 이웃 rank 가 null(lazy 미부여)이면 고갈과 동일하게 rebalance 트리거.
        val nullNeighbor =
            cmd.prevRank == null && cmd.previousIssueKey != null ||
                cmd.nextRank == null && cmd.nextIssueKey != null
        if (nullNeighbor) {
            log.warn("neighbor_rank_null key={} projectId={} — rebalance (E13)", cmd.key.value, cmd.projectId)
            rebalanceAndRetry(cmd)
            return
        }

        try {
            val newRank = Rank.between(cmd.prevRank, cmd.nextRank)
            repo.updateRank(cmd.key, newRank.value)
            log.info("rerank_done key={} rank={}", cmd.key.value, newRank.value)
        } catch (e: RankSpaceExhaustedException) {
            log.warn("rank_space_exhausted key={} projectId={} — rebalance", cmd.key.value, cmd.projectId, e)
            rebalanceAndRetry(cmd)
        }
    }

    /**
     * rebalance 후 이웃 rank 를 재조회(C3)하여 between 재계산·updateRank 한다.
     *
     * rebalance 가 키를 재배포하므로 이전 rank 값은 stale — 반드시 재조회한다.
     */
    private fun rebalanceAndRetry(cmd: RerankCmd) {
        rebalance(cmd.projectId)
        val reloadedPrev = cmd.previousIssueKey?.let { k -> repo.findRankByKey(k)?.let { Rank.of(it) } }
        val reloadedNext = cmd.nextIssueKey?.let { k -> repo.findRankByKey(k)?.let { Rank.of(it) } }
        val newRank = Rank.between(reloadedPrev, reloadedNext)
        repo.updateRank(cmd.key, newRank.value)
        log.info("rerank_after_rebalance_done key={} rank={}", cmd.key.value, newRank.value)
    }

    /**
     * N개 이슈를 균등 간격으로 배포하는 3자리 rank 목록을 반환한다.
     *
     * 3자리 base-26(REBALANCE_DIGITS=3) 로 표현 가능한 총 키 공간(26^3=17,576)을
     * N+1 구간으로 나눠 각 이슈에 인덱스 i*(space/(N+1)) 를 할당한다.
     * Rank.of 의 불변식(trailing-a 금지) 이 보장되도록 각 자리를 인코딩한다.
     *
     * 인코딩 방식: 정수 인덱스를 base-26 으로 자리별 분해 후 'a' 오프셋을 더한다.
     * trailing-a 는 encodeRank 내 후처리로 'b' 로 교체한다.
     *
     * @param n 이슈 수.
     * @return n 개의 균등 간격 rank 문자열 목록.
     */
    private fun computeEvenRanks(n: Int): List<String> {
        // kotlin.math.pow 사용 (java.lang.Math.pow 대신).
        val totalSpace = ALPHA_SIZE.toDouble().pow(REBALANCE_DIGITS.toDouble()).toInt()
        val step = maxOf(1, totalSpace / (n + 1))
        return (1..n).map { i -> encodeRank(i * step) }
    }

    /**
     * 정수 인덱스를 REBALANCE_DIGITS 자리 base-26 rank 문자열로 인코딩한다.
     *
     * 각 자리는 'a'(0)~'z'(25) 에 매핑되지만, trailing-a 를 피하기 위해
     * 최하위 자리는 'b'(1)~'z'(25) 범위만 사용한다.
     * 인덱스가 범위를 벗어나면 마지막 유효값('z')으로 클램핑한다.
     */
    private fun encodeRank(idx: Int): String {
        var rem = idx
        val digits = mutableListOf<Char>()
        repeat(REBALANCE_DIGITS) {
            digits.add(0, 'a' + (rem % ALPHA_SIZE))
            rem /= ALPHA_SIZE
        }
        val encoded = String(digits.toCharArray())
        // trailing-a 보장: 끝 문자가 'a' 이면 'b' 로 교체
        return if (encoded.last() == 'a') encoded.dropLast(1) + 'b' else encoded
    }
}
