// 비동기 Import 작업 생명주기 상태 enum — AWAITING_MAPPING, PENDING, RUNNING, COMPLETED, FAILED

package com.bts.search.imports.job.domain

/**
 * [ImportJob] 상태 머신의 상태.
 *
 * FR-IM-02(매핑 UI) 도입으로 analyze → map → run 2단계 흐름이 되어, 파일 분석 직후에는
 * AWAITING_MAPPING 으로 시작한다 — 사용자가 소스 필드 ↔ 대상 필드 매핑을 확정해야 PENDING 으로
 * 전이한다. 이후 워커가 클레임하면 RUNNING 으로 전이한다. 파싱/생성이 모두 끝나면 COMPLETED 로,
 * 인프라 오류 또는 처리 한도 초과 시 FAILED 로 전이한다. COMPLETED 와 FAILED 는 종단(terminal) 상태다.
 *
 * `export` BC [com.bts.search.export.job.domain.ExportJobStatus] 의 4-상태(PENDING/RUNNING/
 * COMPLETED/FAILED) 를 그대로 유지하되, import 전용으로 AWAITING_MAPPING 을 앞에 추가한 5-상태다.
 *
 * @see ImportJob.status 도메인 집계체 상태 필드
 * @see ImportJob 도메인 집계체
 */
enum class ImportJobStatus {
    AWAITING_MAPPING,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
