// 초안이 「받아 놓고 버리는 값」을 담지 못하게 막는 관문 — 저장과 발행이 함께 지난다

package com.bts.workflow.application

import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.repository.CatalogStatus

/**
 * 초안이 담은 **워크플로우 키**가 경로와 같은지 본다.
 *
 * `replaceDefinition` 은 `WORKFLOWS.KEY` 를 갱신하지 않으므로 이 값은 발행에서 쓰이지 않는다.
 * 그런데 `workflow_publications.definition` 스냅샷에는 그대로 기록돼, `software-default` 의 발행
 * 이력이 자신을 다른 워크플로우라고 주장하는 상태가 만들어진다 — 그 스냅샷은 되돌리기의 원본이다.
 *
 * 형제 `UpdateWorkflowRequest` 는 「이슈·자동화·검색이 문자열로 참조하는 식별자라 바뀌면 조용히
 * 끊긴다」는 이유로 `key` 필드를 **아예 없앴다.** 초안 API 가 그 구멍을 다시 열지 않게 한다.
 *
 * 비어 있으면 통과다 — 클라이언트가 안 보낸 것이고, 저장부가 경로 키로 채운다.
 *
 * @throws WorkflowInvalidRequestException 비어 있지 않은데 경로 키와 다를 때
 */
internal fun requireKeyMatchesPath(
    pathKey: String,
    definition: WorkflowDraftDefinition,
) {
    if (definition.key.isNotBlank() && definition.key != pathKey) {
        throw WorkflowInvalidRequestException(
            pathKey,
            "초안이 담은 워크플로우 키 '${definition.key}' 가 경로 '$pathKey' 와 다르다. " +
                "워크플로우 키는 이 API 로 바꿀 수 없다",
        )
    }
}

/**
 * 상태의 **표시 이름·카테고리를 카탈로그 값으로 갈아 끼운** 사본을 돌려준다.
 *
 * 「기본값으로 복원」이 쓴다. 시드 YAML 은 심을 당시의 이름을 들고 있는데, 그 뒤 관리자가 상태
 * API 로 이름을 바꾸면 둘이 갈린다. 그때 YAML 값을 그대로 초안에 실으면 저장은 되고
 * [requireStatesMatchCatalog] 가 **발행에서만** 막아, 관리자가 만들지도 않은 값 때문에 발행이
 * 불가능해지고 고칠 방법도 없다 — 이 저장소가 「막다른 길」이라 부르는 상태다.
 *
 * YAML 의 권위는 워크플로우의 **구조**(어떤 상태를 쓰고 어떤 전환이 있는가)다. 표시 이름의 정본은
 * 전역 카탈로그 하나이므로, 구조는 YAML 에서 이름은 카탈로그에서 가져온다.
 *
 * 카탈로그에 없는 키는 그대로 둔다 — 그쪽은 발행의 「상태를 만들지 않는다」 판정이 400 으로 막는다.
 */
internal fun WorkflowDraftDefinition.withCatalogStateLabels(
    repository: com.bts.workflow.repository.WorkflowPublishRepository,
): WorkflowDraftDefinition {
    val catalog = repository.findCatalogStatuses(states.map { it.key })
    return copy(
        states =
            states.map { state ->
                val row = catalog[state.key] ?: return@map state
                state.copy(name = row.name, category = row.category)
            },
    )
}

/**
 * 초안의 상태 **이름·카테고리**가 전역 카탈로그와 같은지 본다.
 *
 * ### 왜 받아만 두면 안 되나
 * 발행의 편성 INSERT 는 `workflow_id` · `status_id` · `display_order` 세 컬럼만 쓴다. 이름과
 * 카테고리는 읽기 경로가 `statuses` 에서 가져오므로 **초안의 값은 어디에도 쓰이지 않는다.**
 * 그런데 `workflow_publications.definition` 스냅샷에는 그대로 실려, 관리자가 이름을 고치고
 * 발행하면 200 을 받고 라이브는 그대로인데 **감사 기록만 일어나지 않은 변경을 증언한다.**
 *
 * 받아서 버릴 바에는 거절한다(절대 규칙 16). 상태 이름·카테고리 변경은 `PATCH /api/v1/statuses`
 * 의 일이고, 그 경로를 지나야 카탈로그의 이름 유일 규칙(V203 `uq_statuses_lower_name`)도 지켜진다.
 *
 * 카탈로그에 없는 키는 여기서 보지 않는다 — 그쪽은 호출부의 「상태를 만들지 않는다」 판정이 맡는다.
 *
 * @throws WorkflowInvalidRequestException 이름이나 카테고리가 카탈로그와 다를 때
 */
internal fun requireStatesMatchCatalog(
    pathKey: String,
    definition: WorkflowDraftDefinition,
    catalog: Map<String, CatalogStatus>,
) {
    definition.states.forEach { state ->
        val row = catalog[state.key] ?: return@forEach

        if (state.name != row.name) {
            throw WorkflowInvalidRequestException(
                pathKey,
                "상태 '${state.key}' 의 이름은 카탈로그가 정한다 (카탈로그 '${row.name}' · 초안 " +
                    "'${state.name}'). 이름을 바꾸려면 상태 API 를 쓸 것",
            )
        }
        if (state.category != row.category) {
            throw WorkflowInvalidRequestException(
                pathKey,
                "상태 '${state.key}' 의 카테고리는 카탈로그가 정한다 (카탈로그 '${row.category}' · 초안 " +
                    "'${state.category}'). 카테고리를 바꾸려면 상태 API 를 쓸 것",
            )
        }
    }
}
