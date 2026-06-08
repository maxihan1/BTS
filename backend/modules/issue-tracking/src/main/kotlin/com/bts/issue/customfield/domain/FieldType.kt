// 커스텀 필드가 가질 수 있는 데이터 타입을 나타내는 열거형
package com.bts.issue.customfield.domain

/**
 * 커스텀 필드 타입 10종.
 *
 * 확장 가능한 구조로 설계되어, 후속에 USER/GROUP/VERSION/COMPONENT 등의 타입을
 * 열거값 추가만으로 도입할 수 있다.
 * 기존 타입을 제거하거나 재명명하면 기존 필드 데이터가 깨지므로 금지한다.
 *
 * @property isSelectType 이 타입이 옵션(선택지) 목록을 필요로 하는지 여부.
 *   true 인 타입에 옵션이 없으면 [InvalidFieldDefinitionException] 이 발생한다.
 */
enum class FieldType(val isSelectType: Boolean) {
    /** 짧은 텍스트 입력 (한 줄). */
    SHORT_TEXT(isSelectType = false),

    /** 긴 텍스트 입력 (여러 줄). */
    LONG_TEXT(isSelectType = false),

    /** 숫자 입력. */
    NUMBER(isSelectType = false),

    /** 날짜 입력 (ISO 8601 date). */
    DATE(isSelectType = false),

    /** 날짜+시간 입력 (ISO 8601 datetime). */
    DATETIME(isSelectType = false),

    /** 단일 선택 드롭다운. 옵션 목록 필요. */
    SINGLE_SELECT(isSelectType = true),

    /** 다중 선택 체크박스 그룹. 옵션 목록 필요. */
    MULTI_SELECT(isSelectType = true),

    /** 단일 참/거짓 체크박스. 옵션 불필요. */
    CHECKBOX(isSelectType = false),

    /** 라디오 버튼 그룹. 옵션 목록 필요. */
    RADIO(isSelectType = true),

    /** URL 입력. */
    URL(isSelectType = false),
}
