package com.awd.teledrive.ui.screens.logs

import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awd.teledrive.core.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M27: this screen used to show only AppLog.logs — but almost nothing logs
 * through AppLog (every class uses android.util.Log directly), so it was
 * always a blank black screen. It now reads this app's own logcat output
 * (own-pid logs need no READ_LOGS permission), falling back to the AppLog
 * buffer if logcat is unavailable. The read runs on Dispatchers.IO and is
 * capped at the last 500 lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val appLogs by AppLog.logs.collectAsState()
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logcatFailed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        logcatFailed = false
        val lines = withContext(Dispatchers.IO) { readOwnLogcat() }
        if (lines.isEmpty()) {
            logcatFailed = true
        } else {
            logLines = lines
        }
    }

    // Fallback: if logcat produced nothing, show the (usually small)
    // AppLog buffer so the screen is never just black.
    val displayLogs = if (logLines.isNotEmpty()) logLines else appLogs

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.logs_refresh))
                    }
                    IconButton(onClick = {
                        // The system logcat buffer can not be cleared by the
                        // app — this clears what is shown now (and the
                        // in-app buffer); Refresh re-reads logcat.
                        logLines = emptyList()
                        AppLog.clear()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear))
                    }
                }
            )
        }
    ) { padding ->
        if (displayLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (logcatFailed) {
                        stringResource(R.string.logs_read_failed)
                    } else {
                        stringResource(R.string.logs_empty)
                    },
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
            ) {
                items(displayLogs) { log ->
                    val color = when {
                        log.contains("ERROR") -> Color.Red
                        log.contains("INFO") -> Color.Cyan
                        else -> Color.White
                    }
                    Text(
                        text = log,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** Reads this app's own logcat (own pid — no READ_LOGS permission needed). */
private fun readOwnLogcat(): List<String> {
    return try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
        )
        val buffer = ArrayDeque<String>()
        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (buffer.size >= 500) buffer.removeFirst()
                buffer.addLast(line)
            }
        }
        proc.waitFor()
        buffer.toList()
    } catch (_: Exception) {
        emptyList()
    }
}
