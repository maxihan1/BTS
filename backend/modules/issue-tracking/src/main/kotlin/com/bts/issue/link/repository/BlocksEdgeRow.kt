// 타임라인 의존 엣지 조회 결과 행 — blocks 링크의 양끝 이슈 id 쌍

package com.bts.issue.link.repository

import java.util.UUID

/**
 * [IssueLinkRepository.findBlocksEdgesAmong] 조회 결과 행.
 *
 * 타임라인 뷰에서 blocks 화살표를 그리기 위해 필요한 최소 데이터만 담는다.
 * 호출 시 id 집합이 이미 가시성·프로젝트·날짜 필터를 통과했으므로 별도 보안 술어가 불필요하다.
 *
 * @property sourceId blocks 링크 출발 이슈 UUID.
 * @property targetId blocks 링크 도착 이슈 UUID.
 */
data class BlocksEdgeRow(
    val sourceId: UUID,
    val targetId: UUID,
)
