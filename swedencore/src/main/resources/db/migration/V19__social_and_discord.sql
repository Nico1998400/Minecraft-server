-- V19: nicknames, mutes, ignore lists and Discord account links.

CREATE TABLE player_nicks (
    player_uuid UUID        PRIMARY KEY REFERENCES players (uuid),
    nick        TEXT        NOT NULL,
    nick_lower  TEXT        NOT NULL UNIQUE,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT player_nicks_chk CHECK (length(nick) BETWEEN 3 AND 16)
);

CREATE TABLE player_mutes (
    player_uuid UUID        PRIMARY KEY REFERENCES players (uuid),
    until_at    TIMESTAMPTZ,
    reason      TEXT,
    muted_by    UUID        REFERENCES players (uuid),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE player_ignores (
    player_uuid  UUID        NOT NULL REFERENCES players (uuid),
    ignored_uuid UUID        NOT NULL REFERENCES players (uuid),
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (player_uuid, ignored_uuid),
    CONSTRAINT player_ignores_self_chk CHECK (player_uuid <> ignored_uuid)
);

CREATE TABLE discord_links (
    player_uuid UUID        PRIMARY KEY REFERENCES players (uuid),
    discord_id  TEXT        NOT NULL UNIQUE,
    linked_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT discord_links_id_chk CHECK (discord_id ~ '^[0-9]{5,25}$')
);
