package ai.openbot.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.openbot.android.*
import ai.openbot.android.R
import ai.openbot.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable internal fun rememberAttachmentPicker(vm:OpenBotViewModel):()->Unit {
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())vm.addAttachments(uris)}
    return {launcher.launch(arrayOf("*/*"))}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AttachmentStrip(files:List<LocalAttachment>,vm:OpenBotViewModel,removable:Boolean=false,enabled:Boolean=true) {
    if(files.isEmpty())return
    val p=LocalOpenBotPalette.current
    FlowRow(Modifier.fillMaxWidth().padding(bottom=12.dp).testTag("attachment-strip"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        files.forEach{file->key(file.id) {
            val image=classifyAttachment(file.mimeType)==AttachmentKind.IMAGE
            Box(Modifier.width(if(image)80.dp else 160.dp).height(80.dp).testTag("attachment-${file.id}")) {
                val shape=RoundedCornerShape(12.dp)
                if(image) {
                    val bitmap by produceState<android.graphics.Bitmap?>(null,file.id) {
                        value=withContext(Dispatchers.IO) {
                            val local=vm.attachmentFile(file)?:return@withContext null
                            runCatching {
                                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                                local.inputStream().use{BitmapFactory.decodeStream(it,null,bounds)}
                                var sample=1
                                while(bounds.outWidth/sample>160||bounds.outHeight/sample>160)sample*=2
                                local.inputStream().use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
                            }.getOrNull()
                        }
                    }
                    Box(Modifier.fillMaxSize().clip(shape).background(p.muted).border(1.dp,p.border,shape).clickable(enabled=enabled){vm.openAttachment(file)},contentAlignment=Alignment.Center) {
                        bitmap?.let{Image(it.asImageBitmap(),file.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}?:Text(file.name,Modifier.padding(8.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                    }
                } else Surface(Modifier.fillMaxSize().clickable(enabled=enabled){vm.openAttachment(file)},shape=shape,color=p.muted.copy(alpha=.4f),border=BorderStroke(1.dp,p.border)) {
                    Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.SpaceBetween) {
                        TablerIcon(R.drawable.ic_file,modifier=Modifier.size(20.dp))
                        Column {
                            Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
                            val size=when{file.size<1024->"${file.size}B";file.size<1024*1024->"${kotlin.math.floor(file.size/1024.0+.5).toInt()}KB";else->String.format(Locale.ROOT,"%.1fMB",file.size/(1024.0*1024))}
                            Text(size+if(mayBeTruncatedForModel(file.size))" · may be cut" else "",color=p.mutedForeground,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if(removable)IconButton(onClick={vm.removeAttachment(file.id)},enabled=enabled,modifier=Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)) {
                    Box(Modifier.size(20.dp).background(p.background.copy(alpha=.9f),RoundedCornerShape(100)).border(1.dp,p.border,RoundedCornerShape(100)),contentAlignment=Alignment.Center) {
                        TablerIcon(R.drawable.ic_x,"Remove ${file.name}",Modifier.size(12.dp))
                    }
                }
            }
        }}
    }
}
