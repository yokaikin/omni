import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { DatabaseModule } from '../database/database.module';
import { GrabAdmissionService } from './grab-admission.service';
import { GrabCompensationService } from './grab-compensation.service';
import { GrabController, GrabInternalController, GrabSessionController } from './grab.controller';
import { GrabOpsController } from './grab-ops.controller';
import { GrabOpsService } from './grab-ops.service';
import { GrabProgressGateway } from './grab-progress.gateway';
import { GrabQueueService } from './grab-queue.service';
import { GrabRepository } from './grab.repository';
import { GrabService } from './grab.service';
import { GrabStockService } from './grab-stock.service';
import { GrabWaitEstimatorService } from './grab-wait-estimator.service';
import { GrabWorkerService } from './grab-worker.service';
import { OrderClientService } from './order-client.service';
import { RedisService } from './redis.service';
import { TicketClientService } from './ticket-client.service';
import { VisibleStockService } from './visible-stock.service';

@Module({
  imports: [AuthModule, DatabaseModule],
  controllers: [GrabController, GrabInternalController, GrabSessionController, GrabOpsController],
  providers: [
    GrabService,
    GrabProgressGateway,
    GrabRepository,
    GrabAdmissionService,
    GrabCompensationService,
    GrabOpsService,
    GrabQueueService,
    GrabWaitEstimatorService,
    GrabWorkerService,
    GrabStockService,
    OrderClientService,
    RedisService,
    TicketClientService,
    VisibleStockService,
  ],
  exports: [GrabService],
})
export class GrabModule {}
