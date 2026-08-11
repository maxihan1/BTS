# context-notes — pending mutation 누수 봉합 + 전역 가드 승격 (R2)

작업 중 내린 결정과 그 근거. 계속 덧붙인다.

## D1. 대상 집합을 정적 grep 이 아니라 1회 실측으로 만든다 (TODOS 처방 승계)

원 처방은 「34개 파일을 디렉토리 단위로 나눠 이주」였다. **실측이 그 전제를 뒤집었다.**

| 기준 | 값 |
|---|---|
| 34-set 중 mutation 을 아예 안 만드는 파일 | **13** (원천적으로 무관) |
| 34-set 중 mutation 은 만들지만 누수 0 | **14** (이미 안전) |
| 34-set 중 실제 누수 | **7** |
| 34-set **밖**인데 실제 누수 | **3** |
| 34-set·22-set **둘 다 밖**인데 실제 누수 | **2** |

즉 정적 목록은 **거짓양성 27/34(79%) · 거짓음성 3/10(30%)** 로 양방향 모두 틀렸다.
대상이 10파일 13건으로 줄어 **디렉토리 분할이 불필요**해졌고 한 PR 로 닫는다.

## D2. ★제3 관용구 — 「수동 게이트」가 두 정의 모두를 빠져나갔다

`RuleExecutionTraceRow.test.tsx` · `CreateIssueDialog.test.tsx` 는 `setTimeout` · `delay()` ·
무한 Promise 가 **전부 0건**인데 pending 을 남긴다. 실제 형태는 이렇다.

```ts
let release: () => void = () => {}
const held = new Promise<void>((resolve) => { release = resolve })   // ← executor 인자 1개
server.use(http.post('/api/v1/issues', async () => { await held; return HttpResponse.json(...) }))
// ... 검증 ...
release()   // 풀어주긴 한다. 그러나 정착을 기다리지 않고 테스트가 끝난다
```

- executor 인자가 **1개**(`resolve`)라 22-set 정의(`\(\(\) *=>` — 인자 0개)에 안 걸린다
- `setTimeout` · `delay()` 를 쓰지 않아 34-set 에도 안 걸린다
- **그런데 응답이 실제로 도착한다.** 무한 Promise(영원히 안 옴)보다 위험하다 —
  `release()` 이후 응답이 다음 파일이 도는 동안 도착해 `onSuccess` 가 실행된다

⇒ **정적 grep 기반 가드는 원리적으로 불가능**하다는 실증. 가드는 런타임이어야 한다.

## D3. 추적은 `MutationCache.prototype.build` 래핑으로 한다

선례 `AutomationYamlImportDialog.test.tsx:107` 은 파일 로컬 `lastQueryClient` 를 렌더 헬퍼가
채우는 방식이다. 전역화하려면 **모든 테스트 파일이 등록해 줘야** 하므로 쓸 수 없다.

`build` 를 감싸면 **mutation 을 실제로 만든 캐시만** 레지스트리에 들어온다. 그래서
「레지스트리가 비었다」가 「이 파일은 mutation 을 아예 안 만들었다」와 **동치**가 되고,
TODOS ②가 경고한 `?? []` 항진명제(레지스트리가 비면 무조건 통과)가 구조적으로 사라진다.

## D4. `process.on('unhandledRejection')` 경로는 쓰지 않는다

`setup.ts:19-45` 가 절대 금지로 못박았다. 리스너가 1개를 넘으면 vitest 가 물러나
**종료 코드가 1 → 0 으로 뒤집힌다**. 프로토타입 래핑은 그 경로가 아니라 무관하다.

## D5. 측정을 2회 돌렸다 — 1회로 확정하지 않는다

체크포인트에 「재측정 자체가 4회 틀렸다」가 있고, pending 누수는 타이밍 의존일 수 있다.
2회차가 **파일::테스트 단위로 차이 0건**이라 대상집합이 안정적임을 확인한 뒤 확정했다.
(메모리 `flaky-determination-needs-repeat-not-single-contrast` — 단일 대조로는 flaky 판정 불가.)

## D6. 전역 `afterEach` 는 수집 → 레지스트리 비우기 → 단언 순서로 한다

