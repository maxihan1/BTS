# ADR — 일괄 전이 가용 전이 교집합은 서버가 계산한다 (Jira 방식)

> 날짜: 2026-06-02
> 상태: 채택
> BC: issue-tracking
> 관련: [[2026-06-02-bulk-operation-async-architecture]] · [[2026-06-02-issue-permission-query-api]] (서버가 정답지 선례) · [[2026-05-28-workflow-transition-identity-policy]]

## 맥락

FR-IS-05 D6에서 일괄 상태 전이 Dialog는 선택한 이슈마다 `GET /api/v1/issues/{key}/transitions`를 호출해 가용 전이를 받고, 프론트의 순수 함수 `intersectTransitions`로 toStateKey 기준 교집합을 계산했다.

이 방식은 선택 이슈 수만큼 동시 요청을 발생시킨다(`Promise.allSettled(issueKeys.map(fetch))`, 최대 1000건). 브라우저의 호스트당 동시 연결 한도(보통 6)를 초과해 요청이 직렬 대기하고, 서버도 순간 부하를 받는다. FR-IS-05 D6 후속 항목으로 fan-out 상한이 남아 있었다.

## 결정

가용 전이 교집합 계산을 **서버로 이전**한다. Jira의 Bulk Change가 서버 주도 마법사로 공통 전이를 한 번에 계산하는 방식과 동일하며, FR-PM-02에서 확립한 **"프론트가 하드코딩하지 않고 서버가 정답지"** 원칙([[2026-06-02-issue-permission-query-api]])과 일관된다.

- 신규 엔드포인트: `POST /api/v1/issues/bulk-transitions/available` body `{ issueKeys: string[] }` → `{ data: { transitions: TransitionItem[], unresolvedIssueKeys: string[] } }`.
- 서버는 issueKey별 `availableTransitions(actor, key)`(기존 application 서비스 재사용)를 호출해 toStateKey 기준 교집합을 계산한다. 교집합 항목의 표시 정보(name/key)는 입력 순서상 첫 이슈 기준으로 보존한다(프론트 `intersectTransitions`와 동일 시맨틱).
- **부분 실패 허용(best-effort)** — not-found / 워크플로우 미설정 이슈는 교집합에서 제외하고 `unresolvedIssueKeys`로 돌려준다. 프론트는 이 값으로 기존 "일부 이슈의 전이 정보를 불러오지 못했습니다" 경고를 유지한다.
- 가용 전이 조회는 **권한 비의존**(워크플로우 FSM 기준) — 기존 `GET /{key}/transitions`와 동일 시맨틱. 권한 강제는 일괄 작업 실행 시점의 항목별 best-effort(FORBIDDEN 항목 실패)로 유지된다.

## 대안 (기각)

- **프론트 자체 동시성 limiter** — fan-out을 N개씩 제한하는 작은 유틸. 즉효이고 비용 작지만, 교집합 계산이 여전히 프론트에 남아 "서버가 정답지" 원칙과 어긋난다. Jira 모델과도 불일치.
- **외부 라이브러리(p-limit)** — 동시성 제한 기성품. 외부 의존성 추가는 Maxi 승인 필요(절대 규칙). limiter 자체가 근본 해결이 아니라 기각.

## 결과

- 프론트 BulkTransitionDialog는 1회 호출로 단순화(fan-out + `Promise.allSettled` 제거). `intersectTransitions` 순수 함수는 서버 로직의 단위 테스트 대조군 또는 제거 대상(스펙에서 결정).
- 교집합 로직이 서버 단일 출처가 되어 프론트/서버 drift 위험 제거.
