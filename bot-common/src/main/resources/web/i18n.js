'use strict';
// The same catalogs are read by Java and served here. Translate display text only.
window.BotLanguage=(()=>{
    const dictionaries=new Map(),textSources=new WeakMap(),attributeSources=new WeakMap();
    let language='en',catalog=null,matcher=null,generation=0;
    const escape=s=>Array.from(s).map(c=>'\\.^$*+?()[]{}|'.includes(c)?'\\'+c:c).join('');
    function translate(value){return matcher?String(value).replace(matcher,s=>catalog.messages[s]):String(value);}
    function excluded(el){return el?.closest('[data-no-i18n],script,style,textarea,input');}
    function apply(root=document.body){
        if(!catalog || !root || excluded(root.nodeType===1?root:root.parentElement))return;
        const nodes=[];
        if(root.nodeType===3)nodes.push(root);else{const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);while(walker.nextNode())nodes.push(walker.currentNode);}
        for(const node of nodes){
            if(excluded(node.parentElement))continue;
            const previous=textSources.get(node),source=previous && node.nodeValue===previous.output?previous.source:node.nodeValue;
            const output=translate(source);textSources.set(node,{source,output});if(node.nodeValue!==output)node.nodeValue=output;
        }
        const elements=root.nodeType===1?[root,...root.querySelectorAll('[aria-label],[placeholder],[title]')]:[];
        for(const el of elements){if(el.closest('[data-no-i18n],script,style'))continue;
            const saved=attributeSources.get(el)||{};
            for(const name of ['aria-label','placeholder','title'])if(el.hasAttribute(name)){
                const current=el.getAttribute(name),previous=saved[name],source=previous && current===previous.output?previous.source:current;
                const output=translate(source);saved[name]={source,output};if(current!==output)el.setAttribute(name,output);
            }attributeSources.set(el,saved);
        }
    }
    async function use(selected,force=false){
        if(!['vi','en'].includes(selected))return;
        if(language===selected && catalog && !force)return;
        const request=++generation;
        try {
            let loaded=dictionaries.get(selected);
            if(!loaded || force){const response=await fetch('/lang.'+selected,{cache:'no-store'});if(!response.ok)throw Error('Language unavailable');loaded=await response.json();dictionaries.set(selected,loaded);}
            if(request!==generation)return;
            language=selected;catalog=loaded;
            const keys=Object.keys(catalog.messages).filter(s=>s!==catalog.messages[s]).sort((a,b)=>b.length-a.length||a.localeCompare(b));
            matcher=keys.length?new RegExp(keys.map(escape).join('|'),'gu'):null;
            document.documentElement.lang=language;
            const select=document.querySelector('#language-select');if(select)select.value=language;
            apply();
        } catch(error){console.error('Could not load language catalog',error);}
    }
    new MutationObserver(changes=>{const roots=new Set();for(const change of changes){if(change.type==='characterData')roots.add(change.target);else if(change.type==='attributes')roots.add(change.target);else for(const node of change.addedNodes)roots.add(node);}for(const root of roots)apply(root);})
        .observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['aria-label','placeholder','title']});
    return {use,translate,get language(){return language;}};
})();

