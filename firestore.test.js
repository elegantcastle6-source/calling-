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

// --- 7. SHARED TWO-USER CHAT ROOMS (/chats/{chatRoomId}/messages/{messageId}) ---

function buildChatRoomId(userA, userB) {
  return [userA.trim().toLowerCase(), userB.trim().toLowerCase()]
    .sort()
    .join("_")
    .replace(/[^a-zA-Z0-9_]/g, "_");
}

function validSharedMessagePayload(senderUid, senderEmail, receiverEmail, text, ts = pastTimestamp()) {
  const normSender = senderEmail.trim().toLowerCase();
  const normReceiver = receiverEmail.trim().toLowerCase();
  const roomId = buildChatRoomId(normSender, normReceiver);
  return {
    chatRoomId: roomId,
    senderUid,
    senderEmail: normSender,
    receiverEmail: normReceiver,
    participantEmails: [normSender, normReceiver].sort(),
    text,
    status: "SENT",
    encrypted: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

test("SharedChats: two-user real-time messaging succeeds; non-participants, spoofing, room squatting & unfiltered list fail", async () => {
  const CHARLIE_UID = "charlie_uid_789";
  const aliceEmail = "alice@gmail.com";
  const bobEmail = "bob@gmail.com";
  const charlieEmail = "charlie@gmail.com";
  const roomId = buildChatRoomId(aliceEmail, bobEmail);

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
  const charlieDb = testEnv
    .authenticatedContext(CHARLIE_UID, { email: charlieEmail, email_verified: true })
    .firestore();

  const roomMessages = (db) => db.collection("chats").doc(roomId).collection("messages");

  // 1. Unauthenticated & unverified users are strictly denied
  await assertFails(
    roomMessages(unauthDb).where("participantEmails", "array-contains", aliceEmail).get()
  );
  await assertFails(
    roomMessages(unauthDb)
      .doc("msg_unauth")
      .set(validSharedMessagePayload(ALICE_UID, aliceEmail, bobEmail, "Unauthorized"))
  );
  await assertFails(
    roomMessages(unverifiedAliceDb)
      .doc("msg_unverified")
      .set(validSharedMessagePayload(ALICE_UID, aliceEmail, bobEmail, "Unverified"))
  );

  // 2. Empty room filtered query succeeds for participant; unfiltered list fails (Query Enforcer)
  await assertSucceeds(
    roomMessages(aliceDb).where("participantEmails", "array-contains", aliceEmail).get()
  );
  await assertFails(roomMessages(aliceDb).get());
  await assertFails(roomMessages(charlieDb).get());

  // 3. Alice sends message to Bob in shared room
  await assertSucceeds(
    roomMessages(aliceDb)
      .doc("msg_alice_1")
      .set(validSharedMessagePayload(ALICE_UID, aliceEmail, bobEmail, "Hey Bob!"))
  );

  // 4. Bob lists & reads Alice's message using Kotlin-mirrored array-contains query
  const bobSnapshot = await assertSucceeds(
    roomMessages(bobDb).where("participantEmails", "array-contains", bobEmail).get()
  );
  if (bobSnapshot.size !== 1) {
    throw new Error(`Expected Bob to see 1 message, got ${bobSnapshot.size}`);
  }
  await assertSucceeds(roomMessages(bobDb).doc("msg_alice_1").get());

  // 5. Bob replies in the exact same room; Alice sees both messages
  await assertSucceeds(
    roomMessages(bobDb)
      .doc("msg_bob_1")
      .set(validSharedMessagePayload(BOB_UID, bobEmail, aliceEmail, "Hi Alice!"))
  );
  const aliceSnapshot = await assertSucceeds(
    roomMessages(aliceDb).where("participantEmails", "array-contains", aliceEmail).get()
  );
  if (aliceSnapshot.size !== 2) {
    throw new Error(`Expected Alice to see 2 messages, got ${aliceSnapshot.size}`);
  }

  // 6. Both participants may update reactions; receiver may mark READ (terminal lock afterward)
  await assertSucceeds(
    roomMessages(bobDb).doc("msg_alice_1").update({
      reactions: { "👍": 1 },
      updatedAt: pastTimestamp(1500),
    })
  );
  await assertSucceeds(
    roomMessages(bobDb).doc("msg_alice_1").update({
      status: "READ",
      updatedAt: pastTimestamp(1000),
    })
  );
  // Terminal state lock: once READ, status cannot be changed again
  await assertFails(
    roomMessages(bobDb).doc("msg_alice_1").update({
      status: "DELIVERED",
      updatedAt: pastTimestamp(500),
    })
  );
  // Sender cannot change own message's status (receiver-only action)
  await assertFails(
    roomMessages(aliceDb).doc("msg_alice_1").update({
      status: "DELIVERED",
      updatedAt: pastTimestamp(500),
    })
  );

  // 7. Reject immutable field mutation (text, senderEmail, participantEmails, createdAt) & ghost keys
  await assertFails(
    roomMessages(aliceDb).doc("msg_alice_1").update({
      text: "Edited message",
      updatedAt: pastTimestamp(500),
    })
  );
  await assertFails(
    roomMessages(aliceDb).doc("msg_alice_1").update({
      createdAt: pastTimestamp(500),
      updatedAt: pastTimestamp(500),
    })
  );
  await assertFails(
    roomMessages(aliceDb).doc("msg_alice_1").update({
      isAdmin: true,
      updatedAt: pastTimestamp(500),
    })
  );

  // 8. Non-participant Charlie cannot read, list, update, delete, or inject/squat in Alice+Bob's room
  await assertFails(roomMessages(charlieDb).doc("msg_alice_1").get());
  await assertFails(
    roomMessages(charlieDb).where("participantEmails", "array-contains", aliceEmail).get()
  );
  await assertFails(
    roomMessages(charlieDb).doc("msg_alice_1").update({
      reactions: { "🔥": 1 },
      updatedAt: pastTimestamp(500),
    })
  );
  await assertFails(roomMessages(charlieDb).doc("msg_alice_1").delete());
  // Room-ID parity blocks Charlie from injecting a Charlie+Alice payload into Alice+Bob's roomId
  await assertFails(
    roomMessages(charlieDb)
      .doc("msg_charlie_squat")
      .set({
        ...validSharedMessagePayload(CHARLIE_UID, charlieEmail, aliceEmail, "Room squat"),
        chatRoomId: roomId,
      })
  );

  // 9. Reject spoofing senderUid, spoofing senderEmail, redundant `id` key, oversized text, and future timestamps
  await assertFails(
    roomMessages(bobDb)
      .doc("msg_spoof_uid")
      .set(validSharedMessagePayload(ALICE_UID, bobEmail, aliceEmail, "Wrong UID"))
  );
  await assertFails(
    roomMessages(bobDb)
      .doc("msg_spoof_email")
      .set(validSharedMessagePayload(BOB_UID, aliceEmail, bobEmail, "Wrong email"))
  );
  await assertFails(
    roomMessages(aliceDb)
      .doc("msg_extra_id")
      .set({
        id: "msg_extra_id",
        ...validSharedMessagePayload(ALICE_UID, aliceEmail, bobEmail, "Extra id"),
      })
  );
  await assertFails(
    roomMessages(aliceDb)
      .doc("msg_oversized")
      .set(validSharedMessagePayload(ALICE_UID, aliceEmail, bobEmail, "x".repeat(2001)))
  );
  await assertFails(
    roomMessages(aliceDb)
      .doc("msg_future_ts")
      .set(
        validSharedMessagePayload(
          ALICE_UID,
          aliceEmail,
          bobEmail,
          "Future",
          new Date(Date.now() + 60000)
        )
      )
  );

  // 10. Delete: recipient cannot delete sender's message; sender can delete own message
  await assertFails(roomMessages(bobDb).doc("msg_alice_1").delete());
  await assertSucceeds(roomMessages(aliceDb).doc("msg_alice_1").delete());
});

// --- 8. SHARED TWO-USER CALL SIGNALING (/calls/{chatRoomId}) ---

function validCallInvitePayload(
  callerUid,
  callerEmail,
  callerName,
  calleeEmail,
  status = "calling",
  ts = pastTimestamp()
) {
  const normCaller = callerEmail.trim().toLowerCase();
  const normCallee = calleeEmail.trim().toLowerCase();
  const roomId = buildChatRoomId(normCaller, normCallee);
  const channelName = `call_${roomId}`.slice(0, 64);
  return {
    chatRoomId: roomId,
    channelName,
    callerUid,
    caller: normCaller,
    callerName,
    callee: normCallee,
    participantEmails: [normCaller, normCallee].sort(),
    callType: "AUDIO",
    status,
    createdAt: ts,
    updatedAt: ts,
  };
}

test("CallSignaling: full calling -> accepted -> ended -> re-call lifecycle succeeds; unauthorized access & state shortcutting fail", async () => {
  const CHARLIE_UID = "charlie_uid_789";
  const aliceEmail = "alice@gmail.com";
  const bobEmail = "bob@gmail.com";
  const charlieEmail = "charlie@gmail.com";
  const roomId = buildChatRoomId(aliceEmail, bobEmail);

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
  const charlieDb = testEnv
    .authenticatedContext(CHARLIE_UID, { email: charlieEmail, email_verified: true })
    .firestore();

  const callDoc = (db) => db.collection("calls").doc(roomId);

  // 1. Unauthenticated & unverified users denied
  await assertFails(callDoc(unauthDb).get());
  await assertFails(
    callDoc(unauthDb).set(validCallInvitePayload(ALICE_UID, aliceEmail, "Alice", bobEmail))
  );
  await assertFails(
    callDoc(unverifiedAliceDb).set(validCallInvitePayload(ALICE_UID, aliceEmail, "Alice", bobEmail))
  );

  // 2. Non-existent room get succeeds for participants, fails for non-participant Charlie
  await assertSucceeds(callDoc(aliceDb).get());
  await assertSucceeds(callDoc(bobDb).get());
  await assertFails(callDoc(charlieDb).get());

  // 3. Incoming call list query alignment: filtered array-contains succeeds, unfiltered list fails
  await assertSucceeds(
    bobDb.collection("calls").where("participantEmails", "array-contains", bobEmail).get()
  );
  await assertFails(bobDb.collection("calls").get());
  await assertFails(charlieDb.collection("calls").get());

  // 4. Reject creating a call directly in "accepted" or "ended" state (State Shortcutting)
  await assertFails(
    callDoc(aliceDb).set(
      validCallInvitePayload(ALICE_UID, aliceEmail, "Alice", bobEmail, "accepted")
    )
  );
  // Reject spoofing callerUid or caller email or redundant `id` key
  await assertFails(
    callDoc(aliceDb).set(validCallInvitePayload(BOB_UID, aliceEmail, "Alice", bobEmail))
  );
  await assertFails(
    callDoc(charlieDb).set({
      ...validCallInvitePayload(CHARLIE_UID, charlieEmail, "Charlie", aliceEmail),
      chatRoomId: roomId,
    })
  );
  await assertFails(
    callDoc(aliceDb).set({
      id: roomId,
      ...validCallInvitePayload(ALICE_UID, aliceEmail, "Alice", bobEmail),
    })
  );

  // 5. Alice starts a call to Bob -> succeeds
  await assertSucceeds(
    callDoc(aliceDb).set(validCallInvitePayload(ALICE_UID, aliceEmail, "Alice", bobEmail))
  );

  // 6. Bob sees the incoming call via filtered query and direct get; Charlie is denied both
  const bobIncoming = await assertSucceeds(
    bobDb.collection("calls").where("participantEmails", "array-contains", bobEmail).get()
  );
  if (bobIncoming.size !== 1) {
    throw new Error(`Expected Bob to observe 1 active call invite, got ${bobIncoming.size}`);
  }
  await assertSucceeds(callDoc(bobDb).get());
  await assertFails(callDoc(charlieDb).get());
  await assertFails(
    charlieDb.collection("calls").where("participantEmails", "array-contains", aliceEmail).get()
  );

  // 7. Caller Alice cannot self-accept; Callee Bob accepts -> succeeds
  await assertFails(
    callDoc(aliceDb).update({
      status: "accepted",
      updatedAt: pastTimestamp(2000),
    })
  );
  await assertSucceeds(
    callDoc(bobDb).update({
      status: "accepted",
      updatedAt: pastTimestamp(1800),
    })
  );

  // 8. Cannot delete active call; cannot tamper with channelName or ghost field
  await assertFails(callDoc(aliceDb).delete());
  await assertFails(
    callDoc(bobDb).update({
      channelName: "call_hijacked",
      updatedAt: pastTimestamp(1500),
    })
  );
  await assertFails(
    callDoc(bobDb).update({
      isAdmin: true,
      updatedAt: pastTimestamp(1500),
    })
  );

  // 9. Charlie cannot end Alice+Bob's call; Alice ends the call -> succeeds
  await assertFails(
    callDoc(charlieDb).update({
      status: "ended",
      updatedAt: pastTimestamp(1200),
    })
  );
  await assertSucceeds(
    callDoc(aliceDb).update({
      status: "ended",
      updatedAt: pastTimestamp(1000),
    })
  );

  // 10. Ended call cannot jump straight to "accepted" (Terminal State Lock)
  await assertFails(
    callDoc(bobDb).update({
      status: "accepted",
      updatedAt: pastTimestamp(800),
    })
  );

  // 11. Re-call: Bob calls Alice back in the same room (preserving createdAt!) -> succeeds
  await assertSucceeds(
    callDoc(bobDb).update({
      channelName: `call_${roomId}`,
      callerUid: BOB_UID,
      caller: bobEmail,
      callerName: "Bob",
      callee: aliceEmail,
      callType: "AUDIO",
      status: "calling",
      updatedAt: pastTimestamp(600),
    })
  );

  // Reject mutating createdAt during re-call
  await assertSucceeds(
    callDoc(aliceDb).update({
      status: "ended",
      updatedAt: pastTimestamp(400),
    })
  );
  await assertFails(
    callDoc(bobDb).update({
      channelName: `call_${roomId}`,
      callerUid: BOB_UID,
      caller: bobEmail,
      callerName: "Bob",
      callee: aliceEmail,
      callType: "AUDIO",
      status: "calling",
      createdAt: pastTimestamp(300),
      updatedAt: pastTimestamp(300),
    })
  );

  // 12. Ended call doc can be deleted by a participant, never by Charlie
  await assertFails(callDoc(charlieDb).delete());
  await assertSucceeds(callDoc(aliceDb).delete());
});


