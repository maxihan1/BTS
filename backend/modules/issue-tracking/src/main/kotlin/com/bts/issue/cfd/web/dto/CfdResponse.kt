// CFD(누적 흐름도) 조회 응답 DTO — GET /api/v1/projects/{projectKey}/cfd 응답 구조 (FR-RP-03 Task 5)

package com.bts.issue.cfd.web.dto

import com.bts.issue.cfd.domain.CfdResult
import java.time.LocalDate

/**
 * CFD 시계열의 단일 지점 응답 DTO.
 *
 * @property date 이 지점이 나타내는 캘린더 일자.
 * @property todoCount 이 날짜에 TODO 카테고리로 분류된 이슈 누적 개수.
 * @property inProgressCount 이 날짜에 IN_PROGRESS 카테고리로 분류된 이슈 누적 개수.
 * @property doneCount 이 날짜에 DONE 카테고리로 분류된 이슈 누적 개수.
 */
data class CfdPointResponse(
    val date: LocalDate,
    val todoCount: Int,
    val inProgressCount: Int,
    val doneCount: Int,
)

/**
 * GET /api/v1/projects/{projectKey}/cfd 최종 응답 DTO.
 *
 * [from]/[to] 는 컨트롤러가 해석한 실제 조회 창을 그대로 echo 한다(요청에서 생략되었어도 실제
 * 적용된 값을 프론트가 알 수 있도록 한다).
 *
 * @property projectKey 대상 프로젝트 키.
 * @property from 실제 적용된 창 시작일(inclusive).
 * @property to 실제 적용된 창 종료일(inclusive).
 * @property points 날짜 오름차순 CFD 지점 목록.
 */
data class CfdResponse(
    val projectKey: String,
    val from: LocalDate,
    val to: LocalDate,
    val points: List<CfdPointResponse>,
) {
    companion object {
        /**
         * [CfdResult] 도메인 read-model 을 응답 DTO 로 변환한다.
         *
         * @param result 서비스 계층의 CFD 계산 결과.
         * @return 응답 DTO.
         */
        fun from(result: CfdResult): CfdResponse =
            CfdResponse(
                projectKey = result.projectKey,
                from = result.from,
                to = result.to,
                points =
                    result.points.map { point ->
                        CfdPointResponse(
                            date = point.date,
                            todoCount = point.todoCount,
                            inProgressCount = point.inProgressCount,
                            doneCount = point.doneCount,
                        )
                    },
            )
    }
}
