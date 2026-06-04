# FR-IS-09 라벨 자동완성 — 스펙

> slug: fr-is-09-label-autocomplete | BC: issue-tracking | 작성: 2026-06-04
> 데이터 모델: 기존 `issues.labels TEXT[]` 활용 (ADR 2026-06-04-issue-label-freeform-tag-model)
> 스코프 결정: 글로벌 (Jira 정합) — Maxi 확정 2026-06-04

## 배경

이슈에 라벨을 부여할 때 이미 사용 중인 라벨을 입력값(prefix)에 맞춰 자동 제안한다.
라벨은 마스터 테이블 없는 free-form 텍스트 태그(`issues.labels TEXT[]`)이므로, 자동완성은
"기존 이슈들에 실제로 쓰인 라벨"을 prefix로 검색해 재사용을 돕는다.

본 작업의 범위는 **조회 엔드포인트 + 프론트 콤보박스 UI** 뿐이다. 라벨 도메인 검증·
PATCH 교체·클론 복사는 이미 구현되어 있다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (prefix 제안)**. Given 시스템에 "backend", "bug", "billing" 라벨이 이미 쓰였고,
  When 사용자가 라벨 입력란에 "b"를 입력하면, Then 세 라벨이 사용 빈도순으로 드롭다운에 뜬다.
- **S2 (빈도 정렬)**. Given "bug"가 30개 이슈, "backend"가 5개 이슈에 쓰였고, When "b" 입력 시,
  Then "bug"가 "backend"보다 위에 표시된다.
- **S3 (포커스 시 인기 라벨)**. Given 입력란이 비어 있고, When 사용자가 입력란에 포커스하면,
  Then 전체 라벨 중 사용 빈도 상위 N개가 제안된다.
- **S4 (신규 라벨 입력)**. Given 제안 목록에 없는 라벨이 필요하고, When 사용자가 "newlabel"을
  입력하고 확정하면, Then 자동완성에 없어도 그 라벨이 이슈에 추가된다 (free-form 유지).
- **S5 (대소문자)**. Given "Bug"와 "bug"가 서로 다른 이슈에 쓰였고, When "b" 입력 시,
  Then 둘 다 별개 후보로 표시된다 (Jira 동형, 케이스 보존).

## 기능 요구사항 (FR)

- **FR1**. `GET /api/v1/labels?q=<prefix>` 는 활성 이슈(`deleted_at IS NULL`)의 `labels`에서
  prefix가 일치하는 distinct 라벨을 사용 빈도순으로 반환한다.
- **FR2**. 매칭은 **prefix**(시작 일치), **대소문자 무시**(ILIKE). 반환 라벨은 **원본 케이스** 보존.
- **FR3**. 정렬은 (1) 사용 빈도(라벨이 등장하는 활성 이슈 수) **내림차순**, (2) 동률 시 라벨
  문자열 **오름차순(알파벳)** tiebreak — 결정적 순서 보장.
- **FR4**. 결과 개수는 최대 **10개**(고정 상수). prefix 매칭 결과가 10개를 넘으면 빈도 상위 10개만.
- **FR5**. `q`가 생략/빈 문자열/공백-only(trim 후 빈 값)이면 전체 라벨 빈도순 상위 10개를 반환한다.
- **FR6**. 매칭 0건이면 빈 배열을 200으로 반환한다 (404 아님).
- **FR7**. 응답 형식은 `DataResponse<List<String>>` — `{ "data": ["bug", "backend", ...] }`.
  빈도 수치는 응답에 포함하지 않는다(정렬에만 반영).

## 비기능 요구사항 (NFR)

- **NFR1 (보안)**. 인증 필수(Bearer JWT). 권한 가드 = `IssuePermission.VIEW` + `IssueScope.Global`.
  prod 권한 결선은 identity-access BC 책임(기존 패턴), non-prod는 AlwaysAllow fallback 사용.
