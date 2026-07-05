// 프로필 PATCH 요청 DTO — JsonNode 기반 3-state(부재/명시 null/명시 값) 표현 (FR-PR-01 Task 5, C4)

package com.atlas.bts.identity.dto

import com.fasterxml.jackson.databind.JsonNode

/**
 * PATCH `/api/v1/users/me/profile` 요청 바디.
 *
 * ## 3-state 표현 (C4 — issue-tracking `DatePatch`/`JsonNullable` 기법 미러, 모듈 격리로 타입은 자체 정의)
 * `org.openapitools:jackson-databind-nullable`([JsonNullable][org.openapitools.jackson.nullable.JsonNullable])
 * 은 issue-tracking 전용 의존성이라 identity-access 모듈에는 없다(모듈 경계 — C4). 대신 코어 Jackson
 * [JsonNode] 만으로 동일한 3-state 를 표현한다.
 *
 * 각 필드를 [JsonNode] 로 선언하면 Jackson 역직렬화 시 다음과 같이 구분된다.
 *  - JSON 바디에 필드가 아예 없으면 Kotlin 기본값(`null`)이 그대로 유지된다 → **부재**(미변경).
 *  - JSON 바디에 `"field": null` 로 명시되면 Jackson 이 [com.fasterxml.jackson.databind.node.NullNode]
 *    인스턴스를 채운다 → **명시 null**([JsonNode.isNull] true).
 *  - 그 외 값이 오면 해당 값을 담은 [JsonNode] → **명시 값**([JsonNode.asText]).
 *
 * 컨트롤러의 `toRequiredField`/`toNullableField` 가 이 3-state 를
 * [ProfilePatchField][com.atlas.bts.identity.profile.ProfilePatchField] 로 변환한다.
 *
 * @property displayName 표시 이름. 부재=미변경, 명시=검증 후 반영(공백/255자 초과 시 서비스가 400).
 * @property timezone 타임존(IANA). 부재=미변경, 명시=검증 후 반영(유효하지 않으면 서비스가 400).
 * @property department 부서. 부재=미변경, 명시 null=삭제, 명시 값=반영.
 */
data class ProfilePatchRequest(
    val displayName: JsonNode? = null,
    val timezone: JsonNode? = null,
    val department: JsonNode? = null,
)
