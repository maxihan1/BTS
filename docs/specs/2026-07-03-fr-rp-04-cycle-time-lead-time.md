# FR-RP-04 — Cycle Time / Lead Time 분포 리포트 — 스펙

> slug: fr-rp-04-cycle-time-lead-time · type: backend · BC: issue-tracking(논리 notification-dashboard)
> 범위: 백엔드 D1~D5 (프론트 D6/D7 히스토그램+박스플롯은 후속 PR)
> 선행: FR-RP-03 CFD(ADR `2026-07-03-fr-rp-03-cfd`) — 상태 이력 역산 인프라 재사용
> Maxi 확정(2026-07-03): Cycle=첫 IN_PROGRESS→완료(미경유 제외) · 모집단=완료일 기준 창 내 완료 이슈 · 응답=이슈별 샘플+요약 통계

## 개요

프로젝트의 **완료된 이슈**들이 얼마나 걸려서 끝났는지의 **분포**를 조회한다.
- **Lead Time** = 이슈 생성(created) → 완료(DONE 도달)까지의 총 소요.
- **Cycle Time** = 첫 작업 착수(IN_PROGRESS 카테고리 최초 진입) → 완료까지의 소요.

두 지표는 이슈별 **초 단위** 값 배열 + 서버 계산 요약 통계로 반환한다. 프론트(후속 PR)가 히스토그램 binning과 박스플롯을 그린다. CFD와 달리 날짜(day) 절삭이 아니라 실제 타임스탬프 기반이다(같은 날 완료 이슈가 0으로 뭉개지면 분포가 무의미).

## 사용자 시나리오 (Given-When-Then)

- **S1 정상 조회**. Given 프로젝트에 최근 30일 내 완료된 이슈 여러 건, When 팀 리드가 `GET /projects/{key}/cycle-time` 호출, Then 이슈별 cycle/lead 초 배열 + count·min·max·avg·p50·p75·p90 통계를 받아 병목/분산을 파악한다.
- **S2 IN_PROGRESS 미경유**. Given TODO에서 바로 완료된 이슈(즉시 close), When 조회, Then 그 이슈는 **Lead Time 분포에만** 포함되고 Cycle Time 분포에서는 제외된다(Cycle 표본 수 < Lead 표본 수).
- **S3 재오픈**. Given 완료 후 재오픈되었다가 다시 완료된 이슈, When 조회, Then Cycle = 첫 IN_PROGRESS 진입 → **마지막** DONE 도달(재작업이 cycle을 늘림), 완료일 = 마지막 DONE 전이일로 창 판정.
- **S4 창 밖 완료**. Given 40일 전 완료된 이슈(기본 창 30일), When 기본 조회, Then 그 이슈는 모집단에서 제외된다(완료일이 창 밖).
- **S5 빈 결과**. Given 창 내 완료 이슈 0건, When 조회, Then count=0·samples=[]·통계 필드 null 로 200 반환(에러 아님).
- **S6 기밀 이슈 누출 차단**. Given 뷰어가 못 보는 기밀 이슈가 창 내 완료, When 뷰어가 조회, Then 그 이슈는 샘플·통계 어디에도 안 잡힌다(가시성 필터).
- **S7 권한 없음**. Given BROWSE 권한 없는 사용자, When 조회, Then 403(이슈 존재 probe 차단 — repo 조회 전 권한 검사).
- **S8 잘못된 창**. Given `from > to` / 파싱 실패 / 상한(180일) 초과, When 조회, Then 400.

## 기능 요구사항 (FR)

