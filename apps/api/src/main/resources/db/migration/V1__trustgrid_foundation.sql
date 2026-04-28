create table marketplace_participants (
    id uuid primary key,
    display_name text not null,
    profile_slug text not null unique,
    account_status text not null,
    verification_status text not null,
    profile_quality_score numeric(5,2) not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_marketplace_participants_display_name_not_blank check (length(btrim(display_name)) > 0),
    constraint chk_marketplace_participants_profile_slug_not_blank check (length(btrim(profile_slug)) > 0),
    constraint chk_marketplace_participants_account_status check (account_status in ('ACTIVE', 'LIMITED', 'RESTRICTED', 'SUSPENDED', 'CLOSED')),
    constraint chk_marketplace_participants_verification_status check (verification_status in ('UNVERIFIED', 'BASIC', 'VERIFIED', 'ENHANCED', 'REJECTED')),
    constraint chk_marketplace_participants_profile_quality_score check (profile_quality_score between 0 and 100)
);

create table participant_capabilities (
    id uuid primary key,
    participant_id uuid not null references marketplace_participants(id),
    capability text not null,
    status text not null,
    granted_at timestamptz not null default now(),
    revoked_at timestamptz,
    reason text not null,
    unique (participant_id, capability),
    constraint chk_participant_capabilities_capability check (capability in ('BUY', 'SELL_ITEMS', 'OFFER_SERVICES', 'ACCEPT_ERRANDS', 'ACCEPT_SHOPPING_REQUESTS')),
    constraint chk_participant_capabilities_status check (status in ('ACTIVE', 'REVOKED', 'RESTRICTED')),
    constraint chk_participant_capabilities_reason_not_blank check (length(btrim(reason)) > 0)
);

create table trust_profiles (
    id uuid primary key,
    participant_id uuid not null unique references marketplace_participants(id),
    trust_tier text not null,
    risk_level text not null,
    trust_score numeric(6,2) not null default 0,
    trust_confidence text not null,
    max_transaction_value_cents bigint not null default 0,
    listing_blocked boolean not null default false,
    accepting_blocked boolean not null default false,
    requires_manual_review boolean not null default false,
    requires_verification boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_trust_profiles_trust_tier check (trust_tier in ('NEW', 'LIMITED', 'STANDARD', 'TRUSTED', 'HIGH_TRUST', 'RESTRICTED', 'SUSPENDED')),
    constraint chk_trust_profiles_risk_level check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_trust_profiles_trust_confidence check (trust_confidence in ('LOW', 'MEDIUM', 'HIGH')),
    constraint chk_trust_profiles_trust_score check (trust_score between 0 and 100),
    constraint chk_trust_profiles_max_transaction_value_cents check (max_transaction_value_cents >= 0)
);

create table marketplace_events (
    id uuid primary key,
    aggregate_type text not null,
    aggregate_id uuid not null,
    event_type text not null,
    actor text not null,
    reason text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint chk_marketplace_events_aggregate_type check (aggregate_type in ('PARTICIPANT', 'TRUST_PROFILE', 'CAPABILITY', 'SYSTEM')),
    constraint chk_marketplace_events_event_type check (event_type in ('PARTICIPANT_CREATED', 'TRUST_PROFILE_CREATED', 'CAPABILITY_GRANTED', 'CAPABILITY_REVOKED', 'CAPABILITY_RESTRICTED', 'VERIFICATION_STATUS_CHANGED', 'ACCOUNT_STATUS_CHANGED', 'RISK_DECISION_RECORDED', 'FOUNDATION_SEED_CREATED')),
    constraint chk_marketplace_events_actor_not_blank check (length(btrim(actor)) > 0),
    constraint chk_marketplace_events_reason_not_blank check (length(btrim(reason)) > 0)
);

create index idx_marketplace_participants_profile_slug on marketplace_participants(profile_slug);
create index idx_participant_capabilities_participant_capability on participant_capabilities(participant_id, capability);
create index idx_trust_profiles_participant_id on trust_profiles(participant_id);
create index idx_marketplace_events_aggregate on marketplace_events(aggregate_type, aggregate_id, created_at);
create index idx_marketplace_events_type_created_at on marketplace_events(event_type, created_at);
