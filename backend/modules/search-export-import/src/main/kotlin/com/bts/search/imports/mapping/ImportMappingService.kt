// FR-IM-02 Import 필드 매핑 검증/확정 서비스 — AWAITING_MAPPING 소유확인 + 헤더 재읽기 검증 + CAS 우선 트랜잭션
package com.bts.search.imports.mapping

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.parse.ImportRowParser
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
 * ### dryRun — 순방향 호환 파라미터
 *
 * [confirm] 의 `dryRun` 파라미터는 API 계약(`POST /imports/{jobId}/mapping` body 의 optional
 * `dryRun` 필드, 웹 계층 sibling task)을 그대로 받는다. `AWAITING_MAPPING` job 의 [ImportJob.dryRun]
 * 은 이미 analyze 단계에서 확정되어 불변이고, 실제 dry-run 판정은 워커가 그 값을 사용해 수행한다
 * (스펙 §엣지케이스 "dryRun 확정 → 저장·전이·enqueue 동일, 워커가 dryRun 검증만"). 이 서비스가 접근
 * 가능한 [ImportJobRepository] 에는 dryRun 을 갱신하는 메서드가 없으므로(PR-A 범위 밖) 이 파라미터는
 * 감사 로깅에만 사용하고 별도로 저장하지 않는다.
 *
 * @param importMappingRepository 필드 매핑 영속 저장소([ImportMappingRepository.saveAll]).
 * @param importJobRepository Import 작업 저장소. 소유확인([ImportJobRepository.findByIdForRequester]) +
 *   CAS 전이([ImportJobRepository.transitionToPending])에 사용.
 * @param enqueuePublisher pgmq 큐에 작업 ID 를 발행하는 아웃바운드 어댑터.
 * @param storage Import 원본 오브젝트 스토리지 포트. 헤더 재읽기용.
 * @param transactionTemplate CAS+saveAll+enqueue 구간의 프로그래밍 방식 트랜잭션 경계.
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
     * @param jobId 확정 대상 Import 작업 식별자.
     * @param actor 요청자 UUID. 소유확인에 사용.
     * @param fieldMappings 소스 필드 이름 → [TargetField.key](또는 [TargetField.IGNORE_KEY]) 매핑.
     * @param dryRun API 계약 순방향 호환 파라미터(클래스 KDoc §dryRun 참조) — 감사 로깅에만 사용한다.
     * @return `PENDING` 상태로 전이된 [ImportJob] — [confirm] 호출 직전 조회한 스냅샷에 status/expiresAt
     *   만 갱신한 사본이며, CAS 이후 재조회하지 않는다(다른 필드는 확정으로 바뀌지 않는다).
     * @throws ResponseStatusException(404) 작업이 없거나 [actor] 소유가 아닌 경우.
     * @throws ImportMappingStateConflictException 사전확인 시점 또는 CAS 시점(TOCTOU 포함)에 작업
     *   상태가 [ImportJobStatus.AWAITING_MAPPING] 이 아닌 경우.
     * @throws ImportMappingInvalidException 필드 매핑 검증 실패 시(422 위임).
     */
    fun confirm(
        jobId: ImportJobId,
        actor: UUID,
        fieldMappings: Map<String, String>,
        dryRun: Boolean,
    ): ImportJob {
        val job = requireOwnedAwaitingMapping(jobId, actor)
        val result = computeValidationResult(job, fieldMappings)
        if (!result.valid) {
            throw ImportMappingInvalidException(result.errors)
        }

        transactionTemplate.execute {
            if (!importJobRepository.transitionToPending(jobId)) {
                throw ImportMappingStateConflictException()
            }
            importMappingRepository.saveAll(jobId, fieldMappings)
            enqueuePublisher.enqueue(jobId)
        }

        log.info(
            "import_mapping_confirmed jobId={} actor={} fieldCount={} dryRun={}",
            jobId.value,
            actor,
            fieldMappings.size,
            dryRun,
        )
        return job.copy(status = ImportJobStatus.PENDING, expiresAt = null)
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

    companion object {
        private const val FORMAT_JSON = "JSON"
    }
}
