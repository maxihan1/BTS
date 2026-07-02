// 스프린트 벨로시티(Velocity) 차트 REST 응답 DTO — agile-planning BC (FR-RP-02 Task 5)

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.domain.velocity.SprintVelocityResult
import com.bts.agileplanning.domain.velocity.VelocityPoint
import java.time.LocalDate
import java.util.UUID

/**
 * 벨로시티(Velocity) 시계열의 스프린트 단위 지점 응답 DTO.
 *
 * 도메인 [VelocityPoint] 를 그대로 REST 응답 형태로 노출한다.
 *
 * @property sprintId 스프린트 UUID.
 * @property name 스프린트 이름.
 * @property startDate 스프린트 시작일(ISO `yyyy-MM-dd`). 미설정 스프린트는 null.
 * @property endDate 스프린트 종료일(ISO `yyyy-MM-dd`). 미설정 스프린트는 null.
 * @property commitmentSeconds 계획(Commitment) 시간 합계(초).
 * @property completedSeconds 완료(Completed) 시간 합계(초).
 */
data class VelocityPointResponse(
    val sprintId: UUID,
    val name: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val commitmentSeconds: Long,
    val completedSeconds: Long,
) {
    companion object {
        /**
         * 도메인 [VelocityPoint] 를 [VelocityPointResponse] 로 변환한다.
         *
         * @param point 변환할 벨로시티 시계열 지점.
         * @return 응답 DTO 인스턴스.
         */
        fun from(point: VelocityPoint): VelocityPointResponse =
            VelocityPointResponse(
                sprintId = point.sprintId,
                name = point.name,
                startDate = point.startDate,
                endDate = point.endDate,
                commitmentSeconds = point.commitmentSeconds,
                completedSeconds = point.completedSeconds,
            )
    }
}

/**
 * 스프린트 벨로시티 차트 REST 응답 DTO.
 *
 * `GET /api/v1/projects/{projectKey}/velocity` 의 `data` 필드 형태다([DataResponse] 로 래핑된다).
 *
 * @property projectKey 프로젝트 키.
 * @property averageCommitmentSeconds 계획(Commitment) 시간의 산술평균(초). 스프린트가 없으면 0.
 * @property averageCompletedSeconds 완료(Completed) 시간의 산술평균(초). 스프린트가 없으면 0.
 * @property sprints 시간순 오름차순 스프린트별 벨로시티 지점.
 */
data class VelocityResponse(
    val projectKey: String,
    val averageCommitmentSeconds: Long,
    val averageCompletedSeconds: Long,
    val sprints: List<VelocityPointResponse>,
) {
    companion object {
        /**
         * 애플리케이션 계층 [SprintVelocityResult] 를 [VelocityResponse] 로 변환한다.
         *
         * @param result 변환할 벨로시티 조회 결과 VO.
         * @return 응답 DTO 인스턴스.
         */
        fun from(result: SprintVelocityResult): VelocityResponse =
            VelocityResponse(
                projectKey = result.projectKey,
                averageCommitmentSeconds = result.averageCommitmentSeconds,
                averageCompletedSeconds = result.averageCompletedSeconds,
                sprints = result.points.map(VelocityPointResponse::from),
            )
    }
}
