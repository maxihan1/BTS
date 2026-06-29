// 가젯 카탈로그 응답 DTO — GadgetType.catalog() 단일 출처에서 매핑하여 drift 차단

package com.bts.notification.dashboard.web.dto

import com.bts.notification.dashboard.domain.ConfigFieldDescriptor
import com.bts.notification.dashboard.domain.GadgetCatalogEntry
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 가젯 카탈로그 전체 응답 DTO.
 *
 * @param gadgets 카탈로그 가젯 목록 (category→type 순 안정 정렬)
 */
data class GadgetCatalogResponse(
    val gadgets: List<GadgetCatalogEntryDto>,
)

/**
 * 가젯 카탈로그 단건 DTO.
 *
 * [GadgetCatalogEntry] 도메인 객체에서 변환한다.
 * category 는 [com.bts.notification.dashboard.domain.GadgetCategory] 의 name 문자열로 직렬화된다.
 *
 * @param type 가젯 직렬화 키 (소문자 snake_case)
 * @param category 가젯 대분류 문자열 (ISSUE / STATIC / CHART / ACTIVITY)
 * @param label 표시용 레이블
 * @param enabled MVP 활성 여부 (false 는 미래 기능)
 * @param configFields config 필드 디스크립터 DTO 목록
 */
data class GadgetCatalogEntryDto(
    val type: String,
    val category: String,
    val label: String,
    val enabled: Boolean,
    val configFields: List<ConfigFieldDto>,
) {
    companion object {
        /**
         * 도메인 [GadgetCatalogEntry] 를 응답 DTO 로 변환한다.
         *
         * @param entry 변환 대상 도메인 객체
         * @return 직렬화 준비된 DTO
         */
        fun from(entry: GadgetCatalogEntry): GadgetCatalogEntryDto =
            GadgetCatalogEntryDto(
                type = entry.type,
                category = entry.category.name,
                label = entry.label,
                enabled = entry.enabled,
                configFields = entry.configFields.map { ConfigFieldDto.from(it) },
            )
    }
}

/**
 * config 필드 디스크립터 DTO.
 *
 * nullable 제약 필드는 [JsonInclude.Include.NON_NULL] 로 직렬화 시 생략한다.
 * 재귀 구조인 [itemSchema] 도 동일 DTO 타입을 사용하므로 단일 출처가 보장된다.
 *
 * @param key 필드 키
 * @param type 필드 타입 문자열 (STRING / INT / UUID / ENUM / ARRAY / URL)
 * @param required 필수 여부
 * @param minLength 최소 길이 (STRING 타입, null 이면 생략)
 * @param maxLength 최대 길이 (STRING·URL 타입, null 이면 생략)
 * @param min 최솟값 (INT 타입, null 이면 생략)
 * @param max 최댓값 (INT 타입, null 이면 생략)
 * @param enumValues 허용 값 집합 (ENUM 타입, null 이면 생략)
 * @param itemSchema 배열 항목 스키마 (ARRAY 타입, null 이면 생략)
 * @param minItems 최소 항목 수 (ARRAY 타입, null 이면 생략)
 * @param maxItems 최대 항목 수 (ARRAY 타입, null 이면 생략)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConfigFieldDto(
    val key: String,
    val type: String,
    val required: Boolean,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val min: Int? = null,
    val max: Int? = null,
    val enumValues: Set<String>? = null,
    val itemSchema: List<ConfigFieldDto>? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
) {
    companion object {
        /**
         * 도메인 [ConfigFieldDescriptor] 를 DTO 로 변환한다.
         *
         * itemSchema 는 재귀 변환한다.
         *
         * @param descriptor 변환 대상 도메인 디스크립터
         * @return 직렬화 준비된 DTO
         */
        fun from(descriptor: ConfigFieldDescriptor): ConfigFieldDto =
            ConfigFieldDto(
                key = descriptor.key,
                type = descriptor.type.name,
                required = descriptor.required,
                minLength = descriptor.minLength,
                maxLength = descriptor.maxLength,
                min = descriptor.min,
                max = descriptor.max,
                enumValues = descriptor.enumValues,
                itemSchema = descriptor.itemSchema?.map { from(it) },
                minItems = descriptor.minItems,
                maxItems = descriptor.maxItems,
            )
    }
}
