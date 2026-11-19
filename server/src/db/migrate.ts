import type Database from 'better-sqlite3';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export function runMigrations(db: Database.Database): void {
  // Ensure schema_migrations exists
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version     INTEGER PRIMARY KEY,
      applied_at  INTEGER NOT NULL
    );
  `);

  const appliedRows = db.prepare('SELECT version FROM schema_migrations').all() as { version: number }[];
  const appliedSet = new Set(appliedRows.map((r) => r.version));

  // Migration 1
  if (!appliedSet.has(1)) {
    // Try to read 001_initial.sql from possible paths
    let sql: string | null = null;
    const candidatePaths = [
      path.join(__dirname, 'migrations', '001_initial.sql'),
      path.join(process.cwd(), 'src', 'db', 'migrations', '001_initial.sql'),
      path.join(process.cwd(), 'migrations', '001_initial.sql'),
    ];

    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        sql = fs.readFileSync(p, 'utf8');
        break;
      }
    }

    if (!sql) {
      throw new Error('Migration file 001_initial.sql not found in candidate paths');
    }

    const applyMigration1 = db.transaction(() => {
      db.exec(sql);
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(1, Date.now());
    });

    applyMigration1();
    console.log('[db] Applied migration 001_initial.sql');
  }

  // Migration 2
  if (!appliedSet.has(2)) {
    let sql: string | null = null;
    const candidatePaths = [
      path.join(__dirname, 'migrations', '002_relay_fcm_token.sql'),
      path.join(process.cwd(), 'src', 'db', 'migrations', '002_relay_fcm_token.sql'),
      path.join(process.cwd(), 'migrations', '002_relay_fcm_token.sql'),
    ];

    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        sql = fs.readFileSync(p, 'utf8');
        break;
      }
    }

    if (!sql) {
      sql = `
        ALTER TABLE channels ADD COLUMN fcm_token TEXT;
        CREATE INDEX IF NOT EXISTS idx_channels_fcm_token ON channels(fcm_token);
      `;
    }

    const applyMigration2 = db.transaction(() => {
      db.exec(sql!);
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(2, Date.now());
    });

    applyMigration2();
    console.log('[db] Applied migration 002_relay_fcm_token.sql');
  }

  // Migration 3
  if (!appliedSet.has(3)) {
    let sql: string | null = null;
    const candidatePaths = [
      path.join(__dirname, 'migrations', '003_subscriber_silences.sql'),
      path.join(process.cwd(), 'src', 'db', 'migrations', '003_subscriber_silences.sql'),
      path.join(process.cwd(), 'migrations', '003_subscriber_silences.sql'),
    ];

    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        sql = fs.readFileSync(p, 'utf8');
        break;
      }
    }

    if (!sql) {
      sql = `
        CREATE TABLE IF NOT EXISTS subscriber_silences (
          id             INTEGER PRIMARY KEY AUTOINCREMENT,
          subscriber_id  TEXT NOT NULL REFERENCES subscribers(subscriber_id) ON DELETE CASCADE,
          channel_id     TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
          app_name       TEXT NOT NULL DEFAULT '',
          silenced_until INTEGER NOT NULL,
          created_at     INTEGER NOT NULL,
          UNIQUE(subscriber_id, app_name)
        );
        CREATE INDEX IF NOT EXISTS idx_subscriber_silences_sub ON subscriber_silences(subscriber_id);
        CREATE INDEX IF NOT EXISTS idx_subscriber_silences_until ON subscriber_silences(silenced_until);
      `;
    }

    const applyMigration3 = db.transaction(() => {
      db.exec(sql!);
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(3, Date.now());
    });

    applyMigration3();
    console.log('[db] Applied migration 003_subscriber_silences.sql');
  }

  // Migration 4
  if (!appliedSet.has(4)) {
    let sql: string | null = null;
    const candidatePaths = [
      path.join(__dirname, 'migrations', '004_actions_and_handled.sql'),
      path.join(process.cwd(), 'src', 'db', 'migrations', '004_actions_and_handled.sql'),
      path.join(process.cwd(), 'migrations', '004_actions_and_handled.sql'),
    ];

    for (const p of candidatePaths) {
      if (fs.existsSync(p)) {
        sql = fs.readFileSync(p, 'utf8');
        break;
      }
    }

    if (!sql) {
      sql = `
        ALTER TABLE subscribers ADD COLUMN allow_actions INTEGER NOT NULL DEFAULT 1;
        ALTER TABLE notifications ADD COLUMN handled_at INTEGER;
        ALTER TABLE notifications ADD COLUMN handled_reason TEXT;
        ALTER TABLE notifications ADD COLUMN handled_by TEXT;
        CREATE INDEX IF NOT EXISTS idx_notifications_handled ON notifications(channel_id, handled_at);
      `;
    }

    const applyMigration4 = db.transaction(() => {
      db.exec(sql!);
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(4, Date.now());
    });

    applyMigration4();
    console.log('[db] Applied migration 004_actions_and_handled.sql');
  }
}
