// 스프린트별 벨로시티 지점과 산술평균을 담는 순수 도메인 값 객체 — 외부 의존 0
package com.bts.agileplanning.domain.velocity

/**
 * 프로젝트의 스프린트 벨로시티 시계열과 평균 값 객체 (Value Object).
 *
 * 이 클래스는 외부 의존이 없는 순수 도메인 객체이며, [of] 팩토리 메서드로만 생성한다.
 *
 * @property projectKey 프로젝트 키.
 * @property averageCommitmentSeconds 계획(Commitment) 시간의 산술평균(초). 스프린트가 없으면 0.
 * @property averageCompletedSeconds 완료(Completed) 시간의 산술평균(초). 스프린트가 없으면 0.
 * @property points 입력 순서(시간순 오름차순)를 보존한 스프린트별 지점 목록.
 */
data class SprintVelocityResult(
    val projectKey: String,
    val averageCommitmentSeconds: Long,
    val averageCompletedSeconds: Long,
    val points: List<VelocityPoint>,
) {
    companion object {
        /**
         * 스프린트별 [VelocityPoint] 목록으로부터 [SprintVelocityResult] 를 생성한다.
         *
         * 평균은 정수(Long) 나눗셈으로 계산하며 소수점 이하는 반내림(floor)된다.
         *
         * @param projectKey 프로젝트 키.
         * @param points 시간순으로 정렬된 스프린트별 지점 목록. 빈 리스트면 평균은 0.
         */
        fun of(
            projectKey: String,
            points: List<VelocityPoint>,
        ): SprintVelocityResult {
            if (points.isEmpty()) {
                return SprintVelocityResult(
                    projectKey = projectKey,
                    averageCommitmentSeconds = 0L,
                    averageCompletedSeconds = 0L,
                    points = emptyList(),
                )
            }

            return SprintVelocityResult(
                projectKey = projectKey,
                averageCommitmentSeconds = points.sumOf { it.commitmentSeconds } / points.size,
                averageCompletedSeconds = points.sumOf { it.completedSeconds } / points.size,
                points = points,
            )
        }
    }
}
