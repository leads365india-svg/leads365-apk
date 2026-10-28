# Leads365 Android — Phase 16 Daily Pilot Stabilisation Review Template

> **Privacy & Security Notice:**
> Never paste unredacted phone numbers, customer names, passwords, or raw audio content in this report.
> Use `PrivacyRedactor` formatting: `+91 98****10` for phone numbers and redacted filenames `[REDACTED_REC_...].m4a`.

---

### Daily Operational Summary

- **Review Date:** `YYYY-MM-DD`
- **Pilot Day:** `Day X of 14`
- **Lead QA Engineer / Reviewer:** `[Name]`
- **Active Agent Devices in Cohort:** `[X / 8 Active]`
- **Installed App Version / Build:** `v1.0.0-prod (Build X)`

---

### 1. Telephony & Recording Match Performance

| Metric | Daily Count / Rate | Target | Status (PASS / WARN / FAIL) | Notes & Telemetry Source |
| :--- | :---: | :---: | :---: | :--- |
| **Total Cellular Calls Observed** | `0` | N/A | Normal | Telephony state transitions captured |
| **Unique Eligible CRM Lead Calls** | `0` | N/A | Normal | Denominator: `isLeadCall == 1`, duration $\ge$ 10s, completed |
| **Recordings Matched Locally (`FILE_FOUND`)** | `0` | N/A | Normal | Numerator: Matched via SAF / MediaStore (`hasRecording = 1`) |
| **Recording Capture Rate (%)** | `0.0%` | $\ge 95\%$ | — | $\frac{\text{Matched Unique Calls}}{\text{Eligible Unique Calls}} \times 100$ (Local Discovery) |
| **Recordings Uploaded (`STATE_UPLOADED`)** | `0` | $\approx$ Matched | — | Server confirmed receipt with SHA-256 |
| **Upload Delivery Rate (%)** | `0.0%` | $\ge 95\%$ | — | $\frac{\text{Uploaded Unique Calls}}{\text{Matched Unique Calls}} \times 100$ (Network Delivery) |
| **End-to-End Pipeline Rate (%)** | `0.0%` | $\ge 90\%$ | — | $\frac{\text{Uploaded Unique Calls}}{\text{Eligible Unique Calls}} \times 100$ |
| **No-File-Found Count** | `0` | $< 5\%$ | — | Delayed dialer write or folder path error |
| **Ambiguous-Match Count** | `0` | $0$ | — | Flagged and withheld at privacy gate |

---

### 2. Upload Pipeline & Playback Latency

| Pipeline Stage | Daily Metric | Target | Status | Notes |
| :--- | :---: | :---: | :---: | :--- |
| **Average Upload Transfer Time** | `0s` | Low | — | Time spent in `STATE_UPLOADING` |
| **Playable within 5m of Call End** | `0 / 0` | $\ge 90\%$ | — | Server Playback Available Time - Call End Time $\le 300\text{s}$ |
| **Playback Latency Compliance (%)** | `0.0%` | $\ge 90\%$ | — | *Requires Server Audit Correlation (Client `updatedAt` is mutable)* |
| **Pending / In-Queue Tasks** | `0` | Low | — | Awaiting Wi-Fi/unmetered network |
| **Transient Retries (`RETRY_WAIT`)** | `0` | Low | — | Recovered on connection restore |
| **Permanent Failures (`FAILED_PERMANENT`)** | `0` | $0$ | — | Investigated (e.g. file size $>50\text{MB}$ or 4xx error) |

---

### 3. Critical Privacy & Integrity Audit (Zero-Tolerance)

| Guardrail | Daily Incidents | Target | Status | Investigation / Action Required |
| :--- | :---: | :---: | :---: | :--- |
| **Non-Lead Upload Attempts** | **0** | **0** | **SAFE** | Stopped by `GatedNotLead` privacy gate |
| **Do-Not-Record (DNR) Violations** | **0** | **0** | **SAFE** | Stopped before SAF query |
| **Duplicate Uploads / Recordings** | **0** | **0** | **SAFE** | Prevented by unique `clientCallId` & SHA-256 |
| **Data Loss / Unwiped Revocation** | **0** | **0** | **SAFE** | Verified by remote wipe contract |

---

### 4. Stability, Battery & Crash-Free Session Tracking

> [!NOTE]
> Automated session and crash persistence is currently uninstrumented in the client app.
> Daily stability data must be aggregated from agent bug reports, operational triage, and device `logcat` dumps.

- **Total App Sessions:** `0`
- **Sessions with $\ge 1$ Crash or ANR:** `0`
- **Crash-Free Session Rate (%):** `100.0%` *(Target: $\ge 99.0\%$ | Formula: $\left(1 - \frac{\text{Sessions with }\ge 1\text{ Crash/ANR}}{\text{Total Sessions}}\right) \times 100$)*
- **ANR (Application Not Responding) Incidents:** `0`
- **OEM Deep Doze / Process Kill Incidents:**
  - *Samsung:* `[ ] Normal  [ ] Process Killed (Investigate Unrestricted setting)`
  - *Xiaomi / HyperOS:* `[ ] Normal  [ ] Autostart Revoked (Investigate Battery Saver)`
  - *Vivo / Funtouch:* `[ ] Normal  [ ] Background Restricted (Check High Power Consumption)`
  - *Oppo / ColorOS:* `[ ] Normal  [ ] Frozen after 15m (Check App Lock)`
- **Device Reboot Work Resumption:**
  - `BootCompletedReceiver` executed: `[ ] Yes  [ ] No`
  - Persistable SAF URI permissions retained: `[ ] Yes  [ ] No`

---

### 5. Agent Onboarding & Support Tracking

> [!IMPORTANT]
> The Onboarding Support Ratio measures human IT intervention required by real agents.
> Automated `SelfTestRunner` pass counts are diagnostic checks and must NOT be used as a proxy for agent support.

- **Total Enrolled Agents:** `0`
- **Agents Successfully Completed Onboarding:** `0`
- **Completed Agents Requiring Technical/IT Support:** `0`
- **Onboarding Support Ratio (%):** `0.0%` *(Target: $< 20.0\%$ | Formula: $\frac{\text{Agents Requiring Support}}{\text{Completed Agents}} \times 100$)*
- **Onboarding Abandonment Count:** `0`

| Agent ID | Device Model | Support Required? | Symptom / Issue Description | Root Cause & Resolution |
| :---: | :--- | :---: | :--- | :--- |
| `AGENT-0X` | `[Model]` | `[Yes / No]` | `[e.g. Folder picker re-prompted]` | `[SAF URI revoked -> Re-granted]` |

---

### 6. Stabilisation Action Items & Regression Verifications

- [ ] Action 1: `[Description]` -> Assigned to: `[Engineer]` -> Verification: `[Test Class]`
- [ ] Action 2: `[Description]` -> Assigned to: `[Engineer]` -> Verification: `[Test Class]`
