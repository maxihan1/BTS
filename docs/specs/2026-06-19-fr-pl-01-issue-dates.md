# FR-PL-01 — 일정 필드 (Start/Due/Target Date) 스펙

> BC: issue-tracking | type: feature | slug: fr-pl-01-issue-dates
> SDD: 05.1(데이터 모델 issues 24~26행), agile-planning §6.1
> 동형 선례: Version 날짜 필드(V010, ChangeVersionDatesRequest/VersionResponse), securityLevelId 3-state PATCH

## 개요

이슈에 일정 필드 3종을 추가한다 — `startDate`(시작일), `dueDate`(마감일), `targetDate`(목표일). 모두 선택(nullable) **DATE**(시각·타임존 없는 캘린더 날짜). 향후 FR-TL-01(타임라인/Gantt)·FR-PL-02(지연 알림)의 데이터 원천.

## 사용자 시나리오 (Given-When-Then)

1. **일정 지정** — Given 이슈 상세 화면, When 사용자가 시작일·마감일·목표일을 데이트픽커로 선택하고 저장, Then 이슈에 날짜가 저장되고 상세/응답에 ISO(yyyy-MM-dd)로 표시된다.
2. **일정 클리어** — Given 마감일이 설정된 이슈, When 데이트픽커에서 마감일을 비우고 저장, Then `dueDate`만 null로 클리어되고 나머지 필드는 유지된다.
3. **부분 무변경** — Given 일정이 설정된 이슈, When 일정 외 필드(예: summary)만 PATCH(요청 바디에 날짜 키 부재), Then 기존 날짜는 그대로 유지된다.
4. **검증 없음(Jira 정석)** — Given, When 시작일을 마감일보다 늦게 저장, Then 거부 없이 그대로 저장된다(경고도 없음). Maxi 확정.
5. **혼합 패치** — Given, When 한 요청에서 시작일은 설정·마감일은 클리어·목표일은 미포함, Then 각 필드가 3-state 시맨틱대로 독립 적용된다.

## 기능 요구사항 (FR)

- **F1**. `issues` 테이블에 `start_date`, `due_date`, `target_date` 컬럼(`DATE NULL`)을 추가한다.
- **F2**. `PATCH /api/v1/issues/{key}` 를 확장해 3필드를 **3-state 부분수정**한다. 필드 부재=무변경 / 명시 null=클리어 / 날짜값=설정.
- **F3**. 이슈 단건 응답(`IssueResponse`)에 3필드를 포함한다(미지정 시 null). 직렬화는 ISO-8601 `yyyy-MM-dd`.
- **F4**. **교차 필드 검증 없음** — start/due/target 간 선후 관계를 강제하지 않는다(Version `ChangeVersionDatesRequest` 주석과 동일 정책).
- **F5**. 권한은 기존 `IssuePermission.UPDATE` + `IssueScope.Issue(key)` 를 재사용한다(신규 권한 코드 없음). 다른 PATCH 스칼라 필드와 동일 게이트.
- **F6**. 변경은 도메인 mutation 메서드를 경유한다(repository 직접 update 금지, patch-merge-domain-bypass 방지). OCC(`expectedVersion`) 적용 — 날짜 편집도 이슈 version을 올린다.
- **F7**. (프론트) 이슈 상세 화면에 3개 데이트픽커를 추가한다. 날짜 선택/해제 가능, date-fns 포맷.

## 비기능 요구사항 (NFR)

- **N1**. DATE 타입 → 타임존 무관. 저장·표시 모두 캘린더 날짜 그대로(시각 변환 없음).
- **N2**. 기존 `PATCH /{key}` 필드(summary/priority/labels/securityLevel/customFields 등) 동작 무회귀.
- **N3**. 잘못된 날짜 형식 입력은 400으로 거부(Jackson LocalDate 파싱 실패 경로).
- **N4**. DEVELOPMENT.md §1 절대 규칙(테스트·에러 처리·non-null)·DATA.md(소프트삭제 무관, 단순 컬럼 추가) 준수.

## API 인터페이스 (REST)

### 요청 — `PATCH /api/v1/issues/{key}` (기존 엔드포인트 확장)

```jsonc
{
  "expectedVersion": 3,          // 기존, 필수 (OCC)
  "startDate": "2026-06-20",     // 신규: 부재=무변경 / null=클리어 / "yyyy-MM-dd"=설정
  "dueDate": null,               // 신규: 명시 null → 클리어
  "targetDate": "2026-07-01"     // 신규
  // (summary/priority/… 기존 필드는 그대로 공존)
}
```

