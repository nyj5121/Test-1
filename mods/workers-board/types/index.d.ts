export type Status = 'running' | 'waiting' | 'failed' | 'done'

export type Worker = {
  id: string
  task: string
  model: string
  startedAt: number
  endedAt: number | null
  status: Status
  last: string
}

declare module 'claude-code' {
  interface PluginState {
    'workers-board': { workers: Worker[]; now: number; isBand: boolean }
  }
}
