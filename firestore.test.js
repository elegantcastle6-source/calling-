const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const crypto = require("node:crypto");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const CONTACT_ID = "contact_abc123";
const MESSAGE_ID = "msg_001";
const CALL_LOG_ID = "call_001";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp(msAgo = 5000) {
  return new Date(Date.now() - msAgo);
}

function sha256Email(email) {
  return crypto
    .createHash("sha256")
    .update(email.trim().toLowerCase(), "utf8")
    .digest("hex");
}

function validUserProfilePayload(uid, ts = pastTimestamp()) {
  return {
    userId: uid,
    displayName: "Alice Johnson",
    email: "alice@gmail.com",
    statusMessage: "Using Just Chat",
    lowDataCallsEnabled: true,
    lowLatencyEnabled: true,
    totalDataSavedMb: 0.0,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validContactPayload(uid, ts = pastTimestamp()) {
  return {
    userId: uid,
    name: "Marcus Vance",
    gmail: "marcus.vance@gmail.com",
    avatarColorHex: 0xFF0EA5E9,
    statusMessage: "Connected via Gmail",
    online: true,
    lastSeenText: "Active now",
    securityFingerprint: "12345  67890  11223  44556  48192  91024",
    createdAt: ts,
    updatedAt: ts,
  };
}

function validMessagePayload(uid, contactId, ts = pastTimestamp()) {
  return {
    userId: uid,
    contactId: contactId,
    senderId: "current_user",
    receiverId: contactId,
    text: "Hello from Just Chat!",
    status: "SENT",
    encrypted: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validCallLogPayload(uid, contactId, ts = pastTimestamp()) {
  return {
    userId: uid,
    contactId: contactId,
    contactName: "Marcus Vance",
    contactGmail: "marcus.vance@gmail.com",
    contactAvatarColorHex: 0xFF0EA5E9,
    callType: "VIDEO",
    incoming: false,
    missed: false,
    durationSeconds: 120,
    dataUsedKb: 5040,
    lowDataMode: true,
    encrypted: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- 1. UNAUTHENTICATED ACCESS REJECTION ---

test("Unauthenticated user: denied read/write across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(validUserProfilePayload(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("contacts").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .set(validContactPayload(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("callLogs").get()
  );
});

// --- 2. USER PROFILE RULES & PII ISOLATION ---

test("UserProfile: owner can create, get, and partially update settings; cross-user and list denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validUserProfilePayload(ALICE_UID))
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Partial settings update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      lowDataCallsEnabled: false,
      updatedAt: pastTimestamp(1000),
    })
  );

  // Reject ghost/shadow field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastTimestamp(500),
    })
  );

  // Reject createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: pastTimestamp(500),
      updatedAt: pastTimestamp(500),
    })
  );

  // Reject future timestamp
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set(validUserProfilePayload(BOB_UID, new Date(Date.now() + 60000)))
  );

  // Cross-user PII read & write denied; user enumeration (list) denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());
  await assertFails(aliceDb.collection("users").get());
});

// --- 3. CONTACTS RULES & KOTLIN PAYLOAD PARITY ---

test("Contacts: owner CRUD and list succeed; invalid payloads and cross-user access fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Empty subcollection list must succeed for owner
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").get()
  );

  // Valid create (no @DocumentId `id` field)
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .set(validContactPayload(ALICE_UID))
  );

  // Reject create containing redundant `id` key
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc("contact_extra_id")
      .set({
        id: "contact_extra_id",
        ...validContactPayload(ALICE_UID),
      })
  );

  // Reject spoofing userId
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc("contact_spoof")
      .set(validContactPayload(BOB_UID))
  );

  // Valid partial update
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .update({
        statusMessage: "Available now",
        updatedAt: pastTimestamp(1000),
      })
  );

  // Cross-user list, get, update, delete denied
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("contacts").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .delete()
  );
});

// --- 4. MESSAGES RULES, PARENT GATE & PARTIAL REACTION UPDATES ---

test("Messages: require existing parent contact, allow reaction updates, reject oversized text & cross-user", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Orphaned message without parent contact must fail
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc("missing_contact")
      .collection("messages")
      .doc(MESSAGE_ID)
      .set(validMessagePayload(ALICE_UID, "missing_contact"))
  );

  // Create parent contact first
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .set(validContactPayload(ALICE_UID))
  );

  // Valid message create
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .collection("messages")
      .doc(MESSAGE_ID)
      .set(validMessagePayload(ALICE_UID, CONTACT_ID))
  );

  // Owner can list messages in contact subcollection
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .collection("messages")
      .get()
  );

  // Owner can update reactions only
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .collection("messages")
      .doc(MESSAGE_ID)
      .update({
        reactions: { "👍": 1 },
        updatedAt: pastTimestamp(1000),
      })
  );

  // Reject mutating message text on update
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .collection("messages")
      .doc(MESSAGE_ID)
      .update({
        text: "Tampered text",
        updatedAt: pastTimestamp(500),
      })
  );

  // Reject oversized text (> 2000 chars)
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .collection("messages")
      .doc("msg_oversized")
      .set({
        ...validMessagePayload(ALICE_UID, CONTACT_ID),
        text: "a".repeat(2001),
      })
  );

  // Cross-user access denied
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("contacts")
      .doc(CONTACT_ID)
      .collection("messages")
      .get()
  );
});

