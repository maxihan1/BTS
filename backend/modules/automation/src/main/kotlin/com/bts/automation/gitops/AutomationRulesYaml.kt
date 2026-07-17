// GitOps YAML 스키마 v1의 Jackson wire DTO — 필드 선언 순서 = 방출 순서 (FR-AT-06 Task 1)

package com.bts.automation.gitops

import com.bts.automation.domain.ActionType
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * GitOps YAML 문서 최상위 구조.
 *
 * 필드 선언 순서가 그대로 YAML 방출 순서다(GitOps git diff 안정성을 위한 결정적 순서 요구,
 * Jackson Kotlin 모듈은 주 생성자 파라미터 순서를 직렬화 순서로 사용한다).
 *
 * @property version YAML 스키마 버전. 현재 [AutomationYamlCodec.SCHEMA_VERSION](=1) 고정 — 다른 값이면
 *   [AutomationYamlCodec.fromYaml] 이 거부한다.
 * @property projectKey 규칙이 속한 프로젝트 키. import 시 경로의 `{projectKey}` 와 일치해야 한다(상위
 *   계층 책임 — 이 codec은 값을 그대로 노출만 한다).
 * @property rules 규칙 목록. 빈 리스트 허용(EC5 — `rules: []` 는 유효한 문서).
 */
data class AutomationRulesYaml(
    val version: Int = 0,
    val projectKey: String = "",
    val rules: List<YamlRule> = emptyList(),
)

/**
 * 규칙 1건의 YAML 표현.
 *
 * @property id 규칙 식별자. export는 항상 채운다. import는 생략 시 신규 UUID로 생성된다(id 기준 upsert,
 *   FR3 — 실제 생성 로직은 이 codec 밖의 import 서비스 책임).
 * @property name 규칙 표시 이름.
 * @property enabled 활성화 여부. 기본값 `true`(사람이 손으로 YAML을 작성할 때 생략 가능).
 * @property actorUserId 액션 실행 주체(rule actor). 생략 시 import 호출자로 기본(import 서비스 책임).
 * @property trigger 트리거 타입 + 설정.
 * @property condition 조건 게이트(JSONLogic 부분집합) YAML 객체. `null`이면 조건 없음(생성) 또는 기존
 *   조건 유지(갱신 — 비파괴적 upsert 시맨틱, EC6). [JsonInclude.Include.NON_NULL]로 `null`일 때 export
 *   출력에서 키 자체를 생략한다(스키마 예시와 일치하는 깔끔한 출력).
 * @property actions 순차 실행 액션 목록. 빈 리스트 허용.
 */
data class YamlRule(
    val id: UUID? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val actorUserId: UUID? = null,
    val trigger: YamlTrigger = YamlTrigger(),
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val condition: Map<String, Any?>? = null,
    val actions: List<YamlAction> = emptyList(),
)

/**
 * 트리거 타입 + 설정.
 *
 * [config]는 사람이 읽는 YAML 객체로 방출/입력되고, 도메인의 `triggerConfig: String`(JSON 문자열)과
 * 상호 변환된다 — wire 비대칭 흡수는 [AutomationYamlCodec] 이 담당한다.
 *
 * @property type 트리거 타입 6종([TriggerType]).
 * @property config 트리거별 설정. 빈 객체(`{}`) 허용.
 */
data class YamlTrigger(
    val type: TriggerType = TriggerType.ISSUE_CREATED,
    val config: Map<String, Any?> = emptyMap(),
)

/**
 * 액션 타입 + 설정.
 *
 * [config]는 사람이 읽는 YAML 객체로 방출/입력되고, 도메인의 action_config JSON 문자열과 상호
 * 변환된다 — wire 비대칭 흡수는 [AutomationYamlCodec] 이 담당한다.
 *
 * @property type 액션 타입 5종([ActionType]).
 * @property config 액션별 설정. 빈 객체(`{}`) 허용.
 */
data class YamlAction(
    val type: ActionType = ActionType.SET_FIELD,
    val config: Map<String, Any?> = emptyMap(),
)
