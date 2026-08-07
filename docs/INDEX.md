<!-- 자동 생성 — 직접 수정 금지. 원본을 고치고 `node scripts/build-doc-index.mjs` 재실행 -->

# docs 인덱스 — 무엇을 찾느냐에 따라

| 찾는 것 | 열 파일 |
|---|---|
| 이 FR 을 작업할 때 읽을 문서 | [INDEX-fr.md](INDEX-fr.md) |
| 최근에 무슨 작업을 했나 | [INDEX-recent.md](INDEX-recent.md) |
| FR 목록·진척 | [plan/README.md](plan/README.md) · [plan/fr-index.md](plan/fr-index.md) |
| 설계 26개 챕터 | [sdd/README.md](sdd/README.md) |

## 읽는 법 — 통째로 열지 말 것

두 인덱스는 전량을 담아 각각 40KB 를 넘는다. **필요한 행만 grep 한다.**

```bash
grep "^| FR-UX-09 " docs/INDEX-fr.md      # 이 FR 의 spec·plan·adr·memory 한 줄
head -20 docs/INDEX-recent.md            # 최근 작업 12건
grep " fr-ux-08" docs/INDEX-recent.md    # slug 로 찾기
```

> 집계. spec 269 · plan 316 · decision 134 · adr 34
