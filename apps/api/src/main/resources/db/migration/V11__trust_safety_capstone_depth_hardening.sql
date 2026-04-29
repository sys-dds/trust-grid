alter table campaign_containment_actions add column if not exists reversal_json jsonb not null default '{}'::jsonb;

alter table enforcement_actions add column if not exists severe_action_approval_id uuid references severe_action_approvals(id);
alter table severe_action_approvals add column if not exists consumed_at timestamptz;
alter table severe_action_approvals add column if not exists consumed_by_enforcement_action_id uuid references enforcement_actions(id);

create table guarantee_payment_boundary_recommendations (
    id uuid primary key,
    guarantee_decision_id uuid not null references guarantee_decision_logs(id),
    recommendation text not null,
    idempotency_key text,
    request_fingerprint text not null,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique(guarantee_decision_id, recommendation),
    unique(idempotency_key)
);

create index idx_guarantee_payment_recommendations_decision on guarantee_payment_boundary_recommendations(guarantee_decision_id);
create index idx_enforcement_actions_severe_approval on enforcement_actions(severe_action_approval_id);
create index idx_severe_action_approvals_consumed on severe_action_approvals(consumed_at);

alter table data_repair_recommendations drop constraint if exists chk_data_repair_type;
alter table data_repair_recommendations add constraint chk_data_repair_type check (repair_type in (
    'REBUILD_REPUTATION', 'REBUILD_SEARCH_INDEX', 'REBUILD_LINEAGE',
    'MARK_EVIDENCE_REFERENCE_INVALID', 'REQUEST_OPERATOR_REVIEW',
    'RESTORE_CAPABILITY_ALIGNMENT', 'REPLAY_EVENTS', 'MANUAL_REPAIR_REQUIRED',
    'EXPIRE_TEMPORARY_CAPABILITY_GRANT', 'EXPIRE_BREAK_GLASS_CAPABILITY_ACTION',
    'REQUEST_CAPABILITY_DECISION_REVIEW', 'REVOKE_GRANT_FOR_CLOSED_PARTICIPANT',
    'REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT',
    'REQUEST_TRUST_CASE_TARGET_REVIEW', 'REQUEST_CAMPAIGN_GRAPH_REBUILD',
    'REQUEST_CONTAINMENT_REVERSAL_REVIEW', 'REQUEST_EVIDENCE_CUSTODY_REVIEW',
    'REQUEST_GUARANTEE_MANUAL_REVIEW', 'REQUEST_ENFORCEMENT_QA_REVIEW',
    'REQUEST_RECOVERY_REVIEW', 'REQUEST_ADVERSARIAL_COVERAGE_REVIEW',
    'REQUEST_TRUST_DOSSIER_REBUILD', 'REQUEST_SCALE_SEED_REVIEW'
));

alter table operator_data_repair_actions drop constraint if exists chk_operator_repair_action;
alter table operator_data_repair_actions add constraint chk_operator_repair_action check (action_type in (
    'APPLY_REBUILD_REPUTATION', 'APPLY_REBUILD_SEARCH_INDEX', 'APPLY_REBUILD_LINEAGE',
    'MARK_FINDING_RESOLVED', 'RECORD_MANUAL_REPAIR', 'REJECT_RECOMMENDATION',
    'EXPIRE_TEMPORARY_CAPABILITY_GRANT', 'EXPIRE_BREAK_GLASS_CAPABILITY_ACTION',
    'REQUEST_CAPABILITY_DECISION_REVIEW', 'REVOKE_GRANT_FOR_CLOSED_PARTICIPANT',
    'REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT',
    'REQUEST_TRUST_CASE_TARGET_REVIEW', 'REQUEST_CAMPAIGN_GRAPH_REBUILD',
    'REQUEST_CONTAINMENT_REVERSAL_REVIEW', 'REQUEST_EVIDENCE_CUSTODY_REVIEW',
    'REQUEST_GUARANTEE_MANUAL_REVIEW', 'REQUEST_ENFORCEMENT_QA_REVIEW',
    'REQUEST_RECOVERY_REVIEW', 'REQUEST_ADVERSARIAL_COVERAGE_REVIEW',
    'REQUEST_TRUST_DOSSIER_REBUILD', 'REQUEST_SCALE_SEED_REVIEW'
));
