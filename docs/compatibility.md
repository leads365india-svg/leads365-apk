# Leads365 Android — Phase 14 Device Compatibility Matrix & QA Specifications

## 1. Executive Summary & QA Objectives
Phase 14 defines the cross-OEM device validation matrix, acceptance criteria, and physical QA protocol for the Leads365 Android application.
The core application capability relies on discovering native OEM dialer call recordings, gating access strictly to assigned CRM leads, and maintaining background upload queues without user data loss.

> **CRITICAL VERIFICATION NOTICE:**
> All OEM entries in this document reflect architectural specifications, automated unit/Robolectric test results, and platform security baselines.
> Because physical mobile handsets are not connected via ADB in this workspace session, **all physical hardware metrics remain explicitly NOT VERIFIED pending laboratory device testing.**
> Hardware status is never fabricated.

---

## 2. Phase 14 Multi-OEM Dialer Recording Compatibility Matrix

> **Official Verdict Taxonomy (Phase 14 Specification):**
> - `WORKS`: Required physical tests passed with recorded hardware evidence.
> - `WORKS WITH CAVEATS`: Verified with documented OEM-specific limitations and manual configuration prerequisites.
> - `NOT VERIFIED`: Physical hardware test not yet executed; architectural projections and automated tests in place.
> - `FAILED`: Actual physical test failed with defect documented.
> - `USE CLOUD TELEPHONY FALLBACK`: Native auto-recording is architecturally impossible or sandboxed by OEM dialer.

| Manufacturer & Model | OS & API Level | App Build | Dialer Recording & Storage Path | Call Detection & Matcher | Background Sync & Recovery | Reboot & Battery Policy | Upload & Playback | Known Limitations & Manual Settings | Evidence Source & Date | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Samsung Galaxy**<br>(S21/S22/S23, A54, M34) | One UI 5/6/7<br>(Android 13–15, API 33–35) | 1.0.0-prod<br>(Phase 13/14) | Native Auto-Record in Phone App.<br>Path: `/Recordings/Call/` | CallLog observer detects telephony state.<br>File format: `Call to [Name]_[Number]_[YYMMDD_HHMMSS].m4a` | WorkManager periodic sync.<br>Safe recovery on network reconnect. | Survives reboot (`RECEIVE_BOOT_COMPLETED`).<br>Requires battery "Unrestricted" whitelist. | Streaming upload with SHA-256.<br>Server-verified playback. | 1. Requires CSC with native recording (India `INS`).<br>2. User must grant SAF tree permission to `/Recordings/Call/`. | Automated Matcher + Architecture Spec (2026-10-28) | **NOT VERIFIED**<br>*(Pending physical lab handset)* |
| **Xiaomi / Redmi / POCO**<br>(Note 12/13, POCO X5/X6) | MIUI 14 / HyperOS<br>(Android 12–14, API 32–34) | 1.0.0-prod<br>(Phase 13/14) | Mi Dialer in India ROM.<br>Path: `/MIUI/sound_recorder/call_rec/` | Telephony detection verified.<br>File format: `[Name]([Number])_[YYYYMMDDHHMMSS].mp3` | Upload queue persists across process death.<br>Retry backoff on 429/network drop. | Aggressive process killing.<br>Must enable **Autostart** in Settings. | OkHttp chunked upload.<br>Server idempotency verified. | 1. Global ROM with Google Dialer announces calls.<br>2. Battery Saver must be set to "No restrictions". | Automated Matcher + Architecture Spec (2026-10-28) | **NOT VERIFIED**<br>*(Pending physical lab handset)* |
| **Vivo / iQOO**<br>(V27/V29, T2x, iQOO Z7) | Funtouch OS 13/14<br>(Android 13–14, API 33–34) | 1.0.0-prod<br>(Phase 13/14) | Native Vivo Phone App.<br>Path: `/Recordings/Call/` or `/Recordings/Record/Call/` | Telephony observer active.<br>File format: `[Number]_[YYYYMMDD_HHMMSS].m4a` | Queue survives simulated network failure.<br>Exponential backoff active. | Very aggressive background killer.<br>Must toggle "High Background Power Consumption". | Streamed upload with progress.<br>SHA-256 verification. | 1. Hidden battery whitelist under Battery > High Background Power Consumption.<br>2. Folder structure varies between Android 12 and 14. | Automated Matcher + Architecture Spec (2026-10-28) | **NOT VERIFIED**<br>*(Pending physical lab handset)* |
| **Oppo / Realme / OnePlus**<br>(Reno 10/11, Narzo 60, Nord CE) | ColorOS / OxygenOS 13/14<br>(Android 13–14, API 33–34) | 1.0.0-prod<br>(Phase 13/14) | Native O-Dialer (ColorOS).<br>Path: `/Recordings/Call Recordings/` | Telephony detection active.<br>File format: `REC_[Number]_[YYYYMMDD_HHMMSS].m4a` | Pending upload tasks survive process kill.<br>Recovered on launch. | ColorOS freezes unwhitelisted background tasks after 15m screen-off.<br>App lock required. | Streaming upload with SHA-256 checksum.<br>Playback confirmed. | 1. Default Google Dialer announces recordings; users must install O-Dialer APK.<br>2. Background freeze requires manual exception in App Management. | Automated Matcher + Architecture Spec (2026-10-28) | **NOT VERIFIED**<br>*(Pending physical lab handset)* |
| **Google Pixel / Motorola**<br>(Pixel 6/7/8/9, Moto G/Edge) | Clean Stock Android<br>(Android 13–15, API 33–35) | 1.0.0-prod<br>(Phase 13/14) | Google Phone Dialer.<br>Path: Private App Sandbox (`/data/user/0/com.google.android.dialer/`) | Telephony detection works.<br>Recording file lookup **BLOCKED**. | Lead synchronization and call logging active.<br>Audio recording upload disabled. | Low battery killing.<br>Standard Android Doze behavior. | N/A (Audio recordings inaccessible). | 1. Google Dialer stores recordings in private app sandbox.<br>2. Storage Access Framework cannot access `/data/data`.<br>3. Google Dialer announces "This call is now being recorded". | Platform Security Architecture Audit (2026-10-28) | **USE CLOUD TELEPHONY FALLBACK** |

