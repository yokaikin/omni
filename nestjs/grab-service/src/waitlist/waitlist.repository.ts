import { Injectable } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';
import {
  WAITLIST_ENTRY_STATUS,
  WAITLIST_OFFER_STATUS,
  WaitlistEntryRecord,
  WaitlistOfferRecord,
} from './waitlist.types';

@Injectable()
export class WaitlistRepository {
  constructor(private readonly database: DatabaseService) {}

  async createEntry(input: { userId: number; sessionId: number; ticketTypeId: number; quantity: number; attendeeIds?: number[] }): Promise<{ entry: WaitlistEntryRecord; rank: number }> {
    const attendeeIds = [...(input.attendeeIds ?? [])].sort((a, b) => a - b);
    const inserted = await this.database.query(
      `insert into waitlist_entry (user_id, session_id, ticket_type_id, quantity, attendee_ids, status)
       values ($1, $2, $3, $4, $5::jsonb, $6)
       returning *`,
      [input.userId, input.sessionId, input.ticketTypeId, input.quantity, JSON.stringify(attendeeIds), WAITLIST_ENTRY_STATUS.WAITING],
    );
    const entry = this.mapEntry(inserted.rows[0]);
    const rank = await this.getRank(entry.id, entry.sessionId, entry.ticketTypeId);
    return { entry, rank };
  }

  async listByUser(userId: number, limit?: number): Promise<Array<WaitlistEntryRecord & { rank: number | null }>> {
    const params: Array<number> = [userId];
    const limitClause = Number.isInteger(limit) && limit >= 0 ? ' limit $2' : '';
    if (limitClause) {
      params.push(limit as number);
    }
    const result = await this.database.query(
      `select * from waitlist_entry where user_id = $1 order by create_time desc, id desc${limitClause}`,
      params,
    );
    const entries = result.rows.map((row) => this.mapEntry(row));
    return Promise.all(entries.map(async (entry) => ({
      ...entry,
      rank: entry.status === WAITLIST_ENTRY_STATUS.WAITING
        ? await this.getRank(entry.id, entry.sessionId, entry.ticketTypeId)
        : null,
    })));
  }

  async cancelWaitingEntry(id: number, userId: number): Promise<WaitlistEntryRecord | null> {
    const result = await this.database.query(
      `update waitlist_entry
       set status = $3, update_time = now()
       where id = $1 and user_id = $2 and status = $4
       returning *`,
      [id, userId, WAITLIST_ENTRY_STATUS.CANCELLED, WAITLIST_ENTRY_STATUS.WAITING],
    );
    return result.rows[0] ? this.mapEntry(result.rows[0]) : null;
  }

  async claimNextEntry(input: { sessionId: number; ticketTypeId: number; releasedQuantity: number }): Promise<WaitlistEntryRecord | null> {
    const result = await this.database.query(
      `update waitlist_entry
       set status = $4, update_time = now()
       where id = (
         select id
         from waitlist_entry
         where session_id = $1
           and ticket_type_id = $2
           and status = $3
           and quantity <= $5
         order by priority_no asc, create_time asc, id asc
         for update skip locked
         limit 1
       )
       returning *`,
      [input.sessionId, input.ticketTypeId, WAITLIST_ENTRY_STATUS.WAITING, WAITLIST_ENTRY_STATUS.ALLOCATING, input.releasedQuantity],
    );
    return result.rows[0] ? this.mapEntry(result.rows[0]) : null;
  }

  async markEntryOffered(entryId: number, orderId: number, expireTime: Date): Promise<WaitlistEntryRecord> {
    const result = await this.database.query(
      `update waitlist_entry
       set status = $2, offer_order_id = $3, offer_expire_time = $4, fail_reason = null, update_time = now()
       where id = $1 and status = $5
       returning *`,
      [entryId, WAITLIST_ENTRY_STATUS.OFFERED, orderId, expireTime, WAITLIST_ENTRY_STATUS.ALLOCATING],
    );
    return this.mapEntry(result.rows[0]);
  }

