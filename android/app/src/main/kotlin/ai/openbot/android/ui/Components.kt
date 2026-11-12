package ai.openbot.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.BorderStroke
import ai.openbot.android.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler

@Composable internal fun Title(text: String) { Text(text, style = MaterialTheme.typography.titleLarge) }
@Composable internal fun TablerIcon(@DrawableRes id: Int, label: String? = null, modifier: Modifier = Modifier.size(18.dp), tint: Color = LocalOpenBotPalette.current.mutedForeground) {
    Icon(painterResource(id), label, modifier, tint)
}

/** Render at the app root, so the original modal scrim also blocks the toolbar and sidebar. */
@Composable internal fun SourceDialog(title:String,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val host=LocalSourceDialogs.current
    val currentTitle by rememberUpdatedState(title)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentContent by rememberUpdatedState(content)
    val id=remember{Any()}
    if(host==null)SourceDialogContent(title,onDismiss,content)
    else DisposableEffect(host,id) {
        val entry=SourceDialogs.Entry(id){SourceDialogContent(currentTitle,currentDismiss,currentContent)}
        host.entries.add(entry)
        onDispose{host.entries.remove(entry)}
    }
}

@Composable private fun SourceDialogContent(title:String,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    BackHandler(onBack=onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.10f)).clickable(onClick=onDismiss))
        Surface(Modifier.align(Alignment.Center).padding(16.dp).widthIn(max=512.dp).fillMaxWidth().heightIn(max=maxHeight*.85f).semantics{paneTitle=title},
            shape=RoundedCornerShape(14.dp),color=LocalOpenBotPalette.current.card,border=BorderStroke(1.dp,LocalOpenBotPalette.current.border),shadowElevation=8.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(21.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Text(title,Modifier.fillMaxWidth().padding(end=28.dp),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium))
                    IconButton(onClick=onDismiss,modifier=Modifier.align(Alignment.TopEnd).size(24.dp)){TablerIcon(R.drawable.ic_x,"Close dialog",Modifier.size(16.dp))}
                }
                content()
            }
        }
    }
}
@Composable internal fun OpenBotButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.defaultMinSize(minWidth=1.dp,minHeight=36.dp), enabled = enabled, shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), content = content)
}
@Composable internal fun OpenBotTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=10.dp,vertical=8.dp),content:@Composable RowScope.()->Unit) {
    TextButton(onClick,modifier.defaultMinSize(minWidth=1.dp,minHeight=36.dp),enabled=enabled,shape=RoundedCornerShape(10.dp),contentPadding=contentPadding,content=content)
}
@Composable internal fun OpenBotOutlineButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:androidx.compose.ui.graphics.Shape=RoundedCornerShape(10.dp),content:@Composable RowScope.()->Unit) {
    OutlinedButton(onClick,modifier.defaultMinSize(minWidth=1.dp,minHeight=36.dp),enabled=enabled,shape=shape,
        border=BorderStroke(1.dp,LocalOpenBotPalette.current.border),contentPadding=PaddingValues(horizontal=10.dp,vertical=8.dp),content=content)
}

/** Source input/textarea geometry with a label above the field, rather than a Material floating label. */
@Composable internal fun SourceTextField(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
    label:(@Composable () -> Unit)?=null,placeholder:(@Composable () -> Unit)?=null,enabled:Boolean=true,
    singleLine:Boolean=false,minLines:Int=1,maxLines:Int=Int.MAX_VALUE,keyboardOptions:KeyboardOptions=KeyboardOptions.Default,
    visualTransformation:VisualTransformation=VisualTransformation.None) {
    val p=LocalOpenBotPalette.current
    var focused by remember{mutableStateOf(false)}
    val shape=RoundedCornerShape(10.dp)
    BasicTextField(value,onValueChange,modifier.onFocusChanged{focused=it.isFocused},enabled=enabled,singleLine=singleLine,
        minLines=if(singleLine)1 else minLines,maxLines=if(singleLine)1 else maxLines,keyboardOptions=keyboardOptions,visualTransformation=visualTransformation,
        textStyle=MaterialTheme.typography.bodyLarge.copy(color=if(enabled)p.foreground else p.mutedForeground),cursorBrush=SolidColor(p.foreground),decorationBox={inner->
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                label?.let{ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium)){it()}}
                Box(Modifier.fillMaxWidth().heightIn(min=if(singleLine||minLines==1)36.dp else 64.dp)
                    .drawBehind{if(focused)drawRoundRect(p.ring.copy(alpha=.5f),topLeft=androidx.compose.ui.geometry.Offset(-1.5.dp.toPx(),-1.5.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width+3.dp.toPx(),size.height+3.dp.toPx()),cornerRadius=CornerRadius(11.5.dp.toPx()),style=Stroke(3.dp.toPx()))}
                    .background(if(enabled)p.card else p.muted,shape).border(1.dp,if(focused)p.ring else p.border,shape)
                    .padding(horizontal=11.dp,vertical=if(singleLine||minLines==1)5.dp else 9.dp),contentAlignment=if(singleLine||minLines==1)Alignment.CenterStart else Alignment.TopStart) {
                    if(value.isEmpty())placeholder?.let{CompositionLocalProvider(LocalContentColor provides p.mutedForeground){ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color=p.mutedForeground)){it()}}}
                    inner()
                }
            }
        })
}
@Composable internal fun SourceEmpty(title: String, description: String? = null, modifier: Modifier = Modifier.height(140.dp)) {
    val p = LocalOpenBotPalette.current
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(p.border, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
        }
        Column(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.Center).padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = p.mutedForeground, style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium,letterSpacing=(-.328125).sp), textAlign = TextAlign.Center)
            description?.let { Text(it, color = p.mutedForeground, style=MaterialTheme.typography.bodyMedium,textAlign = TextAlign.Center) }
        }
    }
}
