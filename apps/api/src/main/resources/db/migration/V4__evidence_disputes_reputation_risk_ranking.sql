create table marketplace_evidence (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    uploaded_by_participant_id uuid references participants(id),
    evidence_type text not null,
    object_key text,
    evidence_hash text,
    captured_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_marketplace_evidence_target check (target_type in ('LISTING', 'TRANSACTION', 'DISPUTE', 'PARTICIPANT')),
    constraint chk_marketplace_evidence_type check (evidence_type in (
        'LISTING_PHOTO', 'RECEIPT', 'DELIVERY_PROOF', 'BEFORE_PHOTO', 'AFTER_PHOTO', 'CHAT_EXCERPT',
        'SERVICE_COMPLETION_NOTE', 'CANCELLATION_REASON', 'MODERATOR_NOTE', 'USER_STATEMENT',
        'PURCHASE_PROOF_PLACEHOLDER', 'DELIVERY_PROOF_PLACEHOLDER'
    ))
);

create table evidence_requirements (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    evidence_type text not null,
    required_before_action text,
    satisfied boolean not null default false,
    satisfied_by_evidence_id uuid references marketplace_evidence(id),
    reason text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_evidence_requirements_target check (target_type in ('LISTING', 'TRANSACTION', 'DISPUTE', 'PARTICIPANT')),
    constraint chk_evidence_requirements_reason check (length(btrim(reason)) > 0)
);

create table marketplace_disputes (
    id uuid primary key,
    transaction_id uuid not null references marketplace_transactions(id),
    opened_by_participant_id uuid not null references participants(id),
    dispute_type text not null,
    status text not null,
    outcome text,
    reason text not null,
    resolution_reason text,
    resolved_by text,
    opened_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    resolved_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_marketplace_disputes_type check (dispute_type in (
        'ITEM_NOT_RECEIVED', 'ITEM_NOT_AS_DESCRIBED', 'COUNTERFEIT_SUSPECTED', 'SERVICE_NOT_DELIVERED',
        'NO_SHOW', 'WRONG_ITEM_PURCHASED', 'SHOPPER_DID_NOT_BUY', 'BUYER_FALSE_CLAIM',
        'SELLER_FALSE_CLAIM', 'SAFETY_CONCERN', 'OFF_PLATFORM_PAYMENT_ATTEMPT'
    )),
    constraint chk_marketplace_disputes_status check (status in (
        'OPEN', 'AWAITING_BUYER_EVIDENCE', 'AWAITING_SELLER_EVIDENCE', 'AWAITING_PROVIDER_EVIDENCE',
        'UNDER_REVIEW', 'RESOLVED_BUYER', 'RESOLVED_SELLER', 'RESOLVED_PROVIDER',
        'SPLIT_DECISION', 'ESCALATED', 'CLOSED'
    )),
    constraint chk_marketplace_disputes_outcome check (outcome is null or outcome in (
        'BUYER_WINS', 'SELLER_WINS', 'PROVIDER_WINS', 'SPLIT_DECISION', 'INSUFFICIENT_EVIDENCE',
        'FRAUD_SUSPECTED', 'SAFETY_ESCALATION'
    )),
    constraint chk_marketplace_disputes_reason check (length(btrim(reason)) > 0)
);

create table dispute_statements (
    id uuid primary key,
    dispute_id uuid not null references marketplace_disputes(id),
    participant_id uuid references participants(id),
    statement_type text not null,
    statement_text text not null,
    actor text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_dispute_statements_type check (statement_type in ('BUYER_STATEMENT', 'SELLER_STATEMENT', 'PROVIDER_STATEMENT', 'MODERATOR_NOTE', 'SYSTEM_NOTE')),
    constraint chk_dispute_statements_text check (length(btrim(statement_text)) > 0),
    constraint chk_dispute_statements_actor check (length(btrim(actor)) > 0),
    constraint chk_dispute_statements_reason check (length(btrim(reason)) > 0)
);

