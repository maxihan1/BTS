# FR-CM-02 — 이슈에 다중 컴포넌트 할당

> slug: fr-cm-02-issue-components
> type: feature
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> primary BC: issue-tracking
> 생성: 2026-06-04

## Brief

FR-CM-02 이슈에 다중 컴포넌트 할당. 한 이슈에 여러 컴포넌트(프로젝트 하위 영역 분류)를 붙인다.

- 선행: FR-CM-01(컴포넌트 CRUD, PR #59/#64) · FR-IS-03(담당자 전용 서브리소스 PATCH, PR #49/#51) · FR-PM-03(컴포넌트 권한 prod resolver, PR #70/#72) 모두 완료.
- D1 도메인: Issue Aggregate에 componentIds 다중 연결.
- D3 데이터: issue_components 다대다 테이블 (+ jOOQ init_codegen 미러).
- D4 백엔드: 이슈 컴포넌트 할당/해제 엔드포인트 (FR-IS-03 전용 서브리소스 패턴).
- D6 프론트: 다중 컴포넌트 셀렉터 UI.
- D7 E2E: Playwright.

분류 메모: classify-task 키워드 휴리스틱이 qa/migration으로 오분류 → Maxi 확인 후 feature 전체체인으로 override.

## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: Issue(componentIds 추가), Component(기존 FR-CM-01), issue_components(신규 조인 테이블)
- 새 용어: 없음 (컴포넌트는 glossary 기존 항목). 관계 "이슈↔컴포넌트 다대다"만 명확화.
- 기존 결정 충돌: 없음. 라벨 ADR이 "컴포넌트=정규화 엔티티"로 구분 설계 → 조인 테이블 정합.

### 핵심 결정 (ADR로 기록)
- **D1 저장**: `issue_components` 정규화 조인 테이블(둘 다 issue-tracking 소유 → 실 FK). 복합 PK `(issue_id, component_id)` 멱등성. 관계라 소프트삭제 불요(연결 해제=행 DELETE). jOOQ init_codegen 미러 필수.
- **D2 API**: 전체교체(set) 의미론. `PATCH /api/v1/issues/{key}/components` 전용 서브리소스(FR-IS-03 담당자 동형) + expectedVersion 낙관락. 빈 배열=전부 해제.
- **D3 권한**: `IssuePermission.UPDATE` + `IssueScope.Project`(이슈 편집권). ComponentPermissionResolver(CRUD 관리권)와 구분. Global 사용 금지(prod 무조건 거부 함정).
- **D4 검증**: 같은 프로젝트 + 활성 컴포넌트만(ComponentRepository.findById(id, projectId) 활용). 위반 422 COMPONENT_NOT_FOUND. 요청 중복 ID distinct 정규화(도메인).
- **D5 읽기**: IssueResponse.componentIds 노출(초기엔 ID 목록만, 셀렉터가 이름 해소).

### 복제 선례 (ground-truth 확인됨)
- 도메인: `Issue.kt:62` assigneeId 옆 componentIds 추가, assignComponents()/clearComponents() 메서드.
- API: `IssueController.changeAssignee()` / `ChangeAssigneeRequest.kt` / `IssueApplicationService.changeAssignee()` / `IssueRepository.updateAssignee()`.
- 검증: UserLookupPort 422 패턴 → ComponentRepository.findById 422.
- 마이그레이션: 최신 V011 → 신규 **V012**. init_codegen.sql 미러. 조인 테이블 선례 bulk_operation_items(V008).
- 프론트: `IssueMetaPanel.tsx`(담당자 셀렉터 옆 다중 컴포넌트 셀렉터), `useChangeAssignee.ts` 복제, `components.ts`(fetchComponents).

- 관련 ADR: [docs/adr/2026-06-04-issue-component-assignment-model.md](../adr/2026-06-04-issue-component-assignment-model.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
