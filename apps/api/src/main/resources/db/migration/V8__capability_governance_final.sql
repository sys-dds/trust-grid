alter table consistency_findings
    add column if not exists last_seen_at timestamptz not null default now();

create unique index if not exists idx_consistency_findings_open_unique
    on consistency_findings(finding_type, target_type, target_id)
    where status = 'OPEN';

create unique index if not exists idx_trust_score_lineage_unique_source
    on trust_score_lineage_entries(participant_id, source_type, source_id, policy_version);

create unique index if not exists idx_ranking_lineage_unique_source
    on ranking_lineage_entries(ranking_decision_id, listing_id, policy_version);

create unique index if not exists idx_policy_lineage_unique_source
    on policy_lineage_entries(decision_type, decision_id, policy_name, policy_version);

create table marketplace_capability_policies (
    id uuid primary key,
    action_name text not null,
    policy_name text not null,
    policy_version text not null,
    enabled boolean not null default true,
    min_trust_tier text,
    required_verification_status text,
    max_risk_level text,
    requires_no_active_restriction boolean not null default true,
    requires_active_capability boolean not null default true,
    max_value_cents bigint,
    policy_json jsonb not null default '{}'::jsonb,
    created_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(action_name, policy_name, policy_version),
    constraint chk_marketplace_capability_policy_action check (action_name in (
        'PUBLISH_LISTING', 'ACCEPT_TRANSACTION', 'OPEN_DISPUTE', 'CREATE_REVIEW',
        'RECEIVE_SEARCH_EXPOSURE', 'REQUEST_PAYMENT_RELEASE'
    )),
    constraint chk_marketplace_capability_policy_trust_tier check (
        min_trust_tier is null or min_trust_tier in ('NEW', 'LIMITED', 'STANDARD', 'TRUSTED', 'HIGH_TRUST', 'RESTRICTED', 'SUSPENDED')
    ),
    constraint chk_marketplace_capability_policy_verification check (
        required_verification_status is null or required_verification_status in ('UNVERIFIED', 'BASIC', 'VERIFIED', 'ENHANCED', 'REJECTED')
    ),
    constraint chk_marketplace_capability_policy_risk check (
        max_risk_level is null or max_risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    constraint chk_marketplace_capability_policy_max_value check (max_value_cents is null or max_value_cents >= 0),
    constraint chk_marketplace_capability_policy_created_by check (length(btrim(created_by)) > 0),
    constraint chk_marketplace_capability_policy_reason check (length(btrim(reason)) > 0)
);

create table capability_decision_logs (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    action_name text not null,
    target_type text,
    target_id uuid,
    decision text not null,
    policy_name text not null,
    policy_version text not null,
    deny_reasons_json jsonb not null default '[]'::jsonb,
    next_steps_json jsonb not null default '[]'::jsonb,
    input_snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_capability_decision_action check (action_name in (
        'PUBLISH_LISTING', 'ACCEPT_TRANSACTION', 'OPEN_DISPUTE', 'CREATE_REVIEW',
        'RECEIVE_SEARCH_EXPOSURE', 'REQUEST_PAYMENT_RELEASE'
    )),
    constraint chk_capability_decision check (decision in (
        'ALLOW', 'DENY', 'ALLOW_WITH_TEMPORARY_GRANT', 'ALLOW_WITH_BREAK_GLASS',
        'REQUIRE_VERIFICATION', 'REQUIRE_EVIDENCE', 'REQUIRE_MANUAL_REVIEW'
    ))
);

create table temporary_capability_grants (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    action_name text not null,
    target_type text,
    target_id uuid,
    status text not null,
    granted_by text not null,
    reason text not null,
    risk_acknowledgement text not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by text,
    revoke_reason text,
    constraint chk_temporary_capability_grant_action check (action_name in (
        'PUBLISH_LISTING', 'ACCEPT_TRANSACTION', 'OPEN_DISPUTE', 'CREATE_REVIEW',
        'RECEIVE_SEARCH_EXPOSURE', 'REQUEST_PAYMENT_RELEASE'
    )),
    constraint chk_temporary_capability_grant_status check (status in ('ACTIVE', 'EXPIRED', 'REVOKED')),
    constraint chk_temporary_capability_grant_actor check (length(btrim(granted_by)) > 0),
    constraint chk_temporary_capability_grant_reason check (length(btrim(reason)) > 0),
    constraint chk_temporary_capability_grant_ack check (length(btrim(risk_acknowledgement)) > 0)
);

create table break_glass_capability_actions (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    action_name text not null,
    target_type text,
    target_id uuid,
    status text not null,
    actor text not null,
    reason text not null,
    risk_acknowledgement text not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by text,
    revoke_reason text,
    constraint chk_break_glass_capability_action check (action_name in (
        'PUBLISH_LISTING', 'ACCEPT_TRANSACTION', 'OPEN_DISPUTE', 'CREATE_REVIEW',
        'RECEIVE_SEARCH_EXPOSURE', 'REQUEST_PAYMENT_RELEASE'
    )),
    constraint chk_break_glass_capability_status check (status in ('ACTIVE', 'EXPIRED', 'REVOKED')),
    constraint chk_break_glass_capability_actor check (length(btrim(actor)) > 0),
    constraint chk_break_glass_capability_reason check (length(btrim(reason)) > 0),
    constraint chk_break_glass_capability_ack check (length(btrim(risk_acknowledgement)) > 0)
);

create table capability_governance_timeline_events (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    action_name text,
    target_type text,
    target_id uuid,
    event_type text not null,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_capability_governance_timeline_event check (event_type in (
        'CAPABILITY_POLICY_CREATED', 'CAPABILITY_SIMULATED', 'TEMPORARY_GRANT_CREATED',
        'TEMPORARY_GRANT_REVOKED', 'TEMPORARY_GRANT_EXPIRED', 'BREAK_GLASS_CREATED',
        'BREAK_GLASS_REVOKED', 'BREAK_GLASS_EXPIRED', 'CAPABILITY_DECISION_LOGGED'
    )),
    constraint chk_capability_governance_timeline_actor check (length(btrim(actor)) > 0),
    constraint chk_capability_governance_timeline_reason check (length(btrim(reason)) > 0)
);

alter table marketplace_events drop constraint if exists chk_marketplace_events_v7_type;
alter table marketplace_events add constraint chk_marketplace_events_v8_type check (event_type in (
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
    'POLICY_BLAST_RADIUS_PREVIEWED', 'POLICY_REGRESSION_CHECK_RUN',
    'TRUST_TELEMETRY_RECORDED', 'TRUST_SLO_BREACHED',
    'TRUST_INCIDENT_CREATED', 'TRUST_INCIDENT_UPDATED', 'TRUST_INCIDENT_RESOLVED', 'TRUST_INCIDENT_REPLAYED',
    'TRUST_ALERT_CREATED', 'TRUST_ALERT_ACKNOWLEDGED', 'OPS_DASHBOARD_AGGREGATED',
    'TRUST_SCORE_LINEAGE_RECORDED', 'RANKING_LINEAGE_RECORDED', 'POLICY_LINEAGE_RECORDED', 'LINEAGE_REBUILD_RUN',
    'CONSISTENCY_CHECK_RUN', 'DATA_REPAIR_RECOMMENDED', 'OPERATOR_DATA_REPAIR_APPLIED',
    'CAPABILITY_POLICY_CREATED', 'CAPABILITY_SIMULATED', 'TEMPORARY_CAPABILITY_GRANT_CREATED',
    'TEMPORARY_CAPABILITY_GRANT_REVOKED', 'TEMPORARY_CAPABILITY_GRANT_EXPIRED',
    'BREAK_GLASS_CAPABILITY_CREATED', 'BREAK_GLASS_CAPABILITY_REVOKED', 'BREAK_GLASS_CAPABILITY_EXPIRED',
    'CAPABILITY_DECISION_LOGGED', 'CAPABILITY_GOVERNANCE_TIMELINE_RECORDED',
    'FINAL_INVARIANT_CHECK_RUN', 'FINAL_PERFORMANCE_PROOF_RUN', 'FINAL_DOCKER_DEMO_PROOF_RUN',
    'FINAL_CAPSTONE_STOP_LINE_RECORDED'
));

create index idx_marketplace_capability_policies_action_enabled on marketplace_capability_policies(action_name, enabled);
create index idx_marketplace_capability_policies_policy on marketplace_capability_policies(policy_name, policy_version);
create index idx_capability_decision_logs_participant_action on capability_decision_logs(participant_id, action_name, created_at);
create index idx_capability_decision_logs_target on capability_decision_logs(target_type, target_id);
create index idx_temporary_capability_grants_participant_action_status on temporary_capability_grants(participant_id, action_name, status);
create index idx_temporary_capability_grants_status_expires on temporary_capability_grants(status, expires_at);
create index idx_break_glass_capability_participant_action_status on break_glass_capability_actions(participant_id, action_name, status);
create index idx_break_glass_capability_status_expires on break_glass_capability_actions(status, expires_at);
create index idx_capability_governance_timeline_participant_created on capability_governance_timeline_events(participant_id, created_at);
create index idx_capability_governance_timeline_action_created on capability_governance_timeline_events(action_name, created_at);
