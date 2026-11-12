import {readFileSync,writeFileSync} from 'node:fs';
const root=process.env.OPENBOT_FIXTURE_ROOT!;
const scratch=process.env.OPENBOT_FIXTURE_TMP!;
const source=readFileSync(root+'/server/src/responsibilities/types.ts','utf8');
const start=source.indexOf('const TITLE_QUOTES');
const why=source.indexOf('export function whyItRepeats',start);
const end=source.indexOf('\n}',why)+2;
const path=scratch+'/original-goal-links.ts';writeFileSync(path,source.slice(start,end));
const {routineRunsGoal}=await import(path);
const goal={id:'goal-id',title:'Track OpenBot releases'};
const cases=[
 {goalId:'goal-id',goalByTitle:false,instruction:'Work'},
 {goalId:'other',goalByTitle:true,instruction:'Run goal-id'},
 {goalId:null,goalByTitle:false,instruction:'Run goal-id'},
 {goalId:null,goalByTitle:true,instruction:'Run goal-id'},
 ...['"Track OpenBot releases"','“Track OpenBot releases”',"'Track OpenBot releases'",'‘Track OpenBot releases’','«Track OpenBot releases»','`Track OpenBot releases`','Track OpenBot releases','"Track OpenBot"','"track openbot releases"'].map(title=>({goalId:null,goalByTitle:true,instruction:'Run the goal '+title}))
];
writeFileSync(root+'/android/core/src/test/resources/goal-links-typescript-parity.json',JSON.stringify(cases.map(routine=>({routine,goal,matches:routineRunsGoal(routine,goal)})),null,2)+'\n');