create table dispute_evidence_deadlines (
    id uuid primary key,
    dispute_id uuid not null references marketplace_disputes(id),
    required_from_role text not null,
    due_at timestamptz not null,
    status text not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    satisfied_at timestamptz,
    missed_at timestamptz,
    constraint chk_dispute_deadlines_role check (required_from_role in ('BUYER', 'SELLER', 'PROVIDER', 'REQUESTER', 'SHOPPER')),
    constraint chk_dispute_deadlines_status check (status in ('ACTIVE', 'SATISFIED', 'MISSED', 'CANCELLED'))
);

create table marketplace_reviews (
    id uuid primary key,
    transaction_id uuid not null references marketplace_transactions(id),
    reviewer_participant_id uuid not null references participants(id),
    reviewed_participant_id uuid not null references participants(id),
    status text not null,
    overall_rating int not null,
    accuracy_rating int,
    reliability_rating int,
    communication_rating int,
    punctuality_rating int,
    evidence_quality_rating int,
    item_service_match_rating int,
    review_text text,
    confidence_weight int not null default 0,
    suppression_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (transaction_id, reviewer_participant_id, reviewed_participant_id),
    constraint chk_marketplace_reviews_status check (status in ('ACTIVE', 'HIDDEN', 'SUPPRESSED', 'FRAUD_CONFIRMED_SUPPRESSED')),
    constraint chk_marketplace_reviews_overall check (overall_rating between 1 and 5),
    constraint chk_marketplace_reviews_accuracy check (accuracy_rating is null or accuracy_rating between 1 and 5),
    constraint chk_marketplace_reviews_reliability check (reliability_rating is null or reliability_rating between 1 and 5),
    constraint chk_marketplace_reviews_communication check (communication_rating is null or communication_rating between 1 and 5),
    constraint chk_marketplace_reviews_punctuality check (punctuality_rating is null or punctuality_rating between 1 and 5),
    constraint chk_marketplace_reviews_evidence_quality check (evidence_quality_rating is null or evidence_quality_rating between 1 and 5),
    constraint chk_marketplace_reviews_match check (item_service_match_rating is null or item_service_match_rating between 1 and 5)
);

create table reputation_snapshots (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    trust_score int not null,
    trust_confidence int not null,
    trust_tier text not null,
    risk_level text not null,
    review_score int not null default 0,
    completion_rate int not null default 0,
    cancellation_penalty int not null default 0,
    no_show_penalty int not null default 0,
    dispute_penalty int not null default 0,
    evidence_reliability int not null default 0,
    profile_quality int not null default 0,
    strengths_json jsonb not null default '[]'::jsonb,
    penalties_json jsonb not null default '[]'::jsonb,
    contributing_signals_json jsonb not null default '{}'::jsonb,
    policy_version text not null,
    created_at timestamptz not null default now()
);

