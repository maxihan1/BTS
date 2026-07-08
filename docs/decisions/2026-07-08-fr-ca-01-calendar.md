<!-- FR-CA-01 개인 캘린더: 모듈 배치 + cross-BC 조회 포트 + 이벤트 taxonomy + visibility 결정 ADR -->

# ADR — FR-CA-01 개인 캘린더: 모듈 배치 + cross-BC 조회 포트 + 이벤트 taxonomy

- 날짜: 2026-07-08
- 상태: 채택 (Accepted)
- 관련 FR: FR-CA-01 (개인 캘린더 — 할당/마감일/Worklog 통합)
- 관련 slug: fr-ca-01-calendar

## 맥락 (Context)

product 문서(`docs/plan/product/personalization.md §5.1`)는 FR-CA-01을 personalization 그룹에 분류하고,
`GET /api/v1/users/me/calendar?from=&to=`로 (1) 할당 이슈, (2) 마감일, (3) Worklog 일정을 월/주 캘린더로
통합 조회한다고 명세한다. "별도 테이블 없이 조회(read-only)만".

코드베이스 조사 결과:

1. **personalization은 물리 모듈이 아니다.** FR-PR-01(`2026-07-05-fr-pr-01-user-profile-placement.md` D1)이
   "논리 BC ≠ 물리 모듈" 패턴을 확립하며 프로필 백엔드를 **identity-access**에 배치했다. `/api/v1/users/me/*`
   엔드포인트군(`UsersController`, `WhoamiController`, `PreferencesController`)이 이미 identity-access에 있다.
2. **캘린더 3데이터가 모두 issue-tracking BC에 있다.**
   - 할당 이슈 / 마감일: `issues.assignee_id`(V007), `issues.start_date/due_date/target_date DATE`(V025, FR-PL-01),
     `due_date` 스캔 부분 인덱스(V026, FR-PL-02).
   - Worklog: `worklogs(author_id, started_at TIMESTAMPTZ, time_spent_seconds)`(V027, FR-TT-01),
     `idx_worklogs_author_started(author_id, started_at)` — "작성자별 기간 조회"용 복합 인덱스 존재.
3. **cross-BC 조회 선례 = TimelineLookupPort.** `shared-kernel/.../timeline/TimelineLookupPort.kt`가
   프로젝트 축 이슈 날짜 조회를 shared-kernel 포트로 노출하고 issue-tracking이 adapter를 구현한다
   (`TimelineLookupAdapter`). agile-planning이 소비. fail-safe 빈 결과 기본값 + viewer visibility 필터 구현체 책임.

## 결정 (Decision)

### D1. 물리 모듈 = identity-access (personalization 논리 BC)

캘린더 엔드포인트(`GET /api/v1/users/me/calendar`)는 identity-access 모듈에 둔다.
`/api/v1/users/me/*` 관례에 응집. fr-index의 FR-CA-01 BC 매핑(personalization)은 변경하지 않는다
(논리 ≠ 물리, FR-PR-01·FR-UX-01 선례). 카운트/BC 합계 영향 0.

### D2. cross-BC 조회 = 신규 shared-kernel 포트 (사용자 축)

identity-access가 issue-tracking 데이터를 읽기 위해 **신규 shared-kernel 포트**를 도입한다.
TimelineLookupPort는 프로젝트 축(`listTimelineItemsByProject`)이라 직접 재사용 불가 — 캘린더는 사용자 축
("내 담당 이슈 + 내 Worklog, 기간 [from,to]")이다. 새 포트를 shared-kernel에 배치하고 issue-tracking이
adapter를 구현한다. identity-access는 issue-tracking을 gradle 직접 의존하지 않는다(BC 격리, ArchUnit 강제).

- 포트 배치: `shared-kernel/.../calendar/UserCalendarLookupPort.kt` (인터페이스명은 spec에서 확정)
- 구현: issue-tracking adapter
- fail-safe: adapter 미등록(테스트 stub / 단계적 배포) 시 빈 결과 반환(데이터 조회 실패는 보안 판단 아님).
  TimelineLookupPort와 동일 방향.

