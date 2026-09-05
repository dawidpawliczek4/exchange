import { describe, expect, it } from 'vitest'

import { createBarStream, toBar, type CandleMessage } from './candles.ts'

function message(overrides: Partial<CandleMessage> = {}): CandleMessage {
  return {
    intervalSeconds: 5,
    bucketStart: 1_756_900_000_000,
    open: 10_000,
    high: 10_050,
    low: 9_950,
    close: 10_020,
    volume: 1_234,
    tradeCount: 7,
    lastSeq: 42,
    ...overrides,
  }
}

describe('toBar', () => {
  it('converts the bucket start from milliseconds to seconds', () => {
    expect(toBar(message())?.time).toBe(1_756_900_000)
  })

  it('floors sub-second precision instead of rounding up', () => {
    expect(toBar(message({ bucketStart: 1_756_900_000_999 }))?.time).toBe(1_756_900_000)
  })

  it('carries the OHLC values through unchanged', () => {
    expect(toBar(message())).toEqual({
      time: 1_756_900_000,
      open: 10_000,
      high: 10_050,
      low: 9_950,
      close: 10_020,
    })
  })

  it('rejects a bucket that closed without a trade', () => {
    expect(toBar(message({ open: null, high: null, low: null, close: null }))).toBeNull()
  })
})

describe('createBarStream', () => {
  it('rejects a bar older than the last one, which update() would throw on', () => {
    const next = createBarStream()

    expect(next(message({ bucketStart: 1_756_900_010_000 }))).not.toBeNull()
    expect(next(message({ bucketStart: 1_756_900_005_000 }))).toBeNull()
  })

  it('accepts a repeat of the current bucket, since update() upserts the last bar', () => {
    const next = createBarStream()
    const bucketStart = 1_756_900_010_000

    expect(next(message({ bucketStart }))).not.toBeNull()
    expect(next(message({ bucketStart, close: 10_100 }))?.close).toBe(10_100)
  })

  it('does not advance its watermark on a rejected bucket', () => {
    const next = createBarStream()

    expect(next(message({ bucketStart: 1_756_900_010_000, open: null }))).toBeNull()
    expect(next(message({ bucketStart: 1_756_900_005_000 }))).not.toBeNull()
  })
})
