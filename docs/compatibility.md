# Leads365 Android — Phase 1 Device Compatibility Matrix & Real-Device Test Script

## 1. Executive Summary & Spike Objective
Phase 1 of the Leads365 Android application is a targeted feasibility spike to determine whether native OEM phone dialer call recordings can be located, attributed to CRM leads, and matched with high confidence without resorting to accessibility exploits, microphone eavesdropping, or root privileges.

> **CRITICAL VERIFICATION NOTICE:**
> All manufacturer entries in this document represent **architectural projections and platform specifications** derived from documented Android APIs and OEM dialer storage conventions.
> **No physical handset measurements have been fabricated.**
> Because no physical handsets are currently connected to the development environment via ADB, **all real-device feasibility metrics remain explicitly UNVERIFIED and PENDING physical hardware testing.**

This document outlines:
1. The cross-OEM architectural baseline matrix.
2. The architectural comparison between **Storage Access Framework (SAF)** and **MediaStore Audio Queries**.
3. Theoretical storage consumption baselines per audio codec.
4. A reproducible physical testing protocol for QA and mobile field engineers across 3–5 real devices.
5. The cloud-telephony fallback allocation (strictly retained in Roadmap Phase 18).

---

## 2. Multi-OEM Dialer Recording Compatibility Matrix (Architectural Baselines — Unverified on Physical Hardware)

> **Status Legend:**
> - `[UNVERIFIED - PENDING LAB TEST]`: Architectural behavior projected from platform specs; awaiting execution on a connected physical handset.

| OEM Brand | Stock Dialer & Expected Recording Capability | Default Storage Location | Filename Convention | Audio Codec & Bitrate | MediaStore Discoverable? | SAF Tree Persistable? | Expected Post-Call Write Delay | OEM Battery / Background Restrictions | Architectural Projection & Real-Device Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Samsung** (One UI 5 / 6 / 7, Android 13–15) | Samsung Phone App with built-in native Auto-Call Recording (in supported CSC regions, e.g., India `INS`). | `/Recordings/Call/` or `/Internal storage/Recordings/Call/` | `Call to [Name]_[Number]_[YYMMDD_HHMMSS].m4a` or `Call from [Number]_[YYMMDD_HHMMSS].m4a` | AAC / M4A (16-bit, 44.1kHz mono, ~64 kbps) | Projected: **Yes**, indexed as audio. Bounded query by `DATE_MODIFIED`. | Projected: **Yes**, user can grant persistent access to `/Recordings/Call/` via `ACTION_OPEN_DOCUMENT_TREE`. | Expected ~1–3 seconds after `IDLE` telephony transition. | Moderate. Whitelist via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and "Unrestricted" battery setting required. | **Projected High Feasibility**<br>`[UNVERIFIED - PENDING LAB TEST]` |
| **Xiaomi / Redmi / POCO** (MIUI / HyperOS, Android 12–15) | Mi Dialer (India/Indonesia ROMs) has native auto-recording. Global ROMs using Google Phone may announce call recording out loud. | `/MIUI/sound_recorder/call_rec/` or `/Recordings/call/` | `[Name]([Number])_[YYYYMMDDHHMMSS].mp3` or `.aac` | MP3 / AAC (~64–128 kbps) | Projected: **Partial**. HyperOS media scanner may delay indexing non-public folders until system media scan. | Projected: **Yes**, SAF tree grant can select `/MIUI/sound_recorder/call_rec/` directly. | Expected ~2–6 seconds. In aggressive power-save mode, writer flushes on screen wake. | Aggressive. HyperOS kills background observers unless Auto-Start is granted and Battery Saver is set to "No restrictions". | **Projected Medium-High Feasibility**<br>`[UNVERIFIED - PENDING LAB TEST]` |
| **Vivo / iQOO** (Funtouch OS / OriginOS, Android 12–15) | Vivo Phone app has native auto-record without audio warning announcement in Indian region. | `/Recordings/Call/` or `/Recordings/Record/Call/` | `[Number]_[YYYYMMDD_HHMMSS].m4a` or `[Name]_[YYMMDDHHMMSS].wav` | M4A / AMR-WB (~32–64 kbps) | Projected: **Yes**, accessible via `READ_MEDIA_AUDIO` when indexed. | Projected: **Yes**, SAF tree grants persist across reboots. | Expected ~1–4 seconds post-call. | Very Aggressive. Requires enabling "High Background Power Consumption" in Battery Settings. | **Projected Medium-High Feasibility**<br>`[UNVERIFIED - PENDING LAB TEST]` |
| **Oppo / Realme / OnePlus** (ColorOS / OxygenOS 13–15) | O-Dialer app (ColorOS native) supports silent auto-recording. (Default Google Dialer announces recording). | `/Recordings/Call Recordings/` or `/Music/Recordings/` | `REC_[Number]_[YYYYMMDD_HHMMSS].m4a` | AAC / M4A (mono, ~64 kbps) | Projected: **Yes**, discoverable in `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`. | Projected: **Yes**, SAF tree selector reaches folder easily. | Expected ~2–5 seconds. | High. ColorOS freezes unwhitelisted background processes after 15 minutes of screen off. | **Projected Medium Feasibility**<br>`[UNVERIFIED - PENDING LAB TEST]` |
| **Google Pixel / Motorola** (Clean Android 13–15) | Google Phone Dialer (Dialer does NOT support silent auto-recording; announces recording to caller; saves recordings to private app sandbox). | `/data/user/0/com.google.android.dialer/cache/` (Private Sandbox) | `CallRecording_...` | AMR-WB / WAV | **NO**. Google Dialer stores recordings in its private internal database/cache. | **NO**. SAF cannot select another application's private `/data/data` directory. | N/A (Files are sandboxed). | Low battery killing, but security model strictly isolates recordings. | **Local Storage Infeasible**<br>`[VERIFIED PLATFORM LIMITATION]`<br>*(Requires Cloud Telephony Fallback in Roadmap Phase 18)* |

