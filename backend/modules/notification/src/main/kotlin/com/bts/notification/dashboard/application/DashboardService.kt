// 대시보드 CRUD 애플리케이션 서비스 — 권한 판정·OCC·정규화·소프트삭제를 단일 트랜잭션으로 조율

package com.bts.notification.dashboard.application

import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardShareToken
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.dashboard.domain.ShareTokenMinter
import com.bts.notification.dashboard.repository.DashboardPage
import com.bts.notification.dashboard.repository.DashboardRepository
import com.bts.notification.dashboard.repository.DashboardShareTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 공유 토큰 발급 결과 — 원문(plaintext)은 발급 응답에서 단 한 번만 노출된다.
 *
 * plaintext 는 DB 에 저장되지 않으며(해시만 보관), 로그로도 출력하지 않는다.
 * token 은 저장된 공유 토큰 엔티티(해시·만료 등 메타)로, 응답 DTO 파생에 사용한다.
 *
 * @param plaintext 원문 공유 토큰 (응답 1회 노출용)
 * @param token 저장된 공유 토큰 엔티티
 */
data class IssuedShareToken(
    val plaintext: String,
    val token: DashboardShareToken,
)

/**
 * 익명(비로그인) 공개 공유 뷰에 노출하는 최소 대시보드 스냅샷.
 *
 * 소유자·공유 대상·OCC version 등 내부 정보는 절대 포함하지 않는다.
 * layout 은 [AnonymousLayoutSanitizer] 로 정화된(데이터 가젯 config 제거) 값이다.
 *
 * @param name 대시보드 이름
 * @param description 대시보드 설명 (없으면 null)
 * @param layout 익명 뷰용으로 정화된 layout JSON 문자열
 */
data class PublicDashboardSnapshot(
    val name: String,
    val description: String?,
    val layout: String,
)

/**
 * 대시보드 CRUD 를 담당하는 애플리케이션 서비스.
 *
 * 권한 판정 정책.
 * - 조회(get): PRIVATE=owner만, TEAM=owner+공유대상, ORG=인증사용자 전체. 접근 불가는 404(존재 숨김).
 * - 수정(update)/삭제(delete): owner 만 허용. 비소유자는 403.
 * - SYSTEM_ADMIN 예외 없음(1차).
 *
 * OCC(낙관적 잠금): repository.update rowcount = 0 이면 DashboardConflictException 발생.
 *
 * 정규화(C6): PATCH 는 도메인 applyPatch() -> 정규화 -> repository.update() 단일 경로.
 * repository 직행 금지 (memory: PATCH-merge-domain-bypass 교훈).
 *
 * 소프트 삭제(C1): findById(deleted_at IS NULL) -> owner 비교(403) -> softDelete 순서.
 * 단일 rowcount 로 404/403 판정 금지. 이미 삭제된 건 404(멱등).
 *
 * 공유 토큰(FR-DB-03): URL 공유용 불투명 토큰을 발급/조회/취소하고, 익명 공개 조회를 제공한다.
 * - 발급/목록/취소는 소유자 가드(findById -> requireOwner) 뒤에서만 수행한다.
 * - 익명 공개 조회(getPublicByToken)는 인증이 없으며, 원문을 해시해 조회하고 layout 을 정화한다.
 * - 미존재·만료·부모 삭제는 모두 404 로 수렴시켜 존재 여부를 숨긴다.
 *
 * @param repository dashboards jOOQ 저장소
 * @param shareTokenRepository dashboard_share_tokens jOOQ 저장소
 * @param shareTokenMinter 공유 토큰 발급기(원문 생성 + SHA-256 해싱)
 * @param layoutSanitizer 익명 공개 뷰용 layout 정화기(데이터 가젯 config 제거)
 * @param clock 시각 주입 (기본값 UTC — 테스트에서 고정 Clock 으로 교체)
 */
