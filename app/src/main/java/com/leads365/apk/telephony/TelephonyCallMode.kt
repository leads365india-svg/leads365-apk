package com.leads365.apk.telephony

/**
 * Phase 18: Telephony Call Mode.
 *
 * Configurable per agent/agency:
 * - NATIVE_DIALER: Uses device's native dialer (ACTION_DIAL/ACTION_CALL). Audio recordings
 *   are discovered locally from device storage (SAF or MediaStore).
 * - CLOUD_TELEPHONY: Requests the CRM cloud bridge (Exotel/Twilio) to bridge the call.
 *   The cloud service calls the agent's phone first, connects the lead, and captures
 *   the recording directly on the telecom switch.
 */
enum class TelephonyCallMode(val title: String, val description: String) {
    NATIVE_DIALER(
        title = "Native Phone Dialer",
        description = "Places calls directly from your SIM card. Call audio is discovered locally from device storage."
    ),
    CLOUD_TELEPHONY(
        title = "Cloud Telephony Fallback",
        description = "Calls your phone first then bridges the lead via cloud switch (Exotel/Twilio). Call is recorded at the telecom server."
    )
}
