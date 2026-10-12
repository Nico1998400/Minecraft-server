-- V18: essentials — player homes and server warps (plain coordinates, no economy involved).

CREATE TABLE homes (
    player_uuid UUID             NOT NULL REFERENCES players (uuid),
    name        TEXT             NOT NULL,
    world       TEXT             NOT NULL,
    x           DOUBLE PRECISION NOT NULL,
    y           DOUBLE PRECISION NOT NULL,
    z           DOUBLE PRECISION NOT NULL,
    yaw         REAL             NOT NULL,
    pitch       REAL             NOT NULL,
    created_at  TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (player_uuid, name),
    CONSTRAINT homes_name_chk CHECK (name ~ '^[a-z0-9_-]{1,16}$')
);

CREATE TABLE warps (
    name       TEXT             PRIMARY KEY,
    world      TEXT             NOT NULL,
    x          DOUBLE PRECISION NOT NULL,
    y          DOUBLE PRECISION NOT NULL,
    z          DOUBLE PRECISION NOT NULL,
    yaw        REAL             NOT NULL,
    pitch      REAL             NOT NULL,
    created_by UUID             REFERENCES players (uuid),
    created_at TIMESTAMPTZ      NOT NULL,
    CONSTRAINT warps_name_chk CHECK (name ~ '^[a-z0-9_-]{1,24}$')
);
