import { WaitlistOfferExpiryScheduler } from './waitlist-offer-expiry.scheduler';
import { WaitlistAllocatorService } from './waitlist-allocator.service';

describe('WaitlistOfferExpiryScheduler', () => {
  const originalEnv = process.env;

  afterEach(() => {
    process.env = originalEnv;
    jest.restoreAllMocks();
  });

  it('sweeps expired offers through the allocator on demand', async () => {
    const allocator = { scanExpiredOffers: jest.fn().mockResolvedValue({ scanned: 2 }) };
    const scheduler = new WaitlistOfferExpiryScheduler(allocator as unknown as WaitlistAllocatorService);

    const result = await scheduler.sweepExpiredOffers();

    expect(result).toEqual({ scanned: 2 });
    expect(allocator.scanExpiredOffers).toHaveBeenCalledTimes(1);
  });

  it('sweeps silently when no expired offers exist', async () => {
    const allocator = { scanExpiredOffers: jest.fn().mockResolvedValue({ scanned: 0 }) };
    const scheduler = new WaitlistOfferExpiryScheduler(allocator as unknown as WaitlistAllocatorService);

    const result = await scheduler.sweepExpiredOffers();

    expect(result).toEqual({ scanned: 0 });
    expect(allocator.scanExpiredOffers).toHaveBeenCalledTimes(1);
  });

  it('registers and clears an interval timer across lifecycle', () => {
    jest.useFakeTimers();
    try {
      const allocator = { scanExpiredOffers: jest.fn().mockResolvedValue({ scanned: 0 }) };
      const scheduler = new WaitlistOfferExpiryScheduler(allocator as unknown as WaitlistAllocatorService);

      scheduler.onModuleInit();
      jest.advanceTimersByTime(61_000);
      expect(allocator.scanExpiredOffers.mock.calls.length).toBeGreaterThanOrEqual(1);

      scheduler.onModuleDestroy();
      const callsAfterStop = allocator.scanExpiredOffers.mock.calls.length;
      jest.advanceTimersByTime(120_000);
      expect(allocator.scanExpiredOffers.mock.calls.length).toBe(callsAfterStop);
    } finally {
      jest.useRealTimers();
    }
  });

  it('swallows allocator failures so the timer keeps running', async () => {
    jest.useFakeTimers();
    try {
      const allocator = {
        scanExpiredOffers: jest.fn()
          .mockRejectedValueOnce(new Error('扫描失败'))
          .mockResolvedValue({ scanned: 1 }),
      };
      const scheduler = new WaitlistOfferExpiryScheduler(allocator as unknown as WaitlistAllocatorService);

      scheduler.onModuleInit();
      jest.advanceTimersByTime(61_000);
      await Promise.resolve();

      expect(allocator.scanExpiredOffers).toHaveBeenCalledTimes(1);

      jest.advanceTimersByTime(61_000);
      await Promise.resolve();
      expect(allocator.scanExpiredOffers).toHaveBeenCalledTimes(2);
    } finally {
      jest.useRealTimers();
    }
  });

  it('falls back to the default interval when the configured value is invalid', () => {
    process.env = { ...originalEnv, WAITLIST_OFFER_EXPIRY_SCAN_INTERVAL_MS: '-5' };
    const allocator = { scanExpiredOffers: jest.fn() };
    const scheduler = new WaitlistOfferExpiryScheduler(allocator as unknown as WaitlistAllocatorService);

    expect((scheduler as unknown as { scanIntervalMs: number }).scanIntervalMs).toBe(60_000);
  });
});
