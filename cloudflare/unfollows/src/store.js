// Tiny helpers over the D1 `state` table (key/value), shared by the webhook, the reader and the kick processor.

export async function getState(db, key) {
  const row = await db.prepare("SELECT value FROM state WHERE key = ?").bind(key).first();
  return row ? row.value : null;
}

export async function setState(db, key, value) {
  await db
    .prepare("INSERT INTO state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
    .bind(key, String(value))
    .run();
}

export async function bump(db, key) {
  await db
    .prepare("INSERT INTO state(key, value) VALUES(?, '1') ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1")
    .bind(key)
    .run();
  return Number(await getState(db, key));
}

/** A global change counter: every new or changed kick row takes the next number, and the app asks for "everything after N". */
export const nextVersion = (db) => bump(db, "version");

export async function clearState(db, key) {
  await db.prepare("DELETE FROM state WHERE key = ?").bind(key).run();
}
