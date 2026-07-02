// 비동기 Import 작업 생명주기 상태 enum — PENDING, RUNNING, COMPLETED, FAILED (ExportJobStatus 동형)

package com.bts.search.imports.job.domain

/**
 * [ImportJob] 상태 머신의 상태.
 *
 * 접수 시 PENDING 으로 시작하고, 워커가 클레임하면 RUNNING 으로 전이한다.
 * 파싱/생성이 모두 끝나면 COMPLETED 로, 인프라 오류 또는 처리 한도 초과 시 FAILED 로 전이한다.
 * COMPLETED 와 FAILED 는 종단(terminal) 상태다.
 *
 * `export` BC [com.bts.search.export.job.domain.ExportJobStatus] 와 동일한 4-상태 이름을 미러한다.
 *
 * @see ImportJob.status 도메인 집계체 상태 필드
 * @see ImportJob 도메인 집계체
 */
enum class ImportJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
