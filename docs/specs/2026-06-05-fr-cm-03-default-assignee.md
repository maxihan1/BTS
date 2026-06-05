# FR-CM-03 컴포넌트별 기본 담당자 자동 배정 — 스펙

> slug: fr-cm-03-default-assignee
> BC: issue-tracking
> 관련 ADR: docs/adr/2026-06-05-component-default-assignee-auto-assignment.md
> 작성: 2026-06-05

## 개요

이슈에 컴포넌트가 지정되면, 그 컴포넌트의 리드(`Component.leadUserId`)를 이슈의 기본 담당자
(`Issue.assigneeId`)로 자동 배정한다. 담당자가 미할당(null)일 때만 채우며, 이슈가 여러 컴포넌트에
속하면 리드가 지정된 컴포넌트 중 이름 사전순 첫 번째의 리드를 채택한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 생성 시 단일 컴포넌트 자동 배정
- Given 프로젝트 PROJ에 리드가 Alice인 컴포넌트 "결제"가 있다.
- When 사용자가 컴포넌트 "결제"를 지정해 이슈를 생성한다(담당자 미지정).
- Then 생성된 이슈의 담당자는 Alice로 자동 설정된다.

### S2. 변경 시 자동 배정 (미할당 이슈)
- Given 담당자 미할당 이슈 PROJ-10이 있고, 리드가 Bob인 컴포넌트 "알림"이 있다.
- When 사용자가 PROJ-10에 컴포넌트 "알림"을 지정한다(PATCH /components).
- Then PROJ-10의 담당자는 Bob으로 자동 설정된다.

### S3. 명시 담당자 보존 (덮어쓰기 금지)
- Given 담당자가 Carol로 명시 지정된 이슈 PROJ-11.
- When 사용자가 PROJ-11에 리드가 Bob인 컴포넌트를 지정한다.
- Then PROJ-11의 담당자는 Carol 그대로다(자동 배정 skip).

### S4. 다중 컴포넌트 — 이름 사전순 첫 번째 리드
- Given 리드가 Alice인 "결제", 리드가 Bob인 "알림" 두 컴포넌트.
- When 미할당 이슈에 두 컴포넌트를 함께 지정한다.
- Then 이름 사전순("결제" vs "알림") 첫 번째 컴포넌트의 리드가 채택된다.

### S5. 리드 없는 컴포넌트 — 자동 배정 없음
- Given 리드가 미지정(null)인 컴포넌트 "문서"만 존재.
- When 미할당 이슈에 "문서"를 지정한다.
- Then 담당자는 미할당(null) 그대로다.

### S6. 컴포넌트 전부 해제 — 기존 담당자 보존
- Given 자동 배정으로 담당자가 Alice인 이슈.
- When 사용자가 컴포넌트를 전부 해제한다(componentIds=[]).
- Then 담당자 Alice는 그대로 유지된다(자동 해제 안 함).

## 기능 요구사항 (FR)

- **FR1**. `POST /api/v1/issues`(createIssue)가 선택적 `componentIds: List<UUID>`를 수용한다.
  생성 시 컴포넌트는 FR-CM-02 `validateComponents`(같은 프로젝트 + 활성)로 검증한다.
  **주의**: 현재 createIssue는 컴포넌트 링크를 영속하지 않으므로(PATCH의 `replaceComponents`만 존재),
  생성 트랜잭션에서 `issue_components` 링크를 함께 insert해야 한다(단순 요청 필드 추가 이상).
- **FR2**. 이슈 생성 시 `assigneeId`가 null(생성 API는 담당자 미수용)이고 리드 보유 컴포넌트가
  있으면, 사전순 첫 컴포넌트의 리드를 `assigneeId`로 자동 설정한다.
- **FR3**. `PATCH /api/v1/issues/{key}/components`(changeComponents) 처리 후, 이슈 `assigneeId`가
  null이고 리드 보유 컴포넌트가 있으면 사전순 첫 컴포넌트의 리드로 자동 설정한다.
- **FR4**. `assigneeId`가 이미 지정돼 있으면(명시/이전 자동 배정 불문) 자동 배정은 동작하지 않는다.
- **FR5**. 자동 배정 후보 = 이슈의 활성 컴포넌트 중 `leadUserId != null`인 것. 후보가 없으면 변경 없음.
- **FR6**. 다중 후보 시 컴포넌트 `name` 오름차순 정렬 첫 번째를 채택한다. name 동률 시 `id` 오름차순으로
  결정적 처리.
- **FR7**. 담당자 설정은 도메인 `Issue.assignTo()`를 경유한다(서비스→repository 직행 금지).

## 비기능 요구사항 (NFR)

- **NFR1**. 자동 배정은 생성/컴포넌트 변경과 **동일 트랜잭션**에서 영속한다(이슈 + 관계 + 이벤트 한 트랜잭션).
- **NFR2**. 리드 실재는 컴포넌트 set 시점에 `UserLookupPort`로 이미 검증됨 → 자동 배정 시 재검증/추가
  쿼리 없음(불필요한 cross-BC 호출 회피).
