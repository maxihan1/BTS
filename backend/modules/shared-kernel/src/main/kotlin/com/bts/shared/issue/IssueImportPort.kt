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
     * 파싱된 import 행 1건으로 이슈 생성을 요청한다.
     *
     * 구현체는 [IssueImportCommand.requesterUserId] 를 actor 로 CREATE_ISSUE 권한을 검증하고,
     * 이슈 생성과 후속 필드(우선순위·라벨·담당자) 설정을 단일 트랜잭션으로 처리해야 한다.
     * [IssueImportCommand.dryRun] 이 true 이면 실제 생성 없이 검증만 수행해야 한다.
     *
     * default 구현은 어댑터 부재 환경에서 실패 결과를 반환한다(fail-closed) — 성공으로
     * 위장해 데이터 유실을 감추지 않는다.
     *
     * @param cmd 이슈 생성 커맨드 객체. projectKey, requesterUserId, summary 등 포함.
     * @return 생성 결과. 어댑터 미등록 시 실패([IssueImportResult.failure],
     *   reasonCode=[IssueImportResult.ADAPTER_UNAVAILABLE]).
     */
    fun importIssue(cmd: IssueImportCommand): IssueImportResult {
        return IssueImportResult.failure(IssueImportResult.ADAPTER_UNAVAILABLE)
    }
}
