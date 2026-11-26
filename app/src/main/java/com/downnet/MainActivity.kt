package com.downnet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.downnet.MainViewModel.State
import com.downnet.ui.DownloadList
import com.downnet.ui.QualitySheet
import com.downnet.ui.theme.DownNetTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = intent?.getStringExtra(EXTRA_INCOMING_URL)
        setContent {
            DownNetTheme {
                DownNetApp(startUrl = incoming)
            }
        }
    }

    companion object {
        const val EXTRA_INCOMING_URL = "com.downnet.extra.INCOMING_URL"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownNetApp(startUrl: String?) {
    val vm: MainViewModel = viewModel()
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val downloads by vm.downloads.collectAsState()
    var link by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    // Progress/completion notifications need POST_NOTIFICATIONS on 13+; ask
    // once when the first download starts (never at cold start).
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(startUrl) {
        startUrl?.let { vm.onLink(it, fromShare = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.paste_hint)) },
                singleLine = true,
            )
            TextButton(
                onClick = { clipboard.getText()?.text?.let { link = it } },
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.paste)) }
            if (link.isNotBlank()) {
                Button(
                    onClick = {
                        maybeAskNotificationPermission()
                        vm.onLink(link, fromShare = false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.download)) }
            }

            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                State.Idle -> if (downloads.isEmpty()) EmptyState()
                State.Resolving -> StatusCard(stringResource(R.string.resolving_link))
                is State.Extracting -> StatusCard(
                    stringResource(R.string.extracting_streams),
                    s.url,
                )
                is State.Failed -> ErrorCard(s.message) { vm.retry() }
                is State.Ready -> Unit // the sheet below is the UI for this state
            }

            if (downloads.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.queue_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                DownloadList(downloads)
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    (state as? State.Ready)?.let { ready ->
        QualitySheet(
            info = ready.info,
            platform = ready.platform,
            muxed = ready.muxed,
            audio = ready.audio,
            onPick = { choice ->
                maybeAskNotificationPermission()
                vm.startDownload(ready.info, choice.format)
            },
            onDismiss = { vm.dismissResult() },
        )
    }
}

@Composable
private fun StatusCard(label: String, detail: String? = null) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.extraction_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EntryMode(stringResource(R.string.entry_share), MaterialTheme.colorScheme.primary)
        EntryMode(stringResource(R.string.entry_copy), MaterialTheme.colorScheme.secondary)
        EntryMode(stringResource(R.string.entry_paste), MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun EntryMode(label: String, dot: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
