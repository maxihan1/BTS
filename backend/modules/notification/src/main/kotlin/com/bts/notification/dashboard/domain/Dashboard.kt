// 대시보드 Aggregate Root — 그리드 레이아웃·공개 범위·공유 대상을 관리하는 핵심 도메인 모델

package com.bts.notification.dashboard.domain

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * 대시보드 Aggregate Root.
 *
 * 그리드 레이아웃(layout)·공개 범위(visibility)·지정 공유(sharedUserIds)를 캡슐화하며,
 * 팩토리 메서드(create)와 변경 메서드(applyPatch)를 통해서만 불변식을 보장한다.
 *
 * 모든 필드는 val 로 선언해 한 번 생성된 이후 외부에서 변경 불가.
 * version 은 OCC(낙관적 동시성 제어) 용도로 applyPatch 호출 시마다 +1 증가한다.
 *
 * @param id 대시보드 식별자 (UUID)
 * @param ownerId 소유자 사용자 ID (identity-access users.id, 논리 참조)
 * @param name 대시보드 표시 이름 (1~200자)
 * @param description 대시보드 설명 (선택)
 * @param visibility 공개 범위
 * @param layout 위젯 배치 JSONB (64KB 이하)
 * @param sharedUserIds TEAM 공유 대상 사용자 ID 집합 (owner 제외, visibility != TEAM 시 빈 집합)
 * @param createdAt 생성 시각
 * @param updatedAt 최종 수정 시각
 * @param deletedAt 소프트 삭제 시각 (null = 활성)
 * @param version OCC 버전
 */
