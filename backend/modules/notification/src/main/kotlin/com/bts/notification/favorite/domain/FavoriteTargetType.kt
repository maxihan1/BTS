// 즐겨찾기 대상 종류를 나타내는 값 객체 enum

package com.bts.notification.favorite.domain

/**
 * 즐겨찾기 대상 종류를 정의하는 열거형.
 *
 * DB 에는 TEXT 컬럼으로 저장되며 name() 문자열(ISSUE/FILTER/DASHBOARD/PROJECT)을 사용한다.
 * [from] 팩토리를 통해서만 외부 문자열(wire 값)로부터 변환하며,
 * 대소문자를 구분하므로 반드시 대문자 이름과 정확히 일치해야 한다.
 */
enum class FavoriteTargetType {
    /** 이슈 */
    ISSUE,

    /** 저장된 이슈 필터 (현재 정의만 존재, UI 미노출) */
    FILTER,

    /** 대시보드 */
    DASHBOARD,

    /** 프로젝트 */
    PROJECT,
    ;

    companion object {
        /**
         * wire 문자열에서 [FavoriteTargetType] 으로 변환한다.
         *
         * 대소문자를 구분하며 enum name() 과 정확히 일치하는 값만 허용한다.
         * 일치하는 값이 없으면 [FavoriteDomainException] 을 던진다.
         *
         * @param wire DB / API 에서 수신한 문자열
         * @return 해당하는 [FavoriteTargetType]
         * @throws FavoriteDomainException 유효하지 않은 값이 전달된 경우
         */
        fun from(wire: String): FavoriteTargetType {
            return entries.find { it.name == wire }
                ?: throw FavoriteDomainException(
                    "유효하지 않은 즐겨찾기 대상 타입입니다: '$wire'. " +
                        "허용 값: ${entries.joinToString { it.name }}",
                )
        }
    }
}
