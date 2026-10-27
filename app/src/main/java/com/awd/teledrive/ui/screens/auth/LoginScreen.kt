package com.awd.teledrive.ui.screens.auth

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
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
            title = { Text("Select Country") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchCountry,
                        onValueChange = { searchCountry = it },
                        placeholder = { Text("Search country or code...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filteredCountries) { country ->
                            ListItem(
                                headlineContent = { Text("${country.flag}  ${country.name}") },
                                trailingContent = {
                                    Text(
                                        text = "+${country.dialCode}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.clickable {
                                    selectedCountry = country
                                    countryCode = country.dialCode
                                    showCountryDialog = false
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryDialog = false }) {
                    Text(stringResource(R.string.close))
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    if (canGoBackToPhone) {
                        IconButton(onClick = {
                            code = ""
                            password = ""
                            onBackToPhoneInput()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
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

                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (uiState) {
                                is LoginUiState.InitialSetup -> Icons.Default.CloudQueue
                                is LoginUiState.EnteringCode -> Icons.Default.Sms
                                is LoginUiState.EnteringPassword -> Icons.Default.LockPerson
                                else -> Icons.Default.CloudQueue
                            },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = when (uiState) {
                        is LoginUiState.InitialSetup -> "Your Phone Number"
                        is LoginUiState.EnteringCode -> "Enter Verification Code"
                        is LoginUiState.EnteringPassword -> stringResource(R.string.two_step_verification)
                        else -> stringResource(R.string.app_name)
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = when (uiState) {
                        is LoginUiState.InitialSetup -> "Please confirm your country code and enter your Telegram phone number."
                        is LoginUiState.EnteringCode -> "We've sent an activation code to your Telegram app or via SMS."
                        is LoginUiState.EnteringPassword -> stringResource(R.string.account_protected)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
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
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
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
                        // Country Selector Box (Telegram Style)
                        OutlinedCard(
                            onClick = { showCountryDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${selectedCountry.flag}   ${selectedCountry.name}",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select Country",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Split Country Code (+880) & Phone Number Row (Telegram Style)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = countryCode,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(4)
                                    countryCode = digits
                                    COUNTRY_LIST.find { it.dialCode == digits }?.let { matched ->
                                        selectedCountry = matched
                                    }
                                },
                                label = { Text("Code") },
                                prefix = { Text("+") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.width(105.dp)
                            )

                            OutlinedTextField(
                                value = localPhone,
                                onValueChange = { input ->
                                    localPhone = input.filter { it.isDigit() }
                                },
                                label = { Text(stringResource(R.string.phone_number)) },
                                placeholder = { Text("1712345678") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (countryCode.isNotBlank() && localPhone.length >= 6) {
                                        submitFormattedPhone()
                                    }
                                }),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        val canSubmit = countryCode.isNotBlank() && localPhone.length >= 6

                        Button(
                            onClick = submitFormattedPhone,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = !isProcessing && canSubmit
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.next), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
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
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.padding(bottom = 20.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = submittedPhone,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Phone",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = code,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }.take(6)
                                code = filtered
                                if (filtered.length == 5 && !isProcessing) {
                                    onCodeSubmit(filtered)
                                }
                            },
                            label = { Text("Code") },
                            placeholder = { Text("12345") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onCodeSubmit(code) }),
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { onCodeSubmit(code) },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = code.length >= 5 && !isProcessing
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.verify), style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Resend Code & Change Number Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    code = ""
                                    onBackToPhoneInput()
                                }
                            ) {
                                Text("Wrong number?")
                            }

                            TextButton(
                                onClick = {
                                    onResendCode()
                                    resendTimer = 60
                                },
                                enabled = resendTimer == 0 && !isProcessing
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (resendTimer > 0) "Resend Code (${resendTimer}s)" else "Resend Code"
                                )
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
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { onPasswordSubmit(password) },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = password.isNotBlank() && !isProcessing
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.unlock), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }

                    else -> {
                        Spacer(modifier = Modifier.height(32.dp))
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    }
}
