import { Logger, UnauthorizedException } from '@nestjs/common';
import {
  ConnectedSocket,
  MessageBody,
  OnGatewayConnection,
  SubscribeMessage,
  WebSocketGateway,
  WebSocketServer,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import * as jwt from 'jsonwebtoken';
import { GrabService } from './grab.service';
import type { GrabProgressResponse } from './grab.types';

interface JwtPayload {
  userId?: number | string;
  sub?: string;
}

interface SubscribePayload {
  requestId?: unknown;
}

export interface GrabProgressSnapshotEvent {
  event: string;
  data: GrabProgressResponse | null;
}

@WebSocketGateway({
  namespace: '/grab-progress',
  path: '/api/grab/socket.io',
  cors: { origin: true, credentials: false },
})
export class GrabProgressGateway implements OnGatewayConnection {
  private readonly logger = new Logger(GrabProgressGateway.name);

  @WebSocketServer()
  private readonly server?: Server;

  constructor(private readonly grabService: GrabService) {}

  async handleConnection(client: Socket): Promise<void> {
    try {
      const payload = this.verifyToken(this.extractToken(client));
      client.data.userId = Number(payload.userId ?? payload.sub);
    } catch {
      client.disconnect(true);
    }
  }

  @SubscribeMessage('subscribe')
  async subscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() body: SubscribePayload,
  ): Promise<GrabProgressSnapshotEvent> {
    const requestId = typeof body?.requestId === 'string' ? body.requestId : '';
    const userId = Number(client.data?.userId);
    if (!requestId || !Number.isInteger(userId) || userId <= 0) {
      return { event: 'error', data: null };
    }

    let progress: GrabProgressResponse;
    try {
      progress = await this.grabService.getProgress(userId, requestId);
    } catch {
      return { event: 'error', data: null };
    }

    await client.join(this.requestRoom(requestId));
    if (Number.isInteger(progress.sessionId) && progress.sessionId > 0) {
      await client.join(this.sessionRoom(progress.sessionId));
    }
    return { event: 'subscribed', data: progress };
  }

  @SubscribeMessage('unsubscribe')
  async unsubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() body: SubscribePayload,
  ): Promise<GrabProgressSnapshotEvent> {
    const requestId = typeof body?.requestId === 'string' ? body.requestId : '';
    if (requestId) {
      await client.leave(this.requestRoom(requestId));
    }
    return { event: 'unsubscribed', data: null };
  }

  async publishProgress(requestId: string): Promise<void> {
    if (!this.server || !requestId) return;
    try {
      const progress = await this.grabService.getProgressForVerifiedRequest(requestId);
      this.server.to(this.requestRoom(requestId)).emit('progress', progress);
    } catch {
      this.logger.debug(`推送抢票进度失败: requestId=${requestId}`);
    }
  }

  publishQueueAdvanced(sessionId: number): void {
    if (!this.server || !Number.isInteger(sessionId) || sessionId <= 0) return;
    this.server.to(this.sessionRoom(sessionId)).emit('queue-advanced', { sessionId });
  }

  private requestRoom(requestId: string): string {
    return `grab-request:${requestId}`;
  }

  private sessionRoom(sessionId: number): string {
    return `grab-session:${sessionId}`;
  }

  private extractToken(client: Socket): string | null {
    const auth = client.handshake?.auth as Record<string, unknown> | undefined;
    if (typeof auth?.token === 'string' && auth.token.trim()) return auth.token.trim();

    const authorization = client.handshake?.headers?.authorization;
    if (typeof authorization === 'string' && authorization.startsWith('Bearer ')) {
      return authorization.slice('Bearer '.length);
    }

    const query = client.handshake?.query as Record<string, unknown> | undefined;
    if (typeof query?.token === 'string' && query.token.trim()) return query.token.trim();

    return null;
  }

  private verifyToken(token: string | null): JwtPayload {
    if (!token) throw new UnauthorizedException('未登录');
    const jwtSecret = process.env.JWT_SECRET?.trim();
    if (!jwtSecret) throw new UnauthorizedException('JWT 未配置');
    const payload = jwt.verify(token, jwtSecret) as JwtPayload;
    const userId = Number(payload.userId ?? payload.sub);
    if (!Number.isInteger(userId) || userId <= 0) throw new UnauthorizedException('登录状态无效');
    return payload;
  }
}
