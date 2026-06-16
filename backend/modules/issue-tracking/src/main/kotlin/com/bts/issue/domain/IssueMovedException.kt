// 이슈가 이동되어 옛 키로 조회 시 308 Permanent Redirect 로 안내하는 도메인 예외

package com.bts.issue.domain

/**
 * 이슈가 다른 프로젝트로 이동되어 옛 키가 더 이상 유효하지 않을 때 발생한다.
 *
 * [findCurrentKey] 가 redirect 체인을 순회하여 최종 키를 반환하면
 * [IssueApplicationService.findByKey] 가 이 예외를 던진다.
 * HTTP 핸들러는 308 Permanent Redirect + `Location: /api/v1/issues/{newKey}` 로 응답한다.
 *
 * 308 Permanent Redirect 를 사용하는 이유 (DATA.md §2).
 * - 이슈 키는 Slack·이메일 등 외부에서 인용되므로 영속성이 보장돼야 한다 (이슈 키 영속성 §10).
 * - 301/302 는 POST 를 GET 으로 변경할 수 있어 PATCH/DELETE 시 의도치 않은 동작이 발생한다.
 * - 308 은 원본 HTTP 메서드를 그대로 유지하여 클라이언트가 동일 메서드로 새 URL 에 재시도한다.
 *
 * RuntimeException 을 상속하므로 Spring @Transactional 이 롤백을 트리거할 수 있다.
 * 단, findByKey 는 readOnly 트랜잭션이므로 롤백 대상 쓰기 작업이 없다.
 *
 * @param newKey 이동 후 최종 이슈 키 문자열 (체인 이동 시 최종 목적지).
 */
class IssueMovedException(val newKey: String) :
    RuntimeException("Issue moved to: $newKey")
