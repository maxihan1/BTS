// Resolution Aggregate Root — 표준 5종(Fixed/Won't Fix/Duplicate/Cannot Reproduce/Done) + 커스텀 후속 FR
package com.bts.issue.resolution.domain

import java.time.Instant
import java.util.UUID

/**
 * Resolution(해결 방법) Aggregate Root.
 *
 * 하나의 이슈(Issue)에 정확히 하나의 Resolution 이 할당될 수 있다.
 * 표준 5종(Fixed / Won't Fix / Duplicate / Cannot Reproduce / Done)은 [companion object] 상수로 제공되며
 * isStandard = true 로 표시된다.
 *
 * **key 슬러그 규칙.**
 * key 는 소문자 알파벳과 숫자만 허용하며 공백·특수문자를 포함할 수 없다.
 * 예: `"fixed"`, `"wontfix"`, `"cannotreproduce"`.
 *
 * @property id DB PK(UUID). 신규 생성 전(DB 저장 전)에는 null 이다.
 * @property key URL-safe 소문자 슬러그 키. 공백·특수문자 불가.
 * @property name 표시 이름. 빈 문자열 불가.
 * @property description 선택적 설명. null 허용.
 * @property displayOrder 목록 표시 순서. 1부터 시작.
 * @property isStandard 표준 Resolution 여부. 표준 5종은 true, 커스텀은 false.
 * @property createdAt 생성 시각.
 * @property updatedAt 최종 수정 시각.
 * @property deletedAt 소프트 삭제 시각. null 이면 활성 상태.
 */
data class Resolution(
    val id: UUID?,
    val key: String,
    val name: String,
    val description: String?,
    val displayOrder: Int,
    val isStandard: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    companion object {
        /** 표준 Resolution 의 [displayOrder] 시작값. */
        private const val DISPLAY_ORDER_FIXED = 1
        private const val DISPLAY_ORDER_WONT_FIX = 2
        private const val DISPLAY_ORDER_DUPLICATE = 3
        private const val DISPLAY_ORDER_CANNOT_REPRODUCE = 4
        private const val DISPLAY_ORDER_DONE = 5

        /**
         * Resolution 인스턴스를 생성하는 factory 메서드.
         *
         * - [name] 이 빈 문자열이거나 공백만으로 구성된 경우 [IllegalArgumentException] 을 던진다.
         * - [id] 는 DB 저장 전이므로 null 로 초기화된다.
         * - [createdAt], [updatedAt] 은 현재 시각으로 초기화된다.
         * - [deletedAt] 은 null 로 초기화된다 (활성 상태).
         *
         * @param key URL-safe 소문자 슬러그 키. 소문자 알파벳·숫자만 허용.
         * @param name 표시 이름. 공백 트림 후 빈 문자열이면 예외.
         * @param description 선택적 설명. 기본값 null.
         * @param displayOrder 목록 표시 순서. 기본값 1.
         * @param isStandard 표준 Resolution 여부. 기본값 false.
         */
        fun create(
            key: String,
            name: String,
            description: String? = null,
            displayOrder: Int = DISPLAY_ORDER_FIXED,
            isStandard: Boolean = false,
        ): Resolution {
            require(name.isNotBlank()) { "Resolution name must not be blank" }
            val now = Instant.now()
            return Resolution(
                id = null,
                key = key,
                name = name,
                description = description,
                displayOrder = displayOrder,
                isStandard = isStandard,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        }

        /** 표준 Resolution — Fixed. 정상적으로 수정/해결된 이슈. */
        val FIXED: Resolution =
            create(
                key = "fixed",
                name = "Fixed",
                description = "이슈가 정상적으로 수정·해결되었다.",
                displayOrder = DISPLAY_ORDER_FIXED,
                isStandard = true,
            )

        /** 표준 Resolution — Won't Fix. 의도적으로 수정하지 않기로 결정한 이슈. */
        val WONT_FIX: Resolution =
            create(
                key = "wontfix",
                name = "Won't Fix",
                description = "의도적으로 수정하지 않기로 결정하였다.",
                displayOrder = DISPLAY_ORDER_WONT_FIX,
                isStandard = true,
            )

        /** 표준 Resolution — Duplicate. 동일한 이슈가 이미 존재하는 경우. */
        val DUPLICATE: Resolution =
            create(
                key = "duplicate",
                name = "Duplicate",
                description = "동일한 내용의 이슈가 이미 존재한다.",
                displayOrder = DISPLAY_ORDER_DUPLICATE,
                isStandard = true,
            )

        /** 표준 Resolution — Cannot Reproduce. 재현이 불가능한 이슈. */
        val CANNOT_REPRODUCE: Resolution =
            create(
                key = "cannotreproduce",
                name = "Cannot Reproduce",
                description = "보고된 현상을 재현할 수 없다.",
                displayOrder = DISPLAY_ORDER_CANNOT_REPRODUCE,
                isStandard = true,
            )

        /** 표준 Resolution — Done. 작업이 완료된 이슈. */
        val DONE: Resolution =
            create(
                key = "done",
                name = "Done",
                description = "작업이 완료되었다.",
                displayOrder = DISPLAY_ORDER_DONE,
                isStandard = true,
            )
    }
}
