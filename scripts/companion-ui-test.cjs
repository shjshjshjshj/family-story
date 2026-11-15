// GPT acceptance tests for companion permissions, navigation and stale sessions.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
class Element{
  constructor(tag='div'){this.tagName=tag;this.children=[];this.attributes={};this.listeners={};this.hidden=false;this.disabled=false;this.value='';this.className='';this.textContent='';}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=nodes;}
  setAttribute(k,v){this.attributes[k]=v;}
  removeAttribute(k){delete this.attributes[k];}
  addEventListener(k,fn){this.listeners[k]=fn;}
  focus(){focused=this;}
  set innerHTML(value){throw Error('User content must not be rendered through innerHTML');}
}
const nodes=Object.fromEntries([...fs.readFileSync(path.join(assets,'phone.html'),'utf8').matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
let focused=null;
const data=JSON.parse(fs.readFileSync(path.join(assets,'places.json'),'utf8'));
const fixture={...data,revision:2,routeRevision:2,approvedRevision:1,selected:'cheomseongdae',stops:data.initialStops,visited:[],history:['일정 변경'],proposals:[],memberId:'me',members:[{id:'me',name:'<img onerror=alert(1)>',role:'viewer'}],vehicle:{safety:'정차'}};
const context=vm.createContext({document:{getElementById:id=>{assert(nodes[id],id);return nodes[id];},createElement:tag=>new Element(tag)},sessionStorage:{getItem:()=>'',setItem(){},removeItem(){}},location:{hash:'',pathname:'/phone'},history:{replaceState(){}},window:{scrollTo(){},confirm:()=>true},setTimeout:()=>0,clearTimeout(){},AbortController,fetch:async()=>({ok:true,json:async()=>structuredClone(fixture)})});
vm.runInContext(fs.readFileSync(path.join(assets,'phone.js'),'utf8'),context);
const run=code=>vm.runInContext(code,context),text=node=>node.textContent+node.children.map(text).join(' ');
context.fixture=fixture;run("session='qa-session';state=fixture; connected=true; render();");
assert.equal(nodes.previous.disabled,true);assert.equal(nodes.next.disabled,false);
assert.match(text(nodes.myDevice),/<img onerror=alert\(1\)>/);assert.equal(nodes.members.children[0].children.length,2);
assert.match(text(nodes.permissions),/불가/);assert.equal(nodes.stops.children[0].attributes['aria-current'],'true');
assert.match(nodes.approval.textContent,/자동 반영되지는/);
run("state.members[0].role='proposer'; renderFamily();");assert.match(text(nodes.permissions),/제안할 수 있어요/);assert.doesNotMatch(text(nodes.permissions),/바로 더할 수/);
run("state.members[0].role='editor'; renderFamily();");assert.match(text(nodes.permissions),/바로 더할 수 있어요/);assert.doesNotMatch(text(nodes.permissions),/불가/);
run("scene=2;kids=true;renderStory();tab('family');");assert.equal(nodes.next.disabled,true);assert.equal(nodes.familyPanel.hidden,false);assert.equal(nodes.storyPanel.hidden,true);assert.equal(focused,nodes.familyHeading);
run("tab('story');");assert.equal(nodes.pageNumber.textContent,'03 / 03');assert.equal(nodes.familyTab.attributes['aria-current'],undefined);assert.equal(nodes.storyTab.attributes['aria-current'],'page');assert.equal(focused,nodes.placeName);
run("kids=false;renderStory();");assert.equal(nodes.pager.hidden,true);assert.equal(nodes.storyText.children.length,4);assert.match(nodes.sourceLink.href,/https:/);
(async()=>{
  const before=focused;await run('refresh()');assert.equal(focused,before,'poll must not steal focus');
  context.fetch=async()=>{throw Error('offline');};await run('refresh()');
  assert.equal(nodes.familyStale.hidden,false);assert.match(nodes.connection.textContent,/마지막/);
  run('renderPlan()');assert(nodes.stops.children.flatMap(li=>li.children).length>0);
  const actionButtons=nodes.stops.children.flatMap(li=>li.children.filter(n=>n.className==='stopmain').flatMap(n=>n.children.filter(n=>n.className==='actions').flatMap(n=>n.children)));
  assert(actionButtons.length>0);assert(actionButtons.every(b=>b.disabled),'offline edit controls disabled');
  context.fetch=async()=>({ok:false,status:403,json:async()=>({error:'revoked'})});await run('refresh()');
  assert.equal(nodes.connected.hidden,true);assert.equal(nodes.join.hidden,false);assert.equal(nodes.navigation.hidden,true);assert.match(nodes.message.textContent,/다시 초대/);
  console.log('PASS: permissions, literal names, scene boundaries, 3-tab focus/state, no poll focus theft, stale/offline editing and revoked session');
})().catch(e=>{console.error(e);process.exitCode=1;});
