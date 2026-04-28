create table review_graph_edges (
    id uuid primary key,
    review_id uuid not null references marketplace_reviews(id),
    transaction_id uuid not null references marketplace_transactions(id),
    reviewer_participant_id uuid not null references participants(id),
    reviewed_participant_id uuid not null references participants(id),
    rating int not null,
    transaction_value_cents bigint not null default 0,
    normalized_text_hash text,
    created_at timestamptz not null default now(),
    unique(review_id)
);

create table review_abuse_clusters (
    id uuid primary key,
    cluster_type text not null,
    severity text not null,
    status text not null default 'OPEN',
    policy_version text not null default 'review_abuse_rules_v1',
    summary text not null,
    signals_json jsonb not null default '[]'::jsonb,
    member_participant_ids_json jsonb not null default '[]'::jsonb,
    review_ids_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    constraint chk_review_abuse_cluster_type check (cluster_type in ('RECIPROCAL_REVIEWS', 'REVIEW_RING', 'LOW_VALUE_REVIEW_FARMING', 'SIMILAR_REVIEW_TEXT', 'REVIEW_BURST', 'SYNTHETIC_CLUSTER_SIGNAL')),
    constraint chk_review_abuse_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_review_abuse_status check (status in ('OPEN', 'SUPPRESSED', 'DISMISSED', 'ESCALATED', 'RESOLVED'))
);

create table review_suppression_actions (
    id uuid primary key,
    review_id uuid not null references marketplace_reviews(id),
    abuse_cluster_id uuid references review_abuse_clusters(id),
    suppression_type text not null,
    previous_weight int not null,
    new_weight int not null,
    actor text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    constraint chk_review_suppression_type check (suppression_type in ('WEIGHT_REDUCED', 'WEIGHT_ZEROED', 'HIDDEN_FROM_REPUTATION'))
);

