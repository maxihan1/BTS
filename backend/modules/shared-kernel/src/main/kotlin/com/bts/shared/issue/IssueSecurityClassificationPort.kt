// 이슈 보안등급 여부 판정 cross-BC 포트 — Slack 채널 브로드캐스트 제외 게이트 전용 (FR-SL-06 PR-B Task 1)

package com.bts.shared.issue

/**
 * 이슈가 보안등급(security level)으로 제한되어 있는지 판정하는 cross-BC 읽기 포트 — 전 BC 공용.
 *
 * slack-integration BC 의 채널 브로드캐스트(FR-SL-06 PR-B)가 이슈 이벤트를 매핑된 Slack 채널로
 * 팬아웃하기 전, 이 포트로 "이 이슈가 보안등급 제한 대상인가"를 물어 제한 대상이면 브로드캐스트
 * 대상에서 통째로 제외한다. prod 실판정 adapter 는 issue-tracking 이
 * [com.bts.issue.adapter.IssueSecurityClassificationAdapter] (`@Profile("prod")`)로 제공한다.
 *
 * ## 왜 새 포트가 필요한가 — [IssueVisibilityPort]/[com.bts.shared.permission.IssueSecurityDirectory] 와 목적 상이
 *
 * shared-kernel 에는 이미 이슈 가시성 관련 포트가 있다.
 * - [IssueVisibilityPort] — **뷰어별** VIEW 가시성(권한 매트릭스 + 보안등급 결합)을 판정한다.
 *   "이 후보 사용자들 중 누가 이 이슈를 볼 수 있는가" 라는 **다자 뷰어** 질문에 답한다.
 * - [com.bts.shared.permission.IssueSecurityDirectory] — 보안등급 자체의 스킴 소속 여부·행위자가
 *   접근 가능한 보안등급 목록을 조회한다. 이슈 생성/수정 시 등급 유효성 검증에 쓰인다.
 *
 * 채널 브로드캐스트는 **뷰어가 없는 팬아웃**이다 — 채널 안의 누가 메시지를 볼지 미리 알 수 없으므로
 * (Slack 채널 멤버는 BTS 프로젝트 멤버십과 별개로 관리된다), 뷰어별 판정([IssueVisibilityPort])은
 * 적용할 수 없다. 대신 "이슈 자체가 보안등급으로 제한되어 있는가"라는 **뷰어 무관(viewer-agnostic)**
 * 이진 질문만 필요하다 — 제한되어 있으면 어떤 채널로도 보내지 않는다(통째 제외). 이 포트는 그
 * 목적 하나만을 위한 최소 계약이다.
 *
 * ## fail-closed — default 구현 없음
 *
 * [isSecurityRestricted] 는 default 구현이 없다. adapter 부재 시 Spring 부팅이
 * `NoSuchBeanDefinitionException` 으로 실패해야 한다 — allow-all(항상 false) default 는 보안등급
 * 이슈가 채널로 새는 보안 사고로 직결되므로 절대 금지한다([IssueVisibilityPort]·
 * [com.bts.shared.permission.SlackChannelMappingPermissionResolver] 동형 fail-closed 원칙).
 *
 * ## 판정 불명도 제한(true) — 미존재·소프트 삭제 포함
 *
 * 이슈가 존재하지 않거나 소프트 삭제되어 조회할 수 없는 경우도 "판정 불명"으로 보아 `true`(제한)를
 * 반환해야 한다. "존재하지 않으니 안전하다(false)"는 착오이며, 이슈 삭제/키 오류로 조회 실패한
 * 케이스를 공개로 오판하면 예기치 못한 채널 누출을 초래할 수 있다.
 *
 * ## 파라미터 타입 — String (BC 공통 분모)
 *
 * 이슈 키는 issue-tracking 이 소유하는 `IssueKey` VO 대신 원문 문자열로 받는다 — 소비자
 * (slack-integration)가 issue-tracking 도메인 타입을 역방향 의존하지 않도록 한다
 * ([SlackChannelMappingPermissionResolver] 의 `projectKey: String` 과 동형 이유).
 *
 * ## ArchUnit 강제
 * 이 interface 는 원시 타입(String/Boolean)만 사용한다 — BC 도메인 타입을 참조하면
 * [com.bts.shared.architecture.SharedKernelBoundaryArchTest] 가 빌드를 차단한다.
 *
 * @see IssueVisibilityPort
 * @see com.bts.shared.permission.IssueSecurityDirectory
 */
interface IssueSecurityClassificationPort {
    /**
     * [issueKey] 가 가리키는 이슈가 보안등급으로 제한되어 있는지 판정한다.
     *
     * @param issueKey 판정할 이슈 키 문자열. 예: `"ATLAS-42"`.
     * @return 보안등급이 설정되어 있거나 판정할 수 없으면(미존재·소프트 삭제) `true`(제한).
     *   보안등급이 설정되지 않은 공개 이슈만 `false`.
     */
    fun isSecurityRestricted(issueKey: String): Boolean
}
