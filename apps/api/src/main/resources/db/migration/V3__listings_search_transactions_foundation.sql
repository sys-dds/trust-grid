create table marketplace_categories (
    id uuid primary key,
    code text not null unique,
    name text not null,
    description text not null,
    default_risk_tier text not null,
    allowed_listing_types_json jsonb not null default '[]'::jsonb,
    evidence_requirement_hint text,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_marketplace_categories_code check (length(btrim(code)) > 0),
    constraint chk_marketplace_categories_name check (length(btrim(name)) > 0),
    constraint chk_marketplace_categories_description check (length(btrim(description)) > 0),
    constraint chk_marketplace_categories_risk check (default_risk_tier in ('LOW', 'MEDIUM', 'HIGH', 'RESTRICTED'))
);

create table marketplace_listings (
    id uuid primary key,
    owner_participant_id uuid not null references participants(id),
    listing_type text not null,
    category_id uuid not null references marketplace_categories(id),
    title text not null,
    description text not null,
    price_amount_cents bigint,
    budget_amount_cents bigint,
    currency text not null default 'GBP',
    location_mode text not null,
    status text not null,
    risk_tier text not null,
    moderation_status text not null,
    single_accept boolean not null default true,
    revision int not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    submitted_at timestamptz,
    published_at timestamptz,
    hidden_at timestamptz,
    rejected_at timestamptz,
    expired_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_marketplace_listings_title check (length(btrim(title)) > 0),
    constraint chk_marketplace_listings_description check (length(btrim(description)) > 0),
    constraint chk_marketplace_listings_revision check (revision >= 1),
    constraint chk_marketplace_listings_price check (price_amount_cents is null or price_amount_cents >= 0),
    constraint chk_marketplace_listings_budget check (budget_amount_cents is null or budget_amount_cents >= 0),
    constraint chk_marketplace_listings_type check (listing_type in ('SERVICE_OFFER', 'ITEM_LISTING', 'ERRAND_REQUEST', 'SHOPPING_REQUEST')),
    constraint chk_marketplace_listings_location check (location_mode in ('REMOTE', 'LOCAL', 'SHIPPING', 'LOCAL_PICKUP', 'IN_PERSON', 'HYBRID')),
    constraint chk_marketplace_listings_status check (status in ('DRAFT', 'PENDING_RISK_CHECK', 'LIVE', 'UNDER_REVIEW', 'HIDDEN', 'REJECTED', 'EXPIRED')),
    constraint chk_marketplace_listings_moderation check (moderation_status in ('NOT_REVIEWED', 'AUTO_APPROVED', 'NEEDS_REVIEW', 'EVIDENCE_REQUIRED', 'MODERATOR_HIDDEN', 'MODERATOR_REJECTED', 'RESTORED')),
    constraint chk_marketplace_listings_risk check (risk_tier in ('LOW', 'MEDIUM', 'HIGH', 'RESTRICTED'))
);

create table listing_service_details (
    listing_id uuid primary key references marketplace_listings(id),
    pricing_model text not null,
    remote_allowed boolean not null default false,
    in_person_allowed boolean not null default false,
    service_duration_minutes int,
    trial_allowed boolean not null default false,
    cancellation_policy text,
    availability_summary text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_listing_service_details_pricing check (pricing_model in ('HOURLY', 'FIXED')),
    constraint chk_listing_service_details_duration check (service_duration_minutes is null or service_duration_minutes > 0)
);

create table listing_item_details (
    listing_id uuid primary key references marketplace_listings(id),
    item_condition text not null,
    brand text,
    high_value boolean not null default false,
    shipping_allowed boolean not null default false,
    local_pickup_allowed boolean not null default true,
    proof_photo_required boolean not null default false,
    ownership_proof_required boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_listing_item_details_condition check (item_condition in ('NEW', 'LIKE_NEW', 'GOOD', 'FAIR', 'POOR', 'FOR_PARTS'))
);

