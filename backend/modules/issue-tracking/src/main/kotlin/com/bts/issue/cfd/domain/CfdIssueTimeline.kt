// CFD 계산기 입력 — 이슈 하나의 상태 타임라인(생성일 + 상태 구간 목록) 순수 VO

package com.bts.issue.cfd.domain

import java.time.LocalDate

/**
 * CFD 계산기([CfdCalculator])의 입력으로 사용되는 이슈 하나의 상태 타임라인.
 *
 * [segments]는 이슈가 거쳐온 카테고리 구간을 나타낸다. 각 구간은 [CfdSegment.startDate]부터
 * (다음 구간 시작 전까지) 유효하다. 호출자(서비스 계층)는 다음 불변식을 만족하도록 조립해야 한다.
 * - [segments]는 [CfdSegment.startDate] 기준 오름차순 정렬, distinct 날짜.
 * - `segments[0].startDate == createdDate` (이슈 생성 시점의 초기 카테고리).
 *
 * @property createdDate 이슈 생성일.
 * @property segments 오름차순 정렬된 상태 구간 목록. 최소 1개(초기 카테고리) 이상.
 */
data class CfdIssueTimeline(
    val createdDate: LocalDate,
    val segments: List<CfdSegment>,
)

/**
 * CFD 계산기 입력의 상태 구간.
 *
 * [startDate]부터 다음 구간 시작 전까지 [category]가 유효함을 나타낸다.
 *
 * @property startDate 이 카테고리가 유효하기 시작하는 날짜(inclusive).
 * @property category 이 구간의 카테고리(TODO/IN_PROGRESS/DONE).
 */
data class CfdSegment(
    val startDate: LocalDate,
    val category: CfdCategory,
)
