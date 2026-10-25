package com.awd.teledrive.ui.screens.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    
    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            onLoginSuccess()
        }
    }

    LoginContent(
        uiState = uiState,
        authError = authError,
        isProcessing = isProcessing,
        onLoginSubmit = viewModel::onLoginSubmit,
        onCodeSubmit = viewModel::onCodeSubmit,
        onPasswordSubmit = viewModel::onPasswordSubmit,
        onResetApi = viewModel::onResetApi
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginContent(
    uiState: LoginUiState,
    authError: String?,
    isProcessing: Boolean,
    onLoginSubmit: (String, String, String) -> Unit,
    onCodeSubmit: (String) -> Unit,
    onPasswordSubmit: (String) -> Unit,
    onResetApi: () -> Unit
) {
    // এখানে টেলিগ্রামের অফিসিয়াল API ID এবং Hash লুকিয়ে বসানো হলো
    val defaultApiId = "94575" 
    val defaultApiHash = "a3406de8d171bb422bb6ddf3bbd800e2" 
    
    var phoneNumber by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.app_name), 
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .imePadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Icon Section
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when(uiState) {
                                is LoginUiState.InitialSetup -> Icons.Default.CloudQueue
                                is LoginUiState.EnteringCode -> Icons.Default.Sms
                                is LoginUiState.EnteringPassword -> Icons.Default.LockPerson
                                else -> Icons.AutoMirrored.Filled.Login
                            },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = when (uiState) {
                        is LoginUiState.InitialSetup -> stringResource(R.string.welcome_title)
                        is LoginUiState.EnteringCode -> stringResource(R.string.enter_code)
                        is LoginUiState.EnteringPassword -> stringResource(R.string.two_step_verification)
                        else -> stringResource(R.string.app_name)
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = when (uiState) {
                        is LoginUiState.InitialSetup -> "আপনার ফোন নম্বর দিয়ে লগইন করুন"
                        is LoginUiState.EnteringCode -> stringResource(R.string.code_sent)
                        is LoginUiState.EnteringPassword -> stringResource(R.string.account_protected)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
                )

                AnimatedVisibility(
                    visible = authError != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(text = authError ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                when (uiState) {
                    is LoginUiState.InitialSetup -> {
                        
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            label = { Text(stringResource(R.string.phone_number)) },
                            placeholder = { Text(stringResource(R.string.phone_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onLoginSubmit(defaultApiId, defaultApiHash, phoneNumber) }),
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        val canSubmit = phoneNumber.isNotBlank()

                        Button(
                            onClick = { onLoginSubmit(defaultApiId, defaultApiHash, phoneNumber) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.large,
                            enabled = !isProcessing && canSubmit
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.next), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                            }
                        }
                    }
                    
                    is LoginUiState.EnteringCode -> {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text(stringResource(R.string.enter_code)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onCodeSubmit(code) }),
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { onCodeSubmit(code) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.large,
                            enabled = code.isNotBlank() && !isProcessing
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.verify), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    
                    is LoginUiState.EnteringPassword -> {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.password)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onPasswordSubmit(password) }),
                            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { onPasswordSubmit(password) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = MaterialTheme.shapes.large,
                            enabled = password.isNotBlank() && !isProcessing
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.unlock), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    else -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    }
}
