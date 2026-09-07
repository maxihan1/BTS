// 이슈를 삽입하는 생산 경로가 본문 두 컬럼(description·description_html)을 함께 다루는지 대조한다
//
// ## 왜 있나
//
// V039 가 이슈 본문을 두 컬럼으로 쪼갰다 — `description`(마크다운, 레거시·CSV import)과
// `description_html`(리치 에디터가 보낸 정화 HTML). 그런데 **수정 경로만** 두 컬럼을 함께
// 다루도록 옮기고 생성·복제 경로는 두고 갔다.
//
// 2026-09-07 실측으로 드러난 결과.
//
// 1. **생성** — `CreateIssueRequest` 에 `descriptionHtml` 이 없어 생성 화면이 리치 에디터를
//    쓸 길이 없었다(Maxi 지적 1번 「이슈 생성할때는 에디터 서식이 안나옴」).
// 2. **복제** — 도메인 `Issue` 에 `descriptionHtml` 이 없으므로 `cloneIssue` 가 마크다운만
//    복사했다. 리치 에디터로 저장한 이슈는 `description` 이 NULL 이라 **복제본 본문이 빈다.**
//    마크다운 시절 이슈는 멀쩡해서 지금까지 안 드러났다.
//
// 이 저장소가 이름 붙인 「두 목록이 서로를 검사하지 않는다」의 판본이다 — 본문을 **쓰는**
// 경로 목록과 본문 두 컬럼을 **아는** 경로 목록이 서로를 몰랐다.
//
// ## 컴파일러 강제를 왜 안 쓰나 — 시도했고, 대가를 쟀다
//
// 처음에는 `IssueRepository.insert` 의 `descriptionHtml` 에 **기본값을 주지 않아** 인자
// 누락이 컴파일 에러가 되게 했다. 그것이 더 강한 처방이기 때문이다.
//
// 그런데 실측 결과 **테스트 픽스처 174곳·61파일**이 함께 컴파일 에러가 났다. 전부 「이슈를
// 하나 저장해 두고 다른 것을 검증한다」는 준비 코드라 본문 HTML 과 무관한데, 그 노이즈가
// 진짜 변경을 diff 에서 가린다. 테스트 소스셋 확장 함수로 우회해도 import 가 없는 93파일이
// 남았다. **생산 호출자는 2곳뿐인데 테스트 174곳을 고치는** 거래라 접었다.
//
// 그래서 강제를 이 판별식으로 옮겼다 — 검사 범위를 `src/main` 으로 좁히면 목록이 2줄이라
// 차집합이 정확하고, 새 생산 호출자가 인자를 빠뜨리면 pre-push 에서 red 가 난다.
//
// ## 강제하지 **않는** 것
//
// - **넘긴 값이 옳은지**는 못 본다. `insert(issue, null)` 도 문법상 통과한다. 값의 정합은
//   통합 테스트와 E2E 의 몫이다.
// - **테스트 코드**는 검사하지 않는다. 픽스처가 본문 HTML 을 안 넘기는 것은 정상이다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const BC_MAIN = path.join(REPO_ROOT, 'backend/modules/issue-tracking/src/main/kotlin');

const REPOSITORY = path.join(BC_MAIN, 'com/bts/issue/repository/IssueRepository.kt');
const SERVICE = path.join(BC_MAIN, 'com/bts/issue/application/IssueApplicationService.kt');

/**
 * `IssueRepository` 를 주입받는 이름으로 자주 쓰이는 식별자.
 *
 * ★**파일 하나·변수 하나로 좁히지 않는다.** 초안은 `IssueApplicationService.kt` 안의
 * `repo.insert(` 만 봤다. 그러면 **새 서비스가 `issueRepository.insert(...)` 로 부르는 순간
 * 판별식이 눈이 먼다** — 이 판별식이 막으려던 바로 그 상황(V039 가 경로 하나를 두고 감)이
 * 다른 파일에서 재현된다. 리뷰가 그것을 잡았다(2026-09-07).
 */
const RECEIVERS = ['repo', 'issueRepository'] as const;

/**
 * `insert` 를 가진 **다른** 리포지토리 — 오탐 대상.
 *
 * `WorkflowStatusMigrationAdapter` 도 `repo.insert(operation)` 를 부르는데 그것은
 * `IssueRepository` 가 아니다. 파일 안에 `IssueRepository` 타입 참조가 있을 때만 검사한다.
 */
const REPOSITORY_TYPE = 'IssueRepository';

/**
 * 생산 호출자 하한.
 *
 * 실측 2곳 — `createIssue` · `cloneIssue`. 하한이 없으면 파서가 눈이 멀어 「0건 대 0건」이
 * 되어 아래 단언이 공허하게 통과한다.
 */
const MIN_CALLERS = 2;

/**
 * Kotlin 소스에서 `repo.insert(...)` 호출을 찾아 **인자 개수**를 센다.
 *
 * 중첩 괄호를 세며 매칭 닫는 괄호를 찾는다 — `insert(Issue.create(...), html)` 처럼 인자
 * 안에 괄호가 있어서, 첫 `)` 로 끊으면 잘못 읽는다.
 *
 * @returns 호출마다 `{ args, snippet }`. `args` 는 최상위 콤마로 나눈 인자 수
 */
