# Beta metrics: goal definitions

Owner: WING-026. Definitions for the four beta success metrics, how each is measured **today**
(from the audit log and tables that exist), and what needs WING-022 (analytics, not built).

Ground rules:

- **Dates count only when confirmed from explicit evidence**, never from model inference
  (ticket constraint). Today that means the WING-009 stage `confirmed`, whose gate requires cited
  messages showing **both** people agreed to **one specific plan** (time and place or activity), with
  no negative signal after, above a confidence threshold (`backend/app/ai/stages.py`, rule 6). A
  proposed time, one-sided enthusiasm or model confidence alone is never a date. WING-018 (date
  proposals, `DateProposal` states `suggested`, `offered`, `accepted`, `confirmed`, `canceled`) is
  not built; when it is, only `confirmed` counts. A date that "probably happened" counts for nothing.
- Count each event once: audit rows are append-only and unique per action; retried sends are
  separate `send_attempted` rows but one message (`messages.id`), so count messages, not attempts.
- Exclude operator and test accounts (`auth_subject` not starting with `beta-`, and any `mock`
  connection traffic) from every metric.
- Never read message text for metrics. The queries below use IDs, types, timestamps and codes only.

## Definitions

### 1. Weekly active users (WAU)

**Definition.** Distinct beta testers with at least one **user-initiated** action in an ISO week
(Monday 00:00 UTC to Sunday): an approval decision, a manual send, a draft request, a preference
change, pause/resume/takeover, pairing. Receiving messages alone does not count (an idle phone
still receives).

**Goal (beta).** ≥ 60 % of invited testers active each week for 4 consecutive weeks.

**Measurable today: yes, approximately.** From `agent_actions` with `actor = 'user'` (approval
approved/rejected, preferences updated, pause/resume/takeover, pairing) plus `messages` with
`origin = 'user_manual'`. Gap: `createDraft` ("Suggest a reply") writes `draft_generated` with actor
`agent`, so on-request suggestions are not separable from automatic drafts without WING-022 usage
events; app opens are not recorded at all.

### 2. Time saved

**Definition.** Per tester per week: Σ over replies sent from an approved Wingman draft of
(self-reported time to write such a reply unaided − time from the approval card appearing to the
decision), plus nothing for drafts rejected or rewritten. Reported as median minutes per tester-week.

**Goal (beta).** Median ≥ 10 minutes per active tester-week, and no tester reporting net time lost
for 2 consecutive weeks.

**Measurable today: partly.**
- Derivable: count of `approved_draft` messages that reached `sent`; per approval,
  `decided_at − created_at` (an upper bound on review time: it includes time the tester was away);
  whether it was edited (`approval_approved` audit `details.edited`).