---

## 3. Theoretical Storage Consumption & Network Bandwidth Projections

The following figures represent mathematical and codec baselines. Actual per-minute sizes must be physically confirmed on target handsets:

| Codec & Container | Typical Sample Rate | Channels | Nominal Bitrate | Theoretical Storage per 1-minute Call | Theoretical Storage per 100 Calls (avg 2.5 min/call) | Streaming Upload Strategy | Physical Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :---: |
| **AMR-NB** (`.amr`) | 8 kHz | 1 (Mono) | 12.2 kbps | **~0.09 MB / min** | ~22.5 MB | Instant streaming upload via OkHttp chunked pipeline. | Unverified |
| **AMR-WB** (`.amr`) | 16 kHz | 1 (Mono) | 23.85 kbps | **~0.18 MB / min** | ~45.0 MB | Instant streaming upload via OkHttp chunked pipeline. | Unverified |
| **AAC-LC / M4A** (`.m4a`) | 44.1 kHz | 1 (Mono) | 64 kbps | **~0.48 MB / min** | ~120.0 MB | Direct streaming upload with `X-Content-SHA256`. | Unverified |
| **AAC-LC / M4A** (`.m4a`) | 44.1 kHz | 2 (Stereo) | 128 kbps | **~0.96 MB / min** | ~240.0 MB | Direct streaming upload with `X-Content-SHA256`. | Unverified |
| **MP3** (`.mp3`) | 44.1 kHz | 1 (Mono) | 64–128 kbps | **~0.48 – 0.96 MB / min** | ~120 – 240 MB | Direct streaming upload with `X-Content-SHA256`. | Unverified |
| **PCM / WAV** (`.wav`) | 16 kHz / 44.1 kHz | 1 (Mono) | 256–705 kbps | **~1.92 – 5.28 MB / min** | ~480 MB – 1.32 GB | Streaming upload with live progress callback. | Unverified |

---

