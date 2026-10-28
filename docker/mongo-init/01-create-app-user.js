// Runs ONCE, only when /data/db is empty (docker-entrypoint-initdb.d semantics).
// Editing this file after the volume exists has no effect: remove the volume to re-run it.
// The entrypoint runs it with mongosh, already authenticated as the root user.

const dbName = process.env.MONGO_INITDB_DATABASE;
const user = process.env.MONGO_APP_USERNAME;
const pwd = process.env.MONGO_APP_PASSWORD;

if (!dbName || !user || !pwd) {
  throw new Error('MONGO_INITDB_DATABASE, MONGO_APP_USERNAME and MONGO_APP_PASSWORD must be set');
}

// The user is CREATED IN dbName, so its authSource is dbName (not admin).
const appDb = db.getSiblingDB(dbName);
appDb.createUser({
  user: user,
  pwd: pwd,
  roles: [{ role: 'readWrite', db: dbName }],
});

print(`created user ${user} in ${dbName} with readWrite on ${dbName}`);
