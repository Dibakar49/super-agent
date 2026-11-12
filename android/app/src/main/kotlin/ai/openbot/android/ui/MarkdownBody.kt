package ai.openbot.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import org.commonmark.node.*
import org.commonmark.node.Paragraph as MarkdownParagraph
import org.commonmark.node.Text as MarkdownText
import androidx.compose.material3.Text
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*
import java.net.URI

private val parser=Parser.builder().extensions(listOf(TablesExtension.create(),StrikethroughExtension.create())).build()
private fun Node.children():List<Node> = buildList {var child=firstChild;while(child!=null){add(child);child=child.next}}

/** Native Markdown blocks corresponding to the original transcript's Streamdown prose. */
@Composable internal fun MarkdownBody(text:String) {
    val document=remember(text){parser.parse(text)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){document.children().forEach{MarkdownBlock(it)}}
}

@Composable private fun MarkdownBlock(node:Node) {
    val p=LocalOpenBotPalette.current
    when(node) {
        is MarkdownParagraph->InlineText(node)
        is Heading->InlineText(node,MaterialTheme.typography.titleMedium.copy(fontSize=(when(node.level){1->24;2->20;else->18}).sp,fontWeight=FontWeight.SemiBold))
        is BulletList->Column(verticalArrangement=Arrangement.spacedBy(6.dp)){node.children().forEach {item->Row{Text("•",Modifier.width(18.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){item.children().forEach{MarkdownBlock(it)}}}}}
        is OrderedList->Column(verticalArrangement=Arrangement.spacedBy(6.dp)){node.children().forEachIndexed{index,item->Row{Text("${node.startNumber+index}.",Modifier.width(28.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){item.children().forEach{MarkdownBlock(it)}}}}}
        is BlockQuote->Column(Modifier.drawBehind{drawLine(p.border,Offset.Zero,Offset(0f,size.height),2.dp.toPx())}.padding(start=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){node.children().forEach{MarkdownBlock(it)}}
        is FencedCodeBlock->CodeBlock(node.literal,node.info)
        is IndentedCodeBlock->CodeBlock(node.literal,"")
        is ThematicBreak->HorizontalDivider(color=p.border)
        is TableBlock->MarkdownTable(node)
        is HtmlBlock->Text(node.literal,color=p.mutedForeground)
        else->node.children().forEach{MarkdownBlock(it)}
    }
}

@Suppress("DEPRECATION")
@Composable private fun InlineText(node:Node,style:TextStyle=MaterialTheme.typography.bodyMedium.copy(lineHeight=21.328125.sp)) {
    val p=LocalOpenBotPalette.current
    val uriHandler=LocalUriHandler.current
    val annotated=buildAnnotatedString {
        fun appendNode(child:Node) {
            when(child) {
                is MarkdownText->append(child.literal)
                is Code->withStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=p.muted)){append(child.literal)}
                is StrongEmphasis->withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)){child.children().forEach{appendNode(it)}}
                is Emphasis->withStyle(SpanStyle(fontStyle=FontStyle.Italic)){child.children().forEach{appendNode(it)}}
                is Strikethrough->withStyle(SpanStyle(textDecoration=TextDecoration.LineThrough)){child.children().forEach{appendNode(it)}}
                is Link->{pushStringAnnotation("URL",child.destination);withStyle(SpanStyle(color=p.foreground,textDecoration=TextDecoration.Underline)){child.children().forEach{appendNode(it)}};pop()}
                is Image->{append("[Image: ");child.children().forEach{appendNode(it)};append("]")}
                is SoftLineBreak->append(" ")
                is HardLineBreak->append("\n")
                is HtmlInline->append(child.literal)
                else->child.children().forEach{appendNode(it)}
            }
        }
        node.children().forEach{appendNode(it)}
    }
    ClickableText(annotated,modifier=Modifier.fillMaxWidth(),style=style.copy(color=p.foreground)) {offset->
        annotated.getStringAnnotations("URL",offset,offset).firstOrNull()?.let {annotation->
            val uri=runCatching{URI(annotation.item)}.getOrNull()
            if(uri?.scheme?.lowercase() in listOf("https","http","mailto"))runCatching{uriHandler.openUri(annotation.item)}
        }
    }
}

@Suppress("DEPRECATION")
@Composable private fun CodeBlock(code:String,language:String) {
    val p=LocalOpenBotPalette.current
    val clipboard=LocalClipboardManager.current
    Surface(color=p.muted,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(language.substringBefore(' '),style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)
                OpenBotTextButton(onClick={clipboard.setText(AnnotatedString(code))},contentPadding=PaddingValues(0.dp)){Text("Copy",style=MaterialTheme.typography.bodySmall)}
            }
            Text(code.trimEnd('\n'),Modifier.horizontalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun MarkdownTable(table:TableBlock) {
    val p=LocalOpenBotPalette.current
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp,p.border,RoundedCornerShape(6.dp))) {
        table.children().forEach {group->
            group.children().forEach {row->
                Row {
                    row.children().forEach {cell->
                        Box(Modifier.width(160.dp).background(if(group is TableHead)p.muted else p.card).padding(10.dp)) {
                            InlineText(cell,MaterialTheme.typography.bodyMedium.copy(fontWeight=if(group is TableHead)FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }
                HorizontalDivider(color=p.border)
            }
        }
    }
}