단언이 throw 하면 그 뒤 코드가 안 돌기 때문에, 레지스트리 정리를 **단언 앞**에 둔다.
정리하지 않으면 같은 pending mutation 이 뒤따르는 모든 테스트에서 다시 잡혀 **연쇄 실패**한다.
`build` 가 mutation 마다 호출되므로 캐시는 다음 mutation 때 재등록되고 추적 손실은 없다.

## D7. ★★가드가 자기 자신을 부풀렸다 — 봉합이 새 회귀

가드를 켠 첫 실행에서 `FavoriteButton` 이 **5건** red 였다. 측정값은 3건이다.
추가 2건(S6a·S7b)의 실패 사유는 pending 단언이 **아니라**
`TestingLibraryElementError: Found multiple elements with the role "button"` 이었다.

**기전.** 전역 `afterEach` 의 `expect` 가 throw 하면 **같은 root 레벨에 등록된 RTL 자동 cleanup 이
실행되지 않는다.** DOM 이 남고 다음 테스트가 연쇄 실패한다. 소요 시간이 20ms 대(진짜 위반) 대
1000ms 대(waitFor 타임아웃)로 갈린 것이 서명이었다.

**처방.** `afterEach` 맨 앞에서 `cleanup()` 을 **직접** 부른다. 멱등이라 RTL 이 나중에 또 불러도
무해하고, unmount 뒤에 재는 편이 판정도 엄밀하다(컴포넌트가 사라졌는데도 남은 것만 누수로 잡힌다).

**교훈.** 전역 훅에 throw 하는 단언을 넣으면 **그 뒤에 등록된 정리 훅을 전부 인질로 잡는다.**
같은 형태(전역 afterEach 단언)를 추가할 때마다 재발한다.

## D8. ★★측정조차 2건을 놓쳤다 — 목록을 정본으로 믿지 말 것

가드를 켠 전량 RED 는 16건이었고, `WatchersSection.test.tsx` S9a·S9c **2건이 측정 목록에 없었다.**
소스를 보면 `FavoriteButton` S7a·S7c 와 **구조가 완전히 동일**하다 — 같은 패턴을 복사한 형제 파일이다.

**기전.** 프로브의 `afterEach` 는 `import` 위치 때문에 `setup.ts` 의 `afterEach` 보다 **늦게** 실행됐고,
훅 사이의 await 경계에서 마이크로태스크가 flush 되며 그 사이 정착한 것을 놓쳤다.
가드는 `setup.ts` 안에서 더 이른 시점에 재므로 더 엄격하다.

**어느 쪽이 옳은가.** 엄격한 쪽이다. 선례 `AutomationYamlImportDialog.test.tsx:107` 도 파일 레벨
`afterEach` 에서 **가장 이른 시점**에 판정하며, 주석이 그 의도를 「파일 안에서 즉시 드러낸다 —
옆 파일로 번지기 전에」라고 적었다. 그리고 「의도적으로 붙잡은 것은 테스트가 명시적으로 정리한다」가
더 좋은 규율이다.

**교훈.** 측정 목록은 **하한**이지 정본이 아니다. 같은 관용구를 쓰는 형제 파일이 있으면 목록에
없어도 의심하고, **가드를 켠 red 목록**을 정본으로 삼는다.

## D9. 가드 주석이 기존 판별식을 건드렸다

`msw-single-setupserver.test.ts` 가 `pending-mutation-guard.ts` 를 위반으로 지목했다.
내가 주석에 34-set 정의를 **문자 그대로** 옮겨 적었고 거기에 그 판별식이 감시하는 문자열이 있었다.

선례가 이미 같은 문제를 `NEEDLE = 'setupServer' + '('` 런타임 조립으로 풀어 뒀는데, 그건 판별식
**자신**의 회피책이고 **새로 들어오는 파일**은 각자 피해야 한다. 주석에서 정의를 풀어쓰는 것으로 해결했다.

**교훈.** 소스를 훑는 판별식이 있는 저장소에서는 **주석도 소스다.** 정의를 인용할 때 원문 복사는
판별식을 깨운다.