create table listing_errand_details (
    listing_id uuid primary key references marketplace_listings(id),
    pickup_summary text,
    dropoff_summary text,
    deadline_at timestamptz,
    proof_required boolean not null default false,
    local_only boolean not null default true,
    safety_category text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table listing_shopping_request_details (
    listing_id uuid primary key references marketplace_listings(id),
    target_item_description text not null,
    target_shop_source text,
    buyer_budget_cents bigint not null,
    shopper_reward_cents bigint not null,
    receipt_required boolean not null default true,
    delivery_proof_required boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_listing_shopping_target check (length(btrim(target_item_description)) > 0),
    constraint chk_listing_shopping_budget check (buyer_budget_cents >= 0),
    constraint chk_listing_shopping_reward check (shopper_reward_cents >= 0)
);

create table listing_revisions (
    id uuid primary key,
    listing_id uuid not null references marketplace_listings(id),
    revision int not null,
    changed_by text not null,
    reason text not null,
    snapshot_json jsonb not null,
    created_at timestamptz not null default now(),
    unique (listing_id, revision),
    constraint chk_listing_revisions_actor check (length(btrim(changed_by)) > 0),
    constraint chk_listing_revisions_reason check (length(btrim(reason)) > 0),
    constraint chk_listing_revisions_revision check (revision >= 1)
);

create table listing_evidence_requirements (
    id uuid primary key,
    listing_id uuid not null references marketplace_listings(id),
    evidence_type text not null,
    required_before_publish boolean not null default false,
    satisfied boolean not null default false,
    reason text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_listing_evidence_type check (evidence_type in ('LISTING_PHOTO', 'OWNERSHIP_PROOF', 'RECEIPT_PLACEHOLDER', 'DELIVERY_PROOF_PLACEHOLDER', 'USER_STATEMENT_PLACEHOLDER')),
    constraint chk_listing_evidence_reason check (length(btrim(reason)) > 0)
);

create table listing_risk_snapshots (
    id uuid primary key,
    listing_id uuid not null references marketplace_listings(id),
    risk_tier text not null,
    decision text not null,
    matched_rules_json jsonb not null default '[]'::jsonb,
    actor text not null,
    snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_listing_risk_snapshots_tier check (risk_tier in ('LOW', 'MEDIUM', 'HIGH', 'RESTRICTED')),
    constraint chk_listing_risk_snapshots_decision check (decision in ('ALLOW_LIVE', 'SEND_UNDER_REVIEW', 'REQUIRE_EVIDENCE', 'BLOCK_PUBLISH', 'HIDE_LISTING')),
    constraint chk_listing_risk_snapshots_actor check (length(btrim(actor)) > 0)
);

create table duplicate_listing_findings (
    id uuid primary key,
    listing_id uuid not null references marketplace_listings(id),
    finding_type text not null,
    severity text not null,
    status text not null,
    matched_listing_ids_json jsonb not null default '[]'::jsonb,
    reason text not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    constraint chk_duplicate_listing_type check (finding_type in ('SAME_OWNER_TITLE_PRICE', 'SAME_TITLE_HASH_MANY_NEW_USERS', 'REPEATED_HIGH_VALUE_CATEGORY_NEW_USER')),
    constraint chk_duplicate_listing_severity check (severity in ('LOW', 'MEDIUM', 'HIGH')),
    constraint chk_duplicate_listing_status check (status in ('OPEN', 'RESOLVED')),
    constraint chk_duplicate_listing_reason check (length(btrim(reason)) > 0)
);

create table listing_search_documents (
    listing_id uuid primary key references marketplace_listings(id),
    owner_participant_id uuid not null,
    listing_type text not null,
    category_code text not null,
    title text not null,
    description text not null,
    price_amount_cents bigint,
    budget_amount_cents bigint,
    location_mode text not null,
    status text not null,
    risk_tier text not null,
    searchable boolean not null default false,
    search_backend_status text not null default 'POSTGRES_FALLBACK',
    indexed_at timestamptz,
    document_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    constraint chk_listing_search_backend check (search_backend_status in ('OPENSEARCH', 'POSTGRES_FALLBACK', 'DEGRADED'))
);

create table marketplace_transactions (
    id uuid primary key,
    listing_id uuid not null references marketplace_listings(id),
    transaction_type text not null,
    requester_participant_id uuid not null references participants(id),
    provider_participant_id uuid references participants(id),
    status text not null,
    value_amount_cents bigint not null,
    currency text not null default 'GBP',
    risk_status text not null default 'NOT_CHECKED',
    idempotency_key text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    accepted_at timestamptz,
    started_at timestamptz,
    shipped_at timestamptz,
    delivered_at timestamptz,
    proof_placeholder_at timestamptz,
    completion_claimed_at timestamptz,
    confirmed_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    no_show_reported_at timestamptz,
    disputed_at timestamptz,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_marketplace_transactions_type check (transaction_type in ('SERVICE_BOOKING', 'ITEM_PURCHASE', 'ERRAND', 'SHOPPING_REQUEST')),
    constraint chk_marketplace_transactions_risk check (risk_status in ('NOT_CHECKED', 'ALLOWED', 'ALLOWED_WITH_LIMITS', 'BLOCKED', 'REQUIRES_REVIEW')),
    constraint chk_marketplace_transactions_value check (value_amount_cents > 0),
    constraint chk_marketplace_transactions_status check (status in (
        'REQUESTED', 'ACCEPTED', 'SCHEDULED', 'IN_PROGRESS', 'COMPLETION_CLAIMED', 'BUYER_CONFIRMED',
        'COMPLETED', 'CANCELLED', 'NO_SHOW_REPORTED', 'DISPUTED', 'PURCHASED', 'SHIPPED', 'DELIVERED',
        'CONFIRMATION_WINDOW_OPEN', 'RETURN_REQUESTED', 'POSTED', 'PROOF_UPLOADED', 'REQUESTER_CONFIRMED',
        'ACCEPTED_BY_SHOPPER', 'PURCHASE_PROOF_UPLOADED', 'DELIVERY_PROOF_UPLOADED'
    ))
);

create table transaction_deadlines (
    id uuid primary key,
    transaction_id uuid not null references marketplace_transactions(id),
    deadline_type text not null,
    due_at timestamptz not null,
    status text not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    satisfied_at timestamptz,
    cancelled_at timestamptz,
    constraint chk_transaction_deadlines_type check (deadline_type in ('ACCEPT_DEADLINE', 'SERVICE_START_DEADLINE', 'PURCHASE_PROOF_DEADLINE', 'DELIVERY_PROOF_DEADLINE', 'BUYER_CONFIRMATION_WINDOW', 'DISPUTE_WINDOW')),
    constraint chk_transaction_deadlines_status check (status in ('ACTIVE', 'SATISFIED', 'CANCELLED', 'EXPIRED'))
);

create table transaction_timeline_events (
    id uuid primary key,
    transaction_id uuid not null references marketplace_transactions(id),
    event_type text not null,
    actor_participant_id uuid,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_transaction_timeline_actor check (length(btrim(actor)) > 0),
    constraint chk_transaction_timeline_reason check (length(btrim(reason)) > 0)
);

create table transaction_risk_snapshots (
    id uuid primary key,
    transaction_id uuid references marketplace_transactions(id),
    listing_id uuid not null references marketplace_listings(id),
    requester_participant_id uuid not null references participants(id),
    provider_participant_id uuid references participants(id),
    decision text not null,
    matched_rules_json jsonb not null default '[]'::jsonb,
    snapshot_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_transaction_risk_decision check (decision in ('ALLOW', 'ALLOW_WITH_LIMITS', 'BLOCK_TRANSACTION', 'REQUIRE_MANUAL_REVIEW'))
);

create table transaction_invariant_checks (
    id uuid primary key,
    transaction_id uuid references marketplace_transactions(id),
    check_name text not null,
    status text not null,
    message text not null,
    created_at timestamptz not null default now(),
    constraint chk_transaction_invariant_status check (status in ('PASS', 'FAIL')),
    constraint chk_transaction_invariant_check check (length(btrim(check_name)) > 0),
    constraint chk_transaction_invariant_message check (length(btrim(message)) > 0)
);

alter table marketplace_events drop constraint if exists chk_marketplace_events_v2_type;
alter table marketplace_events add constraint chk_marketplace_events_v3_type check (event_type in (
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
    'TRANSACTION_DEADLINE_SATISFIED', 'TRANSACTION_DEADLINE_CANCELLED', 'TRANSACTION_INVARIANT_CHECK_RUN'
));

insert into marketplace_categories (id, code, name, description, default_risk_tier, allowed_listing_types_json, evidence_requirement_hint)
values
    ('00000000-0000-0000-0000-000000000001', 'TUTORING', 'Tutoring', 'Educational support and tutoring services', 'LOW', '["SERVICE_OFFER"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000002', 'COACHING', 'Coaching', 'Coaching and mentoring service offers', 'LOW', '["SERVICE_OFFER"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000003', 'REPAIRS', 'Repairs', 'Repair help and local repair requests', 'MEDIUM', '["SERVICE_OFFER","ERRAND_REQUEST"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000004', 'ERRANDS', 'Errands', 'Local errand requests', 'MEDIUM', '["ERRAND_REQUEST"]'::jsonb, 'Proof placeholder may be required'),
    ('00000000-0000-0000-0000-000000000005', 'SHOPPING', 'Shopping', 'Shopping request support', 'MEDIUM', '["SHOPPING_REQUEST"]'::jsonb, 'Receipt and delivery proof placeholders may be required'),
    ('00000000-0000-0000-0000-000000000006', 'ELECTRONICS', 'Electronics', 'Electronics item listings and shopping requests', 'HIGH', '["ITEM_LISTING","SHOPPING_REQUEST"]'::jsonb, 'Photo and ownership proof placeholders may be required'),
    ('00000000-0000-0000-0000-000000000007', 'CLOTHING', 'Clothing', 'Clothing item listings and shopping requests', 'LOW', '["ITEM_LISTING","SHOPPING_REQUEST"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000008', 'COLLECTIBLES', 'Collectibles', 'Collectible item listings', 'MEDIUM', '["ITEM_LISTING"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000009', 'LOCAL_HELP', 'Local Help', 'Local help services and errands', 'MEDIUM', '["SERVICE_OFFER","ERRAND_REQUEST"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000010', 'HOUSEHOLD', 'Household', 'Household items, services, and errands', 'MEDIUM', '["ITEM_LISTING","SERVICE_OFFER","ERRAND_REQUEST"]'::jsonb, null),
    ('00000000-0000-0000-0000-000000000011', 'DELIVERY_PICKUP', 'Delivery Pickup', 'Delivery and pickup requests', 'MEDIUM', '["ERRAND_REQUEST","SHOPPING_REQUEST"]'::jsonb, 'Delivery proof placeholder may be required')
on conflict (code) do nothing;

create index idx_marketplace_categories_code on marketplace_categories(code);
create index idx_marketplace_categories_default_risk_tier on marketplace_categories(default_risk_tier);
create index idx_marketplace_listings_owner_participant_id on marketplace_listings(owner_participant_id);
create index idx_marketplace_listings_listing_type on marketplace_listings(listing_type);
create index idx_marketplace_listings_category_id on marketplace_listings(category_id);
create index idx_marketplace_listings_status on marketplace_listings(status);
create index idx_marketplace_listings_risk_tier on marketplace_listings(risk_tier);
create index idx_marketplace_listings_moderation_status on marketplace_listings(moderation_status);
create index idx_marketplace_listings_created_at on marketplace_listings(created_at);
create index idx_marketplace_listings_published_at on marketplace_listings(published_at);
create index idx_listing_evidence_requirements_listing_satisfied on listing_evidence_requirements(listing_id, satisfied);
create index idx_listing_risk_snapshots_listing_created_at on listing_risk_snapshots(listing_id, created_at);
create index idx_duplicate_listing_findings_listing_status on duplicate_listing_findings(listing_id, status);
create index idx_listing_search_documents_searchable_status on listing_search_documents(searchable, status);
create index idx_listing_search_documents_type_category on listing_search_documents(listing_type, category_code);
create index idx_listing_search_documents_location_mode on listing_search_documents(location_mode);
create index idx_listing_search_documents_risk_tier on listing_search_documents(risk_tier);
create index idx_marketplace_transactions_listing_id on marketplace_transactions(listing_id);
create index idx_marketplace_transactions_requester on marketplace_transactions(requester_participant_id);
create index idx_marketplace_transactions_provider on marketplace_transactions(provider_participant_id);
create index idx_marketplace_transactions_type on marketplace_transactions(transaction_type);
create index idx_marketplace_transactions_status on marketplace_transactions(status);
create index idx_marketplace_transactions_created_at on marketplace_transactions(created_at);
create index idx_transaction_deadlines_transaction_status on transaction_deadlines(transaction_id, status);
create index idx_transaction_timeline_events_transaction_created_at on transaction_timeline_events(transaction_id, created_at);
create index idx_transaction_risk_snapshots_transaction_created_at on transaction_risk_snapshots(transaction_id, created_at);
create index idx_transaction_invariant_checks_transaction_created_at on transaction_invariant_checks(transaction_id, created_at);
