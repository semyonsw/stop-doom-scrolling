package am.onex.stopdoom.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The block screen.
 *
 * Self-contained colours rather than MaterialTheme: this composes inside a raw
 * window with no Activity theme, and it should look identical regardless of what
 * app it is covering.
 *
 * The countdown is the working part. An instantly dismissable screen trains you
 * to tap through without reading; a few seconds of enforced pause is what
 * actually breaks the reflex.
 */
@Composable
fun BlockOverlayContent(
    title: String,
    reason: String,
    frictionSeconds: Int,
    usedTodaySeconds: Int?,
    replacements: List<String>,
    onDismiss: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(frictionSeconds.coerceAtLeast(0)) }

    LaunchedEffect(frictionSeconds) {
        while (remaining > 0) {
            delay(1_000L)
            remaining -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF21B1C22)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = Color(0xFF7BD88F),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = reason,
                color = Color(0xFFE6E7EB),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )

            if (usedTodaySeconds != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${formatDuration(usedTodaySeconds)} on this today",
                    color = Color(0xFF9AA0AC),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (replacements.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = "Instead:",
                    color = Color(0xFF9AA0AC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                replacements.take(3).forEach { item ->
                    Text(
                        text = item,
                        color = Color(0xFFE6E7EB),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onDismiss,
                enabled = remaining <= 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7BD88F),
                    contentColor = Color(0xFF11121A),
                    disabledContainerColor = Color(0xFF2B2D36),
                    disabledContentColor = Color(0xFF6B7280),
                ),
            ) {
                Text(if (remaining > 0) "Wait ${remaining}s" else "Okay")
            }
        }
    }
}

fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}