@Service
// TooManyFunctions — CRUD 5종 + 공유 토큰 4종은 대시보드 Aggregate 를 조율하는 응집된 유스케이스 집합이다.
@Suppress("TooManyFunctions")
class DashboardService(
    private val repository: DashboardRepository,
    private val shareTokenRepository: DashboardShareTokenRepository,
    private val shareTokenMinter: ShareTokenMinter = ShareTokenMinter(),
    private val layoutSanitizer: AnonymousLayoutSanitizer = AnonymousLayoutSanitizer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 목록 페이지 크기 상한 — 1,000 명 규모 ORG 대시보드 폭증 대비 */
    private companion object {
        const val MAX_LIMIT: Int = 100
    }

    /**
     * 새 대시보드를 생성한다.
     *
     * owner = actorId. 도메인 create() 에서 불변식·정규화를 검증한다.
     * insert 후 shares 를 동일 트랜잭션 내에서 교체한다.
     *
     * @param actorId 소유자 UUID
     * @param name 대시보드 이름 (1~200자)
     * @param description 설명 (선택)
     * @param visibility 공개 범위
     * @param layout 위젯 배치 JSONB 문자열 (기본 빈 배열)
     * @param sharedUserIds TEAM 공유 대상 사용자 ID 집합
     * @return 저장된 대시보드
     * @throws com.bts.notification.dashboard.domain.DashboardDomainException 불변식 위반
     */
    @Transactional
    @Suppress("LongParameterList")
    fun create(
        actorId: UUID,
        name: String,
        description: String?,
        visibility: DashboardVisibility,
        layout: String = "[]",
        sharedUserIds: Set<UUID> = emptySet(),
    ): Dashboard {
        val dashboard =
            Dashboard.create(
                ownerId = actorId,
                name = name,
                description = description,
                visibility = visibility,
                layout = layout,
                sharedUserIds = sharedUserIds,
                now = clock.instant(),
            )

        val saved = repository.insert(dashboard)
        repository.replaceShares(saved.id, saved.sharedUserIds)

        log.info("대시보드 생성 — id={}, ownerId={}, visibility={}", saved.id, saved.ownerId, saved.visibility)
        return saved.copy(sharedUserIds = saved.sharedUserIds)
    }

    /**
     * id 로 대시보드 단건을 조회한다.
     *
     * 존재하지 않거나 접근 권한이 없으면 DashboardNotFoundException 을 던진다 (존재 숨김 정책).
     *
     * @param actorId 조회 주체 UUID
     * @param id 대시보드 식별자
     * @return 대시보드 도메인 객체 (sharedUserIds 포함)
     * @throws DashboardNotFoundException 없거나 접근 불가
     */
    @Transactional(readOnly = true)
    fun get(
        actorId: UUID,
        id: UUID,
    ): Dashboard {
        val dashboard = repository.findById(id) ?: throw DashboardNotFoundException(id)
        val shares = repository.findSharesByDashboardId(id).toSet()
        checkReadAccess(actorId, dashboard, shares) { throw DashboardNotFoundException(id) }
        return dashboard.copy(sharedUserIds = shares)
    }

    /**
     * actorId 가 접근 가능한 대시보드 목록을 페이지네이션으로 반환한다.
     *
     * 접근 가능 범위 = 소유 UNION 공유받은(TEAM) UNION 전체공개(ORG).
     * limit 상한 = MAX_LIMIT(100). 초과 시 자동 클램프.
     *
     * @param actorId 조회 주체 UUID
     * @param limit 페이지 크기 (상한 100)
     * @param offset 건너뛸 항목 수
     * @return 페이지네이션 결과
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        limit: Int,
        offset: Int,
    ): DashboardPage {
        val clampedLimit = limit.coerceIn(1, MAX_LIMIT)
        val clampedOffset = offset.coerceAtLeast(0)
        return repository.findPage(actorId, clampedLimit, clampedOffset)
    }

    /**
     * 대시보드를 부분 수정한다.
     *
     * C6: 반드시 도메인 applyPatch() -> 정규화 -> repository.update() 경로만 사용.
     * repository 직행 금지.
     *
     * @param actorId 요청 주체 UUID (owner 여야 함)
     * @param id 수정 대상 대시보드 식별자
     * @param name 변경할 이름 (null = 유지)
     * @param description 변경할 설명 (null = 유지)
     * @param visibility 변경할 공개 범위 (null = 유지)
     * @param layout 변경할 위젯 배치 JSON (null = 유지)
     * @param sharedUserIds 변경할 공유 대상 집합 (null = 유지, 빈 Set = 전체 제거)
     * @param version 요청자가 알고 있는 현재 version (OCC)
     * @return 수정된 대시보드
     * @throws DashboardNotFoundException 조회 불가
     * @throws DashboardForbiddenException 비소유자 수정 시도
     * @throws DashboardConflictException version 불일치
     */
    @Transactional
    // LongParameterList — 3-state PATCH 의 7개 필드는 각각 독립 의미로 커맨드 객체 도입 시 오히려 과설계.
    // ThrowsCount — 404(미존재)/403(비소유자)/409(OCC 충돌) 세 분기는 본질적으로 독립된 실패 경로.
    @Suppress("LongParameterList", "ThrowsCount")
    fun update(
        actorId: UUID,
        id: UUID,
        name: String?,
        description: String?,
        visibility: DashboardVisibility?,
        layout: String?,
        sharedUserIds: Set<UUID>?,
        version: Long,
    ): Dashboard {
        val dashboard = repository.findById(id) ?: throw DashboardNotFoundException(id)
        requireOwner(actorId, dashboard)

        // OCC 사전 검증 — 클라이언트 version 과 DB version 이 다르면 즉시 409
        // repository.update() 의 WHERE version=? 와 이중 방어
        if (dashboard.version != version) {
            throw DashboardConflictException(id)
        }

        // [C6] 도메인 applyPatch() -> 정규화 -> repository.update() 단일 경로
        // applyPatch 는 내부에서 version+1 을 반환한다
        val patched =
            dashboard.applyPatch(
                name = name,
                description = description,
                visibility = visibility,
                layout = layout,
                sharedUserIds = sharedUserIds,
                now = clock.instant(),
            )

        // repository.update 는 WHERE version = patched.version - 1 조건으로 갱신
        val affected = repository.update(patched)
        if (affected == 0) {
            throw DashboardConflictException(id)
        }

        repository.replaceShares(id, patched.sharedUserIds)

        log.info(
            "대시보드 수정 — id={}, actorId={}, version={}->{}",
            id,
            actorId,
            version,
            patched.version,
        )
        return patched
    }

    /**
     * 대시보드를 소프트 삭제한다.
     *
     * C1: findById(deleted_at IS NULL) -> owner 비교(403) -> softDelete 순서.
     * 단일 rowcount 로 404/403 판정 금지.
     * 이미 삭제된(deleted_at NOT NULL) 대시보드는 findById 가 null 반환 -> 404(멱등).
     *
     * @param actorId 요청 주체 UUID (owner 여야 함)
     * @param id 삭제 대상 대시보드 식별자
     * @throws DashboardNotFoundException 조회 불가 또는 이미 삭제됨
     * @throws DashboardForbiddenException 비소유자 삭제 시도
     */
    @Transactional
    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        val dashboard = repository.findById(id) ?: throw DashboardNotFoundException(id)
        requireOwner(actorId, dashboard)
        repository.softDelete(id)
        log.info("대시보드 소프트 삭제 — id={}, actorId={}", id, actorId)
    }

    // ── 공유 토큰 (FR-DB-03) ────────────────────────────────────────────────────

    /**
     * 대시보드 공유 토큰을 발급한다.
     *
     * 소유자 가드(findById -> requireOwner) 뒤에서만 발급한다. 발급 전 대시보드당 활성 토큰 수가
     * 상한(MAX_SHARE_TOKENS) 이상이면 ShareTokenLimitExceededException 으로 거부한다.
     *
     * 원문(plaintext)은 반환 결과에만 담기고 DB 에는 해시만 저장된다. 로그에 원문·해시를 출력하지 않는다.
     *
     * 참고: count 조회와 insert 사이의 TOCTOU 경합은 MVP 에서 허용한다 — 상한은 남용 방지가 목적이며
     * 정확한 개수 강제가 아니다(동시 발급으로 상한을 근소하게 넘길 수 있음).
     *
     * @param actorId 요청 주체 UUID (owner 여야 함)
     * @param dashboardId 대상 대시보드 식별자
     * @param expiresAt 만료 시각 (null = 무기한)
     * @return 원문 + 저장된 공유 토큰 엔티티
     * @throws DashboardNotFoundException 조회 불가 또는 이미 삭제됨
     * @throws DashboardForbiddenException 비소유자
     * @throws ShareTokenLimitExceededException 활성 토큰 수 상한 초과
     */
    @Transactional
    fun issueShareToken(
        actorId: UUID,
        dashboardId: UUID,
        expiresAt: Instant?,
    ): IssuedShareToken {
        requireOwnedDashboard(actorId, dashboardId)
        if (shareTokenRepository.countByDashboard(dashboardId) >= DashboardShareToken.MAX_SHARE_TOKENS) {
            throw ShareTokenLimitExceededException()
        }
        val minted =
            shareTokenMinter.mint(
                dashboardId = dashboardId,
                createdBy = actorId,
                expiresAt = expiresAt,
                now = clock.instant(),
            )
        val saved = shareTokenRepository.insert(minted.token)
        log.info("대시보드 공유 토큰 발급 — dashboardId={}, actorId={}, tokenId={}", dashboardId, actorId, saved.id)
        return IssuedShareToken(plaintext = minted.plaintext, token = saved)
    }

    /**
     * 대시보드에 발급된 공유 토큰 목록을 조회한다.
     *
     * 소유자 가드 뒤에서만 조회한다. 엔티티에는 tokenHash 가 포함되지만, 해시·원문의 응답 노출 여부는
     * DTO 계층(Task 6)의 책임이다 — 서비스는 도메인 엔티티를 그대로 반환한다.
     *
     * @param actorId 요청 주체 UUID (owner 여야 함)
     * @param dashboardId 대상 대시보드 식별자
     * @return 발급된 공유 토큰 목록 (없으면 빈 목록)
     * @throws DashboardNotFoundException 조회 불가 또는 이미 삭제됨
     * @throws DashboardForbiddenException 비소유자
     */
    @Transactional(readOnly = true)
    fun listShareTokens(
        actorId: UUID,
        dashboardId: UUID,
    ): List<DashboardShareToken> {
        requireOwnedDashboard(actorId, dashboardId)
        return shareTokenRepository.listByDashboard(dashboardId)
    }

    /**
     * 대시보드 공유 토큰을 취소(하드 삭제)한다.
     *
     * 소유자 가드 뒤에서 dashboardId 스코프로 삭제한다. deleteById rowcount = 0(미존재 또는 다른
     * 대시보드의 shareId)이면 ShareTokenNotFoundException(404)으로 수렴한다.
     *
     * @param actorId 요청 주체 UUID (owner 여야 함)
     * @param dashboardId 대상 대시보드 식별자
     * @param shareId 취소할 공유 토큰 식별자
     * @throws DashboardNotFoundException 대시보드 조회 불가 또는 이미 삭제됨
     * @throws DashboardForbiddenException 비소유자
     * @throws ShareTokenNotFoundException 공유 토큰 미존재 또는 스코프 불일치
     */
    @Transactional
    fun revokeShareToken(
        actorId: UUID,
        dashboardId: UUID,
        shareId: UUID,
    ) {
        requireOwnedDashboard(actorId, dashboardId)
        val affected = shareTokenRepository.deleteById(shareId, dashboardId)
        if (affected == 0) {
            throw ShareTokenNotFoundException()
        }
        log.info("대시보드 공유 토큰 취소 — dashboardId={}, actorId={}, tokenId={}", dashboardId, actorId, shareId)
    }

    /**
     * 원문 공유 토큰으로 익명(비로그인) 공개 대시보드 스냅샷을 조회한다.
     *
     * 인증이 없다. 원문을 SHA-256 해시로 변환해(원문으로 직접 조회하지 않음) 활성 토큰을 조회하고,
     * 만료(expiresAt <= now)·미존재·부모 대시보드 삭제는 모두 PublicDashboardNotFoundException(404)으로
     * 수렴시켜 토큰 존재·만료 여부를 노출하지 않는다.
     *
     * 반환 layout 은 [AnonymousLayoutSanitizer] 로 정화되어 데이터 가젯 config 가 제거된다.
     * ownerId·sharedUserIds·version 등 내부 정보는 스냅샷에 포함하지 않는다.
     *
     * @param plaintextToken 원문 공유 토큰
     * @return 정화된 공개 대시보드 스냅샷
     * @throws PublicDashboardNotFoundException 미존재·만료·부모 삭제 (모두 404 수렴)
     */
    @Transactional(readOnly = true)
    // ThrowsCount — 미존재/만료/부모삭제 3분기는 동일 404 로 수렴하나 각각 독립된 판정 경로다.
    @Suppress("ThrowsCount")
    fun getPublicByToken(plaintextToken: String): PublicDashboardSnapshot {
        val tokenHash = shareTokenMinter.hash(plaintextToken)
        val token =
            shareTokenRepository.findActiveByTokenHash(tokenHash)
                ?: throw PublicDashboardNotFoundException()
        if (token.isExpired(clock.instant())) {
            throw PublicDashboardNotFoundException()
        }
        val dashboard = repository.findById(token.dashboardId) ?: throw PublicDashboardNotFoundException()
        return PublicDashboardSnapshot(
            name = dashboard.name,
            description = dashboard.description,
            layout = layoutSanitizer.sanitize(dashboard.layout),
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 대시보드를 조회하고 요청 주체가 소유자인지 검증한 뒤 반환한다.
     *
     * 공유 토큰 유스케이스의 공통 가드 — findById(deleted_at IS NULL, 없으면 404) 후
     * requireOwner(비소유자 403) 순서를 강제한다.
     *
     * @param actorId 요청 주체 UUID
     * @param dashboardId 대상 대시보드 식별자
     * @return 소유자 검증을 통과한 대시보드
     * @throws DashboardNotFoundException 조회 불가 또는 이미 삭제됨
     * @throws DashboardForbiddenException 비소유자
     */
    private fun requireOwnedDashboard(
        actorId: UUID,
        dashboardId: UUID,
    ): Dashboard {
        val dashboard = repository.findById(dashboardId) ?: throw DashboardNotFoundException(dashboardId)
        requireOwner(actorId, dashboard)
        return dashboard
    }

    /**
     * 읽기 접근 권한을 판정한다.
     *
     * - PRIVATE: owner 만
     * - TEAM: owner 또는 공유 대상
     * - ORG: 모든 인증 사용자
     *
     * 접근 불가 시 onDenied 를 실행한다 (caller 가 예외 타입을 결정).
     *
     * @param actorId 조회 주체
     * @param dashboard 대시보드 도메인 객체
     * @param shares 공유 대상 사용자 ID 집합
     * @param onDenied 접근 불가 시 실행할 람다 (예: throw DashboardNotFoundException)
     */
    private fun checkReadAccess(
        actorId: UUID,
        dashboard: Dashboard,
        shares: Set<UUID>,
        onDenied: () -> Nothing,
    ) {
        val allowed =
            when (dashboard.visibility) {
                DashboardVisibility.PRIVATE -> actorId == dashboard.ownerId
                DashboardVisibility.TEAM -> actorId == dashboard.ownerId || actorId in shares
                DashboardVisibility.ORG -> true
            }
        if (!allowed) onDenied()
    }

    /**
     * 요청 주체가 소유자인지 검증한다.
     *
     * 소유자가 아닌 경우 DashboardForbiddenException 을 던진다.
     * message 에 actorId/ownerId 를 포함하지 않는다 (내부 정보 누출 방지).
     *
     * @param actorId 요청 주체 UUID
     * @param dashboard 대상 대시보드
     * @throws DashboardForbiddenException 비소유자
     */
    private fun requireOwner(
        actorId: UUID,
        dashboard: Dashboard,
    ) {
        if (actorId != dashboard.ownerId) {
            throw DashboardForbiddenException()
        }
    }
}
