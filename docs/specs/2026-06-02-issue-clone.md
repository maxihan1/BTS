<!-- 스펙: FR-IS-06 이슈 클론 — POST /issues/{key}/clone. 복사/리셋 경계 + CloneOptions + 엣지 케이스 + NFR -->

# FR-IS-06 이슈 클론 — 스펙

> slug. `issue/clone`
> 대상 FR. SDD `docs/sdd/02-requirements.md §FR-IS-06` + `docs/plan/product/issue-tracking.md §2.3.1`
> 일자. 2026-06-02
> 작성. backend-engineer agent (Maxi 승인 대기)
> 관련 ADR. `docs/decisions/2026-06-02-issue-clone-semantics.md`
> 범위. **백엔드 API (D1~D5)**. 프론트 UI(D6)·E2E(D7)는 후속 작업.

## 0. 범위 한정 (중요)

- 클론은 원본 이슈의 **필드만** 복사한다. 첨부/Watcher/댓글은 issue-tracking BC에 **미구현**이므로 이번 범위에서 제외(ADR §3).
- 클론본은 원본과 **같은 프로젝트**에 생성한다. 다른 프로젝트로의 복제는 FR-MV(이슈 이동)와 함께 다룸(ADR §4).
- 신규 테이블/마이그레이션 없음. 기존 `issues` 테이블·`Issue.create`·`IssueRepository.insert` 재사용 (D3 "활용만").

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 클론 — 정상 흐름 (옵션 미지정, 기본값)

```
Given. ATLAS-1 이슈 존재 (summary="결제 버그", description="...", typeId=Bug, priority=2,
       labels=["payment","urgent"], environment="prod", impact=1, assigneeId=U2),
       호출자가 ATLAS 프로젝트 VIEW(원본) + CREATE(대상) 권한 보유
When.  POST /api/v1/issues/ATLAS-1/clone  (body 없음 또는 {})
Then.  201 Created + Location: /api/v1/issues/ATLAS-2
       AND  본문 { key:"ATLAS-2", id:<새 uuid>, summary:"결제 버그", description:"...",
                  typeInfo:{...Bug}, priority:2, labels:["payment","urgent"],
                  environment:"prod", impact:1, assigneeId:U2,
                  currentStateKey:<워크플로우 초기상태>, reporterId:<호출자>, version:1 }
       AND  ATLAS 프로젝트 key_sequence +1
       AND  pgmq outbox 에 IssueCreated 이벤트 enqueue (같은 트랜잭션)
```

### S2. 클론 — 담당자 제외 (includeAssignee=false)

```
Given. ATLAS-1 (assigneeId=U2) 존재, 호출자 권한 보유
When.  POST /api/v1/issues/ATLAS-1/clone  { includeAssignee: false }
Then.  201 Created + 클론본 assigneeId = null (미할당)
       AND  나머지 복사 필드는 S1과 동일
```

### S3. 클론 — 제목 덮어쓰기 (summaryOverride)

```
Given. ATLAS-1 (summary="결제 버그") 존재, 호출자 권한 보유
When.  POST /api/v1/issues/ATLAS-1/clone  { summaryOverride: "결제 버그 (재현 케이스)" }
Then.  201 Created + 클론본 summary = "결제 버그 (재현 케이스)"
       AND  나머지 복사 필드는 원본과 동일
```

### S4. 클론 — 상태/이력은 새로 시작

```
Given. ATLAS-1 이 "In Progress" 상태이고 전이 이력 보유
When.  POST /api/v1/issues/ATLAS-1/clone
Then.  클론본 currentStateKey = 워크플로우 초기 상태 (원본의 "In Progress" 복사 안 함)
       AND  클론본 version=1, createdAt/updatedAt=클론 시각 (원본 값 복사 안 함)
       AND  클론본 reporterId = 호출자 (원본 reporter 복사 안 함)
```

### S5. 원본 없음 — 404

```
Given. ATLAS-999 가 존재하지 않거나 소프트 삭제됨
When.  POST /api/v1/issues/ATLAS-999/clone
Then.  404 Not Found + errorCode=ISSUE_NOT_FOUND
```

### S6. 권한 없음 — 403

```
Given. 호출자가 원본 VIEW 또는 대상 프로젝트 CREATE 권한 없음
When.  POST /api/v1/issues/ATLAS-1/clone
Then.  403 Forbidden + errorCode=ACCESS_DENIED
       AND  key_sequence 변동 없음, 클론본 미생성
```

## 2. 기능 요구사항 (FR)

- **FR-C1.** `POST /api/v1/issues/{key}/clone` 는 원본 이슈의 `summary, description, typeId, priority, labels, environment, impact, assigneeId` 를 복사한 새 이슈를 같은 프로젝트에 생성한다.
- **FR-C2.** 클론본은 `id`(새 UUID), `key`(새 시퀀스), `reporterId`(호출자), `currentStateKey`(워크플로우 초기상태), `version=1`, `createdAt/updatedAt`(now)를 새로 시작한다.
- **FR-C3.** `CloneOptions.includeAssignee`(기본 true)가 false면 클론본 `assigneeId=null`.
- **FR-C4.** `CloneOptions.summaryOverride`(선택)가 주어지면 클론본 summary로 사용. 미지정/공백이면 원본 summary. override는 기존 summary 불변식(1~255자, 공백만 불가) 검증을 동일 적용.
- **FR-C5.** 클론은 단일 트랜잭션. insert + IssueCreated 이벤트 발행이 원자적으로 묶인다(기존 createIssue 패턴 준수).
- **FR-C6.** 요청 body는 선택적. body 없음 / `{}` / 일부 필드만 모두 허용(기본값 적용).
- **FR-C7.** 응답은 기존 생성 API와 동일하게 `201 Created` + `Location` 헤더 + `DataResponse<IssueResponse>`. 단건 조회와 동일하게 `descriptionHtml` 렌더 포함.

