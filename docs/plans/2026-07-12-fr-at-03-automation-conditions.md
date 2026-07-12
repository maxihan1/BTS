# FR-AT-03 조건 분기 (if-else, 표현식)

> slug: fr-at-03-automation-conditions
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-12

## Brief

FR-AT-03 — automation BC 조건 평가 엔진. 자동화 룰이 트리거 발화 후 액션 실행 전에
조건(Condition/Expression)을 평가하여 분기(if-else)한다.

원문 요청: "fr-at-03 진행해줘"

classify 결과:
- type: backend
- agent: backend-engineer
- primary_bc: automation

product doc §2.3 스코프:
- D1. 도메인 — Condition + Expression
- D2. 명세 — 표현식 문법
- D3. 데이터 모델 — automation_conditions(expression)
- D4. 백엔드 — 표현식 평가 엔진 (Spring SpEL 또는 자체)
- D5. 백엔드 테스트 — 표현식 케이스 50개
- D6. 프론트 UI — 조건 빌더 (후속 PR 예상)
- D7. E2E (후속 PR 예상)

선행: FR-AT-01(트리거) 완료, FR-AT-02(액션) 완료.
분할 방침(선례): 백엔드 D1~D5 이번 PR, 프론트 D6/D7 후속 PR. (spec/plan에서 확정)

## 도메인 정리

- **BC**: automation (TCA — Trigger-Condition-Action 엔진의 빠진 중간 조각 = Condition)
- **선행 완료**: FR-AT-01(트리거, #251/#254), FR-AT-02(액션, #256/#260)
- **영향 엔티티**:
  - `AutomationRule` (aggregate) — `conditions: List<Condition>` 필드 신규 추가 (현재 예약 슬롯 없음. FR-AT-02가 `actions`를 add한 방식과 동일)
  - `Condition` (신규 도메인) — `Action`의 `sealed class` + `fromJson(type, configJson)` 팩토리 패턴 미러링
  - `automation_conditions` 테이블 (신규, V304 — V302 `automation_actions` 스키마 미러링. 최신 마이그레이션 V303)
- **실행 hook**: `ActionExecutor.execute` — 컨텍스트 구성(`buildContext`) 직후·액션 dispatch 직전 사이에 조건 게이트 삽입. 조건 불충족 시 액션 0개 실행하고 no-op 조기 반환.

### Maxi 확정 결정 (2026-07-12 게이트, AskUserQuestion 3건)

1. **표현식 엔진 = 구조화 조건 모델** (JSONLogic류 데이터 트리 / `{field, operator, value}` 절, BTS 자체 코드로 평가).
   - 근거: 자동화 조건은 프로젝트 관리자가 **런타임 API**로 입력. project-workflow의 샌드박스 SpEL은 "런타임 API 유입 표현식 금지"를 문서상 전제로 하므로 부적합. 데이터 트리는 **코드 실행이 구조적으로 불가** → API 유입이어도 RCE 표면 0. D6 조건 빌더 UI와 직결. 50케이스 테스트 용이. SDD §8.3 첫 번째 안.
   - 기각: 샌드박스 SpEL 재사용 (선례 일관성 이점보다 API 유입 보안 전제 충돌이 큼).
2. **이슈 필드 값 읽기 = 신규 cross-BC 읽기 포트** (`IssueSnapshotPort`, shared-kernel).
   - 근거: 트리거 이벤트(`IssueUpdated` 등)는 `{issueKey, 변경된 필드 이름}`만 담고 status/priority/assignee/labels **값이 없음**. SDD 조건 예시는 전부 값 비교라 새 읽기 통로 필수. FR-AT-02 `IssueMutationPort`(쓰기 포트)가 확립한 automation→shared-kernel←issue-tracking 방향·fail-closed·actor 신뢰 모델 미러. automation BC 격리 유지. 덤: FR-AT-02 `{{ issue.status }}` 템플릿도 현재 값이 비어 있어 이 포트의 수혜.
   - 기각: 이벤트 payload enrich (producer 변경 폭 큼 + SCHEDULED/WEBHOOK 트리거는 스냅샷 원천 없음).
3. **이번 PR 범위 = 백엔드 D1~D5** (도메인·명세·데이터모델·평가엔진·테스트 50케이스). 프론트 D6(조건 빌더 UI)·D7(E2E)는 후속 PR. FR-AT-01/02 분할 선례 동일.

- **기존 결정 충돌**: 없음. SDD §8.3의 2안(JSONLogic vs SpEL) 중 JSONLogic 계열 확정으로 정합.
- **glossary 갱신 후보**: "조건 (Condition)" — 자동화 룰에서 트리거 발화 후 액션 실행 전 평가하는 분기 판정. (Maxi 승인 후 추가)
- **관련 ADR**: `docs/decisions/2026-07-12-fr-at-03-automation-conditions.md` (bts-spec 단계에서 전체 설계와 함께 작성 예정)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
