package com.vknxworks.callctrl.ui.dialer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.data.CallAccount
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.ui.common.launch
import com.vknxworks.callctrl.ui.components.CtrlBottomSheet
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.theme.CtrlColors

/**
 * Calls [number]. With several SIMs and no default SIM, [chooseSim] is asked first. Returns false when the call
 * couldn't be placed (no phone permission).
 */
fun startCall(context: Context, number: String, chooseSim: (List<CallAccount>) -> Unit): Boolean {
    val clean = number.trim()
    if (clean.isEmpty()) return false
    if (!Phone.has(context, android.Manifest.permission.CALL_PHONE)) {
        Toast.makeText(context, context.getString(R.string.need_phone_permission), Toast.LENGTH_LONG).show()
        return false
    }
    val accounts = Phone.callAccounts(context)
    // Emergency numbers always go out straight away; Android picks the line.
    if (accounts.size > 1 && Phone.defaultAccount(context) == null && !Phone.isEmergency(context, clean)) {
        chooseSim(accounts)
        return true
    }
    val ok = Phone.call(context, clean)
    if (!ok) Toast.makeText(context, context.getString(R.string.call_failed), Toast.LENGTH_SHORT).show()
    return ok
}

@Composable
fun SimSheet(accounts: List<CallAccount>, onDismiss: () -> Unit, onPick: (CallAccount) -> Unit) {
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.choose_sim), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            accounts.forEach { a ->
                GroupRow(a.label, icon = Icons.Outlined.SimCard, iconTint = if (a.color != 0) Color(a.color) else CtrlColors.Text, onClick = { onPick(a) })
            }
        }
    }
}

fun sendMessage(context: Context, number: String) {
    if (!context.launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
        Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
    }
}

/** "Add to contacts" in your contacts app (ContactCtrl or any other), with the number filled in. */
fun addToContacts(context: Context, number: String) {
    val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!context.launch(i)) Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
}

fun openContact(context: Context, uri: Uri) {
    if (!context.launch(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
        Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
    }
}
