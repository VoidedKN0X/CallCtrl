package com.vknxworks.callctrl.calls

import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.ContactsRepository
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.data.screening.Decision
import com.vknxworks.callctrl.data.screening.IncomingCall
import com.vknxworks.callctrl.data.screening.ScreenAction
import com.vknxworks.callctrl.data.screening.ScreeningMatcher
import java.util.concurrent.Executors

/**
 * Android asks the default phone app's screening service about every incoming call before it rings. CallCtrl
 * checks its own rules (Settings > Call screening) and answers reject, silence or allow. Numbers on the phone's
 * blocked list never get here: Android blocks them itself. Emergency numbers are never screened.
 */
class ScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        // Outgoing calls (Android also shows them to the default phone app) are never screened.
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respond(details, CallResponse.Builder().build())
            return
        }
        // Contacts lookup and the rules file are disk work: off the main thread, well within Android's time limit.
        worker.execute {
            val decision = runCatching { decide(applicationContext, details) }.getOrNull()
            val response = CallResponse.Builder()
            if (decision != null) {
                val config = applicationContext.container.screening.current()
                when (decision.action) {
                    ScreenAction.REJECT -> response
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipCallLog(!config.logRejected)
                        .setSkipNotification(true)
                    ScreenAction.SILENCE -> response.setSilenceCall(true)
                    ScreenAction.ALLOW -> Unit
                }
            }
            respond(details, response.build())
            if (decision?.action == ScreenAction.REJECT && applicationContext.container.screening.current().notifyRejected) {
                CallNotifications.screened(applicationContext, numberOf(details))
            }
        }
    }

    private fun respond(details: Call.Details, response: CallResponse) {
        runCatching { respondToCall(details, response) }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()

        fun numberOf(details: Call.Details): String? = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }

        fun decide(context: Context, details: Call.Details): Decision {
            val config = context.container.screening.current()
            val number = numberOf(details)
            val hidden = number == null || details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
            val call = IncomingCall(
                number = number,
                hidden = hidden,
                isContact = !hidden && config.enabled && isContact(context, number!!),
                isEmergency = number != null && Phone.isEmergency(context, number),
            )
            return ScreeningMatcher.decide(config, call) { forms(context, it) }
        }

        private fun isContact(context: Context, number: String): Boolean =
            Phone.has(context, android.Manifest.permission.READ_CONTACTS) && ContactsRepository.lookup(context, number).name != null

        /** The forms of a number to compare: as given, international (+32…) and national (0…) for your country. */
        fun forms(context: Context, number: String): Set<String> {
            val out = LinkedHashSet<String>()
            out += ScreeningMatcher.normalize(number)
            val iso = runCatching { Phone.countryIso(context) }.getOrNull()
            if (iso != null) {
                runCatching { PhoneNumberUtils.formatNumberToE164(number, iso) }.getOrNull()?.let { out += ScreeningMatcher.normalize(it) }
                runCatching { PhoneNumberUtils.formatNumber(number, iso) }.getOrNull()?.let { out += ScreeningMatcher.normalize(it) }
            }
            out.remove("")
            return out
        }
    }
}
