import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { WaitlistAllocatorService } from './waitlist-allocator.service';

const DEFAULT_SCAN_INTERVAL_MS = 60_000;

/**
 * 候补 offer 过期扫描定时器：
 * 定时调用候补分配器的过期扫描，把超过支付时限的候补 offer 标记过期并通知用户，
 * 不再依赖手动调用 internal/offers/expire-scan 接口。
 */
@Injectable()
export class WaitlistOfferExpiryScheduler implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(WaitlistOfferExpiryScheduler.name);
  private timer: NodeJS.Timeout | null = null;
  private readonly scanIntervalMs: number;

  constructor(private readonly allocator: WaitlistAllocatorService) {
    const configured = Number(process.env.WAITLIST_OFFER_EXPIRY_SCAN_INTERVAL_MS || DEFAULT_SCAN_INTERVAL_MS);
    this.scanIntervalMs = Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_SCAN_INTERVAL_MS;
  }

  onModuleInit(): void {
    this.timer = setInterval(() => {
      void this.sweepExpiredOffers().catch((error) => this.logger.error(error));
    }, this.scanIntervalMs);
  }

  onModuleDestroy(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  async sweepExpiredOffers(): Promise<{ scanned: number }> {
    const result = await this.allocator.scanExpiredOffers();
    if (result.scanned > 0) {
      this.logger.log(`候补 offer 过期扫描完成: scanned=${result.scanned}`);
    }
    return result;
  }
}
