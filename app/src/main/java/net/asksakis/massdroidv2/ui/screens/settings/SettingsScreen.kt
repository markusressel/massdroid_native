package net.asksakis.massdroidv2.ui.screens.settings

import android.app.Activity
import android.security.KeyChain
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val authToken by viewModel.authToken.collectAsStateWithLifecycle()
    val clientCertAlias by viewModel.clientCertAlias.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val sendspinState by viewModel.sendspinState.collectAsStateWithLifecycle()
    val sendspinEnabled by viewModel.sendspinEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val savedUsername by viewModel.savedUsername.collectAsStateWithLifecycle()
    val savedPassword by viewModel.savedPassword.collectAsStateWithLifecycle()

    var editUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    var showPassword by remember { mutableStateOf(false) }

    val isConnected = connectionState is ConnectionState.Connected
    val hasToken = authToken.isNotBlank()

    // Load saved certificate on screen open
    LaunchedEffect(Unit) {
        viewModel.loadSavedCertificate(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                        is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(
                        when (connectionState) {
                            is ConnectionState.Connected -> Icons.Default.Cloud
                            is ConnectionState.Connecting -> Icons.Default.CloudSync
                            else -> Icons.Default.CloudOff
                        },
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        when (connectionState) {
                            is ConnectionState.Connected -> {
                                val info = (connectionState as ConnectionState.Connected).serverInfo
                                "Connected (v${info.serverVersion})"
                            }
                            is ConnectionState.Connecting -> "Connecting..."
                            is ConnectionState.Error -> "Error: ${(connectionState as ConnectionState.Error).message}"
                            is ConnectionState.Disconnected -> "Disconnected"
                        }
                    )
                }
            }

            // Server URL
            OutlinedTextField(
                value = editUrl,
                onValueChange = { editUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://ma.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected
            )

            if (!isConnected) {
                HorizontalDivider()

                if (hasToken) {
                    // Quick reconnect with saved token
                    Button(
                        onClick = {
                            viewModel.connectWithToken(editUrl)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reconnect")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Or login with different credentials:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Username
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        viewModel.clearLoginError()
                    },
                    label = { Text("Username") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

                // Password
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.clearLoginError()
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                contentDescription = "Toggle password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Error
                loginError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Login button
                Button(
                    onClick = { viewModel.login(editUrl, username, password) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionState !is ConnectionState.Connecting
                ) {
                    if (connectionState is ConnectionState.Connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting...")
                    } else {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Login")
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            }

            // mTLS Certificate
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Client Certificate (mTLS)",
                        style = MaterialTheme.typography.titleSmall
                    )

                    if (clientCertAlias != null) {
                        Text(
                            "Certificate: $clientCertAlias",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val activity = context as? Activity ?: return@OutlinedButton
                                KeyChain.choosePrivateKeyAlias(
                                    activity, { alias ->
                                        viewModel.onCertificateSelected(alias, context)
                                    },
                                    null, null, null, -1, clientCertAlias
                                )
                            }) {
                                Text("Change")
                            }
                            OutlinedButton(onClick = { viewModel.clearCertificate() }) {
                                Text("Remove")
                            }
                        }
                    } else {
                        Text(
                            "No certificate selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = {
                            val activity = context as? Activity ?: return@OutlinedButton
                            KeyChain.choosePrivateKeyAlias(
                                activity, { alias ->
                                    viewModel.onCertificateSelected(alias, context)
                                },
                                null, null, null, -1, null
                            )
                        }) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Certificate")
                        }
                    }
                }
            }

            // Sendspin section (only when connected)
            if (isConnected) {
                HorizontalDivider()

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sendspin (Phone as Speaker)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    when (sendspinState) {
                                        SendspinState.STREAMING -> "Streaming"
                                        SendspinState.SYNCING -> "Ready"
                                        SendspinState.HANDSHAKING -> "Handshaking..."
                                        SendspinState.AUTHENTICATING -> "Authenticating..."
                                        SendspinState.CONNECTING -> "Connecting..."
                                        SendspinState.ERROR -> "Error"
                                        SendspinState.DISCONNECTED -> if (sendspinEnabled) "Stopped" else "Disabled"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (sendspinState) {
                                        SendspinState.STREAMING -> MaterialTheme.colorScheme.primary
                                        SendspinState.ERROR -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Switch(
                                checked = sendspinEnabled,
                                onCheckedChange = { viewModel.toggleSendspin(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}