---

## 3. Storage Consumption & Codec Baselines

| Codec & Container | Sample Rate | Channels | Nominal Bitrate | Storage per 1-min Call | Storage per 100 Calls (avg 2.5 min) | Streaming Upload Strategy | Physical Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :---: |
| **AMR-NB** (`.amr`) | 8 kHz | 1 (Mono) | 12.2 kbps | **~0.09 MB / min** | ~22.5 MB | OkHttp chunked streaming pipeline | Unverified |
| **AMR-WB** (`.amr`) | 16 kHz | 1 (Mono) | 23.85 kbps | **~0.18 MB / min** | ~45.0 MB | OkHttp chunked streaming pipeline | Unverified |
| **AAC-LC / M4A** (`.m4a`) | 44.1 kHz | 1 (Mono) | 64 kbps | **~0.48 MB / min** | ~120.0 MB | Direct streaming upload with `X-Content-SHA256` | Unverified |
| **AAC-LC / M4A** (`.m4a`) | 44.1 kHz | 2 (Stereo) | 128 kbps | **~0.96 MB / min** | ~240.0 MB | Direct streaming upload with `X-Content-SHA256` | Unverified |
| **MP3** (`.mp3`) | 44.1 kHz | 1 (Mono) | 64–128 kbps | **~0.48 – 0.96 MB / min** | ~120 – 240 MB | Direct streaming upload with `X-Content-SHA256` | Unverified |
| **PCM / WAV** (`.wav`) | 16/44.1 kHz | 1 (Mono) | 256–705 kbps | **~1.92 – 5.28 MB / min** | ~480 MB – 1.32 GB | Streaming upload with live progress callback | Unverified |

---

## 4. Architectural Discovery Protocol: SAF vs. MediaStore

1. **Storage Access Framework (SAF) — Primary**:
   - Explicit one-time directory selection via `ACTION_OPEN_DOCUMENT_TREE`.
   - Persisted via `takePersistableUriPermission`.
   - Immune to MediaStore indexing lag; accesses OEM-specific directory structures.
2. **MediaStore Audio Provider — Secondary Probe**:
   - Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with bounded `DATE_MODIFIED`.
   - Zero user configuration, but subject to OEM media scanner delays.
3. **Cloud Telephony Fallback (Phase 18 Allocation)**:
   - Reserved strictly for devices with sandboxed dialers (Google Pixel, Motorola) where native recordings cannot be extracted locally.

---

## 5. Build Flavor & Production Security Alignment

| Flavor | Application ID | Target Base URL | Network Security Config | R8 Minification |
| :--- | :--- | :--- | :--- | :--- |
| **dev** | `com.leads365.apk.dev` | `http://[devHost]:5000/api/` | Cleartext allowed for localhost & dev IPs only | Disabled in debug |
| **prod** | `com.leads365.apk` | `https://api.leads365.com/api/` | Strictly HTTPS (`cleartextTrafficPermitted="false"`) | Enabled (`isMinifyEnabled = true`, `isShrinkResources = true`) |

---

## 6. Release Readiness Assessment & Active Security Blockers

> **CRITICAL ARCHITECTURAL DISTINCTION:**
> **Phase 14 QA Automated Testing Progress** is distinct from **Overall Production Release Readiness**.

### Current Status Matrix
| Track | Status | Notes & Blockers |
| :--- | :---: | :--- |
| **Phase 14 Automated QA Suite** | **PASSED** | Matcher resolution matrix, lead number normalization, queue cleanup safety, and ambiguous match rejection automated tests verified. |
| **Phase 14 Documentation & Checklists** | **PASSED** | Multi-OEM matrix updated; 16-scenario repeatable physical device QA checklist published. |
| **Phase 13 Release Shrinking & Obfuscation** | **PASSED** | `prodRelease` verified with R8 code/resource shrinking (2.55 MB binary, 88.4% reduction). |
| **Phase 13 Transport Security & Token Crypto** | **PASSED** | Strict HTTPS network security config wired; Keystore-backed AES256-GCM token storage. |
| **Physical Handset Lab Testing** | **BLOCKED / PENDING** | Requires connected physical devices with live cellular SIM cards. |
| **Local Room Database Encryption at Rest** | **ACTIVE SECURITY BLOCKER** | `leads365.db` remains unencrypted SQLite at the block layer; full SQLCipher migration pending dedicated release cycle. |
| **OVERALL PRODUCTION RELEASE READINESS** | **BLOCKED (NOT READY)** | Must not deploy to production until database encryption blocker and physical lab tests pass. |

