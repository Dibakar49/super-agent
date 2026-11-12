import {writeFileSync} from 'node:fs';
const root=process.env.OPENBOT_FIXTURE_ROOT!;
const shared=await import(root+'/shared/dictation.ts');
const {appendDictation}=await import(root+'/app/src/components/channels/composer/dictation-draft.ts');
const formats=['audio/webm','audio/webm; codecs=opus','VIDEO/MP4; codecs=aac',' audio/x-wav ','audio/mpeg','text/plain','audio/wav','constructor','__proto__','', 'video/webm'];
const drafts=['','Hello','Hello ','Hello\n','Hello\u00a0','Hello\ufeff','Hello\u001c'];
const rows=[];
for(const draft of drafts)for(const transcript of ['', ' words ', ' 😀 ', '\ufeffspoken\ufeff']) {
 const output=appendDictation(draft?[{type:'text',text:draft}]:[],transcript);
 rows.push({draft,transcript,expected:output.map((x:any)=>x.type==='text'?x.text:'').join('')});
}
writeFileSync(root+'/android/core/src/test/resources/dictation-typescript-parity.json',JSON.stringify({formats:formats.map(type=>({type,normalized:shared.dictationMediaType(type),extension:shared.dictationExtension(type)??null})),drafts:rows},null,2)+'\n');