- **NFR2 (SQL 안전)**. `q`의 ILIKE 와일드카드 문자(`%`, `_`, `\`)는 **반드시 이스케이프**한다
  (ESCAPE 절). 미이스케이프 시 "b%"가 와일드카드로 오작동 → 잘못된 매칭/성능 저하.
- **NFR3 (성능)**. distinct unnest + group by 집계 쿼리. 1,000명 규모 이슈 수에서 < 200ms 목표.
  GIN 인덱스(`ix_issues_labels_gin`)는 배열 포함(@>) 검색용이라 본 집계엔 직접 활용되지 않음 —
  이슈 수가 대폭 증가하면 캐시/머티리얼라이즈드 뷰 도입을 후속 검토(현 범위 미구현).
- **NFR4 (일관성)**. 기존 issue API의 `DataResponse` 래퍼·에러 계약 재사용. 신규 에러 코드 없음.

## API 인터페이스 (REST)

```
GET /api/v1/labels?q=<prefix>
  - 인증: Bearer JWT (필수)
  - 쿼리: q (string, optional) — prefix. 생략/빈값 시 전체 인기 라벨.
  - 200: { "data": ["bug", "backend", "billing"] }   // 빈도순, 최대 10
  - 401: 미인증
  - 403: Global VIEW 권한 없음 (prod 권한 모델)
```

## 데이터 모델 변경

**없음.** 기존 `issues.labels TEXT[]` + `ix_issues_labels_gin` 그대로 활용. 신규 테이블/마이그레이션 0.

집계 쿼리(개념).
```sql
SELECT label, COUNT(*) AS freq
FROM (SELECT id, UNNEST(labels) AS label FROM issues WHERE deleted_at IS NULL) t
WHERE label ILIKE :prefix || '%' ESCAPE '\'   -- prefix 비었으면 이 WHERE 생략
GROUP BY label
ORDER BY freq DESC, label ASC
LIMIT 10
```

## 엣지 케이스

- `q` 없음/빈/공백 → 전체 라벨 빈도순 top-10 (FR5)
- prefix 매칭 0건 → `{ "data": [] }` 200 (FR6)
- 시스템에 라벨이 전혀 없음 → `{ "data": [] }`
- `q`에 `%`/`_`/`\` 포함 → 리터럴로 이스케이프 후 매칭 (NFR2)
- 대소문자만 다른 라벨("Bug"/"bug") → 둘 다 별개 후보 (FR2, S5)
- 삭제(soft-delete)된 이슈의 라벨 → 집계 제외 (FR1)
- `q` 길이 > 50(LABEL_MAX_LENGTH) → 매칭 불가, 빈 배열(에러 아님)

## 제약 조건

- 라벨은 free-form 텍스트 태그 — 자동완성은 "제안"일 뿐, 신규 라벨 입력을 막지 않는다(S4).
- 라벨 도메인 검증(최대 50자/20개, 중복 제거)은 기존 PATCH 경로(`Issue.normalizeLabels`)에서 수행.
  자동완성 엔드포인트는 읽기 전용이라 정규화 불필요(DB에 이미 정규화 저장됨).
- BC 격리: 라벨 자동완성은 issue-tracking BC 내부 완결. 다른 BC 호출 없음.

## 측정 가능한 완료 기준

- [ ] `GET /api/v1/labels?q=bac` → "backend" 등 prefix 매칭만, 빈도순 (통합테스트)
- [ ] 삭제 이슈 라벨이 결과에서 제외됨 (통합테스트)
- [ ] 빈도 동률 시 알파벳순 tiebreak로 결정적 순서 (통합테스트)
- [ ] `q` 빈값 → 전체 top-10 (통합테스트)
- [ ] `q`에 `%` 포함 시 리터럴 매칭 (ILIKE 이스케이프 통합테스트)
- [ ] prefix 매칭 0건 → 빈 배열 200 (통합테스트)
- [ ] 프론트 cmdk 콤보박스: 입력 시 debounce 후 자동완성, 선택/신규입력 모두 가능, 기존 PATCH로 라벨 저장
- [ ] E2E: 이슈 편집 → 라벨 입력 → 자동완성 제안 → 선택 → 저장 happy path

## Brainstorming Check

✅ 자체 적대적 sanity check 통과. 발견·반영한 gap.
- ILIKE 와일드카드 미이스케이프 위험 → NFR2 추가
- `q` 공백-only / 빈값 처리 모호 → FR5 명시(전체 top-10)
- 대소문자 매칭 vs 케이스 보존 충돌 → FR2/S5 명시
- 삭제 이슈 라벨 노출 → FR1 deleted_at 제외 명시
- 빈도 동률 비결정 순서(테스트 flaky 위험) → FR3 알파벳 tiebreak
