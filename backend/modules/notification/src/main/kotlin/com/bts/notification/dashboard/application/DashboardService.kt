// 대시보드 CRUD 애플리케이션 서비스 — 권한 판정·OCC·정규화·소프트삭제를 단일 트랜잭션으로 조율

package com.bts.notification.dashboard.application

import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.dashboard.repository.DashboardPage
import com.bts.notification.dashboard.repository.DashboardRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

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
 * @param repository dashboards jOOQ 저장소
 * @param clock 시각 주입 (기본값 UTC — 테스트에서 고정 Clock 으로 교체)
 */
@Service
class DashboardService(
    private val repository: DashboardRepository,
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

    // ── private helpers ───────────────────────────────────────────────────────

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
