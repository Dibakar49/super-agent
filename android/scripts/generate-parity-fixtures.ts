/** Development only: re-run unchanged TypeScript references; the Android runtime remains Kotlin. */
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
const root=resolve(import.meta.dir,'../..');
const scratch=mkdtempSync(join(tmpdir(),'openbot-kotlin-parity-'));
try {
  for(const name of ['routine-fixtures.ts','memory-fixtures.ts','goal-link-fixtures.ts','extract-goal-tools.ts','export-policy-reference.ts','policy-fixtures.ts','question-fixtures.ts','attachment-fixtures.ts','dictation-fixtures.ts']) {
    const child=Bun.spawn([process.execPath,join(import.meta.dir,name)],{
      cwd:root,env:{...process.env,OPENBOT_FIXTURE_ROOT:root,OPENBOT_FIXTURE_TMP:scratch},stdout:'inherit',stderr:'inherit',
    });
    const code=await child.exited;
    if(code!==0)throw new Error(`${name} failed with exit code ${code}`);
  }
} finally {rmSync(scratch,{recursive:true,force:true});}
