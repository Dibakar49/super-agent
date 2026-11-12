package ai.openbot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.openbot.android.*

@Composable internal fun DictationSettingsScreen(state:AppState,vm:OpenBotViewModel) {
    var baseUrl by rememberSaveable(state.transcriptionSettings){mutableStateOf(state.transcriptionSettings.baseUrl)}
    var model by rememberSaveable(state.transcriptionSettings){mutableStateOf(state.transcriptionSettings.model)}
    var key by remember{mutableStateOf("")}
    var clearKey by remember{mutableStateOf(false)}
    var saved by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("dictation-settings-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item{Title("Dictation settings")}
        item{Text("Record a message, add it to your draft, or transcribe and send. Recordings go directly to the transcription provider you choose here.")}
        item{Text(if(state.dictationConfigured)"Dictation is on." else "Dictation is off.")}
        item{SourceTextField(baseUrl,{baseUrl=it;saved=false},label={Text("HTTPS transcription API URL")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy)}
        item{SourceTextField(model,{model=it;saved=false},label={Text("Transcription model")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy)}
        item{SourceTextField(key,{key=it;saved=false},label={Text("Transcription API key (optional)")},placeholder={Text(if(state.dictationKeyConfigured)"Leave empty to retain the saved key" else "Enter a key if your provider requires one")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy)}
        item{Text("This key is stored separately from the chat model key. Changing the URL clears it unless you supply a replacement.")}
        if(state.dictationKeyConfigured)item{Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Checkbox(clearKey,{clearKey=it},enabled=!state.busy);Text("Remove saved transcription key")}}
        item{OpenBotButton(onClick={vm.saveTranscriptionSettings(baseUrl.trim(),model.trim(),key.takeIf{it.isNotBlank()},clearKey){key="";clearKey=false;saved=true}},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(if(saved)"Dictation settings saved." else "Save dictation settings")}}
        if(state.dictationConfigured)item{OpenBotTextButton(onClick={vm.disableDictation{saved=false}},enabled=!state.busy){Text("Turn off dictation",color=LocalOpenBotPalette.current.destructive)}}
        item{Text("Microphone permission is requested when you start recording. Recordings stop after two minutes and are removed after transcription or cancellation.")}
    }
}
