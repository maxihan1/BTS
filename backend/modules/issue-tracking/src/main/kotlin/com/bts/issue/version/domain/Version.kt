// 프로젝트별 릴리스 단위(버전) Aggregate Root
package com.bts.issue.version.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 버전 이름의 최대 허용 글자 수. */
internal const val MAX_NAME = 255

/**
 * 버전 Aggregate Root.
 *
 * 프로젝트의 릴리스 단위를 표현한다.
 * 직접 생성자 대신 [Version.create] factory 를 통해 invariant 를 검증하고 인스턴스를 얻는다.
 *
 * invariant.
 * - [name] 은 trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
 * - [deletedAt] 은 생성 시 null. 소프트 삭제 시 타임스탬프가 채워진다.
 * - 이미 삭제된 버전에 [softDelete] 재호출 시 [IllegalStateException] 발생.
 *
 * **날짜 순서 미강제.**
 * [startDate] 와 [releaseDate] 의 선후 관계는 도메인이 검증하지 않는다(Jira 기본 동작).
 * 순서 강제가 필요해질 경우 [changeDates] 가 단일 진입점이므로 그 안에서 추가한다.
 *
 * @property id DB PK. 신규 생성 전(DB 저장 전)에는 null 이다.
 * @property projectId 이 버전이 속한 프로젝트의 UUID.
 * @property name 버전 이름. trim 후 1~[MAX_NAME]자.
 * @property description 선택적 설명. null 허용.
 * @property startDate 버전 시작일. null 이면 미지정 상태.
 * @property releaseDate 버전 릴리스 예정일. null 이면 미지정 상태.
 * @property deletedAt 소프트 삭제 타임스탬프. null 이면 활성 상태.
 */
data class Version(
    val id: UUID?,
    val projectId: UUID,
    val name: String,
    val description: String?,
    val startDate: LocalDate?,
    val releaseDate: LocalDate?,
    val deletedAt: Instant?,
) {
    companion object {
        /**
         * 새 버전을 생성한다.
         *
         * [name] invariant 위반 시 [IllegalArgumentException] 을 던진다.
         *
         * @param projectId 이 버전이 속한 프로젝트 UUID.
         * @param name 버전 이름. trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
         * @param description 선택적 설명. 기본값 null.
         * @param startDate 버전 시작일. 기본값 null(미지정).
         * @param releaseDate 버전 릴리스 예정일. 기본값 null(미지정).
         * @return 생성된 [Version] 인스턴스.
         */
        fun create(
            projectId: UUID,
            name: String,
            description: String? = null,
            startDate: LocalDate? = null,
            releaseDate: LocalDate? = null,
        ): Version {
            val trimmedName = validateAndTrimName(name)
            return Version(
                id = null,
                projectId = projectId,
                name = trimmedName,
                description = description,
                startDate = startDate,
                releaseDate = releaseDate,
                deletedAt = null,
            )
        }
    }

    /**
     * 이 버전의 이름을 변경한다.
     *
     * [newName] invariant 위반 시 [IllegalArgumentException] 을 던진다.
     *
     * @param newName 새 이름. trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
     * @return [name] 이 [newName] 으로 설정된 새 [Version] 인스턴스.
     */
    fun rename(newName: String): Version {
        val trimmedName = validateAndTrimName(newName)
        return copy(name = trimmedName)
    }

    /**
     * 이 버전의 설명을 변경한다.
     *
     * null 을 전달하면 설명을 클리어한다.
     *
     * @param newDescription 새 설명. null 허용(클리어).
     * @return [description] 이 [newDescription] 으로 설정된 새 [Version] 인스턴스.
     */
    fun changeDescription(newDescription: String?): Version = copy(description = newDescription)

    /**
     * 이 버전의 시작일과 릴리스 예정일을 통째로 변경한다.
     *
     * 두 값을 동시에 치환하는 순수 setter 다. 날짜 선후 관계는 강제하지 않는다(Jira 기본 동작).
     * 미래에 순서 강제가 필요해질 경우 이 메서드 안에서 추가하면 단일 진입점이 된다.
     *
     * @param newStartDate 새 시작일. null 이면 미지정 상태로 전환.
     * @param newReleaseDate 새 릴리스 예정일. null 이면 미지정 상태로 전환.
     * @return [startDate] 와 [releaseDate] 가 갱신된 새 [Version] 인스턴스.
     */
    fun changeDates(newStartDate: LocalDate?, newReleaseDate: LocalDate?): Version =
        copy(startDate = newStartDate, releaseDate = newReleaseDate)

    /**
     * 이 버전을 소프트 삭제한다.
     *
     * 이미 삭제된 버전에 호출하면 [IllegalStateException] 을 던진다.
     *
     * @return [deletedAt] 이 현재 시각으로 설정된 새 [Version] 인스턴스.
     * @throws IllegalStateException 이미 삭제된 버전 재삭제 시.
     */
    fun softDelete(): Version {
        check(deletedAt == null) { "Version is already deleted (id=$id)" }
        return copy(deletedAt = Instant.now())
    }
}

/**
 * [Version.name] 도메인 불변식 검증 및 trim 정규화.
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
    require(trimmed.isNotEmpty()) { "Version name must not be blank" }
    require(trimmed.length <= MAX_NAME) {
        "Version name must be $MAX_NAME characters or fewer, but was ${trimmed.length}"
    }
    return trimmed
}
