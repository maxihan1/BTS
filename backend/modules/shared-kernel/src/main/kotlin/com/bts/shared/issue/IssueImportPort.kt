// 이슈 생성 cross-BC 쓰기 포트 계약 (import → issue-tracking 위임) — BTS 최초 쓰기 포트
package com.bts.shared.issue

/**
 * 이슈 생성(import) cross-BC 쓰기 포트 계약 (FR-IM-01 Task 3).
 *
 * search-export-import 모듈이 CSV/JSON 파싱 결과([IssueImportCommand])를
 * issue-tracking BC 에 위임해 이슈를 생성하기 위한 포트 인터페이스다.
 * 의존 방향은 아래와 같다.
 *
 * ```
 * search-export-import ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
 * ```
 *
 * search-export-import 모듈은 issue-tracking 내부를 직접 gradle 의존하지 않는다.
 * 이 포트를 shared-kernel 에 배치함으로써 BC 경계를 ArchUnit 이 강제한다.
 * [com.bts.shared.search.IssueSearchPort] 를 1:1 로 미러하되, **fail 방향이 반대인 BTS 최초의
 * cross-BC "쓰기" 포트**다.
 *
 * ### fail-closed default 구현 — 읽기 포트와 의도적으로 반대
 *
 * 어댑터(issue-tracking 구현체)가 등록되지 않은 환경(단위 테스트, 단계적 배포)에서
 * default 구현은 명시적 실패([IssueImportResult.failure], reasonCode=
 * [IssueImportResult.ADAPTER_UNAVAILABLE])를 반환한다.
 *
 * [com.bts.shared.search.IssueSearchPort] 의 default 는 빈 페이지를 반환하는 fail-safe 다 —
 * 읽기는 실패 시 빈 결과(데이터 누출 0)가 안전하기 때문이다.
 * 이 포트는 **쓰기**이므로 방향이 의도적으로 반대다. 어댑터 부재 시 default 가 조용히
 * "생성 성공"으로 위장하면, 실제로는 이슈가 하나도 생성되지 않았는데 호출자(마이그레이션
 * job)가 성공으로 오인해 원본 데이터가 소리 없이 유실되는 사고로 이어진다.
 * 따라서 쓰기 포트의 fail-safe 는 반드시 명시적 실패(fail-closed)여야 한다.
 *
 * ### 권한 위임
 *
 * 구현체는 [IssueImportCommand.requesterUserId] 를 actor 로 삼아 issue-tracking 기존
 * `IssueApplicationService.createIssue` 경로(CREATE_ISSUE 권한 게이트 포함)를 그대로 재사용해야
 * 한다. 이 포트 자체는 권한을 판단하지 않는다 — search-export-import 는 issue-tracking 의
 * 권한 모델을 알지 못하고 우회할 수 없다.
 *
 * @see IssueImportCommand
 * @see IssueImportResult
 */
interface IssueImportPort {
    /**
     * 파싱된 import 행 1건으로 이슈 생성을 요청한다(하위호환 1-arg 오버로드).
     *
     * PR4 이전부터 존재하던 시그니처다. default 구현은 첨부 소스 없이
     * [importIssue] 2-arg 오버로드로 위임한다 — 어댑터가 2-arg 를 override 했다면
     * 그 구현으로 흘러가고(첨부만 스킵), 어댑터가 아무것도 override 하지 않았다면
     * 2-arg default 의 fail-closed 실패로 이어진다. 이 메서드 자체는 fail-closed 를
     * 직접 반환하지 않는다(기존 호출자를 무회귀로 유지하기 위한 위임 방향).
     *
     * @param cmd 이슈 생성 커맨드 객체. projectKey, requesterUserId, summary 등 포함.
     * @return 생성 결과. [importIssue] 2-arg 오버로드에 위임한 결과와 동일하다.
     */
    fun importIssue(cmd: IssueImportCommand): IssueImportResult {
        return importIssue(cmd, null)
    }

    /**
     * 파싱된 import 행 1건으로 이슈 생성을 요청한다(첨부 소스 포함, PR4 주 메서드).
     *
     * 구현체는 [IssueImportCommand.requesterUserId] 를 actor 로 CREATE_ISSUE 권한을 검증하고,
     * 이슈 생성과 후속 필드(우선순위·라벨·담당자·첨부·변경이력) 설정을 단일 트랜잭션으로
     * 처리해야 한다. [IssueImportCommand.dryRun] 이 true 이면 실제 생성 없이 검증만 수행해야 한다.
     *
     * default 구현은 어댑터 부재 환경에서 실패 결과를 반환한다(fail-closed) — 성공으로
     * 위장해 데이터 유실을 감추지 않는다. 어댑터는 이 2-arg 메서드를 override 해야 하며,
     * [importIssue] 1-arg 오버로드는 이 메서드로 위임하므로 별도 override 가 불필요하다.
     *
     * @param cmd 이슈 생성 커맨드 객체. projectKey, requesterUserId, summary 등 포함.
     * @param attachments import 대상 이슈의 첨부 파일 바이너리를 조회하는 포트. null 이면
     *   첨부 바이너리 조회를 시도하지 않는다(첨부 스킵, 구현체 책임).
     * @return 생성 결과. 어댑터 미등록 시 실패([IssueImportResult.failure],
     *   reasonCode=[IssueImportResult.ADAPTER_UNAVAILABLE]).
     */
    fun importIssue(
        cmd: IssueImportCommand,
        attachments: ImportAttachmentSource?,
    ): IssueImportResult {
        return IssueImportResult.failure(IssueImportResult.ADAPTER_UNAVAILABLE)
    }
}
