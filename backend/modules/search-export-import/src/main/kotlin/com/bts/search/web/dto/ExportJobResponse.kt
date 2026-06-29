// 비동기 Export 작업 폴링 응답 DTO — GET /api/v1/search/export-jobs/{id} (FR-EX-02 Task 10)

package com.bts.search.web.dto

import com.bts.search.export.job.domain.ExportJob
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * 비동기 Export 작업 폴링 응답 DTO.
 *
 * `GET /api/v1/search/export-jobs/{id}` 응답으로 클라이언트가 작업 상태를 폴링할 때 사용한다.
 * null 필드는 직렬화에서 제외한다([JsonInclude.Include.NON_NULL]).
 *
 * @property jobId 작업 식별자 UUID.
 * @property status 현재 상태 문자열 (PENDING/RUNNING/COMPLETED/FAILED).
 * @property progress 진행률(0~100). PENDING 이면 0.
 * @property rowCount 내보낸 행 수. 완료 전까지 null.
 * @property format 파일 형식 문자열 (CSV/XLSX).
 * @property errorCode 실패 시 오류 코드. 정상이면 null.
 * @property downloadReady 다운로드 가능 여부. COMPLETED + objectKey 있음 = true.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportJobResponse(
    val jobId: UUID,
    val status: String,
    val progress: Int,
    val rowCount: Long?,
    val format: String,
    val errorCode: String?,
    val downloadReady: Boolean,
) {
    companion object {
        /**
         * [ExportJob] 도메인 객체를 [ExportJobResponse] DTO 로 변환한다.
         *
         * @param job 변환할 Export 작업 도메인 객체.
         * @return 변환된 응답 DTO.
         */
        fun from(job: ExportJob): ExportJobResponse =
            ExportJobResponse(
                jobId = job.id.value,
                status = job.status.name,
                progress = job.progress,
                rowCount = job.rowCount,
                format = job.format,
                errorCode = job.errorCode,
                downloadReady = job.downloadReady,
            )
    }
}

/**
 * Export 작업 접수 응답 DTO.
 *
 * `POST /api/v1/search/export-jobs` 의 202 응답으로 jobId 와 초기 status 를 반환한다.
 *
 * @property jobId 생성된 작업 식별자 UUID.
 * @property status 초기 상태 = "PENDING" 고정.
 */
data class ExportJobSubmitResponse(
    val jobId: UUID,
    val status: String,
)