create table reputation_recalculation_events (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    previous_score int,
    new_score int not null,
    previous_tier text,
    new_tier text not null,
    policy_version text not null,
    reason text not null,
    contributing_signals_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table risk_decisions (
    id uuid primary key,
    target_type text not null,
    target_id uuid not null,
    score int not null,
    risk_level text not null,
    decision text not null,
    reasons_json jsonb not null default '[]'::jsonb,
    snapshot_json jsonb not null default '{}'::jsonb,
    policy_version text not null,
    created_at timestamptz not null default now(),
    constraint chk_risk_decisions_target check (target_type in ('PARTICIPANT', 'LISTING', 'TRANSACTION', 'REVIEW', 'DISPUTE', 'EVIDENCE')),
    constraint chk_risk_decisions_level check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_risk_decisions_decision check (decision in (
        'ALLOW', 'ALLOW_WITH_LIMITS', 'REQUIRE_EXTRA_EVIDENCE', 'REQUIRE_VERIFICATION',
        'REQUIRE_MANUAL_REVIEW', 'HIDE_LISTING', 'BLOCK_TRANSACTION', 'RESTRICT_CAPABILITY',
        'SUSPEND_ACCOUNT', 'SUPPRESS_REVIEW_WEIGHT'
    ))
);

create table off_platform_contact_reports (
    id uuid primary key,
    transaction_id uuid references marketplace_transactions(id),
    reporter_participant_id uuid not null references participants(id),
    reported_participant_id uuid not null references participants(id),
    report_text text not null,
    status text not null default 'OPEN',
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    constraint chk_off_platform_reports_status check (status in ('OPEN', 'REVIEWED', 'ACTION_RECOMMENDED', 'DISMISSED')),
    constraint chk_off_platform_reports_text check (length(btrim(report_text)) > 0)
);

create table synthetic_risk_signals (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    signal_type text not null,
    signal_hash text not null,
    risk_weight int not null default 0,
    source text not null,
    retention_until timestamptz,
    created_at timestamptz not null default now(),
    constraint chk_synthetic_risk_signal_type check (signal_type in ('DEVICE_HASH_SIMULATED', 'REGION_HASH_SIMULATED', 'ACCOUNT_CLUSTER_SIMULATED')),
    constraint chk_synthetic_risk_signal_hash check (length(btrim(signal_hash)) > 0),
    constraint chk_synthetic_risk_signal_source check (length(btrim(source)) > 0)
);

create table ranking_decision_logs (
    id uuid primary key,
    query_text text,
    filters_json jsonb not null default '{}'::jsonb,
    policy_version text not null,
    candidate_ids_json jsonb not null default '[]'::jsonb,
    result_ids_json jsonb not null default '[]'::jsonb,
    scores_json jsonb not null default '{}'::jsonb,
    reasons_json jsonb not null default '{}'::jsonb,
    trust_risk_snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_ranking_policy_version check (policy_version in ('trust_balanced_v1', 'risk_averse_v1', 'new_user_fairness_v1'))
);

alter table marketplace_events drop constraint if exists chk_marketplace_events_v3_type;
alter table marketplace_events add constraint chk_marketplace_events_v4_type check (event_type in (
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
    'RANKING_DECISION_LOGGED', 'RANKING_REPLAYED'
));

create index idx_marketplace_evidence_target on marketplace_evidence(target_type, target_id);
create index idx_marketplace_evidence_type on marketplace_evidence(evidence_type);
create index idx_evidence_requirements_target_satisfied on evidence_requirements(target_type, target_id, satisfied);
create index idx_marketplace_disputes_transaction on marketplace_disputes(transaction_id);
create index idx_marketplace_disputes_status on marketplace_disputes(status);
create index idx_marketplace_disputes_type on marketplace_disputes(dispute_type);
create index idx_dispute_statements_dispute on dispute_statements(dispute_id);
create index idx_dispute_evidence_deadlines_dispute_status on dispute_evidence_deadlines(dispute_id, status);
create index idx_marketplace_reviews_transaction on marketplace_reviews(transaction_id);
create index idx_marketplace_reviews_reviewer on marketplace_reviews(reviewer_participant_id);
create index idx_marketplace_reviews_reviewed on marketplace_reviews(reviewed_participant_id);
create index idx_marketplace_reviews_status on marketplace_reviews(status);
create index idx_reputation_snapshots_participant_created on reputation_snapshots(participant_id, created_at);
create index idx_reputation_recalculation_events_participant_created on reputation_recalculation_events(participant_id, created_at);
create index idx_risk_decisions_target_created on risk_decisions(target_type, target_id, created_at);
create index idx_risk_decisions_level_decision on risk_decisions(risk_level, decision);
create index idx_off_platform_contact_reports_reported_status on off_platform_contact_reports(reported_participant_id, status);
create index idx_synthetic_risk_signals_participant_type on synthetic_risk_signals(participant_id, signal_type);
create index idx_synthetic_risk_signals_hash on synthetic_risk_signals(signal_hash);
create index idx_ranking_decision_logs_policy_created on ranking_decision_logs(policy_version, created_at);
