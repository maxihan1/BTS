// 이슈 링크 타입 enum — 4종 코드·라벨·대칭 여부 정의

package com.bts.issue.link.domain

/**
 * 이슈 간 링크 관계 유형.
 *
 * ## 단방향 저장, 역방향 계산
 * DB의 `issue_links` 테이블에는 row 1개만 저장한다 (`source_id`, `target_id`, `link_type`).
 * 조회 시 target 쪽 이슈에서 표시할 라벨은 [inwardLabel]로 계산한다.
 * 예) BLOCKS로 저장된 row를 target 입장에서 보면 "is blocked by" 라벨로 표시된다.
 *
 * ## DDL CHECK 정합
 * `issue_links.link_type` 컬럼의 CHECK 제약은 이 enum의 [code] 집합과 동기화되어야 한다.
 * [LinkTypeTest]의 DDL 정합 가드 테스트가 drift를 방지한다.
 *
 * @property code DB 저장 값. DDL CHECK 제약과 1:1 대응.
 * @property outwardLabel source 이슈에서 표시하는 관계 라벨.
 * @property inwardLabel target 이슈에서 표시하는 역방향 라벨.
 * @property isSymmetric true이면 outwardLabel == inwardLabel (방향 무관 관계).
 */
enum class LinkType(
    val code: String,
    val outwardLabel: String,
    val inwardLabel: String,
    val isSymmetric: Boolean,
) {
    /** source가 target을 차단한다. 역방향: target은 source에 의해 차단된다. */
    BLOCKS("blocks", "blocks", "is blocked by", false),

    /** 양방향 연관 관계. 방향 무관하므로 대칭(isSymmetric = true). */
    RELATES("relates", "relates to", "relates to", true),

    /** source가 target을 중복한다. 역방향: target은 source에 의해 중복된다. */
    DUPLICATES("duplicates", "duplicates", "is duplicated by", false),

    /** source가 target을 복제한다. 역방향: target은 source에 의해 복제된다. */
    CLONES("clones", "clones", "is cloned by", false),
    ;

    companion object {
        /**
         * DB 저장 코드 문자열로 [LinkType]을 조회한다.
         *
         * @param code DB에 저장된 link_type 코드 (예: "blocks")
         * @return 매칭된 [LinkType]
         * @throws InvalidLinkTypeCodeException 코드가 알 수 없는 값일 때
         */
        fun fromCode(code: String): LinkType {
            return entries.firstOrNull { it.code == code }
                ?: throw InvalidLinkTypeCodeException(code)
        }
    }
}
