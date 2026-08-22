CREATE TABLE candidate_profile (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    professional_display_name varchar(120) NOT NULL,
    professional_headline varchar(160),
    career_summary varchar(2000),
    location_preference varchar(160),
    target_roles varchar(1000),
    work_authorization_statement varchar(500),
    work_location_preferences varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_candidate_profile_owner UNIQUE (owner_account_id),
    CONSTRAINT fk_candidate_profile_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_candidate_profile_display_name_nonblank CHECK (btrim(professional_display_name) <> ''),
    CONSTRAINT ck_candidate_profile_headline_nonblank CHECK (professional_headline IS NULL OR btrim(professional_headline) <> ''),
    CONSTRAINT ck_candidate_profile_summary_nonblank CHECK (career_summary IS NULL OR btrim(career_summary) <> ''),
    CONSTRAINT ck_candidate_profile_location_nonblank CHECK (location_preference IS NULL OR btrim(location_preference) <> ''),
    CONSTRAINT ck_candidate_profile_target_roles_nonblank CHECK (target_roles IS NULL OR btrim(target_roles) <> ''),
    CONSTRAINT ck_candidate_profile_work_auth_nonblank CHECK (work_authorization_statement IS NULL OR btrim(work_authorization_statement) <> ''),
    CONSTRAINT ck_candidate_profile_work_location_nonblank CHECK (work_location_preferences IS NULL OR btrim(work_location_preferences) <> ''),
    CONSTRAINT ck_candidate_profile_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_candidate_profile_version CHECK (version >= 0)
);

CREATE TABLE career_fact (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    category varchar(24) NOT NULL,
    status varchar(16) NOT NULL,
    factual_content varchar(2000) NOT NULL,
    organization varchar(200),
    title varchar(200),
    location varchar(160),
    started_on date,
    ended_on date,
    ongoing boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_career_fact_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_career_fact_category
        CHECK (category IN ('EMPLOYMENT', 'SKILL', 'EDUCATION', 'CERTIFICATION', 'PROJECT', 'ACCOMPLISHMENT')),
    CONSTRAINT ck_career_fact_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'ARCHIVED')),
    CONSTRAINT ck_career_fact_content_nonblank CHECK (btrim(factual_content) <> ''),
    CONSTRAINT ck_career_fact_organization_nonblank CHECK (organization IS NULL OR btrim(organization) <> ''),
    CONSTRAINT ck_career_fact_title_nonblank CHECK (title IS NULL OR btrim(title) <> ''),
    CONSTRAINT ck_career_fact_location_nonblank CHECK (location IS NULL OR btrim(location) <> ''),
    CONSTRAINT ck_career_fact_dates
        CHECK ((started_on IS NULL AND ended_on IS NULL)
            OR (started_on IS NOT NULL AND ended_on IS NULL)
            OR (started_on IS NOT NULL AND ended_on >= started_on)),
    CONSTRAINT ck_career_fact_ongoing CHECK (NOT ongoing OR ended_on IS NULL),
    CONSTRAINT ck_career_fact_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_career_fact_version CHECK (version >= 0)
);

CREATE INDEX candidate_profile_owner_ix
    ON candidate_profile (owner_account_id, id);

CREATE INDEX career_fact_owner_status_category_ix
    ON career_fact (owner_account_id, status, category, id);

CREATE INDEX career_fact_owner_category_ix
    ON career_fact (owner_account_id, category, id);

CREATE FUNCTION reject_owner_account_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_account_id IS DISTINCT FROM OLD.owner_account_id THEN
        RAISE EXCEPTION 'owner_account_id is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER candidate_profile_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON candidate_profile
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

CREATE TRIGGER career_fact_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON career_fact
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

COMMENT ON TABLE candidate_profile IS
    'One owner-scoped candidate profile per account. Owner is server-derived and immutable in application code.';
COMMENT ON COLUMN candidate_profile.owner_account_id IS
    'Immutable owner account UUID from identity::actor; browser-supplied owner identifiers are never trusted.';
COMMENT ON TABLE career_fact IS
    'Owner-scoped structured career facts. CONFIRMED means account-owner attested, not third-party verified.';
COMMENT ON COLUMN career_fact.status IS
    'DRAFT is ineligible for generated content; CONFIRMED is owner-attested; ARCHIVED is retained history only.';
