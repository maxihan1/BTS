# FR-IS-10 커스텀 필드 인프라 — 스펙

> BC: issue-tracking | type: backend(feature) | 신규 FR
> 도메인 정리: docs/plans/2026-06-08-fr-is-10-custom-fields.md §도메인 정리
> ADR: docs/decisions/2026-06-08-custom-fields-model.md
> **1차 PR 범위 = 백엔드만** (정의 CRUD API + 이슈 값 검증 + 테스트). 프론트(관리 화면 + 10타입 위젯 + E2E)는 후속 PR.

## 배경

회사가 이슈에 자기 업무에 맞는 칸("급여 영향도", "고객명" 등)을 직접 정의해 쓰는 인프라. SDD는 `issues.custom_fields JSONB` 컬럼 한 줄만 남기고 정의 메타데이터·관리 API를 비워 두었다. 본 FR이 그 결선이다. 후행 FR-PM-07(필드 수준 권한)이 이 위에서 동작한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 필드 정의 생성 (PROJECT_ADMIN)
- Given 프로젝트 ATLAS의 관리자(PROJECT_ADMIN)가
- When `POST /api/v1/projects/ATLAS/custom-fields`로 `{key, name, fieldType, required, options?}`를 보내면
- Then 201 + 생성된 정의 반환. 같은 프로젝트에 같은 key가 이미 있으면 409.

### S2. 필드 정의 목록/단건 조회 (프로젝트 멤버)
- Given 프로젝트 멤버가
- When `GET /api/v1/projects/ATLAS/custom-fields`를 호출하면
- Then 활성(`deleted_at IS NULL`) 정의를 `display_order` 오름차순으로 반환(200).

### S3. 필드 정의 수정 (PROJECT_ADMIN)
- Given 관리자가
- When `PATCH /api/v1/projects/ATLAS/custom-fields/{fieldKey}`로 name/description/required/display_order/options를 보내면
- Then 200 + 수정 반영. **fieldType과 key는 불변**(타입 변경은 기존 값 정합 붕괴 → 422).

### S4. 필드 정의 삭제 (PROJECT_ADMIN)
- Given 관리자가
- When `DELETE /api/v1/projects/ATLAS/custom-fields/{fieldKey}`를 호출하면
- Then 204 + 소프트 삭제(`deleted_at`). 기존 이슈의 `custom_fields` JSONB 값은 보존(읽기 시 비활성 정의는 표시에서 제외).

### S5. 이슈 생성/수정 시 커스텀 필드 값 입력
- Given 프로젝트 ATLAS에 NUMBER 타입 필수 필드 `salary_impact`가 정의돼 있고
- When 이슈 생성/수정 요청 바디에 `customFields: {"salary_impact": 3}`를 포함하면
- Then 값이 검증 통과 후 `issues.custom_fields`에 저장된다. 필수 필드 누락/타입 불일치/미정의 키/선택지 위반 시 422.

### S6. 이슈 조회 시 커스텀 필드 노출
- Given 커스텀 필드 값이 있는 이슈를
- When `GET /api/v1/projects/{key}/issues/{key}`로 조회하면
- Then `IssueResponse.customFields`에 `{field_key: value}` 형태로 포함(활성 정의만).

## 기능 요구사항 (FR-IS-10)

| ID | 요구사항 |
|---|---|
| IS-10-1 | 프로젝트별 커스텀 필드 정의 CRUD (생성/목록/단건/수정/소프트삭제) |
| IS-10-2 | 확장 가능 FieldType — 1차 10종: SHORT_TEXT, LONG_TEXT, NUMBER, DATE, DATETIME, SINGLE_SELECT, MULTI_SELECT, CHECKBOX, RADIO, URL |
| IS-10-3 | 선택형(SINGLE_SELECT/MULTI_SELECT/RADIO)은 선택지(CustomFieldOption) 정의. value/label/display_order |
| IS-10-4 | 정의 관리 권한 = PROJECT_ADMIN 역할 직접 확인 (신규 권한 코드 없음, FR-PM-06 스킴적용 선례) |
| IS-10-5 | 이슈 생성/수정 시 customFields 값 검증 + `issues.custom_fields` JSONB 저장 |
| IS-10-6 | 값 검증: 미정의 키 거부, required 누락 거부, 타입 불일치 거부, 선택지 위반 거부 — 모두 422 |
| IS-10-7 | 이슈 조회 응답(IssueResponse)에 customFields 노출 (활성 정의만). 단건 + 목록 경로 모두 — JSONB가 issues row에 있어 추가 쿼리 없음 |
| IS-10-8 | key는 프로젝트 내 유니크(활성 기준), URL-safe 소문자. fieldType/key 생성 후 불변 |

