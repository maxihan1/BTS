// CFD 계산 최종 결과 — 프로젝트·창·시계열을 담는 read-model VO

package com.bts.issue.cfd.domain

import java.time.LocalDate

/**
 * CFD(Cumulative Flow Diagram, 누적 흐름 다이어그램) 계산 최종 결과.
 *
 * [CfdCalculator.calculate]가 반환하는 [CfdPoint] 목록에 프로젝트·창 정보를 더해 서비스 계층이
 * 조립하는 read-model VO다. 영속 대상이 아니다.
 *
 * @property projectKey 대상 프로젝트 키.
 * @property from 창 시작일(inclusive).
 * @property to 창 종료일(inclusive).
 * @property points 날짜 오름차순으로 정렬된 CFD 지점 목록. [from]~[to] 각 날짜 1개씩.
 */
data class CfdResult(
    val projectKey: String,
    val from: LocalDate,
    val to: LocalDate,
    val points: List<CfdPoint>,
)
