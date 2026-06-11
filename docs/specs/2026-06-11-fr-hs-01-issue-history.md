# FR-HS-01 이슈 변경 이력 기록 — 스펙

> BC: issue-tracking · type: backend(전용) · PR #115
> 도메인 결정: ADR [docs/adr/2026-06-11-issue-change-history-model.md](../adr/2026-06-11-issue-change-history-model.md)
> 선행: FR-IS-01 완료 · 후속 unblock: FR-HS-02(조회 UI)·FR-MV-02(이동 시 이력 보존)

## 범위 한정

- **이 FR은 "기록"만 담당한다.** 이슈가 변경될 때 이력을 남기는 **부수효과**가 전부다.
- **공개 조회 API/UI는 FR-HS-02 소관** — 본 PR은 사용자 대면 REST 엔드포인트를 신규로 추가하지 않는다. 검증은 통합 테스트가 변경 호출 후 이력 테이블을 직접 조회해 수행한다.

## 사용자 시나리오 (Given-When-Then)

1. **단일 필드 변경**
   - Given 이슈 ATL-1의 우선순위가 3
   - When 사용자 U가 PATCH로 우선순위를 1로 변경
   - Then `issue_change_group` 1행(actor=U, issue=ATL-1, created_at=now) + `issue_change_item` 1행(field='priority', from='3', to='1')

2. **한 번의 편집에서 여러 필드 변경**
   - Given 이슈 ATL-1
   - When 사용자 U가 PATCH update로 summary와 priority를 동시에 변경
   - Then group 1행 + item 2행(summary, priority). **한 트랜잭션 = 한 그룹**.

3. **상태 전이**
   - When 사용자 U가 transition으로 open→in_progress
   - Then group 1행 + item 1행(field='status', from='open', to='in_progress'). resolution이 함께 설정되면 item 추가(field='resolution').

4. **컬렉션 변경(담당자/컴포넌트/버전/라벨)**
   - When 사용자 U가 컴포넌트를 [A] → [A,B]로 변경
   - Then group 1행 + item 1행(field='components', from='[A의 id]', to='[A의 id, B의 id]').

5. **생명주기 — 생성**
   - When 사용자 U가 이슈 생성
   - Then group 1행 + item 1행(field='lifecycle', to='created'). (초기 필드 값들을 일일이 item으로 박지 않음 — 생성 단일 마커)

6. **생명주기 — 소프트 삭제**
   - When 사용자 U가 이슈 소프트 삭제
   - Then group 1행 + item 1행(field='lifecycle', to='deleted').

7. **변경 없음(no-op)**
   - When PATCH가 실제로는 아무 값도 바꾸지 않음
   - Then group/item을 **생성하지 않는다**(빈 이력 금지).

8. **append-only**
   - 이력은 어떤 경로로도 UPDATE/DELETE되지 않는다. 이슈가 소프트 삭제돼도 이력은 보존.

## 기능 요구사항 (FR)

- FR-HS-01-1. 다음 변경 진입점에서 변경 발생 시 이력 그룹+항목을 **같은 트랜잭션**에 기록한다.
  `createIssue`(생성 마커), `updateIssue`(summary/description/priority/labels/environment/impact/type/customFields + securityLevel), `transitionIssue`(status + resolution), `changeAssignee`(assignee), `changeComponents`(components), `changeAffectsVersions`(affectsVersions), `changeFixVersions`(fixVersions), `softDeleteIssue`(삭제 마커).
- FR-HS-01-2. 한 변경 호출에서 실제로 바뀐 필드만 item으로 기록(불변 필드 제외). 변경 0건이면 group 미생성.
- FR-HS-01-3. group은 actor(변경 주체)·issue_id·issue_key·created_at을 가진다. item은 field·from_value·to_value를 가진다.
- FR-HS-01-4. 이력 기록 실패는 이슈 변경 트랜잭션을 함께 롤백한다(부분 성공 금지 — 감사 정합).

## 데이터 모델 변경

