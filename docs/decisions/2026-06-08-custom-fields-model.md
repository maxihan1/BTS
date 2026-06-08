# 커스텀 필드 — 프로젝트별 정의 + JSONB 값 저장 (FR-IS-10)

> 상태: 채택 | 날짜: 2026-06-08 | 영역: issue-tracking | 관련 FR: FR-IS-10 (신규)
> 후행: FR-PM-07(필드 수준 권한) — 코어 + 커스텀 필드를 대상으로 함

## 맥락

SDD는 커스텀 필드를 사실상 설계하지 않은 채 흔적만 남겨 두었다.

- §05 데이터 모델 — `issues.custom_fields JSONB` 컬럼 한 줄 + GIN 인덱스 언급(line 266)
- §04 아키텍처 — Issue Tracking 책임 목록에 "커스텀 필드" 단어
- §10 검색 — "커스텀 필드도 선택 가능" 한 줄
- §12 권한 — FR-PM-07 예시로 `salary_impact` 커스텀 필드 언급

즉 정의 메타데이터(필드 타입·이름·선택지·적용 범위)와 관리 API·UI가 통째로 비어 있었고, 커스텀 필드를 명세하는 FR도 0개였다(FR-IS-02의 "+커스텀"은 커스텀 *이슈 타입*이지 필드가 아님).

2026-06-08 Maxi 결정으로 커스텀 필드 인프라를 신규 **FR-IS-10**으로 신설한다(FR-PM은 identity-access 권한 전용 시리즈라 부적합). 필드 수준 권한(FR-PM-07)은 이 위에서 동작하는 후행 작업으로 분리한다.

## 결정

**프로젝트별 커스텀 필드 정의 + 이슈 JSONB 값 저장 모델을 채택한다.**

1. **정의 메타데이터** — `CustomFieldDefinition`(프로젝트별, `project_id` 보유). Resolution 마스터 테이블 패턴 차용 — key(URL-safe)/name/description/display_order/소프트 삭제(`deleted_at`). 선택형 타입의 선택지는 `CustomFieldOption`(정의 종속).

2. **확장 가능한 타입 시스템** — `FieldType` enum. 1차 10종(자체 완결형): `SHORT_TEXT` · `LONG_TEXT` · `NUMBER` · `DATE` · `DATETIME` · `SINGLE_SELECT` · `MULTI_SELECT` · `CHECKBOX` · `RADIO` · `URL`. 타입별 값 검증을 전략으로 분리해, 후속에서 cross-BC 참조형(USER/GROUP/VERSION/COMPONENT picker)을 **타입만 추가**해 확장한다(인프라 재작업 없음).

3. **값 저장 = JSONB** — `issues.custom_fields` JSONB에 `{field_key: value}`로 저장(SDD §05 설계와 일치). 조인 0·읽기 빠름. 타입 정합·필수 검증·참조 무결성은 ApplicationService가 책임(JSONB는 스키마리스). 검색/필터는 GIN 인덱스.

4. **적용 범위 = 프로젝트별** — 이슈 타입과 무관하게 프로젝트 전체에 적용. component/version의 프로젝트별 선례를 따른다. Jira Cloud의 풀 컨텍스트(필드 + 프로젝트×이슈타입 컨텍스트)는 복잡도 대비 효익이 낮아 채택하지 않는다.

5. **BC 격리** — 정의·값 모두 issue-tracking BC 내부. cross-BC 참조 없음(1차). `CustomFieldDefinition.project_id`는 같은 BC의 `projects`를 참조하므로 FK 적용 가능. `issues.custom_fields`는 BTS 정책상 BC 격리 참조에 FK 미적용 관례를 따른다.

## 대안과 기각 사유

- **전역 스코프** — 가장 단순하나 프로젝트별 차등 불가. 1000명 사내 다(多)프로젝트 환경에 부적합.
- **Jira식 프로젝트×이슈타입 컨텍스트** — 가장 강력하나 첫 FR로는 설계/UI 복잡도 과도. 필요 시 후속 FR로 컨텍스트 테이블 도입 가능.
- **별도 값 테이블(`issue_custom_field_values`)** — 필터/집계는 강력하나 이슈 조회마다 멀티로우 수집 + 조인 비용. SDD 설계(JSONB)와도 불일치. 1000명 규모에선 JSONB + GIN로 충분.

## 영향

- 신규 FR-IS-10 등록 — fr-index · SDD(02 + 신규 §) · product/issue-tracking · README · CLAUDE 카운트 전수 동기화(CLAUDE.md §명세/범위 변경).
- 관리 권한(PROJECT_ADMIN vs 신규 권한 코드)은 spec에서 확정. 신규 권한 코드 도입 시 identity-access의 role_permissions 시드 + PermissionSchemaMigrationTest 카운트 영향(learnings: fr-pm-permission-seed-migration-test-coupling).
- 후행 FR-PM-07(필드 수준 권한)이 코어 필드 + 이 커스텀 필드를 대상으로 가시성/편집 제어.