// --- 5. CALL LOGS IMMUTABILITY & ISOLATION ---

test("CallLogs: owner can create, list, delete; updates rejected; cross-user denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("callLogs")
      .doc(CALL_LOG_ID)
      .set(validCallLogPayload(ALICE_UID, CONTACT_ID))
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("callLogs").get()
  );

  // Call logs are immutable
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("callLogs")
      .doc(CALL_LOG_ID)
      .update({
        durationSeconds: 999,
        updatedAt: pastTimestamp(500),
      })
  );

  // Invalid callType enum rejected
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("callLogs")
      .doc("call_bad_enum")
      .set({
        ...validCallLogPayload(ALICE_UID, CONTACT_ID),
        callType: "video",
      })
  );

  // Cross-user denied
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("callLogs").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("callLogs")
      .doc(CALL_LOG_ID)
      .get()
  );

  // Owner delete succeeds
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("callLogs")
      .doc(CALL_LOG_ID)
      .delete()
  );
});

// --- 6. USER DIRECTORY EXACT-MATCH GMAIL LOOKUP & ZERO-ENUMERATION ---

test("UserDirectory: verified owner registers hash, peers lookup by exact hash, spoofing/squatting/list denied", async () => {
  const aliceEmail = "alice@gmail.com";
  const bobEmail = "bob@gmail.com";
  const aliceHash = sha256Email(aliceEmail);
  const bobHash = sha256Email(bobEmail);

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: aliceEmail, email_verified: true })
    .firestore();
  const unverifiedAliceDb = testEnv
    .authenticatedContext(ALICE_UID, { email: aliceEmail, email_verified: false })
    .firestore();
  const bobDb = testEnv
    .authenticatedContext(BOB_UID, { email: bobEmail, email_verified: true })
    .firestore();

  const ts = pastTimestamp(10000);
  const validEntry = {
    uid: ALICE_UID,
    displayName: "Alice Johnson",
    createdAt: ts,
    updatedAt: ts,
  };

  // Unauthenticated access denied
  await assertFails(unauthDb.collection("userDirectory").doc(aliceHash).get());
  await assertFails(unauthDb.collection("userDirectory").doc(aliceHash).set(validEntry));

  // Unverified email token rejected (Email Spoofing Test)
  await assertFails(
    unverifiedAliceDb.collection("userDirectory").doc(aliceHash).set(validEntry)
  );

  // Bob cannot squat on Alice's email hash
  await assertFails(
    bobDb.collection("userDirectory").doc(aliceHash).set({
      uid: BOB_UID,
      displayName: "Bob Impostor",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject storing raw PII (email field) or ghost fields
  await assertFails(
    aliceDb.collection("userDirectory").doc(aliceHash).set({
      ...validEntry,
      email: aliceEmail,
    })
  );

  // Verified Alice creates her own directory entry
  await assertSucceeds(
    aliceDb.collection("userDirectory").doc(aliceHash).set(validEntry)
  );

  // Signed-in Bob can look up Alice by exact SHA-256(email)
  await assertSucceeds(bobDb.collection("userDirectory").doc(aliceHash).get());

  // Enumeration / list is strictly denied for everyone
  await assertFails(bobDb.collection("userDirectory").get());
  await assertFails(aliceDb.collection("userDirectory").get());
  await assertFails(
    bobDb.collection("userDirectory").where("uid", "==", ALICE_UID).get()
  );

  // Alice can partially update her displayName while preserving createdAt
  await assertSucceeds(
    aliceDb.collection("userDirectory").doc(aliceHash).update({
      displayName: "Alice J.",
      updatedAt: pastTimestamp(1000),
    })
  );

  // Reject mutating createdAt or uid
  await assertFails(
    aliceDb.collection("userDirectory").doc(aliceHash).update({
      createdAt: pastTimestamp(500),
      updatedAt: pastTimestamp(500),
    })
  );
  await assertFails(
    bobDb.collection("userDirectory").doc(aliceHash).update({
      displayName: "Hijacked",
      updatedAt: pastTimestamp(500),
    })
  );

  // Non-owner cannot delete; owner can delete
  await assertFails(bobDb.collection("userDirectory").doc(aliceHash).delete());
  await assertSucceeds(aliceDb.collection("userDirectory").doc(aliceHash).delete());

  // Invalid non-hex doc ID rejected
  await assertFails(bobDb.collection("userDirectory").doc(bobEmail).get());
  await assertFails(
    bobDb.collection("userDirectory").doc(bobHash).set({
      uid: ALICE_UID, // uid mismatch
      displayName: "Bob",
      createdAt: ts,
      updatedAt: ts,
    })
  );
});
