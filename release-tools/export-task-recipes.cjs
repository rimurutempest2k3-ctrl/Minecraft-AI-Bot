// Regenerate static crafting grids from the bundled official Minecraft recipe catalog.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),folder=path.join(root,'bot-common/src/main/resources/core-data');
const source=JSON.parse(fs.readFileSync(path.join(folder,'minecraft-recipes-26.2.json'),'utf8'));
const library=JSON.parse(fs.readFileSync(path.join(folder,'task-recipes.json'),'utf8'));
for(const key of Object.keys(library.recipes))if(key.startsWith('vanilla_'))delete library.recipes[key];
let count=0;
for(const [id,r] of Object.entries(source.recipes)) {
    if(!['minecraft:crafting_shaped','minecraft:crafting_shapeless'].includes(r.type))continue;
    const cells=[];let width;
    const cell=(row,column,value)=>{
        const choices=Array.isArray(value)?value:[value];
        if(!choices.length || choices.some(x=>typeof x!=='string'))throw new Error('Invalid ingredient: '+id);
        cells.push({row,column,ingredient:choices[0],...(choices.length>1?{alternatives:choices}:{})});
    };
    if(r.pattern) {
        width=Math.max(2,r.pattern.length,...r.pattern.map(x=>x.length));
        r.pattern.forEach((line,row)=>[...line].forEach((symbol,column)=>{if(symbol!==' ')cell(row,column,r.key[symbol]);}));
    } else {
        width=r.ingredients.length<=4?2:3;
        r.ingredients.forEach((value,i)=>cell(Math.floor(i/width),i%width,value));
    }
    if(width>3 || !r.result?.id)throw new Error('Unsupported grid: '+id);
    library.recipes['vanilla_'+id.split(':')[1]]={output:r.result.id,count:r.result.count||1,width,cells,...(r.result.components?{components:r.result.components}:{})};count++;
}
const text=JSON.stringify(library,null,2)+'\n';
for(const destination of [path.join(folder,'task-recipes.json'),path.join(root,'task-pack/config/minecraft-ai-bot/core-data/task-recipes.json')])fs.writeFileSync(destination,text);
console.log(`Exported ${count} vanilla crafting grids plus ${Object.keys(library.recipes).length-count} compatibility recipes.`);
