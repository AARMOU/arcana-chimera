package com.arcanatable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context

/** Single globally accessible window across XP, other skins and the classic/home UI. */
internal object ChimeraLegal {
    var open by mutableStateOf(false)
    const val GPL = "legal/GPL-3.0.txt"
    const val SCOPE = "legal/CHIMERA-LICENSE-SCOPE.md"
    const val SOURCE = "legal/SOURCE-ACCESS.txt"
    const val NOTICES = "legal/GPL-RELEASE-NOTICES.md"
}

internal data class ChimeraSourceRecord(val published: Boolean, val url: String = "",
                                        val sha256: String = "")

/** A generic repository URL or a mismatched version is NOT corresponding source. */
internal object ChimeraLegalSource {
    fun parse(content: String, installedVersion: String): ChimeraSourceRecord {
        val fields = content.lineSequence().mapNotNull { line ->
            val pos = line.indexOf('=')
            if (pos <= 0) null else line.substring(0, pos).trim() to line.substring(pos + 1).trim()
        }.toMap()
        val version = fields["SOURCE_VERSION_NAME"] ?: ""
        val url = fields["SOURCE_URL"] ?: ""
        val hash = fields["SOURCE_SHA256"] ?: ""
        val valid = version == installedVersion && installedVersion.isNotBlank() &&
            url.startsWith("https://") && url.none { it.isWhitespace() } &&
            Regex("^[0-9a-fA-F]{64}$").matches(hash)
        return if (valid) ChimeraSourceRecord(true, url, hash) else ChimeraSourceRecord(false)
    }
}

private fun Context.legalAsset(path: String): String = runCatching {
    assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}.getOrElse { "Required license/source information is missing from this build. Release is blocked." }

@Composable
internal fun ChimeraLegalDialog() {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    val gpl = remember(context) { context.legalAsset(ChimeraLegal.GPL) }
    val scope = remember(context) { context.legalAsset(ChimeraLegal.SCOPE) }
    val notices = remember(context) { context.legalAsset(ChimeraLegal.NOTICES) }
    val source = remember(context, version) {
        ChimeraLegalSource.parse(context.legalAsset(ChimeraLegal.SOURCE), version)
    }
    var tab by remember { mutableIntStateOf(0) }
    val body = when (tab) {
        0 -> "Arcana Table with Forge\n\nThe Forge engine is licensed under GNU GPLv3. " +
            "Independently authored Chimera glue/overlay code is intended to be GPL-3.0-only " +
            "where contributors hold the relevant rights. Third-party libraries, card data, " +
            "artwork and other materials retain their respective rights and notices.\n\n" +
            "App version: " + version + "\n\n" +
            "The build is not approved for distribution merely because these notices appear."
        1 -> gpl
        2 -> scope + "\n\n--- Release-readiness notes ---\n\n" + notices
        else -> if (source.published)
            "Version-matched corresponding-source location:\n" + source.url +
            "\n\nSource SHA-256: " + source.sha256 +
            "\n\nA link alone does not prove that the source is complete; see the distribution review."
        else
            "Corresponding source for this exact build is NOT YET PUBLISHED.\n\n" +
            "This build must not be distributed until the version-matched source package, " +
            "hash, required notices and GPL/Google Play release obligations are reviewed."
    }
    Dialog(onDismissRequest = { ChimeraLegal.open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.82f)
            .testTag("chimera-legal-dialog"),
            shape = RoundedCornerShape(12.dp), color = Color(0xFF192132)) {
            Column(Modifier.padding(14.dp)) {
                Text("Licenses & source", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 19.sp)
                Text("Arcana / Forge • " + version, color = Color(0xFFB8D0E5),
                    fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("About", "GPLv3", "Notices", "Source").forEachIndexed { index, name ->
                        TextButton(onClick = { tab = index }, modifier = Modifier.weight(1f)) {
                            Text(name, fontSize = 12.sp, color = if (tab == index)
                                Color(0xFFFFD875) else Color.White)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SelectionContainer {
                        Text(body, color = Color(0xFFF0F2F6), fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .testTag("chimera-legal-content"))
                    }
                }
                TextButton(onClick = { ChimeraLegal.open = false },
                    modifier = Modifier.fillMaxWidth().testTag("chimera-legal-close")) {
                    Text("Close", color = Color(0xFFFFD875))
                }
            }
        }
    }
}