  async restoreAllocatingEntry(entryId: number, reason: string): Promise<void> {
    await this.database.query(
      `update waitlist_entry
       set status = $2, fail_reason = $3, update_time = now()
       where id = $1 and status = $4`,
      [entryId, WAITLIST_ENTRY_STATUS.WAITING, reason, WAITLIST_ENTRY_STATUS.ALLOCATING],
    );
  }

  async markEntryFailed(entryId: number, reason: string): Promise<void> {
    await this.database.query(
      `update waitlist_entry
       set status = $2, fail_reason = $3, update_time = now()
       where id = $1 and status = $4`,
      [entryId, WAITLIST_ENTRY_STATUS.FAILED, reason, WAITLIST_ENTRY_STATUS.ALLOCATING],
    );
  }

  async createOffer(input: { entry: WaitlistEntryRecord; orderId: number; expireTime: Date }): Promise<WaitlistOfferRecord> {
    const result = await this.database.query(
      `insert into waitlist_offer (entry_id, user_id, session_id, ticket_type_id, quantity, order_id, status, expire_time)
       values ($1, $2, $3, $4, $5, $6, $7, $8)
       on conflict (order_id) do update set update_time = now()
       returning *`,
      [
        input.entry.id,
        input.entry.userId,
        input.entry.sessionId,
        input.entry.ticketTypeId,
        input.entry.quantity,
        input.orderId,
        WAITLIST_OFFER_STATUS.OFFERED,
        input.expireTime,
      ],
    );
    return this.mapOffer(result.rows[0]);
  }

  async beginAllocationEvent(eventKey: string, sessionId: number, ticketTypeId: number, releasedQuantity: number, sourceOrderId: number | null): Promise<boolean> {
    const result = await this.database.query(
      `insert into waitlist_allocation_log (event_key, attempt_no, session_id, ticket_type_id, released_quantity, source_order_id, status, message)
       values ($1, 0, $2, $3, $4, $5, 'PROCESSING', '候补分配事件开始')
       on conflict (event_key, attempt_no) do nothing
       returning id`,
      [eventKey, sessionId, ticketTypeId, releasedQuantity, sourceOrderId],
    );
    if (result.rows.length === 1) {
      return true;
    }
    const retryClaim = await this.database.query(
      `update waitlist_allocation_log
       set status = 'PROCESSING', message = '候补分配事件重试'
       where event_key = $1 and attempt_no = 0 and status = 'FAILED'
       returning id`,
      [eventKey],
    );
    if (retryClaim.rows.length === 1) {
      await this.database.query(
        `delete from waitlist_allocation_log where event_key = $1 and attempt_no > 0`,
        [eventKey],
      );
      return true;
    }
    return false;
  }

  async markAllocationEventFailed(eventKey: string, message: string): Promise<void> {
    await this.database.query(
      `update waitlist_allocation_log
       set status = 'FAILED', message = $2
       where event_key = $1 and attempt_no = 0`,
      [eventKey, message],
    );
  }

  async logAllocationAttempt(input: {
    eventKey: string;
    attemptNo: number;
    sessionId: number;
    ticketTypeId: number;
    releasedQuantity: number;
    entryId: number | null;
    orderId: number | null;
    sourceOrderId: number | null;
    status: 'FAILED' | 'OFFERED' | 'NO_MATCH' | 'DUPLICATE';
    message: string;
  }): Promise<void> {
    await this.database.query(
      `insert into waitlist_allocation_log
        (event_key, attempt_no, session_id, ticket_type_id, released_quantity, allocated_entry_id, order_id, source_order_id, status, message)
       values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       on conflict (event_key, attempt_no) do nothing`,
      [input.eventKey, input.attemptNo, input.sessionId, input.ticketTypeId, input.releasedQuantity, input.entryId, input.orderId, input.sourceOrderId, input.status, input.message],
    );
  }

