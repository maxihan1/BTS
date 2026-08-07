<!-- 보드/백로그 카드 응답에 유형·라벨·추정 추가 — 노출 필드 집합·포트 확장 여부·type JOIN 부활 3건 확정 -->

# ADR — 보드/백로그 카드 응답 필드 확장 (유형·라벨·추정)

> 날짜: 2026-08-07
> 상태: 채택 (FR-UX-14 B2, PR #346)
> BC: agile-planning (포트는 shared-kernel · 어댑터는 issue-tracking)
> 관련 SDD: [13. 보드/백로그/타임라인](../sdd/13-board-backlog-timeline.md)
> 정본: `docs/plan/product/personalization.md` §4.12 FR-UX-14 D4/D5
> plan: [plan](../plans/2026-08-07-fr-ux-14-b2-card-fields.md)

## 맥락

보드/백로그 카드는 지금 **제목·키·담당자 3필드**뿐이라 한눈에 읽히는 정보가 없다.
원인은 화면이 아니라 응답이다 — `BoardCardResponse` 에 유형·라벨·추정이 아예 없어
프론트가 그릴 재료를 못 받는다. 정본이 **B2 를 F14 의 유일한 차단점**으로 지목한 이유다.

### 데이터 흐름 (실측)

```
IssueRepository.listVisibleForBoard          (issue-tracking)
  → BoardIssueEntry(issue, epicKey)
  → toBoardIssueView()                        (BoardIssueLookupAdapter)
  → BoardIssueView (shared-kernel, 8필드)
  → BoardCardResponse.from / BacklogIssueResponse.from  (agile-planning)
```

두 응답 DTO 가 **모두 `BoardIssueView` 를 미러**한다. 포트 VO 한 곳을 늘리면
보드와 백로그가 함께 따라온다 — 이 PR 이 두 화면을 동시에 여는 이유다.

### 착수 전 실측이 뒤집은 정본 전제 3건

정본 §4.12 는 FR-UX-13 과 마찬가지로 실측과 어긋났다. **줄번호·행수는 착수 때마다 다시 잰다**는
이 로드맵의 규율을 그대로 승계한다.

1. **「타입은 `listWithType` 이 이미 조인 중」 — 경로 혼동.**
   `listWithType` 은 **이슈 목록** 경로다. 보드 경로 `listVisibleForBoard` 는
   `IssueRepository.kt:745` 에 *"type 요약은 보드 카드에 불필요하므로 ISSUE_TYPES JOIN 생략"* 이라고
   **의도적 생략을 명문화**해 뒀다. 즉 조인은 "이미 있는 것"이 아니라 **이 PR 이 새로 넣는 것**이다.

2. **「보드 카드 SELECT 확장」 — 라벨·추정은 확장이 불필요.**
   `listVisibleForBoard:793` 이 `dsl.select(ISSUES.fields().toList() + …)` 로 **ISSUES 전 컬럼을 이미 조회**하고
   `:805` 가 `record.into(ISSUES).toIssue()` 로 `Issue` 를 완성한다. `issues.labels TEXT[]`(V006)·
   `issues.original_estimate_seconds INT`(V027)는 **이미 손 안에 있고 매핑에서만 버려지고 있었다**
   (`BoardIssueLookupAdapter.kt:119` — *"최소 필드만 추출한다 (type/description 등은 제외)"*).
   실제 쿼리 변경은 **type JOIN 하나뿐**이다.

3. **지정 필드 `typeKey`·`typeIconName` — 소비자 계약과 불일치.**
   프론트 `IssueTypeIcon` 의 props 는 `{ iconName, typeName }` 이고 **`typeKey` 를 쓰지 않는다**.
   정본 조합은 접근성 레이블(`typeName`)이 빠져 있어 **그대로는 스크린리더에 유형을 못 알린다**
   (WCAG 2.1 AA 는 이 프로젝트의 NFR 임계다).

### 부수 발견 — `icon_name` 은 컬럼도 시드도 있는데 노출 경로가 0이다

`issue_types.icon_name VARCHAR(50)`(V003)은 표준 5타입에 `epic·story·task·subtask·bug` 로
**시드까지 채워져 있고** 프론트 `TYPE_ICON_BY_NAME` 매핑 키와 정확히 일치한다. 그런데
`buildTypeSelectColumns()` 가 `ID·KEY·NAME` 3개만 뽑아 **어떤 API 로도 나간 적이 없다**.
이슈 상세 화면은 이슈 응답이 아니라 **타입 목록 API**(`apps/web/src/api/issue-types.ts`,
`iconName: z.string().nullable()`)에서 아이콘을 얻어 쓰고 있었다(`IssueMetaPanel.tsx:272`
`iconName={currentType?.iconName ?? null}`).

이는 [[learnings 2026-07-17 파일 존재 ≠ 기능 존재]] 와 동형이다 — **컬럼 존재 ≠ 노출**.
그리고 이 사실이 D-1 의 결정적 근거가 됐다.

## 결정

### D-1. 노출 필드는 `typeKey` · `labels` · `originalEstimateSeconds` 3개 (Maxi 확정 2026-08-07)

카드 응답에는 **유형 식별자만** 싣고, 아이콘·유형 이름은 프론트가 **기존 타입 목록 조회**와
맞춰 채운다. `typeIconName`·`typeName` 은 **싣지 않는다**.

**근거 3건.**

| 근거 | 내용 |
|---|---|
| 선례 A | **이슈 상세 화면이 이미 이 방식**이다 — 이슈 응답엔 `typeKey`·`typeName` 만 있고 아이콘은 타입 목록에서 온다 |
| 선례 B | **자매 포트 `TimelineItemView.issueType` 도 `issue_types.key` 만 담는다** (KDoc 명시). shared-kernel VO 에 식별자만 넣는 것이 확립된 패턴 |
| 중복 방지 | 유형 메타(이름·아이콘)를 카드 응답에도 실으면 **타입 목록 API 와 두 벌**이 된다. 유형 이름을 바꿨을 때 화면마다 갈라진다 |

**감수하는 비용.** 프론트가 카드 조회와 타입 목록 조회를 조합해야 하고, 타입 목록이
도착하기 전 짧은 순간 아이콘이 비어 보일 수 있다. 타입 목록은 프로젝트당 소수 행이고
**목록당 1회 조회**라 N+1 이 아니다 — 이슈 상세가 이미 이 비용을 치르고 있다.

**기각한 대안.** ⑴ 유형 3필드 전부 싣기 — 카드 1,000건에 같은 문자열이 반복돼 응답이 커지고
메타가 두 곳으로 갈린다. ⑵ 정본 그대로(`typeKey`+`typeIconName`) — 접근성 레이블 결손이라
WCAG 임계를 스스로 깬다.

### D-2. `BoardIssueView` 를 직접 확장한다 — 표시 전용 VO 를 따로 두지 않는다

`BoardIssueView` KDoc 은 *"보드 컬럼 배치와 카드 정렬에 필요한 **최소 필드만** 포함한다"* 고
적혀 있고, 이 PR 은 표시용 필드를 넣으므로 그 서술과 충돌하는 것처럼 보인다.

**그러나 자매 VO `TimelineItemView` 도 똑같이 "필요한 최소 필드만"이라 적어 두고 `issueType` 을
담고 있다.** 즉 "최소"는 **그 화면이 필요로 하는 최소**를 뜻하지 "표시 필드 금지"가 아니다.
보드 카드의 요구가 3필드에서 6필드로 넓어졌으므로 같은 논리로 VO 를 넓힌다.
**KDoc 의 "최소 필드만" 문구는 새 필드 목록에 맞춰 갱신한다** — 고치지 않으면 다음 독자가
같은 혼동을 반복한다.

표시 전용 VO 분리는 **기각**한다. 포트가 둘로 갈리면 보드·백로그·타임라인 세 소비자가
어느 VO 를 쓸지 매번 정해야 하고, 이득은 문서 한 줄뿐이다.

### D-3. `ISSUE_TYPES` INNER JOIN 을 추가한다 — `listVisibleForTimeline` 이 템플릿

`IssueRepository.kt:745` 의 "type JOIN 생략"은 FR-BD-01 당시 **요구가 없었다**는 서술이지
성능 금칙이 아니다. 근거 — **같은 파일의 `listVisibleForTimeline:886` 이 정확히 그 조인을 이미 하고 있다.**

```kotlin
.join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))   // :886, INNER
ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS)                        // :881
```

- **INNER JOIN 안전.** `issues.type_id` 는 V005 에서 `SET NOT NULL` + FK 다. 행이 사라지지 않는다.
  타임라인 구현은 여기에 `?: error("issue_types.key must not be null …")` 단언까지 붙여 뒀다 — 승계한다.
- **N+1 아님.** N:1 단일 조인이라 **행 수가 늘지 않는다**. 보드는 이미 `PROJECTS` INNER JOIN +
  EPIC self LEFT JOIN 2개를 하고 있고, 이것이 3번째다.
- **규모.** `BOARD_CARD_FETCH_LIMIT = 1000` 대 `TIMELINE_FETCH_LIMIT = 500`. 보드가 2배지만
  `issue_types` 는 프로젝트당 수 개 행이라 조인 비용이 카드 수에 비례하지 않는다.

**`:745` 주석은 이 PR 에서 정정한다.** 코드와 반대되는 주석을 남기면 다음 독자가 속는다.

### D-4. 성공 판정식은 N+1 회귀 가드 (정본 지정)

정본이 지정한 판정식을 그대로 채택한다. 라벨은 `TEXT[]` 컬럼이라 조인이 없고 유형은 단일
조인이므로, **카드 수와 무관하게 쿼리 수가 일정함**을 테스트로 증명한다.
`BoardIssueLookupAdapterTest` 는 선례 커밋 `dcbf130e6`(FR-BD-02) 에서 이미 같은 축으로 확장된 적이 있다.

## 결과

- 신규 도메인 용어 **0건** — glossary 의 「이슈 타입」(Epic/Story/Task/Subtask/Bug + 커스텀,
  모든 이슈는 정확히 하나 보유)·라벨·추정이 전부 기존 개념이다. `glossary.md` 갱신 불필요.
- 신규 엔티티·관계 **0건**. `domain/agile-planning.md` 갱신 불필요.
- 마이그레이션 **0건** — 필요한 컬럼이 이미 전부 있다 (정본 D3 「없음 예상」이 맞았다).
- **정본 §4.12 D4 문구는 이 PR 에서 정정한다** (`typeIconName` 제외 · SELECT 확장 범위 축소).

## 범위 밖 — 관측만 기록

**보드 조회의 truncation 경계가 비결정적이다.** `listVisibleForBoard:802` 는
`orderBy(ISSUES.CREATED_AT.desc())` 뿐이라 `created_at` 동률 행이 1,000건 경계에 걸치면
**어느 카드가 잘려 나갈지 실행마다 달라질 수 있다**. 자매 경로인 타임라인은 이 문제를 이미
알고 `orderBy(ISSUES.CREATED_AT.desc(), ISSUES.KEY.asc())` 로 보조 정렬을 넣어 뒀다(`:894`, 주석 "M1").

**이 PR 에서 고치지 않는다** — 이 PR 의 변경(필드 추가)과 무관한 **선재 결함**이고,
정렬을 건드리면 카드 배치 회귀 위험이 이 PR 의 검증 범위를 넘는다.
`TODOS.md` 등재 후보로 남긴다.
