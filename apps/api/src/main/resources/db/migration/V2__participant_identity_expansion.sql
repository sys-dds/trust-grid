alter table if exists participant_capabilities rename to foundation_participant_capabilities;
alter table if exists trust_profiles rename to foundation_trust_profiles;
alter table if exists marketplace_events rename to foundation_marketplace_events;

create table participants (
    id uuid primary key,
    profile_slug text not null unique,
    display_name text not null,
    account_status text not null,
    verification_status text not null,
    trust_tier text not null,
    risk_level text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_participants_profile_slug_not_blank check (length(btrim(profile_slug)) > 0),
    constraint chk_participants_display_name_not_blank check (length(btrim(display_name)) > 0),
    constraint chk_participants_account_status check (account_status in ('ACTIVE', 'LIMITED', 'RESTRICTED', 'SUSPENDED', 'CLOSED')),
    constraint chk_participants_verification_status check (verification_status in ('UNVERIFIED', 'BASIC', 'VERIFIED', 'ENHANCED', 'REJECTED')),
    constraint chk_participants_trust_tier check (trust_tier in ('NEW', 'LIMITED', 'STANDARD', 'TRUSTED', 'HIGH_TRUST', 'RESTRICTED', 'SUSPENDED')),
    constraint chk_participants_risk_level check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table participant_profiles (
    participant_id uuid primary key references participants(id),
    bio text,
    location_summary text,
    capability_description text,
    profile_photo_object_key text,
    profile_completeness_score int not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_participant_profiles_score check (profile_completeness_score between 0 and 100)
);

create table participant_capabilities (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    capability text not null,
    status text not null,
    granted_by text,
    grant_reason text,
    revoked_by text,
    revoke_reason text,
    restricted_by text,
    restrict_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (participant_id, capability),
    constraint chk_participant_capabilities_v2_capability check (capability in ('BUY', 'SELL_ITEMS', 'OFFER_SERVICES', 'ACCEPT_ERRANDS', 'ACCEPT_SHOPPING_REQUESTS')),
    constraint chk_participant_capabilities_v2_status check (status in ('ACTIVE', 'REVOKED', 'RESTRICTED'))
);

create table participant_verification_history (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    old_status text,
    new_status text not null,
    actor text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_participant_verification_history_new_status check (new_status in ('UNVERIFIED', 'BASIC', 'VERIFIED', 'ENHANCED', 'REJECTED')),
    constraint chk_participant_verification_history_actor check (length(btrim(actor)) > 0),
    constraint chk_participant_verification_history_reason check (length(btrim(reason)) > 0)
);

create table participant_status_history (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    old_status text,
    new_status text not null,
    actor text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_participant_status_history_new_status check (new_status in ('ACTIVE', 'LIMITED', 'RESTRICTED', 'SUSPENDED', 'CLOSED')),
    constraint chk_participant_status_history_actor check (length(btrim(actor)) > 0),
    constraint chk_participant_status_history_reason check (length(btrim(reason)) > 0)
);

create table participant_restrictions (
    id uuid primary key,
    participant_id uuid not null references participants(id),
    restriction_type text not null,
    status text not null,
    max_transaction_value_cents bigint,
    actor text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    removed_at timestamptz,
    removed_by text,
    remove_reason text,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_participant_restrictions_type check (restriction_type in ('LISTING_BLOCKED', 'ACCEPTING_BLOCKED', 'MAX_TRANSACTION_VALUE', 'REQUIRES_MANUAL_REVIEW', 'REQUIRES_VERIFICATION', 'HIDDEN_FROM_MARKETPLACE_SEARCH', 'REVIEW_WEIGHT_SUPPRESSED')),
    constraint chk_participant_restrictions_status check (status in ('ACTIVE', 'REMOVED')),
    constraint chk_participant_restrictions_actor check (length(btrim(actor)) > 0),
    constraint chk_participant_restrictions_reason check (length(btrim(reason)) > 0),
    constraint chk_participant_restrictions_max_value check (max_transaction_value_cents is null or max_transaction_value_cents >= 0)
);

create table trust_profiles (
    participant_id uuid primary key references participants(id),
    trust_score int not null default 500,
    trust_confidence int not null default 0,
    trust_tier text not null default 'NEW',
    risk_level text not null default 'LOW',
    max_transaction_value_cents bigint not null default 0,
    restriction_flags_json jsonb not null default '{}'::jsonb,
    signals_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_trust_profiles_v2_score check (trust_score between 0 and 1000),
    constraint chk_trust_profiles_v2_confidence check (trust_confidence between 0 and 100),
    constraint chk_trust_profiles_v2_tier check (trust_tier in ('NEW', 'LIMITED', 'STANDARD', 'TRUSTED', 'HIGH_TRUST', 'RESTRICTED', 'SUSPENDED')),
    constraint chk_trust_profiles_v2_risk check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_trust_profiles_v2_max_value check (max_transaction_value_cents >= 0)
);

create table marketplace_events (
    id uuid primary key,
    aggregate_type text not null,
    aggregate_id uuid not null,
    participant_id uuid,
    event_key text not null unique,
    event_type text not null,
    event_status text not null default 'PENDING',
    payload_json jsonb not null default '{}'::jsonb,
    publish_attempts int not null default 0,
    published_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    constraint chk_marketplace_events_v2_status check (event_status in ('PENDING', 'PUBLISHED', 'FAILED')),
    constraint chk_marketplace_events_v2_type check (event_type in ('PARTICIPANT_CREATED', 'PROFILE_UPDATED', 'CAPABILITY_GRANTED', 'CAPABILITY_REVOKED', 'CAPABILITY_RESTRICTED', 'VERIFICATION_STATUS_UPDATED', 'ACCOUNT_STATUS_UPDATED', 'RESTRICTION_APPLIED', 'RESTRICTION_REMOVED', 'TRUST_PROFILE_INITIALIZED', 'TRUST_PROFILE_UPDATED')),
    constraint chk_marketplace_events_v2_key check (length(btrim(event_key)) > 0)
);

create table idempotency_records (
    id uuid primary key,
    scope text not null,
    idempotency_key text not null,
    request_hash text not null,
    resource_type text not null,
    resource_id uuid not null,
    created_at timestamptz not null default now(),
    unique (scope, idempotency_key),
    constraint chk_idempotency_records_scope check (length(btrim(scope)) > 0),
    constraint chk_idempotency_records_key check (length(btrim(idempotency_key)) > 0)
);

create index idx_participants_profile_slug on participants(profile_slug);
create index idx_participants_account_status on participants(account_status);
create index idx_participants_verification_status on participants(verification_status);
create index idx_participants_trust_tier on participants(trust_tier);
create index idx_participant_capabilities_participant_capability_v2 on participant_capabilities(participant_id, capability);
create index idx_participant_capabilities_capability_status on participant_capabilities(capability, status);
create index idx_participant_restrictions_participant_status on participant_restrictions(participant_id, status);
create index idx_marketplace_events_aggregate_v2 on marketplace_events(aggregate_type, aggregate_id, created_at);
create index idx_marketplace_events_participant_created_at on marketplace_events(participant_id, created_at);
create index idx_marketplace_events_status_created_at on marketplace_events(event_status, created_at);
create index idx_idempotency_records_scope_key on idempotency_records(scope, idempotency_key);
