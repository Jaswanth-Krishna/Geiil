const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

// --- BLOCKED NUMBERS SECURITY & KOTLIN PAYLOAD PARITY ---

test("Unauthenticated user: denied read and write on blockedNumbers and transcripts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919876543210").set({
      userId: ALICE_UID,
      phoneNumber: "+919876543210",
      displayLabel: "Spam",
      reason: "Last caller",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("transcripts").get());
});

test("Authenticated owner: valid Indian (+91) blocked number and 6-digit room ID create, list, update, delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);

  // Indian 10-digit mobile number (+91 starting with 6-9)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919876543210").set({
      userId: ALICE_UID,
      phoneNumber: "+919876543210",
      displayLabel: "Unknown Spam",
      reason: "Blocked from Call Logs",
      createdAt: created,
      updatedAt: created,
    })
  );

  // 6-digit Room ID fallback
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("482910").set({
      userId: ALICE_UID,
      phoneNumber: "482910",
      createdAt: created,
      updatedAt: created,
    })
  );

  // List query matching Kotlin repository
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").get()
  );

  // Partial update on mutable fields only
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919876543210").update({
      reason: "Telemarketer",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Delete succeeds for owner
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("482910").delete()
  );
});

test("BlockedNumbers validation rejects invalid phone formats, shadow fields, future timestamps, and immutable mutations", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 2000);

  // Non-Indian invalid phone number rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("bad_num").set({
      userId: ALICE_UID,
      phoneNumber: "+15551234567",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Shadow field (`id` or `isAdmin`) rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("shadow_1").set({
      id: "shadow_1",
      userId: ALICE_UID,
      phoneNumber: "+919876543210",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Tampered future timestamp rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("future_1").set({
      userId: ALICE_UID,
      phoneNumber: "+919876543210",
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Immutable phoneNumber & createdAt protected on update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919811112222").set({
      userId: ALICE_UID,
      phoneNumber: "+919811112222",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919811112222").update({
      phoneNumber: "+919999999999",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Cross-user isolation: Bob cannot read, write, or delete Alice's blockedNumbers or transcripts", async () => {
  const now = new Date(Date.now() - 3000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919876543210").set({
      userId: ALICE_UID,
      phoneNumber: "+919876543210",
      createdAt: now,
      updatedAt: now,
    });
    await ctx.firestore().collection("users").doc(ALICE_UID).collection("transcripts").doc("tr_1").set({
      userId: ALICE_UID,
      transcriptText: "Hello from Alice",
      durationSec: 5,
      createdAt: now,
      updatedAt: now,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("blockedNumbers").doc("919876543210").delete());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("transcripts").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("tr_1").get());
});

// --- AUDIO TRANSCRIPTS SECURITY & KOTLIN PAYLOAD PARITY ---

test("Authenticated owner: valid AudioTranscript create, list, update, delete succeed; invalid bounds rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 4000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("tr_100").set({
      userId: ALICE_UID,
      transcriptText: "Namaste, testing live voice transcription.",
      durationSec: 12,
      languageHint: "English / Hindi",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("transcripts").get()
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("tr_100").update({
      transcriptText: "Updated transcript note.",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Empty transcript text rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("tr_bad").set({
      userId: ALICE_UID,
      transcriptText: "",
      durationSec: 3,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("transcripts").doc("tr_100").delete()
  );
});

// --- CALL LOGS & WEBRTC SIGNALING CALL ROOMS ---

test("CallLogs: owner can create, list, delete; cross-user and invalid status rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("callLogs").doc("log_1").set({
      userId: ALICE_UID,
      roomCode: "482910",
      callerNumber: "+919876543210",
      direction: "Incoming",
      status: "Blocked",
      voicePreset: "Female Soft (+4.5 st)",
      durationSec: 0,
      createdAt: now,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("callLogs").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("callLogs").get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("callLogs").doc("log_bad").set({
      userId: ALICE_UID,
      roomCode: "482910",
      direction: "Incoming",
      status: "Hacked",
      voicePreset: "Normal",
      durationSec: 0,
      createdAt: now,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("callLogs").doc("log_1").delete());
});

test("CallRooms & ICE Candidates: host creates room, caller joins & exchanges ICE, third party blocked, listing denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext("eve_uid_003").firestore();
  const now = new Date(Date.now() - 2000);

  // Listing all rooms is denied
  await assertFails(bobDb.collection("callRooms").get());

  // Host (Alice) creates 6-digit waiting room
  await assertSucceeds(
    aliceDb.collection("callRooms").doc("654321").set({
      hostUid: ALICE_UID,
      status: "waiting",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Caller (Bob) checks room and sends SDP offer
  await assertSucceeds(bobDb.collection("callRooms").doc("654321").get());
  await assertSucceeds(
    bobDb.collection("callRooms").doc("654321").update({
      callerUid: BOB_UID,
      status: "ringing",
      offerType: "offer",
      offerSdp: "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n",
      callerNumber: "+919876543210",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Caller (Bob) pushes ICE candidate
  await assertSucceeds(
    bobDb.collection("callRooms").doc("654321").collection("candidates").doc("cand_1").set({
      senderUid: BOB_UID,
      role: "caller",
      candidateJson: "{\"candidate\":\"candidate:1 1 UDP 2122260223 192.168.1.2 54321 typ host\"}",
      createdAt: new Date(Date.now() - 500),
    })
  );

  // Third party (Eve) cannot read candidates or hijack active room
  await assertFails(eveDb.collection("callRooms").doc("654321").collection("candidates").get());
  await assertFails(
    eveDb.collection("callRooms").doc("654321").update({
      status: "ended",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Host (Alice) answers and reads ICE candidates
  await assertSucceeds(aliceDb.collection("callRooms").doc("654321").collection("candidates").get());
  await assertSucceeds(
    aliceDb.collection("callRooms").doc("654321").update({
      status: "connected",
      answerType: "answer",
      answerSdp: "v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\n",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Participant cleans up room
  await assertSucceeds(aliceDb.collection("callRooms").doc("654321").delete());
});

test("Direct Indian (+91) phone calling: host listens on 91XXXXXXXXXX without Room ID and logs call", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("callRooms").doc("919876543210").set({
      hostUid: ALICE_UID,
      status: "waiting",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    bobDb.collection("callRooms").doc("919876543210").update({
      callerUid: BOB_UID,
      status: "ringing",
      offerType: "offer",
      offerSdp: "v=0\r\no=- 789 2 IN IP4 127.0.0.1\r\n",
      callerNumber: "+919123456789",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("callLogs").doc("log_in_1").set({
      userId: BOB_UID,
      roomCode: "919876543210",
      callerNumber: "+919876543210",
      direction: "Outgoing",
      status: "Completed",
      voicePreset: "Female Soft (+4.5 st)",
      durationSec: 45,
      createdAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(aliceDb.collection("callRooms").doc("919876543210").delete());
});


