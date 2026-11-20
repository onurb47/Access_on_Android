package com.bdfutsal.campro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bdfutsal.campro.AppViewModel
import com.bdfutsal.campro.data.MatchSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DELAYS = listOf(90, 120, 180, 300, 600)
private fun delayLabel(s: Int) = if (s < 120) "Start in $s seconds" else "Start in ${s / 60} minutes"

@Composable
fun SessionScreen(vm: AppViewModel, now: Long) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading("02 / MATCH CONTROL", "One session, two phones") { Pill("NO CLOUD ACCOUNT", dot = false) }

        BoxWithConstraints {
            val wide = maxWidth > 640.dp
            val create = @Composable { m: Modifier -> CreateCard(m, settings.matchName, settings.delaySeconds, vm) }
            val shared = @Composable { m: Modifier -> SharedCard(m, session, now, vm, context) }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { create(Modifier.weight(1f)); shared(Modifier.weight(1f)) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { create(Modifier.fillMaxWidth()); shared(Modifier.fillMaxWidth()) }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "01" to "CREATE" to "On either phone",
                "02" to "SHARE" to "Send to the other phone",
                "03" to "ARM" to "Both phones, screens on",
                "04" to "CLAP" to "One loud clap in view",
            ).forEach { (a, sub) ->
                BdCard(Modifier.weight(1f)) {
                    Kicker(a.first)
                    Text(a.second, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = Bd.Muted)
                }
            }
        }

        Text(
            "This phone's clock: " + SimpleDateFormat("HH:mm:ss.S", Locale.getDefault()).format(Date(now)),
            fontFamily = FontFamily.Monospace,
            color = Bd.Muted,
            fontSize = 12.sp,
        )
        Notice(
            "Timing is approximate.",
            "Each phone starts on its own clock; there's no pairing server. Android sets the time from the network, so phones are usually " +
                "close, but not frame-exact. Compare the clocks above on both phones, and use the clap or start beeps in the Sync lab to line clips up.",
        )
    }
}

@Composable
private fun CreateCard(modifier: Modifier, matchName: String, delay: Int, vm: AppViewModel) = BdCard(modifier) {
    Kicker("NEW MATCH")
    Text("Prepare your recording", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = matchName,
        onValueChange = { vm.setMatchName(it.take(80)) },
        label = { Text("Match name") },
        placeholder = { Text("e.g. University vs Coventry") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    FieldLabel("Shared countdown")
    var open by remember { mutableStateOf(false) }
    Column {
        GhostButton(delayLabel(delay) + "  ▾", { open = true }, Modifier.fillMaxWidth())
        DropdownMenu(open, { open = false }) {
            DELAYS.forEach { d -> DropdownMenuItem(text = { Text(delayLabel(d)) }, onClick = { vm.setDelay(d); open = false }) }
        }
    }
    PrimaryButton("Generate match link", { vm.createSession() }, Modifier.fillMaxWidth())
    Text(
        "Share the link to the other phone. Pick LEFT on one and RIGHT on the other, then tap Arm timed start on both before the countdown ends.",
        style = MaterialTheme.typography.bodySmall,
        color = Bd.Muted,
    )
}

@Composable
private fun SharedCard(modifier: Modifier, session: MatchSession?, now: Long, vm: AppViewModel, context: Context) = BdCard(modifier) {
    Kicker("SHARED SESSION")
    Text(session?.name ?: "No active match", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        session?.code?.toList()?.joinToString(" ") ?: "— — — — —",
        fontFamily = FontFamily.Monospace,
        fontSize = 32.sp,
        fontWeight = FontWeight.Black,
        color = Bd.Accent,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(
            "SCHEDULED START",
            session?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.startEpochMs)) } ?: "Not set",
            Modifier.weight(1f),
        )
        Stat(
            "COUNTDOWN",
            when {
                session == null -> "—:—"
                session.startEpochMs > now -> formatCountdown(session.startEpochMs - now)
                else -> "STARTED"
            },
            Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("Share to phone", { session?.let { share(context, it) } }, Modifier.weight(1f), enabled = session != null)
        GhostButton("Copy link", {
            session?.let {
                copy(context, shareText(it))
                Toast.makeText(context, "Match link copied", Toast.LENGTH_SHORT).show()
            }
        }, Modifier.weight(1f), enabled = session != null)
    }
    Text(
        "The link holds only the match name, code and start time. No recordings are sent.",
        style = MaterialTheme.typography.bodySmall,
        color = Bd.Muted,
    )

    var link by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val doJoin = {
        if (vm.join(link) != null) {
            link = ""; error = null
            Toast.makeText(context, "Joined match", Toast.LENGTH_SHORT).show()
        } else {
            error = "No BD Cam Pro match link found in that text."
        }
    }
    OutlinedTextField(
        value = link,
        onValueChange = { link = it; error = null },
        label = { Text("Have a match link?") },
        placeholder = { Text("Paste the shared message or link") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Paste", { link = paste(context) }, Modifier.weight(1f))
        PrimaryButton("Join", doJoin, Modifier.weight(1f), enabled = link.isNotBlank())
    }
    if (session != null) TextButton(onClick = vm::clearSession) { Text("Clear session", color = Bd.Muted) }
}

private fun shareText(s: MatchSession): String {
    val start = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.startEpochMs))
    return "BD Futsal Cam Pro match: ${s.name}\nCode ${s.code}, recording starts at $start.\n" +
        "Open in BD Cam Pro (or copy this whole message and use Paste → Join):\n${s.toLink()}"
}

private fun share(context: Context, s: MatchSession) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(s))
    context.startActivity(Intent.createChooser(intent, "Share match"))
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("BD Cam Pro match", text))
}

private fun paste(context: Context): String {
    val cm = context.getSystemService(ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
