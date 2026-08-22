CREATE TABLE base_resume_document (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    original_filename varchar(180) NOT NULL,
    media_type varchar(80) NOT NULL,
    byte_size bigint NOT NULL,
    sha256_checksum char(64) NOT NULL,
    storage_key varchar(96) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_base_resume_document_owner UNIQUE (owner_account_id),
    CONSTRAINT uq_base_resume_document_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_base_resume_document_owner
        FOREIGN KEY (owner_account_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_base_resume_document_filename_nonblank CHECK (btrim(original_filename) <> ''),
    CONSTRAINT ck_base_resume_document_media_type
        CHECK (media_type IN (
            'application/pdf',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        )),
    CONSTRAINT ck_base_resume_document_byte_size CHECK (byte_size BETWEEN 1 AND 5242880),
    CONSTRAINT ck_base_resume_document_sha256 CHECK (sha256_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_base_resume_document_storage_key_nonblank CHECK (btrim(storage_key) <> ''),
    CONSTRAINT ck_base_resume_document_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_base_resume_document_version CHECK (version >= 0)
);

CREATE INDEX base_resume_document_owner_ix
    ON base_resume_document (owner_account_id, id);

CREATE TRIGGER base_resume_document_owner_immutable_trg
    BEFORE UPDATE OF owner_account_id ON base_resume_document
    FOR EACH ROW
    EXECUTE FUNCTION reject_owner_account_change();

COMMENT ON TABLE base_resume_document IS
    'One owner-scoped current base resume source document per account. Binary content is stored outside PostgreSQL.';
COMMENT ON COLUMN base_resume_document.owner_account_id IS
    'Immutable owner account UUID from identity::actor; browser-supplied owner identifiers are never trusted.';
COMMENT ON COLUMN base_resume_document.storage_key IS
    'Opaque server-generated storage key. Never expose through API responses.';
