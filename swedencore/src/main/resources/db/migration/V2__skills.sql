-- V2: skill system.
-- `skills` is reference data so other tables (job positions, contracts) can reference skills with a foreign key.
-- Level curve and max level are configuration, not schema; `level` is stored denormalised for queries/leaderboards
-- and always recomputed from `xp` by the application.

CREATE TABLE skills (
    id         TEXT PRIMARY KEY,
    sort_order INT NOT NULL
);

INSERT INTO skills (id, sort_order) VALUES
    ('MINING', 1),
    ('FARMING', 2),
    ('HERBALISM', 3),
    ('BUILDING', 4),
    ('FORESTRY', 5),
    ('FISHING', 6),
    ('ENGINEERING', 7),
    ('LOGISTICS', 8);

CREATE TABLE skill_progress (
    player_uuid UUID        NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    skill_id    TEXT        NOT NULL REFERENCES skills (id),
    xp          BIGINT      NOT NULL DEFAULT 0,
    level       INT         NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, skill_id),
    CONSTRAINT skill_progress_xp_chk CHECK (xp >= 0),
    CONSTRAINT skill_progress_level_chk CHECK (level >= 1)
);

CREATE INDEX skill_progress_leaderboard_idx ON skill_progress (skill_id, xp DESC);
