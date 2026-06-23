# ADR: 백로그 LexoRank 정렬 — rank 소유권 + 알고리즘 위치 (FR-BL-01)

> 날짜: 2026-06-23
> 상태: 채택
> 범위: issue-tracking BC (issues.rank) + shared-kernel (LexoRank VO), FR-BL-01 백엔드 (D1~D5, D7 API)
> 관련 SDD: §13.2.1 (LexoRank 정렬)
> 관련 ADR: [agile-planning bootstrap](2026-06-20-fr-bd-01-agile-planning-bootstrap.md) (결정 3에서 LexoRank를 FR-BL-01로 이연)
> 관련 PR: #179

## 맥락

FR-BL-01은 백로그(미할당/대기 이슈 목록)에서 이슈 순서를 자유롭게 재배치하는 기능이다. product 명세(agile-planning §3.1)는 이를 agile-planning BC §3.1에 분류하면서 D3 데이터 모델을 `issues.rank`(TEXT), D4 백엔드를 `PATCH /api/v1/issues/{key}/rank`로 정의한다. SDD §13.2.1은 "VARCHAR(50) 알파벳 키 / 드래그 시 위·아래 이웃의 중간값 / 주 1회 백그라운드 rebalance"로 명시한다.

여기서 BC 경계 충돌이 발생한다. classify-task는 primary_bc를 issue-tracking으로(`/api/v1/issues` 경로 + issues 테이블 기준), product는 FR을 agile-planning §3에 분류한다. `issues` 테이블은 issue-tracking BC가 소유하므로 rank를 어디에 두고 누가 변경하는지 확정이 필요하다.

FR-BD-01 부트스트랩 ADR(결정 3)은 "보드가 issues를 직접 UPDATE하면 워크플로우 불변식을 우회한다(patch-merge-domain-bypass 반례)"며 LexoRank 재정렬을 FR-BL-01로 명시 이연했다.

## 결정 1 — rank는 issue-tracking BC가 소유 (issues.rank 컬럼 + IssueController PATCH)

rank를 `issues` 테이블의 스칼라 컬럼으로 추가하고, 리랭크 API(`PATCH /api/v1/issues/{key}/rank`)를 issue-tracking BC의 `IssueController`가 제공한다.

**근거**.
- **직접 선례**. FR-PL-01(일정 필드)도 product에서 agile-planning(§6 FR-PL)에 분류된 FR이지만, `start_date`/`due_date`/`target_date`는 issue-tracking BC의 마이그레이션 `V025__issue_schedule_dates.sql` + `IssueController`에서 구현됐다. "이슈의 스칼라 속성은 issue-tracking이 소유"가 확립된 패턴이다.
- product D3(`issues.rank`)·D4(`/api/v1/issues/{key}/rank`)와 정확히 일치. `/api/v1/issues`는 issue-tracking 경로.
- rank는 이슈 개체의 정렬 속성이므로 issue 애그리거트 안에 두는 것이 응집도 높음. 별도 테이블로 분리하면 백로그 조회마다 조인이 필요하고 product/SDD 명세와 어긋난다.

## 결정 2 — LexoRank 알고리즘은 shared-kernel 순수 VO

LexoRank의 Rank 값 객체와 중간값 계산(between/gen（initial/rebalance) 로직)을 `backend/modules/shared-kernel`의 `com.bts.shared.lexorank` 패키지에 순수 값 객체로 둔다. issue-tracking은 이 VO를 의존해 rank 컬럼 값을 계산한다.

**근거**.
- glossary가 LexoRank를 "보드/백로그 정렬"(보드와 백로그가 함께 쓰는 알고리즘)로 정의하고, product §1.1도 공유 위치(`backend/shared/lexorank.kt`)를 지정한다.
- BC 의존성이 0인 순수 알고리즘 VO이므로 shared-kernel(이미 ProjectKey/IssueTypeKey 등 cross-BC VO 보유)의 성격과 부합한다.
- 미래 보드 컬럼 내 정렬(agile-planning)에서도 재사용 가능. issue-tracking은 이미 shared-kernel을 의존한다.

## 결정 3 — product/SDD의 `backend/shared` 경로 표기 정정

product agile-planning.md §1.1의 `backend/shared/lexorank.kt` 경로는 실제 모듈 구조와 다르다. `backend/shared` 디렉토리는 존재하지 않으며 공유 모듈은 `backend/modules/shared-kernel`이다. 정본 경로를 `backend/modules/shared-kernel/.../com/bts/shared/lexorank/`로 정정한다(spec/plan 단계에서 product 문서 동기화).

## 결정 4 — FR-BL-01 범위 = 백엔드 D1~D5 + D7(API 레벨)

이번 작업은 도메인(Rank VO)·명세(알고리즘+rebalance)·데이터 모델(issues.rank)·백엔드(PATCH+rebalance)·테스트(1K 부하)·E2E(API 레벨)로 한정한다. 프론트 D6은 product 명세대로 FR-BL-02(백로그→스프린트 드래그)와 통합 예정이므로 이번 PR에서 제외한다.

**근거**. product §3.1 D6이 "(§3.2와 통합)"으로 명시. FR-BD/NT/MV 등 최근 표준의 백엔드/프론트 분리와 일치.

## 대안

- **agile-planning 별도 테이블(backlog_ranks)** — issues 테이블을 안 건드려 BC 격리는 깔끔하나, product D3(issues.rank)·SDD §13.2.1과 어긋나고 백로그 조회마다 조인 필요. FR-PL-01 선례와도 불일치. 기각.
- **LexoRank를 issue-tracking 내부에 배치** — 현재 단일 사용처라 단순하나, glossary/SDD/product §1.1이 보드+백로그 공유 알고리즘으로 전제. 미래 보드 정렬 재사용 시 이동 필요. 기각.

## 결과

- issue-tracking BC에 `issues.rank` 컬럼 + 리랭크 API가 추가되고, shared-kernel에 LexoRank VO가 신설된다.
- rebalance 구체 전략(주기적 스케줄러 vs 중간값 고갈 시 즉시)·rank 인덱스·동시성(OCC/락)은 spec에서 확정한다.
- product/SDD의 `backend/shared` 경로 표기는 shared-kernel로 정정한다(전수 동기화 대상).
