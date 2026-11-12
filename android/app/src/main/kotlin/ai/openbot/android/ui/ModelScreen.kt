package ai.openbot.android.ui

import ai.openbot.android.AppState
import ai.openbot.android.OpenBotViewModel
import ai.openbot.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun ModelScreen(
    state: AppState,
    vm: OpenBotViewModel,
    onDictation: () -> Unit = {},
    onImport: () -> Unit,
) {
    var mode by rememberSaveable(state.providerSettings) { mutableStateOf(state.providerSettings.mode) }
    var baseUrl by rememberSaveable(state.providerSettings) { mutableStateOf(state.providerSettings.baseUrl) }
    var model by rememberSaveable(state.providerSettings) { mutableStateOf(state.providerSettings.model) }
    var style by rememberSaveable(state.providerSettings) { mutableStateOf(state.providerSettings.apiStyle) }
    // Secrets are deliberately not saved with rememberSaveable or included in AppState.
    var key by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("model-settings-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("Model settings") }
        item { Text("Bots and conversations run in the local Android backend. Choose where the language model runs.") }
        item { Text("Active: ${if (state.providerSettings.mode == ModelMode.PROVIDER_API) "Model provider API" else "On-device model"}") }
        item {
            Column {
                OpenBotTextButton(onClick = {
                    mode = ModelMode.PROVIDER_API
                    saved = false
                }, enabled = !state.busy) { Text("${if (mode == ModelMode.PROVIDER_API) "✓ " else ""}Model provider API") }
                OpenBotTextButton(onClick = {
                    mode = ModelMode.ON_DEVICE
                    saved = false
                }, enabled = !state.busy) { Text("${if (mode == ModelMode.ON_DEVICE) "✓ " else ""}On-device model") }
            }
        }
        if (mode == ModelMode.PROVIDER_API) {
            item { Text("Your messages and enabled memory are sent directly to the model provider you configure. Your OpenBot data stays on this phone.") }
            item {
                SourceTextField(baseUrl, {
                    baseUrl = it
                    saved = false
                }, label = { Text("HTTPS model API URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            }
            item {
                SourceTextField(model, {
                    model = it
                    saved = false
                }, label = { Text("Model name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            }
            item {
                Column {
                    Text("API style")
                    ApiStyle.entries.forEach { candidate ->
                        OpenBotTextButton(onClick = {
                            style = candidate
                            saved = false
                        }, enabled = !state.busy) {
                            Text("${if (style == candidate) "✓ " else ""}${if (candidate == ApiStyle.RESPONSES) "Responses" else "Chat Completions"}")
                        }
                    }
                }
            }
            item {
                SourceTextField(
                    key,
                    {
                        key = it
                        saved = false
                    },
                    label = { Text("Provider API key") },
                    placeholder = { Text(if (state.providerKeyConfigured) "Leave empty to retain the saved key" else "Enter your key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.busy,
                )
            }
            item { Text(if (state.providerKeyConfigured) "A key is stored using Android Keystore encryption. Changing the API URL clears it unless you enter a new key." else "No provider key is saved.") }
            if (state.providerKeyConfigured) {
                item {
                    Row {
                        Checkbox(clearKey, { clearKey = it }, enabled = !state.busy)
                        Text("Remove saved key", Modifier.padding(top = 12.dp))
                    }
                }
            }
        } else {
            item { Text(if (state.modelInstalled) "A local model is installed." else "Import a MediaPipe-compatible LLM .task model to chat offline.") }
            item { Text("On-device inference needs a compatible ARM64 phone and enough RAM for the selected model. No API key is used in this mode.") }
            item { OpenBotButton(onClick = onImport, enabled = !state.busy) { Text(if (state.modelInstalled) "Replace model" else "Import model") } }
            state.modelHash?.let { hash -> item { Text("Imported file SHA-256: $hash") } }
        }
        item {
            OpenBotButton(onClick = {
                vm.saveProviderSettings(ProviderSettings(mode, baseUrl.trim(), model.trim(), style), key.takeIf { it.isNotBlank() }, clearKey) {
                    key = ""
                    clearKey = false
                    saved = true
                }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Model settings saved." else "Save model settings") }
        }
        item { OpenBotTextButton(onClick = onDictation) { Text("Dictation settings") } }
        item { Text("Provider mode can use your granted tools and apps. On-device mode currently supports text replies. Computer use is excluded.") }
    }
}
