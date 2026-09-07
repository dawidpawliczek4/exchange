import { describe, expect, it } from 'vitest'

import {
  applyTrade,
  isTrade,
  toBar,
  type Bar,
  type CandleHistoryItem,
  type TradeFrame,
} from './candles.ts'

const BUCKET = 1_756_900_000_000

function candle(overrides: Partial<CandleHistoryItem> = {}): CandleHistoryItem {
  return {
    bucketStart: BUCKET,
    open: 10_000,
    high: 10_050,
    low: 9_950,
    close: 10_020,
    volume: 12,
    quoteVolume: 120_000,
    tradeCount: 7,
    ...overrides,
  }
}

function trade(timestamp: number, price: number): TradeFrame {
  return {
    seq: 1,
    timestamp,
    trade: { makerId: 1, makerUserId: 1, takerId: 2, takerUserId: 2, price, quantity: 1 },
  }
}

describe('toBar', () => {
  it('converts the bucket start from milliseconds to seconds', () => {
    expect(toBar(candle()).time).toBe(1_756_900_000)
  })

  it('carries the OHLC values through unchanged', () => {
    expect(toBar(candle())).toEqual({
      time: 1_756_900_000,
      open: 10_000,
      high: 10_050,
      low: 9_950,
      close: 10_020,
    })
  })
})

describe('isTrade', () => {
  it('tells a trade frame from a cancel frame on the same socket', () => {
    expect(isTrade(trade(BUCKET, 1))).toBe(true)
    expect(isTrade({ seq: 1, timestamp: BUCKET, userId: 1, orderId: 1, status: 'CANCELED' })).toBe(
      false,
    )
  })
})

describe('applyTrade', () => {
  const open: Bar = {
    time: 1_756_900_000 as Bar['time'],
    open: 100,
    high: 100,
    low: 100,
    close: 100,
  }

  it('opens a bar aligned to the epoch grid when there is none', () => {
    expect(applyTrade(undefined, trade(BUCKET + 3_210, 100))).toEqual(open)
  })

  it('extends the open bar with a trade in the same bucket', () => {
    expect(applyTrade(open, trade(BUCKET + 4_999, 90))).toEqual({
      ...open,
      low: 90,
      close: 90,
    })
    expect(applyTrade(open, trade(BUCKET + 4_999, 120))).toEqual({
      ...open,
      high: 120,
      close: 120,
    })
  })

  it('opens a fresh bar when the trade crosses the bucket boundary', () => {
    expect(applyTrade(open, trade(BUCKET + 5_000, 130))).toEqual({
      time: 1_756_900_005,
      open: 130,
      high: 130,
      low: 130,
      close: 130,
    })
  })

  it('ignores a trade older than the open bar, which update() would throw on', () => {
    expect(applyTrade(open, trade(BUCKET - 1, 50))).toBe(open)
  })
})