- **FR1**. 엔드포인트 `GET /api/v1/projects/{projectKey}/cycle-time?from=&to=`. `from`/`to`는 UTC 날짜(ISO `yyyy-MM-dd`), 둘 다 생략 가능. 기본 = 최근 30일(`to`=오늘(UTC, Clock 주입), `from`=`to`−29일). 실제 적용된 창을 응답에 echo.
- **FR2 모집단**. 창 내 **완료 이슈** = 이슈의 **마지막 DONE-카테고리 status 전이** 시각(`completedAt`)의 UTC 날짜가 `[from, to]` 안에 드는 이슈. 소프트 삭제 이슈 제외. 뷰어 가시 이슈로 한정.
- **FR3 Lead Time**. `leadTimeSeconds` = `completedAt − created_at`(초, ≥ 0). 모집단 전원 산출.
- **FR4 Cycle Time**. `cycleStart` = 이슈의 **첫 IN_PROGRESS-카테고리 status 전이** 시각. `cycleTimeSeconds` = `completedAt − cycleStart`(초). IN_PROGRESS 전이가 없거나 `cycleStart > completedAt`(음수)면 해당 이슈는 Cycle 분포에서 **제외**(Lead에는 유지).
- **FR5 카테고리 매핑**. 상태 키 → 카테고리(TODO/IN_PROGRESS/DONE)는 `WorkflowStateCatalog.category`(이슈 타입별) 재사용. 카탈로그에 없는(삭제된) 상태 키는 TODO 폴백(CFD/EpicProgress 동형). 초기 상태 = 첫 status 전이의 `fromValue`(없으면 `currentStateKey`) — CFD `buildTimeline` 동형.
- **FR6 요약 통계**. cycle/lead 각각: `count`, `min`, `max`, `avg`(정수 초, 반올림), `p25`, `p50`, `p75`, `p90`. `p25`는 프론트 박스플롯 Q1(하단 경계)용 — 박스플롯 상자 = min·p25·p50·p75·max이므로 서버가 함께 제공해 프론트 재-백분위와 "중앙값 두 개" 불일치를 방지한다. 백분위 = **nearest-rank**(오름차순 정렬 `v[0..n-1]`, `idx = clamp(ceil(p/100·n)−1, 0, n−1)`, 값 = `v[idx]`). `count==0`이면 min/max/avg/p25/p50/p75/p90 = null.
- **FR7 샘플**. `samples: [{issueKey, seconds}]` 이슈별 배열. cycle 샘플은 Cycle 모집단만, lead 샘플은 Lead 모집단(=전체). 정렬 = `seconds` 오름차순(결정성). issueKey는 가시성 필터를 통과한 이슈만이라 노출 안전.
- **FR8 보안**. (1) 프로젝트 BROWSE 검사를 repo 조회보다 먼저(존재 probe 차단). (2) 집계 전 `IssueRepository`가 `buildActiveSecureWhere` 보안 술어를 **재사용**해 뷰어 가시 이슈만 조회(복제 금지 — isomorphic-clone-permission-guard-gap 회귀 방지).

## 비기능 요구사항 (NFR)

- **NFR1 성능**. 창 상한 180일 + 프로젝트당 이슈·전이 행 수 유계. status 전이 배치 조회(1쿼리 in-set) + 타입별 상태 카탈로그 캐싱(N+1 차단, CFD 동형). p95 < 500ms 목표(§NFR 측정표 "번다운 조회" 기준 준용).
- **NFR4 페이로드**. `samples` 크기 = O(창 내 완료 이슈 수)(CFD의 O(days)와 다름). v1은 **캡 없음**(히스토그램·박스플롯이 전체 표본 필요, 무언의 절삭 금지). 1K 규모·180일 상한에서 프로젝트당 완료 이슈 수는 유계. 장래 대용량 시 서버 사이드 binning 전환 여지(product 명세 기록).
- **NFR2 결정성**. UTC 벽시계 날짜(`(ts AT TIME ZONE 'UTC')::date`), Clock 주입(기본 창 계산). 세션 TZ 무관.
- **NFR3 신규 스키마·스케줄러 0**. on-the-fly. 마이그레이션 없음.

## API 인터페이스 (REST)

`GET /api/v1/projects/{projectKey}/cycle-time?from=2026-06-03&to=2026-07-03`

응답 200 `DataResponse<CycleTimeResponse>`(BTS 표준 외피):
```json
{
  "data": {
    "projectKey": "PROJ",
    "from": "2026-06-03",
    "to": "2026-07-03",
    "cycleTime": {
      "count": 2, "min": 86400, "max": 259200, "avg": 172800,
      "p25": 86400, "p50": 172800, "p75": 259200, "p90": 259200,
      "samples": [ {"issueKey":"PROJ-12","seconds":86400}, {"issueKey":"PROJ-9","seconds":259200} ]
    },
    "leadTime": {
      "count": 3, "min": 90000, "max": 600000, "avg": 300000,
      "p25": 90000, "p50": 210000, "p75": 600000, "p90": 600000,
      "samples": [ {"issueKey":"PROJ-12","seconds":90000}, {"issueKey":"PROJ-7","seconds":210000}, {"issueKey":"PROJ-9","seconds":600000} ]
    }
  }
}
```
- 401 actor 미인증 · 403 BROWSE 없음 **또는 프로젝트 미존재**(존재 여부 노출 안 함 — probe 차단, CFD 정합) · 400 잘못된 창(`from>to`/파싱실패/상한초과).
- 빈 결과: `count:0, min/max/avg/p25/p50/p75/p90: null, samples: []`.

