// 비동기 Import 작업 도메인 집계체 — 상태/진행률/에러 로그 가용 여부 캡슐화

package com.bts.search.imports.job.domain

import java.time.Instant
import java.util.UUID

/**
 * 비동기 CSV/JSON Import 작업 도메인 집계체(Aggregate Root).
 *
 * import_jobs 테이블 행 1개와 1:1 대응한다.
 * 모든 필드는 불변([val])이며 상태 변경은 새 인스턴스를 반환하는 순수 함수로 처리한다.
 *
 * ## 순수 함수 목록
 *
 * - [progressPercent]: 처리된 행 수와 전체 행 수를 받아 진행률(0..100)을 계산한다.
 * - [errorLogReady]: 실패행 로그 다운로드 제공 가능 여부를 반환한다.
 *
 * `export` BC [com.bts.search.export.job.domain.ExportJob] 을 1:1 미러하되
 * import 전용 필드(sourceObjectKey, dryRun, totalRows, succeededRows, failedRows,
 * errorLogObjectKey)로 조정했다.
 *
 * @property id 도메인 식별자.
 * @property projectKey Import 대상 프로젝트 키.
 * @property format Import 파일 형식 문자열 (`"CSV"` 또는 `"JSON"`).
 * @property sourceObjectKey 업로드 원본 MinIO 오브젝트 키.
 * @property dryRun 검증 전용 실행 여부. true 면 실제 이슈를 생성하지 않는다.
 * @property requesterUserId 작업 요청자 UUID.
 * @property status 현재 작업 상태. [ImportJobStatus] 참조.
 * @property progress 현재 진행률(0..100). DB 컬럼 progress 와 동기화된다.
 * @property totalRows 전체 행 수. RUNNING 에서 파싱 후 확정되며 그 전에는 null.
 * @property succeededRows 성공 처리된 행 수 누적.
 * @property failedRows 실패 처리된 행 수 누적.
 * @property errorCode 실패 시 오류 코드. 완료 시 null.
 * @property errorLogObjectKey 실패행 로그 MinIO 오브젝트 키. COMPLETED 에서 채워질 수 있다.
 * @property expiresAt 작업 산출물 만료 시각(UTC). null 이면 만료 없음.
 * @property createdAt 작업 생성 시각(UTC).
 * @property startedAt 워커 클레임 시각(UTC). PENDING 상태에서 null.
 * @property completedAt 작업 완료(성공 또는 실패) 시각(UTC). 진행 중에는 null.
 * @property attachmentsObjectKey 첨부 zip MinIO 오브젝트 키(V605). CSV import 이거나 zip 미첨부면 null.
 */
data class ImportJob(
    val id: ImportJobId,
    val projectKey: String,
    val format: String,
    val sourceObjectKey: String,
    val dryRun: Boolean,
    val requesterUserId: UUID,
    val status: ImportJobStatus,
    val progress: Int,
    val totalRows: Long?,
    val succeededRows: Long,
    val failedRows: Long,
    val errorCode: String?,
    val errorLogObjectKey: String?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val attachmentsObjectKey: String? = null,
) {
    /**
     * 처리된 행 수와 전체 행 수를 받아 진행률(0..100)을 계산한다.
     *
     * - [total] 이 0 이면 100 을 반환한다(전체 없음 = 이미 완료로 간주).
     * - 그 외에는 processed 곱하기 100 나누기 total 을 정수 내림(floor)으로 반환한다.
     *
     * @param processed 지금까지 처리된 행 수.
     * @param total 전체 행 수.
     * @return 진행률 정수(0..100).
     */
    fun progressPercent(
        processed: Long,
        total: Long,
    ): Int {
        if (total == 0L) return PERCENT_SCALE.toInt()
        return (processed * PERCENT_SCALE / total).toInt()
    }

    /**
     * 실패행 로그 다운로드가 가능한 상태인지 반환한다.
     *
     * [status] 가 [ImportJobStatus.COMPLETED] 이고 [errorLogObjectKey] 가 null 이 아닐 때만 true.
     */
    val errorLogReady: Boolean
        get() = status == ImportJobStatus.COMPLETED && errorLogObjectKey != null

    companion object {
        /**
         * 비동기 Import 작업이 처리할 수 있는 최대 행 수.
         *
         * 워커가 파싱 시 총 건수를 확인하여 이 값 초과 시 작업을 FAILED 로 전환한다.
         * `export` BC ExportJob.MAX_ROWS 와 동일한 한도를 사용한다.
         */
        const val MAX_ROWS = 100_000L

        /** 진행률 100% 기준 값 (Long). [progressPercent] 계산에 사용. */
        private const val PERCENT_SCALE = 100L
    }
}
