import { GrabWaitEstimatorService } from './grab-wait-estimator.service';

describe('GrabWaitEstimatorService', () => {
  it('returns null when session or queue rank is missing', () => {
    const service = new GrabWaitEstimatorService();

    expect(service.estimateWaitSeconds(null, 5)).toBeNull();
    expect(service.estimateWaitSeconds(101, null)).toBeNull();
    expect(service.estimateWaitSeconds(101, 0)).toBeNull();
    expect(service.estimateWaitSeconds(101, -1)).toBeNull();
  });

  it('falls back to the default cost when no sample exists', () => {
    const service = new GrabWaitEstimatorService();

    // 默认成本：500ms 轮询 + 1000ms 处理 = 1.5s/人
    expect(service.estimateWaitSeconds(101, 2)).toBe(3);
  });

  it('uses the recorded EWMA cost once a sample exists', () => {
    const service = new GrabWaitEstimatorService();
    service.recordProcessingMs(101, 1500);
    // 500 + 1500 = 2000ms/人
    expect(service.estimateWaitSeconds(101, 3)).toBe(6);
  });

  it('smooths processing cost with EWMA across samples', () => {
    const service = new GrabWaitEstimatorService();
    service.recordProcessingMs(101, 1000);
    service.recordProcessingMs(101, 2000);
    // EWMA = 1000*0.7 + 2000*0.3 = 1300 → 500+1300 = 1800ms/人 → rank 5 = 9s
    expect(service.estimateWaitSeconds(101, 5)).toBe(9);
  });

  it('ignores invalid samples', () => {
    const service = new GrabWaitEstimatorService();
    service.recordProcessingMs(101, 0);
    service.recordProcessingMs(101, Number.NaN);
    service.recordProcessingMs(Number.NaN, 100);

    expect(service.estimateWaitSeconds(101, 2)).toBe(3);
  });

  it('keeps sessions isolated', () => {
    const service = new GrabWaitEstimatorService();
    service.recordProcessingMs(101, 1500);

    expect(service.estimateWaitSeconds(102, 2)).toBe(3);
    expect(service.estimateWaitSeconds(101, 2)).toBe(4);
  });

  it('always returns a positive integer estimate', () => {
    const service = new GrabWaitEstimatorService();
    service.recordProcessingMs(101, 1);

    const estimate = service.estimateWaitSeconds(101, 1);
    expect(estimate).toBeGreaterThanOrEqual(1);
    expect(Number.isInteger(estimate ?? 0)).toBe(true);
  });
});
