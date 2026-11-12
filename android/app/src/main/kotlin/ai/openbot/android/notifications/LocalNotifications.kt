package ai.openbot.android.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import ai.openbot.android.MainActivity
import ai.openbot.android.R
import ai.openbot.android.data.*
import ai.openbot.core.*

/** Android delivery for the phone's own local agent; no remote push service or device token. */
object LocalNotifications {
    const val CHANNEL="openbot"
    private const val SEEN="delivered"
    fun allowed(context:Context):Boolean =
        (Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()&&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    private fun manager(context:Context)=context.getSystemService(NotificationManager::class.java)
    fun createChannel(context:Context) {
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL,"OpenBot",NotificationManager.IMPORTANCE_HIGH).apply {
            description="Answers, questions and approvals from your Bots"
            lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
    }
    @Synchronized fun sync(context:Context,store:LocalStore) {
        createChannel(context)
        if(!store.preferences().notificationsEnabled||!allowed(context)){clear(context);return}
        val waiting=buildMap<String,Triple<String,String,String>> {
            for(action in store.pendingActions().filter{it.status==LocalActionStatus.PENDING}) {
                val bot=store.bots().find{it.id==action.botId}?.name?:"Your Bot"
                put("approval:${action.id}",Triple("$bot is waiting for your approval.",action.channelId,action.id))
            }
            for(question in QuestionStore(store).waiting()) {
                val bot=store.bots().find{it.id==question.botId}?.name?:"Your Bot"
                put("question:${question.id}",Triple("$bot has a question for you.",question.channelId,question.id))
            }
        }
        val preferences=context.getSharedPreferences("openbot-notifications",Context.MODE_PRIVATE)
        val seen=preferences.getStringSet(SEEN,emptySet()).orEmpty()
        for((tag,values) in waiting)if(tag !in seen)post(context,tag,values.first,"Open OpenBot to answer.","approvals",values.second,values.third)
        // Dismissing an Android notification does not make it reappear on every database change.
        preferences.edit().putStringSet(SEEN,waiting.keys).apply()
        manager(context).activeNotifications.filter{it.tag?.startsWith("approval:")==true||it.tag?.startsWith("question:")==true}
            .filter{it.tag !in waiting}.forEach{manager(context).cancel(it.tag,it.id)}
        val channels=store.conversations().map{it.id}.toSet()
        manager(context).activeNotifications.filter{it.tag?.startsWith("reply:")==true&&it.notification.extras.getString("openbot-channel") !in channels}
            .forEach{manager(context).cancel(it.tag,it.id)}
    }
    @Synchronized fun reply(context:Context,store:LocalStore,message:LocalMessage,botId:String) {
        if(message.state!=MessageState.COMPLETE||!store.preferences().notificationsEnabled||!allowed(context)||store.conversations().none{it.id==message.conversationId})return
        val name=store.bots().find{it.id==botId}?.name?:"Your Bot"
        post(context,"reply:${message.id}",name,"Your Bot has finished. Open the conversation to read its reply.","conversation",message.conversationId,null)
    }
    private fun post(context:Context,tag:String,title:String,body:String,path:String,channelId:String,requestId:String?) {
        createChannel(context)
        val uri=Uri.Builder().scheme("openbotmobile").authority(path).appendQueryParameter("channelId",channelId).apply{requestId?.let{appendQueryParameter("requestId",it)}}.build()
        val intent=Intent(context,MainActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_bolt).setContentTitle("OpenBot").setContentText("A Bot has an update for you.").build()
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_bolt).setContentTitle(title).setContentText(body)
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(Notification.CATEGORY_MESSAGE).addExtras(android.os.Bundle().apply{putString("openbot-channel",channelId)}).build()
        try{manager(context).notify(tag,0,notification)}catch(_:SecurityException){/* Permission can be revoked between checking and posting. */}
    }
    @Synchronized fun clear(context:Context) {
        manager(context).activeNotifications.filter{it.tag?.let{tag->tag.startsWith("approval:")||tag.startsWith("question:")||tag.startsWith("reply:")}==true}
            .forEach{manager(context).cancel(it.tag,it.id)}
        context.getSharedPreferences("openbot-notifications",Context.MODE_PRIVATE).edit().remove(SEEN).apply()
    }
}
