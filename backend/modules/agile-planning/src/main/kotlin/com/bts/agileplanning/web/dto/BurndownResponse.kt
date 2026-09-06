// 스프린트 번다운/번업 차트 REST 응답 DTO — agile-planning BC (FR-RP-01 Task 5)

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.SprintBurndownResult
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownPoint
import com.bts.agileplanning.domain.burndown.BurndownUnit
import java.time.LocalDate
import java.util.UUID

/**
 * 번다운(Burndown)/번업(Burnup) 시계열의 하루 단위 지점 응답 DTO.
 *
 * 도메인 [BurndownPoint] 를 그대로 REST 응답 형태로 노출한다(스펙 API 인터페이스 §번다운/번업).
 *
 * ### ★네 값의 **단위**는 [BurndownResponse.unit] 이 정한다 (부채 177 task-35)
 * 보드의 `time_tracking` 이 `NONE` 이면 이 칸들에 초가 아니라 **이슈 개수**가 담긴다.
 * 필드 이름을 바꾸지 않은 것은 그것이 이미 공개 계약이고 프론트 Zod 스키마가 같은 이름으로 파싱하기
 * 때문이다 — 대신 `unit` 을 같은 응답에 실어 소비측이 단위를 오해할 수 없게 한다.
 * ★소비측(차트)은 `unit` 을 보고 축 라벨과 포맷(초→시간 vs 개수)을 갈라야 한다.
 *
 * @property date 이 지점이 나타내는 캘린더 일자(ISO `yyyy-MM-dd`).
 * @property remainingSeconds Actual 잔여값. asOf(= min(end, today)) 이후 미래 일자는 null.
 * @property idealSeconds Ideal 값. start 에서 총 스코프, end 에서 0 으로 선형 보간. 전 구간 non-null.
 * @property completedSeconds Burnup 누적 완료값. remainingSeconds 와 동일한 null 규칙을 따른다.
 * @property scopeSeconds Burnup 총 스코프. 전 구간 평탄(flat), non-null.
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
 * @property totalScopeSeconds 총 스코프. [unit] 이 뜻을 정한다 — `SECONDS` 면 초
 *   (Σ original_estimate_seconds, NULL=0), `ISSUE_COUNT` 면 가시 이슈 수다.
 * @property unit 이 시계열의 단위(`SECONDS` / `ISSUE_COUNT`). 보드 「추정」 탭의 `time_tracking` 이
 *   정한다(부채 177 task-35 · 스펙 S2 · J36). **추가 필드다** — 기존 클라이언트는 무시해도 되지만,
 *   그러면 `NONE` 보드에서 개수를 시간으로 포맷해 보여주게 된다.
 * @property points 날짜 오름차순 번다운/번업 시계열. 값의 단위는 [unit] 이다.
 */
data class BurndownResponse(
    val sprintId: UUID,
    val projectKey: String,
    val status: SprintStatus,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalScopeSeconds: Long,
    val unit: BurndownUnit,
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
                unit = result.unit,
                points = result.points.map(BurndownPointResponse::from),
            )
    }
}
