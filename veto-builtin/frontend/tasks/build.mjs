import {readFile,writeFile} from 'node:fs/promises';
const source=await readFile(new URL('./index.js',import.meta.url),'utf8');
const output=new URL('../../src/main/resources/frontend/tasks.js',import.meta.url);
if(process.argv.includes('--check')){if(await readFile(output,'utf8')!==source)throw new Error('tasks.js is stale');}else await writeFile(output,source);
