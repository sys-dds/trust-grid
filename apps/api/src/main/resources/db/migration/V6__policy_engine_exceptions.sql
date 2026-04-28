create table trust_policy_rules (
    id uuid primary key,
    policy_version_id uuid not null references trust_policy_versions(id),
    rule_key text not null,
    rule_type text not null,
    target_scope text not null,
    condition_json jsonb not null default '{}'::jsonb,
    action_json jsonb not null default '{}'::jsonb,
    enabled boolean not null default true,
    priority int not null default 100,
    created_at timestamptz not null default now(),
    unique(policy_version_id, rule_key),
    constraint chk_trust_policy_rule_type check (rule_type in ('RISK_RULE', 'REPUTATION_RULE', 'RANKING_RULE', 'DISPUTE_RULE', 'REVIEW_WEIGHT_RULE', 'RESTRICTION_RULE')),
    constraint chk_trust_policy_rule_scope check (target_scope in ('PARTICIPANT', 'LISTING', 'TRANSACTION', 'DISPUTE', 'REVIEW', 'CATEGORY', 'GLOBAL')),
    constraint chk_trust_policy_rule_key check (length(btrim(rule_key)) > 0)
);

create table policy_approvals (
    id uuid primary key,
    policy_version_id uuid not null references trust_policy_versions(id),
    approval_status text not null,
    requested_by text not null,
    request_reason text not null,
    approved_by text,
    approval_reason text,
    risk_acknowledgement text,
    created_at timestamptz not null default now(),
    decided_at timestamptz,
    constraint chk_policy_approval_status check (approval_status in ('REQUIRED', 'APPROVED', 'REJECTED')),
    constraint chk_policy_approval_requester check (length(btrim(requested_by)) > 0),
    constraint chk_policy_approval_reason check (length(btrim(request_reason)) > 0)
);

create table policy_exceptions (
    id uuid primary key,
    policy_name text not null,
    policy_version text not null,
    target_type text not null,
    target_id uuid not null,
    exception_type text not null,
    status text not null,
    reason text not null,
    requested_by text not null,
    approved_by text,
    approval_reason text,
    risk_acknowledgement text,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    approved_at timestamptz,
    revoked_at timestamptz,
    constraint chk_policy_exception_target check (target_type in ('PARTICIPANT', 'LISTING', 'TRANSACTION', 'CATEGORY')),
    constraint chk_policy_exception_type check (exception_type in ('ALLOW_HIGH_VALUE', 'ALLOW_NEW_USER_ACTION', 'BYPASS_EXTRA_EVIDENCE', 'TEMPORARY_RANKING_VISIBILITY', 'DISPUTE_POLICY_OVERRIDE', 'REVIEW_WEIGHT_OVERRIDE')),
    constraint chk_policy_exception_status check (status in ('REQUESTED', 'APPROVED', 'ACTIVE', 'REJECTED', 'EXPIRED', 'REVOKED')),
    constraint chk_policy_exception_reason check (length(btrim(reason)) > 0)
);

