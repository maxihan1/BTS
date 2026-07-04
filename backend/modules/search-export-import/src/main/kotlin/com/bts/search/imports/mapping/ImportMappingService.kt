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
import com.bts.search.imports.parse.ImportRowParser
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

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
 * @param importMappingRepository 필드 매핑 영속 저장소([ImportMappingRepository.saveAll]).
 * @param importJobRepository Import 작업 저장소. 소유확인([ImportJobRepository.findByIdForRequester]) +
 *   CAS 전이([ImportJobRepository.transitionToPending])에 사용.
 * @param enqueuePublisher pgmq 큐에 작업 ID 를 발행하는 아웃바운드 어댑터.
 * @param storage Import 원본 오브젝트 스토리지 포트. 헤더/전량 재읽기용.
 * @param transactionTemplate CAS+saveAll+enqueue 구간의 프로그래밍 방식 트랜잭션 경계.
 * @param userLookupPort 사용자 실재 확인 + 이메일→UUID/UUID→표시명 일괄 조회 cross-BC 포트.
 *   [collectUsers] 의 추천 계산과 `confirm` 의 대상 사용자 실재 검증에 사용한다.
 * @param importUserMappingRepository 확정 사용자 매핑 영속 저장소([ImportUserMappingRepository.saveAll]).
 * @param parser CSV/JSON 스트리밍 파서. [ImportRowParser] 는 Spring 빈으로 등록되어 있지 않으므로
 *   (`ImportJobProcessor` 의 동일 기본값 패턴을 따라) 기본값으로 직접 인스턴스화한다.
 */
@Service
class ImportMappingService(
    private val importMappingRepository: ImportMappingRepository,
    private val importJobRepository: ImportJobRepository,
    private val enqueuePublisher: ImportJobEnqueuePublisher,
    private val storage: ImportObjectStoragePort,
    private val transactionTemplate: TransactionTemplate,
    private val userLookupPort: UserLookupPort,
    private val importUserMappingRepository: ImportUserMappingRepository,
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
     * @return `PENDING` 상태로 전이된 [ImportJob] — [confirm] 호출 직전 조회한 스냅샷에
     *   status/expiresAt/dryRun 만 갱신한 사본이며, CAS 이후 재조회하지 않는다(그 외 필드는 확정으로
     *   바뀌지 않는다).
     * @throws ResponseStatusException(404) 작업이 없거나 [actor] 소유가 아닌 경우.
     * @throws ImportMappingStateConflictException 사전확인 시점 또는 CAS 시점(TOCTOU 포함)에 작업
     *   상태가 [ImportJobStatus.AWAITING_MAPPING] 이 아닌 경우.
     * @throws ImportMappingInvalidException 필드 매핑 검증 실패 시(422 위임).
     * @throws ImportUserMappingInvalidException 사용자 매핑 검증 실패 시(422 위임).
     */
    fun confirm(
        jobId: ImportJobId,
        actor: UUID,
        fieldMappings: Map<String, String>,
        dryRun: Boolean,
        userMappings: List<Pair<String, UUID?>> = emptyList(),
    ): ImportJob {
        val job = requireOwnedAwaitingMapping(jobId, actor)
        val result = computeValidationResult(job, fieldMappings)
        if (!result.valid) {
            throw ImportMappingInvalidException(result.errors)
        }
        val normalizedUserMappings = validateUserMappings(userMappings)

        transactionTemplate.execute {
            if (!importJobRepository.transitionToPending(jobId, dryRun)) {
                throw ImportMappingStateConflictException()
            }
            importMappingRepository.saveAll(jobId, fieldMappings)
            importUserMappingRepository.saveAll(jobId, normalizedUserMappings)
            enqueuePublisher.enqueue(jobId)
        }

        log.info(
            "import_mapping_confirmed jobId={} actor={} fieldCount={} userMappingCount={} dryRun={}",
            jobId.value,
            actor,
            fieldMappings.size,
            normalizedUserMappings.size,
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

    companion object {
        private const val FORMAT_JSON = "JSON"
    }
}
