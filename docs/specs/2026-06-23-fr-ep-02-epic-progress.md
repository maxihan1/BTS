# FR-EP-02 — Epic 진행률 자동 집계 — 스펙

> slug: fr-ep-02-epic-progress
> BC: issue-tracking (패키지 `com.bts.issue.epic`)
> 선행: FR-EP-01 (PR #175) — `issues.epic_id` 자기참조 FK
> 작성: 2026-06-23 (office-hours 대신 직접 기술 스펙 — 명세/ADR 확정된 FR)

## 개요

에픽(Epic, `hierarchy_level=1` 이슈)에 `epic_id`로 직속 연결된 자식 이슈들의 워크플로우
상태를 **카테고리(TODO/IN_PROGRESS/DONE)** 단위로 집계해 진행률을 자동 계산한다.
신규 테이블·컬럼 없이 기존 데이터(`issues.epic_id` + `issues.current_state_key`)를 활용한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 정상 진행률 조회
- **Given** Epic `PROJ-1`에 자식 10건이 연결됨 (DONE 4 / IN_PROGRESS 3 / TODO 3)
- **When** BROWSE 권한 보유 사용자가 `GET /api/v1/epics/PROJ-1/progress` 호출
- **Then** `{ total:10, done:4, donePercentage:40, byCategory:{TODO:3, IN_PROGRESS:3, DONE:4} }` 200 반환

### S2. 빈 에픽 (자식 0)
- **Given** Epic `PROJ-2`에 연결된 자식 없음
- **When** 진행률 조회
- **Then** `{ total:0, done:0, donePercentage:0, byCategory:{TODO:0, IN_PROGRESS:0, DONE:0} }` 200 (에러 아님)

### S3. 자식 타입별 워크플로우 상이
- **Given** Epic의 자식이 Story 2건(Story 워크플로우)·Bug 3건(Bug 워크플로우)이고 각 타입의 DONE 상태 키가 다름
- **When** 진행률 조회
- **Then** 각 자식을 **자기 타입의 워크플로우 카테고리**로 판정해 집계 (Story 자식은 Story 워크플로우의 DONE 기준, Bug 자식은 Bug 워크플로우의 DONE 기준)

### S4. visibility 필터 — 못 보는 자식 제외
- **Given** Epic에 자식 10건 중 3건이 사용자의 보안 등급(accessibleLevels)으로 접근 불가
- **When** 진행률 조회
- **Then** 보이는 7건만 모수로 집계 (total=7). 숨겨진 3건은 카운트·total 어디에도 노출 안 됨 (존재 추론 차단)

### S5. 권한 없음
- **Given** 해당 프로젝트 BROWSE 권한 없는 사용자
- **When** 진행률 조회
- **Then** 403 (IssueAccessDeniedException)

### S6. 에픽 미존재/소프트삭제
- **Given** 존재하지 않거나 soft-delete된 키
- **When** 진행률 조회
- **Then** 404 (EpicChildNotFoundException — 존재 숨김, 403 금지. 보안 N1)

### S7. 미인증
- **Given** 인증 토큰 없음
- **When** 진행률 조회
- **Then** 401 (CurrentActor.current() ResponseStatusException)

## 기능 요구사항 (FR)

- **FR1**. `GET /api/v1/epics/{key}/progress` 엔드포인트를 `IssueEpicController`에 추가한다.
- **FR2**. 진행률 모수는 `IssueRepository.findEpicChildren(epicId, actor, access, projectKey)`로 조회한 **직속 자식**(epic_id 직속, listChildren과 동일 쿼리). 손자(자식의 자식)는 모수 아님.
- **FR3**. 각 자식의 카테고리는 `WorkflowStateCatalog.listStates(projectKey, 자식의 issueTypeKey)`로 얻은 `WorkflowStateView` 목록에서 `currentStateKey` 일치 항목의 `category`로 판정한다.
- **FR4**. listStates는 **자식 타입(issueTypeKey)별로 1회만** 호출하고 결과를 캐싱한다 (타입 수만큼만 조회, 자식 수만큼 호출 금지 — N+1 방지).
- **FR5**. 응답은 `total`, `done`(=DONE 카운트), `donePercentage`(반올림 정수), `byCategory{TODO, IN_PROGRESS, DONE}`를 포함한다.
- **FR6**. `donePercentage = total>0 ? Math.round(done*100.0/total) : 0`.
- **FR7**. 진행률 집계 권한 게이트는 listChildren과 동형 — BROWSE(Project scope) + accessibleLevels SQL 푸시다운.

## 비기능 요구사항 (NFR)

- **NFR1 (성능)**. 자식 100건 집계 p95 < 500ms (agile-planning §NFR 측정표). findEpicChildren 1쿼리 + listStates 타입수(보통 2~3)회로 충족.
- **NFR2 (보안)**. 못 보는 자식은 total/byCategory 어디에도 반영 금지 (정보 누출 0). findEpicChildren의 accessibleLevels 푸시다운으로 보장.
- **NFR3 (정합)**. category 판정은 항상 자식의 실제 타입 워크플로우 기준 (FR-EP-01 epic-children 응답의 currentStateKey와 일관).

## API 인터페이스 (REST)

```
GET /api/v1/epics/{key}/progress
권한: BROWSE (Project scope) — {key}의 프로젝트
응답 200:
{
  "total": 10,
  "done": 4,
  "donePercentage": 40,
  "byCategory": { "TODO": 3, "IN_PROGRESS": 3, "DONE": 4 }
}
오류: 401(미인증) / 403(BROWSE 없음) / 404(에픽 미존재·소프트삭제)
```

- 경로 일관성. FR-EP-01의 자식 연결/조회는 `/api/v1/issues/{key}/epic-children`(이슈 관점)이나, 진행률은 ADR 2026-06-22 §영향이 예약한 `/api/v1/epics/{key}/progress`(에픽 관점)를 따른다. 동일 `IssueEpicController`에 매핑하되 별도 base path(`/api/v1/epics`)를 추가한다.
- 응답 DTO. 신규 `EpicProgressResponse(total, done, donePercentage, byCategory: EpicProgressByCategory)` — `byCategory`는 `{ todo, inProgress, done }` 3 정수 필드(JSON 키는 TODO/IN_PROGRESS/DONE 또는 todo/inProgress/done — 프론트 계약과 일치, 구현 시 JSON naming 확정).

## 데이터 모델 변경

**없음.** 기존 `issues.epic_id`(V028) + `issues.current_state_key` + `issue_types.hierarchy_level` 활용. 마이그레이션·init_codegen 변경 없음.

## 엣지 케이스

- **EC1. 빈 에픽** — 자식 0 → total=0, 모든 카운트 0, donePercentage=0 (S2).
- **EC2. 매핑 안 되는 상태키** — 자식의 `currentStateKey`가 해당 타입 listStates 결과에 없음(stale 상태키 또는 타입 워크플로우 미할당으로 빈 리스트) → **TODO 카테고리로 분류**(비-DONE, 보수적 — 진행률을 과대평가하지 않음). 로그 debug 남김.
- **EC3. 비표준 category 값** — `WorkflowStateView.category`가 TODO/IN_PROGRESS/DONE 외 값(이론상 String)일 경우 TODO로 폴백. (현 StateCategory enum은 3개뿐이라 실제 발생 0이나 방어.)
- **EC4. 비-Epic 키로 조회** — `{key}`가 Epic 타입이 아닌 일반 이슈여도 listChildren 동형으로 타입 검증 생략. 그 이슈를 epic_id로 가리키는 자식이 없으면 빈 진행률(EC1) 반환. BROWSE 게이트가 무권한 진입을 차단하므로 존재 probe 누출 없음.
- **EC5. 자식 전부 DONE** — donePercentage=100.
- **EC6. 반올림** — done=1/total=3 → donePercentage=33 (Math.round(33.33)=33). done=2/total=3 → 67.

## 제약 조건

- **C1. BC 격리**. issue-tracking → shared-kernel `WorkflowStateCatalog` SPI만 호출. project-workflow 직접 import 금지 (board adapter 선례).
- **C2. 트랜잭션**. progress 서비스 메서드는 `@Transactional(readOnly=true)` — listStates의 `Propagation.MANDATORY` 충족.
- **C3. 예외 핸들러**. `EpicChildExceptionHandler(assignableTypes=[IssueEpicController])`가 자동 커버. 신규 예외 불필요(404/403/401은 기존 재사용).
- **C4. 직속 자식만**. 손자 미포함 (epic_id 직속 1레벨, ADR §폐기대안 B 근거와 일관).

## 프론트엔드 (D6)

- Epic 상세 페이지(FR-EP-01 D6가 만든 이슈 상세 재사용 화면)에 **진행률 섹션** 추가.
- **진행률 막대** — 3색 구간(DONE/IN_PROGRESS/TODO 비율) 단일 가로 바 + `donePercentage%` 텍스트 + 카운트(`4/10 완료`).
- 자식 0건이면 "자식 이슈 없음" 표시(0% 빈 바).
- 기존 진행률/막대 컴포넌트 컨벤션 따름(신규 디자인 시안 불필요 — 표준 progress bar). Zod 스키마는 백엔드 `EpicProgressResponse` 계약과 정확히 일치(frontend-zod-backend-dto-contract-gap 교훈).
- 데이터 패칭 — Epic 상세 진입 시 progress 조회. 자식 연결/해제(FR-EP-01) mutation 후 progress invalidate(cross-mutation queryKey 공유).

## 측정 가능한 완료 기준

- [ ] `GET /api/v1/epics/{key}/progress` 200 — S1 시나리오 정확 집계 (통합테스트)
- [ ] 빈 에픽 0% (S2), 타입별 판정 (S3), visibility 제외 (S4) 통합테스트 통과
- [ ] 401/403/404 (S5~S7) 통합테스트 통과
- [ ] listStates 호출이 자식 타입 수 이하 (N+1 부재) 단위테스트 검증
- [ ] EC2(매핑 실패→TODO)·EC6(반올림) 단위테스트 통과
- [ ] 프론트 진행률 막대 렌더 + Zod 계약 일치 + E2E(진행률 표시)
- [ ] product/agile-planning.md §7.2 D1~D7 `[x]` 마킹

## Brainstorming Check

✅ 통과 (직접 self-review). 점검 항목과 결과.
- **누락 요구사항** — visibility 모수 기준(전체 vs 보이는 것)을 S4/NFR2로 명문화(보이는 자식만, 누출 0). 손자 포함 여부를 C4로 명문화(직속만).
- **모호 표현** — 매핑 안 되는 상태키 처리를 EC2로 확정(TODO 분류). 반올림 규칙 FR6/EC6로 확정.
- **가정 누락** — listStates MANDATORY 트랜잭션 요구를 C2로 명시. 비-Epic 키 조회를 EC4로 명시.
- **엣지 미커버** — 빈 에픽(EC1)·전부 완료(EC5)·비표준 category(EC3) 커버.
- **Maxi 결정 필요 gap** — 없음(진행률 정의·타입별 판정·빈 에픽은 도메인 단계에서 이미 확정).
