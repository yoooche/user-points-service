# Design notes

## Scale assumptions

- ~70M monthly web visits; app traffic assumed 2–4× web → ~10M DAU, 100M registered users.
- Peak on weekend evenings at 10× average → ~6K write QPS, ~25K read QPS.

## Leaderboard tie-break

Equal totals rank by `userId` ascending, the same in Redis and in the MySQL fallback.

- The sorted set stores `score = -total` and is read with `ZRANGE 0 9`; equal scores fall back to member order.
  `ZREVRANGE` on positive scores would order ties descending.
- Redis compares members byte by byte, and `user_id` uses `utf8mb4_0900_bin`, which does the same.

## Event delivery: transactional outbox

The event is written to `outbox_message` in the same transaction as the points, and `OutboxRelay` publishes it after
the commit. A rolled-back add never sends an event, and a committed one always does.

| Option | Crash after commit | Broker down |
| --- | --- | --- |
| Send after commit | event lost | `POST` succeeds |
| **Transactional outbox** | sent after restart | `POST` succeeds; rows wait |
| RocketMQ transactional message | broker checks back | `POST` fails |

- **Trigger:** a commit wakes the relay, so a message leaves within milliseconds. Passes run on one thread, wake-ups
  arriving during a queued pass merge into it, and a 30-second sweep plus a startup pass pick up leftovers.
- **At least once:** a row is deleted after its send, so a failed delete sends it again. Consumers deduplicate by
  `eventId`.
- **No ordering:** `ADDED` events commute, so each page of up to 200 rows goes as one batch (split by topic, tag and
  1 MB). Seeded drain: ~15,000 msg/s, against ~1,400 sent one by one in order. A pass stops after 3 failed batches in
  a row, so an outage cannot hold up shutdown.
- **Dead letters:** only `MESSAGE_ILLEGAL` (a message RocketMQ refuses for its content) moves to
  `outbox_dead_letter`. Everything else, outages included, is retried forever. A refused batch is resent one by one so
  only the bad message moves. To replay one, insert it back into `outbox_message` and delete it from the dead-letter
  table in one transaction; it leaves with the next sweep.

## Virtual threads

Request threads are virtual (`spring.threads.virtual.enabled`). With 200 platform threads, writes stuck on a slow
database take every thread and cached reads queue behind them; with virtual threads the limit is the 20-connection
pool, and reads carry on.

k6, 50 cached reads per second while 300 writers hit a table locked for 15 s:

| Cached reads | Platform | Virtual |
| --- | --- | --- |
| p95 | 2,650 ms | 5.0 ms |
| Max | 2,990 ms | 36.7 ms |

Writes fail in both cases. Connector/J 9 uses locks, not `synchronized`, so blocked queries do not pin carriers. The
relay keeps its single platform thread (`ThreadConfig`).
