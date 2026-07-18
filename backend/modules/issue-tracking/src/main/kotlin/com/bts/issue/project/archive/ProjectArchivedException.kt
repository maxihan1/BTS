// 아카이브된 프로젝트에 쓰기를 시도할 때 발생하는 도메인 예외 (409 매핑, FR-PJ-04 PR-4)

package com.bts.issue.project.archive

/**
 * 아카이브된 프로젝트(`projects.archived_at IS NOT NULL`)에 프로젝트 스코프 쓰기를 시도할 때
 * [ProjectArchiveGuard] 가 던지는 도메인 예외.
 *
 * HTTP 409(Conflict) 로 매핑된다
 * ([com.bts.issue.project.archive.web.ProjectArchivedExceptionHandler]).
 *
 * `message` 는 **로그 전용**으로 내부 식별자(projectId/projectKey/issueKey)를 담는다. HTTP 응답
 * detail 에는 이 message 를 그대로 노출하지 않고 일반 메시지로 치환한다 — 프로젝트의 아카이브
 * 여부/식별자 같은 내부 상태가 응답으로 새지 않도록 한다
 * (memory: fr-pm-04-guard-exception-message-http-leak).
 *
 * RuntimeException 을 상속하므로 Spring `@Transactional` 롤백 트리거 대상이다.
 *
 * @param identifier 아카이브 판정 대상 식별자(projectId/projectKey/issueKey 원문). 로그 전용.
 */
class ProjectArchivedException(identifier: String) :
    RuntimeException("Project is archived: $identifier")