## 4. Method Comparison: SAF vs. MediaStore

### Method A: Storage Access Framework (SAF)
- **Mechanism:** User explicitly grants read access to the dialer's target recording directory once using `ACTION_OPEN_DOCUMENT_TREE`. The app takes persistable URI permission (`takePersistableUriPermission`).
- **Architectural Strengths:**
  1. Immune to MediaStore indexing delays or OEM media-scanner exclusions.
  2. Complies with modern Android storage restrictions (Android 11–15) without needing `MANAGE_EXTERNAL_STORAGE`.
  3. Reaches custom OEM paths (e.g., `/MIUI/sound_recorder/call_rec/`).
- **Operational Trade-offs:**
  1. Requires one-time manual onboarding (user selects directory).
  2. `DocumentFile` traversal overhead is mitigated by bounded time-window queries.

### Method B: MediaStore Audio Queries
- **Mechanism:** Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with bounded `DATE_MODIFIED` selection and version-appropriate permissions (`READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` on Android 12-).
- **Architectural Strengths:**
  1. Zero-configuration (no folder selection required).
  2. Provides duration and MIME type directly from the media provider.
- **Operational Trade-offs:**
  1. Delayed indexing by OEM media scanners.
  2. Complete omission by private dialers (Google Phone).

### Architectural Decision Criterion:
**Hybrid Model:**
1. Default to **Method A (Storage Access Framework)** as the primary deterministic discovery engine.
2. Use **Method B (MediaStore)** as a zero-click auxiliary probe.
3. Devices that write recordings to private app sandboxes (Google Pixel, Motorola) cannot use local file discovery. These devices are allocated to the **Roadmap's Phase 18 (Cloud Telephony Fallback Architecture)**.

---

## 5. Physical Real-Device Testing Protocol (QA Test Script)

### Equipment Needed
- 3–5 physical phones:
  1. Samsung Galaxy (One UI 5/6/7 with native Auto Call Recording enabled in Phone settings)
  2. Xiaomi / Redmi phone (MIUI 14 or HyperOS)
  3. Vivo or iQOO phone (Funtouch OS 13/14)
  4. Oppo, Realme, or OnePlus phone (ColorOS / OxygenOS 13/14)