- DTO: `UpdateIssueRequest` 에 `startDate/dueDate/targetDate: JsonNullable<LocalDate>` 추가, 기본값 `JsonNullable.undefined()`. → securityLevelId 동형(3-state).
- (대비) Version `/dates` 서브리소스는 2-state(LocalDate=설정/null=해제)지만, 그건 전용 replace 엔드포인트라서다. 이슈는 RFC 7396 merge patch라 필드 부재(무변경)를 구분해야 하므로 `JsonNullable` 필수.

### 응답 — `IssueResponse` 확장

```jsonc
{
  "key": "BTS-1", "version": 4, /* … */
  "startDate": "2026-06-20",    // LocalDate? (null 가능)
  "dueDate": null,
  "targetDate": "2026-07-01"
}
```

## 데이터 모델 변경

- 마이그레이션 **V025**(현재 최신 V024). `ALTER TABLE issues ADD COLUMN start_date DATE NULL, ADD COLUMN due_date DATE NULL, ADD COLUMN target_date DATE NULL;` + COMMENT.
- `db/codegen/init_codegen.sql` 의 `issues` 블록에도 동일 3컬럼 미러(jOOQ codegen 정합, memory: jooq-init-codegen-mirror).
- ⚠️ V번호는 머지 직전 재확인(동시 브랜치 Flyway 충돌). 현재 병행 fr-nt-03=notification 모듈이라 issue-tracking과 무충돌이나 규칙상 재확인.

## 엣지 케이스

| # | 케이스 | 기대 |
|---|---|---|
| E1 | 미존재/소프트삭제 이슈 PATCH | 404 ISSUE_NOT_FOUND (기존 동작) |
| E2 | `expectedVersion` 불일치 | 409 Version Conflict (기존 OCC) |
| E3 | UPDATE 권한 없음 | 403 (기존 게이트) |
| E4 | 잘못된 날짜 형식("2026-13-40") | 400 (Jackson 파싱 실패) |
| E5 | 윤년 2/29, 월말 날짜 | 정상 저장 |
| E6 | start > due (역순) | 정상 저장 (검증 없음, F4) |
| E7 | 3필드 모두 부재 | 날짜 무변경, 다른 필드만 적용 |
| E8 | 같은 요청서 일부 설정·일부 클리어·일부 부재 | 각 필드 3-state 독립 적용 |
| E9 | 응답 매핑: 날짜 미지정 이슈 | startDate/dueDate/targetDate = null |

## 제약 조건

- DATE만(시각/타임존 없음). DATETIME 아님.
- 교차 필드 검증 없음(Maxi 확정).
- agile-planning 모듈 신설 없음 — issue-tracking BC 내 확장.
- BC 격리 — 다른 BC import 없음. 향후 FR-TL/FR-PL-02가 이 컬럼을 읽음(이번 범위 밖).

## 측정 가능한 완료 기준

- 백엔드 단위: 도메인 mutation(설정/클리어/무변경 3-state × 3필드), 검증 없음(E6) 통과.
- 백엔드 통합(Testcontainers): PATCH 설정→응답 반영, 클리어→null, 무변경 유지, 404/409/403/400(E1~E4), 혼합 패치(E8), OCC version +1.
- 마이그레이션: V025 적용 + init_codegen 미러 → jOOQ 빌드 그린.
- 프론트: 데이트픽커 3필드 단위 테스트 + MSW(설정/클리어 반영), Zod 응답 스키마 3필드 nullable.
- E2E: happy path(날짜 설정→저장→상세 표시) 1건 이상.

## Brainstorming Check (self sanity — gap 카테고리별)

- **누락 요구사항**: 응답 직렬화(F3)·잘못된 형식 400(N3·E4)·OCC version bump(F6) 명시로 보강. ✅
- **모호 표현**: "3-state" → 부재/null/값 명시(F2). DATE vs DATETIME 명시(N1). ✅
- **가정 누락**: 권한=기존 UPDATE 재사용(F5, 실측 IssueApplicationService L6) — 신규 권한 가정 배제. agile-planning 모듈 미신설 명시. ✅
- **엣지 미커버**: 역순 날짜(E6)·혼합 패치(E8)·윤년(E5)·응답 null(E9) 추가. ✅
- **결론**: gap 없음. Version 날짜 패턴 + securityLevelId 3-state 선례로 설계 공간 닫힘.

✅ 통과 (직접 sanity check — 동형 선례 2종으로 설계 확정, 인터랙티브 brainstorming 생략: memory bts-spec-office-hours-mismatch 원칙)
