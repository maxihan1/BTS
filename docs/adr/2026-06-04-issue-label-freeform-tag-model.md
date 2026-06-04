# 이슈 라벨은 free-form 텍스트 태그 모델 (정규화 테이블 미도입)

> 상태: 채택(Accepted)
> 날짜: 2026-06-04
> 맥락: FR-IS-09 라벨 자동완성
> BC: issue-tracking

## 맥락

FR-IS-09(라벨 자동완성) 착수 시점에 데이터 모델 drift를 발견했다.

- plan 문서(`docs/plan/product/issue-tracking.md §2.2.2`)의 D3은 `labels` + `issue_labels`
  **정규화 조인 테이블 신설**을 명세했다.
- 그러나 SDD 정본(`docs/sdd/05-data-model.md:18`)은 라벨을 `issues.labels TEXT[]`
  **배열 컬럼**으로 설계했고, 실제 코드(V006 마이그레이션 + GIN 인덱스 +
  `Issue.normalizeLabels()` 도메인 검증)도 그렇게 구현되어 있다.

즉 plan의 D3 표기가 SDD 정본·실제 구현과 어긋난 stale 기술이다
(learnings 2026-05-22 "plan/spec drift 시 상위 정본 우선"과 동형).

## 결정

라벨은 **free-form 텍스트 태그**로 유지한다. 정규화 테이블(`labels`, `issue_labels`)을
도입하지 않는다. 기존 `issues.labels TEXT[]` 배열을 그대로 활용한다.

근거.

1. **Jira Cloud 정합**. Jira의 Label은 마스터 테이블 없는 글로벌 free-form 텍스트 태그로,
   색상·설명 같은 메타데이터가 없고 입력 즉시 존재한다. 자동완성은 "이미 쓰인 라벨"을
   prefix로 검색해 재사용하는 방식이다. BTS의 현 구현이 이미 이 모델이다.
2. **SDD 정본 정합**. SDD가 라벨을 `TEXT[]`로, 컴포넌트(FR-CM)·버전(FR-VR)을 별도 정규화
   엔티티로 의도적으로 구분 설계했다. "라벨=태그, 컴포넌트/버전=정규화 엔티티"는 Jira 구분이다.
3. **색상·설명을 가진 정규화 라벨**은 Jira가 아니라 GitHub Issues 모델이며, BTS에서 그 역할은
   컴포넌트/버전이 이미 담당한다.

## 결과

- FR-IS-09 자동완성은 데이터 모델 변경(신규 테이블/마이그레이션) **없이** 조회 엔드포인트
  (`GET /api/v1/labels?q=<prefix>`)만 추가한다. 모든 이슈의 `labels`를 distinct unnest →
  prefix 필터 → 사용 빈도순 정렬로 후보를 제안한다.
- plan 문서 §2.2.2 D3의 "`labels`, `issue_labels`" 표기는 본 ADR에 맞춰 정정한다
  (정규화 테이블이 아니라 기존 `issues.labels TEXT[]` 활용).

## 관련

- SDD 05-data-model.md (라벨 TEXT[] 정본)
- learnings 2026-05-20 (phantom 엔티티), 2026-05-22 (plan/spec drift 시 정본 우선)
