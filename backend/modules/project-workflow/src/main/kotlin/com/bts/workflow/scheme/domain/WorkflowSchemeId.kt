// 워크플로우 스킴 식별자 VO — workflow_schemes.id BIGINT 매핑
package com.bts.workflow.scheme.domain

/**
 * 워크플로우 스킴 식별자.
 *
 * DB 테이블 `workflow_schemes`의 `id BIGINT` 컬럼에 1:1 매핑되는 값 객체(VO)다.
 * 값 클래스(`@JvmInline value class`)로 선언하여 런타임에 박싱 비용 없이
 * Long 과 동일하게 처리되면서, 타입 안전성을 제공한다.
 *
 * equals/hashCode 는 내부 [value] Long 기반으로 Kotlin 컴파일러가 자동 생성한다.
 *
 * @property value `workflow_schemes.id BIGINT` 컬럼 원본 값. 1 이상의 자동 증가 양의 정수.
 */
@JvmInline
value class WorkflowSchemeId(val value: Long)
