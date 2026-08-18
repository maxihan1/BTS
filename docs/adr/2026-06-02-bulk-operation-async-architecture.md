# ADR: 이슈 일괄 작업(FR-IS-05)을 비동기 pgmq job + 영속 BulkOperation으로 구현

> 날짜: 2026-06-02
> 상태: 제안 (게이트 1 승인 대기)
> BC: issue-tracking
> 관련: FR-IS-05, [2026-05-22-pgmq-postgres-image](2026-05-22-pgmq-postgres-image.md)

## 맥락

FR-IS-05는 여러 이슈에 같은 변경(필드 편집 또는 상태 전환)을 한 번에 적용한다. 실행 방식으로 두 갈래가 있었다.

- **A. 동기** — 한 HTTP 요청 안에서 내부 청크로 나눠 처리하고 결과 리포트를 즉시 반환. 신규 인프라 없음. 한 번에 최대 N건(예 100) 상한.
- **B. 비동기** — 요청은 접수만 하고 pgmq job이 백그라운드에서 처리. 진행률/결과는 별도 조회 API. Jira의 Bulk Change와 동일한 UX.

현 규모는 사내 1,000명, 일괄 작업은 통상 수십~수백 건이다. 원래 명세 D3는 "기존 테이블 활용, 신규 테이블 없음"을 전제했다(= A 가정).

## 결정

**B(비동기)를 채택한다.** Maxi가 A/B 트레이드오프(신규 테이블·작업 큐·조회 API, 명세 D3 수정, 범위 ≈2배 증가)를 인지한 뒤 명시적으로 선택했다.

- 영속 Aggregate **BulkOperation** + 하위 **BulkOperationItem** 도입.
- 처리는 **pgmq job**으로 백그라운드 실행.
- 트랜잭션 정책은 **best-effort 부분 성공** — 이슈별 독립 트랜잭션, 실패 항목만 사유와 함께 기록(Jira 동일).
- 각 이슈 처리는 기존 `IssueApplicationService.updateIssue`/`transitionIssue` 도메인 로직을 재사용한다(도메인 우회 금지). 일괄 전환은 기존 `WorkflowTransitionPort`(project-workflow 위임)를 이슈별로 호출한다.

## 트레이드오프

**채택(B)의 비용**
- 신규 테이블 2개(`bulk_operations`, `bulk_operation_items`) + pgmq 큐 1개 → 명세 D3 수정.
- prod에 pgmq **consumer(백그라운드 job 소비) 선례가 없음** → 워커 구동·실패 격리·at-least-once 멱등 처리를 처음 정립해야 함.
- 범위·일정 약 2배.

**채택(B)의 이점**
- 수천 건 이상 대량에 견고(요청 타임아웃과 분리).
- 사용자가 기다리지 않음. 완료 시 알림으로 결과 수신하는 UX(notification BC 연계) 확장 가능.

**기각(A)의 이유**
- 단순하지만 대량 시 요청 타임아웃·락 보유시간 위험. 건수 상한으로 사용성 제약.
- Maxi가 비동기 UX와 확장성을 우선.

## 영향

- 멱등성/재시도, 결과 보존 기간(TTL), 혼합 상태 전환 처리, 청크 크기/건수 상한은 spec(D2)에서 확정한다.
- glossary에 "일괄 작업(Bulk Operation)", "일괄 작업 항목(Bulk Operation Item)" 추가.

## 일괄 작업 레코드 삭제 정책 — 하드 삭제 채택 (NEVER-7 예외 근거)

`bulk_operations` / `bulk_operation_items` 레코드는 완료 후 30일이 경과하면 하드 삭제한다.

**소프트 삭제가 불필요한 이유.**
일괄 작업 레코드는 영구 이력(감사 로그)이 아니라 일시적 운영 로그다.
완료된 일괄 작업의 식별자(`bulk_operations.id`)는 `issues` 테이블에 참조되지 않으며,
`IssueKey` 처럼 재사용 금지가 강제되는 식별자도 아니다.
30일 경과 시 사용자도 조회하지 않는 운영 부산물이므로 소프트 삭제(`deleted_at`) 없이
직접 DELETE 하는 것이 적합하다.

이 결정은 `DATA.md §3 하드 삭제 허용 영역` 의 "세션/임시 토큰 — TTL 만료 후 GC" 패턴과
동일한 근거를 따르며, Maxi가 명시적으로 ADR 필수 항목으로 요청했다 (게이트2 리뷰 F7).
