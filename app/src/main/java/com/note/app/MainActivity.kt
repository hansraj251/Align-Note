package com.note.app

import android.Manifest
import com.note.app.backup.DriveBackupManager
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.note.app.data.Note
import com.note.app.data.NoteDatabase
import com.note.app.reminders.ReminderScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
private val Canvas = Color(0xFFFAF9F6)
private val Ink = Color(0xFF292A25)
private val Muted = Color(0xFF85867D)
private val Accent = Color(0xFF596347)
private val CardColors = listOf(
    0xFFFFFFFF.toInt(),
    0xFFFFF1CC.toInt(),
    0xFFE2F0E5.toInt(),
    0xFFE2EBFA.toInt(),
    0xFFF4E3EF.toInt(),
    0xFFF8E5D9.toInt()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent { NoteApp() }
    }
}

@Composable
private fun NoteApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { NoteDatabase.get(context).noteDao() }
    val scope = rememberCoroutineScope()
    val notes by dao.observeAll().collectAsState(initial = emptyList())
    val profilePrefs = remember {
        context.getSharedPreferences("note_profile", android.content.Context.MODE_PRIVATE)
    }
    var profileName by remember {
        mutableStateOf(profilePrefs.getString("name", "").orEmpty())
    }
    var profileMobile by remember {
        mutableStateOf(profilePrefs.getString("mobile", "").orEmpty())
    }

    var connectedEmail by remember {
        mutableStateOf(profilePrefs.getString("google_email", "").orEmpty())
    }
    var driveStatus by remember { mutableStateOf("") }
    var driveBusy by remember { mutableStateOf(false) }
    var pendingDriveAction by remember { mutableStateOf("connect") }
    var driveAccessToken by remember { mutableStateOf("") }
    var showChangeBackupWarning by remember { mutableStateOf(false) }
    var pendingChangeToken by remember { mutableStateOf("") }
    var pendingChangeEmail by remember { mutableStateOf("") }
    var autoBackupJob by remember { mutableStateOf<Job?>(null) }
    var driveStartupComplete by remember { mutableStateOf(false) }

    fun scheduleAutoBackup() {
        if (connectedEmail.isBlank() || driveAccessToken.isBlank()) return

        autoBackupJob?.cancel()
        autoBackupJob = scope.launch {
            driveBusy = true
            driveStatus = "Syncing to Google Drive"

            try {
                delay(1500)

                val email = DriveBackupManager.getConnectedEmail(driveAccessToken)
                if (!email.equals(connectedEmail, ignoreCase = true)) {
                    driveStatus = "Auto backup paused: Google account mismatch. Reconnect Drive."
                    return@launch
                }

                val result = DriveBackupManager.backup(context, driveAccessToken)
                result.modifiedTime?.let {
                    profilePrefs.edit()
                        .putString("last_drive_sync_modified_time", it)
                        .apply()
                }
                driveStatus = "Auto backup complete: ${result.noteCount} notes/tasks saved."
            } catch (e: Exception) {
                driveStatus = "Auto sync failed: ${e.message ?: "Check your Google Drive connection."}"
            } finally {
                driveBusy = false
            }
        }
    }

    val activity = context as ComponentActivity

    val performAuthorizedAction: (String, String) -> Unit = { action, token ->
        scope.launch {
            try {
                driveBusy = true
                when (action) {
                    "startup" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)
                        if (connectedEmail.isNotBlank() &&
                            !connectedEmail.equals(email, ignoreCase = true)
                        ) {
                            error(
                                "The authorized Gmail differs from the saved Drive account. " +
                                "Retry and authorize the intended Google account."
                            )
                        }

                        connectedEmail = email
                        profilePrefs.edit().putString("google_email", email).apply()

                        val remoteModified =
                            DriveBackupManager.getLatestBackupModifiedTime(token)
                        val lastRestored = profilePrefs.getString(
                            "last_drive_sync_modified_time", ""
                        ).orEmpty()

                        if (remoteModified.isNullOrBlank()) {
                            driveStatus = "No Drive backup found. Starting fresh."
                        } else if (remoteModified != lastRestored) {
                            val result = DriveBackupManager.restore(context, token)
                            profileName = profilePrefs.getString("name", "").orEmpty()
                            profileMobile = profilePrefs.getString("mobile", "").orEmpty()
                            profilePrefs.edit().putString(
                                "last_drive_sync_modified_time",
                                result.modifiedTime ?: remoteModified
                            ).apply()
                            driveStatus =
                                "Updated Drive backup restored: ${result.noteCount} notes/tasks."
                        } else {
                            driveStatus = "Google Drive is up to date."
                        }

                        driveStartupComplete = true
                    }
                    "change" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)
                        if (email.equals(connectedEmail, ignoreCase = true)) {
                            driveStatus = "This Gmail is already connected. Choose another account."
                        } else {
                            val remote = DriveBackupManager.getLatestBackupModifiedTime(token)
                            if (!remote.isNullOrBlank()) {
                                pendingChangeToken = token
                                pendingChangeEmail = email
                                showChangeBackupWarning = true
                                driveStatus = "A backup exists in $email. Confirm before syncing."
                            } else {
                                val result = DriveBackupManager.backup(context, token)
                                connectedEmail = email
                                profilePrefs.edit().putString("google_email", email).apply()
                                result.modifiedTime?.let {
                                    profilePrefs.edit().putString("last_drive_sync_modified_time", it).apply()
                                }
                                driveAccessToken = token
                                driveStatus = "Changed to $email. Synced ${result.noteCount} local notes/tasks."
                            }
                        }
                    }
                    "change-confirmed" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)
                        require(email.equals(pendingChangeEmail, ignoreCase = true)) {
                            "Selected Gmail changed. Please retry Change Account."
                        }
                        val result = DriveBackupManager.backup(context, token)
                        connectedEmail = email
                        profilePrefs.edit().putString("google_email", email).apply()
                        result.modifiedTime?.let {
                            profilePrefs.edit().putString("last_drive_sync_modified_time", it).apply()
                        }
                        driveAccessToken = token
                        pendingChangeToken = ""
                        pendingChangeEmail = ""
                        showChangeBackupWarning = false
                        driveStatus = "Changed to $email. Synced ${result.noteCount} local notes/tasks. Existing backups were preserved."
                    }
                    "connect" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)

                        if (connectedEmail.isNotBlank() &&
                            !connectedEmail.equals(email, ignoreCase = true)
                        ) {
                            error("A different Gmail was selected. Use Change Account to switch accounts safely.")
                        }

                        connectedEmail = email
                        profilePrefs.edit().putString("google_email", email).apply()

                        val remoteModified =
                            DriveBackupManager.getLatestBackupModifiedTime(token)

                        if (!remoteModified.isNullOrBlank()) {
                            val result = DriveBackupManager.restore(context, token)

                            profileName = profilePrefs.getString("name", "").orEmpty()
                            profileMobile = profilePrefs.getString("mobile", "").orEmpty()

                            profilePrefs.edit().putString(
                                "last_drive_sync_modified_time",
                                result.modifiedTime ?: remoteModified
                            ).apply()

                            driveStatus =
                                "Drive backup restored: ${result.noteCount} notes/tasks."
                        } else {
                            driveStatus =
                                "No Drive backup found. Your local notes/tasks were kept."
                        }

                        driveAccessToken = token
                        driveStartupComplete = true
                    }
                    "backup" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)
                        if (connectedEmail.isNotBlank() &&
                            !connectedEmail.equals(email, ignoreCase = true)
                        ) {
                            error("The authorized Gmail differs from the connected account. Connect the intended account first.")
                        }
                        connectedEmail = email
                        profilePrefs.edit().putString("google_email", email).apply()
                        val result = DriveBackupManager.backup(context, token)
                        result.modifiedTime?.let {
                            profilePrefs.edit().putString(
                                "last_drive_sync_modified_time", it
                            ).apply()
                        }
                        driveStatus = "Backup complete: \${result.noteCount} notes/tasks saved."
                    }
                    "clear-data" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)
                        if (
                            connectedEmail.isBlank() ||
                            !connectedEmail.equals(email, ignoreCase = true)
                        ) {
                            error("The authorized Gmail does not match the connected account. No local data was deleted.")
                        }

                        // Prevent a queued auto-backup from recreating deleted data.
                        autoBackupJob?.cancel()
                        autoBackupJob = null

                        // Delete Drive backups first. If this fails, keep local data intact.
                        val deletedBackups = DriveBackupManager.deleteAllBackups(token)

                        NoteDatabase.get(context).noteDao().clearAll()

                        profilePrefs.edit()
                            .remove("last_drive_sync_modified_time")
                            .apply()

                        driveAccessToken = token
                        driveStartupComplete = true
                        driveStatus =
                            "Clear complete: local notes/tasks cleared and $deletedBackups Drive backup(s) deleted. Profile and Google account were preserved."
                    }
                    "restore" -> {
                        val email = DriveBackupManager.getConnectedEmail(token)
                        if (connectedEmail.isNotBlank() &&
                            !connectedEmail.equals(email, ignoreCase = true)
                        ) {
                            error("The authorized Gmail differs from the connected account. Connect the intended account first.")
                        }
                        connectedEmail = email
                        profilePrefs.edit().putString("google_email", email).apply()
                        val result = DriveBackupManager.restore(context, token)
                        profileName = profilePrefs.getString("name", "").orEmpty()
                        profileMobile = profilePrefs.getString("mobile", "").orEmpty()
                        result.modifiedTime?.let {
                            profilePrefs.edit().putString(
                                "last_drive_sync_modified_time", it
                            ).apply()
                        }
                        driveStatus = "Restore complete: \${result.noteCount} notes/tasks restored."
                    }
                }
            } catch (e: Exception) {
                driveStatus = e.message ?: "Google Drive operation failed."
            } finally {
                driveBusy = false
            }
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val authResult = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(result.data)
                val token = authResult.accessToken
                if (!token.isNullOrBlank()) {
                    driveAccessToken = token
                    performAuthorizedAction(pendingDriveAction, token)
                } else {
                    driveBusy = false
                    driveStatus = "Google authorization did not return an access token."
                }
            } catch (e: Exception) {
                driveBusy = false
                driveStatus = e.message ?: "Google authorization failed."
            }
        } else {
            driveBusy = false
            driveStatus = "Google account authorization was cancelled. Connect to continue."
        }
    }

    val requestDriveAuthorization: (String) -> Unit = { action ->
        pendingDriveAction = action
        driveBusy = true
        driveStatus = "Waiting for Google authorization..."
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope("https://www.googleapis.com/auth/drive.appdata"),
                    Scope("https://www.googleapis.com/auth/userinfo.email")
                )
            )
            .setPrompt(
                if (action == "change") AuthorizationRequest.Prompt.SELECT_ACCOUNT
                else AuthorizationRequest.Prompt.NOT_SET
            )
            .build()

        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authResult ->
                if (authResult.hasResolution()) {
                    val pendingIntent = authResult.pendingIntent
                    if (pendingIntent != null) {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else {
                        driveBusy = false
                        driveStatus = "Google authorization needs another attempt."
                    }
                } else {
                    val token = authResult.accessToken
                    if (!token.isNullOrBlank()) {
                        driveAccessToken = token
                        performAuthorizedAction(action, token)
                    } else {
                        driveBusy = false
                        driveStatus = "Google authorization did not return an access token."
                    }
                }
            }
            .addOnFailureListener { error ->
                driveBusy = false
                driveStatus = error.message ?: "Could not connect to Google."
            }
    }

    LaunchedEffect(Unit) {
        requestDriveAuthorization("startup")
    }

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Canvas,
            surface = Canvas,
            onBackground = Ink,
            onSurface = Ink,
            secondaryContainer = Color(0xFFE7EBDD)
        )
    ) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                if (driveStartupComplete) NavigationBar(
                    containerColor = Color.White,
                    contentColor = Accent,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0; query = "" },
                        icon = { Icon(Icons.Outlined.Description, null) },
                        label = { Text("Note", fontWeight = FontWeight.Medium) }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1; query = "" },
                        icon = { Icon(Icons.Outlined.Checklist, null) },
                        label = { Text("To-do", fontWeight = FontWeight.Medium) }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2; query = ""; editorOpen = false },
                        icon = { Icon(Icons.Outlined.Person, null) },
                        label = { Text("Profile", fontWeight = FontWeight.Medium) }
                    )
                }
            },
            floatingActionButton = {
                if (driveStartupComplete && tab != 2) ExtendedFloatingActionButton(
                    onClick = {
                        editing = null
                        editorOpen = true
                    },
                    containerColor = Accent,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(if (tab == 0) "New note" else "Add task") }
                )
            }
        ) { padding ->
            if (!driveStartupComplete) {
                DriveStartupScreen(
                    busy = driveBusy,
                    status = driveStatus,
                    onConnect = { requestDriveAuthorization("startup") },
                    modifier = Modifier.padding(padding)
                )
            } else if (tab == 2) {
                ProfileScreen(
                    name = profileName,
                    mobile = profileMobile,
                    onNameChange = { profileName = it },
                    onMobileChange = { profileMobile = it },
                    connectedEmail = connectedEmail,
                    driveStatus = driveStatus,
                    driveBusy = driveBusy,
                    onConnectGoogle = { requestDriveAuthorization("connect") },
                    onChangeGoogle = { requestDriveAuthorization("change") },
                    showChangeBackupWarning = showChangeBackupWarning,
                    changeBackupEmail = pendingChangeEmail,
                    onConfirmChangeAccount = {
                        showChangeBackupWarning = false
                        performAuthorizedAction("change-confirmed", pendingChangeToken)
                    },
                    onCancelChangeAccount = {
                        showChangeBackupWarning = false
                        pendingChangeToken = ""
                        pendingChangeEmail = ""
                        driveStatus = "Account change cancelled. No data was uploaded or deleted."
                    },
                    onBackupNow = { requestDriveAuthorization("backup") },
                    onRestore = { requestDriveAuthorization("restore") },
                    onClearData = { requestDriveAuthorization("clear-data") },
                    onSave = {
                        profilePrefs.edit()
                            .putString("name", profileName.trim())
                            .putString("mobile", profileMobile.trim())
                            .apply()
                    }
                )
            } else {
            Column(
                Modifier.fillMaxSize()
                    .background(Canvas)
                    .padding(padding)
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (tab == 0) "Notes" else "Tasks",
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            letterSpacing = (-0.7).sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (tab == 0) "A little space for every thought"
                            else "One thing at a time.",
                            fontSize = 13.sp,
                            color = Muted
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFFE8EBDD), CircleShape)
                            .clickable {
                                searchVisible = !searchVisible
                                if (!searchVisible) query = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = if (searchVisible) "Close search" else "Search",
                            tint = Accent
                        )
                    }
                }

                if (driveBusy && driveStatus.contains("Syncing", ignoreCase = true)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (driveStatus.contains("Syncing", ignoreCase = true)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Accent
                            )
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(
                                imageVector = if (
                                    driveStatus.contains("complete", ignoreCase = true) ||
                                    driveStatus.contains("saved", ignoreCase = true)
                                ) Icons.Outlined.Description
                                else Icons.Outlined.Description,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = driveStatus,
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))

                if (searchVisible) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFFF0EFE9), RoundedCornerShape(16.dp))
                        .padding(horizontal = 15.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Search, null, tint = Muted)
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Ink, fontSize = 14.sp
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        if (tab == 0) "Search your notes"
                                        else "Search your tasks",
                                        color = Muted, fontSize = 14.sp
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                }

                Spacer(Modifier.height(20.dp))

                val visible = notes.filter { note ->
                    note.isTodo == (tab == 1) &&
                        (note.title.contains(query, true) ||
                         note.content.contains(query, true))
                }.sortedWith(
                    compareBy<Note> { if (tab == 1) it.isCompleted else !it.pinned }
                        .thenByDescending { it.updatedAt }
                )

                if (visible.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(bottom = 70.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(68.dp)
                                    .background(Color(0xFFECEDE5), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (tab == 0) Icons.Outlined.Description
                                    else Icons.Outlined.Checklist,
                                    null,
                                    tint = Accent,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (query.isNotBlank()) "Nothing found"
                                else if (tab == 0) "A fresh page"
                                else "Clear your mind",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (query.isNotBlank()) "Try another search"
                                else if (tab == 0) "Tap New note to capture an idea."
                                else "Add a task to get started.",
                                color = Muted,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else if (tab == 0) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        gridItems(visible, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                onClick = { editing = note; editorOpen = true },
                                onPin = {
                                    scope.launch {
                                        dao.update(note.copy(
                                            pinned = !note.pinned,
                                            updatedAt = System.currentTimeMillis()
                                        ))
                                        scheduleAutoBackup()
                                    }
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        listItems(visible, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onToggle = {
                                    scope.launch {
                                        dao.update(task.copy(
                                            isCompleted = !task.isCompleted,
                                            updatedAt = System.currentTimeMillis()
                                        ))
                                        scheduleAutoBackup()
                                    }
                                },
                                onClick = { editing = task; editorOpen = true }
                            )
                        }
                    }
                }
            }
            }
        }

        if (editorOpen && tab != 2) {
            NoteEditor(
                initial = editing,
                todoMode = tab == 1,
                onDismiss = { editorOpen = false; editing = null },
                onSave = { result ->
                    scope.launch {
                        if (result.id == 0L) {
                            val newId = dao.insert(result)
                            if (result.reminderAt != null) {
                                ReminderScheduler.schedule(context, result.copy(id = newId))
                            }
                        } else {
                            ReminderScheduler.cancel(context, result.id)
                            dao.update(result)
                            if (result.reminderAt != null) {
                                ReminderScheduler.schedule(context, result)
                            }
                        }
                            scheduleAutoBackup()
                    }
                    editorOpen = false
                    editing = null
                },
                onDelete = { item ->
                    ReminderScheduler.cancel(context, item.id)
                    scope.launch {
                        dao.delete(item)
                        scheduleAutoBackup()
                    }
                    editorOpen = false
                    editing = null
                }
            )
        }
    }
}

