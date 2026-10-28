# Leads365 Android — Phase 16 Pilot Cohort & Operating Plan

## 1. Pilot Executive Summary & Objective
Phase 16 specifies the operational readiness, monitoring protocol, telemetry derivation, and stabilisation criteria for a two-week real-agent pilot of Leads365 Android.

> **CRITICAL RELEASE GATE NOTICE:**
> Prior to deploying APK builds to real agents in production, all three active release blockers must be resolved:
> 1. **Phase 13:** `leads365.db` Room database file encryption at rest via SQLCipher.
> 2. **Phase 14:** Lab verification of physical hardware test scripts across supported OEM devices.
> 3. **Phase 15:** Signing of the production APK with the authorized production release keystore.
> 
> Deploying an unencrypted database or unsigned binary to production agents is **STRICTLY PROHIBITED**.
> Phase 16 establishes the operational and telemetry framework to execute immediately once release gates pass.

---

## 2. Pilot Cohort Definition (5–10 Agent Target)

The pilot cohort is composed of 8 primary seats and 2 reserve seats distributed across target OEM platforms with native auto-recording capabilities:

| Pilot Seat | Assigned OEM Platform | Target Handset | OS & API Level | Onboarding & Permission Status | Auto-Record & SAF Folder Readiness | Initial Sync & Queue Status | Known Platform Limitations |
| :---: | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **AGENT-01** | Samsung | Galaxy A54 5G | One UI 6 (Android 14, API 34) | Awaiting Enrollment | Phone app Auto-Record ON; `/Recordings/Call/` | Pending Onboarding | Requires CSC India `INS`; set Battery to "Unrestricted" |
| **AGENT-02** | Samsung | Galaxy S22 | One UI 6.1 (Android 14, API 34) | Awaiting Enrollment | Phone app Auto-Record ON; `/Recordings/Call/` | Pending Onboarding | Requires CSC India `INS`; set Battery to "Unrestricted" |
| **AGENT-03** | Xiaomi | Redmi Note 13 5G | HyperOS 1.0 (Android 14, API 34) | Awaiting Enrollment | Mi Dialer Auto-Record ON; `/MIUI/sound_recorder/call_rec/` | Pending Onboarding | Requires manual **Autostart** permission + Battery Saver "No restrictions" |
| **AGENT-04** | Xiaomi | Redmi Note 12 | MIUI 14 (Android 13, API 33) | Awaiting Enrollment | Mi Dialer Auto-Record ON; `/MIUI/sound_recorder/call_rec/` | Pending Onboarding | Aggressive RAM killer; lock app in Recents screen |
| **AGENT-05** | Vivo | Vivo V29 | Funtouch OS 14 (Android 14, API 34) | Awaiting Enrollment | Vivo Phone Auto-Record ON; `/Recordings/Call/` | Pending Onboarding | Must enable "High Background Power Consumption" in Battery settings |
| **AGENT-06** | Vivo | iQOO Z7 | Funtouch OS 13 (Android 13, API 33) | Awaiting Enrollment | Vivo Phone Auto-Record ON; `/Recordings/Call/` | Pending Onboarding | Must enable "High Background Power Consumption" in Battery settings |
| **AGENT-07** | Oppo / OnePlus | OnePlus Nord CE 3 | OxygenOS 14 (Android 14, API 34) | Awaiting Enrollment | O-Dialer APK Auto-Record ON; `/Recordings/Call Recordings/` | Pending Onboarding | Google Dialer announces recording; must install native O-Dialer APK |
| **AGENT-08** | Oppo / Realme | Realme Narzo 60 | Realme UI 4.0 (Android 13, API 33) | Awaiting Enrollment | O-Dialer APK Auto-Record ON; `/Recordings/Call Recordings/` | Pending Onboarding | Requires background app lock to prevent 15m screen-off freeze |
| **RESERVE-01** | Samsung | Galaxy M34 5G | One UI 6 (Android 14, API 34) | Standby Reserve | Phone app Auto-Record ON; `/Recordings/Call/` | Standby Reserve | Backup device for Samsung cohort |
| **RESERVE-02** | Xiaomi | POCO X6 | HyperOS 1.0 (Android 14, API 34) | Standby Reserve | Mi Dialer Auto-Record ON; `/MIUI/sound_recorder/call_rec/` | Standby Reserve | Backup device for Xiaomi cohort |

*Note: Clean Stock Android (Google Pixel, Motorola) is excluded from local recording pilot seats and assigned to Cloud Telephony Fallback (Roadmap Phase 18).*

---

## 3. Real Pilot Success Metrics & Telemetry Derivation

The pilot will be evaluated against six strict quantitative acceptance criteria. All metrics must be computed from real evidence sources (persisted local database entities, backend audit records, or operational support logs) rather than simulated test counters.

---

