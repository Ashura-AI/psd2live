package io.github.psd2live.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.theme.CompactToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumberSpinnerTest {
    @Test fun focusingAnUnchangedNumberDoesNotStartAnEdit() = runBlocking {
        withContext(Dispatchers.Main) {
            val focus = FocusRequester()
            var started = 0
            var ended = 0
            var focused = false
            val scene = ImageComposeScene(160, 40, density = Density(1f)) {
                CompactToolTheme {
                    CompactNumberSpinner(2.0, {}, Modifier.width(140.dp).focusRequester(focus).onFocusChanged { focused = it.hasFocus },
                        onEditStart = { started++ }, onEditEnd = { ended++ })
                }
            }
            try {
                scene.render(0).close()
                yield()
                focus.requestFocus()
                repeat(3) { scene.render((it + 1) * 16_000_000L).close(); yield() }
                assertTrue(focused, "The number field must actually receive focus")
                assertEquals(0, started, "Focus notifications must not open a number edit")
                assertEquals(0, ended)
            } finally {
                scene.close()
            }
        }
    }
}
