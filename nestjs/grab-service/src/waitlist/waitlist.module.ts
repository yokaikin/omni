import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { DatabaseModule } from '../database/database.module';
import { GrabModule } from '../grab/grab.module';
import { OrderClientService } from '../grab/order-client.service';
import { TicketClientService } from '../grab/ticket-client.service';
import { VisibleStockService } from '../grab/visible-stock.service';
import { GrabStockService } from '../grab/grab-stock.service';
import { RedisService } from '../grab/redis.service';
import { WaitlistController } from './waitlist.controller';
import { WaitlistAllocatorService } from './waitlist-allocator.service';
import { WaitlistMqConsumer } from './waitlist-mq.consumer';
import { WaitlistNotificationService } from './waitlist-notification.service';
import { WaitlistOfferExpiryScheduler } from './waitlist-offer-expiry.scheduler';
import { WaitlistRepository } from './waitlist.repository';
import { WaitlistService } from './waitlist.service';

@Module({
  imports: [AuthModule, DatabaseModule, GrabModule],
  controllers: [WaitlistController],
  providers: [
    WaitlistService,
    WaitlistRepository,
    WaitlistAllocatorService,
    WaitlistNotificationService,
    OrderClientService,
    TicketClientService,
    VisibleStockService,
    GrabStockService,
    RedisService,
    WaitlistMqConsumer,
    WaitlistOfferExpiryScheduler,
  ],
})
export class WaitlistModule {}
