package com.vknxworks.callctrl.data

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

/** Placing calls, SIM choice, the default-phone-app role and number formatting. */
object Phone {
    fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** CallCtrl is the phone's default phone app (it shows the call screen and gets the missed-call notices). */
    fun isDefault(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    }.getOrDefault(false)

    fun roleAvailable(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_DIALER) == true
    }.getOrDefault(false)

    fun telecom(context: Context): TelecomManager? = context.getSystemService(TelecomManager::class.java)

    /** SIMs and other accounts that can make calls. */
    @SuppressLint("MissingPermission")
    fun callAccounts(context: Context): List<CallAccount> {
        if (!has(context, android.Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val tm = telecom(context) ?: return emptyList()
        return runCatching {
            tm.callCapablePhoneAccounts.mapNotNull { h ->
                val a = tm.getPhoneAccount(h) ?: return@mapNotNull null
                if (!a.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) && !a.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) return@mapNotNull null
                CallAccount(h, a.label?.toString().orEmpty().ifBlank { "SIM" }, a.highlightColor)
            }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun defaultAccount(context: Context): PhoneAccountHandle? = runCatching {
        telecom(context)?.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
    }.getOrNull()

    /**
     * Places a call through Android's telephony service. [account] picks the SIM; without one Android uses the
     * default SIM, or asks on the call screen.
     */
    @SuppressLint("MissingPermission")
    fun call(context: Context, number: String, account: PhoneAccountHandle? = null): Boolean {
        if (number.isBlank() || !has(context, android.Manifest.permission.CALL_PHONE)) return false
        val tm = telecom(context) ?: return false
        val extras = Bundle()
        if (account != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
        return runCatching { tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras); true }.getOrDefault(false)
    }

    /** Calls voicemail (long-press 1). */
    @SuppressLint("MissingPermission")
    fun callVoicemail(context: Context): Boolean {
        if (!has(context, android.Manifest.permission.CALL_PHONE)) return false
        val tm = telecom(context) ?: return false
        return runCatching { tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), Bundle()); true }.getOrDefault(false)
    }

    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country).uppercase(Locale.ROOT)
    }

    /** "+32 470 12 34 56" style, as the phone formats numbers for your country. */
    fun format(context: Context, number: String?): String {
        if (number.isNullOrBlank()) return ""
        return runCatching { PhoneNumberUtils.formatNumber(number, countryIso(context)) }.getOrNull() ?: number
    }

    /** Two numbers are the same phone line (ignores spaces, and +32 vs 0). */
    fun same(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return runCatching { PhoneNumberUtils.areSamePhoneNumber(a, b, Locale.getDefault().country) }.getOrNull()
            ?: (a.filter { it.isDigit() }.takeLast(9) == b.filter { it.isDigit() }.takeLast(9))
    }

    fun isEmergency(context: Context, number: String): Boolean = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true
    }.getOrDefault(false)
}

data class CallAccount(val handle: PhoneAccountHandle, val label: String, val color: Int)
