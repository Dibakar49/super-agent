import {writeFileSync} from 'node:fs';
const {safetyRequirement,decideAction,globMatches}=await import(process.env.OPENBOT_FIXTURE_TMP!+'/native-policy-reference.ts');
const root=process.env.OPENBOT_FIXTURE_ROOT!;
const scratch=process.env.OPENBOT_FIXTURE_TMP!;
const base={actorId:'person',botId:'bot',toolRef:'notes/update_note',effect:'write',scope:'notes',args:{text:'A useful note'},target:{serverId:'notes',toolName:'update_note',url:'https://example.com/mcp',effect:'write'}};
const names=['write_note','create_file','get_file','delete_file','remove_file','archive_contact','list_archived_emails','unarchive_contact','get_archive_settings','remove_collaborator','send_email','send_message_to_self','reply_to_comment','create_draft','create_message_draft','send_draft','update_comment','mark_comment_as_spam','like_tweet','add_post_to_bookmarks','create_post','create_post_draft','post_comment','POST_V1_PLANS_OVERVIEW','GET_MESSAGE','SHARE_FILE','create_scheduled_message','cancel_scheduled_send','add_message_attachment','publish_release','create_release','create_release_template','transfer_money','transfer_repository','transfer_ownership','create_order','purchase_item','update_pay_schedule','run_payroll','submit_on_demand_payroll','update_payroll_settings','create_app_subscription','create_subscription','delete_account','update_password','update_api_key','grant_admin_role','share_with_anyone','close_account'];
const safety=names.map(tool=>{const candidate={...base,toolRef:'service/'+tool,target:{...base.target,toolName:tool}};return {candidate,expected:safetyRequirement(candidate)??null}});
for(const args of [{attendees:['a@example.com']},{attendeeEmails:['a@example.com']},{settings__meeting__invitees:[{email:'a@example.com'}]},{guestsCanModify:true},{send_notifications_to_attendees:false},{to:'folder'},{to:'self@example.com'},{billing_address:null},{billing_address:''},{billing_address:{}},{billing_address:[]},{billing_address:0},{text:'Business Wire and news-wire sources'},{text:'wire money'},{text:'password change'},{a:{b:{c:{d:{e:{f:{billing_address:'filled'}}}}}}}]) {
 const candidate={...base,args};safety.push({candidate,expected:safetyRequirement(candidate)??null});
}
for(const extra of [{effect:'read',args:{password:'secret'}},{destructive:true},{toolRef:'memory/remember_personal_fact',args:{text:'A fact'}},{toolRef:'vendor/update_repository_settings',args:{text:'A fact'}}]){
 const candidate={...base,...extra};safety.push({candidate,expected:safetyRequirement(candidate)??null});
}
writeFileSync(root+'/android/core/src/test/resources/approval-safety-typescript-parity.json',JSON.stringify(safety,null,2)+'\n');
const cases=[];
for(const effect of ['write','read','delete','payment'])for(const personal of [null,'allow','pre_approved','ask','hand_off'])for(const team of [null,'allow','ask'])for(const mode of ['none','proceed','ask','handoff'])for(const autoReview of [false,true])for(const enabled of [false,true]) {
 const candidate={...base,effect:effect==='read'?'read':'write',toolRef:effect==='delete'?'notes/delete_note':effect==='payment'?'bank/transfer_money':base.toolRef};
 const rule=(behaviour,layer)=>({id:layer+'_'+behaviour,botId:'*',toolRef:'*',effect:'*',scope:'*',behaviour});
 const personalRules=personal?[rule(personal,'personal')]:[];const teamRules=team?[rule(team,'team')]:[];
 const verdict=mode==='proceed'?{verdict:'proceed',preApproved:true,safety:'none',reason:'Fixture reviewer'}:mode==='ask'?{verdict:'needs_approval',preApproved:false,safety:'none',reason:'Fixture reviewer'}:{verdict:'hand_off',preApproved:false,safety:'security_settings',reason:'Fixture reviewer'};
 const expected=await decideAction({candidate,personal:personalRules,team:teamRules,customRulesEnabled:true,autoReview,askBeforeChanges:enabled,...(mode==='none'?{}:{model:async()=>JSON.stringify(verdict)})});
 cases.push({candidate,personal:personalRules,team:teamRules,autoReview,enabled,review:mode==='none'?null:verdict,expected});
}
writeFileSync(root+'/android/core/src/test/resources/approval-decision-typescript-parity.json',JSON.stringify(cases)+'\n');
const globCases=[['*','a\nb'],['foo','foo\n'],['a?','a?'],['a?','ab'],['a[b]','a[b]'],['a*b','a\nb'],['*',''],['a\\b','a\\b'],['a**b','ab']].map(([pattern,value])=>({pattern,value,matches:globMatches(pattern,value)}));
writeFileSync(root+'/android/core/src/test/resources/approval-glob-typescript-parity.json',JSON.stringify(globCases,null,2)+'\n');
console.log(JSON.stringify({safety:safety.length,decisions:cases.length,globs:globCases.length}));