### Metric 1: Recording Capture Rate (Target: ≥ 95%)
- **Definition:** Percentage of unique eligible completed CRM lead calls that successfully discover, match, and persist a local audio recording file on supported devices.
- **Distinction from Upload:**
  - *Capture Rate (Discovery):* Measures whether the native dialer recorded the call and our SAF/MediaStore engine matched it locally.
  - *Upload Delivery Rate (Network):* Measures whether the captured recording was successfully transferred and acknowledged by the server.
- **Numerator:** Unique eligible CRM lead calls where a local call recording was matched and persisted (`hasRecording == true` in `CallEntity`, or linked `UploadTaskEntity` reached `STATE_FILE_FOUND`, `STATE_UPLOADING`, or `STATE_UPLOADED`).
- **Denominator:** Total unique completed eligible CRM lead calls (`isLeadCall == true`, `durationSeconds >= 10`, completed call outcome excluding rejected/missed).
- **Formula:**
  $$\text{Recording Capture Rate} = \left( \frac{\text{Unique Eligible Lead Calls with Matched Recording}}{\text{Total Unique Eligible Completed Lead Calls}} \right) \times 100$$
- **Evidence Source:** Local `calls` table (`CallEntity`) joined with `upload_tasks` table (`UploadTaskEntity`).
- **Missing-Data Treatment:** Calls with unrecorded duration or unknown call outcome are excluded from the eligible denominator. Eligible calls with no matched recording file are treated as capture failures ($0$ in numerator).
- **Feasibility Status:** **Reliably Measurable** via existing Room queries.

---

### Metric 2: Playback Latency (Target: Most recordings playable within 5 minutes)
- **Definition:** Percentage of captured recordings that become playable in the CRM within 300 seconds (5 minutes) of call termination.
- **Numerator:** Count of captured lead recordings where $(\text{Confirmed Server Playback Availability Timestamp} - \text{Reliable Call End Timestamp}) \le 300\text{ seconds}$.
- **Denominator:** Total captured lead recordings uploaded to the backend server.
- **Reliable Call End Timestamp:** Derived on Android client from `CallEntity.startTimeEpochMs + (CallEntity.durationSeconds * 1000)`.
- **Confirmed Server Playback Availability Timestamp:**
  > [!WARNING]
  > **Missing Instrumentation on Client:**
  > Neither `CallEntity` nor `UploadTaskEntity` stores an immutable server acknowledgement timestamp (`serverAckEpochMs` / `completedAtEpochMs`).
  > The field `UploadTaskEntity.updatedAtEpochMs` is a generic SQLite row modification timestamp updated on any state transition or retry, and does **not** represent server confirmation or playback availability.
  > Furthermore, backend playback availability (audio transcoding completion and S3 accessibility) is strictly a backend event and is not pushed to the Android client.
- **Evidence Source:** Requires cross-referencing client call end timestamps (`clientCallId`, `startTimeEpochMs`) with backend CRM recording audit logs (`recordings.created_at` or `recordings.playable_at`).
- **Missing-Data Treatment:** Calls missing duration cannot be accurately timed and are excluded. If backend availability timestamp is unrecorded, the task cannot be counted as passing and is classified as "Unmeasured / Missing Server Telemetry".
- **Feasibility Status:** **Cannot Be Measured from Client Data Alone.** Requires backend CRM audit correlation or adding a dedicated `serverAckEpochMs` field to `UploadTaskEntity`.

---

### Metric 3: Zero Non-Lead Recordings Uploaded (Target: Exactly 0 Incidents)
- **Definition:** No audio recording file originating from a personal, unlisted, or non-CRM telephone number may ever be opened, parsed, hashed, or uploaded.
- **Numerator:** Total count of non-lead or personal audio recording files accessed, hashed, or uploaded to the server.
- **Denominator:** Total recording files processed by the discovery and upload pipeline.
- **Target / Incident Threshold:** Exactly **0**. A single confirmed event triggers an immediate stop-and-investigate halt.
- **Evidence Source:** Client-side privacy filter logs (`RecordingDiscoveryCoordinator.GatedNotLead`), `PrivacyScreen` audit logs, and backend tenant call logs.
- **Missing-Data Treatment:** Ambiguous phone matches or unlisted numbers are quarantined at discovery; unverified uploads must be audited against CRM contact tables.
- **Feasibility Status:** **Reliably Measurable** via client discovery gate logs and backend audit.

---

### Metric 4: Zero Duplicate Uploads (Target: Exactly 0 Incidents)
- **Definition:** No single call event may produce multiple active upload tasks or duplicate recording entries in the CRM backend.
- **Numerator:** Total instances of multiple uploads or duplicate CRM recording entries created for the same call event.
- **Denominator:** Total completed upload tasks.
- **Target / Incident Threshold:** Exactly **0**.
- **Evidence Source:** SQLite unique index on `clientCallId` in `CallEntity` and `upload_tasks`, SHA-256 hash collision checks, and backend database uniqueness constraints.
- **Missing-Data Treatment:** Duplicate upload attempts blocked client-side by Room or server idempotency headers are logged as "Blocked at Gate"; duplicate rows persisting in CRM backend count as fatal metric violations.
- **Feasibility Status:** **Reliably Measurable** via database constraints and backend CRM deduplication checks.

