// 프로젝트 내 하위 영역 분류(컴포넌트) Aggregate Root
package com.bts.issue.component.domain

import java.time.Instant
import java.util.UUID

/** 컴포넌트 이름의 최대 허용 글자 수. */
internal const val MAX_NAME = 255

/**
 * 컴포넌트 Aggregate Root.
 *
 * 프로젝트 내 이슈를 하위 영역으로 분류하는 단위다.
 * 직접 생성자 대신 [Component.create] factory 를 통해 invariant 를 검증하고 인스턴스를 얻는다.
 *
 * invariant.
 * - [name] 은 trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
 * - [deletedAt] 은 생성 시 null. 소프트 삭제 시 타임스탬프가 채워진다.
 * - 이미 삭제된 컴포넌트에 [softDelete] 재호출 시 [IllegalStateException] 발생.
 *
 * **리드 실재 검증 위치.**
 * [leadUserId] 의 실재 여부는 도메인이 검증하지 않는다.
 * 호출자(ApplicationService, Task 6)가 UserLookupPort 로 검증한 후 전달해야 한다.
 *
 * @property id DB PK. 신규 생성 전(DB 저장 전)에는 null 이다.
 * @property projectId 이 컴포넌트가 속한 프로젝트의 UUID.
 * @property name 컴포넌트 이름. trim 후 1~[MAX_NAME]자.
 * @property description 선택적 설명. null 허용.
 * @property leadUserId 리드 사용자 식별자. null 이면 미지정 상태.
 * @property deletedAt 소프트 삭제 타임스탬프. null 이면 활성 상태.
 */
data class Component(
    val id: UUID?,
    val projectId: UUID,
    val name: String,
    val description: String?,
    val leadUserId: UUID?,
    val deletedAt: Instant?,
) {
    companion object {
        /**
         * 새 컴포넌트를 생성한다.
         *
         * [name] invariant 위반 시 [IllegalArgumentException] 을 던진다.
         *
         * @param projectId 이 컴포넌트가 속한 프로젝트 UUID.
         * @param name 컴포넌트 이름. trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
         * @param description 선택적 설명. 기본값 null.
         * @param leadUserId 리드 사용자 UUID. 기본값 null(미지정). 실재 검증은 ApplicationService 책임.
         * @return 생성된 [Component] 인스턴스.
         */
        fun create(
            projectId: UUID,
            name: String,
            description: String? = null,
            leadUserId: UUID? = null,
        ): Component {
            val trimmedName = validateAndTrimName(name)
            return Component(
                id = null,
                projectId = projectId,
                name = trimmedName,
                description = description,
                leadUserId = leadUserId,
                deletedAt = null,
            )
        }
    }

    /**
     * 이 컴포넌트의 이름을 변경한다.
     *
     * [newName] invariant 위반 시 [IllegalArgumentException] 을 던진다.
     *
     * @param newName 새 이름. trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
     * @return [name] 이 [newName] 으로 설정된 새 [Component] 인스턴스.
     */
    fun rename(newName: String): Component {
        val trimmedName = validateAndTrimName(newName)
        return copy(name = trimmedName)
    }

    /**
     * 이 컴포넌트의 리드를 변경한다.
     *
     * null 을 전달하면 리드를 해제한다.
     * 리드 실재 여부는 도메인이 검증하지 않는다.
     *
     * @param userId 새 리드 UUID. null 이면 미지정 상태로 전환.
     * @return [leadUserId] 가 [userId] 로 설정된 새 [Component] 인스턴스.
     */
    fun changeLead(userId: UUID?): Component = copy(leadUserId = userId)

    /**
     * 이 컴포넌트의 설명을 변경한다.
     *
     * null 을 전달하면 설명을 클리어한다.
     *
     * @param newDescription 새 설명. null 허용(클리어).
     * @return [description] 이 [newDescription] 으로 설정된 새 [Component] 인스턴스.
     */
    fun changeDescription(newDescription: String?): Component = copy(description = newDescription)

    /**
     * 이 컴포넌트를 소프트 삭제한다.
     *
     * 이미 삭제된 컴포넌트에 호출하면 [IllegalStateException] 을 던진다.
     *
     * @return [deletedAt] 이 현재 시각으로 설정된 새 [Component] 인스턴스.
     * @throws IllegalStateException 이미 삭제된 컴포넌트 재삭제 시.
     */
    fun softDelete(): Component {
        check(deletedAt == null) { "Component is already deleted (id=$id)" }
        return copy(deletedAt = Instant.now())
    }
}

/**
 * [Component.name] 도메인 불변식 검증 및 trim 정규화.
 *
 * - trim 후 빈 문자열이면 [IllegalArgumentException] 을 던진다.
 * - trim 후 [MAX_NAME]자를 초과하면 [IllegalArgumentException] 을 던진다.
 *
 * @param name 정규화 전 이름.
 * @return trim 된 이름.
 * @throws IllegalArgumentException invariant 위반 시.
 */
private fun validateAndTrimName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Component name must not be blank" }
    require(trimmed.length <= MAX_NAME) {
        "Component name must be $MAX_NAME characters or fewer, but was ${trimmed.length}"
    }
    return trimmed
}