- **NFR3**. 자동 배정 결과는 단건 조회 응답 `assigneeId`에 반영된다(별도 필드 없음, 기존 계약).
- **NFR4 (silent 처리)**. 자동 배정은 **이벤트/히스토리/알림을 발행하지 않는다**. 근거: 기존
  `changeAssignee`도 도메인 이벤트·히스토리 없이 persist + log만 하며(ground-truth 확인), IssueHistory는
  미구현이다. 자동 배정만 별도 이벤트를 내면 비일관. 알림/히스토리 연동은 별도 후속 FR(notification BC).

## API 인터페이스 (REST)

### 변경 1 — POST /api/v1/issues
요청 본문에 `componentIds` 추가(선택, 기본 빈 목록).
```jsonc
// CreateIssueRequest (확장)
{
  "projectKey": "PROJ",
  "typeId": "...",
  "summary": "...",
  "reporterId": "...",
  "componentIds": ["<uuid>", "..."]   // 신규, optional
}
```
- 응답: 기존 `IssueResponse`. 자동 배정 결과 `assigneeId` 포함(단건 경로 동형).
- 컴포넌트 검증 실패 시 422 `COMPONENT_NOT_FOUND`(FR-CM-02 선례 동형).

### 변경 2 — PATCH /api/v1/issues/{key}/components
요청/응답 형태 변경 없음(FR-CM-02 그대로). 처리 후 자동 배정 평가만 추가.
- 응답 `IssueResponse.assigneeId`가 자동 배정 결과를 반영.

## 데이터 모델 변경

- **없음**. 기존 `components.lead_user_id`(FR-CM-01) + `issue_components`(FR-CM-02) + `issues.assignee_id`
  (FR-IS-03) 재사용. 마이그레이션 없음.
- 신규 조회 필요: `ComponentRepository`가 컴포넌트 id 집합으로 (id, name, leadUserId, deletedAt) 배치
  조회(활성만). 기존 `findById(id, projectId)` 반복 또는 `findByIds` 추가 — plan에서 확정.

## 엣지 케이스

- 컴포넌트 없이 생성 → 자동 배정 없음, 담당자 null.
- 컴포넌트는 있으나 전부 리드 null → 자동 배정 없음.
- 다중 컴포넌트 일부만 리드 보유 → 리드 보유분 중 사전순 첫 번째.
- 소프트삭제된 컴포넌트는 후보 제외(활성 필터). 단 검증 단계에서 이미 활성만 통과.
- 이미 자동 배정된 담당자가 있는 이슈에 컴포넌트 추가 → 재배정 안 함(FR4, null 아님).
- 컴포넌트 전부 해제 → 담당자 유지(자동 unassign 없음, S6).
- 동시 편집(낙관락) → changeComponents의 `expectedVersion` 충돌 시 409(FR-CM-02 그대로). 자동 배정은
  같은 트랜잭션이라 부분 적용 없음.

## 제약 조건

- BC 격리: issue-tracking 단일 BC. identity-access 추가 접촉 없음(NFR2).
- 도메인 우회 금지: 담당자 설정은 `Issue.assignTo()` 경유(MEMORY patch-merge-domain-bypass).
- 권한: 생성은 `IssuePermission.CREATE`/`IssueScope.Project`, 컴포넌트 변경은 `UPDATE`/`IssueScope.Issue`
  (기존 가드 그대로). 자동 배정은 별도 권한 게이트 없음(컴포넌트 변경 권한에 종속).

## 프론트엔드 (D6 — 자동 표시)

- 이슈 생성 폼: 컴포넌트 선택 입력 추가(생성 시 componentIds 전달). 다중 셀렉터 재사용
  (ComponentMultiSelect, FR-CM-02).
- 이슈 상세: 컴포넌트 변경 mutation 후 담당자 표시가 자동 갱신(invalidate-only → 단건 GET refetch,
  MEMORY mutation-setquerydata-partial-response-flicker 준수). 별도 신규 컴포넌트 없음(담당자 표시는
  FR-IS-03 기존 UI 재사용).

## 측정 가능한 완료 기준

- 도메인 단위: 사전순 선택 + 미할당-only + 리드 null 제외 로직(S1·S3·S4·S5·S6 대응) 테스트 그린.
- Testcontainers 통합: 생성 시 자동 배정(S1), 변경 시 자동 배정(S2), 명시 담당자 보존(S3), 다중 사전순
  (S4), 리드 없음(S5), 전부 해제 보존(S6), 권한/낙관락 회귀.
- 프론트: 생성 폼 컴포넌트 입력 + 상세 자동 갱신 단위 테스트.
- E2E: 컴포넌트 지정 후 담당자 자동 표시 happy path 1종.
- 전체 백엔드 테스트 + 프론트 typecheck/test 그린, 회귀 0.

## Brainstorming Check

✅ 통과 (1회 iteration). 점검 결과.
- 자동 배정 알림/히스토리 여부 → 기존 changeAssignee가 silent(이벤트/히스토리 없음, IssueHistory 미구현)
  확인 → 자동 배정도 silent로 일관(NFR4). Maxi 결정 불요.
- createIssue 컴포넌트 영속 → 생성 트랜잭션에서 issue_components 링크 insert 필요(FR1 주의 보강).
- 사전순 동률 → name 후 id 오름차순 tiebreak로 결정성 확보(FR6).
- 한글/영문 혼합 정렬은 코드포인트 기반 결정적 정렬(허용, 별도 collation 미도입).
