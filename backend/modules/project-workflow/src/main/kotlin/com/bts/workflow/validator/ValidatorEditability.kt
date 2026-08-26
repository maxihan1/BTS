// validator 편집 가능 여부 판정 — 거부 경로와 응답 변환이 공유하는 한 벌

package com.bts.workflow.validator

import com.bts.workflow.domain.spi.WorkflowValidator

/**
 * 이 API 로 만들거나 고칠 수 있는 validator 인지 판정한다.
 *
 * ### 인자가 `type`/`config` 가 아니라 **인스턴스**인 이유
 * 판정을 문자열 비교로 하면 팩토리의 `when` 분기와 각 구현체의 `override val type` 에 이은
 * **세 번째 사본**이 되고, `is` 검사와 달리 컴파일러가 그 사본을 지켜 주지 않는다. 인스턴스를
 * 받으면 클래스가 사라지거나 이름이 바뀔 때 컴파일이 먼저 깨진다.
 *
 * 또 호출자가 **이미 만들어 둔** 인스턴스를 넘기므로 판정을 위해 다시 만들지 않는다. 그래서
 * 응답의 `phase` 와 `editable` 이 같은 인스턴스에서 나오고, 인스턴스화가 실패한 행은 넘길 것이
 * 없으므로 `phase = null` 과 `editable = false` 가 자동으로 짝을 이룬다.
 *
 * ### 허용 목록이지 거절 목록이 아니다
 * 여기에 **명시 등재**된 셋만 편집 가능하고 나머지는 전부 거절이다. 거절 목록이었다면 팩토리에
 * 표현식·스크립트·템플릿·URL 을 받는 분기가 하나 늘어나는 것만으로 그 타입이 코드 변경 0 으로
 * 이 API 를 통해 쓰기 가능해진다(fail-open). 새 validator 를 편집 가능하게 하려면 이 목록에
 * 한 줄을 더해야 하고, 빠뜨리면 400 이지 조용한 허용이 아니다.
 *
 * [CustomExpressionValidator] 가 빠진 근거는 `expression/SpelEvaluator` 의 「일반 사용자가 API 를
 * 통해 임의 표현식을 전달하는 경로를 절대로 만들지 않는다」이고, Jira Cloud 내장 validator 9종에도
 * 자유 표현식 입력칸이 없다.
 *
 * @param validator 팩토리가 만든 validator 인스턴스.
 * @return 이 API 로 편집할 수 있는 타입이면 true.
 */
internal fun isEditable(validator: WorkflowValidator): Boolean =
    validator is RequiredFieldValidator ||
        validator is PermissionValidator ||
        validator is NotStatusCategoryValidator