## 비기능 요구사항 (NFR)

- `issues.custom_fields`에 GIN 인덱스(SDD §05 line 266) — 후속 검색/필터 대비.
- 값 검증은 ApplicationService 단일 책임(도메인 우회 금지, DATA.md). JSONB 스키마리스이므로 타입/참조 정합은 앱이 보증.
- 정의 조회는 이슈 생성/수정마다 1회(프로젝트 정의 셋) — N+1 회피, 단건 쿼리로 정의 셋 로드.
- BC 격리: issue-tracking 단일. `custom_field_definitions.project_id`는 같은 BC `projects` FK 적용. `issues.custom_fields`는 BC 격리 관례상 FK 무관(JSONB).

## API 인터페이스 (REST) — Component CRUD 패턴 차용

베이스: `/api/v1/projects/{projectIdOrKey}/custom-fields` (ComponentController 동형)

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| POST | `/custom-fields` | PROJECT_ADMIN | 201 + CustomFieldResponse |
| GET | `/custom-fields` | 프로젝트 멤버 | 200 + List |
| GET | `/custom-fields/{fieldKey}` | 프로젝트 멤버 | 200 |
| PATCH | `/custom-fields/{fieldKey}` | PROJECT_ADMIN | 200 |
| DELETE | `/custom-fields/{fieldKey}` | PROJECT_ADMIN | 204 |

이슈 값: 기존 `CreateIssueRequest`/`UpdateIssueRequest`에 `customFields: Map<String, Any?>?` 추가, `IssueResponse`에 `customFields: Map<String, Any?>` 추가.

**PATCH 병합 정책(필드 단위 병합)** — `UpdateIssueRequest.customFields`가
- 부재(키 자체 없음) → custom_fields 무변경
- 명시된 맵 → 맵의 각 키만 병합 갱신(나머지 기존 값 유지)
- 맵 안의 키 = `null` → 해당 필드 값 제거(키 삭제)
required 검증은 병합 후 **최종 상태** 기준. FR-PM-06 JsonNullable 3-state 선례 적용.

- 응답 래핑: `DataResponse`(issue-tracking 관례).
- 에러: RFC7807 ProblemDetail(detail + errorCode, `message` 필드 금지 — learnings).
- 도메인 예외 → HTTP: 프로젝트 미존재 404 / key 중복 409 / 검증 위반 422 / 미인가 403 / 미인증 401.

## 데이터 모델 변경 (issue-tracking 모듈, 다음 = V015)

```sql
-- custom_field_definitions
id UUID PK
project_id UUID NOT NULL REFERENCES projects(id)
key TEXT NOT NULL                 -- URL-safe 소문자, JSONB 키
name TEXT NOT NULL
description TEXT NULL
field_type TEXT NOT NULL           -- FieldType enum 값
required BOOLEAN NOT NULL DEFAULT FALSE
display_order INT NOT NULL
created_at/updated_at TIMESTAMPTZ, deleted_at TIMESTAMPTZ NULL
UNIQUE (project_id, key) WHERE deleted_at IS NULL   -- 부분 유니크

-- custom_field_options (선택형)
id UUID PK
field_id UUID NOT NULL REFERENCES custom_field_definitions(id) ON DELETE CASCADE
value TEXT NOT NULL                -- 저장값(JSONB에 들어가는 값)
label TEXT NOT NULL
display_order INT NOT NULL
UNIQUE (field_id, value)

-- issues 컬럼 추가
ALTER TABLE issues ADD COLUMN custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX idx_issues_custom_fields ON issues USING GIN (custom_fields);
```

> jOOQ codegen 미러 필수(메모리 jooq-init-codegen-mirror) — init_codegen.sql에도 동일 컬럼 반영.

