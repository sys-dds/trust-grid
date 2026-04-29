alter table capability_decision_logs add column if not exists policy_snapshot_json jsonb not null default '{}'::jsonb;
alter table capability_decision_logs add column if not exists policy_hash text;

create table trust_cases (
    id uuid primary key,
    case_type text not null,
    status text not null,
    priority text not null,
    title text not null,
    summary text not null,
    assigned_to text,
    opened_by text not null,
    reason text not null,
    sla_due_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    resolved_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb
);

create table trust_case_targets (
    id uuid primary key,
    case_id uuid not null references trust_cases(id),
    target_type text not null,
    target_id uuid not null,
    relationship_type text not null,
    added_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    unique(case_id, target_type, target_id)
);

create table trust_case_timeline_events (
    id uuid primary key,
    case_id uuid not null references trust_cases(id),
    event_type text not null,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table trust_case_playbooks (
    id uuid primary key,
    playbook_key text not null unique,
    case_type text not null,
    name text not null,
    steps_json jsonb not null default '[]'::jsonb,
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table trust_case_recommendations (
    id uuid primary key,
    case_id uuid not null references trust_cases(id),
    recommendation_type text not null,
    target_type text,
    target_id uuid,
    status text not null default 'OPEN',
    severity text not null default 'MEDIUM',
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table trust_campaigns (
    id uuid primary key,
    campaign_type text not null,
    status text not null,
    severity text not null,
    title text not null,
    summary text not null,
    opened_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb
);

create table trust_campaign_graph_edges (
    id uuid primary key,
    campaign_id uuid not null references trust_campaigns(id),
    source_type text not null,
    source_id uuid not null,
    target_type text not null,
    target_id uuid not null,
    edge_type text not null,
    strength int not null default 1,
    evidence_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table campaign_containment_plans (
    id uuid primary key,
    campaign_id uuid not null references trust_campaigns(id),
    status text not null,
    proposed_by text not null,
    reason text not null,
    blast_radius_json jsonb not null default '{}'::jsonb,
    actions_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    approved_at timestamptz,
    approved_by text,
    approval_reason text,
    risk_acknowledgement text,
    executed_at timestamptz,
    reversed_at timestamptz
);

create table campaign_containment_actions (
    id uuid primary key,
    containment_plan_id uuid not null references campaign_containment_plans(id),
    action_type text not null,
    target_type text not null,
    target_id uuid not null,
    status text not null,
    before_json jsonb not null default '{}'::jsonb,
    after_json jsonb not null default '{}'::jsonb,
    executed_at timestamptz,
    reversed_at timestamptz,
    created_at timestamptz not null default now()
);

create table evidence_versions (
    id uuid primary key,
    evidence_id uuid not null references marketplace_evidence(id),
    version_number int not null,
    object_key text,
    hash text not null,
    provenance_json jsonb not null default '{}'::jsonb,
    created_by uuid references participants(id),
    created_at timestamptz not null default now(),
    unique(evidence_id, version_number)
);

create table evidence_custody_events (
    id uuid primary key,
    evidence_id uuid not null references marketplace_evidence(id),
    evidence_version_id uuid references evidence_versions(id),
    event_type text not null,
    actor_type text not null,
    actor_id text not null,
    reason text not null,
    hash_before text,
    hash_after text,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table evidence_access_decisions (
    id uuid primary key,
    evidence_id uuid not null references marketplace_evidence(id),
    requested_by text not null,
    access_purpose text not null,
    decision text not null,
    deny_reasons_json jsonb not null default '[]'::jsonb,
    redaction_required boolean not null default false,
    created_at timestamptz not null default now()
);

create table evidence_retention_metadata (
    id uuid primary key,
    evidence_id uuid not null references marketplace_evidence(id),
    retention_class text not null,
    retain_until timestamptz,
    legal_hold boolean not null default false,
    legal_hold_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(evidence_id)
);

create table evidence_disclosure_bundles (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    requested_by text not null,
    reason text not null,
    bundle_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table marketplace_guarantee_policies (
    id uuid primary key,
    policy_name text not null,
    policy_version text not null,
    enabled boolean not null default true,
    max_value_cents bigint,
    required_evidence_json jsonb not null default '[]'::jsonb,
    fraud_exclusions_json jsonb not null default '[]'::jsonb,
    outcome_rules_json jsonb not null default '{}'::jsonb,
    created_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    unique(policy_name, policy_version)
);

create table guarantee_decision_logs (
    id uuid primary key,
    transaction_id uuid references marketplace_transactions(id),
    dispute_id uuid references marketplace_disputes(id),
    participant_id uuid references participants(id),
    policy_name text not null,
    policy_version text not null,
    decision text not null,
    deny_reasons_json jsonb not null default '[]'::jsonb,
    required_evidence_json jsonb not null default '[]'::jsonb,
    recommendation text,
    input_snapshot_json jsonb not null default '{}'::jsonb,
    idempotency_key text,
    created_at timestamptz not null default now()
);

create table guarantee_audit_timeline_events (
    id uuid primary key,
    guarantee_decision_id uuid not null references guarantee_decision_logs(id),
    event_type text not null,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table enforcement_ladder_policies (
    id uuid primary key,
    policy_name text not null,
    policy_version text not null,
    enabled boolean not null default true,
    ladder_json jsonb not null default '[]'::jsonb,
    created_by text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    unique(policy_name, policy_version)
);

create table enforcement_actions (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    action_type text not null,
    severity text not null,
    status text not null,
    target_type text,
    target_id uuid,
    actor text not null,
    reason text not null,
    risk_acknowledgement text,
    before_json jsonb not null default '{}'::jsonb,
    after_json jsonb not null default '{}'::jsonb,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    reversed_at timestamptz,
    reversed_by text,
    reversal_reason text
);

create table trust_recovery_plans (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    status text not null,
    opened_by text not null,
    reason text not null,
    required_milestones_json jsonb not null default '[]'::jsonb,
    progress_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    cancelled_at timestamptz
);

create table trust_recovery_milestone_events (
    id uuid primary key,
    recovery_plan_id uuid not null references trust_recovery_plans(id),
    milestone_key text not null,
    status text not null,
    evidence_json jsonb not null default '{}'::jsonb,
    evaluated_by text not null,
    reason text not null,
    created_at timestamptz not null default now()
);

create table moderator_qa_reviews (
    id uuid primary key,
    moderator_action_id uuid references moderator_actions(id),
    enforcement_action_id uuid references enforcement_actions(id),
    case_id uuid references trust_cases(id),
    reviewer text not null,
    qa_status text not null,
    score int,
    findings_json jsonb not null default '[]'::jsonb,
    reason text not null,
    created_at timestamptz not null default now()
);

create table severe_action_approvals (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    action_type text not null,
    status text not null,
    requested_by text not null,
    request_reason text not null,
    approved_by text,
    approval_reason text,
    risk_acknowledgement text,
    created_at timestamptz not null default now(),
    decided_at timestamptz
);

create table moderator_action_reversals (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    reversed_by text not null,
    reason text not null,
    risk_acknowledgement text not null,
    before_json jsonb not null default '{}'::jsonb,
    after_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table adversarial_scenario_catalog (
    id uuid primary key,
    scenario_key text not null unique,
    name text not null,
    description text not null,
    expected_controls_json jsonb not null default '[]'::jsonb,
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table adversarial_attack_runs (
    id uuid primary key,
    scenario_key text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    seed_json jsonb not null default '{}'::jsonb,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create table detection_coverage_matrix (
    id uuid primary key,
    attack_run_id uuid not null references adversarial_attack_runs(id),
    control_key text not null,
    expected boolean not null,
    detected boolean not null,
    evidence_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table defense_recommendations (
    id uuid primary key,
    attack_run_id uuid references adversarial_attack_runs(id),
    recommendation_type text not null,
    target_type text,
    target_id uuid,
    severity text not null,
    status text not null default 'OPEN',
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table false_positive_reviews (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    reported_by text not null,
    reason text not null,
    status text not null,
    decision text,
    decided_by text,
    decision_reason text,
    created_at timestamptz not null default now(),
    decided_at timestamptz
);

create table trust_dossier_snapshots (
    id uuid primary key,
    dossier_type text not null,
    target_id uuid not null,
    generated_by text not null,
    reason text not null,
    snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table trust_scale_seed_runs (
    id uuid primary key,
    seed_type text not null,
    status text not null,
    requested_by text not null,
    reason text not null,
    counts_json jsonb not null default '{}'::jsonb,
    metrics_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

alter table data_repair_recommendations drop constraint if exists chk_data_repair_type;
alter table data_repair_recommendations add constraint chk_data_repair_type check (repair_type in (
    'REBUILD_REPUTATION', 'REBUILD_SEARCH_INDEX', 'REBUILD_LINEAGE',
    'MARK_EVIDENCE_REFERENCE_INVALID', 'REQUEST_OPERATOR_REVIEW',
    'RESTORE_CAPABILITY_ALIGNMENT', 'REPLAY_EVENTS', 'MANUAL_REPAIR_REQUIRED',
    'EXPIRE_TEMPORARY_CAPABILITY_GRANT', 'EXPIRE_BREAK_GLASS_CAPABILITY_ACTION',
    'REQUEST_CAPABILITY_DECISION_REVIEW', 'REVOKE_GRANT_FOR_CLOSED_PARTICIPANT',
    'REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT',
    'REQUEST_TRUST_CASE_TARGET_REVIEW', 'REQUEST_CAMPAIGN_GRAPH_REBUILD',
    'REQUEST_EVIDENCE_CUSTODY_REVIEW', 'REQUEST_GUARANTEE_MANUAL_REVIEW',
    'REQUEST_ENFORCEMENT_QA_REVIEW', 'REQUEST_RECOVERY_REVIEW',
    'REQUEST_ADVERSARIAL_COVERAGE_REVIEW', 'REQUEST_TRUST_DOSSIER_REBUILD'
));

alter table operator_data_repair_actions drop constraint if exists chk_operator_repair_action;
alter table operator_data_repair_actions add constraint chk_operator_repair_action check (action_type in (
    'APPLY_REBUILD_REPUTATION', 'APPLY_REBUILD_SEARCH_INDEX', 'APPLY_REBUILD_LINEAGE',
    'MARK_FINDING_RESOLVED', 'RECORD_MANUAL_REPAIR', 'REJECT_RECOMMENDATION',
    'EXPIRE_TEMPORARY_CAPABILITY_GRANT', 'EXPIRE_BREAK_GLASS_CAPABILITY_ACTION',
    'REQUEST_CAPABILITY_DECISION_REVIEW', 'REVOKE_GRANT_FOR_CLOSED_PARTICIPANT',
    'REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT',
    'REQUEST_TRUST_CASE_TARGET_REVIEW', 'REQUEST_CAMPAIGN_GRAPH_REBUILD',
    'REQUEST_EVIDENCE_CUSTODY_REVIEW', 'REQUEST_GUARANTEE_MANUAL_REVIEW',
    'REQUEST_ENFORCEMENT_QA_REVIEW', 'REQUEST_RECOVERY_REVIEW',
    'REQUEST_ADVERSARIAL_COVERAGE_REVIEW', 'REQUEST_TRUST_DOSSIER_REBUILD'
));

alter table marketplace_events drop constraint if exists chk_marketplace_events_v9_type;
alter table marketplace_events add constraint chk_marketplace_events_v10_type check (event_type in (
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
    'CAPABILITY_DECISION_REPLAYED', 'CAPABILITY_BLAST_RADIUS_PREVIEWED',
    'TRUST_CASE_OPENED', 'TRUST_CASE_TARGET_LINKED', 'TRUST_CASE_MERGED', 'TRUST_CASE_SPLIT',
    'TRUST_CASE_ASSIGNED', 'TRUST_CASE_PLAYBOOK_APPLIED', 'TRUST_CASE_RECOMMENDATION_CREATED',
    'TRUST_CASE_STATUS_CHANGED', 'TRUST_CASE_REPLAYED',
    'TRUST_CAMPAIGN_CREATED', 'TRUST_CAMPAIGN_GRAPH_EDGE_CREATED', 'CAMPAIGN_BLAST_RADIUS_PREVIEWED',
    'CAMPAIGN_CONTAINMENT_SIMULATED', 'CAMPAIGN_CONTAINMENT_APPROVED', 'CAMPAIGN_CONTAINMENT_EXECUTED',
    'CAMPAIGN_CONTAINMENT_REVERSED',
    'EVIDENCE_VERSION_CREATED', 'EVIDENCE_CUSTODY_EVENT_RECORDED', 'EVIDENCE_ACCESS_SIMULATED',
    'EVIDENCE_DISCLOSURE_BUNDLE_CREATED', 'EVIDENCE_TAMPER_CHECK_RUN', 'EVIDENCE_CONSISTENCY_REPLAYED',
    'EVIDENCE_LEGAL_HOLD_UPDATED', 'EVIDENCE_RETENTION_UPDATED',
    'GUARANTEE_POLICY_CREATED', 'GUARANTEE_ELIGIBILITY_SIMULATED', 'GUARANTEE_DECISION_RECORDED',
    'GUARANTEE_PAYMENT_BOUNDARY_RECOMMENDED', 'GUARANTEE_AUDIT_TIMELINE_RECORDED',
    'ENFORCEMENT_POLICY_CREATED', 'ENFORCEMENT_ACTION_SIMULATED', 'ENFORCEMENT_ACTION_EXECUTED',
    'ENFORCEMENT_ACTION_REVERSED', 'TRUST_RECOVERY_PLAN_CREATED', 'TRUST_RECOVERY_MILESTONE_EVALUATED',
    'CAPABILITY_RESTORATION_RECOMMENDED', 'MODERATOR_QA_REVIEW_CREATED', 'SEVERE_ACTION_APPROVAL_REQUESTED',
    'SEVERE_ACTION_APPROVED', 'SEVERE_ACTION_REJECTED', 'MODERATOR_ACTION_REVERSAL_RECORDED',
    'CASE_QUALITY_REVIEW_RUN',
    'ADVERSARIAL_SCENARIO_CREATED', 'ADVERSARIAL_ATTACK_RUN_CREATED', 'ADVERSARIAL_ATTACK_RUN_COMPLETED',
    'DETECTION_COVERAGE_RECORDED', 'DEFENSE_RECOMMENDATION_CREATED', 'FALSE_POSITIVE_REVIEW_CREATED',
    'FALSE_POSITIVE_REVIEW_DECIDED',
    'TRUST_DOSSIER_GENERATED', 'TRUST_SCALE_SEED_RUN', 'FINAL_TECHNICAL_CAPSTONE_PROOF_RUN'
));

create index idx_trust_cases_status_priority on trust_cases(status, priority);
create index idx_trust_case_targets_case on trust_case_targets(case_id, target_type);
create index idx_trust_case_targets_target on trust_case_targets(target_type, target_id);
create index idx_trust_case_timeline_case on trust_case_timeline_events(case_id, created_at);
create index idx_trust_campaigns_status on trust_campaigns(status, severity);
create index idx_trust_campaign_edges_campaign on trust_campaign_graph_edges(campaign_id);
create index idx_containment_plans_campaign on campaign_containment_plans(campaign_id, status);
create index idx_containment_actions_plan on campaign_containment_actions(containment_plan_id, status);
create index idx_evidence_versions_evidence on evidence_versions(evidence_id, version_number);
create index idx_evidence_custody_evidence on evidence_custody_events(evidence_id, created_at);
create index idx_guarantee_decisions_transaction on guarantee_decision_logs(transaction_id, created_at);
create index idx_enforcement_actions_participant on enforcement_actions(participant_id, created_at);
create index idx_recovery_plans_participant on trust_recovery_plans(participant_id, status);
create index idx_moderator_qa_case on moderator_qa_reviews(case_id, created_at);
create index idx_severe_action_status on severe_action_approvals(status, action_type);
create index idx_adversarial_runs_scenario on adversarial_attack_runs(scenario_key, status);
create index idx_coverage_run on detection_coverage_matrix(attack_run_id);
create index idx_dossier_target on trust_dossier_snapshots(dossier_type, target_id, created_at);
create index idx_scale_seed_status on trust_scale_seed_runs(seed_type, status);
