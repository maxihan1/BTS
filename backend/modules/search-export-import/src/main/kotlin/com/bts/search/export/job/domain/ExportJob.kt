// 비동기 Export 작업 도메인 집계체 — 상태/진행률/다운로드 가용 여부 캡슐화

package com.bts.search.export.job.domain

import java.time.Instant
import java.util.UUID

/**
 * 비동기 이슈 Export 작업 도메인 집계체(Aggregate Root).
 *
 * export_jobs 테이블 행 1개와 1:1 대응한다.
 * 모든 필드는 불변([val])이며 상태 변경은 새 인스턴스를 반환하는 순수 함수로 처리한다.
 *
 * ## 순수 함수 목록
 *
 * - [progressPercent]: 처리된 행 수와 전체 행 수를 받아 진행률(0..100)을 계산한다.
 * - [downloadReady]: 다운로드 링크 제공 가능 여부를 반환한다.
 *
 * @property id 도메인 식별자.
 * @property projectKey 내보낼 이슈의 프로젝트 키.
 * @property query AQL 쿼리 문자열.
 * @property format 내보내기 형식 문자열 (`"CSV"` 또는 `"XLSX"`).
 * @property columns 출력할 컬럼 이름 목록. 빈 목록이면 전체 9컬럼.
 * @property requesterUserId 작업 요청자 UUID.
 * @property status 현재 작업 상태. [ExportJobStatus] 참조.
 * @property progress 현재 진행률(0..100). DB 컬럼 `progress` 와 동기화된다.
 * @property rowCount 실제 내보낸 행 수. 완료 전까지 null.
 * @property resultObjectKey MinIO 오브젝트 키. 완료 후 채워진다.
 * @property errorCode 실패 시 오류 코드. 완료 시 null.
 * @property expiresAt 결과 파일 만료 시각(UTC). null 이면 만료 없음.
 * @property createdAt 작업 생성 시각(UTC).
 * @property startedAt 워커 클레임 시각(UTC). PENDING 상태에서 null.
 * @property completedAt 작업 완료(성공 또는 실패) 시각(UTC). 진행 중에는 null.
 */
data class ExportJob(
    val id: ExportJobId,
    val projectKey: String,
    val query: String,
    val format: String,
    val columns: List<String>,
    val requesterUserId: UUID,
    val status: ExportJobStatus,
    val progress: Int,
    val rowCount: Long?,
    val resultObjectKey: String?,
    val errorCode: String?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    /**
     * 처리된 행 수와 전체 행 수를 받아 진행률(0..100)을 계산한다.
     *
     * - [total] 이 0 이면 100 을 반환한다(전체 없음 = 이미 완료로 간주).
     * - 그 외에는 `(processed * 100 / total)` 를 정수 내림(floor)으로 반환한다.
     *
     * @param processed 지금까지 처리된 행 수.
     * @param total 전체 행 수.
     * @return 진행률 정수(0..100).
     */
    fun progressPercent(
        processed: Long,
        total: Long,
    ): Int {
        if (total == 0L) return 100
        return (processed * 100L / total).toInt()
    }

    /**
     * 결과 파일 다운로드가 가능한 상태인지 반환한다.
     *
     * [status] 가 [ExportJobStatus.COMPLETED] 이고 [resultObjectKey] 가 null 이 아닐 때만 true.
     */
    val downloadReady: Boolean
        get() = status == ExportJobStatus.COMPLETED && resultObjectKey != null

    companion object {
        /**
         * 단일 Export 작업이 처리할 수 있는 최대 행 수.
         *
         * 이 값을 초과하는 쿼리 결과는 요청 시점에 거부된다([ExportLimitExceededException]).
         * 워커도 이 상수를 `LIMIT` 으로 사용해 DB 과부하를 방지한다.
         */
        const val MAX_ROWS = 100_000L
    }
}
