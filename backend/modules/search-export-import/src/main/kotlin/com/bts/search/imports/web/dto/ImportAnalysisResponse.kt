// Import 파일 분석(analyze) 응답 DTO — 소스 필드/샘플 행/대상 필드 카탈로그 (FR-IM-02 PR-A Task 8)

package com.bts.search.imports.web.dto

import com.bts.search.imports.job.application.ImportAnalysisResult
import com.bts.search.imports.mapping.TargetField
import java.util.UUID

/**
 * `POST /api/v1/imports/analyze` 응답 DTO.
 *
 * analyze는 비동기 워커를 거치지 않는 동기 완결 흐름이라([com.bts.search.imports.job.application.ImportJobService.analyze]
 * KDoc §분석 흐름 참조) 202 Accepted가 아닌 200 OK로 분석 결과를 즉시 반환한다.
 *
 * [sourceFields]는 매핑 UI가 드롭다운으로 노출할 소스(CSV 헤더 또는 JSON canonical) 필드 목록이고,
 * [targetFields]는 요청과 무관하게 고정된 [TargetField] 카탈로그 11종이다 — analyze 요청마다 동일한
 * 값이지만, 프론트가 별도 API 호출 없이 매핑 UI를 그릴 수 있도록 이 응답에 매번 포함한다.
 *
 * @property jobId 분석 단계에서 생성된 Import 작업 식별자.
 * @property status 항상 `"AWAITING_MAPPING"`.
 * @property format 정규화된 업로드 형식(`"CSV"` 또는 `"JSON"`).
 * @property sourceFields 소스 필드 이름 목록(원본 순서).
 * @property sampleRows CSV 미리보기 샘플 행(각 행은 셀 목록, 최대 5건). JSON은 항상 빈 목록.
 * @property targetFields BTS 대상 필드 카탈로그([TargetField.entries]) 11종.
 */
data class ImportAnalysisResponse(
    val jobId: UUID,
    val status: String,
    val format: String,
    val sourceFields: List<SourceFieldItem>,
    val sampleRows: List<List<String>>,
    val targetFields: List<TargetFieldItem>,
) {
    /** 소스 필드 항목 — 현재는 이름만 담지만, forward-compatible 확장(타입 힌트 등)을 대비해 객체로 감쌌다. */
    data class SourceFieldItem(val name: String)

    /** [TargetField] 카탈로그 항목의 직렬화 형태. */
    data class TargetFieldItem(
        val key: String,
        val label: String,
        val required: Boolean,
        val multi: Boolean,
    )

    companion object {
        /**
         * [ImportAnalysisResult]를 [ImportAnalysisResponse]로 변환한다.
         *
         * @param result [com.bts.search.imports.job.application.ImportJobService.analyze] 반환 결과.
         * @return 변환된 응답 DTO.
         */
        fun from(result: ImportAnalysisResult): ImportAnalysisResponse =
            ImportAnalysisResponse(
                jobId = result.job.id.value,
                status = result.job.status.name,
                format = result.job.format,
                sourceFields = result.sourceFields.map { SourceFieldItem(it) },
                sampleRows = result.sampleRows,
                targetFields =
                    TargetField.entries.map {
                        TargetFieldItem(key = it.key, label = it.label, required = it.required, multi = it.multi)
                    },
            )
    }
}