create table policy_decision_logs (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    policy_name text not null,
    policy_version text not null,
    decision text not null,
    matched_rules_json jsonb not null default '[]'::jsonb,
    exception_ids_json jsonb not null default '[]'::jsonb,
    explanation_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table policy_blast_radius_previews (
    id uuid primary key,
    policy_name text not null,
    from_policy_version text,
    to_policy_version text not null,
    requested_by text not null,
    reason text not null,
    affected_users int not null default 0,
    affected_listings int not null default 0,
    affected_transactions int not null default 0,
    affected_disputes int not null default 0,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

alter table marketplace_events drop constraint if exists chk_marketplace_events_v5_type;
alter table marketplace_events add constraint chk_marketplace_events_v6_type check (event_type in (
    'PARTICIPANT_CREATED', 'PROFILE_UPDATED', 'CAPABILITY_GRANTED', 'CAPABILITY_REVOKED', 'CAPABILITY_RESTRICTED',
    'VERIFICATION_STATUS_UPDATED', 'ACCOUNT_STATUS_UPDATED', 'RESTRICTION_APPLIED', 'RESTRICTION_REMOVED',
    'TRUST_PROFILE_INITIALIZED', 'TRUST_PROFILE_UPDATED',
    'LISTING_CREATED', 'LISTING_SUBMITTED', 'LISTING_PUBLISHED', 'LISTING_UNDER_REVIEW', 'LISTING_HIDDEN',
    'LISTING_REJECTED', 'LISTING_EXPIRED', 'LISTING_UPDATED', 'LISTING_RESTORED', 'LISTING_EVIDENCE_REQUIRED',
    'LISTING_RISK_SNAPSHOT_RECORDED', 'DUPLICATE_LISTING_DETECTED',
    'TRANSACTION_CREATED', 'TRANSACTION_ACCEPTED', 'TRANSACTION_STARTED', 'TRANSACTION_SHIPPED',
    'TRANSACTION_DELIVERED', 'TRANSACTION_PROOF_PLACEHOLDER_RECORDED', 'TRANSACTION_CANCELLED',
    'TRANSACTION_COMPLETION_CLAIMED', 'TRANSACTION_CONFIRMED', 'TRANSACTION_COMPLETED',
    'TRANSACTION_NO_SHOW_REPORTED', 'TRANSACTION_RISK_SNAPSHOT_RECORDED', 'TRANSACTION_DEADLINE_CREATED',
    'TRANSACTION_DEADLINE_SATISFIED', 'TRANSACTION_DEADLINE_CANCELLED', 'TRANSACTION_INVARIANT_CHECK_RUN',
    'EVIDENCE_RECORDED', 'EVIDENCE_REQUIREMENT_CREATED', 'EVIDENCE_REQUIREMENT_SATISFIED', 'EVIDENCE_BUNDLE_GENERATED',
    'DISPUTE_OPENED', 'DISPUTE_STATUS_UPDATED', 'DISPUTE_STATEMENT_ADDED', 'DISPUTE_EVIDENCE_DEADLINE_CREATED',
    'DISPUTE_EVIDENCE_DEADLINE_MISSED', 'DISPUTE_RESOLVED', 'DISPUTE_CLOSED',
    'REVIEW_CREATED', 'REVIEW_SUPPRESSED', 'REPUTATION_RECALCULATED', 'TRUST_TIER_CHANGED',
    'RISK_DECISION_RECORDED', 'OFF_PLATFORM_CONTACT_REPORTED', 'RISK_ACTION_RECOMMENDED',
    'RANKING_DECISION_LOGGED', 'RANKING_REPLAYED',
    'REVIEW_GRAPH_EDGE_CREATED', 'REVIEW_ABUSE_CLUSTER_DETECTED', 'REVIEW_WEIGHT_SUPPRESSED', 'TRUST_GRAPH_RISK_RECORDED',
    'OPS_QUEUE_ITEM_CREATED', 'OPS_QUEUE_ITEM_UPDATED', 'MODERATOR_ACTION_RECORDED', 'MANUAL_REVIEW_CASE_CREATED',
    'MANUAL_REVIEW_STATUS_UPDATED', 'SAFETY_ESCALATION_CREATED', 'ACCOUNT_RESTRICTION_WORKFLOW_UPDATED',
    'PAYMENT_BOUNDARY_STATE_CHANGED', 'MARKETPLACE_FUNDS_RELEASE_REQUESTED', 'MARKETPLACE_REFUND_REQUESTED',
    'MARKETPLACE_PAYOUT_HOLD_REQUESTED', 'MARKETPLACE_TRANSACTION_CLOSED',
    'ANALYTICS_EVENT_INGESTED', 'REPUTATION_DRIFT_DETECTED', 'REPUTATION_REBUILD_RUN', 'SEARCH_INDEX_REBUILD_RUN',
    'EVIDENCE_CONSISTENCY_CHECK_RUN', 'OUTBOX_REPLAY_RUN', 'AUDIT_TIMELINE_REPLAY_RUN', 'SCAM_SIMULATION_RUN',
    'BENCHMARK_RUN', 'FAILURE_MATRIX_RUN',
    'TRUST_POLICY_VERSION_CREATED', 'POLICY_SIMULATION_RUN', 'SHADOW_RISK_DECISION_RECORDED',
    'COUNTERFACTUAL_RANKING_SIMULATED', 'DISPUTE_DECISION_SIMULATED', 'APPEAL_OPENED', 'APPEAL_DECIDED',
    'POLICY_RULE_CREATED', 'POLICY_APPROVAL_REQUESTED', 'POLICY_APPROVAL_RECORDED', 'POLICY_ACTIVATED',
    'POLICY_RETIRED', 'POLICY_ROLLBACK_COMPLETED', 'POLICY_EXCEPTION_REQUESTED', 'POLICY_EXCEPTION_APPROVED',
    'POLICY_EXCEPTION_REJECTED', 'POLICY_EXCEPTION_REVOKED', 'POLICY_DECISION_RECORDED',
    'POLICY_BLAST_RADIUS_PREVIEWED', 'POLICY_REGRESSION_CHECK_RUN'
));

create index idx_trust_policy_rules_version_type on trust_policy_rules(policy_version_id, rule_type);
create index idx_trust_policy_rules_version_priority on trust_policy_rules(policy_version_id, priority);
create index idx_policy_approvals_version_status on policy_approvals(policy_version_id, approval_status);
create index idx_policy_exceptions_name_version_status on policy_exceptions(policy_name, policy_version, status);
create index idx_policy_exceptions_target_status on policy_exceptions(target_type, target_id, status);
create index idx_policy_decision_logs_target_created on policy_decision_logs(target_type, target_id, created_at);
create index idx_policy_decision_logs_policy_created on policy_decision_logs(policy_name, policy_version, created_at);
create index idx_policy_blast_radius_name_created on policy_blast_radius_previews(policy_name, created_at);
