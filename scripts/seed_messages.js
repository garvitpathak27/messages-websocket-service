// Deterministic seed for Step 18: 100,000 messages in 4 fixed rooms. Room A contains a 500-message
// cluster sharing ONE createdAt millisecond. Re-running replaces the previous seed.
// Remove with: db.messages.deleteMany({ roomId: { $regex: '^5eed' } })
const ROOMS = [
  { id: '5eed0000-0000-4000-8000-00000000000a', count: 70000 },
  { id: '5eed0000-0000-4000-8000-00000000000b', count: 20000 },
  { id: '5eed0000-0000-4000-8000-00000000000c', count: 9000 },
  { id: '5eed0000-0000-4000-8000-00000000000d', count: 1000 },
];
const BASE = Date.UTC(2026, 0, 1);   // 2026-01-01T00:00:00.000Z
const STEP_MS = 1000;                // one message per second per room
const CLUSTER_ROOM = ROOMS[0].id;
const CLUSTER_AT = 35000;            // room A messages 35000..35499 share one millisecond
const CLUSTER_SIZE = 500;
const BATCH = 5000;

const started = Date.now();
const removed = db.messages.deleteMany({ roomId: { $in: ROOMS.map(r => r.id) } }).deletedCount;
let seq = 0;
for (const room of ROOMS) {
  let batch = [];
  for (let i = 0; i < room.count; i++) {
    const inCluster = room.id === CLUSTER_ROOM && i >= CLUSTER_AT && i < CLUSTER_AT + CLUSTER_SIZE;
    seq++;
    batch.push({
      _id: ObjectId('5eed' + seq.toString(16).padStart(20, '0')),
      roomId: room.id,
      senderId: 'usr_seed_' + (seq % 25),
      content: 'seed message ' + seq,
      type: 'TEXT',
      clientMessageId: '00000000-0000-4000-8000-' + seq.toString().padStart(12, '0'),
      createdAt: new Date(inCluster ? BASE + CLUSTER_AT * STEP_MS : BASE + i * STEP_MS),
    });
    if (batch.length === BATCH) {
      db.messages.insertMany(batch, { ordered: false });
      batch = [];
    }
  }
  if (batch.length) db.messages.insertMany(batch, { ordered: false });
}
const clusterAt = new Date(BASE + CLUSTER_AT * STEP_MS);
print(`removed ${removed} old seed docs; inserted ${seq} in ${((Date.now() - started) / 1000).toFixed(1)}s`);
print(`cluster: ${db.messages.countDocuments({ roomId: CLUSTER_ROOM, createdAt: clusterAt })} docs at ${clusterAt.toISOString()}`);
