package com.awd.teledrive.ui.screens.auth

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import kotlinx.coroutines.delay

data class CountryOption(
    val name: String,
    val dialCode: String,
    val flag: String
)

private val COUNTRY_LIST = listOf(
    CountryOption("Bangladesh", "880", "🇧🇩"),
    CountryOption("India", "91", "🇮🇳"),
    CountryOption("Pakistan", "92", "🇵🇰"),
    CountryOption("Saudi Arabia", "966", "🇸🇦"),
    CountryOption("United Arab Emirates", "971", "🇦🇪"),
    CountryOption("Qatar", "974", "🇶🇦"),
    CountryOption("Kuwait", "965", "🇰🇼"),
    CountryOption("Oman", "968", "🇴🇲"),
    CountryOption("Bahrain", "973", "🇧🇭"),
    CountryOption("Malaysia", "60", "🇲🇾"),
    CountryOption("Singapore", "65", "🇸🇬"),
    CountryOption("United States", "1", "🇺🇸"),
    CountryOption("United Kingdom", "44", "🇬🇧"),
    CountryOption("Canada", "1", "🇨🇦"),
    CountryOption("Australia", "61", "🇦🇺"),
    CountryOption("Italy", "39", "🇮🇹"),
    CountryOption("France", "33", "🇫🇷"),
    CountryOption("Germany", "49", "🇩🇪"),
    CountryOption("Turkey", "90", "🇹🇷"),
    CountryOption("Indonesia", "62", "🇮🇩")
)

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val submittedPhone by viewModel.submittedPhone.collectAsState()
    val codeResentMessage by viewModel.codeResentMessage.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(codeResentMessage) {
        codeResentMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearResentMessage()
        }
    }

    LoginContent(
        uiState = uiState,
        authError = authError,
        isProcessing = isProcessing,
        submittedPhone = submittedPhone,
        onLoginSubmit = viewModel::onLoginSubmit,
        onCodeSubmit = viewModel::onCodeSubmit,
        onPasswordSubmit = viewModel::onPasswordSubmit,
        onResendCode = viewModel::onResendCode,
        onBackToPhoneInput = viewModel::onBackToPhoneInput
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginContent(
    uiState: LoginUiState,
    authError: String?,
    isProcessing: Boolean,
    submittedPhone: String = "",
    onLoginSubmit: (String, String, String) -> Unit,
    onCodeSubmit: (String) -> Unit,
    onPasswordSubmit: (String) -> Unit,
    onResendCode: () -> Unit = {},
    onBackToPhoneInput: () -> Unit = {}
) {
    val defaultApiId = "94575"
    val defaultApiHash = "a3406de8d171bb422bb6ddf3bbd800e2"

    var selectedCountry by remember { mutableStateOf(COUNTRY_LIST.first()) }
    var countryCode by remember { mutableStateOf("880") }
    var localPhone by remember { mutableStateOf("") }
    var showCountryDialog by remember { mutableStateOf(false) }

    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var resendTimer by remember { mutableIntStateOf(60) }

    val scrollState = rememberScrollState()
    val canGoBackToPhone = uiState is LoginUiState.EnteringCode || uiState is LoginUiState.EnteringPassword

    BackHandler(enabled = canGoBackToPhone) {
        code = ""
        password = ""
        onBackToPhoneInput()
    }

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.EnteringCode) {
            resendTimer = 60
            while (resendTimer > 0) {
                delay(1000)
                resendTimer--
            }
        }
    }

    if (showCountryDialog) {
        var searchCountry by remember { mutableStateOf("") }
        val filteredCountries = remember(searchCountry) {
            if (searchCountry.isBlank()) COUNTRY_LIST
            else COUNTRY_LIST.filter {
                it.name.contains(searchCountry, ignoreCase = true) || it.dialCode.contains(searchCountry)
            }
        }

        AlertDialog(
            onDismissRequest = { showCountryDialog = false },
            title = { Text("Select Country", color = Color.White) },
            containerColor = Color(0xFF0F172A),
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchCountry,
                        onValueChange = { searchCountry = it },
                        placeholder = { Text("Search...", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filteredCountries) { country ->
                            ListItem(
                                headlineContent = { Text("${country.flag}  ${country.name}", color = Color.White) },
                                trailingContent = {
                                    Text(
                                        text = "+${country.dialCode}",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00E5FF)
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    selectedCountry = country
                                    countryCode = country.dialCode
                                    showCountryDialog = false
                                }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryDialog = false }) {
                    Text(stringResource(R.string.close), color = Color(0xFF00E5FF))
                }
            }
        )
    }

    val submitFormattedPhone = {
        val cleanCode = countryCode.filter { it.isDigit() }
        val cleanLocal = localPhone.filter { it.isDigit() }.let {
            if (cleanCode == "880" && it.startsWith("0")) it.removePrefix("0") else it
        }
        val fullPhone = "+$cleanCode$cleanLocal"
        onLoginSubmit(defaultApiId, defaultApiHash, fullPhone)
    }

    // Glassmorphic Colors
    val glassBg = Color.White.copy(alpha = 0.05f)
    val glassBorder = Color.White.copy(alpha = 0.15f)

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(
            Brush.verticalGradient(
                colors = listOf(Color(0xFF070B14), Color(0xFF0F2942))
            )
        )
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Top Back Button
            if (canGoBackToPhone) {
                IconButton(
                    onClick = {
                        code = ""
                        password = ""
                        onBackToPhoneInput()
                    },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp)
                        .align(Alignment.TopStart)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .imePadding()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // App Logo
                Image(
                    painter = painterResource(id = R.drawable.ic_mycloud_logo),
                    contentDescription = "MyCloud Logo",
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(26.dp))
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Title
                Text(
                    text = when (uiState) {
                        is LoginUiState.InitialSetup -> "Sign in to MyCloud"
                        is LoginUiState.EnteringCode -> "Enter Code"
                        is LoginUiState.EnteringPassword -> "Two-Step Verification"
                        else -> "Welcome Back"
                    },
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    text = when (uiState) {
                        is LoginUiState.InitialSetup -> "Please enter your Telegram phone number."
                        is LoginUiState.EnteringCode -> "We've sent a code to your Telegram app."
                        is LoginUiState.EnteringPassword -> "Enter your Telegram 2FA password."
                        else -> ""
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                // Error Message
                AnimatedVisibility(
                    visible = authError != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        color = Color(0xFFEF5350).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(text = authError ?: "", color = Color(0xFFFFEBEE), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                when (uiState) {
                    is LoginUiState.InitialSetup -> {
                        // Glass Country Selector
                        Surface(
                            color = glassBg,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, glassBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCountryDialog = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${selectedCountry.flag}   ${selectedCountry.name}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select",
                                    tint = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Glass Phone Input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Country Code
                            OutlinedTextField(
                                value = countryCode,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(4)
                                    countryCode = digits
                                    COUNTRY_LIST.find { it.dialCode == digits }?.let { matched ->
                                        selectedCountry = matched
                                    }
                                },
                                prefix = { Text("+", color = Color.White) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = glassBorder,
                                    focusedContainerColor = glassBg,
                                    unfocusedContainerColor = glassBg,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.width(100.dp)
                            )

                            // Phone Number
                            OutlinedTextField(
                                value = localPhone,
                                onValueChange = { input ->
                                    localPhone = input.filter { it.isDigit() }
                                },
                                placeholder = { Text("Phone Number", color = Color.White.copy(alpha = 0.3f)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (countryCode.isNotBlank() && localPhone.length >= 6) {
                                        submitFormattedPhone()
                                    }
                                }),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = glassBorder,
                                    focusedContainerColor = glassBg,
                                    unfocusedContainerColor = glassBg,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(36.dp))

                        val canSubmit = countryCode.isNotBlank() && localPhone.length >= 6

                        // Primary Action Button
                        Button(
                            onClick = submitFormattedPhone,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = !isProcessing && canSubmit,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0078D7),
                                disabledContainerColor = Color(0xFF0078D7).copy(alpha = 0.5f)
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
                            } else {
                                Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White)
                            }
                        }
                    }

                    is LoginUiState.EnteringCode -> {
                        if (submittedPhone.isNotEmpty()) {
                            Surface(
                                onClick = {
                                    code = ""
                                    onBackToPhoneInput()
                                },
                                shape = CircleShape,
                                color = glassBg,
                                border = BorderStroke(1.dp, glassBorder),
                                modifier = Modifier.padding(bottom = 24.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = submittedPhone,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = code,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }.take(5)
                                code = filtered
                                if (filtered.length == 5 && !isProcessing) {
                                    onCodeSubmit(filtered)
                                }
                            },
                            placeholder = { Text("Code", color = Color.White.copy(alpha = 0.3f)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onCodeSubmit(code) }),
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Color.White.copy(alpha = 0.5f)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = glassBorder,
                                focusedContainerColor = glassBg,
                                unfocusedContainerColor = glassBg,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { onCodeSubmit(code) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = code.length >= 5 && !isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0078D7),
                                disabledContainerColor = Color(0xFF0078D7).copy(alpha = 0.5f)
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
                            } else {
                                Text("Verify", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        TextButton(
                            onClick = {
                                onResendCode()
                                resendTimer = 60
                            },
                            enabled = resendTimer == 0 && !isProcessing
                        ) {
                            Icon(Icons.Default.Refresh, null, tint = if (resendTimer == 0) Color(0xFF00E5FF) else Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (resendTimer > 0) "Resend Code (${resendTimer}s)" else "Resend Code",
                                color = if (resendTimer == 0) Color(0xFF00E5FF) else Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }

                    is LoginUiState.EnteringPassword -> {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("Password", color = Color.White.copy(alpha = 0.3f)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onPasswordSubmit(password) }),
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Color.White.copy(alpha = 0.5f)) },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = glassBorder,
                                focusedContainerColor = glassBg,
                                unfocusedContainerColor = glassBg,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { onPasswordSubmit(password) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = password.isNotBlank() && !isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0078D7),
                                disabledContainerColor = Color(0xFF0078D7).copy(alpha = 0.5f)
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
                            } else {
                                Text("Unlock", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    else -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        CircularProgressIndicator(color = Color(0xFF00E5FF))
                    }
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}