create table marketplace_ops_queue_items (
    id uuid primary key,
    queue_type text not null,
    target_type text not null,
    target_id uuid not null,
    priority text not null,
    status text not null default 'OPEN',
    reason text not null,
    signals_json jsonb not null default '[]'::jsonb,
    assigned_to text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    resolved_at timestamptz,
    constraint uq_ops_queue_open unique(queue_type, target_type, target_id, status),
    constraint chk_ops_queue_priority check (priority in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_ops_queue_status check (status in ('OPEN', 'IN_REVIEW', 'AWAITING_EVIDENCE', 'RESOLVED', 'ESCALATED', 'CANCELLED'))
);

create table moderator_actions (
    id uuid primary key,
    action_type text not null,
    target_type text not null,
    target_id uuid not null,
    actor text not null,
    reason text not null,
    before_json jsonb not null default '{}'::jsonb,
    after_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table manual_review_cases (
    id uuid primary key,
    queue_item_id uuid references marketplace_ops_queue_items(id),
    target_type text not null,
    target_id uuid not null,
    status text not null default 'OPEN',
    opened_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    resolved_at timestamptz,
    constraint chk_manual_review_status check (status in ('OPEN', 'IN_REVIEW', 'AWAITING_EVIDENCE', 'RESOLVED', 'ESCALATED', 'CANCELLED'))
);

create table safety_escalations (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    severity text not null,
    status text not null default 'OPEN',
    actor text not null,
    reason text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    constraint chk_safety_escalation_status check (status in ('OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'FALSE_POSITIVE')),
    constraint chk_safety_escalation_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table payment_boundary_states (
    id uuid primary key,
    transaction_id uuid not null references marketplace_transactions(id),
    state text not null,
    reason text not null,
    created_by text not null,
    created_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_payment_boundary_state check (state in ('PAYMENT_REQUIRED', 'PAYMENT_AUTHORIZED', 'FUNDS_HELD', 'RELEASE_REQUESTED', 'REFUND_REQUESTED', 'PAYOUT_HOLD_REQUESTED', 'TRANSACTION_CLOSED'))
);

create table payment_boundary_events (
    id uuid primary key,
    transaction_id uuid not null references marketplace_transactions(id),
    event_type text not null,
    event_key text not null unique,
    reason text not null,
    requested_by text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_payment_boundary_event_type check (event_type in ('MARKETPLACE_FUNDS_RELEASE_REQUESTED', 'MARKETPLACE_REFUND_REQUESTED', 'MARKETPLACE_PAYOUT_HOLD_REQUESTED', 'MARKETPLACE_TRANSACTION_CLOSED'))
);

create table marketplace_event_analytics (
    id uuid primary key,
    source_event_id uuid unique,
    aggregate_type text not null,
    aggregate_id uuid not null,
    event_type text not null,
    occurred_at timestamptz not null,
    payload_json jsonb not null default '{}'::jsonb,
    ingested_at timestamptz not null default now()
);

create table risk_decision_analytics (
    id uuid primary key,
    risk_decision_id uuid references risk_decisions(id),
    target_type text not null,
    target_id uuid not null,
    risk_level text not null,
    decision text not null,
    policy_version text not null,
    occurred_at timestamptz not null default now()
);

create table dispute_analytics (
    id uuid primary key,
    dispute_id uuid references marketplace_disputes(id),
    dispute_type text not null,
    status text not null,
    outcome text,
    opened_at timestamptz not null,
    resolved_at timestamptz,
    created_at timestamptz not null default now()
);

create table ranking_analytics (
    id uuid primary key,
    ranking_decision_id uuid references ranking_decision_logs(id),
    policy_version text not null,
    result_count int not null,
    suppressed_count int not null default 0,
    created_at timestamptz not null default now()
);

create table rebuild_runs (
    id uuid primary key,
    rebuild_type text not null,
    status text not null,
    started_by text not null,
    reason text not null,
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    summary_json jsonb not null default '{}'::jsonb,
    constraint chk_rebuild_type check (rebuild_type in ('REPUTATION', 'SEARCH_INDEX', 'EVIDENCE_CONSISTENCY', 'OUTBOX_REPLAY', 'AUDIT_TIMELINE_REPLAY', 'FULL_CONSISTENCY')),
    constraint chk_rebuild_status check (status in ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

create table consistency_findings (
    id uuid primary key,
    finding_type text not null,
    target_type text not null,
    target_id uuid,
    severity text not null,
    status text not null default 'OPEN',
    message text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    resolved_at timestamptz
);

create table trust_policy_versions (
    id uuid primary key,
    policy_name text not null,
    policy_version text not null,
    status text not null,
    policy_json jsonb not null default '{}'::jsonb,
    created_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    activated_at timestamptz,
    retired_at timestamptz,
    unique(policy_name, policy_version),
    constraint chk_trust_policy_status check (status in ('DRAFT', 'ACTIVE', 'RETIRED'))
);

create table policy_simulation_runs (
    id uuid primary key,
    simulation_type text not null,
    policy_name text not null,
    from_policy_version text,
    to_policy_version text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table appeals (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    target_type text not null,
    target_id uuid not null,
    status text not null default 'OPEN',
    appeal_reason text not null,
    decision text,
    decided_by text,
    decision_reason text,
    created_at timestamptz not null default now(),
    decided_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_appeal_status check (status in ('OPEN', 'UNDER_REVIEW', 'EVIDENCE_REQUIRED', 'DECIDED', 'CANCELLED'))
);

create table scam_simulation_runs (
    id uuid primary key,
    simulation_type text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    seed_size int not null default 0,
    summary_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table benchmark_runs (
    id uuid primary key,
    benchmark_type text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table failure_matrix_results (
    id uuid primary key,
    scenario_name text not null,
    dependency_name text not null,
    status text not null,
    degraded_behavior text not null,
    passed boolean not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

alter table marketplace_events drop constraint if exists chk_marketplace_events_v4_type;
alter table marketplace_events add constraint chk_marketplace_events_v5_type check (event_type in (
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
    'COUNTERFACTUAL_RANKING_SIMULATED', 'DISPUTE_DECISION_SIMULATED', 'APPEAL_OPENED', 'APPEAL_DECIDED'
));

create index idx_review_graph_edges_reviewer on review_graph_edges(reviewer_participant_id);
create index idx_review_graph_edges_reviewed on review_graph_edges(reviewed_participant_id);
create index idx_review_graph_edges_text_hash on review_graph_edges(normalized_text_hash);
create index idx_review_abuse_clusters_type_status on review_abuse_clusters(cluster_type, status);
create index idx_ops_queue_type_status_priority on marketplace_ops_queue_items(queue_type, status, priority);
create index idx_ops_queue_target on marketplace_ops_queue_items(target_type, target_id);
create index idx_moderator_actions_target_created on moderator_actions(target_type, target_id, created_at);
create index idx_manual_review_status on manual_review_cases(status);
create index idx_safety_escalations_status_severity on safety_escalations(status, severity);
create index idx_payment_boundary_states_transaction_created on payment_boundary_states(transaction_id, created_at);
create index idx_payment_boundary_events_transaction_type on payment_boundary_events(transaction_id, event_type);
create index idx_marketplace_event_analytics_type_occurred on marketplace_event_analytics(event_type, occurred_at);
create index idx_risk_decision_analytics_target on risk_decision_analytics(target_type, target_id);
create index idx_dispute_analytics_status_outcome on dispute_analytics(status, outcome);
create index idx_ranking_analytics_policy_created on ranking_analytics(policy_version, created_at);
create index idx_rebuild_runs_type_status on rebuild_runs(rebuild_type, status);
create index idx_consistency_findings_type_status on consistency_findings(finding_type, status);
create index idx_trust_policy_versions_name_status on trust_policy_versions(policy_name, status);
create index idx_policy_simulation_type_status on policy_simulation_runs(simulation_type, status);
create index idx_appeals_participant_status on appeals(participant_id, status);
create index idx_scam_simulation_type_status on scam_simulation_runs(simulation_type, status);
create index idx_benchmark_type_status on benchmark_runs(benchmark_type, status);
create index idx_failure_matrix_dependency_scenario on failure_matrix_results(dependency_name, scenario_name);
