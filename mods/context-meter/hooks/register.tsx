import { atom, read, update } from 'claude-code'
import type { Register } from 'claude-code'

import type { Reading } from '../types'

// 바꾸고 싶은 값은 여기서만 고치면 됩니다 (토큰 수).
const YELLOW_AT = 150_000
const RED_AT = 200_000
const BAR_WIDTH = 20

const reading = atom({ plugin: 'context-meter', key: 'reading' } as const, null)
const hasWarned = atom({ plugin: 'context-meter', key: 'hasWarned' } as const, false)

const colorOf = (tokens: number) =>
  tokens >= RED_AT ? 'red' : tokens >= YELLOW_AT ? 'yellow' : 'green'

const bar = (percent: number) => {
  const filled = Math.min(BAR_WIDTH, Math.round((percent / 100) * BAR_WIDTH))
  return '█'.repeat(filled) + '░'.repeat(BAR_WIDTH - filled)
}

const k = (tokens: number) => `${Math.round(tokens / 1000)}K`

export const register: Register = on => {
  on('session.measure', async ($, e, next) => {
    const tokens = e.context.tokens ?? null
    const next_: Reading = {
      tokens,
      window: e.context.window,
      percent: e.context.percent ?? null,
      limits: e.rateLimits.map(one => ({ kind: one.kind, percent: one.percentUsed })),
    }
    await update($, reading, () => next_)

    if (tokens !== null && tokens >= RED_AT) {
      if (!(await read($, hasWarned))) {
        await update($, hasWarned, () => true)
        $.ui.toast('컨텍스트가 가득 찼어요. 인수인계 메모 남기고 /clear 하세요')
      }
    } else {
      await update($, hasWarned, () => false)
    }

    return next(e)
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const one = await read($, reading)

    if (e.props.hasSurvey || one === null || one.tokens === null || one.percent === null) {
      return next(e)
    }

    const { Box, Text } = $.ui.resolve(e)
    const limit = one.limits.map(l => `${l.kind} ${Math.round(l.percent)}%`).join(' · ')

    return (
      <Box>
        <Text color={colorOf(one.tokens)}>
          {bar(one.percent)} {one.percent}% · {k(one.tokens)}/{k(one.window)}
        </Text>
        {limit !== '' && <Text dimColor> · 한도 {limit}</Text>}
      </Box>
    )
  })
}