data class Dashboard(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String?,
    val visibility: DashboardVisibility,
    val layout: String,
    val sharedUserIds: Set<UUID>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val version: Long,
) {
    companion object {
        /** 대시보드 이름 최대 길이 */
        const val MAX_NAME_LEN: Int = 200

        /** sharedUserIds 최대 개수 */
        const val MAX_SHARES: Int = 200

        /** layout JSONB 최대 바이트 크기 (64KB) */
        const val MAX_LAYOUT_BYTES: Int = 65536

        /** layout 배열 최대 항목 수 */
        const val MAX_GADGETS: Int = 50

        /**
         * layout JSON 파싱에 사용하는 ObjectMapper.
         * Thread-safe — companion object 에 싱글턴으로 보관한다.
         */
        private val JSON_MAPPER = ObjectMapper()

        /**
         * Dashboard 인스턴스를 생성하는 팩토리 메서드.
         *
         * 생성 시 모든 불변식을 검사한다.
         * - name 이 빈 문자열/공백이면 DashboardDomainException
         * - name 이 MAX_NAME_LEN 초과이면 DashboardDomainException
         * - layout 이 MAX_LAYOUT_BYTES 초과이면 DashboardDomainException
         * - sharedUserIds 가 MAX_SHARES 초과이면 DashboardDomainException
         * - visibility != TEAM 이면 sharedUserIds 를 빈 집합으로 정규화
         * - sharedUserIds 에서 ownerId 를 제거해 중복 접근 방지
         *
         * @param ownerId 소유자 ID
         * @param name 대시보드 이름
         * @param description 설명 (선택)
         * @param visibility 공개 범위
         * @param layout 위젯 배치 JSONB 문자열 (기본 빈 배열)
         * @param sharedUserIds TEAM 공유 대상 사용자 ID 집합
         * @param now 생성 시각 (호출자 Clock 에서 주입)
         * @return 불변식이 검증된 새 Dashboard 인스턴스
         * @throws DashboardDomainException 불변식 위반 시
         */
        @Suppress("LongParameterList")
        fun create(
            ownerId: UUID,
            name: String,
            description: String?,
            visibility: DashboardVisibility,
            layout: String = "[]",
            sharedUserIds: Set<UUID> = emptySet(),
            now: Instant,
        ): Dashboard {
            validateName(name)
            validateLayout(layout)
            val normalizedShares = normalizeShares(ownerId, visibility, sharedUserIds)
            return Dashboard(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                name = name,
                description = description,
                visibility = visibility,
                layout = layout,
                sharedUserIds = normalizedShares,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                version = 0L,
            )
        }

        private fun validateName(name: String) {
            if (name.isBlank()) {
                throw DashboardDomainException("대시보드 이름은 빈 문자열 또는 공백일 수 없습니다.")
            }
            if (name.length > MAX_NAME_LEN) {
                throw DashboardDomainException("대시보드 이름은 ${MAX_NAME_LEN}자 이하여야 합니다. 현재: ${name.length}자")
            }
        }

        /**
         * layout JSON 을 검증한다.
         *
         * 검증 순서.
         * 1. 크기 가드(64KB) — 1순위.
         * 2. 빈 문자열/공백 → 예외.
         * 3. JSON 파싱.
         * 4. JSON 배열이어야 함 (객체·스칼라 거부). 빈 배열 허용.
         * 5. 항목 수 ≤ MAX_GADGETS.
         * 6. 각 항목 위치·gadgetType·config 검증.
         */
        private fun validateLayout(layout: String) {
            if (layout.toByteArray().size > MAX_LAYOUT_BYTES) {
                throw DashboardDomainException("layout 은 ${MAX_LAYOUT_BYTES}바이트(64KB) 이하여야 합니다.")
            }
            if (layout.isBlank()) {
                throw DashboardDomainException("layout 은 유효한 JSON 이어야 합니다.")
            }
            val rootNode =
                try {
                    JSON_MAPPER.readTree(layout)
                } catch (e: JsonParseException) {
                    throw DashboardDomainException("layout 은 유효한 JSON 이어야 합니다.")
                }
            if (rootNode == null || !rootNode.isArray) {
                throw DashboardDomainException("layout 은 JSON 배열이어야 합니다.")
            }
            if (rootNode.size() > MAX_GADGETS) {
                throw DashboardDomainException(
                    "layout 항목은 최대 ${MAX_GADGETS}개까지 허용됩니다. 현재: ${rootNode.size()}개",
                )
            }
            val seenIds = mutableSetOf<String>()
            rootNode.forEachIndexed { index, item -> validateLayoutItem(index, item, seenIds) }
        }

        /**
         * layout 배열의 단일 항목을 검증한다.
         *
         * - i: 비어있지 않은 문자열, 배열 내 유일.
         * - x·y: 정수 ≥ 0.
         * - w·h: 정수 ≥ 1.
         * - gadgetType(선택): 알려진 키·enabled=true 이어야 하며 해당 타입의 config 를 위임 검증.
         *   없으면 legacy 타일로 간주하고 config 검증을 생략한다.
         */
        @Suppress("ThrowsCount")
        private fun validateLayoutItem(
            index: Int,
            item: JsonNode,
            seenIds: MutableSet<String>,
        ) {
            val iNode = item.get("i")
            val id =
                if (iNode == null || !iNode.isTextual || iNode.asText().isBlank()) {
                    throw DashboardDomainException("layout[$index] 항목에 유효한 'i' 값이 필요합니다.")
                } else {
                    iNode.asText()
                }
            if (!seenIds.add(id)) {
                throw DashboardDomainException("layout 항목 'i' 값 '$id' 가 중복됩니다.")
            }
            val x = item.get("x")
            if (x == null || !x.isIntegralNumber || x.intValue() < 0) {
                throw DashboardDomainException("layout[$id] 항목의 'x' 는 0 이상의 정수여야 합니다.")
            }
            val y = item.get("y")
            if (y == null || !y.isIntegralNumber || y.intValue() < 0) {
                throw DashboardDomainException("layout[$id] 항목의 'y' 는 0 이상의 정수여야 합니다.")
            }
            val w = item.get("w")
            if (w == null || !w.isIntegralNumber || w.intValue() < 1) {
                throw DashboardDomainException("layout[$id] 항목의 'w' 는 1 이상의 정수여야 합니다.")
            }
            val h = item.get("h")
            if (h == null || !h.isIntegralNumber || h.intValue() < 1) {
                throw DashboardDomainException("layout[$id] 항목의 'h' 는 1 이상의 정수여야 합니다.")
            }
            validateLayoutItemGadgetType(id, item)
        }

        private fun validateLayoutItemGadgetType(
            id: String,
            item: JsonNode,
        ) {
            val gadgetTypeNode = item.get("gadgetType") ?: return
            if (gadgetTypeNode.isNull) return
            if (!gadgetTypeNode.isTextual) {
                throw DashboardDomainException("layout[$id] 항목의 gadgetType 은 문자열이어야 합니다.")
            }
            val typeKey = gadgetTypeNode.asText()
            val gadgetType =
                GadgetType.fromKey(typeKey)
                    ?: throw DashboardDomainException("layout[$id] 항목의 gadgetType '$typeKey' 는 알 수 없는 타입입니다.")
            if (!gadgetType.enabled) {
                throw DashboardDomainException(
                    "layout[$id] 항목의 gadgetType '$typeKey' 는 현재 비활성화된 타입입니다.",
                )
            }
            gadgetType.validateConfig(item.get("config"))
        }

        private fun normalizeShares(
            ownerId: UUID,
            visibility: DashboardVisibility,
            sharedUserIds: Set<UUID>,
        ): Set<UUID> {
            if (visibility != DashboardVisibility.TEAM) {
                return emptySet()
            }
            val normalized = sharedUserIds - ownerId
            if (normalized.size > MAX_SHARES) {
                throw DashboardDomainException("공유 대상 사용자는 ${MAX_SHARES}명 이하여야 합니다. 현재: ${normalized.size}명")
            }
            return normalized
        }
    }

    /**
     * 부분 수정을 적용한 새 Dashboard 인스턴스를 반환한다.
     *
     * null 로 전달된 필드는 기존 값을 유지한다(3-state PATCH 패턴).
     * 호출 시 version 이 +1 증가하고 updatedAt 이 now 로 갱신된다.
     * 불변식(이름·layout·sharedUserIds 상한·visibility 정규화)은 변경된 값에 대해 동일하게 검증된다.
     *
     * description 은 스펙상 null=미변경으로 취급한다.
     * description 을 명시적으로 null 로 초기화하는 시나리오는 현재 스펙에 없으므로 별도 처리 없음.
     *
     * @param name 변경할 이름 (null = 기존 유지)
     * @param description 변경할 설명 (null = 기존 유지)
     * @param visibility 변경할 공개 범위 (null = 기존 유지)
     * @param layout 변경할 위젯 배치 JSON (null = 기존 유지)
     * @param sharedUserIds 변경할 공유 대상 집합 (null = 기존 유지, 빈 Set = 전체 제거)
     * @param now 변경 시각 (호출자 Clock 에서 주입)
     * @return version+1·updatedAt 갱신된 새 Dashboard 인스턴스
     * @throws DashboardDomainException 불변식 위반 시
     */
    @Suppress("LongParameterList")
    fun applyPatch(
        name: String?,
        description: String?,
        visibility: DashboardVisibility?,
        layout: String?,
        sharedUserIds: Set<UUID>?,
        now: Instant,
    ): Dashboard {
        val newName = name ?: this.name
        val newVisibility = visibility ?: this.visibility
        val newLayout = layout ?: this.layout
        val newSharedUserIds = sharedUserIds ?: this.sharedUserIds

        validateName(newName)
        validateLayout(newLayout)
        val normalizedShares = normalizeShares(ownerId, newVisibility, newSharedUserIds)

        return copy(
            name = newName,
            description = description ?: this.description,
            visibility = newVisibility,
            layout = newLayout,
            sharedUserIds = normalizedShares,
            updatedAt = now,
            version = version + 1,
        )
    }
}
