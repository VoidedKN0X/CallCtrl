package com.vknxworks.callctrl.ui.people

import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as CPhone
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.CallAccount
import com.vknxworks.callctrl.data.Person
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.ui.common.ContactAvatar
import com.vknxworks.callctrl.ui.common.launch
import com.vknxworks.callctrl.ui.components.CtrlBottomSheet
import com.vknxworks.callctrl.ui.components.CtrlSquareButton
import com.vknxworks.callctrl.ui.components.CtrlTextField
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.MessageView
import com.vknxworks.callctrl.ui.components.SectionLabel
import com.vknxworks.callctrl.ui.components.TabTitle
import com.vknxworks.callctrl.ui.components.bottomClearance
import com.vknxworks.callctrl.ui.dialer.SimSheet
import com.vknxworks.callctrl.ui.dialer.openContact
import com.vknxworks.callctrl.ui.dialer.startCall
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlType
import com.vknxworks.callctrl.ui.theme.Gutter

/** Contacts with a phone number: tap to call, long-press to open them in your contacts app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen() {
    val context = LocalContext.current
    val c = context.container
    val people by c.contacts.people.collectAsStateWithLifecycle()
    val loaded by c.contacts.loaded.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var choose by remember { mutableStateOf<Person?>(null) }
    var sims by remember { mutableStateOf<Pair<String, List<CallAccount>>?>(null) }
    val shown = remember(people, query) { if (query.isBlank()) people else people.filter { it.matches(query) } }

    fun call(number: String) {
        startCall(context, number) { sims = number to it }
    }
    fun tap(p: Person) {
        if (p.phones.size == 1) call(p.phones.first().number) else choose = p
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(
            stringResource(R.string.tab_contacts),
            trailing = {
                CtrlSquareButton(Icons.Outlined.PersonAdd, stringResource(R.string.add_contact), onClick = {
                    val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!context.launch(i)) Toast.makeText(context, context.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
                })
            },
        )
        CtrlTextField(
            value = query,
            onValueChange = { query = it.take(100) },
            placeholder = { Text(stringResource(R.string.search_contacts)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({
                CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.clear), onClick = { query = "" }, size = 36.dp, tint = CtrlColors.Muted)
            }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
        )
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CtrlColors.AccentText) }
            people.isEmpty() -> MessageView(Icons.Outlined.Contacts, stringResource(R.string.no_contacts), stringResource(R.string.no_contacts_body), modifier = Modifier.bottomClearance())
            shown.isEmpty() -> MessageView(Icons.Outlined.Search, stringResource(R.string.search_none, query.trim()), modifier = Modifier.bottomClearance())
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                val favourites = if (query.isBlank()) shown.filter { it.starred } else emptyList()
                if (favourites.isNotEmpty()) {
                    stickyHeader(key = "h-fav") { Header(stringResource(R.string.favourites)) }
                    items(favourites, key = { "f${it.id}" }) { p -> PersonRow(p, onTap = { tap(p) }, onLong = { openContact(context, p.lookupUri) }) }
                }
                val groups = shown.groupBy { p -> p.name.firstOrNull()?.takeIf { it.isLetter() }?.let { Person.fold(it.toString()).uppercase().take(1) } ?: "#" }
                for ((letter, group) in groups) {
                    stickyHeader(key = "h-$letter") { Header(letter) }
                    items(group, key = { it.id }) { p -> PersonRow(p, onTap = { tap(p) }, onLong = { openContact(context, p.lookupUri) }) }
                }
                item { Spacer(Modifier.bottomClearance(0.dp)) }
            }
        }
    }

    choose?.let { p ->
        CtrlBottomSheet(onDismissRequest = { choose = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                p.phones.forEach { ph ->
                    GroupRow(
                        Phone.format(context, ph.number),
                        CPhone.getTypeLabel(context.resources, ph.type, ph.label).toString(),
                        icon = Icons.Outlined.Call,
                        iconTint = CtrlColors.AccentText,
                        onClick = { choose = null; call(ph.number) },
                    )
                }
                GroupRow(stringResource(R.string.open_contact), icon = Icons.Outlined.Person, onClick = { choose = null; openContact(context, p.lookupUri) })
            }
        }
    }
    sims?.let { (number, list) ->
        SimSheet(list, onDismiss = { sims = null }) { a ->
            sims = null
            if (!Phone.call(context, number, a.handle)) Toast.makeText(context, context.getString(R.string.call_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun Header(text: String) {
    Box(Modifier.fillMaxWidth().background(CtrlColors.Background).padding(horizontal = Gutter, vertical = 8.dp)) {
        SectionLabel(text, color = CtrlColors.AccentText)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(p: Person, onTap: () -> Unit, onLong: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLong).heightIn(min = 64.dp).padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(p.name, p.photoUri)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val first = p.phones.firstOrNull { it.primary } ?: p.phones.first()
            Text(
                Phone.format(context, first.number) + if (p.phones.size > 1) "  +${p.phones.size - 1}" else "",
                style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1,
            )
        }
        if (p.starred) Icon(Icons.Outlined.Star, null, Modifier.size(18.dp), tint = CtrlColors.Muted)
    }
}
