# FR-HS-02 — 이슈 변경 이력 조회 UI 스펙

> slug: fr-hs-02-history-view · type: ui (+ same-BC backend view-layer) · BC: issue-tracking
> 선행: FR-HS-01 (백엔드 이력 기록 #115 + cross-BC 라벨 박제 #120)
> ADR: docs/adr/2026-06-11-issue-change-history-model.md (§단계 분할 PR2)
> 작성: 2026-06-11

## 배경

FR-HS-01이 이슈 변경을 `issue_change_group` / `issue_change_item` 2테이블에 append-only로 기록했고,
`IssueChangeHistoryRepository.findByIssue(issueId): List<IssueChangeGroup>` 조회 메서드까지 구현했다.
그러나 **이력을 외부로 노출하는 REST 엔드포인트가 없어** 사용자가 변경 이력을 볼 수 없다.
FR-HS-02는 (1) 백엔드 read 엔드포인트 + DTO, (2) 이슈 상세 페이지의 변경 이력 타임라인 UI, (3) E2E를 구현한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (이력 조회)**: Given 이슈를 볼 수 있는 사용자가 상세 페이지에 진입했을 때, When 하단 "변경 이력" 섹션을 펼치면, Then 변경 그룹이 **최신순**으로 타임라인에 표시된다. 각 그룹은 "누가(actorName) · 언제(상대 시각)" 헤더 + 그 그룹의 필드별 `from → to` 목록을 보여준다.
- **S2 (라벨 표시)**: Given assignee/securityLevel 변경 이력이 있을 때, When 타임라인을 보면, Then #120에서 박제된 `fromLabel/toLabel`(표시명)이 raw 값 대신 표시된다. status 등 label이 null인 필드는 raw 값(또는 프론트 해석)으로 표시된다.
- **S3 (생명주기)**: Given 이슈 생성/소프트 삭제 이력이 있을 때(`field="lifecycle"`, `toValue="created"|"deleted"`), When 타임라인을 보면, Then "이슈를 생성했습니다" / "이슈를 삭제했습니다"로 특수 렌더된다(from→to 형식 아님).
- **S4 (페이징)**: Given 변경이 페이지 크기보다 많을 때, When "더 보기"를 누르면, Then 다음 페이지가 이어 붙는다(누적 표시).
- **S5 (권한)**: Given 이슈를 볼 수 없는 사용자가 changelog 엔드포인트를 호출하면, Then 단건 조회와 동일하게 404(이슈 미존재와 구별 불가 — 존재 probe 방지).
- **S6 (빈 이력)**: Given 이력이 0건일 때(이론상 created 마커는 항상 있으나), Then "변경 이력이 없습니다" 빈 상태를 표시한다.

## 기능 요구사항 (FR)

- **FR1**: 백엔드는 `GET /api/v1/issues/{key}/changelog`를 제공한다. 페이징(최신순) 파라미터 `page`(기본 0), `size`(기본 20).
- **FR2**: 엔드포인트는 단건 조회(`service.findByKey`)와 **동일한 view 권한 가드**를 통과한 뒤에만 이력을 반환한다. 권한 없음/이슈 없음/소프트 삭제는 모두 404.
- **FR3**: actor 표시명은 **백엔드가 해석**한다(`UserLookupPort`, #120 선례). DTO의 `actorName`에 담는다. actorId가 null(시스템)이거나 조회 실패 시 `actorName=null`로 graceful degrade(이력 표시는 계속).
- **FR4**: 프론트는 이슈 상세 페이지(`issues.$key.tsx`) **하단 전체폭 섹션**에 "변경 이력"을 접기/펼치기 형태로 렌더한다. 그룹별 타임라인, 그룹 내 필드별 `from → to`.
- **FR5 (필드명)**: 필드 표시명(예: `priority`→"우선순위")은 **프론트 라벨 맵**(i18n)으로 해석한다. `customField:<key>`는 custom-fields API의 정의명으로 해석하고, 정의를 못 찾으면 key 원문으로 폴백.
- **FR6 (값 표시명 — 프론트 해석, Maxi 결정 2026-06-11)**: 값 표시는 다음 우선순위로 해석한다.
  1. `xxxLabel`(#120 박제 표시명)이 non-null이면 그것을 표시 — assignee, securityLevel.
  2. label이 null이고 raw 값이 ID/숫자인 필드는 **프론트가 이미 로드된 참조 데이터로 해석**한다.
     - `priority` (Int 1~5) → 프론트 priority i18n 라벨 맵 (기존 `IssuePrioritySelect` 재사용)
     - `impact` (Int) → 프론트 impact 라벨 맵
     - `type` (typeId) → `availableTypes`(useIssueTypes, 페이지 로드)에서 이름 조회
     - `components` (UUID JSON 배열) → `projectComponents`(페이지 로드)에서 이름 조회
     - `affectsVersions`/`fixVersions` (UUID JSON 배열) → `projectVersions`(페이지 로드)에서 이름 조회
     - `resolution` (resolutionId) → resolution 목록(필요 시 소량 fetch 또는 기존 데이터)에서 이름 조회
     - `status` (state key) → state key 원문 표시(읽기 가능) 또는 워크플로우 상태명 해석(선택)
  3. 참조 데이터에서 못 찾으면(삭제/이름변경된 엔티티) raw 값 또는 "(삭제됨)"으로 graceful 폴백.
  4. 텍스트 필드(summary/description/environment) + labels는 raw 값 그대로 표시.
  5. raw·label 모두 null(clear)이면 "(없음)".
- 의미: 값 표시명은 **read-time(현재 이름)** 해석이다. assignee/securityLevel만 박제(과거 시점) 표시명이고, 나머지는 현재 참조 데이터 기준 — Jira도 동일한 혼재 방식.
- **FR7**: "더 보기"로 다음 페이지를 누적 로드한다. 마지막 페이지면 버튼을 숨긴다.

## 비기능 요구사항 (NFR)

- **NFR1**: 이력 조회는 기존 변경 동작/OCC에 영향 0 (읽기 전용, append-only 테이블).
- **NFR2**: 페이징으로 변경이 수백 건인 이슈도 단일 응답이 size로 제한된다.
- **NFR3**: cross-BC 직접 import 없음(actor 해석은 기존 `UserLookupPort` 경유). BC 격리 유지.
- **NFR4**: actor 해석 실패가 이력 조회 전체를 깨뜨리지 않는다(graceful degrade).
- **NFR5**: TypeScript strict + Zod 스키마가 백엔드 DTO와 1:1 정합(필드 invent 금지 — learning `frontend-zod-backend-dto-contract-gap`).

## API 인터페이스 (REST)

```
GET /api/v1/issues/{key}/changelog?page=0&size=20
권한: 이슈 view 권한 (단건 조회와 동일 가드). 실패 시 404.
응답 200: 기존 목록 엔드포인트(GET /api/v1/issues)와 동일한 Spring Page 형태
{
  "content": [
    {
      "actorId": "uuid | null",
      "actorName": "string | null",     // 백엔드 해석, null이면 시스템/조회실패
      "createdAt": "2026-06-11T08:00:00Z",
      "items": [
        {
          "field": "priority",          // raw 키: lifecycle|summary|description|priority|labels|environment|impact|type|assignee|status|resolution|components|affectsVersions|fixVersions|securityLevel|customField:<key>
          "fromValue": "1",
          "toValue": "3",
          "fromLabel": "High",          // #120 박제 (assignee/securityLevel만 현재 non-null), 없으면 null
          "toLabel": "Low"
        }
      ]
    }
  ],
  "page": { "number": 0, "size": 20, "totalElements": N, "totalPages": M }   // 기존 list 응답 형식에 맞춤
}
```

> 정확한 Page 직렬화 형식(`content`/`totalElements`/`number` 등)은 plan 단계에서 기존 `GET /api/v1/issues` list 응답을 grep해 1:1로 맞춘다.

## 데이터 모델 변경

**없음.** 기존 `issue_change_group`/`issue_change_item`(V018) 읽기만 한다. 단, repository에 페이징 조회 메서드 추가가 필요할 수 있다(`findByIssue(issueId, page, size)` + count). 도메인 모델/마이그레이션 변경 없음.

## 엣지 케이스

- actorId가 null(시스템 자동 처리) → actorName=null → UI "시스템"으로 표시.
- UserLookupPort 조회 실패 → actorName=null → UI는 actorId 단축 또는 "알 수 없음"으로 degrade.
- 박제 label과 raw value가 둘 다 null인 항목(clear) → "(없음)" 표시.
- `customField:<key>`의 정의가 삭제됨 → key 원문 폴백.
- 페이지 경계: 마지막 페이지에서 "더 보기" 숨김. 빈 이력(이론상 없음, created 마커 존재) → 빈 상태.
- 소프트 삭제된 이슈: 단건 조회가 404이므로 changelog도 404(이력은 DB에 보존되나 조회 경로는 막힘 — 단건 조회 가드 일관).

## 제약 조건

- 한 PR = 한 BC(issue-tracking). backend read 엔드포인트는 same-BC view-layer 확장(learning `2026-05-22`), cross-BC 직접 import 금지.
- TDD red→green→refactor 강제.
- 새 라이브러리 도입 금지(기존 shadcn/Radix + TanStack Query + Zod).
- 권한 가드는 단건 조회와 동일 — security-engineer 검토 대상(존재 probe 방지, learning `auth-extraction-before-resource-lookup`).

## 측정 가능한 완료 기준

1. `GET /api/v1/issues/{key}/changelog` 통합 테스트 — 권한 통과 시 페이징 응답, 권한 없음/미존재/소프트삭제 404.
2. actor 해석 — actorId 있는 그룹에 actorName 채워짐, null/실패 시 graceful degrade 단위 테스트.
3. 프론트 단위 테스트 — 타임라인 그룹 렌더, label 우선 표시, lifecycle 특수 렌더, "더 보기" 페이징, 빈 상태.
4. Zod 스키마 ↔ 백엔드 DTO 정합(스펙 §API 기준, MSW fixture가 실제 응답 형태 반영).
5. E2E — 이슈 변경 후 상세 페이지에서 변경 이력 타임라인 확인(happy path).
6. typecheck/lint/detekt PASS, 기존 단위/E2E 회귀 0.

## Brainstorming Check

✅ 통과 (1 iteration). 발견 gap — #120이 assignee/securityLevel만 라벨 박제하여 priority(Int)·type(ID)·components/versions(UUID)·resolution(ID)이 사람이 못 읽는 raw 값으로 노출되는 문제. Maxi 결정(2026-06-11)으로 "프론트가 페이지에 이미 로드된 참조 데이터로 read-time 해석"(FR6) 채택해 해소.
