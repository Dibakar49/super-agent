const root=process.env.OPENBOT_FIXTURE_ROOT!;
const {nextOccurrence,describeCron}=await import(root+'/server/src/routines/schedule.ts');
const expressions=['30 9 * * *','0 9 * * 1-5','0 9 * * 0','0 9 * * 7','0,30 9 * * *','0,30,0 9 * * *','*/20 * * * *','*/15 * * * *','*/40 * * * *','0 9 29 2 *','* * * * *','*/5 * * * *','45,55 8 * * *','0,10 9 25 12 *','0,50 0,23 * * *','0 9 * JAN MON','0 9 L * *','0 9 * * 5L','0 9 * * 1#2','0 9 1 * 1','0 9 * *','0 0 9 * * *','not a cron expression','0 25 * * *'];
const zones=['UTC','America/New_York','Asia/Calcutta','Europe/Madrid'];
const starts=['2026-01-01T00:00:00Z','2026-03-07T00:00:00Z','2026-10-31T00:00:00Z'];
const rows=[];
for(const cron of expressions)for(const timezone of zones){const after=starts[rows.length%starts.length];try{rows.push({cron,timezone,after,next:nextOccurrence(cron,timezone,new Date(after)).toISOString(),description:describeCron(cron)})}catch(e){rows.push({cron,timezone,after,error:(e as Error).message,description:describeCron(cron)})}}
for(const [cron,timezone,after]of [['30 2 * * *','America/New_York','2026-03-08T05:00:00Z'],['30 1 * * *','America/New_York','2026-11-01T05:45:00Z'],['30 1 * * *','America/New_York','2026-11-01T06:15:00Z'],['*/15 * * * *','America/New_York','2026-11-01T05:45:00Z'],['0 9 * * *','Wrong/Zone','2026-01-01T00:00:00Z']]){try{rows.push({cron,timezone,after,next:nextOccurrence(cron,timezone,new Date(after)).toISOString(),description:describeCron(cron)})}catch(e){rows.push({cron,timezone,after,error:(e as Error).message,description:describeCron(cron)})}}
await Bun.write(root+'/android/core/src/test/resources/routines-typescript-parity.json',JSON.stringify(rows,null,2));
console.log(`${rows.length} schedule results generated from unchanged original TypeScript`);
