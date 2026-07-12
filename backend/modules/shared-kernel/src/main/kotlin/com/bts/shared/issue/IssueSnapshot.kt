// IssueSnapshotPort 조회 결과 VO — 자동화 조건 평가용 이슈 필드 스냅샷 (FR-AT-03)
package com.bts.shared.issue

import java.util.UUID

/**
 * [IssueSnapshotPort.fetch] 가 반환하는 이슈 필드 값 스냅샷 VO.
 *
 * automation BC 의 조건 평가기(`ConditionEvaluator`)가 조건 표현식의 `{"var": "issue.*"}` 참조를
 * 이 VO 의 필드로 해석한다. 필드 목록은 automation `Condition.FIELD_WHITELIST`(`issue.key` 등)와
 * 1:1 대응한다 — 화이트리스트에 없는 필드는 애초에 조건 표현식 파싱 단계(automation BC)에서 거부되므로
 * 이 VO 자체에는 화이트리스트 강제 로직이 없다.
 *
 * ### Jackson 비의존, 순수 계약
 *
 * shared-kernel 은 순수 계약 모듈로 특정 라이브러리 타입을 포트 계약에 노출하지 않는다 —
 * [SetFieldCommand]`.value: String?`(JSON 인코딩 문자열, Jackson 비의존) 설계 원칙과
 * 동형이다. 이 VO 는 원시 타입(String/Int/UUID/List)만 사용한다.
 *
 * ### 표현 확정 — 조건 표현식이 참조하는 값의 정확한 형태
 *
 * 아래 세 필드는 값의 "형태"가 계약이다 — 조건 표현식이 이 형태를 그대로 참조하므로, 표현이 바뀌면
 * 기존에 저장된 조건 표현식이 깨진다.
 * - [type] — 이슈 **타입 이름** 문자열(예: `"Bug"`, `"Task"`). 타입 ID(UUID)가 아니다.
 *   조건 예: `{"==": [{"var":"issue.type"}, "Bug"]}`.
 * - [status] — 워크플로우 **상태 키**(stateKey, 예: `"in_progress"`, `"done"`). 사용자에게 보이는
 *   상태 표시명(예: "진행 중")이 아니다 — 표시명은 워크플로우 정의에 따라 프로젝트마다 다를 수 있어
 *   조건 비교 기준으로 부적합하다. 조건 예: `{"in": [{"var":"issue.status"}, ["done","closed"]]}`.
 * - [priority] — 1~5 범위의 **숫자**. FR-AT-02 `SetFieldCommand` 로 우선순위를 설정할 때 쓰는 값과
 *   동일한 표현이다(대칭 — 쓰기 경로와 읽기 경로가 같은 숫자 표현을 공유). 조건 예:
 *   `{">=": [{"var":"issue.priority"}, 3]}`.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`. `{"var":"issue.key"}` 에 대응.
 * @property projectKey 이슈가 속한 프로젝트 키. 예: `"PROJ"`. `{"var":"issue.projectKey"}` 에 대응.
 * @property type 이슈 타입 이름. 타입이 지정되지 않았거나 조회 시점에 확인할 수 없으면 null.
 *   `{"var":"issue.type"}` 에 대응. 표현 확정은 클래스 KDoc 참조.
 * @property status 워크플로우 상태 키(stateKey). 모든 이슈는 워크플로우 상태를 가지므로 null 이
 *   아니다. `{"var":"issue.status"}` 에 대응. 표현 확정은 클래스 KDoc 참조.
 * @property priority 우선순위 숫자(1~5). 우선순위가 지정되지 않았으면 null.
 *   `{"var":"issue.priority"}` 에 대응. 표현 확정은 클래스 KDoc 참조.
 * @property assigneeId 담당자 UUID. 담당자가 없으면 null. `{"var":"issue.assignee"}` 에 대응.
 * @property reporterId 리포터 UUID. `{"var":"issue.reporter"}` 에 대응.
 * @property labels 라벨 이름 목록. 라벨이 없으면 빈 리스트. `{"var":"issue.labels"}` 에 대응.
 * @property summary 이슈 요약(제목). `{"var":"issue.summary"}` 에 대응.
 * @see IssueSnapshotPort
 */
data class IssueSnapshot(
    val key: String,
    val projectKey: String,
    val type: String?,
    val status: String,
    val priority: Int?,
    val assigneeId: UUID?,
    val reporterId: UUID?,
    val labels: List<String>,
    val summary: String,
)