## 3. 비기능 요구사항 (NFR)

- **NFR-1.** 클론 1건 p95 < 150ms (단일 이슈 read + insert + event enqueue. createIssue와 동급).
- **NFR-2.** 동시 클론 시 key 충돌 없음 — `incrementKeySequence`의 `pg_advisory_xact_lock` 직렬화 재사용.
- **NFR-3.** BC 격리 — 다른 BC 직접 import 금지. 워크플로우 초기 상태는 기존 `WorkflowKeyResolver` 포트로만 조회.
- **NFR-4.** 권한 가드는 기존 `IssuePermissionResolver` 포트로 추상화(prod 실판정은 FR-PM-03 이연, 기존 컨벤션과 동일).

## 4. API 인터페이스 (REST)

### 요청

```
POST /api/v1/issues/{key}/clone
Content-Type: application/json   (body 선택)

{
  "includeAssignee": true,          // optional, default true
  "summaryOverride": "..."          // optional, null/생략 시 원본 summary
}
```

### 응답

| 상황 | 코드 | 본문 |
|---|---|---|
| 성공 | 201 Created | `DataResponse<IssueResponse>` + `Location: /api/v1/issues/{newKey}` |
| 원본 없음/삭제됨 | 404 | ProblemDetail `ISSUE_NOT_FOUND` |
| 권한 없음 | 403 | ProblemDetail `ACCESS_DENIED` |
| summaryOverride 검증 실패 | 400 | ProblemDetail `VALIDATION_FAILED` |
| 미인증 | 401 | ProblemDetail `UNAUTHENTICATED` |
| 프로젝트 워크플로우 미설정 | 422 | ProblemDetail `WORKFLOW_NOT_CONFIGURED` |
| 내부 오류(이벤트 발행 실패 등) | 500 | ProblemDetail `INTERNAL_ERROR` (롤백) |

신규 errorCode 추가 없음 — 기존 `IssueErrorCodes` 재사용.

## 5. 데이터 모델 변경

- **없음.** 기존 `issues` 테이블에 INSERT만. 마이그레이션 파일 추가 없음.

## 6. 엣지 케이스

- **EC-1.** 원본 summary가 정확히 255자 + summaryOverride 미지정 → 그대로 복사(255자 허용 경계).
- **EC-2.** summaryOverride가 256자 → 400 VALIDATION_FAILED (도메인 불변식 재사용).
- **EC-3.** summaryOverride가 공백만(`"   "`) → 빈 값 취급 안 함. 공백 trim 후 빈 문자열이면 "미지정"으로 보고 원본 summary 사용 (S3과 일관). (PATCH의 sentinel과 달리 clone override는 "유효한 새 제목" 의미만 가짐.)
- **EC-4.** 원본 labels가 비어있음(`[]`) → 클론본도 `[]`.
- **EC-5.** 원본 assigneeId=null + includeAssignee=true → 클론본도 null (복사할 담당자 없음).
- **EC-6.** 원본 impact=null / environment=null → 클론본도 null.
- **EC-7.** 동시에 같은 원본을 2번 클론 → 각각 다른 새 key, 둘 다 성공.
- **EC-8.** 원본 typeId가 그 사이 비활성화됨 → 클론은 원본 typeId를 그대로 복사(이미 존재하는 이슈의 타입 보존). 활성 타입 재검증은 하지 않음(생성 시점 typeId가 아니라 복사이므로). — 단, 이 결정은 brainstorming/리뷰에서 재확인 필요.
- **EC-9.** body가 잘못된 JSON → 400 (Spring 표준 메시지).

## 7. 제약 조건

- 같은 프로젝트로만 클론. `targetProjectKey` 옵션 없음.
- 첨부/Watcher/댓글 복사 안 함 (미구현).
- TDD red→green→refactor 강제. 도메인/application/web 각 계층 테스트 선행.

## 8. 측정 가능한 완료 기준

- [ ] `POST /api/v1/issues/{key}/clone` 가 S1~S6 시나리오대로 동작.
- [ ] 단위 테스트 — application service clone 흐름(복사 경계, includeAssignee, summaryOverride, 404/403).
- [ ] 통합 테스트(Testcontainers) — 실제 DB에 클론 INSERT + key_sequence 증가 + 이벤트 enqueue 확인.
- [ ] 웹 계층 테스트 — 201/Location/본문, 404/403/400 매핑.
- [ ] `./gradlew test ktlintCheck detekt` green, 기존 테스트 회귀 0.

## Brainstorming Check

✅ 통과 (1회 iteration, BLOCKER 없음). 확정된 결정.
- EC-8 비활성 타입: `resolveTypeId` 활성 재검증 생략, 원본 typeId 그대로 복사(기존 이슈 타입 보존).
- 이벤트: 별도 `IssueCloned` 불필요. 신규 이슈이므로 기존 `IssueCreated` 발행.
- 멱등성: 클론은 비멱등(매 호출 새 이슈) — 의도된 동작.
- 권한: 원본 VIEW + 대상 프로젝트 CREATE 둘 다 필요. 기존 `findByKey`(VIEW) + CREATE assertPermission 재사용.