- Physical active SIM cards installed with cellular voice plan.
- The compiled `app-debug.apk` ([d:/LEADS-apk/app/build/outputs/apk/debug/app-debug.apk](file:///d:/LEADS-apk/app/build/outputs/apk/debug/app-debug.apk)).

### Step-by-Step Execution Script

#### Phase A: Installation & Setup Verification
1. Install `app-debug.apk` onto the physical device:
   ```bash
   adb install -r app-debug.apk
   ```
2. Launch **Leads365** from the launcher.
3. Verify the **App Header** correctly identifies the device model and Android OS version.
4. Tap **Grant Required** on the Permission Checklist card:
   - Grant `READ_PHONE_STATE`.
   - Grant `READ_CALL_LOG`.
   - Grant `READ_MEDIA_AUDIO` (or Storage).
5. Open native Phone settings and verify **Auto Call Recording** is turned **ON** in the dialer.

#### Phase B: Discovery Configuration
6. In Leads365, locate **Recording Discovery Comparison**:
   - For **Method A (SAF)**: Tap **Select Recordings Folder**. Navigate to the phone's native call recordings folder (e.g., `Recordings/Call`) and tap **Use this folder** -> **Allow**. Verify status changes to **Persisted & Readable**.
   - For **Method B (MediaStore)**: Verify status shows **READY**.

#### Phase C: Test-Lead Number Provisioning
7. In the **Test-Call & Lead Gate Workflow** card, confirm the default test lead (`+91 98765 43210`) or add the secondary phone's number as an active test lead.

#### Phase D: Controlled Call Execution & Lead-Gate Validation
8. **Test 1: Allowed Lead Call**
   - Place a cellular voice call between the test device and the configured test lead.
   - Speak for exactly 30 seconds, then hang up.
   - Return to Leads365.
   - **Expected Result:**
     - The telephony observer detects call end.
     - CallLog entry is read: duration ~30s, direction recorded.
     - Lead-Only Gate evaluates to **PASSED (Allowed Test Lead)**.
     - Candidate recording is discovered within the bounded window.
     - Targeted matcher outputs **FILE_FOUND** with high confidence.

9. **Test 2: Non-Lead Call Gate Verification**
   - Call an unconfigured personal number for 10 seconds.
   - Hang up and return to Leads365.
   - **Expected Result:**
     - Call is observed.
     - Lead-Only Gate evaluates to **STOPPED: Not a test lead**.
     - Verify diagnostic audit log: **NO** directory scan, **NO** MediaStore query, and **NO** recording match was triggered.

10. **Test 3: Do-Not-Record (DNR) Gate Verification**
    - Toggle the **DNR** switch to ON for the test lead.
    - Place a 15-second call to that lead. Hang up.
    - **Expected Result:**
      - Lead-Only Gate evaluates to **STOPPED: Do-Not-Record Flag**.
      - Recording lookup is blocked before querying SAF or MediaStore.

#### Phase E: Streaming Test Upload Verification
11. In the **Safe Test-Upload Pipeline** card, input an explicit local test endpoint (e.g., `http://192.168.1.X:8080/upload`).
12. On the uniquely matched recording from Test 1, tap **Upload**.
13. Verify the **Confirmation Dialog** appears displaying:
    - Redacted filename
    - File size
    - SHA-256 checksum
    - Safe guarantee notice: "Original dialer recording will NOT be modified, renamed, moved, or deleted."
14. Tap **Confirm & Stream**.
15. Verify progress indicator updates smoothly, HTTP response status displays, and original file remains intact in native dialer.

#### Phase F: Background & Reboot Persistence
16. Lock the screen and leave the device idle for 30 minutes.
17. Place a call from the test lead to the device. Answer and talk for 20s. Hang up.
18. Unlock phone and open Leads365. Verify the call and recording were captured.
19. Reboot the physical device. Launch Leads365. Verify that the SAF Tree URI grant remains valid without re-prompting.

#### Phase G: Diagnostic Export
20. Tap **Copy Redacted JSON**.
21. Paste into a secure test log. Verify no raw unmasked phone numbers or tokens appear.

---

## 7. Phase 2 Architecture & Build Flavor Specifications

### Variant Configuration
| Flavor | Application ID | Base URL | Purpose | Side-by-Side Install |
| :--- | :--- | :--- | :--- | :--- |
| **dev** | `com.leads365.apk.dev` (Debug: `com.leads365.apk.dev.debug`) | `http://10.0.2.2:5000/api/` (Local / Mock) | Development, MockWebServer testing, QA staging | Yes |
| **prod** | `com.leads365.apk` (Debug: `com.leads365.apk.debug`) | `https://api.leads365.com/api/` (Production) | Production release build | Yes |

### Integrated Architectural Components
1. **Dependency Injection**: Google Hilt 2.52 with `@HiltAndroidApp`, `@AndroidEntryPoint`, and `@HiltViewModel`.
2. **Local Persistence**: Android Room 2.6.1 with KSP code generation, indexed entities (`LeadEntity`, `CallEntity`, `UploadTaskEntity`), typed DAOs, and migration scaffolding.
3. **Networking**: Centralized Retrofit 2.11.0 + OkHttp 4.12.0 with Kotlinx Serialization, Bearer token injection, automatic 401 token refresh with concurrency deduplication, and minimum app version verification.
4. **Background Execution**: AndroidX WorkManager 2.10.0 with network constraints and retry backoff.
5. **Provisional minSdk Notice**: The current `minSdk 26` (Android 8.0) is retained as provisional until physical device validation confirms OEM dialer recording accessibility across Android 8 through 15. All physical hardware tests remain **[UNVERIFIED - PENDING LAB TEST]**.