---

### Metric 5: Crash-Free Session Rate (Target: ≥ 99.0%)
- **Definition:** Percentage of user application sessions that complete without experiencing an unhandled exception crash or Application Not Responding (ANR) event.
- **Numerator:** Total Application Sessions minus Sessions with at least one Crash or ANR.
- **Denominator:** Total Application Sessions.
- **Formula:**
  $$\text{Crash-Free Session Rate} = \left( 1 - \frac{\text{Sessions with } \ge 1 \text{ Crash or ANR}}{\text{Total App Sessions}} \right) \times 100$$
- **Evidence Source & Current Limitations:**
  > [!WARNING]
  > **Missing Instrumentation on Client:**
  > The current Android codebase lacks:
  > 1. A session lifecycle manager (tracking foreground session starts and normal terminations).
  > 2. A persistent uncaught exception handler (`Thread.UncaughtExceptionHandler`) storing crash events linked to session IDs.
  > 3. An ANR watchdog or integration with `ActivityManager.getHistoricalProcessExitReasons()` / `ApplicationExitInfo`.
  > Therefore, this metric **cannot be measured reliably** by automated client telemetry in the current build.
- **Missing-Data Treatment:** During the pilot, stability must be tracked via operational incident logs in the Daily Review Template (agent bug reports and `logcat` collection) until session/crash persistence is implemented.
- **Feasibility Status:** **Cannot Be Measured Reliably Without Additional Instrumentation.**

---

### Metric 6: Onboarding Support Ratio (Target: < 20%)
- **Definition:** Percentage of enrolled agents completing onboarding who required human technical assistance or IT intervention.
- **Numerator:** Total unique enrolled agents who required manual technical assistance, guidance, or IT intervention (e.g. phone support, screen share, manual settings adjustment) to complete the onboarding wizard, grant permissions, or configure SAF directories.
- **Denominator:** Total unique enrolled agents who completed onboarding and reached active status.
- **Formula:**
  $$\text{Onboarding Support Ratio} = \left( \frac{\text{Unique Enrolled Agents Requiring Technical Support}}{\text{Total Unique Enrolled Agents Who Completed Onboarding}} \right) \times 100$$
- **Distinction from Self-Test Diagnostic:**
  > [!NOTE]
  > The 12-point `SelfTestRunner` is an automated diagnostic health check, **not** a measurement of human onboarding support. An agent whose device passes 12/12 may still have required hands-on IT support to navigate OEM battery settings, or vice-versa. Self-test score must **not** be used as a proxy for human support.
- **Evidence Source:** Human operational support desk logs and Section 5 of the Daily Pilot Review Template ("Agent Feedback & Support Requests").
- **Missing-Data Treatment:** Enrolled agents who abandon onboarding prior to completion are tracked under "Onboarding Abandonment" and excluded from the completed denominator. Enrolled agents with unrecorded support status must be surveyed directly before final calculation.
- **Feasibility Status:** **Reliably Measurable** via operational pilot support desk logs.

---

## 4. Two-Week Pilot Operating Schedule

```
+----------------------------------------------------------------------------------------------------+
| DAY 0: Controlled Onboarding & Baseline Setup                                                      |
| - Verify signed production APK installed on agent devices.                                         |
| - Execute 12-point SelfTestRunner to verify all channels, permissions, and SAF paths.             |
| - Place controlled test lead call; verify prompt matching and upload.                              |
+----------------------------------------------------------------------------------------------------+
| DAYS 1–5: Week 1 Production Flow Observation                                                       |
| - Daily review of capture rate, upload elapsed time, and battery kill behavior.                    |
| - Triage missing recordings: investigate no-file-found vs ambiguous matches.                       |
| - Verify zero non-lead or DNR upload attempts.                                                     |
+----------------------------------------------------------------------------------------------------+
| DAYS 6–7: Mid-Pilot Review & Triage Fixes                                                          |
| - Consolidate crash reports and ANRs; release targeted hotfixes if necessary.                      |
| - Verify background recovery after weekend device reboots.                                         |
+----------------------------------------------------------------------------------------------------+
| DAYS 8–13: Week 2 Stabilisation & Sustained Performance                                            |
| - Full production call volume with active CRM follow-ups.                                          |
| - Monitor Deep Doze, background sync frequency, and offline browsing performance.                 |
+----------------------------------------------------------------------------------------------------+
| DAY 14: Final Acceptance Evaluation                                                                |
| - Tabulate all 6 success metrics against 14-day aggregated telemetry.                              |
| - Sign off Phase 16 acceptance only if all metrics meet established targets.                      |
+----------------------------------------------------------------------------------------------------+
```
