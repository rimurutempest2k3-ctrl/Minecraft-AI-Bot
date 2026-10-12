'use strict';
(function(root){
    const actions=['mine','craft','smelt','food','chest_take','chest_put','chat','command','menu_open','menu_click'];
    const controls=new Set(['chat','command','menu_open','menu_click']);
    const id=value=>/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(value||'');
    function integer(value,min,max){if(!/^-?\d+$/.test(String(value)))throw new Error('Enter a whole number.');const n=Number(value);if(!Number.isSafeInteger(n)||n<min||n>max)throw new Error('Number is outside the allowed range.');return n;}
    function makeStep(values){
        const action=values.action,label=String(values.label||'').trim();
        if(!actions.includes(action)||!label||label.length>160)throw new Error('Choose an action and enter a step name.');
        const step={label,action,count:controls.has(action)?1:integer(values.count,1,64)};
        if(!['chat','command','menu_click'].includes(action)){if(!id(values.item))throw new Error('Enter an item ID such as minecraft:cobblestone.');step.item=values.item;}
        if(['mine','craft'].includes(action)&&values.role){if(!['logs','pickaxe','axe','sword'].includes(values.role))throw new Error('Invalid item group.');step.role=values.role;}
        if(action==='food')step.role='food';
        if(action==='smelt'){if(!id(values.input))throw new Error('Enter the furnace input item ID.');step.input=values.input;}
        if(action.startsWith('chest_'))step.chest={x:integer(values.x,-29999984,29999984),y:integer(values.y,-2048,2048),z:integer(values.z,-29999984,29999984)};
        if(['chat','command'].includes(action)){const text=String(values.text||'');if(!text.trim()||text.length>256||/[\r\n]/.test(text)||text.startsWith('/'))throw new Error('Enter one line of text; commands must not start with /.');step.text=text;}
        if(action.startsWith('menu_')){
            const title=String(values.title||'').trim();if(!title||title.length>160)throw new Error('Enter the exact menu title.');step.menu={title};
            if(action==='menu_click'){
                step.menu.slot=integer(values.slot,0,53);if(!id(values.expectedItem))throw new Error('Enter the expected menu item ID.');step.menu.expectedItem=values.expectedItem;
                if(values.result==='closed')step.menu.closed=true;
                else if(values.result==='next'){const next=String(values.nextTitle||'').trim();if(!next||next===title||next.length>160)throw new Error('Enter a different next menu title.');step.menu.nextTitle=next;}
                else throw new Error('Choose the expected menu result.');
            }
        }
        return step;
    }
    function documentFor(meta,steps){
        const filename=String(meta.filename||'').trim().toLowerCase().replace(/\.taskbot$/,'')+'.taskbot';
        if(!/^[a-z0-9][a-z0-9_-]{0,63}\.taskbot$/.test(filename))throw new Error('Filename: use 1–64 letters, digits, hyphens or underscores.');
        const title=String(meta.title||'').trim(),description=String(meta.description||'');
        if(!title||title.length>160||description.length>1000)throw new Error('Enter a task title and a description up to 1000 characters.');
        if(!['ensure','collect'].includes(meta.mode)||!steps.length||steps.length>100)throw new Error('Add 1–100 steps and choose a task mode.');
        return {filename,task:{version:1,mode:meta.mode,title,description,steps:JSON.parse(JSON.stringify(steps))}};
    }
    const api={makeStep,documentFor};
    if(typeof module!=='undefined'&&module.exports){module.exports=api;return;}
    root.TaskBuilder=api;
    const q=s=>document.querySelector(s),field=name=>q('#builder-step').elements.namedItem(name);
    let steps=[];
    const pending=new Map();
    function feedback(value){q('#builder-feedback').textContent=value;}
    function values(){return Object.fromEntries(new FormData(q('#builder-step')));}
    function meta(){return Object.fromEntries(new FormData(q('#builder-meta')));}
    function visibility(form=q('#builder-step')){
        const input=name=>form.elements.namedItem(name),action=input('action').value;
        form.querySelectorAll('[data-builder-actions]').forEach(el=>{el.hidden=!el.dataset.builderActions.split(' ').includes(action);});
        form.querySelector('[name="nextTitle"]').closest('label').hidden=action!=='menu_click'||input('result').value!=='next';
        input('count').disabled=controls.has(action);if(controls.has(action))input('count').value='1';
        input('count').closest('label').hidden=controls.has(action);
    }
    function draft(){try{localStorage.setItem('minecraft-ai-bot-task-draft-v1',JSON.stringify({meta:meta(),steps}));}catch{}}
    function resetStep(){q('#builder-step').reset();visibility();q('#builder-add').textContent='Add step';q('#builder-cancel-edit').hidden=true;}
    function button(text,fn,disabled=false){const b=document.createElement('button');b.type='button';b.className='action';b.textContent=text;b.disabled=disabled;b.addEventListener('click',fn);return b;}
    function nodeForm(step,i){
        const form=q('#builder-step').cloneNode(true);form.removeAttribute('id');form.reset();
        form.querySelectorAll('[id]').forEach(el=>el.removeAttribute('id'));
        form.querySelector('button[type="button"]').remove();
        const submit=form.querySelector('button[type="submit"]');submit.textContent='Save step';
        const data=pending.get(step)||{...step,...step.chest,...step.menu,result:step.menu?.closed?'closed':'next'};
        for(const [key,value]of Object.entries(data)){const input=form.elements.namedItem(key);if(input&&typeof value!=='object')input.value=value;}
        const actionLabel=form.elements.namedItem('action').closest('label');actionLabel.classList.add('node-action');form.prepend(actionLabel);
        const status=document.createElement('p');status.className='section-hint';status.setAttribute('role','status');status.textContent=pending.has(step)?'Unsaved changes':'';form.append(status);
        form.addEventListener('input',()=>{pending.set(step,Object.fromEntries(new FormData(form)));status.textContent='Unsaved changes';});
        form.addEventListener('change',()=>{visibility(form);links();});
        form.addEventListener('submit',event=>{event.preventDefault();try{steps[i]=makeStep(Object.fromEntries(new FormData(form)));pending.delete(step);render();draft();feedback('Step saved in the draft.');}catch(error){status.textContent=error.message;}});
        visibility(form);return form;
    }
    function render(){
        const list=q('#builder-steps');list.replaceChildren();q('#builder-step-count').textContent=steps.length+' / 100';
        const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.classList.add('node-links');svg.setAttribute('aria-hidden','true');list.append(svg);
        steps.forEach((step,i)=>{
            const row=document.createElement('article');row.className='task-node';const tools=document.createElement('div');tools.className='actions';
            tools.append(button('Move up',()=>move(i,-1),i===0),button('Move down',()=>move(i,1),i===steps.length-1),button('Duplicate step',()=>{if(steps.length>=100||!savedNodes())return;steps.splice(i+1,0,JSON.parse(JSON.stringify(step)));render();draft();},steps.length>=100),button('Remove step',()=>{pending.delete(step);steps.splice(i,1);render();draft();}));
            const heading=document.createElement('div');heading.className='node-caption';heading.textContent=(i+1)+'. '+step.label;heading.dataset.noI18n='';
            row.append(heading,nodeForm(step,i),tools);list.append(row);
            place(row,{x:30+i*370,y:30});
        });
        if(!steps.length){const p=document.createElement('p');p.className='notice';p.textContent='No steps yet. Add the first action below.';list.append(p);}
        links();try{q('#builder-json').textContent=JSON.stringify(documentFor(meta(),steps).task,null,2);}catch{q('#builder-json').textContent='';}
    }
    function place(node,p){node.style.left=p.x+'px';node.style.top=p.y+'px';}
    function links(){
        const list=q('#builder-steps'),svg=list.querySelector('svg');if(!svg)return;
        const width=Math.max(900,steps.length*370+60),height=Math.max(500,...Array.from(list.querySelectorAll('.task-node'),node=>node.offsetHeight+60));list.style.width=width+'px';list.style.height=height+'px';svg.setAttribute('width',width);svg.setAttribute('height',height);svg.replaceChildren();
        for(let i=1;i<steps.length;i++){const line=document.createElementNS(svg.namespaceURI,'line');line.setAttribute('x1',30+(i-1)*370+330);line.setAttribute('y1',52);line.setAttribute('x2',30+i*370);line.setAttribute('y2',52);svg.append(line);}
    }
    function savedNodes(){if(pending.size){feedback('Save changes in each task node first.');return false;}return true;}
    function move(i,delta){if(!savedNodes())return;const to=i+delta;[steps[i],steps[to]]=[steps[to],steps[i]];render();draft();}
    new ResizeObserver(links).observe(q('#builder-steps'));
    field('action').addEventListener('change',()=>visibility());field('result').addEventListener('change',()=>visibility());
    q('#builder-step').addEventListener('submit',event=>{event.preventDefault();try{
        const step=makeStep(values());if(steps.length>=100)throw new Error('A task can contain at most 100 steps.');steps.push(step);
        resetStep();render();draft();feedback('Step saved in the draft.');
    }catch(error){feedback(error.message);}});
    q('#builder-cancel-edit').addEventListener('click',resetStep);
    q('#builder-meta').addEventListener('input',()=>{draft();render();});
    q('#builder-meta').addEventListener('submit',event=>event.preventDefault());
    function ready(){if(pending.size)throw new Error('Save changes in each task node first.');return documentFor(meta(),steps);}
    q('#builder-download').addEventListener('click',()=>{try{
        const {filename,task}=ready(),url=URL.createObjectURL(new Blob([JSON.stringify(task,null,2)+'\n'],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);feedback('Task file downloaded.');
    }catch(error){feedback(error.message);}});
    q('#builder-save').addEventListener('click',()=>action(async()=>{
        const button=q('#builder-save');button.disabled=true;
        try{
            const {filename,task}=ready();feedback('Checking and saving task…');
            const response=await fetch('/tasks/import',{method:'POST',headers:{'Content-Type':'application/json','X-Bot-Request':'1','X-Bot-Filename':encodeURIComponent(filename)},body:JSON.stringify(task)});
            const result=await response.json();if(!response.ok)throw new Error(result.message||'Task import failed.');
            selectedWorkflow=result.filename;librarySignature='';q('#workflow-search').value='';feedback(result.message);append(result.message,'RESPONSE');showWorkflowTab('workflow-library');
        }catch(error){feedback(error.message);throw error;}finally{button.disabled=false;}
    }));
    q('#builder-clear').addEventListener('click',()=>{if(!confirm('Clear this task draft?'))return;steps=[];pending.clear();q('#builder-meta').reset();resetStep();render();draft();feedback('Draft cleared.');});
    try{
        const raw=localStorage.getItem('minecraft-ai-bot-task-draft-v1');if(raw&&raw.length<=262144){const saved=JSON.parse(raw);if(Array.isArray(saved.steps)&&saved.steps.length<=100){
            // Revalidate browser draft data instead of trusting storage.
            steps=saved.steps.map(step=>makeStep({...step,...step.chest,...step.menu,result:step.menu?.closed?'closed':'next'}));
            for(const [key,value]of Object.entries(saved.meta||{})){const input=q('#builder-meta').elements.namedItem(key);if(input&&typeof value==='string')input.value=value;}
        }}
    }catch{steps=[];feedback('Could not restore the draft; start a new task.');}
    visibility();render();
})(globalThis);
