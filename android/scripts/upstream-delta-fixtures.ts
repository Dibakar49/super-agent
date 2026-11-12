/** Pinned upstream oracle. Does not replace the retained 0.3.4 regression fixtures. */
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
const root = resolve(import.meta.dir, '../..');
const revision = '68fe32774a83689a862d5821a6260e72e5f8fdb9';
const scratch = mkdtempSync(join(tmpdir(), 'openbot-upstream-'));
function source(path: string) {
  const result = Bun.spawnSync(['git', 'show', `${revision}:${path}`], { cwd: root });
  if (result.exitCode !== 0) throw new Error(`Fetch upstream ${revision} before generating fixtures.`);
  return result.stdout.toString();
}
try {
  const schedulePath = join(scratch, 'schedule.ts');
  writeFileSync(schedulePath, source('server/src/routines/schedule.ts').replace('"cron-parser"', JSON.stringify(Bun.resolveSync('cron-parser', join(root, 'server')))));
  const outcomesPath = join(scratch, 'outcomes.ts');
  writeFileSync(outcomesPath, source('shared/approval-outcomes.ts'));
  const schedule = await import(schedulePath);
  const outcomes = await import(outcomesPath);
  const cron = ['0 * * * *', '5 * * * *', '59 * * * *', '05 * * * *', '0 * * 2 *', '*/15 * * * *'].map(expression => ({ expression, description: schedule.describeCron(expression) }));
  const reasons = ['the result was unavailable.', 'the server (api.example.org) stopped.'];
  const notRun = reasons.map(why => ({ why, answer: outcomes.notRun(why), person: outcomes.outcomeForPerson(outcomes.notRun(why)) }));
  const named = ['WITHDRAWN_APPROVALS_OFF', 'WITHDRAWN_RULE_ALLOWS', 'NOT_RUN_WHILE_WAITING'].map(name => ({ name, answer: outcomes[name], person: outcomes.outcomeForPerson(outcomes[name]) }));
  let unknownTimezone;
  try { schedule.nextOccurrence('0 * * * *', 'Wrong/Timezone', new Date('2026-11-12T00:00:00Z')); }
  catch (error) { unknownTimezone = { code: error.code, message: error.message }; }
  await Bun.write(join(root, 'android/core/src/test/resources/upstream-delta.json'), JSON.stringify({ revision, cron, notRun, named, unknownTimezone }, null, 2) + '\n');
} finally { rmSync(scratch, { recursive: true, force: true }); }