export function findInsertCalls(source: string): Array<{ args: number; snippet: string }> {
  const calls: Array<{ args: number; snippet: string }> = [];
  const marker = new RegExp(String.raw`\b(?:${RECEIVERS.join('|')})\.insert\(`, 'g');
  let match: RegExpExecArray | null;
  while ((match = marker.exec(source)) !== null) {
    const open = match.index + match[0].length - 1;
    let depth = 0;
    let end = -1;
    for (let i = open; i < source.length; i++) {
      const ch = source[i];
      if (ch === '(') depth++;
      else if (ch === ')') {
        depth--;
        if (depth === 0) {
          end = i;
          break;
        }
      }
    }
    if (end === -1) continue;
    const inner = source.slice(open + 1, end);
    // 최상위 콤마만 센다 — 중첩 괄호 안의 콤마는 인자 구분자가 아니다.
    let nested = 0;
    let commas = 0;
    for (const ch of inner) {
      if (ch === '(' || ch === '[' || ch === '<') nested++;
      else if (ch === ')' || ch === ']' || ch === '>') nested--;
      else if (ch === ',' && nested === 0) commas++;
    }
    calls.push({ args: inner.trim() === '' ? 0 : commas + 1, snippet: inner.replace(/\s+/g, ' ').slice(0, 80) });
  }
  return calls;
}

/** `src/main` 아래 Kotlin 파일을 전부 훑는다. */
function walkKotlin(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkKotlin(full, out);
    else if (entry.name.endsWith('.kt')) out.push(full);
  }
  return out;
}

/**
 * `IssueRepository` 를 실제로 쓰는 파일만 고른다.
 *
 * 다른 리포지토리도 `repo.insert(...)` 를 부르므로(예: `WorkflowStatusMigrationAdapter` 의
 * 마이그레이션 operation) 타입 참조가 없는 파일은 검사 대상이 아니다.
 */
function filesUsingIssueRepository(): Array<{ rel: string; source: string }> {
  return walkKotlin(BC_MAIN)
    .map((file) => ({ rel: path.relative(REPO_ROOT, file), source: fs.readFileSync(file, 'utf8') }))
    .filter((f) => f.source.includes(REPOSITORY_TYPE));
}

describe('이슈 삽입 경로의 본문 두 컬럼 계약', () => {
  const service = fs.readFileSync(SERVICE, 'utf8');
  const repository = fs.readFileSync(REPOSITORY, 'utf8');
  const candidates = filesUsingIssueRepository();

  test('생산 호출자를 하한 이상 찾는다 (비-공허 짝)', () => {
    const total = candidates.reduce((n, f) => n + findInsertCalls(f.source).length, 0);
    assert.ok(
      total >= MIN_CALLERS,
      `src/main 에서 IssueRepository.insert 호출을 ${total}건만 읽었다 — 파서가 눈이 멀었거나 호출이 사라졌다.`,
    );
    // 파일 탐색이 죽으면 위 합계가 0이 되므로 후보 수도 함께 못박는다.
    assert.ok(
      candidates.length >= 2,
      `IssueRepository 를 쓰는 파일을 ${candidates.length}개만 찾았다 — 탐색이 눈이 멀었다.`,
    );
  });

  test('생산 경로의 insert 호출 전량이 본문 HTML 을 명시한다', () => {
    // ★파일 하나가 아니라 `src/main` 전체를 본다. 새 서비스가 인자를 빠뜨리면 여기서 걸린다.
    const bad = candidates.flatMap((f) =>
      findInsertCalls(f.source)
        .filter((c) => c.args < 2)
        .map((c) => `${f.rel} — insert(${c.snippet}) 인자 ${c.args}개`),
    );
    assert.deepEqual(
      bad,
      [],
      '본문 HTML 을 넘기지 않는 insert 호출이 있다 — 그 경로로 만든 이슈는 리치 에디터 본문을 잃는다(복제 경로가 실제로 그랬다).',
    );
  });

  test('복제 경로가 원본 본문 HTML 을 읽는다', () => {
    // 도메인 Issue 에는 descriptionHtml 이 없다. 별도 조회 없이는 복사할 값 자체가 없다.
    assert.match(
      service,
      /repo\.findDescriptionHtml\(sourceKey\)/,
      '복제 경로가 원본 본문 HTML 을 조회하지 않는다 — 도메인 Issue 에 그 값이 없으므로 복제본 본문이 빈다.',
    );
  });

  test('본문 HTML 단건 조회가 존재한다', () => {
    assert.match(
      repository,
      /fun findDescriptionHtml\(/,
      'findDescriptionHtml 이 사라졌다 — 복제 경로가 본문을 읽을 통로가 없어진다.',
    );
  });

  test('insert 가 본문 HTML 컬럼에 실제로 쓴다', () => {
    assert.match(
      repository,
      /set\(ISSUES\.DESCRIPTION_HTML,\s*descriptionHtml/,
      'insert 가 DESCRIPTION_HTML 컬럼을 세팅하지 않는다 — 인자를 받아 놓고 버리고 있다.',
    );
  });

  test('인자 계수기가 중첩 괄호를 정확히 읽는다 (뮤테이션 짝)', () => {
    assert.deepEqual(
      findInsertCalls('val x = repo.insert(issue)').map((c) => c.args),
      [1],
      '단일 인자 호출을 잘못 셌다.',
    );
    assert.deepEqual(
      findInsertCalls('val x = repo.insert(issue, html)').map((c) => c.args),
      [2],
      '두 인자 호출을 잘못 셌다.',
    );
    // ★인자 안에 괄호와 콤마가 있는 경우 — 첫 `)` 로 끊으면 여기서 틀린다.
    assert.deepEqual(
      findInsertCalls('repo.insert(Issue.create(a = 1, b = 2), repo.findDescriptionHtml(k))').map((c) => c.args),
      [2],
      '중첩 괄호 안의 콤마를 인자 구분자로 오인했다 — 계수기가 틀리면 이 판별식 전체가 거짓을 낸다.',
    );
    assert.deepEqual(findInsertCalls('나는 insert 를 안 부른다').map((c) => c.args), [], '없는 호출을 만들어냈다.');
  });
});
