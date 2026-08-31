// 「Jira Cloud 실물 조회가 먼저」 규칙이 하네스에 배선돼 있고, 도입일 이후 문서가 실제 근거를 남기는지 강제한다
//
// ## 왜 있나
//
// 2026-08-26 실측. `type: ui|design` 계획 **104건 중 `## Jira 대조` 를 가진 것은 13건(12.5%)**,
// 아예 Jira 를 한 번도 언급하지 않은 계획이 62건이었다. 계약 §1 이 「Jira 대조가 먼저다」라고
// 이미 적고 있었는데도 그렇다 — **자연어 지시는 강제가 아니다**(`docs/rules/behavior-rules.md` §3).
// 게다가 대조를 쓴 13건 중에서도 출처 URL 을 남긴 것은 소수라, 나머지는 기억으로 쓰였다.
// 기억으로 쓴 Jira 동작은 검증할 수단이 없고, 틀려도 아무도 모른다.
//
// ## 무엇을 강제하나
//
// ① **근거 도메인 정본은 계약 §1 표 하나다.** 이 파일은 목록을 다시 적지 않고 계약을 파싱한다 —
//    사본을 두면 그것이 두 번째 목록이 되어 `[[two-lists-never-check-each-other]]` 가 된다.
// ② **도입일 이후** 새로 생긴 `docs/plans|specs/*.md` 는 `## Jira 대조` 절을 갖고,
//    그 절이 **허용 도메인 URL** 또는 **「대응 없음 + ADS 준용」 사유** 중 하나를 만족해야 한다.
// ③ 판정 함수를 **픽스처로 직접 흔든다**(뮤테이션 짝). 도입 직후에는 ②의 표본이 0건이라
//    단언이 공허하게 통과하는데, 그 구간에도 판정 로직이 살아있음을 이 짝이 증명한다.
//
// ## 강제하지 **않는** 것 — 이름을 구현보다 넓게 약속하지 않는다
//
// - **T1 UI 3줄 대조는 기계로 못 잡는다.** T1 은 plan 파일이 0개고(`bts` 티어표) 3줄은 게이트 2
//   대화에만 남는다. 저장소에 흔적이 없으므로 아래 ⑤ 는 **문구 계약**(규칙이 스킬 본문에서
//   지워지지 않았는지)까지만 본다. 실행 여부는 사람 몫이다 — 배치표 행에도 그대로 적었다.
// - **인용 내용의 진위**도 못 잡는다. 호스트가 허용 목록인지만 본다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const CONTRACT = path.join(REPO_ROOT, 'docs/design/jira-parity-contract.md');

/**
 * 규칙 발효일. 이 날짜 **이상**의 파일명 날짜를 가진 plan/spec 이 검사 대상이다.
 *
 * 등재일(2026-08-26)이 아니라 **다음 날**인 이유 — 같은 날 이미 쓰인 계획이 있었다
 * (`docs/plans/2026-08-26-debt-404-residual-six.md`). 규칙이 존재하지 않던 시점의 문서를
 * 소급해 red 로 만들면 규칙을 지킨 것과 못 지킨 것을 구별할 수 없다.
 */
const CUTOFF = '2026-08-27';

/** 근거 도메인 표가 사는 절의 제목. 스캔 구역을 여기로 한정한다. */
const RESEARCH_HEADING = '## §1. 사고 절차 — Jira Cloud 실물 조회가 먼저다';

/**
 * 규칙이 배선돼야 하는 자리와, **그 블록에만 있는** 문자열.
 *
 * 문자열을 넓게 잡으면 다른 절의 서술만으로 초록이 된다
 * (`[[invariant-satisfied-by-helptext-not-logic]]`). 각 값은 해당 파일에서 1회만 나오는
 * 제목·규칙 문장으로 고른다.
 */
const WIRING: Readonly<Record<string, readonly string[]>> = {
  'docs/design/jira-parity-contract.md': [RESEARCH_HEADING, '### T1 UI 경량 경로'],
  '.claude/skills/bts-spec/SKILL.md': ['## Jira 대조 (전 타입 필수)'],
  '.claude/skills/bts-plan/SKILL.md': ['Jira 채택 항목 ↔ task 차집합 0'],
  '.claude/skills/bts/SKILL.md': ['Jira 대응 3줄'],
};

