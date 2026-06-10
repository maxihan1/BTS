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
 * - [status] 초기값은 [VersionStatus.UNRELEASED].
 * - [releasedAt] 불변식: UNRELEASED → null, RELEASED → non-null, ARCHIVED → 보관 직전 상태 반영.
 * - [VersionStatus.ARCHIVED] 버전은 읽기 전용 — [rename], [changeDescription], [changeDates],
 *   [softDelete] 호출 시 [VersionTransitionNotAllowedException] 발생.
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
 * @property status 버전 생명주기 상태. 기본값 [VersionStatus.UNRELEASED].
 * @property releasedAt 실제 릴리스 시각. RELEASED 전이 시 설정, UNRELEASED/ARCHIVED 전이 시 null.
 * @property deletedAt 소프트 삭제 타임스탬프. null 이면 활성 상태.
 */
data class Version(
    val id: UUID?,
    val projectId: UUID,
    val name: String,
    val description: String?,
    val startDate: LocalDate?,
    val releaseDate: LocalDate?,
    val status: VersionStatus = VersionStatus.UNRELEASED,
    val releasedAt: Instant? = null,
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
         * @return 생성된 [Version] 인스턴스. status=UNRELEASED, releasedAt=null.
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
                status = VersionStatus.UNRELEASED,
                releasedAt = null,
                deletedAt = null,
            )
        }
    }

    /**
     * 이 버전의 이름을 변경한다.
     *
     * [VersionStatus.ARCHIVED] 버전에서는 [VersionTransitionNotAllowedException] 을 던진다.
     * [newName] invariant 위반 시 [IllegalArgumentException] 을 던진다.
     *
     * @param newName 새 이름. trim 후 빈 문자열 불가, 최대 [MAX_NAME]자.
     * @return [name] 이 [newName] 으로 설정된 새 [Version] 인스턴스.
     */
    fun rename(newName: String): Version {
        requireNotArchived("rename")
        val trimmedName = validateAndTrimName(newName)
        return copy(name = trimmedName)
    }

    /**
     * 이 버전의 설명을 변경한다.
     *
     * [VersionStatus.ARCHIVED] 버전에서는 [VersionTransitionNotAllowedException] 을 던진다.
     * null 을 전달하면 설명을 클리어한다.
     *
     * @param newDescription 새 설명. null 허용(클리어).
     * @return [description] 이 [newDescription] 으로 설정된 새 [Version] 인스턴스.
     */
    fun changeDescription(newDescription: String?): Version {
        requireNotArchived("changeDescription")
        return copy(description = newDescription)
    }

    /**
     * 이 버전의 시작일과 릴리스 예정일을 통째로 변경한다.
     *
     * [VersionStatus.ARCHIVED] 버전에서는 [VersionTransitionNotAllowedException] 을 던진다.
     * 두 값을 동시에 치환하는 순수 setter 다. 날짜 선후 관계는 강제하지 않는다(Jira 기본 동작).
     * 미래에 순서 강제가 필요해질 경우 이 메서드 안에서 추가하면 단일 진입점이 된다.
     *
     * @param newStartDate 새 시작일. null 이면 미지정 상태로 전환.
     * @param newReleaseDate 새 릴리스 예정일. null 이면 미지정 상태로 전환.
     * @return [startDate] 와 [releaseDate] 가 갱신된 새 [Version] 인스턴스.
     */
    fun changeDates(
        newStartDate: LocalDate?,
        newReleaseDate: LocalDate?,
    ): Version {
        requireNotArchived("changeDates")
        return copy(startDate = newStartDate, releaseDate = newReleaseDate)
    }

    /**
     * 이 버전을 소프트 삭제한다.
     *
     * [VersionStatus.ARCHIVED] 버전에서는 [VersionTransitionNotAllowedException] 을 던진다.
     * 이미 삭제된 버전에 호출하면 [IllegalStateException] 을 던진다.
     *
     * @return [deletedAt] 이 현재 시각으로 설정된 새 [Version] 인스턴스.
     * @throws VersionTransitionNotAllowedException ARCHIVED 버전 삭제 시도 시.
     * @throws IllegalStateException 이미 삭제된 버전 재삭제 시.
     */
    fun softDelete(): Version {
        requireNotArchived("softDelete")
        check(deletedAt == null) { "Version is already deleted (id=$id)" }
        return copy(deletedAt = Instant.now())
    }

    /**
     * 이 버전을 RELEASED 상태로 전이한다.
     *
     * UNRELEASED 상태에서만 허용한다. 그 외 상태는 [VersionTransitionNotAllowedException].
     *
     * @param now 릴리스 시각 (Clock 은 서비스 레이어 책임, 도메인은 파라미터로 수신).
     * @return status=RELEASED, releasedAt=[now] 로 설정된 새 [Version] 인스턴스.
     * @throws VersionTransitionNotAllowedException UNRELEASED 외 상태에서 호출 시.
     */
    fun release(now: Instant): Version {
        if (status != VersionStatus.UNRELEASED) {
            throw VersionTransitionNotAllowedException(
                "Cannot release version in status $status (id=$id). Only UNRELEASED can be released.",
            )
        }
        return copy(status = VersionStatus.RELEASED, releasedAt = now)
    }

    /**
     * 이 버전을 UNRELEASED 상태로 되돌린다.
     *
     * RELEASED 상태에서만 허용한다. 그 외 상태는 [VersionTransitionNotAllowedException].
     *
     * @return status=UNRELEASED, releasedAt=null 로 설정된 새 [Version] 인스턴스.
     * @throws VersionTransitionNotAllowedException RELEASED 외 상태에서 호출 시.
     */
    fun unrelease(): Version {
        if (status != VersionStatus.RELEASED) {
            throw VersionTransitionNotAllowedException(
                "Cannot unrelease version in status $status (id=$id). Only RELEASED can be unreleased.",
            )
        }
        return copy(status = VersionStatus.UNRELEASED, releasedAt = null)
    }

    /**
     * 이 버전을 ARCHIVED 상태로 전이한다.
     *
     * UNRELEASED 또는 RELEASED 상태에서만 허용한다. 그 외 상태는 [VersionTransitionNotAllowedException].
     * releasedAt 은 보관 직전 값을 그대로 유지한다(RELEASED → ARCHIVED 시 시각 보존).
     *
     * @return status=ARCHIVED, releasedAt 유지된 새 [Version] 인스턴스.
     * @throws VersionTransitionNotAllowedException UNRELEASED/RELEASED 외 상태에서 호출 시.
     */
    fun archive(): Version {
        if (status == VersionStatus.ARCHIVED) {
            throw VersionTransitionNotAllowedException(
                "Cannot archive version in status $status (id=$id). Already ARCHIVED.",
            )
        }
        return copy(status = VersionStatus.ARCHIVED)
    }

    /**
     * 이 버전을 ARCHIVED 에서 UNRELEASED 로 복원한다.
     *
     * ARCHIVED 상태에서만 허용한다. 그 외 상태는 [VersionTransitionNotAllowedException].
     *
     * @return status=UNRELEASED, releasedAt=null 로 설정된 새 [Version] 인스턴스.
     * @throws VersionTransitionNotAllowedException ARCHIVED 외 상태에서 호출 시.
     */
    fun unarchive(): Version {
        if (status != VersionStatus.ARCHIVED) {
            throw VersionTransitionNotAllowedException(
                "Cannot unarchive version in status $status (id=$id). Only ARCHIVED can be unarchived.",
            )
        }
        return copy(status = VersionStatus.UNRELEASED, releasedAt = null)
    }

    /**
     * ARCHIVED 상태에서 mutation 을 시도할 경우 [VersionTransitionNotAllowedException] 을 던진다.
     *
     * @param operation 호출 측 연산 이름 (에러 메시지 포함용).
     */
    private fun requireNotArchived(operation: String) {
        if (status == VersionStatus.ARCHIVED) {
            throw VersionTransitionNotAllowedException(
                "Cannot $operation an ARCHIVED version (id=$id). Unarchive first.",
            )
        }
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
