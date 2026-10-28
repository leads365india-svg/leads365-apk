# Leads365 Android — Phase 14 QA Device Testing Script & Checklist

## Protocol Overview
This repeatable QA script verifies end-to-end mobile performance, telephony observation, dialer recording discovery, upload reliability, and strict privacy gating on physical Android devices.

> **Execution Guidelines:**
> - Use only controlled test agent accounts, designated test SIM cards, and provisioned test CRM leads.
> - Never execute destructive revocation checks against active production agent sessions.
> - Record exact timestamps, OEM ROM build numbers, and logcat output for any failing test step.

---

## Repeatable 16-Scenario QA Checklist

| Step | Scenario | Test Procedure | Expected Outcome | Pass/Fail |
| :---: | :--- | :--- | :--- | :---: |
| **01** | **Fresh Install & Onboarding** | 1. Install fresh APK (`adb install -r app-dev-debug.apk` or release).<br>2. Launch app.<br>3. Review permission requests (`READ_PHONE_STATE`, `READ_CALL_LOG`, `POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`). | Onboarding wizard displays smoothly; permissions requested with rationale; no crashes. | [ ] |
| **02** | **Agent Login & Device Registration** | 1. Enter test agent credentials.<br>2. Submit login form.<br>3. Verify device registration payload (`manufacturer`, `model`, `sdkInt`, `appVersionCode`). | JWT access & refresh tokens stored in Keystore-backed encrypted preferences; device registered in backend. | [ ] |
| **03** | **Assigned-Lead Sync & Offline Browsing** | 1. Trigger initial lead sync.<br>2. Verify leads appear in Leads tab.<br>3. Enable Airplane Mode.<br>4. Navigate through Leads and Lead Details. | Cached leads display instantly from Room DB while offline; no empty-screen errors or network crashes. | [ ] |
| **04** | **Outgoing Call to Assigned Lead** | 1. Place cellular call to assigned test lead (`+91 98765 43210`).<br>2. Speak for 30s; hang up.<br>3. Return to Leads365 app. | Telephony observer logs OUTGOING call; duration recorded; status changes to `CALL_REPORTED` -> `FILE_FOUND`. | [ ] |
| **05** | **Incoming Call from Assigned Lead** | 1. Receive incoming cellular call from assigned test lead.<br>2. Answer, speak for 25s, hang up.<br>3. Inspect Calls tab. | Telephony observer logs INCOMING call; matched to lead ID; caller overlay displays lead name if enabled. | [ ] |
| **06** | **Missed or Rejected Lead Call** | 1. Call device from assigned lead; let it ring and reject / allow to miss.<br>2. Open Calls tab. | Call logged as missed/rejected (duration = 0s); no recording search triggered; sync reports call outcome. | [ ] |
| **07** | **Call to Non-Lead Number (Privacy Gate)** | 1. Call personal / unassigned number for 15s.<br>2. Hang up and inspect diagnostic logs. | **CRITICAL PRIVACY GATE:** `GatedNotLead` evaluated; zero SAF directory scanning; zero MediaStore audio query; no recording upload. | [ ] |
| **08** | **Do-Not-Record (DNR) Number** | 1. Toggle DNR ON for configured lead.<br>2. Call the DNR lead for 20s; hang up.<br>3. Inspect diagnostic logs. | **CRITICAL DNR GATE:** Call record saved with `isLeadCall=true`, but recording lookup blocked before file lookup; status `SKIPPED_DNR`. | [ ] |
| **09** | **Airplane Mode Interruption & Recovery** | 1. Initiate 5MB recording upload.<br>2. Enable Airplane Mode at ~50% progress.<br>3. Wait 10s, then restore Wi-Fi/LTE. | Upload pauses; task enters `STATE_RETRY_WAIT`; WorkManager automatically retries on reconnection; upload completes without corruption. | [ ] |
| **10** | **Process Kill During Upload** | 1. Start recording upload.<br>2. Force-stop app process via Settings / `am force-stop`.<br>3. Relaunch app. | Upload state machine reloads task from Room DB; resumes upload without duplicate server records. | [ ] |
| **11** | **Device Reboot Work Resumption** | 1. Queue pending upload.<br>2. Reboot physical handset.<br>3. Unlock phone after boot. | `BootCompletedReceiver` triggers `WorkManagerScheduler`; persistable SAF URI grant remains valid; pending upload resumes. | [ ] |
| **12** | **Confirmed Device Revocation** | 1. Administrator revokes device in backend.<br>2. Device transmits heartbeat or receives FCM revocation.<br>3. Observe app state. | **SAFE REMOTE WIPE:** Tokens purged from Keystore; Room tables wiped; pending WorkManager jobs cancelled; app transitions to Revoked screen. | [ ] |
| **13** | **Folder-Permission Loss & Recovery** | 1. Revoke SAF permission in App Info or rename recording folder.<br>2. Complete lead call.<br>3. Observe warning card in app. | UI displays "Folder Access Lost" alert; tapping launches `ACTION_OPEN_DOCUMENT_TREE` picker; re-granting restores discovery. | [ ] |
| **14** | **OEM Battery / Doze Restrictions** | 1. Lock screen and leave handset idle for 60m (Deep Doze).<br>2. Receive lead call.<br>3. Wake device. | App captures call event; WorkManager executes sync within OEM maintenance window; no dropped calls when battery unrestricted. | [ ] |
| **15** | **Recording Playback & Upload Status** | 1. Open completed call in Calls tab.<br>2. Verify upload badge shows "UPLOADED".<br>3. Tap play audio preview. | In-app audio player streams or plays local cached audio cleanly; waveform / progress bar advances. | [ ] |
| **16** | **Navigation UI Regression Check** | 1. Tap Calls tab -> tap Home tab.<br>2. Tap Leads tab -> tap Home tab.<br>3. Tap Settings tab -> tap Home tab.<br>4. Rapidly tap tabs. | Bottom navigation transitions smoothly; Home destination always restores without stack duplication or white screen. | [ ] |

---

## Active Security Blockers & Release Readiness Gate

### 1. Active Security Blocker: Local Database Encryption at Rest
- **Current State:**
  - Token and credential storage is encrypted via Android Keystore (`EncryptedSharedPreferences`).
  - Remote wipe of all Room tables (`leads`, `calls`, `upload_tasks`) on confirmed revocation is verified.
  - Plaintext ADB backups are disabled (`allowBackup="false"`).
  - **BLOCKER:** The local Room SQLite database file `leads365.db` remains unencrypted at the block storage layer. A full SQLCipher migration requires an offline database attach-and-encrypt export cycle with zero user data loss.
- **Release Status:** **BLOCKED**. Production distribution must not be approved until SQLCipher database encryption at rest is implemented and validated.

### 2. Active QA Blocker: Physical Hardware Verification
- **Current State:**
  - Automated test matrix for OEM filename resolution (Samsung, Xiaomi, Vivo, Oppo), ambiguous match rejection, lead lookup normalization, and queue safety passed.
  - **BLOCKER:** No physical Android handsets were connected via ADB during automated QA sessions.
- **Release Status:** **BLOCKED**. Physical execution of the 16-scenario checklist on target OEM phones (Samsung Galaxy, Xiaomi Redmi, Vivo, Oppo) is required before final sign-off.