/** 계약 §1 절만 잘라 낸다 — 다른 절의 표와 코드펜스를 스캔에서 뺀다. */
function readResearchSection(): string {
  const src = fs.readFileSync(CONTRACT, 'utf8');
  const start = src.indexOf(RESEARCH_HEADING);
  assert.ok(start >= 0, `계약에서 §1 제목을 찾지 못했다 — 제목이 바뀌었다: ${RESEARCH_HEADING}`);
  const rest = src.slice(start + RESEARCH_HEADING.length);
  const end = rest.search(/\n## /);
  return (end >= 0 ? rest.slice(0, end) : rest).replace(/```[\s\S]*?```/g, '');
}

/** 계약 §1 표의 첫 열에서 근거 도메인을 뽑는다. **이 파일에 사본을 두지 않는 이유가 여기다.** */
function allowedHosts(): Set<string> {
  const hosts = new Set<string>();
  for (const m of readResearchSection().matchAll(/^\|\s*`([a-z0-9.-]+\.[a-z]{2,})`\s*\|/gm)) {
    hosts.add(m[1] as string);
  }
  return hosts;
}

/** `docs/plans|specs` 의 파일명 날짜(`YYYY-MM-DD-slug.md`). 문서 명명 규칙이 정본이다. */
function datedDocs(dir: string): { file: string; date: string }[] {
  const abs = path.join(REPO_ROOT, dir);
  if (!fs.existsSync(abs)) return [];
  return fs
    .readdirSync(abs)
    .filter((f) => f.endsWith('.md'))
    .map((f) => ({ file: path.join(dir, f), date: (f.match(/^(\d{4}-\d{2}-\d{2})-/) ?? [])[1] ?? '' }))
    .filter((d) => d.date !== '');
}

/** 검사 대상 절의 제목. 아래 앵커의 유일한 출처다. */
const JIRA_HEADING = '## Jira 대조';

/**
 * 절 시작을 찾는 **줄 시작 앵커**.
 *
 * `indexOf` 로 찾으면 안 된다 — 본문이 절 이름을 **인용만 해도** 첫 매치가 그 인용 지점이 되고,
 * 인용문부터 다음 `\n## ` 까지가 섹션으로 오인돼 URL 이 든 진짜 절은 스캔 구역 밖으로 밀려난다.
 * 그러면 문서는 판정을 고치는 대신 **인용 표현을 바꿔 우회**하게 되고, 가드는 문서를 검사하는
 * 물건에서 문서가 피해 다니는 장애물로 바뀐다. 아래 픽스처 2건이 그 양쪽을 못박는다.
 *
 * 제목 리터럴에서 만든다 — 사본을 두면 한쪽만 바뀐다(`[[two-lists-never-check-each-other]]`).
 * 리터럴에 정규식 특수문자가 없으므로 이스케이프는 불필요하다.
 */
const JIRA_HEADING_ANCHOR = new RegExp(`^${JIRA_HEADING}`, 'm');

/**
 * `## Jira 대조` 절 판정. 위반 사유를 돌려주고, 통과면 `null`.
 *
 * 순수 함수로 뺀 이유 — 아래 뮤테이션 짝이 픽스처로 직접 흔들 수 있어야 한다.
 */
export function checkJiraSection(body: string, hosts: Set<string>): string | null {
  const heading = JIRA_HEADING_ANCHOR.exec(body);
  if (heading === null) return '`## Jira 대조` 절이 없다';

  // `### ` 하위 헤딩은 섹션을 끊지 않는다 — 대조 표 아래에 소절을 두는 문서가 있다.
  const rest = body.slice(heading.index + JIRA_HEADING.length);
  const end = rest.search(/\n## /);
  const section = end >= 0 ? rest.slice(0, end) : rest;

  // 면제 경로 — 계약 §1 5단계가 지시하는 「Jira 대응: 없음 — ADS <패턴> 준용」.
  // 「대응 없음」 네 글자만으로는 통과시키지 않는다. 준용 대상을 적게 하는 것이 이 조건의 몫이다.
  if (/대응\s*없음/.test(section) && /ADS|준용/.test(section)) return null;

  const cited = [...section.matchAll(/https?:\/\/([^/\s)\]]+)/g)].map((m) => m[1] as string);
  if (cited.length === 0) return '출처 URL 이 하나도 없다 — 기억으로 쓴 대조는 검증할 수 없다';

  const outside = cited.filter((h) => !hosts.has(h));
  if (outside.length === cited.length) {
    return `허용 도메인 근거가 없다 (인용된 호스트: ${[...new Set(outside)].join(', ')})`;
  }
  return null;
}

