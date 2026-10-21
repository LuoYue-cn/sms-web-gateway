CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    role          TEXT    NOT NULL DEFAULT 'admin',
    session_epoch INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL
);

CREATE TABLE devices (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name         TEXT    NOT NULL,
    android_version     TEXT    NOT NULL DEFAULT '',
    app_version         TEXT    NOT NULL DEFAULT '',
    battery_level       INTEGER,
    charging            INTEGER NOT NULL DEFAULT 0,
    network_type        TEXT    NOT NULL DEFAULT 'none',
    sim_count           INTEGER NOT NULL DEFAULT 0,
    sim_operator        TEXT    NOT NULL DEFAULT '',
    sims_json           TEXT    NOT NULL DEFAULT '[]',
    last_seen           INTEGER,
    sync_status         TEXT    NOT NULL DEFAULT 'never',
    keepalive_mode      TEXT    NOT NULL DEFAULT 'normal',
    watchdog_status     TEXT    NOT NULL DEFAULT 'not_installed',
    root_granted        INTEGER NOT NULL DEFAULT 0,
    battery_whitelisted INTEGER NOT NULL DEFAULT 0,
    last_sync_at        INTEGER,
    last_recovery_at    INTEGER,
    recovery_count      INTEGER NOT NULL DEFAULT 0,
    protection_state    TEXT    NOT NULL DEFAULT 'none',
    created_at          INTEGER NOT NULL,
    revoked_at          INTEGER
);

CREATE TABLE device_tokens (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash TEXT    NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER,
    revoked_at INTEGER
);
CREATE INDEX idx_device_tokens_device ON device_tokens(device_id);

CREATE TABLE pairing_tokens (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash TEXT    NOT NULL UNIQUE,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at    INTEGER,
    device_id  INTEGER REFERENCES devices(id) ON DELETE SET NULL
);

CREATE TABLE conversations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    phone_number    TEXT    NOT NULL,
    phone_key       TEXT    NOT NULL,
    contact_name    TEXT    NOT NULL DEFAULT '',
    last_message    TEXT    NOT NULL DEFAULT '',
    last_message_at INTEGER NOT NULL DEFAULT 0,
    last_direction  TEXT    NOT NULL DEFAULT 'incoming',
    unread_count    INTEGER NOT NULL DEFAULT 0,
    has_failed      INTEGER NOT NULL DEFAULT 0,
    pinned          INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    UNIQUE (device_id, phone_key)
);
CREATE INDEX idx_conversations_order ON conversations(pinned DESC, last_message_at DESC);

CREATE TABLE send_tasks (
    task_id         TEXT    PRIMARY KEY,
    device_id       INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recipient       TEXT    NOT NULL,
    content         TEXT    NOT NULL,
    sim_slot        INTEGER,
    status          TEXT    NOT NULL DEFAULT 'queued',
    error_code      TEXT,
    error_message   TEXT,
    created_by      INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at      INTEGER NOT NULL,
    claimed_at      INTEGER,
    updated_at      INTEGER NOT NULL,
    completed_at    INTEGER,
    resolved_action TEXT,
    requeue_count   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_tasks_device_status ON send_tasks(device_id, status);

CREATE TABLE messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    source_type     TEXT    NOT NULL,
    android_sms_id  INTEGER,
    source_key      TEXT,
    phone_number    TEXT    NOT NULL,
    contact_name    TEXT    NOT NULL DEFAULT '',
    content         TEXT    NOT NULL,
    direction       TEXT    NOT NULL,
    timestamp       INTEGER NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'received',
    sim_slot        INTEGER,
    task_id         TEXT    REFERENCES send_tasks(task_id) ON DELETE SET NULL,
    error_message   TEXT,
    is_read         INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_sms_source ON messages(device_id, source_type, android_sms_id) WHERE android_sms_id IS NOT NULL;
CREATE UNIQUE INDEX idx_sms_source_key ON messages(device_id, source_key) WHERE source_key IS NOT NULL;
CREATE INDEX idx_messages_conversation_time ON messages(conversation_id, timestamp);
CREATE INDEX idx_messages_phone ON messages(phone_number);
CREATE INDEX idx_messages_task ON messages(task_id);
CREATE INDEX idx_messages_device_dir_time ON messages(device_id, direction, timestamp);

CREATE TABLE sync_batches (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    batch_id    TEXT    NOT NULL,
    result_json TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    UNIQUE (device_id, batch_id)
);

CREATE TABLE sync_cursors (
    device_id            INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    state                TEXT    NOT NULL DEFAULT 'never',
    total                INTEGER NOT NULL DEFAULT 0,
    synced               INTEGER NOT NULL DEFAULT 0,
    last_android_sms_id  INTEGER NOT NULL DEFAULT 0,
    updated_at           INTEGER NOT NULL
);

CREATE TABLE device_heartbeats (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id           INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    ts                  INTEGER NOT NULL,
    battery_level       INTEGER,
    charging            INTEGER,
    network_type        TEXT,
    sync_status         TEXT,
    keepalive_mode      TEXT,
    watchdog_status     TEXT,
    root_granted        INTEGER,
    battery_whitelisted INTEGER,
    last_sync_at        INTEGER,
    last_recovery_at    INTEGER,
    recovery_count      INTEGER,
    protection_state    TEXT
);
CREATE INDEX idx_heartbeats_device_ts ON device_heartbeats(device_id, ts);

CREATE TABLE audit_logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         INTEGER NOT NULL,
    actor_type TEXT    NOT NULL,
    actor      TEXT    NOT NULL DEFAULT '',
    action     TEXT    NOT NULL,
    device_id  INTEGER,
    target     TEXT    NOT NULL DEFAULT '',
    detail     TEXT    NOT NULL DEFAULT '',
    ip         TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX idx_audit_ts ON audit_logs(ts);
CREATE INDEX idx_audit_action ON audit_logs(action);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE events (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      INTEGER NOT NULL,
    type    TEXT    NOT NULL,
    payload TEXT    NOT NULL
);

CREATE TABLE login_attempts (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    ts       INTEGER NOT NULL,
    username TEXT    NOT NULL,
    ip       TEXT    NOT NULL,
    success  INTEGER NOT NULL
);
CREATE INDEX idx_login_attempts ON login_attempts(username, ip, ts);

CREATE TABLE rate_limits (
    bucket   TEXT    NOT NULL,
    window   INTEGER NOT NULL,
    count    INTEGER NOT NULL,
    PRIMARY KEY (bucket, window)
);