  async markOfferPaidByOrder(orderId: number): Promise<WaitlistOfferRecord | null> {
    const result = await this.database.query(
      `update waitlist_offer set status = $2, update_time = now() where order_id = $1 and status = $3 returning *`,
      [orderId, WAITLIST_OFFER_STATUS.PAID, WAITLIST_OFFER_STATUS.OFFERED],
    );
    await this.database.query(
      `update waitlist_entry set status = $2, update_time = now()
       where offer_order_id = $1 and status = $3`,
      [orderId, WAITLIST_ENTRY_STATUS.PAID, WAITLIST_ENTRY_STATUS.OFFERED],
    );
    return result.rows[0] ? this.mapOffer(result.rows[0]) : null;
  }

  async markOfferExpiredByOrder(orderId: number): Promise<WaitlistOfferRecord | null> {
    const result = await this.database.query(
      `update waitlist_offer set status = $2, update_time = now() where order_id = $1 and status = $3 returning *`,
      [orderId, WAITLIST_OFFER_STATUS.EXPIRED, WAITLIST_OFFER_STATUS.OFFERED],
    );
    await this.database.query(
      `update waitlist_entry set status = $2, update_time = now()
       where offer_order_id = $1 and status = $3`,
      [orderId, WAITLIST_ENTRY_STATUS.EXPIRED, WAITLIST_ENTRY_STATUS.OFFERED],
    );
    return result.rows[0] ? this.mapOffer(result.rows[0]) : null;
  }

  async findExpiredOffers(now: Date, limit: number): Promise<WaitlistOfferRecord[]> {
    const result = await this.database.query(
      `select * from waitlist_offer
       where status = $1 and expire_time <= $2
       order by expire_time asc, id asc
       limit $3`,
      [WAITLIST_OFFER_STATUS.OFFERED, now, limit],
    );
    return result.rows.map((row) => this.mapOffer(row));
  }

  private async getRank(entryId: number, sessionId: number, ticketTypeId: number): Promise<number> {
    const result = await this.database.query<{ rank: string | number }>(
      `select count(*) + 1 as rank
       from waitlist_entry current_entry
       join waitlist_entry target on target.id = $1
       where current_entry.session_id = $2
         and current_entry.ticket_type_id = $3
         and current_entry.status = $4
         and (
           current_entry.priority_no < target.priority_no
           or (current_entry.priority_no = target.priority_no and current_entry.create_time < target.create_time)
           or (current_entry.priority_no = target.priority_no and current_entry.create_time = target.create_time and current_entry.id < target.id)
         )`,
      [entryId, sessionId, ticketTypeId, WAITLIST_ENTRY_STATUS.WAITING],
    );
    return Number(result.rows[0]?.rank ?? 1);
  }

  private mapEntry(row: any): WaitlistEntryRecord {
    return {
      id: Number(row.id),
      userId: Number(row.user_id),
      sessionId: Number(row.session_id),
      ticketTypeId: Number(row.ticket_type_id),
      quantity: Number(row.quantity),
      attendeeIds: this.parseJsonArray<number>(row.attendee_ids),
      seatPreference: row.seat_preference ?? null,
      status: row.status,
      priorityNo: Number(row.priority_no),
      offerOrderId: row.offer_order_id == null ? null : Number(row.offer_order_id),
      offerExpireTime: row.offer_expire_time ?? null,
      failReason: row.fail_reason ?? null,
      createTime: row.create_time,
      updateTime: row.update_time,
    };
  }

  private mapOffer(row: any): WaitlistOfferRecord {
    return {
      id: Number(row.id),
      entryId: Number(row.entry_id),
      userId: Number(row.user_id),
      sessionId: Number(row.session_id),
      ticketTypeId: Number(row.ticket_type_id),
      quantity: Number(row.quantity),
      orderId: Number(row.order_id),
      status: row.status,
      expireTime: row.expire_time,
      createTime: row.create_time,
      updateTime: row.update_time,
    };
  }

  private parseJsonArray<T>(value: T[] | string | null | undefined): T[] {
    if (Array.isArray(value)) return value;
    if (typeof value === 'string' && value.trim()) return JSON.parse(value) as T[];
    return [];
  }
}
