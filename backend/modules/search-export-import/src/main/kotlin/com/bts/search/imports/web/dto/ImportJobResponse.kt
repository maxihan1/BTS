// Import 작업 접수/폴링 응답 DTO — POST/GET /api/v1/imports 공용 (FR-IM-01 PR1 Task 11)

package com.bts.search.imports.web.dto

import com.bts.search.imports.job.domain.ImportJob
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * Import 작업 접수/폴링 응답 DTO.
 *
 * `POST /api/v1/imports` (202 Accepted)와 `GET /api/v1/imports/{jobId}` (200 OK) 양쪽에서
 * 공용으로 사용한다. `export` BC 는 접수용 [com.bts.search.web.dto.ExportJobSubmitResponse] 와
 * 폴링용 [com.bts.search.web.dto.ExportJobResponse] 를 분리하지만, import 는 접수 응답에도
 * 폴링과 동일한 필드 집합이 유용하므로(진행률 등은 접수 직후 0/null) 단일 DTO 로 통합했다.
 * null 필드는 직렬화에서 제외한다([JsonInclude.Include.NON_NULL]).
 *
 * @property jobId 작업 식별자 UUID.
 * @property status 현재 상태 문자열 (PENDING/RUNNING/COMPLETED/FAILED).
 * @property progress 진행률(0~100). 접수 직후 0.
 * @property totalRows 전체 행 수. RUNNING 에서 파싱 후 확정되며 그 전에는 null.
 * @property succeededRows 성공 처리된 행 수 누적.
 * @property failedRows 실패 처리된 행 수 누적.
 * @property errorCode 실패 시 오류 코드. 정상이면 null.
 * @property errorLogReady 실패행 에러 로그 다운로드 가능 여부.
 * @property dryRun 검증 전용 실행 여부.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImportJobResponse(
    val jobId: UUID,
    val status: String,
    val progress: Int,
    val totalRows: Long?,
    val succeededRows: Long,
    val failedRows: Long,
    val errorCode: String?,
    val errorLogReady: Boolean,
    val dryRun: Boolean,
) {
    companion object {
        /**
         * [ImportJob] 도메인 객체를 [ImportJobResponse] DTO 로 변환한다.
         *
         * @param job 변환할 Import 작업 도메인 객체.
         * @return 변환된 응답 DTO.
         */
        fun from(job: ImportJob): ImportJobResponse =
            ImportJobResponse(
                jobId = job.id.value,
                status = job.status.name,
                progress = job.progress,
                totalRows = job.totalRows,
                succeededRows = job.succeededRows,
                failedRows = job.failedRows,
                errorCode = job.errorCode,
                errorLogReady = job.errorLogReady,
                dryRun = job.dryRun,
            )
    }
}
