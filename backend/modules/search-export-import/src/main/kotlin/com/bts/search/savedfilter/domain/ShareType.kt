// 저장된 필터 공유 종류 enum — PROJECT / GROUP / AUTHENTICATED (FR-SR-03)

package com.bts.search.savedfilter.domain

/**
 * 저장된 필터의 공유 대상 종류.
 *
 * - [PROJECT]: 특정 프로젝트 구성원에게 공유. [SavedFilterShare.targetId]에 프로젝트 키 필수.
 * - [GROUP]: 특정 사용자 그룹에 공유. [SavedFilterShare.targetId]에 그룹 식별자 필수.
 * - [AUTHENTICATED]: 로그인된 모든 사용자에게 공유. [SavedFilterShare.targetId]는 반드시 `null`.
 */
enum class ShareType {
    PROJECT,
    GROUP,
    AUTHENTICATED,
    ;

    companion object {
        /**
         * 문자열 값에 해당하는 [ShareType]을 반환한다.
         *
         * 알 수 없는 값은 500으로 변질되지 않도록 [IllegalArgumentException]을 던진다.
         * 상위 핸들러의 `handleIllegalArgument`가 400으로 매핑한다.
         *
         * @param value 변환할 문자열 (예: "PROJECT").
         * @return 일치하는 [ShareType].
         * @throws IllegalArgumentException 알 수 없는 값인 경우.
         */
        fun from(value: String): ShareType =
            entries.find { it.name == value }
                ?: throw IllegalArgumentException("알 수 없는 ShareType 값입니다: '$value'")
    }
}
