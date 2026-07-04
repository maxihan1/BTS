// Import 값매핑(F2) 대상 필드 3종 — 상태/유형/우선순위
package com.bts.search.imports.mapping

/**
 * Import 값매핑(F2)의 대상이 되는 3종 필드.
 *
 * 파싱된 import 행의 상태/유형/우선순위 이름 필드 각각에 대응하며,
 * `import_value_mappings`(후속 Task) 저장 키의 일부로 쓰인다.
 */
enum class ValueTargetField {
    STATUS,
    TYPE,
    PRIORITY,
}
