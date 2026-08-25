// validator 관리 API 요청/응답 DTO

package com.bts.workflow.validator.web

import com.bts.workflow.validator.ValidatorRow
import java.util.UUID

/**
 * validator 생성/수정 요청 바디 DTO.
 *
 * @property type validator 타입 식별자 (예: "RequiredField").
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서. 기본값 0.
 */
data class ValidatorRequest(
    val type: String,
    val config: Map<String, Any?> = emptyMap(),
    val displayOrder: Int = 0,
)

/**
 * validator 응답 DTO.
 *
 * `transitionId` 는 담지 않는다 — 경로가 이미 전환을 지목하므로 응답이 다시 알려 줄 이유가 없고,
 * 내부 식별자를 하나 덜 흘린다. `phase` 도 담지 않는다. 그 값은 구현체 속성이라 읽기 시점에
 * 팩토리로 인스턴스를 만들어야 하는데, 손으로 들어간 깨진 config 행 하나가 목록 전체를 500 으로
 * 만든다. 필드 추가는 하위호환이므로 화면이 실제로 필요할 때 넣는다.
 *
 * @property id validator UUID.
 * @property type validator 타입 식별자.
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서.
 */
data class ValidatorResponse(
    val id: UUID,
    val type: String,
    val config: Map<String, Any?>,
    val displayOrder: Int,
) {
    companion object {
        /**
         * [ValidatorRow] 를 [ValidatorResponse] 로 변환한다.
         *
         * @param row 도메인 행 객체.
         * @return 응답 DTO.
         */
        fun from(row: ValidatorRow): ValidatorResponse =
            ValidatorResponse(
                id = row.id,
                type = row.type,
                config = row.config,
                displayOrder = row.displayOrder,
            )
    }
}