@Composable
private fun DriveStartupScreen(
    busy: Boolean,
    status: String,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Connect Google Drive",
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Text(
                "Connect your Google account to check for your latest notes and tasks backup.",
                fontSize = 14.sp,
                color = Muted
            )
            if (busy) {
                CircularProgressIndicator(color = Accent)
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Connect / Retry")
                }
            }
            if (status.isNotBlank()) {
                Text(status, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onPin: () -> Unit
) {
    val bg = Color(note.color.takeIf { it != 0 } ?: CardColors[0])
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, Color(0x14000000)
        )
    ) {
        Column(
            Modifier.padding(14.dp).heightIn(min = 112.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    note.title.ifBlank { "Untitled" },
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Ink
                )
                IconButton(
                    onClick = onPin,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (note.pinned)
                            Icons.Filled.PushPin
                        else
                            Icons.Outlined.PushPin,
                        contentDescription = if (note.pinned) "Unpin note" else "Pin note",
                        tint = if (note.pinned) Accent else Muted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note.content,
                    color = Color(0xFF62635B),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 7
                )
            }
            Spacer(Modifier.height(10.dp))

        }
    }
}

@Composable
private fun TaskCard(
    task: Note,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFECEBE5))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = Accent)
            )
            Column(
                Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 5.dp)
            ) {
                Text(
                    task.title.ifBlank { "Untitled task" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (task.isCompleted) Muted else Ink,
                    textDecoration = if (task.isCompleted)
                        TextDecoration.LineThrough else TextDecoration.None
                )
                if (task.content.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(task.content, color = Muted, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }
}



@Composable
private fun ProfileScreen(
    name: String,
    mobile: String,
    onNameChange: (String) -> Unit,
    onMobileChange: (String) -> Unit,
    connectedEmail: String,
    driveStatus: String,
    driveBusy: Boolean,
    onConnectGoogle: () -> Unit,
    onChangeGoogle: () -> Unit,
    showChangeBackupWarning: Boolean,
    changeBackupEmail: String,
    onConfirmChangeAccount: () -> Unit,
    onCancelChangeAccount: () -> Unit,
    onBackupNow: () -> Unit,
    onRestore: () -> Unit,
    onClearData: () -> Unit,
    onSave: () -> Unit
) {
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showClearDataConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 52.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Profile",
            fontSize = 29.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )
        Text(
            text = "Your personal details",
            fontSize = 14.sp,
            color = Muted
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = mobile,
                    onValueChange = onMobileChange,
                    label = { Text("Mobile number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Save profile")
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Google Account", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (connectedEmail.isBlank()) "Not connected"
                    else connectedEmail,
                    color = if (connectedEmail.isBlank()) Muted else Accent,
                    fontSize = 14.sp
                )

                if (connectedEmail.isBlank()) {
                    Button(
                        onClick = onConnectGoogle,
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (driveBusy) "Connecting..." else "Connect Google Account")
                    }
                } else {
                    Button(
                        onClick = onBackupNow,
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Backup Now")
                    }
                    OutlinedButton(
                        onClick = { showRestoreConfirm = true },
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Restore Backup")
                    }
                    TextButton(
                        onClick = onChangeGoogle,
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Change Account")
                    }

                    OutlinedButton(
                        onClick = { showClearDataConfirm = true },
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Clear Data", color = Color.Red)
                    }
                }

                if (driveBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (driveStatus.isNotBlank()) {
                    Text(driveStatus, color = Muted, fontSize = 12.sp)
                }
            }
        }

        if (showChangeBackupWarning) {
            AlertDialog(
                onDismissRequest = onCancelChangeAccount,
                containerColor = Color.White,
                title = { Text("Backup already exists") },
                text = {
                    Text(
                        "A backup already exists in $changeBackupEmail. Continuing uploads the notes and tasks currently on this device as a new backup. Existing backup files will be kept. Nothing will be restored to this device."
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmChangeAccount) {
                        Text("Continue & Sync")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelChangeAccount) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showClearDataConfirm) {
            AlertDialog(
                onDismissRequest = { showClearDataConfirm = false },
                containerColor = Color.White,
                title = { Text("Clear all data?") },
                text = {
                    Text(
                        "This permanently deletes all local notes/tasks and all Note backup files from the connected Google Drive account. " +
                            "Your profile name, mobile number and connected Gmail will remain. This action cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDataConfirm = false
                            onClearData()
                        }
                    ) {
                        Text("Delete Everything", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDataConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showRestoreConfirm) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirm = false },
                title = { Text("Restore backup?") },
                text = {
                    Text("Restoring replaces the notes and tasks currently on this device with the latest backup from Google Drive. Continue only if you want to replace local data.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRestoreConfirm = false
                            onRestore()
                        }
                    ) { Text("Restore") }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Text(
            text = "Your details are saved on this device.",
            fontSize = 12.sp,
            color = Muted
        )
    }
}

@Composable
private fun NoteEditor(
    initial: Note?,
    todoMode: Boolean,
    onDismiss: () -> Unit,
    onSave: (Note) -> Unit,
    onDelete: (Note) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var pinned by remember(initial?.id) { mutableStateOf(initial?.pinned ?: false) }
    var color by remember(initial?.id) {
        mutableIntStateOf(initial?.color ?: CardColors[0])
    }
    var completed by remember(initial?.id) {
        mutableStateOf(initial?.isCompleted ?: false)
    }
    var reminderAt by remember(initial?.id) {
        mutableStateOf(initial?.reminderAt)
    }
    var repeat by remember(initial?.id) {
        mutableStateOf(initial?.repeat ?: "NONE")
    }
    var repeatDialogOpen by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Canvas,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 650.dp)
                    .verticalScroll(rememberScrollState()).padding(22.dp)
            ) {
                Text(
                    if (initial != null) {
                        if (todoMode) "Edit task" else "Edit note"
                    } else {
                        if (todoMode) "New task" else "New note"
                    },
                    fontSize = 23.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (todoMode) "Task title" else "Title") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(if (todoMode) "Details (optional)" else "Your thoughts") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 3
                )
                OutlinedButton(
                    onClick = {
                        val calendar = Calendar.getInstance().apply {
                            reminderAt?.let { timeInMillis = it }
                        }
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                calendar.set(Calendar.YEAR, year)
                                calendar.set(Calendar.MONTH, month)
                                calendar.set(Calendar.DAY_OF_MONTH, day)
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                                        calendar.set(Calendar.MINUTE, minute)
                                        calendar.set(Calendar.SECOND, 0)
                                        calendar.set(Calendar.MILLISECOND, 0)
                                        reminderAt = calendar.timeInMillis
                                    },
                                    calendar.get(Calendar.HOUR_OF_DAY),
                                    calendar.get(Calendar.MINUTE),
                                    false
                                ).show()
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.Alarm, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        reminderAt?.let {
                            SimpleDateFormat(
                                "d MMM yyyy, h:mm a", Locale.getDefault()
                            ).format(Date(it))
                        } ?: "Set reminder"
                    )
                }

                if (reminderAt != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { repeatDialogOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.Alarm, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (repeat) {
                                    "DAILY" -> "Repeats daily"
                                    "WEEKDAYS" -> "Repeats on weekdays"
                                    "WEEKENDS" -> "Repeats on weekends"
                                    "WEEKLY" -> "Repeats weekly"
                                    "MONTHLY" -> "Repeats monthly"
                                    "YEARLY" -> "Repeats yearly"
                                    else -> "Does not repeat"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                reminderAt = null
                                repeat = "NONE"
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, Color(0xFFD7D8CE)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF6D705F)
                            ),
                            contentPadding = PaddingValues(
                                horizontal = 22.dp,
                                vertical = 9.dp
                            )
                        ) {
                            Text("Clear reminder", fontSize = 13.sp)
                        }
                    }
                }

                if (todoMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = completed,
                            onCheckedChange = { completed = it }
                        )
                        Text("Mark as completed")
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                    Text("Choose a colour", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        CardColors.forEach { swatch ->
                            Box(
                                Modifier.size(31.dp)
                                    .background(Color(swatch), CircleShape)
                                    .clickable { color = swatch },
                                contentAlignment = Alignment.Center
                            ) {
                                if (color == swatch) {
                                    Box(
                                        Modifier.size(11.dp)
                                            .background(Accent, CircleShape)
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = pinned,
                            onCheckedChange = { pinned = it }
                        )
                        Text("Pin this note")
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (initial != null) {
                        TextButton(onClick = { onDelete(initial) }) {
                            Text("Delete", color = Color(0xFFB33A32))
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    Button(
                        onClick = {
                            if (title.isNotBlank() || content.isNotBlank()) {
                                onSave(
                                    Note(
                                        id = initial?.id ?: 0L,
                                        title = title.trim(),
                                        content = content.trim(),
                                        pinned = pinned,
                                        color = color,
                                        reminderAt = reminderAt,
                                        repeat = if (reminderAt == null) "NONE" else repeat,
                                        updatedAt = System.currentTimeMillis(),
                                        isTodo = initial?.isTodo ?: todoMode,
                                        isCompleted = if (todoMode) completed
                                            else initial?.isCompleted ?: false
                                    )
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (repeatDialogOpen) {
        val options = listOf(
            Triple("NONE", "Never", "One-time reminder"),
            Triple("DAILY", "Daily", "Repeat every day"),
            Triple("WEEKDAYS", "Weekdays", "Monday to Friday"),
            Triple("WEEKENDS", "Weekends", "Saturday and Sunday"),
            Triple("WEEKLY", "Weekly", "Repeat every 7 days"),
            Triple("MONTHLY", "Monthly", "Repeat on the same day each month"),
            Triple("YEARLY", "Yearly", "Repeat annually")
        )
        Dialog(onDismissRequest = { repeatDialogOpen = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFFFAF9F6),
                tonalElevation = 8.dp
            ) {
                Column(
                    Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Repeat reminder",
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose how often you want to be reminded.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (repeat == option.first)
                                        Color(0xFFE8EBDD)
                                    else Color.Transparent,
                                    RoundedCornerShape(15.dp)
                                )
                                .clickable {
                                    repeat = option.first
                                    repeatDialogOpen = false
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.second,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = Ink
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    option.third,
                                    fontSize = 12.sp,
                                    color = Muted
                                )
                            }
                            RadioButton(
                                selected = repeat == option.first,
                                onClick = {
                                    repeat = option.first
                                    repeatDialogOpen = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Accent
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { repeatDialogOpen = false }
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

}
