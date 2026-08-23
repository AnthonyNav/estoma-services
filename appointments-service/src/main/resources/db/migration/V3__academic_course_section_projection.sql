CREATE TABLE course_section_projection (
    course_section_id uuid PRIMARY KEY,
    academic_period_id uuid NOT NULL,
    status varchar(40) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_course_section_projection_version CHECK (source_version > 0)
);

CREATE INDEX ix_course_section_projection_period_status
    ON course_section_projection (academic_period_id, status);
