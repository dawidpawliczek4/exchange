import type { UTCTimestamp } from 'lightweight-charts'

export const INTERVAL_MS = 5_000

export interface CandleHistoryItem {
  bucketStart: number
  open: number
  high: number
  low: number
  close: number
  volume: number
  quoteVolume: number
  tradeCount: number
}

export interface TradeFrame {
  seq: number
  timestamp: number
  trade: {
    makerId: number
    makerUserId: number
    takerId: number
    takerUserId: number
    price: number
    quantity: number
  }
}

export interface CancelFrame {
  seq: number
  timestamp: number
  userId: number
  orderId: number
  status: string
}

export type MarketFrame = TradeFrame | CancelFrame

export interface Bar {
  time: UTCTimestamp
  open: number
  high: number
  low: number
  close: number
}

export function isTrade(frame: MarketFrame): frame is TradeFrame {
  return 'trade' in frame
}

export function toBar(candle: CandleHistoryItem): Bar {
  return {
    time: Math.floor(candle.bucketStart / 1000) as UTCTimestamp,
    open: candle.open,
    high: candle.high,
    low: candle.low,
    close: candle.close,
  }
}

export function applyTrade(
  current: Bar | undefined,
  frame: TradeFrame,
  intervalMs = INTERVAL_MS,
): Bar {
  const time = ((Math.floor(frame.timestamp / intervalMs) * intervalMs) / 1000) as UTCTimestamp
  const price = frame.trade.price

  if (current !== undefined && time < current.time) return current

  if (current !== undefined && time === current.time) {
    return {
      ...current,
      high: Math.max(current.high, price),
      low: Math.min(current.low, price),
      close: price,
    }
  }

  return { time, open: price, high: price, low: price, close: price }
}