- Not derivable: unaided writing time. It comes from the weekly self-report
  ([`feedback_form.md`](feedback_form.md#time-saved-weekly-about-2-minutes)). Needs WING-022 for
  in-app timing (card shown, edit started, approved) instead of the coarse approval timestamps.

### 3. Assisted-to-date conversion

**Definition.** Of conversations in which the tester **sent at least one approved Wingman draft**
(assisted conversations) during the beta, the share that reached a **confirmed** date: stage
`confirmed` as decided by the WING-009 rules (explicit two-party agreement cited from the
messages). Reported per intent mode, because a serious-relationship tester and an open-to-anything
tester are not comparable. Counted once per conversation, at the first transition to `confirmed`.
Also reported, **separately and never added to the confirmed number**: conversations that reached
`date_proposal` or `scheduling` (a plan was proposed, nothing more).

**Goal (beta).** Not a target. Report it, with the sample size; the beta is too small for a
conversion goal. Its purpose is to check the pipeline: every counted `confirmed` must be traceable
to cited messages a human can check.

**Measurable today: yes, from the audit log, with care.** `analysis_updated` rows carry
`details.previous_stage`, `details.stage`, `details.uncertain` and `details.source_message_ids`
(IDs only). Count a conversation as converted when an `analysis_updated` row has `stage =
'confirmed'` and `previous_stage <> 'confirmed'`. Caveats:
- With the dev mock, analysis never changes the stage, so this is always 0 on mock backends; with a
  real LLM, classification quality is UNVERIFIED (I-009). Spot-check every counted conversation by
  asking the tester (not by reading their messages).
- A later move away from `confirmed` (rescheduled, fell through) does not un-count the first
  confirmation, but report how many regressed.
- **Needs WING-022 + WING-018** for the real thing: a `date_confirmed` usage event tied to a
  `DateProposal` in state `confirmed`, and the dashboard rule "proposed versus confirmed dates are
  shown separately".

### 4. Retention

**Definition.** Week-N retention = share of testers active (WAU definition) in week 1 who are also
active in week N (N = 2, 4). Also track SMS retention: testers who still hold a paired, connected
SMS connection at the end of week N (unpairing is a strong negative signal).

**Goal (beta).** Week-4 retention ≥ 40 %; every tester who unpairs gets a short exit question
("why did you stop?").

**Measurable today: yes** (from the WAU query per week, and `platform_connections.paired_at` /
`unpaired_at` plus `connection_paired` / `connection_unpaired` audit rows).

## What exists versus what needs WING-022

| Data | Exists today | Needs WING-022 |
|---|---|---|
| User actions (approve, reject, edit, pause, takeover, preferences, pairing) | `agent_actions` (actor, type, reason code, IDs, time) | Dashboard API, aggregates per period/intent/connector |
| Sends and delivery | `messages` (origin, delivery state, attempts, `sent_at`, `delivered_at`); audit `send_*` | Delivery-latency metric |
| Stage transitions | `analysis_updated` audit details (previous/new stage, uncertain, cited IDs) | `date_proposed` / `date_confirmed` usage events (with WING-018) |
| On-request suggestions vs automatic drafts | Not separable (both `draft_generated`, actor `agent`) | Usage event per draft request |
| App opens, screen time, review time | Not recorded | Client events |
| LLM tokens and cost | Computed per call, not persisted (`NullUsageSink` by default) | Persist to `usage_events` (table exists, unused) |
| Connector failures | `send_failed`, `send_dead_lettered`, `lease_expired` audit rows | Aggregates and alerts |
| Time saved (unaided writing time) | Not recorded | Self-report stays the source; WING-022 adds in-app timing |

## Queries (operator, read-only)

Run against the beta database with a read-only role. Replace the week bounds. They read no message
text.

```sql
-- Beta testers (exclude operator/test users)
CREATE TEMP VIEW beta_users AS
  SELECT id FROM users WHERE auth_subject LIKE 'beta-%' AND deleted_at IS NULL;

-- 1. WAU for one ISO week
SELECT count(DISTINCT u) AS wau FROM (
  SELECT user_id AS u FROM agent_actions
   WHERE actor = 'user' AND occurred_at >= '2026-11-02' AND occurred_at < '2026-11-09'
  UNION
  SELECT user_id FROM messages
   WHERE origin = 'user_manual' AND created_at >= '2026-11-02' AND created_at < '2026-11-09'
) a WHERE u IN (SELECT id FROM beta_users);

-- 2. Time-saved inputs per tester for one week: approved drafts that were sent, how many
--    were edited, median approval latency (upper bound on review time)
SELECT a.user_id,
       count(*) FILTER (WHERE m.delivery_state IN ('sent','delivered'))      AS approved_sent,
       count(*) FILTER (WHERE (aa.details->>'edited')::boolean)              AS edited,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM a.decided_at - a.created_at)) AS median_decision_s
  FROM approvals a
  JOIN messages m       ON m.approval_id = a.id AND m.user_id = a.user_id
  LEFT JOIN agent_actions aa ON aa.approval_id = a.id AND aa.type = 'approval_approved'
 WHERE a.status = 'approved' AND a.decided_at >= '2026-11-02' AND a.decided_at < '2026-11-09'
   AND a.user_id IN (SELECT id FROM beta_users)
 GROUP BY a.user_id;

-- 3. Assisted-to-date conversion: assisted conversations, and those confirmed from evidence
WITH assisted AS (
  SELECT DISTINCT user_id, conversation_id FROM messages
   WHERE origin = 'approved_draft' AND delivery_state IN ('sent','delivered')
     AND user_id IN (SELECT id FROM beta_users)
), confirmed AS (
  SELECT DISTINCT user_id, conversation_id FROM agent_actions
   WHERE type = 'analysis_updated'
     AND details->>'stage' = 'confirmed' AND details->>'previous_stage' <> 'confirmed'
), proposed AS (   -- reported separately, never counted as dates
  SELECT DISTINCT user_id, conversation_id FROM agent_actions
   WHERE type = 'analysis_updated' AND details->>'stage' IN ('date_proposal','scheduling')
)
SELECT (SELECT count(*) FROM assisted)                                   AS assisted_conversations,
       (SELECT count(*) FROM assisted JOIN confirmed USING (user_id, conversation_id)) AS confirmed_dates,
       (SELECT count(*) FROM assisted JOIN proposed  USING (user_id, conversation_id)) AS proposed_only_or_more;

-- 4. Retention: week-1 cohort active again in week N (reuse the WAU subquery per week)
-- SMS connections still paired at the end of a week
SELECT count(*) FROM platform_connections
 WHERE platform = 'sms' AND paired_at < '2026-11-09'
   AND (unpaired_at IS NULL OR unpaired_at >= '2026-11-09')
   AND user_id IN (SELECT id FROM beta_users);
```

These queries were run with `psql -v ON_ERROR_STOP=1` against a migrated scratch database
(schema `0001`) holding the install-check users from [`install.md` §7](install.md#7-what-was-actually-validated-for-this-guide-2026-11-01):
all execute; WAU counted the one user with a user action in the window. They have not been run on
real beta data, because there is none yet.
