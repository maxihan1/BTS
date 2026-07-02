// 스프린트 번다운/번업 차트 REST 응답 DTO — agile-planning BC (FR-RP-01 Task 5)

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.SprintBurndownResult
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownPoint
import java.time.LocalDate
import java.util.UUID

/**
 * 번다운(Burndown)/번업(Burnup) 시계열의 하루 단위 지점 응답 DTO.
 *
 * 도메인 [BurndownPoint] 를 그대로 REST 응답 형태로 노출한다(스펙 API 인터페이스 §번다운/번업).
 *
 * @property date 이 지점이 나타내는 캘린더 일자(ISO `yyyy-MM-dd`).
 * @property remainingSeconds Actual 잔여 시간(초). asOf(= min(end, today)) 이후 미래 일자는 null.
 * @property idealSeconds Ideal 시간(초). start 에서 총 스코프, end 에서 0 으로 선형 보간. 전 구간 non-null.
 * @property completedSeconds Burnup 누적 완료 시간(초). remainingSeconds 와 동일한 null 규칙을 따른다.
 * @property scopeSeconds Burnup 총 스코프(초). 전 구간 평탄(flat), non-null.
 */
data class BurndownPointResponse(
    val date: LocalDate,
    val remainingSeconds: Long?,
    val idealSeconds: Long,
    val completedSeconds: Long?,
    val scopeSeconds: Long,
) {
    companion object {
        /**
         * 도메인 [BurndownPoint] 를 [BurndownPointResponse] 로 변환한다.
         *
         * @param point 변환할 번다운/번업 시계열 지점.
         * @return 응답 DTO 인스턴스.
         */
        fun from(point: BurndownPoint): BurndownPointResponse =
            BurndownPointResponse(
                date = point.date,
                remainingSeconds = point.remainingSeconds,
                idealSeconds = point.idealSeconds,
                completedSeconds = point.completedSeconds,
                scopeSeconds = point.scopeSeconds,
            )
    }
}

/**
 * 스프린트 번다운/번업 차트 REST 응답 DTO.
 *
 * `GET /api/v1/sprints/{id}/burndown` 의 `data` 필드 형태다([DataResponse] 로 래핑된다).
 *
 * @property sprintId 스프린트 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property status 조회 시점의 스프린트 상태.
 * @property startDate 스프린트 시작일(ISO `yyyy-MM-dd`).
 * @property endDate 스프린트 종료일(ISO `yyyy-MM-dd`).
 * @property totalScopeSeconds 총 스코프(초). Σ original_estimate_seconds, NULL=0.
 * @property points 날짜 오름차순 번다운/번업 시계열.
 */
data class BurndownResponse(
    val sprintId: UUID,
    val projectKey: String,
    val status: SprintStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalScopeSeconds: Long,
    val points: List<BurndownPointResponse>,
) {
    companion object {
        /**
         * 애플리케이션 계층 [SprintBurndownResult] 를 [BurndownResponse] 로 변환한다.
         *
         * @param result 변환할 번다운/번업 조회 결과 VO.
         * @return 응답 DTO 인스턴스.
         */
        fun from(result: SprintBurndownResult): BurndownResponse =
            BurndownResponse(
                sprintId = result.sprintId,
                projectKey = result.projectKey,
                status = result.status,
                startDate = result.startDate,
                endDate = result.endDate,
                totalScopeSeconds = result.totalScopeSeconds,
                points = result.points.map(BurndownPointResponse::from),
            )
    }
}
