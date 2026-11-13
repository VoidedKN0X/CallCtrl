package com.vknxworks.callctrl.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.data.Blocked
import com.vknxworks.callctrl.data.Phone
import com.vknxworks.callctrl.ui.components.CtrlSquareButton
import com.vknxworks.callctrl.ui.components.Group
import com.vknxworks.callctrl.ui.components.GroupRow
import com.vknxworks.callctrl.ui.components.MessageView
import com.vknxworks.callctrl.ui.components.NameDialog
import com.vknxworks.callctrl.ui.components.ScreenTopBar
import com.vknxworks.callctrl.ui.theme.CtrlColors
import kotlinx.coroutines.launch

/** Numbers that can't call or text you. Android keeps this list and does the blocking, for every app. */
@Composable
fun BlockedScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(version) { list = Blocked.list(context) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.blocked_numbers), actions = {
            CtrlSquareButton(Icons.Outlined.Add, stringResource(R.string.block_number), onClick = { adding = true })
        })
        val l = list
        if (l != null && l.isEmpty()) {
            MessageView(Icons.Outlined.Block, stringResource(R.string.blocked_empty), stringResource(R.string.blocked_empty_body))
        } else if (l != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Group(footer = stringResource(R.string.blocked_footer)) {
                        l.forEach { (_, number) ->
                            GroupRow(
                                Phone.format(context, number),
                                icon = Icons.Outlined.Block,
                                trailing = {
                                    CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.unblock), onClick = {
                                        scope.launch { Blocked.unblock(context, number); version++ }
                                    }, size = 40.dp, tint = CtrlColors.Muted)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        NameDialog(stringResource(R.string.block_number), "", confirm = stringResource(R.string.block), onDismiss = { adding = false }) { number ->
            adding = false
            scope.launch {
                if (Blocked.block(context, number)) version++
                else Toast.makeText(context, context.getString(R.string.block_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
