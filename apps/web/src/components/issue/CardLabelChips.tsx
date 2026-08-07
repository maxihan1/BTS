// 보드/백로그 카드의 라벨 칩 행 — 최대 3개 + "+N" 오버플로 (FR-UX-14 F14 Task 2)
import type { JSX } from 'react'
import { Badge } from '@/components/ui/badge'
import { cardLabels } from '@/i18n/card-labels'

/** 접히지 않고 그대로 노출되는 라벨 최대 개수 */
const MAX_VISIBLE_LABELS = 3

interface CardLabelChipsProps {
  /** 이슈에 붙은 라벨 이름 목록 */
  labels: readonly string[]
}

/**
 * 보드·백로그 카드가 **공유**하는 라벨 칩 행.
 *
 * 라벨이 4개 이상이면 앞 3개만 칩으로 보여주고 나머지는 "+N" 오버플로 칩 하나로
 * 접는다. 오버플로 칩의 `aria-label`에는 숨은 라벨 전문이 담겨야 한다 — `title`은
 * 터치 화면에서 뜨지 않기 때문이다 (`i18n/card-labels.ts` D6 참고).
 *
 * @param labels 이슈에 붙은 라벨 이름 목록 (빈 배열이면 아무것도 렌더하지 않는다)
 */
export function CardLabelChips({ labels }: CardLabelChipsProps): JSX.Element | null {
  if (labels.length === 0) return null // FR9 — 빈 div도 만들지 않는다

  const visible = labels.slice(0, MAX_VISIBLE_LABELS)
  const hidden = labels.slice(MAX_VISIBLE_LABELS)

  return (
    <div className="flex flex-wrap items-center gap-1">
      {visible.map((label) => (
        <Badge key={label} variant="neutral" className="max-w-[10rem] text-muted-foreground">
          {/*
            ★ truncate 를 Badge 가 아니라 **안쪽 span** 에 둔다.
            Badge 는 `inline-flex … justify-center` 인데, flex 부모의 아이템이 되면 flex 로
            blockify 되고 `text-overflow: ellipsis` 는 **block container 에만** 적용돼 무효가
            된다. 게다가 `justify-center` 때문에 넘친 글자가 좌·우로 균등하게 밀려나 라벨의
            **가운데 토막만** 남는다(Chromium 실측 — 오프셋 ±105px · scrollWidth 251/clientWidth 158).
            `min-w-0` 이 있어야 flex 아이템이 내용 크기 아래로 줄어든다.
          */}
          <span className="min-w-0 truncate">{label}</span>
        </Badge>
      ))}
      {hidden.length > 0 && (
        <Badge
          variant="neutral"
          className="text-muted-foreground"
          // ★ role="img" 가 없으면 aria-label 이 무효다 — 맨 span 은 role=generic 이고
          // ARIA 에서 generic 은 **name-prohibited** 라 스크린리더가 "+2" 만 읽는다.
          // 그러면 「title 은 터치에서 안 뜨니 접근성 이름으로 준다」는 D6 근거가 무너진다.
          // IssueTypeIcon.tsx:50 이 같은 이유로 role="img" 를 쓴다.
          role="img"
          aria-label={cardLabels.moreLabelsAriaLabel(hidden)} // D6 — 터치엔 hover가 없다
          title={hidden.join(', ')} // 마우스 보조 (주 경로 아님)
        >
          {`+${hidden.length}`}
        </Badge>
      )}
    </div>
  )
}
