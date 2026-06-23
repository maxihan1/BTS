// 이슈 리랭크 이웃 검증 실패 시 던지는 도메인 예외 (FR-BL-01, spec #12)

package com.bts.issue.application

/**
 * 리랭크 이웃 이슈 검증 실패 예외.
 *
 * 다음 케이스에서 던진다.
 * - previousIssueKey 와 nextIssueKey 가 둘 다 null
 * - 대상 이슈 key == previousIssueKey 또는 == nextIssueKey
 * - previousIssueKey == nextIssueKey (동일 이웃)
 * - 이웃 이슈가 대상 이슈와 다른 프로젝트
 * - previousRank >= nextRank (이웃 순서 역전)
 *
 * HTTP 400 매핑은 IssueExceptionHandler (Task 5) 에서 처리한다.
 * IllegalArgumentException raw throw 대신 이 클래스를 사용해 catch-all 500 변질을 차단한다
 * (spec #12, eng 리뷰 B1).
 *
 * @param reason 사람이 읽을 수 있는 검증 실패 이유 (내부 구조 미포함).
 */
class InvalidRankNeighborException(reason: String) : RuntimeException(reason)