마이그레이션 **V018**(FR-MN-01 PR #114와 V번호 조율, 머지 직전 재확인). init_codegen.sql 미러.

```sql
CREATE TABLE issue_change_group (
  id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  issue_id    UUID         NOT NULL,
  issue_key   VARCHAR(...) NOT NULL,       -- 이동/리다이렉트 대비 키도 박제
  actor_id    UUID,                        -- nullable: 시스템 자동 변경
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_issue_change_group_issue ON issue_change_group (issue_id, created_at DESC, id DESC);

CREATE TABLE issue_change_item (
  id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_id    BIGINT       NOT NULL REFERENCES issue_change_group(id),
  field       VARCHAR(64)  NOT NULL,       -- 'summary','priority','status','assignee','components','customField:<key>','lifecycle' ...
  from_value  TEXT,                        -- nullable: 원시 ID/값
  to_value    TEXT,                        -- nullable: 원시 ID/값
  from_label  TEXT,                        -- nullable: 변경 당시 표시 이름 박제 (Jira fromString)
  to_label    TEXT                         -- nullable: 변경 당시 표시 이름 박제
);
CREATE INDEX idx_issue_change_item_group ON issue_change_item (group_id);
CREATE INDEX idx_issue_change_item_field ON issue_change_item (field);
```

- FK는 group(id)에만(부모-자식 무결성). issues로의 FK는 두지 않음(이력 보존 우선, FR-AU-10 패턴). issue_id+issue_key 동시 저장으로 이동/리다이렉트 추적.
- append-only: soft-delete 컬럼·updated_at 없음.

### from_value/to_value + from_label/to_label 직렬화 규칙 (Maxi 확정)

**값(value) — 원시 ID/스칼라.**
- 스칼라(summary/description/environment): 문자열 그대로. priority/impact: 숫자의 문자열. type: typeId 문자열. status: stateKey. resolution: resolutionId 문자열.
- 컬렉션(labels/components/affectsVersions/fixVersions): **정렬된 원소들의 JSON 배열 문자열**(예: `["<uuid1>","<uuid2>"]`). 라벨은 문자열 배열.
- assignee/securityLevel: ID 문자열 또는 null(해제).
- customFields: **변경된 키별로** field='customField:&lt;fieldKey&gt;', value=해당 키의 이전/새 값(스칼라는 문자열, 복합은 JSON 문자열).
- lifecycle: to_value ∈ {'created','deleted'}, from_value=null.

**라벨(label) — 변경 당시 표시 이름 박제 (Jira식, 부분 박제 — Maxi 확정).**
- **issue-tracking 소유 필드만** 변경 당시 이름을 함께 INSERT(영구 박제, 이후 개명/삭제와 무관). type → 타입명, resolution → resolution명, components/versions → 이름의 정렬 배열 문자열.
- **cross-BC 소유 필드는 박제 안 함**(label=null, value=id만 저장). assignee(username은 identity-access 소유)·securityLevel(레벨명은 identity-access 소유)은 표시 이름을 **FR-HS-02 조회 시 해석**. 근거: identity-access 이름 조회는 cross-BC라 "한 PR=한 BC"와 충돌 — 이력 1FR에 포트 확장은 과함(ground-truth 리뷰 BLOCKER). BTS는 소프트삭제라 대개 조회 시 해석 가능, 개명/완전삭제 후엔 id 표시.
- status → stateKey passthrough(label=value). 값 자체가 표시인 필드(summary/description/priority/impact/environment/labels)는 label=null(value가 곧 표시).
- **라벨 해석 헬퍼 1곳 집약**(issue-tracking 내 type/resolution/component/version name lookup만). Component/Version `findById`는 projectId 인자 + `deleted_at IS NULL` 필터 — 박제는 변경 시점 수행이라 OK.

## 비기능 요구사항 (NFR)

- 이력 기록이 이슈 변경 p95(단건 200ms)를 회귀시키지 않음 — 변경당 INSERT 2~N건, 같은 트랜잭션.
- SQL은 NamedParameterJdbcTemplate(인젝션 방어, FR-AU-10 패턴).
- 동시 변경(OCC)과 무관 — 이력은 변경이 커밋되는 트랜잭션에 종속.

## 엣지 케이스

- no-op PATCH → 이력 0.
- 컬렉션 순서만 다르고 집합 동일 → 변경 아님(정렬 후 비교, 도메인 `distinct` 정규화 활용).
- description: null(미설정)↔""(빈문자) 구분 기록.
- 시스템 자동 변경(컴포넌트 기본 담당자 자동배정 FR-CM-03): actor_id를 트리거한 사용자로(자동배정은 사용자 액션의 부수효과). 진짜 무주체면 null.
- 이슈 소프트 삭제 후에도 과거 이력 조회 가능(보존).

## 제약 조건

- 한 PR = 한 BC(issue-tracking). 다른 BC 호출 없음.
- 백엔드 전용 — apps/web 변경 없음.
- 절대 규칙 19개 + DATA.md append-only 준수.

## 측정 가능한 완료 기준

- 8개 변경 진입점 각각에 대해 "변경 → 올바른 group+item 생성" 통합 테스트 통과.
- no-op → 이력 0 테스트.
- append-only(이력 UPDATE/DELETE 경로 부재) 구조 + 이슈 소프트삭제 후 이력 보존 테스트.
- 마이그레이션 V018 + init_codegen 미러, 풀 issue-tracking 테스트 green, ktlint/detekt green.

## Brainstorming Check

✅ 통과 (셀프 적대적 sanity check 1회). 발견 gap 2건 모두 Maxi 확정으로 해소.
- gap-1. from/to 표시값 손실 위험(삭제·개명 후) → **표시 라벨 박제(Jira식 from_label/to_label)** 채택.
- gap-2. customFields 변경 입자 불명 → **키별 분해(field='customField:&lt;key&gt;')** 채택.
- 나머지(no-op 이력 0·컬렉션 정렬 후 비교·actor nullable·lifecycle 단일 마커·이력 기록 실패 시 트랜잭션 롤백)는 스펙 본문에 명시.
