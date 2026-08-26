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
 * 내부 식별자를 하나 덜 흘린다.
 *
 * ### `phase` 는 응답이 준다 — 소비자가 두 번째 목록을 만들지 않도록
 * `phase` 는 **규칙이 전환 버튼을 감추는지(AVAILABILITY) 눌렀을 때 막는지(EXECUTION)** 를 가르는
 * 값이다. 4종 중 [com.bts.workflow.validator.RequiredFieldValidator] 만 EXECUTION 을 명시 override
 * 하고 나머지 3종은 SPI 기본값 AVAILABILITY 를 상속하므로 **`type` 문자열만으로는 판별할 수 없다.**
 * 응답이 주지 않으면 소비자 쪽에 `type → phase` 표가 생기는데, 그 표는 팩토리·구현체와 갈려도
 * 아무도 대조하지 않는다.
 *
 * 값의 출처는 팩토리가 만든 **인스턴스의 속성**
 * ([com.bts.workflow.domain.spi.WorkflowValidator.phase]) 하나뿐이다.
 *
 * `null` 은 「phase 가 없다」가 아니라 **「그 행으로는 인스턴스를 만들 수 없어 phase 를 알 수 없다」**
 * 이다 — 손으로 넣은 깨진 config · 팩토리에서 사라진 type · config 키 변경. 실패는 행 단위로
 * 갇히므로 그 행만 `null` 이고 목록 전체는 200 이다.
 *
 * ### `editable` 도 같은 인스턴스에서 나온다
 * 이 API 로 만들고 고칠 수 있는 type 인지다. 화면이 이 값을 안 받으면 편집 가능한 type 목록을
 * 자기 코드에 베끼게 되고, 그 사본은 백엔드가 네 번째 종류를 허용하는 날 조용히 낡는다 —
 * 사용자는 고칠 수 있는 규칙을 회색으로 보거나 못 고치는 규칙을 눌렀다가 400 을 받는다.
 * 판정의 정본은 [com.bts.workflow.validator.isEditable] 하나이고 생성/수정의 400 도 같은 함수를 탄다.
 *
 * **`phase = null` 이면 항상 `editable = false`** 다 — 인스턴스를 만들 수 없는 행은 편집 가능
 * 여부를 판정할 근거도 없다. 두 값은 같은 인스턴스에서 나오므로 짝이 어긋날 수 없다.
 *
 * @property id validator UUID.
 * @property type validator 타입 식별자.
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서.
 * @property phase 평가 시점 — `"AVAILABILITY"` 또는 `"EXECUTION"`. 인스턴스화 실패 시 `null`.
 * @property editable 이 API 로 편집할 수 있는 type 이면 true. `phase` 가 `null` 이면 항상 false.
 */
data class ValidatorResponse(
    val id: UUID,
    val type: String,
    val config: Map<String, Any?>,
    val displayOrder: Int,
    val phase: String?,
    val editable: Boolean,
) {
    companion object {
        /**
         * [ValidatorRow] 를 [ValidatorResponse] 로 변환한다.
         *
         * `phase` 와 `editable` 을 여기서 계산하지 않고 **받는다** — 인스턴스를 한 번만 만들고
         * 그 실패를 행 단위로 가두는 책임은 호출자(`ValidatorController`)에 있고, DTO 는 팩토리를
         * 알지 않는다.
         *
         * @param row 도메인 행 객체.
         * @param phase 팩토리 인스턴스에서 읽은 평가 시점 이름. 인스턴스화 실패 시 `null`.
         * @param editable 같은 인스턴스로 판정한 편집 가능 여부. `phase` 가 `null` 이면 false 다.
         * @return 응답 DTO.
         */
        fun from(
            row: ValidatorRow,
            phase: String?,
            editable: Boolean,
        ): ValidatorResponse =
            ValidatorResponse(
                id = row.id,
                type = row.type,
                config = row.config,
                displayOrder = row.displayOrder,
                phase = phase,
                editable = editable,
            )
    }
}
