CREATE TABLE appointment_qr_projection (
    appointment_id UUID PRIMARY KEY,
    qr_representation TEXT,
    qr_lookup_hash BYTEA,
    appointment_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT appointment_qr_version_ck CHECK (appointment_version > 0),
    CONSTRAINT appointment_qr_shape_ck CHECK (
      (
        qr_representation IS NULL
        AND qr_lookup_hash IS NULL
      )
      OR
      (
        qr_representation IS NOT NULL
        AND char_length(qr_representation) BETWEEN 1 AND 2000
        AND qr_lookup_hash IS NOT NULL
        AND octet_length(qr_lookup_hash) = 32
      )
    )
);

CREATE UNIQUE INDEX appointment_qr_lookup_hash_uq
  ON appointment_qr_projection(qr_lookup_hash)
  WHERE qr_lookup_hash IS NOT NULL;
