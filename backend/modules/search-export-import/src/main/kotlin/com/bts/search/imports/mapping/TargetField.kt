// FR-IM-02 Import 매핑에서 선택 가능한 BTS 대상 필드 카탈로그 11종 — key·label·required·multi 속성 + IGNORE 센티널

package com.bts.search.imports.mapping

/**
 * FR-IM-02(매핑 UI)에서 소스(CSV/JSON) 필드가 매핑될 수 있는 BTS 대상 필드 카탈로그.
 *
 * 카탈로그는 [com.bts.search.imports.parse.ParsedImportRow] 의 코어 스칼라/다중값 필드 11종만
 * 다룬다 — 댓글([com.bts.search.imports.parse.ParsedImportComment])·worklog
 * ([com.bts.search.imports.parse.ParsedImportWorklog])·첨부
 * ([com.bts.search.imports.parse.ParsedImportAttachment])·변경이력
 * ([com.bts.search.imports.parse.ParsedImportChangeGroup])은 JSON 전용 복합 구조라 매핑 UI 대상에서
 * 제외한다(임의 CSV 헤더 하나를 이런 중첩 구조에 매핑할 방법이 없다).
 *
 * [required] 는 이 대상 필드가 없으면 행 전체를 만들 수 없다는 뜻이다(현재 summary 만 해당).
 * [multi] 는 소스 셀 하나가 콤마/세미콜론으로 분리된 다중값을 담을 수 있다는 뜻이다
 * ([com.bts.search.imports.parse.ImportRowParser] 의 라벨/컴포넌트/버전 분리 규칙과 동일한 의미).
 *
 * @property key 매핑 payload/카탈로그 응답에 쓰는 직렬화 키(소문자 camelCase). 프론트 매핑 UI 와
 *   API 계약의 단일 출처다.
 * @property label 매핑 UI에 노출할 한국어 표시명.
 * @property required 이 필드가 비어 있으면 행을 만들 수 없는 필수 필드인지 여부.
 * @property multi 소스 셀 하나가 다중값(콤마/세미콜론 분리)을 담을 수 있는지 여부.
 */
enum class TargetField(
    val key: String,
    val label: String,
    val required: Boolean,
    val multi: Boolean,
) {
    /** [com.bts.search.imports.parse.ParsedImportRow.summary] 에 대응. */
    SUMMARY(key = "summary", label = "제목", required = true, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.description] 에 대응. */
    DESCRIPTION(key = "description", label = "설명", required = false, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.typeName] 에 대응. */
    TYPE(key = "type", label = "유형", required = false, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.priorityName] 에 대응. */
    PRIORITY(key = "priority", label = "우선순위", required = false, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.reporterEmail] 에 대응. */
    REPORTER(key = "reporter", label = "보고자", required = false, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.assigneeEmail] 에 대응. */
    ASSIGNEE(key = "assignee", label = "담당자", required = false, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.labels] 에 대응. */
    LABELS(key = "labels", label = "라벨", required = false, multi = true),

    /** [com.bts.search.imports.parse.ParsedImportRow.componentNames] 에 대응. */
    COMPONENT(key = "component", label = "컴포넌트", required = false, multi = true),

    /** [com.bts.search.imports.parse.ParsedImportRow.statusName] 에 대응. */
    STATUS(key = "status", label = "상태", required = false, multi = false),

    /** [com.bts.search.imports.parse.ParsedImportRow.fixVersionNames] 에 대응. */
    FIX_VERSION(key = "fixVersion", label = "수정 버전", required = false, multi = true),

    /** [com.bts.search.imports.parse.ParsedImportRow.affectsVersionNames] 에 대응. */
    AFFECTS_VERSION(key = "affectsVersion", label = "영향 버전", required = false, multi = true),
    ;

    companion object {
        /** 매핑 UI에서 "이 소스 필드는 매핑하지 않음"을 표현하는 센티널 키. 카탈로그 11종에는 포함되지 않는다. */
        const val IGNORE_KEY = "IGNORE"

        /**
         * 대상 필드 키 문자열로 카탈로그 항목을 찾는다. 대소문자 엄격 일치.
         *
         * [IGNORE_KEY] 는 카탈로그 항목이 아니므로 이 함수는 null 을 반환한다 — IGNORE 인식은
         * [isIgnoreKey] 를 사용한다.
         *
         * @param key [TargetField.key] 후보 문자열
         * @return 일치하는 카탈로그 항목, 없으면 null
         */
        fun fromKey(key: String): TargetField? = entries.find { it.key == key }

        /**
         * 주어진 키가 IGNORE 센티널([IGNORE_KEY])인지 확인한다.
         *
         * @param key 매핑 payload 의 대상 필드 키 문자열
         * @return IGNORE 센티널이면 true
         */
        fun isIgnoreKey(key: String): Boolean = key == IGNORE_KEY
    }
}