## 데이터 모델 변경

**없음.** 신규 마이그레이션 0. 활용 대상:
- `issues`(created_at·current_state_key·type_id·deleted_at) — 재사용.
- `issue_change_group`/`issue_change_item`(V018, status 전이 이력) — `CfdStatusHistoryRepository.fetchStatusChanges` **그대로 재사용**.
- `WorkflowStateCatalog`(shared-kernel SPI) via `IsolatedWorkflowStateLookup` — 재사용.
- 신규 read-model: `CycleTimeIssueSourceRow(issueId, issueKey, typeId, currentStateKey, createdAt)` — `CfdIssueSourceRow` + issueKey. 전용 `fetchActiveVisibleIssuesForCycleTime`(보안 술어 `buildActiveSecureWhere` 재사용, CFD 쿼리 불변).

## 엣지 케이스

- **EC1 status 전이 이력 없음**(V018 이전 생성 or 직접 done 생성). 완료 시각 불명 → **모집단 제외**(v1 한계, CFD "V018 이전 이슈" 동종). 문서화.
- **EC2 IN_PROGRESS 미경유**. Cycle 제외, Lead 포함(S2).
- **EC3 cycleStart > completedAt**(재오픈 후 미완 상태에서 과거 done만 존재 등). Cycle 음수 → 제외(FR4).
- **EC4 삭제된 상태 키**. TODO 폴백(FR5).
- **EC5 같은 시각 다중 전이**. `group.created_at` + `groupId` tie-break로 결정적 순서(CFD `CfdStatusChangeRow` 정렬 동형).
- **EC6 가시 이슈 0건**. status 전이/카탈로그 조회 스킵, 즉시 빈 결과 반환(jOOQ 빈 IN 절 방어, CFD 동형).
- **EC7 completedAt == cycleStart == created_at**(즉시 흐름). seconds=0 유효 표본(제외 아님, 음수만 제외).
- **EC8 초기 상태가 IN_PROGRESS/DONE로 직접 생성**(희귀). `completedAt`/`cycleStart`는 **전이 이력에서만** 신뢰한다(초기 event 미사용, 레거시 오-계산 방지). 따라서 DONE 진입 전이가 없으면 완료 미집계(EC1과 합류), IN_PROGRESS 진입 전이가 없으면 Cycle 미집계. v1 한계 — 대다수 이슈는 TODO 카테고리 초기 상태라 영향 미미. 문서화.

## 제약 조건

- 한 PR = 한 BC(issue-tracking). cross-BC 신규 포트 0(스프린트 미참조).
- CFD 인프라는 **재사용**(status 이력 조회·상태 카탈로그·보안 술어). 보안 술어 복제 금지.
- fr-index 논리 BC 라벨 notification-dashboard 유지, 총수 123·notification-dashboard 14 불변.
- 완제품 품질(테스트·보안·에러 처리). PoC 금지.

## 측정 가능한 완료 기준

- [ ] `GET /projects/{key}/cycle-time` 200 + `DataResponse<CycleTimeResponse>` 외피, from/to echo.
- [ ] Cycle 표본이 IN_PROGRESS 미경유 이슈를 제외하고 Lead 표본은 포함(S2 통합 테스트로 실측).
- [ ] 완료일 창 필터가 창 밖 완료 이슈를 제외(S4).
- [ ] 기밀 이슈가 샘플·통계에서 제외됨을 비-vacuous 통합 테스트로 실측(S6, 기밀 이슈 시드 후 대조).
- [ ] 401/403/400/빈결과(200) 경계 테스트.
- [ ] nearest-rank 백분위 계산기 단위 테스트(p25/p50/p75/p90, 경계 n=1, 짝/홀수, count=0 null).
- [ ] 신규 마이그레이션 0 · ktlint/detekt 통과 · CFD(FR-RP-03) 회귀 0.

## Brainstorming Check

✅ 통과 (2회 iteration). 발견·보강한 gap 4건.
- p25 누락 → 박스플롯 Q1용 추가(서버 단일 진실, 중앙값 불일치 방지).
- 프로젝트 미존재 404 → 403(존재 probe 차단 정합).
- 초기 상태 직접 생성(IN_PROGRESS/DONE) 미명시 → EC8(전이 기반 신뢰, v1 제외).
- samples 페이로드 O(issues) 특성 미명시 → NFR4(캡 없음·절삭 금지 명문화).