## 값별 JSONB 저장 형식

| FieldType | JSON 값 타입 | 검증 |
|---|---|---|
| SHORT_TEXT | string | 길이 ≤ 255 |
| LONG_TEXT | string | 길이 ≤ 32768 |
| NUMBER | number | 유한수 |
| DATE | string (ISO date) | `YYYY-MM-DD` |
| DATETIME | string (ISO instant) | ISO 8601 |
| SINGLE_SELECT | string | 정의된 option.value 중 하나 |
| MULTI_SELECT | string[] | 모두 정의된 option.value |
| CHECKBOX | boolean | true/false |
| RADIO | string | 정의된 option.value 중 하나 |
| URL | string | http(s) URL 형식 |

## 엣지 케이스

- E1. 미정의 키 포함 → 422 (silent drop 금지, 명시 거부).
- E2. required 필드 값 누락 또는 null → 422.
- E3. 선택형에 정의되지 않은 옵션 값 → 422.
- E4. fieldType과 안 맞는 JSON 타입(NUMBER 필드에 문자열) → 422.
- E5. 정의 삭제(소프트) 후 기존 이슈 값 → 보존하되 조회 응답에서 비활성 정의 키 제외.
- E6. key 중복(활성 기준) 생성 → 409. 삭제된 key 재사용은 허용(부분 유니크).
- E7. SINGLE_SELECT를 MULTI_SELECT로 바꾸기 → fieldType 불변(422)으로 차단. 타입 변경 필요 시 새 필드 생성.
- E8. 옵션 삭제 후 그 값을 가진 기존 이슈 → 값 보존, 신규 저장만 차단(검증 시점 기준).
- E9. 다른 프로젝트의 fieldKey로 접근 → 404(프로젝트 스코프 격리).
- E10. 이슈 클론(FR-IS-06) 시 custom_fields → **미복사**(클론 core-only scope, 메모리 fr-is-06-clone-core-only-scope 일관). 클론 후 별도 입력.
- E11. PATCH customFields 부재 → 무변경 / 맵 명시 → 키 단위 병합 / 키 null → 제거(필드 단위 병합 정책).

## 제약 조건

- DEVELOPMENT.md 절대 규칙(production-ready, 도메인 불변식, 에러 처리). PoC 금지.
- DATA.md: 소프트 삭제, TIMESTAMPTZ, BC 격리 FK 정책, 도메인 우회 금지(PATCH도 service→domain).
- actorId는 ComponentController 선례대로 SYSTEM_ACTOR_UUID placeholder(FR-PM-03 이연), SecurityConfig가 401 보장.

## 측정 가능한 완료 기준

- [ ] V015 마이그레이션 + init_codegen 미러, Flyway 적용.
- [ ] 정의 CRUD API 5종 + 권한 가드(PROJECT_ADMIN) prod Testcontainers 통합으로 ground-truth 검증.
- [ ] 값 검증 9개 엣지(E1~E9) 단위/통합 테스트.
- [ ] 이슈 생성/수정/조회에 customFields 왕복 통합 테스트.
- [ ] issue-tracking 모듈 test + ktlint + detekt(--rerun-tasks) 그린, 회귀 0.
- [ ] 신규 FR-IS-10 전수 동기화(fr-index/SDD/product/README/CLAUDE) + verify-master-plan.sh 통과.

## 1차 범위 밖 (후속 명시)

- **검색/필터(AQL, FR-IS-09)** — 1차는 GIN 인덱스만 깔고 실제 custom_fields 검색 술어는 후속.
- **일괄 편집(FR-IS-05)** — bulk edit의 custom_fields 일괄 변경 후속.
- **PDF 출력(FR-IS-08)** — 이슈 PDF에 custom_fields 렌더 후속.
- **프론트엔드** — 정의 관리 화면 + 10타입 위젯 동적 렌더 + E2E (FR-IS-10 후속 PR).
- **cross-BC 참조 타입** — USER/GROUP/VERSION/COMPONENT picker (FieldType 추가만, 별도 후속).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 발견 → PATCH 병합 정책(Maxi 결정: 필드 단위 병합), 클론 미복사(E10), 목록 노출(IS-10-7), 검색/bulk/pdf 범위밖 명시로 보강 완료.
