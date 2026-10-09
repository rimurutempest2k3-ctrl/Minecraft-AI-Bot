'use strict';
const $=s=>document.querySelector(s),history=[];
let snapshot=null,sending=false,polling=false,aiDirty=false,baritoneDirty=false,selectedChest='',slotSelection=-1,inventorySignature='',chestSignature='',selectedWorkflow='',librarySignature='';
const features=[['survival','Survival','bot survival','Eat, defend and retreat.'],['combat','Combat','bot survival combat','Attack mobs when conditions allow.'],['ranged','Ranged bow combat','bot survival ranged','Skeleton, stray, bogged; predicts motion and arrow drop.'],['shield','Use shield','bot survival shield','Equip offhand and raise shield.'],['equipment','Equipment & hotbar sorting','bot survival equipment','Prefer better armor, an offhand shield and tool sorting when safe.'],['loot','Useful loot pickup','bot loot','Scan every 2 seconds; after death, recover all drops near the death location in loaded chunks.'],['trash','Auto trash cleanup','bot trash auto','Apply the filter when inventory is nearly full.'],['ai','AI execution','bot ai auto','Execute supported AI commands.'],['alerts','Survival alerts','bot survival alerts','Notify about missing items and low durability.']];
function node(tag,value,className){const el=document.createElement(tag);if(value!==undefined)el.textContent=value;if(className)el.className=className;return el;}
function text(selector,value){$(selector).textContent=value??'—';}
function itemText(item){return item?.id?(item.name||item.id)+' x'+item.count+(item.durability?' · durability '+item.durability:''):'Empty';}
function limit(){return Math.max(50,Math.min(500,Number($('#log-limit').value)||500));}
function group(message){return /^(AI|AI suggestion)|Gemini|Groq|API|key |model/i.test(message)?'API':/^Survival|Danger:|Preparation:|armor|shield|loot|trash/i.test(message)?'Survival':/Local:|task|collected|completed|chest|smelt|craft/i.test(message)?'Tasks':'System';}
function renderLogs(){const box=$('#bot-logs'),filter=$('#log-filter').value;box.replaceChildren();history.filter(e=>filter==='all'||e.kind===filter).forEach(e=>{const row=node('div',undefined,'entry');row.append(node('time',e.time),node('span',e.kind,'kind'),node('span',e.text));if(e.rawContent)row.lastElementChild.dataset.noI18n='';box.append(row);});}
function append(message,channel='ERROR'){
    const entry={time:new Date().toLocaleTimeString(),kind:channel==='CHAT'?'Chat':channel==='AI'?'API':channel==='ERROR'?'Error':group(message),text:String(message)};
    entry.rawContent=channel==='CHAT'||channel==='AI';
    history.push(entry);while(history.length>limit())history.shift();
    // Activity events belong only to the full log, not the interactive console.
    if(channel!=='ACTIVITY') {
        const box=$('#log'),atEnd=box.scrollHeight-box.scrollTop-box.clientHeight<60;
        const line=node('div',entry.time+' · '+entry.text);if(entry.rawContent)line.dataset.noI18n='';
        box.append(line);while(box.children.length>limit())box.firstElementChild.remove();
        if(atEnd)box.scrollTop=box.scrollHeight;
    }
    renderLogs();
}
const events=new EventSource('/events');events.onopen=()=>text('#connection','Connected');events.onerror=()=>text('#connection','Disconnected · retrying');
events.onmessage=e=>{try{const data=JSON.parse(e.data);append(typeof data==='string'?data:data.text,typeof data==='string'?'RESPONSE':data.channel);}catch{append('Could not read a log entry.');}};
async function request(command){const response=await fetch('/command',{method:'POST',headers:{'Content-Type':'application/json','X-Bot-Request':'1'},body:JSON.stringify({command})});const data=await response.json();if(!response.ok)throw new Error(data.message||'Request incomplete.');text('#settings-feedback',data.message);return data.message;}
function lock(value){sending=value;document.querySelectorAll('[data-command],.switch,#save-api,#save-ai,#send-command,#baritone-form button,#browse-workflow').forEach(el=>el.disabled=value||!snapshot);const selected=snapshot?.workflows?.files?.find(f=>f.filename===selectedWorkflow);$('#run-workflow').disabled=value||!snapshot?.inWorld||!selected||!!selected.error;}
async function action(work){if(sending)return;lock(true);try{await work();}catch(error){text('#settings-feedback',error.message);append(error.message+' Commands are not retried automatically; check state before resubmitting.');}finally{lock(false);await refresh();}}
async function send(command){if(command.trim())return action(()=>request(command));}
function apiStatus(){const config=snapshot?.ai?.providers?.[$('#api-provider').value];text('#api-status',config?(config.configured?'Configured':'No key')+(config.source==='ENVIRONMENT'?' · from environment variable':''):'Configuration not loaded');}
features.forEach(([id,name,command,hint])=>{const row=node('div',undefined,'row'),description=node('div');description.append(node('h3',name),node('p',hint));const controls=node('div',undefined,'controls'),label=node('span','—','label'),button=node('button',undefined,'switch');button.type='button';button.setAttribute('role','switch');button.setAttribute('aria-label',name);button.setAttribute('aria-checked','false');button.dataset.feature=id;button.disabled=true;button.addEventListener('click',()=>send(command+' '+(snapshot?.features?.[id]?'off':'on')));controls.append(label,button);row.append(description,controls);$('#feature-list').append(row);});
function infoBox(label,value){const box=node('div',undefined,'info');box.append(node('small',label),node('b',value));return box;}
function renderSlots(items){const signature=JSON.stringify(items);if(signature===inventorySignature)return;inventorySignature=signature;for(const [id,start,count] of [['bag-slots',9,27],['hotbar-slots',0,9]]){const box=$('#'+id);box.replaceChildren();for(let n=0;n<count;n++){const index=start+n,item=items[index],slot=node('button',item?.id?(item.name||item.id):'','slot');slot.type='button';slot.style.overflow='hidden';slot.setAttribute('aria-label',itemText(item));slot.title=itemText(item);if(index===slotSelection)slot.classList.add('selected');if(item?.id)slot.append(node('span',String(item.count),'qty'));slot.addEventListener('click',()=>{slotSelection=index;document.querySelectorAll('.slot.selected').forEach(el=>el.classList.remove('selected'));slot.classList.add('selected');text('#item-detail',item?.id?itemText(item)+' · '+item.id:'Empty slot.');});box.append(slot);}}if(slotSelection>=0)text('#item-detail',itemText(items[slotSelection])+(items[slotSelection]?.id?' · '+items[slotSelection].id:''));}
function showChest(){const chest=(snapshot?.chests?.entries||[]).find(c=>c.id===selectedChest),body=$('#chest-items');body.replaceChildren();text('#chest-title',chest?'Chest '+chest.id:'No remembered chests');text('#chest-info',chest?(chest.type==='double'?'Double chest':'Single chest')+' · '+chest.slotCount+' slots · '+chest.positions.map(p=>'('+p.x+', '+p.y+', '+p.z+')').join(' + ')+' · Last recorded: '+new Date(chest.lastSeen).toLocaleString('vi-VN'):'');if(chest){const totals=new Map();for(const slot of chest.slots||[]){if(!slot.item)continue;const item=totals.get(slot.item)||{name:slot.name||slot.item,count:0};item.count+=slot.count;totals.set(slot.item,item);}for(const [id,item] of totals){const row=node('tr'),name=node('td',item.name);name.append(node('br'),node('code',id));row.append(name,node('td',String(item.count)));body.append(row);}if(totals.size===0){const cell=node('td','Chest was empty at the last recording.');cell.colSpan=2;const row=node('tr');row.append(cell);body.append(row);}}document.querySelectorAll('[data-chest]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.chest===selectedChest)));}
function renderChests(){const memory=snapshot.chests||{},entries=memory.entries||[];text('#chest-scope',memory.error||[memory.scope,memory.dimension].filter(Boolean).join(' · ')||'Not in a world');text('#chest-count',entries.length+' chests');text('#chest-file',memory.file?'File: '+memory.file:'');const signature=JSON.stringify(memory);if(signature===chestSignature)return;chestSignature=signature;if(!entries.some(c=>c.id===selectedChest))selectedChest=entries[0]?.id||'';const box=$('#chest-list');box.replaceChildren();entries.forEach(chest=>{const button=node('button','Chest '+chest.id,'chest-card');button.type='button';button.style.overflowWrap='anywhere';button.dataset.chest=chest.id;button.append(node('small',chest.positions.map(p=>p.x+', '+p.y+', '+p.z).join(' + ')));button.addEventListener('click',()=>{selectedChest=chest.id;showChest();});box.append(button);});showChest();}
function render(data){snapshot=data;$('#language-select').value=data.language||'en';window.BotLanguage.use(data.language||'en');renderSupplies(data.starter);renderLibrary(data.workflows);text('#world-state',data.inWorld?'In a world':'Not in a world · enter the game');text('#state',data.state);text('#bot-name',data.name||'No player');const details=$('#bot-details');details.replaceChildren();for(const [label,value] of [['Server / world',data.server],['Dimension',data.dimension],['Holding',itemText(data.held)],['Offhand',itemText(data.offhand)],['Preferred AI',data.ai?.provider],['Survival',data.tasks?.survival]])details.append(infoBox(label,value||'—'));const vitals=$('#vitals');vitals.replaceChildren();for(const [label,value] of [['Health',data.inWorld?data.health+' / '+data.maxHealth:'—'],['Hunger',data.inWorld?data.hunger+' / 20':'—'],['Coordinates',data.coordinates]]){const el=node('span',label);el.append(node('b',value||'—'));vitals.append(el);}let on=0;document.querySelectorAll('[data-feature]').forEach(button=>{const value=!!data.features?.[button.dataset.feature];if(value)on++;button.setAttribute('aria-checked',String(value));button.previousElementSibling.textContent=value?'ON':'OFF';button.previousElementSibling.classList.toggle('off',!value);});text('#count',on+' / '+features.length+' enabled');renderSlots(data.inventory||[]);text('#inventory-count',(data.inventory||[]).filter(i=>i.id).length+' / 36 slots');const equipment=$('#equipment-list');equipment.replaceChildren();for(const [id,name] of [['HEAD','Helmet'],['CHEST','Chestplate'],['LEGS','Leggings'],['FEET','Boots'],['offhand','Offhand']]){const row=node('div',undefined,'setting');row.append(node('span',name),node('b',itemText(id==='offhand'?data.offhand:data.equipment?.[id])));equipment.append(row);}const tasks=$('#task-list');tasks.replaceChildren();for(const [id,name] of [['local','Local'],['movement','Baritone'],['furnace','Nung'],['placement','Place block'],['chest','Chest'],['survival','Survival'],['ai','AI']]){const row=node('div',undefined,'step'),content=node('div');content.append(node('b',name),node('small',data.tasks?.[id]||'—'));row.append(content);tasks.append(row);}if(!baritoneDirty)document.querySelectorAll('[data-setting]').forEach(input=>{if(input.type==='checkbox')input.checked=!!data.baritone?.[input.dataset.setting];else input.value=data.baritone?.[input.dataset.setting]??8;});if(!aiDirty && data.ai?.provider){$('#api-provider').value=data.ai.provider;$('#api-fallback').checked=data.ai.fallback!=='off';$('#api-model').value=data.ai.providers?.[data.ai.provider]?.model||'';}apiStatus();renderChests();const progress=data.progress||{};$("#task-progress").hidden=!(progress.target>0);if(progress.target>0){text("#progress-label",progress.label+" · "+progress.collected+" / "+progress.target);$("#progress-fill").style.width=Math.max(0,Math.min(100,progress.collected/progress.target*100))+"%";}lock(sending);}
async function refresh(){if(polling||document.hidden)return;polling=true;try{const response=await fetch('/data',{cache:'no-store'});const data=await response.json();if(!response.ok)throw new Error();render(data);}catch{snapshot=null;lock(sending);text('#connection','Data unavailable · reload the page if the game just restarted');text('#world-state','The data below is from the previous read; updates are unavailable.');}finally{polling=false;}}
$('#command-form').addEventListener('submit',e=>{e.preventDefault();const command=$('#command-input').value;$('#command-input').value='';send(command);});document.querySelectorAll('[data-command]').forEach(button=>button.addEventListener('click',()=>send(button.dataset.command)));
$('#api-provider').addEventListener('change',()=>{aiDirty=true;$('#api-key').value='';$('#api-model').value=snapshot?.ai?.providers?.[$('#api-provider').value]?.model||'';apiStatus();});$('#api-model').addEventListener('input',()=>aiDirty=true);$('#api-fallback').addEventListener('change',()=>aiDirty=true);
$('#api-key-form').addEventListener('submit',e=>{e.preventDefault();const key=$('#api-key').value.trim(),provider=$('#api-provider').value;$('#api-key').value='';if(!/^[A-Za-z0-9._-]{10,512}$/.test(key)){text('#api-status','Key must be a continuous string of 10–512 characters.');return;}action(()=>request('bot get API '+provider+' '+key));});
$('#save-ai').addEventListener('click',()=>{const provider=$('#api-provider').value,model=$('#api-model').value.trim(),fallback=$('#api-fallback').checked?['groq','gemini','openai'].filter(p=>p!==provider).join(','):'off';if(!/^[a-zA-Z0-9._/-]{1,120}$/.test(model)){text('#settings-feedback','Invalid model name.');return;}action(async()=>{for(const command of ['bot ai use '+provider,'bot ai model '+provider+' '+model,'bot ai fallback '+fallback]){const message=await request(command);if(!message.startsWith('Saved'))throw new Error(message);}aiDirty=false;});});
document.querySelectorAll('[data-setting]').forEach(input=>input.addEventListener('input',()=>baritoneDirty=true));$('#baritone-form').addEventListener('submit',e=>{e.preventDefault();const values=[...document.querySelectorAll('[data-setting]')].map(input=>[input.dataset.setting,input.type==='checkbox'?(input.checked?'on':'off'):input.value]);action(async()=>{for(const [setting,value] of values){const message=await request('bot web baritone '+setting+' '+value);if(!message.startsWith('Applied'))throw new Error(message);}baritoneDirty=false;});});
$('#clear').addEventListener('click',()=>$('#log').replaceChildren());$('#clear-history').addEventListener('click',()=>{history.length=0;renderLogs();});$('#log-filter').addEventListener('change',renderLogs);$('#log-limit').addEventListener('change',()=>{while(history.length>limit())history.shift();renderLogs();});
const tabs=[...document.querySelectorAll('nav.tabs > [role=tab]')],strip=$('nav.tabs');strip.style.overflowX='auto';strip.style.overflowY='hidden';tabs.forEach(t=>t.style.flexShrink='0');function showTab(tab){tabs.forEach(t=>{const active=t===tab;t.setAttribute('aria-selected',String(active));t.tabIndex=active?0:-1;$('#'+t.dataset.panel).hidden=!active;});tab.scrollIntoView({block:'nearest',inline:'nearest'});}
tabs.forEach((tab,i)=>{tab.addEventListener('click',()=>showTab(tab));tab.addEventListener('keydown',e=>{let next;if(e.key==='ArrowRight')next=(i+1)%tabs.length;else if(e.key==='ArrowLeft')next=(i+tabs.length-1)%tabs.length;else if(e.key==='Home')next=0;else if(e.key==='End')next=tabs.length-1;else return;e.preventDefault();showTab(tabs[next]);tabs[next].focus();});});strip.addEventListener('wheel',e=>{if(e.ctrlKey)return;const max=strip.scrollWidth-strip.clientWidth;if(max<=0)return;const delta=(Math.abs(e.deltaX)>Math.abs(e.deltaY)?e.deltaX:e.deltaY)*(e.deltaMode===1?24:e.deltaMode===2?strip.clientWidth:1),next=Math.max(0,Math.min(max,strip.scrollLeft+delta));if(next===strip.scrollLeft)return;e.preventDefault();strip.scrollLeft=next;},{passive:false});
lock(false);setInterval(refresh,2000);refresh();


$("#command-input").addEventListener("input",()=>{$("#command-input").type=/^\s*bot\s+get\s+api\b/i.test($("#command-input").value)?"password":"text";});

function renderSupplies(plan){
    $('#starter-auto').setAttribute('aria-checked',String(!!plan?.automatic));
    text('#starter-status',plan?.status||'No task.');
    text('#running-workflow-title',plan?.title?(plan.title+' · '+plan.file):'No task file running');
    const states={RUNNING:'Running',COMPLETED:'Completed',CANCELLED:'Stopped',IDLE:'Not started'};
    const done=(plan?.steps||[]).filter(s=>['COMPLETED','SKIPPED'].includes(s.state)).length;
    text('#workflow-summary',(states[plan?.state]||'Not started')+' · '+done+' / '+(plan?.steps?.length||0)+' steps finished');
    const labels={COMPLETED:'Done',SKIPPED:'Already available · skipped',RUNNING:'In progress',STOPPED:'Stopped here',WAITING:'Pending'};
    const box=$('#supply-list');box.replaceChildren();
    (plan?.steps||[]).forEach((s,i)=>{const row=node('div',undefined,'step '+(s.state==='RUNNING'?'current':['COMPLETED','SKIPPED'].includes(s.state)?'completed':s.state==='STOPPED'?'stopped':'waiting')),content=node('div');
        row.append(node('span',String(i+1),'num'));content.append(node('b',s.label),node('small',(labels[s.state]||'Pending')+' · Target '+s.target+' · '+(plan?.mode==='collect'?'Added':'Present')+' '+s.present));row.append(content);box.append(row);
    });
}
function previewWorkflow(){
    const file=snapshot?.workflows?.files?.find(f=>f.filename===selectedWorkflow);
    text('#workflow-title',file?.title||'Select a task file');text('#workflow-description',file?(file.description+' · '+file.filename+' · '+(file.mode==='collect'?'Collect requested quantity':'Ensure required stock')):'');
    $('#workflow-error').hidden=!file?.error;text('#workflow-error',file?.error||'');
    const box=$('#workflow-preview');box.replaceChildren();
    const actions={mine:'Gather materials',craft:'Craft',food:'Hunt and cook food',smelt:'Smelt'};
    (file?.steps||[]).forEach((s,i)=>{const row=node('div',undefined,'step'),content=node('div');row.append(node('span',String(i+1),'num'));
        content.append(node('b',s.label),node('small',(actions[s.action]||s.action)+' · '+s.item+' ×'+s.count));
        const ingredients=Object.entries(s.ingredients||{}).map(([item,count])=>item+' ×'+count).join(', ');
        if(ingredients)content.append(node('br'),node('small','Ingredients per craft: '+ingredients));row.append(content);box.append(row);
    });
    document.querySelectorAll('[data-workflow-file]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.workflowFile===selectedWorkflow)));lock(sending);
}
function renderLibrary(library,force=false){
    const signature=JSON.stringify(library);text('#workflow-directory',library?.error||('Directory: '+(library?.directory||'Not initialized')));
    if(!force&&signature===librarySignature)return;librarySignature=signature;
    const files=library?.files||[];if(!files.some(f=>f.filename===selectedWorkflow))selectedWorkflow=files.find(f=>!f.error)?.filename||files[0]?.filename||'';
    const query=$('#workflow-search').value.trim().toLocaleLowerCase('vi-VN'),box=$('#workflow-files');box.replaceChildren();
    files.filter(f=>(f.title+' '+f.filename+' '+f.description).toLocaleLowerCase('vi-VN').includes(query)).forEach(f=>{
        const b=node('button',f.title,'chest-card');b.type='button';b.dataset.workflowFile=f.filename;b.append(node('small',f.filename+' · '+(f.error?'Invalid file':f.steps.length+' steps')));
        b.addEventListener('click',()=>{selectedWorkflow=f.filename;previewWorkflow();});box.append(b);
    });if(!box.children.length)box.append(node('p','No matching files.','notice'));previewWorkflow();
}
const workflowTabs=[...document.querySelectorAll('[data-workflow-tab]')];
function showWorkflowTab(id){workflowTabs.forEach(b=>{const active=b.dataset.workflowTab===id;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;$('#'+b.dataset.workflowTab).hidden=!active;});}
workflowTabs.forEach((b,i)=>{b.addEventListener('click',()=>showWorkflowTab(b.dataset.workflowTab));b.addEventListener('keydown',e=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(e.key))return;e.preventDefault();const next=e.key==='Home'?0:e.key==='End'?1:1-i;showWorkflowTab(workflowTabs[next].dataset.workflowTab);workflowTabs[next].focus();});});
$('#browse-workflow').addEventListener('click',()=>$('#import-workflow-file').click());
$('#import-workflow-file').addEventListener('change',()=>{
    const input=$('#import-workflow-file'),file=input.files?.[0];input.value='';if(!file)return;
    action(async()=>{
        const feedback=$('#task-import-feedback');feedback.hidden=false;
        try {
            if(!/\.(taskbot|json)$/i.test(file.name))throw new Error('Choose a .taskbot or .json task file.');
            if(file.size>262144)throw new Error('Task file exceeds 256 KB');
            text('#task-import-feedback','Checking and importing task file…');
            const response=await fetch('/tasks/import',{method:'POST',headers:{'Content-Type':'application/json','X-Bot-Request':'1','X-Bot-Filename':encodeURIComponent(file.name)},body:await file.text()});
            const data=await response.json();if(!response.ok)throw new Error(data.message||'Task import failed.');
            selectedWorkflow=data.filename;librarySignature='';$('#workflow-search').value='';
            text('#task-import-feedback',data.message);append(data.message,'RESPONSE');
        } catch(error){text('#task-import-feedback',error.message);throw error;}
        finally{input.value='';}
    });
});
$('#workflow-search').addEventListener('input',()=>renderLibrary(snapshot?.workflows,true));
$('#run-workflow').addEventListener('click',()=>action(async()=>{const message=await request('bot workflow run '+selectedWorkflow);if(!message.startsWith('Previous task stopped.'))throw new Error(message);showWorkflowTab('workflow-progress');}));
$('#starter-auto').addEventListener('click',()=>send('bot starter auto '+(snapshot?.starter?.automatic?'off':'on')));

$('#language-select').addEventListener('change',()=>action(async()=>{
    const selected=$('#language-select').value;
    const result=await request('bot language '+selected);
    if(!result.startsWith('Language saved:'))throw new Error(result);
    await window.BotLanguage.use(selected,true);
}));


