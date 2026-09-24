// Rebuild or verify with the existing frontend toolchain; Gradle never invokes this script.
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import config from './tailwind.config.mjs';
const here=dirname(fileURLToPath(import.meta.url));
if(!process.argv[2]) throw new Error('Usage: node build.mjs <veto-ui-directory> [--check]');
const require=createRequire(resolve(process.argv[2],'package.json'));
const postcss=require('postcss'),tailwind=require('tailwindcss'),esbuild=require('esbuild');
const css=await postcss([tailwind({...config,content:[resolve(here,'*.tsx')]})]).process('@tailwind utilities;', {from:undefined});
const result=await esbuild.build({absWorkingDir:here,entryPoints:['index.tsx'],bundle:true,format:'esm',target:'es2022',jsx:'transform',write:false,plugins:[{name:'scoped-style',setup(build){build.onLoad({filter:/style\.css$/},()=>({contents:css.css,loader:'text'}));}}]});
const output=resolve(here,'../../src/main/resources/frontend/tools.js');
if(process.argv.includes('--check')) {
 if(await readFile(output,'utf8')!==result.outputFiles[0].text) throw new Error('Builtin tools.js is stale; rebuild it before delivery');
} else {await writeFile(resolve(here,'style.css'),css.css);await writeFile(output,result.outputFiles[0].text);}
