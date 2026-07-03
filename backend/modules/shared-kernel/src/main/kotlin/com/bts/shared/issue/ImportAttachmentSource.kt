// import 첨부 파일 바이너리를 파일명 기준으로 조회하는 함수형 포트 (FR-IM-01 PR4)
package com.bts.shared.issue

import java.io.InputStream

/**
 * import 첨부 파일의 바이너리 스트림을 조회하는 함수형 포트(FR-IM-01 PR4).
 *
 * [IssueImportCommand.attachments] 는 첨부 파일의 메타데이터([ImportAttachment])만 담으며,
 * 실제 바이너리 내용은 이 인터페이스를 통해 구현체([IssueImportPort.importIssue] 호출자)가
 * 별도로 제공한다. search-export-import 모듈이 업로드받은 zip 아카이브를 이 인터페이스로
 * 감싸고, issue-tracking 어댑터가 [open] 을 호출해 필요한 파일만 지연 조회하는 구조를 의도한다.
 *
 * ### filename → sourceKey 매칭 책임
 *
 * zip 아카이브 내부 경로 구성(예: `{sourceKey}/{filename}` vs 평면 `{filename}`)이나 파일명 충돌
 * 처리는 이 인터페이스 자체가 규정하지 않는다 — 구현체(source 제공자)가 [filename] 과
 * [sourceKey] 를 조합해 매칭하는 책임을 진다.
 *
 * @see IssueImportCommand.sourceKey
 * @see ImportAttachment
 */
fun interface ImportAttachmentSource {
    /**
     * 첨부 파일명과 원본 이슈 키로 바이너리 스트림을 조회한다.
     *
     * @param filename 조회할 첨부 파일명. [ImportAttachment.filename] 과 동일한 값이 전달된다.
     * @param sourceKey 원본(Jira 등) 이슈 키. [IssueImportCommand.sourceKey] 와 동일한 값이
     *   전달되며, null 이면 원본 이슈 키를 알 수 없는 상태다.
     * @return 조회된 스트림. 미매칭(zip 안에 해당 파일이 없는 경우 등)이면 null 을 반환한다 —
     *   구현체는 예외를 던지지 않고 null 로 표현해야 하며, 호출자는 null 을 첨부 스킵으로 처리한다.
     */
    fun open(
        filename: String,
        sourceKey: String?,
    ): InputStream?
}
