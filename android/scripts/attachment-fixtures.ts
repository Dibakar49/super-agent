import {readFileSync,writeFileSync} from 'node:fs';
const root=process.env.OPENBOT_FIXTURE_ROOT!;
const scratch=process.env.OPENBOT_FIXTURE_TMP!;
const shared=await import(root+'/shared/attachments.ts');
let mimeSource=readFileSync(root+'/server/src/channels/attachment-mime.ts','utf8');
mimeSource=mimeSource.replace('"@openbot/shared/attachments"',JSON.stringify(root+'/shared/attachments.ts'));
const modulePath=scratch+'/original-attachment-mime.ts';writeFileSync(modulePath,mimeSource);
const {sniffMimeType}=await import(modulePath);
const samples=[[],[0x89,0x50,0x4e,0x47],[0xff,0xd8,0xff],[0x47,0x49,0x46,0x38],[0x52,0x49,0x46,0x46,0,0,0,0,0x57,0x45,0x42,0x50],[0xff,0,0x80],[0xc3,0x28],...['hello','<svg>test</svg>','{"a":1}','नमस्ते 😀','\0ABC','\ufefftext'].map(text=>Array.from(new TextEncoder().encode(text)))];
const claims=['','TEXT/PLAIN; charset=UTF-8','text/csv','application/json','text/markdown','image/png','image/jpeg','image/svg+xml','image/heic','text/html','application/pdf','application/octet-stream','binary/octet-stream','application/unknown','application/force-download','not-a-type'];
const rows=[];
for(const bytes of samples)for(const claim of claims) {
 const mime=sniffMimeType(new Uint8Array(bytes),claim);
 rows.push({bytes,claim,mime,kind:shared.classifyAttachment(mime),namesNoFormat:shared.namesNoFormat(claim),mediaType:shared.mediaTypeOf(claim)});
}
writeFileSync(root+'/android/core/src/test/resources/attachments-typescript-parity.json',JSON.stringify(rows,null,2)+'\n');
// Extract the original text function without importing the server's storage modules.
const parts=readFileSync(root+'/server/src/channels/attachment-parts.ts','utf8');
const start=parts.indexOf('function extractDocumentText('),end=parts.indexOf('\n/**',start);
const textPath=scratch+'/original-attachment-text.ts';
writeFileSync(textPath,'const MAX_EXTRACTED_CHARACTERS='+shared.MAX_EXTRACTED_CHARACTERS+';\nexport '+parts.slice(start,end));
const {extractDocumentText}=await import(textPath);
const texts=['plain text','\ufefftext','😀'.repeat(60001),'a'.repeat(119999)+'😀 last','b'.repeat(120001)];
writeFileSync(root+'/android/core/src/test/resources/attachment-text-typescript-parity.json',JSON.stringify(texts.map(text=>({text,expected:extractDocumentText(Buffer.from(text,'utf8'))})),null,2)+'\n');
console.log(`${rows.length} attachment MIME reference cases`);

const upload=readFileSync(root+'/server/src/channels/attachments.ts','utf8');
const nameStart=upload.indexOf('const MAX_FILENAME_BYTES =');
const nameEnd=upload.indexOf('\n/**',upload.indexOf('function withinFilenameLimit(',nameStart));
const namePath=scratch+'/original-attachment-name.ts';
writeFileSync(namePath,upload.slice(nameStart,nameEnd)+'\nexport {withinFilenameLimit};');
const {withinFilenameLimit}=await import(namePath);
const names=['short.txt','a'.repeat(256),'😀'.repeat(64)+'file.png','a'.repeat(254)+'😀.txt','名'.repeat(90),'a'.repeat(254)+'\ud800tail'];
writeFileSync(root+'/android/core/src/test/resources/attachment-name-typescript-parity.json',JSON.stringify(names.map(name=>({name,expected:withinFilenameLimit(name)})),null,2)+'\n');