### D3. 이벤트 taxonomy = 기간 막대 포함 (Maxi 확정 2026-07-08)

캘린더 이벤트는 두 종류.

- **이슈 이벤트** — 담당자 = 조회자(me)이고 `start_date`/`due_date` 중 하나 이상 있는 이슈.
  마감일 점 + start~due 기간 막대로 렌더링. 포트 VO는 `startDate`/`due_date`를 담는다(TimelineItemView 형태 참고).
- **Worklog 이벤트** — 작성자 = 조회자(me)인 활성 worklog. `started_at` 날짜 + `time_spent_seconds`.

`target_date` 마일스톤은 **제외**(로드맵/FR-TL 개념, 개인 캘린더 과도). 후속 필요 시 확장.

### D4. 담당 범위 = assignee = me only

이슈 이벤트는 `assignee_id = 조회자` 이슈만. reporter/watcher는 제외(product "할당" 문구).

### D5. visibility 보안 필터 = issue-tracking adapter 책임 (fail-closed)

adapter는 viewer(=조회자) 기준 visibility 보안 필터를 SQL 수준에서 적용한다. 담당자여도 조회자가 볼 수 없는
보안 등급 이슈는 결과에서 제외(권한 상실·보안등급 상향 엣지 케이스 누출 차단). TimelineLookupAdapter의
visibility 필터 패턴 재사용. Worklog는 조회자 본인 것만이라 이슈 가시성과 별개(본인 데이터).

### D6. timezone = 사용자 프로필 timezone 기준 날짜 매핑

이슈 날짜는 DATE(시각 없음), Worklog `started_at`은 TIMESTAMPTZ(시각 있음). worklog가 "며칠"에 속하는지는
사용자 timezone에 의존한다. 사용자 프로필 timezone(`user_profiles.timezone`, FR-PR-01)을 기준으로 worklog
`started_at`을 로컬 날짜로 매핑한다. `from`/`to`는 사용자 로컬 날짜로 해석 → worklog 조회 시 Instant 범위로 변환.
상세(프로필 미설정 시 기본 timezone·경계 처리)는 spec에서 확정.

### D7. read-only, 별도 테이블 없음

product 데이터 모델 "조회만, 별도 테이블 X" 준수. 신규 마이그레이션 없음. jOOQ 조회 쿼리만 추가.

## 결과 (Consequences)

- identity-access에 `CalendarController` + application service + `UserCalendarLookupPort` 소비 배선 추가.
- shared-kernel에 신규 포트 + VO(이슈 이벤트 / worklog 이벤트).
- issue-tracking에 adapter(assignee=me + 날짜 있는 이슈 / author=me worklog, viewer visibility 필터) 추가.
- 신규 마이그레이션·테이블 0(조회 인덱스 V026·V027 재사용).
- fr-index/SDD의 FR 카운트·BC 매핑 변경 없음(논리 ≠ 물리).
- 새 shared-kernel 포트 소비 → identity-access full-boot 시 `@MockBean`/stub 배선 필요
  (memory: new-crossbc-dep-openapi-mockbean-regression).

## 대안 (Rejected)

- **TimelineLookupPort 재사용.** 기각 — 프로젝트 축이라 사용자 축 조회 불가. 프로젝트 전체 순회는 비효율 + 담당 필터 부재.
- **personalization 신규 모듈 신설.** 기각 — `/users/me/*`가 identity-access에 응집. FR-PR-01 선례.
- **target_date 포함(풀 taxonomy).** 기각 — 로드맵 마일스톤은 개인 캘린더 범위 밖(Maxi 확정).
- **issue-tracking에 직접 엔드포인트.** 기각 — `/users/me/calendar`는 개인화 경로. cross-BC 포트로 데이터만 위임.
