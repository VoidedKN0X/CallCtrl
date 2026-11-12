package com.vknxworks.callctrl.ui.dialer

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vknxworks.callctrl.ui.theme.CtrlColors
import com.vknxworks.callctrl.ui.theme.CtrlShapes
import com.vknxworks.callctrl.ui.theme.CtrlType

private val KEYS = listOf(
    listOf('1' to "", '2' to "ABC", '3' to "DEF"),
    listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
    listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
    listOf('*' to "", '0' to "+", '#' to ""),
)

/**
 * The phone keypad. [onKey] fires as a key goes down (so call tones start right away), [onKeyUp] when it's let go,
 * and [onLongKey] on a long press (0 for +, 1 for voicemail).
 */
@Composable
fun DialPad(
    onKey: (Char) -> Unit,
    onKeyUp: () -> Unit = {},
    onLongKey: ((Char) -> Unit)? = null,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 64.dp,
    haptics: Boolean = true,
) {
    val view = LocalView.current
    // The keys keep their gesture handlers across recompositions; these always call the newest lambdas.
    val press by rememberUpdatedState(onKey)
    val keyUp by rememberUpdatedState(onKeyUp)
    val longKey by rememberUpdatedState(onLongKey)
    val hasLong = onLongKey != null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in KEYS) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((digit, letters) in row) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(keyHeight)
                            .clip(CtrlShapes.Key)
                            .background(CtrlColors.Surface)
                            .border(1.dp, CtrlColors.Outline, CtrlShapes.Key)
                            .semantics {
                                role = Role.Button
                                contentDescription = if (letters.isNotEmpty() && digit != '0') "$digit $letters" else digit.toString()
                                onClick { press(digit); keyUp(); true }
                            }
                            .pointerInput(digit, hasLong, haptics) {
                                detectTapGestures(
                                    onPress = {
                                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        press(digit)
                                        tryAwaitRelease()
                                        keyUp()
                                    },
                                    onLongPress = if (hasLong && (digit == '0' || digit == '1')) ({ _ ->
                                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        longKey?.invoke(digit)
                                    }) else null,
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(digit.toString(), style = CtrlType.Key, color = CtrlColors.Text)
                            if (letters.isNotEmpty()) Text(letters, style = CtrlType.Label.copy(fontSize = 10.sp), color = CtrlColors.Muted)
                        }
                    }
                }
            }
        }
    }
}