describe('Jira Cloud 리서치 선행 규칙', () => {
  test('계약 §1 이 근거 도메인을 표로 선언한다 (판별식 비-공허 확인)', () => {
    const hosts = allowedHosts();
    // 하한이 없으면 표가 통째로 사라져도 아래 단언이 「허용 목록이 비었으니 전부 위반」이 아니라
    // 파싱 0건으로 조용히 무너진다.
    assert.ok(
      hosts.size >= 4,
      `계약 §1 표에서 근거 도메인을 ${hosts.size}개만 파싱했다 — 표 서식이 바뀌었거나 표가 사라졌다.`,
    );
    assert.ok(
      hosts.has('support.atlassian.com'),
      'Cloud 사용자 문서(support.atlassian.com)가 근거 도메인에 없다 — 조작 동작의 1순위 근거다.',
    );
  });

  test('파일명 날짜 파서가 plan/spec 전량을 읽는다 (판별식 비-공허 확인)', () => {
    const docs = [...datedDocs('docs/plans'), ...datedDocs('docs/specs')];
    // 파서가 눈이 멀면 「대상 0건」이 되어 아래 컴플라이언스 단언이 공허하게 통과한다.
    assert.ok(
      docs.length >= 400,
      `날짜를 읽은 plan/spec 이 ${docs.length}건뿐이다 — 파일 명명 규칙이 바뀌었을 수 있다.`,
    );
  });

  test('판정 함수가 위반을 실제로 잡는다 (뮤테이션 짝)', () => {
    const hosts = new Set(['support.atlassian.com', 'atlassian.design']);

    assert.equal(checkJiraSection('## 스펙\n본문뿐\n', hosts), '`## Jira 대조` 절이 없다');
    assert.match(
      checkJiraSection('## Jira 대조\nJira 는 카드가 촘촘하다.\n', hosts) ?? '',
      /출처 URL/,
      '출처 없는 서술을 통과시켰다 — 기억으로 쓴 대조가 그대로 머지된다.',
    );
    assert.match(
      checkJiraSection('## Jira 대조\n근거 https://someblog.example/jira\n', hosts) ?? '',
      /허용 도메인/,
      '블로그를 근거로 통과시켰다 — 계약 §3 이 판정한 「스크린샷·블로그는 정본이 아니다」가 무너진다.',
    );
    assert.equal(
      checkJiraSection('## Jira 대조\n대응 없음 — ADS v2 Empty state 준용\n', hosts),
      null,
      '정당한 면제 경로를 막았다.',
    );
    assert.equal(
      checkJiraSection(
        '## Jira 대조\n| J1 | 인용 | https://support.atlassian.com/x (2026-08-26) |\n## 다음 절\n',
        hosts,
      ),
      null,
      '허용 도메인 인용을 막았다.',
    );
    // 「대응 없음」 네 글자만으로는 못 빠져나간다.
    assert.match(
      checkJiraSection('## Jira 대조\n대응 없음\n', hosts) ?? '',
      /출처 URL/,
      '사유 없는 「대응 없음」을 통과시켰다 — 면제가 만능 우회로가 된다.',
    );
  });

  // ── 헤딩 앵커 ─────────────────────────────────────────────────────────────
  //
  // `indexOf('## Jira 대조')` 는 **첫 매치**를 잡는다. 그래서 문서가 절 이름을 본문에서
  // **인용만 해도** 그 인용 지점이 절 시작으로 오인되고, 인용문부터 다음 `\n## ` 까지가
  // 섹션이 된다 — URL 이 든 진짜 절은 스캔 구역 밖으로 밀려난다.
  //
  // 실물로 밟았다. `docs/plans/2026-08-31-board-crud-recovery.md` 가 Brief 에서 절 이름을
  // 인용했다가 「출처 URL 이 하나도 없다」로 red 가 났고, 그때의 처방은 **인용 표현을 바꾸는
  // 우회**였다. 문서가 판정을 피해 문장을 고르기 시작하면 그 가드는 더 이상 문서를 검사하지
  // 않고 문서가 가드를 피한다 — 우회가 반복되면 red 는 「근거 없음」이 아니라 「표현 실수」의
  // 신호로 학습되고, 진짜 위반도 같은 방식으로 지워진다.
  test('본문이 절 이름을 인용해도 헤딩의 URL 을 찾는다', () => {
    const hosts = new Set(['support.atlassian.com', 'atlassian.design']);
    const body = [
      '## Brief',
      '착수 시 반영할 실측 — `## Jira 대조` 절 **안에** 허용 도메인 출처 URL 이 있어야 한다.',
      '',
      '## Jira 대조',
      '| J1 | 보드는 프로젝트당 N개 | https://support.atlassian.com/x | 2026-08-25 |',
      '',
      '## 다음 절',
      '',
    ].join('\n');

    assert.equal(
      checkJiraSection(body, hosts),
      null,
      '절 이름을 인용했다는 이유로 진짜 헤딩을 못 봤다 — 문서가 표현을 바꿔 우회하게 된다.',
    );
  });

  // 회귀 앵커. 위 픽스처만 있으면 「본문 전체에서 URL 을 찾는다」는 **틀린 처방**으로도 초록이
  // 되는데, 그러면 `## Jira 대조` 절이 아예 없는 문서가 무관한 자리의 Atlassian 링크 하나로
  // 통과한다. 그래서 이 픽스처는 허용 도메인 URL 을 **절 밖에** 일부러 둔다 — 처방이
  // 「줄 시작 헤딩」으로 좁혀졌을 때만 통과한다.
  test('헤딩이 아예 없고 인용만 있으면 여전히 실패한다', () => {
    const hosts = new Set(['support.atlassian.com', 'atlassian.design']);
    const body = [
      '## Brief',
      '이 문서는 `## Jira 대조` 절 규칙을 설명만 한다.',
      '참고 https://support.atlassian.com/x — 절 밖의 인용이다.',
      '',
      '## 다음 절',
      '본문뿐',
      '',
    ].join('\n');

    assert.equal(
      checkJiraSection(body, hosts),
      '`## Jira 대조` 절이 없다',
      '절이 없는 문서를 인용문 + 절 밖 URL 만으로 통과시켰다 — 규칙이 통째로 우회된다.',
    );
  });

  test(`도입일(${CUTOFF}) 이후 plan/spec 이 Jira 대조 근거를 남긴다`, () => {
    const hosts = allowedHosts();
    const targets = [...datedDocs('docs/plans'), ...datedDocs('docs/specs')].filter(
      (d) => d.date >= CUTOFF,
    );

    const violations = targets
      .map((d) => ({ d, why: checkJiraSection(fs.readFileSync(path.join(REPO_ROOT, d.file), 'utf8'), hosts) }))
      .filter((v) => v.why !== null)
      .map((v) => `${v.d.file}: ${v.why}`);

    assert.deepEqual(
      violations,
      [],
      `Jira 대조 근거가 없는 문서:\n  ${violations.join('\n  ')}\n` +
        `계약 §1 절차대로 실물 조회 후 표를 채우거나, 대응 개념이 없으면 ` +
        `「대응 없음 — ADS <패턴> 준용」 사유를 적어라. 도입일 이전 문서는 대상이 아니다.`,
    );
  });

  test('규칙이 하네스 4곳에 배선돼 있다 (문구 계약)', () => {
    const missing: string[] = [];
    for (const [rel, phrases] of Object.entries(WIRING)) {
      const abs = path.join(REPO_ROOT, rel);
      if (!fs.existsSync(abs)) {
        missing.push(`${rel}: 파일 없음`);
        continue;
      }
      const src = fs.readFileSync(abs, 'utf8');
      for (const p of phrases) if (!src.includes(p)) missing.push(`${rel}: "${p}"`);
    }

    assert.deepEqual(
      missing,
      [],
      `규칙 배선이 사라진 자리:\n  ${missing.join('\n  ')}\n` +
        '리서치 절차 · 전 타입 필수 · plan 교차검사 · T1 3줄 중 하나라도 지워지면 ' +
        '그 경로는 다시 기억으로 쓰이게 된다.',
    );
  });
});
