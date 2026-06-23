// LexoRank 키 공간 고갈 예외 — between 이 VARCHAR(50) 내 중간값을 생성할 수 없을 때 발행

package com.bts.shared.lexorank

/**
 * LexoRank 키 공간 고갈 예외.
 *
 * Rank.between 이 두 경계 사이에서 VARCHAR(50) 제한 내 중간값을 만들 수 없을 때 던진다.
 * 호출측은 이 예외를 받으면 프로젝트 백로그 rebalance 를 트리거해야 한다.
 *
 * @param message 고갈 상세 메시지
 */
class RankSpaceExhaustedException(message: String) : RuntimeException(message)
