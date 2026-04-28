create table trust_telemetry_events (
    id uuid primary key,
    telemetry_type text not null,
    target_type text not null,
    target_id uuid,
    severity text not null,
    signal_value numeric,
    threshold_value numeric,
    policy_version text,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_trust_telemetry_type check (telemetry_type in ('MODERATION_BACKLOG', 'DISPUTE_BACKLOG', 'RISK_SPIKE', 'REVIEW_ABUSE_SPIKE', 'TRUST_SCORE_SPIKE', 'SEARCH_SUPPRESSION_SPIKE', 'PAYMENT_BOUNDARY_REVIEW_SPIKE', 'EVIDENCE_BACKLOG')),
    constraint chk_trust_telemetry_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table trust_slo_definitions (
    id uuid primary key,
    slo_key text not null unique,
    name text not null,
    target_type text not null,
    threshold_value numeric not null,
    window_minutes int not null,
    severity text not null,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    constraint chk_trust_slo_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table trust_incidents (
    id uuid primary key,
    incident_type text not null,
    status text not null,
    severity text not null,
    title text not null,
    description text not null,
    detected_from_telemetry_id uuid references trust_telemetry_events(id),
    created_at timestamptz not null default now(),
    mitigated_at timestamptz,
    resolved_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_trust_incident_type check (incident_type in ('RISK_SPIKE', 'REVIEW_ABUSE_CAMPAIGN', 'DISPUTE_BACKLOG', 'MODERATION_BACKLOG', 'TRUST_SCORE_ANOMALY', 'SEARCH_SUPPRESSION_ANOMALY', 'SAFETY_ESCALATION_CLUSTER', 'PAYMENT_BOUNDARY_ANOMALY', 'EVIDENCE_BACKLOG')),
    constraint chk_trust_incident_status check (status in ('OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'FALSE_POSITIVE')),
    constraint chk_trust_incident_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table trust_incident_timeline_events (
    id uuid primary key,
    incident_id uuid not null references trust_incidents(id),
    event_type text not null,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table trust_incident_impacts (
    id uuid primary key,
    incident_id uuid not null references trust_incidents(id),
    users_affected int not null default 0,
    listings_hidden int not null default 0,
    transactions_blocked int not null default 0,
    disputes_involved int not null default 0,
    reviews_suppressed int not null default 0,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table trust_alerts (
    id uuid primary key,
    incident_id uuid references trust_incidents(id),
    alert_type text not null,
    severity text not null,
    status text not null default 'OPEN',
    message text not null,
    created_at timestamptz not null default now(),
    acknowledged_at timestamptz,
    acknowledged_by text,
    acknowledgement_reason text,
    constraint chk_trust_alert_type check (alert_type in ('INTERNAL_TRUST_ALERT', 'OPS_REVIEW_ALERT', 'SAFETY_REVIEW_ALERT', 'POLICY_REVIEW_ALERT')),
    constraint chk_trust_alert_status check (status in ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    constraint chk_trust_alert_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table trust_score_lineage_entries (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    reputation_snapshot_id uuid references reputation_snapshots(id),
    source_type text not null,
    source_id uuid not null,
    contribution_type text not null,
    contribution_value int not null default 0,
    policy_version text not null,
    explanation text not null,
    created_at timestamptz not null default now(),
    constraint chk_trust_score_lineage_source check (source_type in ('REVIEW', 'DISPUTE', 'RISK_DECISION', 'MODERATOR_ACTION', 'RESTRICTION', 'PROFILE_QUALITY', 'COMPLETION_RATE', 'CANCELLATION', 'NO_SHOW', 'EVIDENCE_RELIABILITY')),
    constraint chk_trust_score_lineage_contribution check (contribution_type in ('POSITIVE', 'NEGATIVE', 'NEUTRAL', 'SUPPRESSED'))
);

create table ranking_lineage_entries (
    id uuid primary key,
    ranking_decision_id uuid references ranking_decision_logs(id),
    listing_id uuid not null references marketplace_listings(id),
    participant_id uuid references participants(id),
    policy_version text not null,
    score int not null,
    reasons_json jsonb not null default '[]'::jsonb,
    suppression_reason text,
    created_at timestamptz not null default now()
);

create table policy_lineage_entries (
    id uuid primary key,
    decision_type text not null,
    decision_id uuid not null,
    policy_name text not null,
    policy_version text not null,
    matched_rules_json jsonb not null default '[]'::jsonb,
    exception_ids_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now()
);

create table lineage_rebuild_runs (
    id uuid primary key,
    rebuild_type text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    summary_json jsonb not null default '{}'::jsonb,
    constraint chk_lineage_rebuild_type check (rebuild_type in ('TRUST_SCORE_LINEAGE', 'RANKING_LINEAGE', 'POLICY_LINEAGE', 'FULL_LINEAGE')),
    constraint chk_lineage_rebuild_status check (status in ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

create table consistency_check_runs (
    id uuid primary key,
    check_type text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    summary_json jsonb not null default '{}'::jsonb,
    constraint chk_consistency_check_type check (check_type in ('TRUST_PROFILE', 'REPUTATION_REBUILD', 'SEARCH_INDEX', 'EVENT_ANALYTICS', 'EVIDENCE_REFERENCE', 'DISPUTE', 'CAPABILITY', 'FULL_CONSISTENCY')),
    constraint chk_consistency_check_status check (status in ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

create table data_repair_recommendations (
    id uuid primary key,
    consistency_finding_id uuid references consistency_findings(id),
    repair_type text not null,
    target_type text not null,
    target_id uuid,
    severity text not null,
    status text not null default 'PROPOSED',
    recommendation_json jsonb not null default '{}'::jsonb,
    reason text not null,
    created_at timestamptz not null default now(),
    decided_at timestamptz,
    constraint chk_data_repair_type check (repair_type in ('REBUILD_REPUTATION', 'REBUILD_SEARCH_INDEX', 'REBUILD_LINEAGE', 'MARK_EVIDENCE_REFERENCE_INVALID', 'REQUEST_OPERATOR_REVIEW', 'RESTORE_CAPABILITY_ALIGNMENT', 'REPLAY_EVENTS', 'MANUAL_REPAIR_REQUIRED')),
    constraint chk_data_repair_status check (status in ('PROPOSED', 'APPROVED', 'APPLIED', 'REJECTED', 'CANCELLED'))
);

create table operator_data_repair_actions (
    id uuid primary key,
    repair_recommendation_id uuid references data_repair_recommendations(id),
    action_type text not null,
    target_type text not null,
    target_id uuid,
    actor text not null,
    reason text not null,
    risk_acknowledgement text not null,
    before_json jsonb not null default '{}'::jsonb,
    after_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_operator_repair_action check (action_type in ('APPLY_REBUILD_REPUTATION', 'APPLY_REBUILD_SEARCH_INDEX', 'APPLY_REBUILD_LINEAGE', 'MARK_FINDING_RESOLVED', 'RECORD_MANUAL_REPAIR', 'REJECT_RECOMMENDATION'))
);

alter table marketplace_events drop constraint if exists chk_marketplace_events_v6_type;
alter table marketplace_events add constraint chk_marketplace_events_v7_type check (event_type in (
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
    'CONSISTENCY_CHECK_RUN', 'DATA_REPAIR_RECOMMENDED', 'OPERATOR_DATA_REPAIR_APPLIED'
));

create index idx_trust_telemetry_type_created on trust_telemetry_events(telemetry_type, created_at);
create index idx_trust_telemetry_severity_created on trust_telemetry_events(severity, created_at);
create index idx_trust_slo_key on trust_slo_definitions(slo_key);
create index idx_trust_incidents_type_status on trust_incidents(incident_type, status);
create index idx_trust_incidents_severity_created on trust_incidents(severity, created_at);
create index idx_trust_incident_timeline_incident_created on trust_incident_timeline_events(incident_id, created_at);
create index idx_trust_incident_impacts_incident on trust_incident_impacts(incident_id);
create index idx_trust_alerts_status_severity on trust_alerts(status, severity);
create index idx_trust_score_lineage_participant_created on trust_score_lineage_entries(participant_id, created_at);
create index idx_trust_score_lineage_source on trust_score_lineage_entries(source_type, source_id);
create index idx_ranking_lineage_decision on ranking_lineage_entries(ranking_decision_id);
create index idx_ranking_lineage_listing on ranking_lineage_entries(listing_id);
create index idx_policy_lineage_decision on policy_lineage_entries(decision_type, decision_id);
create index idx_policy_lineage_policy on policy_lineage_entries(policy_name, policy_version);
create index idx_lineage_rebuild_type_status on lineage_rebuild_runs(rebuild_type, status);
create index idx_consistency_check_type_status on consistency_check_runs(check_type, status);
create index idx_data_repair_status_type on data_repair_recommendations(status, repair_type);
create index idx_data_repair_target on data_repair_recommendations(target_type, target_id);
create index idx_operator_repair_target on operator_data_repair_actions(target_type, target_id);
