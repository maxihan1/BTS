// 이슈 링크 도메인 객체 — 두 이슈 사이의 단방향 관계를 표현하는 불변 값 객체

package com.bts.issue.link.domain

import java.util.UUID

/**
 * 두 이슈 사이의 단방향 링크 관계.
 *
 * ## 불변성
 * 모든 필드는 val 이다. 링크 수정은 삭제 후 재생성으로 처리한다.
 *
 * ## 단방향 저장
 * DB에는 source→target 방향의 row 1개만 저장된다.
 * target 이슈 입장에서의 역방향 라벨은 [LinkType.inwardLabel]로 계산한다.
 *
 * ## 생성 규칙
 * - source == target 불허: [create] 팩토리에서 [LinkSelfReferenceException] 발생.
 * - 중복/순환 검증은 서비스 계층에서 처리한다 (저장소 조회 필요).
 *
 * @property id DB 서로게이트 키. null 이면 아직 영속화되지 않은 상태.
 * @property sourceId 링크 출발 이슈 UUID.
 * @property targetId 링크 도착 이슈 UUID.
 * @property linkType 링크 관계 유형.
 */
data class IssueLink(
    val id: Long? = null,
    val sourceId: UUID,
    val targetId: UUID,
    val linkType: LinkType,
) {
    companion object {
        /**
         * [IssueLink] 생성 팩토리.
         *
         * 자기참조([sourceId] == [targetId])가 감지되면 즉시 [LinkSelfReferenceException]을 던진다.
         *
         * @param sourceId 링크 출발 이슈 UUID
         * @param targetId 링크 도착 이슈 UUID
         * @param linkType 링크 관계 유형
         * @return 검증된 [IssueLink] 인스턴스
         * @throws LinkSelfReferenceException sourceId == targetId 일 때
         */
        fun create(sourceId: UUID, targetId: UUID, linkType: LinkType): IssueLink {
            if (sourceId == targetId) {
                throw LinkSelfReferenceException(sourceId)
            }
            return IssueLink(sourceId = sourceId, targetId = targetId, linkType = linkType)
        }
    }
}
