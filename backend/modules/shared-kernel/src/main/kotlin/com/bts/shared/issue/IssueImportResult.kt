// IssueImportPort 결과 VO — 생성 성공(issueKey) 또는 명시적 실패(reasonCode) 표현
package com.bts.shared.issue

/**
 * [IssueImportPort.importIssue] 처리 결과.
 *
 * 이슈 생성이 성공하면 [Success], 실패하면 [Failure]로 표현한다.
 * `sealed interface`로 두 상태를 상호 배타적으로 강제해 "issueKey 도 reasonCode 도 채워진" 같은
 * 잘못된 상태를 컴파일 타임에 차단한다.
 *
 * @see IssueImportPort
 * @see IssueImportCommand
 */
sealed interface IssueImportResult {
    /**
     * 이슈 생성 성공.
     *
     * @property issueKey 새로 발급된 이슈 키. 예: `"PROJ-42"`.
     * @property warnings 생성은 성공했으나 일부 필드를 반영하지 못한 경고 목록.
     *   예: 지정한 컴포넌트 이름을 찾지 못해 스킵. 빈 목록이면 경고 없음.
     */
    data class Success(
        val issueKey: String,
        val warnings: List<String> = emptyList(),
    ) : IssueImportResult

    /**
     * 이슈 생성 실패.
     *
     * @property reasonCode 실패 사유 코드. [IssueImportResult] companion 의 상수 참조.
     * @property message 사람이 읽을 수 있는 실패 상세 메시지. 에러 로그(MinIO) 기록용. 없을 수 있다.
     */
    data class Failure(
        val reasonCode: String,
        val message: String? = null,
    ) : IssueImportResult

    companion object {
        /**
         * adapter(issue-tracking 구현체)가 등록되지 않은 환경에서 [IssueImportPort] default 구현이
         * 반환하는 실패 사유 코드.
         */
        const val ADAPTER_UNAVAILABLE: String = "ADAPTER_UNAVAILABLE"

        /** 대상 프로젝트를 찾을 수 없다. */
        const val NOT_FOUND: String = "NOT_FOUND"

        /**
         * [IssueImportCommand.requesterUserId] 에게 필요한 이슈 권한이 없다.
         *
         * 이슈 생성에는 CREATE 권한이, priority/labels/assignee 를 반영하는 행에는
         * 추가로 UPDATE 권한이 필요하다. 둘 중 하나라도 없으면 이 코드로 실패한다.
         */
        const val FORBIDDEN: String = "FORBIDDEN"

        /** [IssueImportCommand.typeName] 에 해당하는 이슈 유형을 찾을 수 없다. */
        const val TYPE_NOT_FOUND: String = "TYPE_NOT_FOUND"

        /** 대상 프로젝트에 워크플로우가 구성되어 있지 않다. */
        const val WORKFLOW_NOT_CONFIGURED: String = "WORKFLOW_NOT_CONFIGURED"

        /** 입력 행 자체가 유효하지 않다 (예: summary 공백). */
        const val VALIDATION: String = "VALIDATION"

        /** 매핑되지 않은 예외 등 예상치 못한 내부 오류. */
        const val UNKNOWN: String = "UNKNOWN"

        /**
         * 생성 성공 결과를 만든다.
         *
         * @param issueKey 새로 발급된 이슈 키
         * @param warnings 부분 스킵 등 경고 목록. 기본값은 빈 목록.
         * @return [Success] 인스턴스
         */
        fun success(
            issueKey: String,
            warnings: List<String> = emptyList(),
        ): IssueImportResult = Success(issueKey = issueKey, warnings = warnings)

        /**
         * 실패 결과를 만든다.
         *
         * @param reasonCode 실패 사유 코드. 이 companion 의 상수를 사용한다.
         * @param message 실패 상세 메시지. 없으면 null.
         * @return [Failure] 인스턴스
         */
        fun failure(
            reasonCode: String,
            message: String? = null,
        ): IssueImportResult = Failure(reasonCode = reasonCode, message = message)
    }
}
