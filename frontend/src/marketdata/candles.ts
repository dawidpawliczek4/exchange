import type { UTCTimestamp } from 'lightweight-charts'

export interface CandleMessage {
  intervalSeconds: number
  bucketStart: number
  open: number | null
  high: number | null
  low: number | null
  close: number | null
  volume: number
  tradeCount: number
  lastSeq: number | null
}

export interface Bar {
  time: UTCTimestamp
  open: number
  high: number
  low: number
  close: number
}

export function toBar(message: CandleMessage): Bar | null {
  const { open, high, low, close } = message
  if (open === null || high === null || low === null || close === null) return null

  return {
    time: Math.floor(message.bucketStart / 1000) as UTCTimestamp,
    open,
    high,
    low,
    close,
  }
}

export function createBarStream(): (message: CandleMessage) => Bar | null {
  let lastTime = 0

  return (message) => {
    const bar = toBar(message)
    if (bar === null || bar.time < lastTime) return null

    lastTime = bar.time
    return bar
  }
}
