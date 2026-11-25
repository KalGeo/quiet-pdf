// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private const val SOURCE_URL = "https://github.com/KalGeo/quiet-pdf"
private const val PRIVACY_URL = "https://github.com/KalGeo/quiet-pdf/blob/main/PRIVACY.md"

/**
 * Version, license, and links to the source code and the privacy policy. The links open in
 * the browser; the app itself has no internet access.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }
    val open = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // No browser installed: nothing to open the link with.
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { open(PRIVACY_URL) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.privacy_policy))
                }
                TextButton(onClick = { open(SOURCE_URL) }) {
                    Text(stringResource(R.string.source_code))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
