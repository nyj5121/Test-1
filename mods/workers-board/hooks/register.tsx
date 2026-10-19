import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Status, Worker } from '../types'

const PANE = 'workers'
const TICK_MS = 15_000

const workers = atom({ plugin: 'workers-board', key: 'workers' } as const, [])
const now = atom({ plugin: 'workers-board', key: 'now' } as const, 0)
const isBand = atom({ plugin: 'workers-board', key: 'isBand' } as const, false)

const LABEL: Record<Status, string> = {
  running: '진행 중',
  waiting: '허락 대기',
  failed: '실패',
  done: '끝남',
}
const COLOR: Record<Status, string> = {
  running: 'cyan',
  waiting: 'yellow',
  failed: 'red',
  done: 'gray',
}

const minutes = (ms: number) => {
  const m = Math.floor(ms / 60_000)
  return m < 1 ? '1분 미만' : `${m}분째`
}

const patch = (id: string, change: Partial<Worker>) => (list: Worker[]) =>
  list.map(one => (one.id === id ? { ...one, ...change } : one))

const detail = (e: { tool: string; [key: string]: unknown }) => {
  const target = e.file_path ?? e.path ?? e.pattern ?? e.command
  return typeof target === 'string' ? `${e.tool} · ${target.split('/').pop()?.slice(0, 40)}` : e.tool
}

const open = async ($: EngineInterface) => {
  const { isPlaced } = await $.ui.open({ id: PANE, title: 'WORKERS' })
  await update($, isBand, () => !isPlaced)
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    await $.command.register({ name: 'workers', description: 'WORKERS 패널을 다시 엽니다' })
    await update($, now, () => 0)
    $.clock.every(TICK_MS, async () => update($, now, () => Date.now()))
    await open($)

    return next(e)
  })

  on('command.run', { command: 'workers' }, async $ => {
    await open($)

    return { text: 'WORKERS 패널을 열었어요.' }
  })

  on('agent.spawn', async ($, e, next) => {
    const started = await next(e)
    const id = 'agentId' in started ? started.agentId : undefined

    if (id !== undefined) {
      const startedAt = await $.clock.now()
      const worker: Worker = {
        id,
        task: e.description.slice(0, 40),
        model: started.model ?? '알 수 없음',
        startedAt,
        endedAt: null,
        status: 'running',
        last: '시작함',
      }
      await update($, workers, list => [...list, worker].slice(-30))
      await update($, now, () => startedAt)
    }

    return started
  })

  // 서브에이전트가 허락을 물어야 하는 도구면 "허락 대기"로 표시합니다.
  on('tool.check', async ($, e, next) => {
    const verdict = await next(e)

    if (e.agentId !== undefined && verdict.decision === 'ask') {
      await update($, workers, patch(e.agentId, { status: 'waiting' }))
    }

    return verdict
  })

  on('tool.call', async ($, e, next) => {
    const id = e.agentId

    if (id !== undefined) {
      await update($, workers, patch(id, { last: detail(e) }))
    }

    const ran = await next(e)

    if (id !== undefined) {
      await update($, workers, list =>
        list.map(one =>
          one.id === id && one.status === 'waiting' ? { ...one, status: 'running' } : one,
        ),
      )
    }

    return ran
  })

  on('turn.complete', async ($, e, next) => {
    if (e.agentId !== undefined) {
      const failed = e.reason === 'error' || e.reason === 'refusal'
      await update(
        $,
        workers,
        patch(e.agentId, {
          status: failed ? 'failed' : 'done',
          endedAt: await $.clock.now(),
        }),
      )
    }

    return next(e)
  })

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Text } = $.ui.resolve(e)
    const list = await read($, workers)
    const clock = Math.max(await read($, now), Date.now())
    const running = list.filter(one => one.status === 'running').length
    const waiting = list.filter(one => one.status === 'waiting').length

    return (
      <Box flexDirection="column">
        <Text bold>
          {running}개 진행 중 · {waiting}개 대기
        </Text>
        {list.length === 0 && <Text dimColor>아직 서브에이전트가 없어요.</Text>}
        {list.map(one => (
          <Box
            key={one.id}
            flexDirection="column"
            borderStyle="round"
            borderColor={COLOR[one.status]}
          >
            <Text color={COLOR[one.status]} bold={one.status === 'waiting'}>
              [{LABEL[one.status]}] {one.task}
            </Text>
            <Text dimColor>
              {one.model} · {minutes((one.endedAt ?? clock) - one.startedAt)}
            </Text>
            <Text dimColor>↳ {one.last}</Text>
          </Box>
        ))}
      </Box>
    )
  })

  // 화면이 좁아 패널이 안 열리면 입력창 위에 작은 박스로 보여줍니다.
  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const list = await read($, workers)

    if (e.props.hasSurvey || !(await read($, isBand)) || list.length === 0) {
      return next(e)
    }

    const { Box, Text } = $.ui.resolve(e)
    const running = list.filter(one => one.status === 'running').length
    const waiting = list.filter(one => one.status === 'waiting').length

    return (
      <Box>
        <Text color={waiting > 0 ? 'yellow' : 'cyan'}>
          WORKERS {running}개 진행 중 · {waiting}개 대기
        </Text>
      </Box>
    )
  })
}
