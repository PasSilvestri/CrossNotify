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
}
