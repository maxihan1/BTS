// FR-IM-02 Import 필드 매핑 검증/확정 서비스 — AWAITING_MAPPING 소유확인 + 헤더 재읽기 검증 + CAS 우선 트랜잭션
package com.bts.search.imports.mapping

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.mapping.repository.ImportUserMappingRepository
import com.bts.search.imports.mapping.repository.ImportValueMappingRepository
import com.bts.search.imports.parse.ImportRowParser
import com.bts.search.imports.parse.ParsedImportRow
import com.bts.shared.issue.IssueTypeCatalog
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * (대상 필드, 소스 값, 대상 값) 튜플 — [ImportMappingService.confirm] 의 `valueMappings` 원본 계약 및
 * [ImportMappingService] 내부 정규화 중간값과 동일 shape 를 짧게 부른다(줄 길이 축소 목적, 순수 표기 alias).
 */
private typealias ValueMappingTriple = Triple<ValueTargetField, String, String>

/**
 * FR-IM-02 Import 필드 매핑 검증([validate]) + 확정([confirm]) 서비스.
 *
 * `ImportJobService.analyze`(sibling task, 별도 병렬 wave 구현)가 `AWAITING_MAPPING` 상태로 만들어 둔
 * 작업을 대상으로, 사용자가 제안한 소스 필드 → [TargetField] 매핑을 검증하고 확정한다.
 *
 * ## validate — [validate]
 *
 * 저장 없이 매핑만 검증한다. 소유확인 + AWAITING_MAPPING 상태 검사([requireOwnedAwaitingMapping]) 후,
 * persist 된 원본 파일을 다시 열어([storage] + [parser]) 소스 필드(CSV 헤더)를 확보하고
 * [MappingValidator] 로 검증한다. **JSON 형식은 필드 매핑 검증 자체를 스킵**한다 — JSON 은 Jira REST
 * export 고정 스키마(canonical)라 필드 매핑 개념이 없다(스펙 FR3/S5). 이 경우 원본을 다시 읽지 않고
 * 항상 `valid=true, errors=[], warnings=[]` 를 반환한다.
 *
 * ## confirm — [confirm]
 *
 * [validate] 와 동일한 검증([computeValidationResult])을 통과해야 매핑을 저장하고 작업을 실행 큐로
 * 넘긴다. 검증 실패 시 [ImportMappingInvalidException](422) 을 던진다.
 *
 * ### 확정 트랜잭션 경계 — CAS 게이트 우선 (CONCERN-3)
 *
 * 검증 통과 후 [transactionTemplate] 으로 다음 세 작업을 한 트랜잭션에 묶는다.
 *
 * 1. [ImportJobRepository.transitionToPending] — `AWAITING_MAPPING → PENDING` CAS(Compare-And-Swap,
 *    "조건이 맞을 때만 바꾼다"는 원자적 갱신).
 * 2. [ImportMappingRepository.saveAll] — 확정 매핑 저장.
 * 3. [ImportJobEnqueuePublisher.enqueue] — 실행 큐 enqueue(outbox, 같은 트랜잭션).
 *
 * **CAS 를 반드시 1번(saveAll/enqueue 이전)에 실행한다.** [confirm] 진입 시점의 상태 사전확인
 * ([requireOwnedAwaitingMapping])만으로는 반복/동시 confirm 요청의 TOCTOU(Time-Of-Check-Time-Of-Use —
 * 검사와 실제 사용 사이에 상태가 바뀌는 경쟁 상황, 교훈 advisory-lock-bigint-toctou)를 막지 못한다.
 * 두 요청이 모두 사전확인을 통과한 뒤 첫 번째만 CAS 에 성공하고 두 번째는 실패해야 하는데, CAS 를
 * saveAll/enqueue 뒤에 두면 이미 saveAll(매핑 덮어씀)/enqueue(중복 실행)가 두 요청 모두에서 실행돼
 * 버린다. CAS 를 트랜잭션 맨 앞에 두고 실패(`false`) 시 즉시 [ImportMappingStateConflictException] 을
 * 던지면(트랜잭션 롤백) saveAll/enqueue 는 CAS 에 성공한 요청 단 1건에서만 실행된다 —
 * [ImportJobEnqueuePublisher] 의 outbox 패턴(DATA.md §7.2)이 보장하는 "영속과 enqueue 는 항상 같은
 * 트랜잭션"이 매핑 확정 경로에서도 깨지지 않는다.
 *
 * ### @Transactional 의도적 생략 — [validate]/[confirm] 은 클래스 레벨에 붙이지 않는다
 *
 * [requireOwnedAwaitingMapping]/[computeValidationResult] 는 DB 조회 1건
 * ([ImportJobRepository.findByIdForRequester], 자체 `@Transactional(readOnly=true)`) + MinIO 스트림
 * 오픈(I/O)을 수행한다. [validate]/[confirm] 전체를 `@Transactional` 로 감싸면 이 I/O 동안 DB 커넥션을
 * 점유하게 되므로(`ImportJobService` KDoc §트랜잭션 경계, FR-AC-01 "100MB I/O @Transactional 밖" 과
 * 동일 이유), [confirm] 은 CAS+saveAll+enqueue 구간만 [transactionTemplate] 으로 좁게 감싸고, 그 외
 * 구간(소유확인, 검증)은 트랜잭션 밖에 둔다. 각 repo 메서드는 자기충족적 트랜잭션을 이미 갖고 있다.
 *
 * ### dryRun — confirm 시점에 최종 확정되어 영속된다 (dryrun-fix)
 *
 * [confirm] 의 `dryRun` 파라미터는 API 계약(`POST /imports/{jobId}/mapping` body 의 optional
 * `dryRun` 필드, 웹 계층 sibling task)을 그대로 받는다. `AWAITING_MAPPING` job 이 analyze 단계에서
 * 가진 [ImportJob.dryRun] 은 항상 `false`(중립값)이며, 실제 dry-run 여부는 사용자가 confirm 시
 * 선택하는 이 파라미터로 비로소 확정된다. [transactionTemplate] 내부 CAS 전이
 * ([ImportJobRepository.transitionToPending])가 이 값을 `dry_run` 컬럼에 함께 SET 해 영속하므로,
 * 워커는 확정된 값을 그대로 읽어 dry-run 판정을 수행한다(스펙 §엣지케이스 "dryRun 확정 → 저장·전이·
 * enqueue 동일, 워커가 dryRun 검증만"). 과거 이 파라미터가 감사 로깅에만 쓰이고 영속되지 않아 워커가
 * 확정된 dryRun 선택을 무시하던 silent bug 가 있었다(재발 금지 — dryrun-fix).
 *
 * ## 사용자 매핑 흐름 — [collectUsers] + `confirm` 의 `userMappings` (FR-IM-02 PR-B)
 *
 * 필드 매핑과 별개로, Import 원본에 등장하는 작성자(보고자/담당자/댓글/worklog/첨부/변경이력 작성자)
 * 식별자를 BTS 사용자로 매핑하는 흐름이다. [UserMappingNormalizer] 가 정규화(trim+lowercase)의
 * 유일 진실원천이다.
 *
 * 1. **[collectUsers]** — 원본을 전량 스캔해 distinct 정규화 식별자를 수집하고,
 *    [userLookupPort]`.resolveByEmails` 로 추천 사용자 UUID 를, `findDisplayNamesByIds` 로 추천 표시명을
 *    조회해 [UserCollectionResult] 로 반환한다. 저장하지 않는다([validate] 와 동일하게 조회 전용).
 * 2. **`confirm` 의 `userMappings`** — 사용자가 [collectUsers] 결과를 참고해 확정한
 *    `sourceIdentifier to targetUserId?` 목록을 검증(정규화 후 중복/미실재 대상 확인, [confirm] KDoc
 *    §사용자 매핑 검증 참조) 한 뒤, CAS 트랜잭션 안에서 [importUserMappingRepository] 로 저장한다.
 *
 * ## 값 매핑 흐름 — [collectValues] + `confirm` 의 `valueMappings` (FR-IM-02 PR-C)
 *
 * 필드/사용자 매핑과 별개로, Import 원본에 등장하는 상태/유형/우선순위 이름([ValueTargetField])을
 * BTS 대상 값으로 매핑하는 흐름이다. [ValueMappingNormalizer] 가 정규화(trim+lowercase)의 유일
 * 진실원천이다.
 *
 * 1. **[collectValues]** — 원본을 전량 스캔해 [ValueTargetField] 별 distinct 정규화 소스값을 수집하고,
 *    [workflowStateCatalog]/[issueTypeCatalog]/[ImportRowParser.canonicalPriorityNames] 후보와 정규화
 *    정확일치하는 자동추천을 계산해 [ValueCollectionResult] 로 반환한다. 저장하지 않는다([collectUsers]
 *    와 동일하게 조회 전용).
 * 2. **`confirm` 의 `valueMappings`** — 사용자가 [collectValues] 결과를 참고해 확정한
 *    `(대상 필드, 소스 값, 대상 값)` 목록을 검증([validateValueMappings], FR7 필드별 비대칭 +
 *    C1 canonical 치환 + E6 중복 검증) 한 뒤, CAS 트랜잭션 안에서 [importValueMappingRepository] 로
 *    저장한다.
 *
 * @param importMappingRepository 필드 매핑 영속 저장소([ImportMappingRepository.saveAll]).
 * @param importJobRepository Import 작업 저장소. 소유확인([ImportJobRepository.findByIdForRequester]) +
 *   CAS 전이([ImportJobRepository.transitionToPending])에 사용.
 * @param enqueuePublisher pgmq 큐에 작업 ID 를 발행하는 아웃바운드 어댑터.
 * @param storage Import 원본 오브젝트 스토리지 포트. 헤더/전량 재읽기용.
 * @param transactionTemplate CAS+saveAll+enqueue 구간의 프로그래밍 방식 트랜잭션 경계. [collectValues]
 *   의 자동추천 계산 구간(짧은 별도 트랜잭션)에도 재사용한다([buildValueSuggestions]).
 * @param userLookupPort 사용자 실재 확인 + 이메일→UUID/UUID→표시명 일괄 조회 cross-BC 포트.
 *   [collectUsers] 의 추천 계산과 `confirm` 의 대상 사용자 실재 검증에 사용한다.
 * @param importUserMappingRepository 확정 사용자 매핑 영속 저장소([ImportUserMappingRepository.saveAll]).
 * @param issueTypeCatalog 전역 이슈타입 전체 목록 조회 cross-BC 포트(issue-tracking). [collectValues]
 *   의 TYPE 자동추천 + `confirm` 의 TYPE 대상 값 검증([validateValueMappings])에 사용한다.
 * @param workflowStateCatalog 프로젝트 워크플로우 상태 전체 목록 조회 cross-BC 포트(project-workflow).
 *   [collectValues] 의 STATUS 자동추천 계산에만 사용한다(`confirm` 의 STATUS 검증은 관대해 호출하지
 *   않는다) — `Propagation.MANDATORY` 라 [transactionTemplate] 안에서만 호출한다.
 * @param importValueMappingRepository 확정 값 매핑 영속 저장소([ImportValueMappingRepository.saveAll]).
 * @param parser CSV/JSON 스트리밍 파서. [ImportRowParser] 는 Spring 빈으로 등록되어 있지 않으므로
 *   (`ImportJobProcessor` 의 동일 기본값 패턴을 따라) 기본값으로 직접 인스턴스화한다.
 *
 * Suppress 근거. `LongParameterList` — DI 생성자, 필드/사용자/값 매핑 영속·CAS 전이·enqueue·cross-BC 조회
 * 협력자 11개. `TooManyFunctions` — 필드/사용자/값 매핑 3종의 검증+확정 책임이 늘며 임계값(11)을 자연
 * 초과(12, validate/confirm/collectUsers/collectValues 4개 public API + 매핑 종류별 전용 private 헬퍼).
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions")
class ImportMappingService(
    private val importMappingRepository: ImportMappingRepository,
    private val importJobRepository: ImportJobRepository,
    private val enqueuePublisher: ImportJobEnqueuePublisher,
    private val storage: ImportObjectStoragePort,
    private val transactionTemplate: TransactionTemplate,
    private val userLookupPort: UserLookupPort,
    private val importUserMappingRepository: ImportUserMappingRepository,
    private val issueTypeCatalog: IssueTypeCatalog,
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val importValueMappingRepository: ImportValueMappingRepository,
    private val parser: ImportRowParser = ImportRowParser(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 제안된 필드 매핑을 저장 없이 검증한다.
     *
     * `@Transactional` **의도적 생략** — 클래스 KDoc §@Transactional 의도적 생략 참조(MinIO 헤더
     * 재읽기 I/O 동안 DB 커넥션을 점유하지 않기 위함).
     *
     * @param jobId 검증 대상 Import 작업 식별자.
     * @param actor 요청자 UUID. 소유확인에 사용.
     * @param fieldMappings 소스 필드 이름 → [TargetField.key](또는 [TargetField.IGNORE_KEY]) 매핑.
     * @return 검증 결과. JSON 형식이면 항상 유효(valid=true).
     * @throws ResponseStatusException(404) 작업이 없거나 [actor] 소유가 아닌 경우(존재 은닉).
     * @throws ImportMappingStateConflictException 작업 상태가 [ImportJobStatus.AWAITING_MAPPING] 이 아닌 경우.
     */
    fun validate(
        jobId: ImportJobId,
        actor: UUID,
        fieldMappings: Map<String, String>,
    ): MappingValidationResult {
        val job = requireOwnedAwaitingMapping(jobId, actor)
        return computeValidationResult(job, fieldMappings)
    }

    /**
     * 필드 매핑을 검증한 뒤 저장하고, 작업을 PENDING 으로 전이해 실행 큐에 enqueue 한다.
     *
     * 클래스 KDoc §확정 트랜잭션 경계 참조 — CAS([ImportJobRepository.transitionToPending])가
     * saveAll/enqueue 보다 먼저 실행되어 반복/동시 confirm 의 중복 실행을 차단한다. `@Transactional`
     * 은 메서드 전체가 아니라 [transactionTemplate] 으로 CAS+saveAll+enqueue 구간만 좁게 감싼다(클래스
     * KDoc §@Transactional 의도적 생략 참조).
     *
     * ### 사용자 매핑 검증 — [userMappings] (클래스 KDoc §사용자 매핑 흐름 참조)
     *
     * [validateUserMappings] 가 트랜잭션 **밖**에서(cross-BC [userLookupPort] I/O 포함) 다음을
     * 검증한다.
     * 1. 정규화([UserMappingNormalizer.normalize]) 시 중복되는 소스 식별자가 없어야 한다(대소문자/공백
     *    변형 포함) — [ImportUserMappingInvalidException.DUPLICATE_SOURCE_IDENTIFIER].
     * 2. targetUserId 가 null 이 아니면 실재 사용자여야 한다([userLookupPort]`.findDisplayNamesByIds`
     *    로 확인) — [ImportUserMappingInvalidException.TARGET_USER_NOT_FOUND]. targetUserId 가 null
     *    이면 미매핑(폴백) 의도로 그대로 허용한다.
     *
     * 검증을 통과하면 정규화된 `sourceIdentifier -> targetUserId?` 맵을 CAS 트랜잭션 안에서
     * [importUserMappingRepository]`.saveAll` 로 저장한다 — [importMappingRepository]`.saveAll`(필드
     * 매핑) 뒤, [enqueuePublisher]`.enqueue` 앞이다. [userMappings] 가 비어 있으면(기본값) 빈 맵을
     * 저장해 기존 매핑을 전량 제거한다(하위호환 — 사용자 매핑 없이 confirm 하던 기존 호출부를 그대로
     * 지원).
     *
     * @param jobId 확정 대상 Import 작업 식별자.
     * @param actor 요청자 UUID. 소유확인에 사용.
     * @param fieldMappings 소스 필드 이름 → [TargetField.key](또는 [TargetField.IGNORE_KEY]) 매핑.
     * @param dryRun 확정 시 사용자가 선택한 dry-run 여부(클래스 KDoc §dryRun 참조). CAS 전이로
     *   `dry_run` 컬럼에 영속된다.
     * @param userMappings 확정할 사용자 매핑 `sourceIdentifier to targetUserId?` 목록. **Map 이 아닌
     *   List** — Map 으로 받으면 동일 키(정규화 전 원본이 다르더라도 정규화 후 겹치는 경우)가 먼저
     *   붕괴돼 [DUPLICATE_SOURCE_IDENTIFIER][ImportUserMappingInvalidException.DUPLICATE_SOURCE_IDENTIFIER]
     *   검증이 무력화된다. 기본값 빈 목록 — 사용자 매핑 없이 confirm 하는 기존 호출부와 하위호환.
     *
     * ### 값 매핑 검증 — [valueMappings] (클래스 KDoc §값 매핑 흐름 참조)
     *
     * [validateValueMappings] 가 트랜잭션 **밖**에서(cross-BC [issueTypeCatalog] I/O 포함) 다음을
     * 검증한다.
     * 1. **FR7 필드별 비대칭** — [ValueTargetField.TYPE]/[ValueTargetField.PRIORITY] 는 엄격하다.
     *    [issueTypeCatalog]`.listTypes`/[ImportRowParser.canonicalPriorityNames] 와 정규화
     *    정확일치(대소문자 무시)하지 않으면
     *    [TARGET_VALUE_NOT_FOUND][ImportValueMappingInvalidException.TARGET_VALUE_NOT_FOUND] 다.
     *    [ValueTargetField.STATUS] 는 관대하다 — 비어 있지만 않으면 그대로 통과한다(워크플로우 YAML
     *    정본이 상태 이름의 최종 진실원천이라 여기서는 카탈로그 존재 확인을 하지 않는다).
     * 2. **C1 저장값 canonical 치환** — TYPE/PRIORITY 는 사용자가 입력한 대소문자가 아니라 매칭된
     *    카탈로그/canonical 값의 정확한 표기로 치환해 저장한다. 사용자 입력 그대로 저장하면
     *    [com.bts.search.imports.job.application.ImportJobProcessor] 의 대소문자 정확일치 치환 맵에서
     *    조용히 유실될 수 있다. STATUS 는 원본 그대로 저장한다.
     * 3. **E6 중복** — 정규화([ValueMappingNormalizer.normalize]) 후 (대상 필드, 소스 값) 조합이
     *    중복되면 대상 값이 같더라도
     *    [DUPLICATE_VALUE_MAPPING][ImportValueMappingInvalidException.DUPLICATE_VALUE_MAPPING] 이다.
     *
     * 검증을 통과하면 `(대상 필드, 소스 값) -> 대상 값` 맵을 CAS 트랜잭션 안에서
     * [importValueMappingRepository]`.saveAll` 로 저장한다 — [importUserMappingRepository]`.saveAll`
     * 뒤, [enqueuePublisher]`.enqueue` 앞이다. [valueMappings] 가 비어 있으면(기본값) 빈 맵을 저장해
     * 기존 매핑을 전량 제거한다(하위호환).
     *
     * @param valueMappings 확정할 값 매핑 `(대상 필드, 소스 값, 대상 값)` 목록. 기본값 빈 목록 — 값
     *   매핑 없이 confirm 하는 기존 호출부와 하위호환.
     * @return `PENDING` 상태로 전이된 [ImportJob] — [confirm] 호출 직전 조회한 스냅샷에
     *   status/expiresAt/dryRun 만 갱신한 사본이며, CAS 이후 재조회하지 않는다(그 외 필드는 확정으로
     *   바뀌지 않는다).
     * @throws ResponseStatusException(404) 작업이 없거나 [actor] 소유가 아닌 경우.
     * @throws ImportMappingStateConflictException 사전확인 시점 또는 CAS 시점(TOCTOU 포함)에 작업
     *   상태가 [ImportJobStatus.AWAITING_MAPPING] 이 아닌 경우.
     * @throws ImportMappingInvalidException 필드 매핑 검증 실패 시(422 위임).
     * @throws ImportUserMappingInvalidException 사용자 매핑 검증 실패 시(422 위임).
     * @throws ImportValueMappingInvalidException 값 매핑 검증 실패 시(422 위임).
     */
    fun confirm(
        jobId: ImportJobId,
        actor: UUID,
        fieldMappings: Map<String, String>,
        dryRun: Boolean,
        userMappings: List<Pair<String, UUID?>> = emptyList(),
        valueMappings: List<Triple<ValueTargetField, String, String>> = emptyList(),
    ): ImportJob {
        val job = requireOwnedAwaitingMapping(jobId, actor)
        val result = computeValidationResult(job, fieldMappings)
        if (!result.valid) {
            throw ImportMappingInvalidException(result.errors)
        }
        val normalizedUserMappings = validateUserMappings(userMappings)
        val normalizedValueMappings = validateValueMappings(valueMappings)

        transactionTemplate.execute {
            if (!importJobRepository.transitionToPending(jobId, dryRun)) {
                throw ImportMappingStateConflictException()
            }
            importMappingRepository.saveAll(jobId, fieldMappings)
            importUserMappingRepository.saveAll(jobId, normalizedUserMappings)
            importValueMappingRepository.saveAll(jobId, normalizedValueMappings)
            enqueuePublisher.enqueue(jobId)
        }

        log.info(
            "import_mapping_confirmed jobId={} actor={} fieldCount={} userMappingCount={} " +
                "valueMappingCount={} dryRun={}",
            jobId.value,
            actor,
            fieldMappings.size,
            normalizedUserMappings.size,
            normalizedValueMappings.size,
            dryRun,
        )
        return job.copy(status = ImportJobStatus.PENDING, expiresAt = null, dryRun = dryRun)
    }

    /**
     * Import 원본을 전량 스캔해 등장하는 작성자 식별자를 수집하고, BTS 사용자 추천을 계산한다
     * (클래스 KDoc §사용자 매핑 흐름 참조). 저장하지 않는다 — [validate] 와 동일하게 조회 전용이다.
     *
     * `@Transactional` **의도적 생략** — 클래스 KDoc §@Transactional 의도적 생략과 동일 이유([storage]
     * 스트림 I/O + [userLookupPort] cross-BC I/O 동안 DB 커넥션을 점유하지 않기 위함).
     *
     * ### CSV — 필드 매핑 선검증 후 전량 스캔 (C1)
     *
     * [computeValidationResult] 로 필드 매핑을 먼저 검증한다(헤더만 재읽기, 전량 스캔 아님). 매핑이
     * 무효(예: summary 미매핑)면 곧바로 [ImportMappingInvalidException](422) 을 던지고 전량 스캔을
     * 하지 않는다 — 검증 없이 바로 전량 파싱하면, 무효한 매핑으로 인한 실패가 [ImportParseException]
     * (예상치 못한 500)으로 표면화될 수 있기 때문이다. 검증을 통과해야만 [storage] 를 다시 열어
     * [ImportRowParser.parseCsv] 로 전량을 스캔한다.
     *
     * ### JSON — 필드 매핑 검증 스킵 후 전량 스캔
     *
     * [computeValidationResult] 는 JSON 이면 저장소를 읽지 않고 즉시 valid=true 를 반환하므로([validate]
     * KDoc 참조), 곧바로 [storage] 를 열어 [ImportRowParser.parseJson] 으로 전량 스캔한다.
     *
     * ### 추천 계산
     *
     * 전량 스캔 중 각 행에서 [UserMappingNormalizer.collectIdentifiers] 로 정규화된 식별자를 모아
     * distinct 집합을 만든다. 식별자가 하나도 없으면 조회 없이 빈 결과를 반환한다. 그 외에는
     * [userLookupPort]`.resolveByEmails` 로 식별자 → 추천 사용자 UUID 를, 추천된 UUID 집합으로
     * `findDisplayNamesByIds` 를 호출해 표시명을 조회한다. 추천을 찾지 못한 식별자는
     * `suggestedUserId`/`suggestedDisplayName` 모두 null — 매핑 UI 가 사용자에게 수동 선택을 요구한다.
     *
     * @param jobId 대상 Import 작업 식별자.
     * @param actor 요청자 UUID. 소유확인에 사용.
     * @param fieldMappings 소스 필드 이름 → [TargetField.key](또는 [TargetField.IGNORE_KEY]) 매핑.
     *   JSON 형식이면 검증에 사용되지 않는다.
     * @return 정규화된 소스 식별자별 추천 사용자 정보([UserCollectionResult.users] — sourceIdentifier
     *   오름차순 정렬).
     * @throws ResponseStatusException(404) 작업이 없거나 [actor] 소유가 아닌 경우(존재 은닉).
     * @throws ImportMappingStateConflictException 작업 상태가 [ImportJobStatus.AWAITING_MAPPING] 이 아닌 경우.
     * @throws ImportMappingInvalidException CSV 필드 매핑 검증 실패 시(422 위임).
     */
    fun collectUsers(
        jobId: ImportJobId,
        actor: UUID,
        fieldMappings: Map<String, String>,
    ): UserCollectionResult {
        val job = requireOwnedAwaitingMapping(jobId, actor)
        val validation = computeValidationResult(job, fieldMappings)
        if (!validation.valid) {
            throw ImportMappingInvalidException(validation.errors)
        }

        val identifiers = mutableSetOf<String>()
        storage.get(job.sourceObjectKey).use { stream ->
            if (job.format == FORMAT_JSON) {
                parser.parseJson(stream) { row -> identifiers += UserMappingNormalizer.collectIdentifiers(row) }
            } else {
                parser.parseCsv(stream, fieldMappings) { row ->
                    identifiers += UserMappingNormalizer.collectIdentifiers(row)
                }
            }
        }
        if (identifiers.isEmpty()) return UserCollectionResult(users = emptyList())

        val suggestions = userLookupPort.resolveByEmails(identifiers)
        val displayNames = userLookupPort.findDisplayNamesByIds(suggestions.values.toSet())
        val entries =
            identifiers.sorted().map { identifier ->
                val suggestedUserId = suggestions[identifier]
                UserCollectionEntry(
                    sourceIdentifier = identifier,
                    suggestedUserId = suggestedUserId,
                    suggestedDisplayName = suggestedUserId?.let(displayNames::get),
                )
            }
        return UserCollectionResult(users = entries)
    }

    /**
     * Import 원본을 전량 스캔해 등장하는 상태/유형/우선순위([ValueTargetField]) 소스값을 수집하고,
     * BTS 대상 값 자동추천을 계산한다(클래스 KDoc §값 매핑 흐름 참조). 저장하지 않는다 — [collectUsers]
     * 와 동일하게 조회 전용이다.
     *
     * `@Transactional` **의도적 생략** — 클래스 KDoc §@Transactional 의도적 생략과 동일 이유([storage]
     * 스트림 I/O 동안 DB 커넥션을 점유하지 않기 위함). 단, 자동추천 계산 구간만 [buildValueSuggestions]
     * 안에서 짧은 [transactionTemplate] 으로 감싼다 — [workflowStateCatalog]`.listStates` 가
     * `Propagation.MANDATORY`([com.bts.shared.workflow.WorkflowStateCatalog] KDoc)라 활성 트랜잭션
     * 밖에서 호출하면 `IllegalTransactionStateException` 이 던져지기 때문이다.
     *
     * ### 필드 매핑 선검증 (C1, [collectUsers] 와 동일 이유)
     *
     * [computeValidationResult] 로 필드 매핑을 먼저 검증한다. 무효(예: summary 미매핑)면 곧바로
     * [ImportMappingInvalidException](422) 을 던지고 전량 스캔을 하지 않는다 — 검증 없이 바로 전량
     * 파싱하면 무효한 매핑으로 인한 실패가 [ImportParseException](예상치 못한 500)으로 표면화될 수
     * 있기 때문이다.
     *
     * ### 소스값 수집 + 자동추천
     *
     * [storage] 를 열어 각 행을 최소 투영([minimalValueRow])으로 전량 수집한 뒤,
     * [ValueMappingNormalizer.collectValues] 로 [ValueTargetField] 별 distinct 정규화 소스값 집합을
     * 얻는다. 각 소스값에 대해 정규화 정확일치하는 후보([workflowStateCatalog]/[issueTypeCatalog]/
     * [ImportRowParser.canonicalPriorityNames])가 있으면 그 원본 표기(대소문자 보존)를 추천값으로
     * 제시하고, 없으면 null(매핑 UI 가 사용자에게 수동 선택을 요구한다).
     *
     * @param jobId 대상 Import 작업 식별자.
     * @param actor 요청자 UUID. 소유확인에 사용.
     * @param fieldMappings 소스 필드 이름 → [TargetField.key](또는 [TargetField.IGNORE_KEY]) 매핑.
     *   JSON 형식이면 검증에 사용되지 않는다.
     * @return [ValueTargetField] 별 소스값+자동추천 목록([ValueCollectionResult]).
     * @throws ResponseStatusException(404) 작업이 없거나 [actor] 소유가 아닌 경우(존재 은닉).
     * @throws ImportMappingStateConflictException 작업 상태가 [ImportJobStatus.AWAITING_MAPPING] 이 아닌 경우.
     * @throws ImportMappingInvalidException CSV 필드 매핑 검증 실패 시(422 위임).
     */
    fun collectValues(
        jobId: ImportJobId,
        actor: UUID,
        fieldMappings: Map<String, String>,
    ): ValueCollectionResult {
        val job = requireOwnedAwaitingMapping(jobId, actor)
        val validation = computeValidationResult(job, fieldMappings)
        if (!validation.valid) {
            throw ImportMappingInvalidException(validation.errors)
        }

        val rows = mutableListOf<ParsedImportRow>()
        storage.get(job.sourceObjectKey).use { stream ->
            if (job.format == FORMAT_JSON) {
                parser.parseJson(stream) { row -> rows += minimalValueRow(row) }
            } else {
                parser.parseCsv(stream, fieldMappings) { row -> rows += minimalValueRow(row) }
            }
        }
        val collectedValues = ValueMappingNormalizer.collectValues(rows)
        val values = buildValueSuggestions(ProjectKey.of(job.projectKey), collectedValues)

        log.info(
            "import_value_collected jobId={} statusCount={} typeCount={} priorityCount={}",
            jobId.value,
            values.getValue(ValueTargetField.STATUS).size,
            values.getValue(ValueTargetField.TYPE).size,
            values.getValue(ValueTargetField.PRIORITY).size,
        )
        return ValueCollectionResult(values = values)
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * 소유확인 + AWAITING_MAPPING 상태 검사를 함께 수행한다.
     *
     * 타인 소유 작업은 존재 자체를 은닉하기 위해 404 로 처리한다 — 이 검사가 매핑 검증 로직보다
     * 먼저 실행돼야 소유권 없는 요청자에게 상태 정보(409 등)조차 노출하지 않는다(교훈
     * auth-extraction-before-resource-lookup).
     */
    private fun requireOwnedAwaitingMapping(
        jobId: ImportJobId,
        actor: UUID,
    ): ImportJob {
        val job =
            importJobRepository.findByIdForRequester(jobId, actor)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Import 작업을 찾을 수 없습니다.")
        if (job.status != ImportJobStatus.AWAITING_MAPPING) {
            throw ImportMappingStateConflictException()
        }
        return job
    }

    /**
     * [job] 의 format 에 따라 필드 매핑을 검증한다.
     *
     * JSON 이면 원본을 다시 읽지 않고 즉시 유효 결과를 반환한다(클래스 KDoc §validate 참조).
     * CSV 면 persist 된 원본을 다시 열어 헤더(소스 필드)를 확보한 뒤 [MappingValidator] 로 검증한다.
     */
    private fun computeValidationResult(
        job: ImportJob,
        fieldMappings: Map<String, String>,
    ): MappingValidationResult {
        if (job.format == FORMAT_JSON) {
            return MappingValidationResult(valid = true, errors = emptyList(), warnings = emptyList())
        }
        val sourceFields =
            storage.get(job.sourceObjectKey).use { stream ->
                parser.readHeaderAndSample(stream).headers
            }
        return MappingValidator.validate(sourceFields, fieldMappings)
    }

    /**
     * [confirm] 의 `userMappings` 를 정규화 + 검증한 뒤, 저장 가능한 `sourceIdentifier -> targetUserId?`
     * 맵으로 변환한다([confirm] KDoc §사용자 매핑 검증 참조). [userLookupPort] I/O 를 포함하므로
     * 트랜잭션 **밖**에서 호출한다.
     *
     * @throws ImportUserMappingInvalidException 정규화 시 중복되는 소스 식별자가 있거나, null 이 아닌
     *   targetUserId 가 실재 사용자가 아닌 경우.
     */
    private fun validateUserMappings(userMappings: List<Pair<String, UUID?>>): Map<String, UUID?> {
        if (userMappings.isEmpty()) return emptyMap()

        val normalized =
            userMappings.map { (sourceIdentifier, targetUserId) ->
                UserMappingNormalizer.normalize(sourceIdentifier) to targetUserId
            }

        val errors = mutableListOf<MappingIssue>()
        errors +=
            normalized
                .groupBy({ it.first }, { it.second })
                .filterValues { targetUserIds -> targetUserIds.size > 1 }
                .map { (identifier, _) ->
                    MappingIssue(
                        ImportUserMappingInvalidException.DUPLICATE_SOURCE_IDENTIFIER,
                        "정규화 시 중복되는 사용자 매핑 소스 식별자입니다: $identifier",
                        identifier,
                    )
                }

        val requestedTargetUserIds = normalized.mapNotNull { it.second }.toSet()
        val existingTargetUserIds =
            if (requestedTargetUserIds.isEmpty()) {
                emptySet()
            } else {
                userLookupPort.findDisplayNamesByIds(requestedTargetUserIds).keys
            }
        errors +=
            normalized
                .mapNotNull { (identifier, targetUserId) -> targetUserId?.let { identifier to it } }
                .filter { (_, targetUserId) -> targetUserId !in existingTargetUserIds }
                .map { (identifier, targetUserId) ->
                    MappingIssue(
                        ImportUserMappingInvalidException.TARGET_USER_NOT_FOUND,
                        "존재하지 않는 대상 사용자입니다: $targetUserId",
                        identifier,
                    )
                }

        if (errors.isNotEmpty()) {
            throw ImportUserMappingInvalidException(errors)
        }
        return normalized.toMap()
    }

    /**
     * [collectValues] 가 전량 스캔 시 보존하는 최소 투영 — status/type/priority 3 필드만 남기고
     * 나머지는 기본값으로 비운다. [ImportJob.MAX_ROWS](최대 10 만 행) 규모의 대량 Import 에서
     * summary/description/댓글 등 값 매핑과 무관한 필드까지 메모리에 쌓아 두지 않기 위함
     * ([ImportRowParser] 클래스 KDoc §스트리밍 설계와 동일 OOM 방지 원칙).
     */
    private fun minimalValueRow(row: ParsedImportRow): ParsedImportRow =
        ParsedImportRow(
            rowNumber = row.rowNumber,
            summary = null,
            description = null,
            typeName = row.typeName,
            priorityName = row.priorityName,
            reporterEmail = null,
            assigneeEmail = null,
            labels = emptyList(),
            componentNames = emptyList(),
            statusName = row.statusName,
        )

    /**
     * [collectValues] 의 자동추천 계산 구간 — [ValueTargetField] 별 후보 조회 + 정규화 정확일치 매칭을
     * 짧은 [transactionTemplate] 으로 감싼다([collectValues] KDoc §@Transactional 의도적 생략 참조,
     * [workflowStateCatalog]`.listStates` 의 `Propagation.MANDATORY` 제약 때문). [issueTypeCatalog] 는
     * 자체 `@Transactional(readOnly = true)` 라 트랜잭션 밖에서도 안전하지만, 두 카탈로그 조회를 한
     * 트랜잭션 경계 안에 일관되게 묶는다. 해당 필드의 소스값 집합이 비어 있으면 그 필드의 카탈로그
     * 조회 자체를 생략한다 — 등장하지 않은 필드까지 불필요한 cross-BC 호출을 하지 않기 위함이다.
     */
    private fun buildValueSuggestions(
        projectKey: ProjectKey,
        collectedValues: Map<ValueTargetField, Set<String>>,
    ): Map<ValueTargetField, List<ValueCollectionEntry>> =
        transactionTemplate.execute {
            val statusValues = collectedValues.getValue(ValueTargetField.STATUS)
            val typeValues = collectedValues.getValue(ValueTargetField.TYPE)
            val priorityValues = collectedValues.getValue(ValueTargetField.PRIORITY)

            val statusNames =
                if (statusValues.isEmpty()) {
                    emptyList()
                } else {
                    workflowStateCatalog.listStates(projectKey, issueTypeKey = null).map { it.name }
                }
            val typeNames = if (typeValues.isEmpty()) emptyList() else issueTypeCatalog.listTypes().map { it.name }

            mapOf(
                ValueTargetField.STATUS to suggestEntries(statusValues, statusNames),
                ValueTargetField.TYPE to suggestEntries(typeValues, typeNames),
                ValueTargetField.PRIORITY to suggestEntries(priorityValues, ImportRowParser.canonicalPriorityNames),
            )
        } ?: error("collectValues 자동추천 계산 결과가 null 입니다 — unexpected (transactionTemplate.execute 반환)")

    /** 정규화 정확일치로 [sourceValues] 각각에 [candidateNames] 중 대응하는 원본 표기를 추천한다. */
    private fun suggestEntries(
        sourceValues: Set<String>,
        candidateNames: Collection<String>,
    ): List<ValueCollectionEntry> {
        val candidateByNormalized = candidateNames.associateBy { name -> ValueMappingNormalizer.normalize(name) }
        return sourceValues.sorted().map { sourceValue ->
            ValueCollectionEntry(sourceValue = sourceValue, suggestedTargetValue = candidateByNormalized[sourceValue])
        }
    }

    /**
     * [confirm] 의 `valueMappings` 를 정규화 + 검증한 뒤, 저장 가능한 `(대상 필드, 소스 값) -> 대상 값`
     * 맵으로 변환한다([confirm] KDoc §값 매핑 검증 참조). [issueTypeCatalog] cross-BC I/O 를 포함하므로
     * 트랜잭션 **밖**에서 호출한다. [valueMappings] 에 [ValueTargetField.TYPE] 항목이 하나도 없으면
     * [issueTypeCatalog] 조회 자체를 생략한다(`by lazy` — 불필요한 cross-BC 호출 방지).
     *
     * @throws ImportValueMappingInvalidException 정규화 시 중복되는 (대상 필드, 소스 값) 조합이 있거나,
     *   TYPE/PRIORITY 대상 값이 카탈로그/canonical 5 와 불일치하거나, STATUS 대상 값이 공백인 경우.
     */
    private fun validateValueMappings(
        valueMappings: List<Triple<ValueTargetField, String, String>>,
    ): Map<Pair<ValueTargetField, String>, String> {
        if (valueMappings.isEmpty()) return emptyMap()

        val normalized =
            valueMappings.map { (targetField, sourceValue, targetValue) ->
                Triple(targetField, ValueMappingNormalizer.normalize(sourceValue), targetValue)
            }

        val errors = mutableListOf<MappingIssue>()
        errors += findDuplicateValueMappingIssues(normalized)

        // TYPE 항목이 하나도 없으면 issueTypeCatalog 조회 자체를 생략한다(불필요한 cross-BC 호출 방지).
        val typeNamesByNormalized: Map<String, String> by lazy {
            issueTypeCatalog.listTypes().associate { type -> ValueMappingNormalizer.normalize(type.name) to type.name }
        }
        val priorityNamesByNormalized =
            ImportRowParser.canonicalPriorityNames.associateBy { name -> ValueMappingNormalizer.normalize(name) }

        val resolved = mutableMapOf<Pair<ValueTargetField, String>, String>()
        normalized.forEach { (targetField, sourceValue, targetValue) ->
            // FR7 필드별 비대칭 검증 + C1 canonical 치환 — TYPE/PRIORITY 는 카탈로그/canonical 값의
            // 정확한 표기로 치환(없으면 null), STATUS 는 공백이 아니면 원본 그대로(공백이면 null).
            val resolvedValue =
                when (targetField) {
                    ValueTargetField.STATUS -> targetValue.takeIf { it.isNotBlank() }
                    ValueTargetField.TYPE -> typeNamesByNormalized[ValueMappingNormalizer.normalize(targetValue)]
                    ValueTargetField.PRIORITY ->
                        priorityNamesByNormalized[ValueMappingNormalizer.normalize(targetValue)]
                }
            if (resolvedValue == null) {
                errors +=
                    MappingIssue(
                        ImportValueMappingInvalidException.TARGET_VALUE_NOT_FOUND,
                        "존재하지 않거나 비어 있는 대상 값입니다: $targetField/$targetValue",
                        sourceValue,
                    )
            } else {
                resolved[targetField to sourceValue] = resolvedValue
            }
        }

        if (errors.isNotEmpty()) {
            throw ImportValueMappingInvalidException(errors)
        }
        return resolved
    }

    /** 정규화 후 (대상 필드, 소스 값) 조합이 둘 이상 존재하는 경우를 찾는다([DUPLICATE_VALUE_MAPPING], E6). */
    private fun findDuplicateValueMappingIssues(normalized: List<ValueMappingTriple>): List<MappingIssue> {
        return normalized
            .groupBy { (targetField, sourceValue, _) -> targetField to sourceValue }
            .filterValues { entries -> entries.size > 1 }
            .map { (key, _) ->
                val (targetField, sourceValue) = key
                MappingIssue(
                    ImportValueMappingInvalidException.DUPLICATE_VALUE_MAPPING,
                    "정규화 시 중복되는 값 매핑입니다: $targetField/$sourceValue",
                    sourceValue,
                )
            }
    }

    companion object {
        private const val FORMAT_JSON = "JSON"
    }
}
