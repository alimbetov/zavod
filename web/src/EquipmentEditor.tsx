import {useCallback,useEffect,useMemo,useRef,useState,type DragEvent} from 'react';
import {
  ReactFlow, Background,Controls,MiniMap,Handle,Position,ReactFlowProvider,
  applyNodeChanges,type Node, type NodeProps,type Edge,type Connection,
  type NodeChange,type ReactFlowInstance
} from '@xyflow/react';
import {EquipmentSymbol} from './Symbols';
import {studioApi,ApiFailure} from './api';
import type {Blueprint,CatalogEntry,Equipment,Port,ProcessUnit} from './model';

type PositionXY={x:number;y:number};
type LayoutResponse={
  viewId:string;layoutVersion:number;blueprintRevision:number;
  layout:{unitId:string;positions:Record<string,PositionXY>;viewport:{x:number;y:number;zoom:number}};
};
type EquipmentData=Record<string,unknown>&{label:string;typeKey:string;ports:Port[]};
type EquipmentNode=Node<EquipmentData,'equipment'>;
const initialPosition=(index:number):PositionXY=>({x:70+(index%4)*230,y:75+Math.floor(index/4)*190});
const layoutView=(unit:string)=>'editor-'+unit;
const viewDefault={x:0,y:0,zoom:1};

function EquipmentTile({data,selected}:NodeProps<EquipmentNode>) {
  return <div className={'equipment-tile'+(selected?' selected':'')} aria-label={'Оборудование '+data.label}>
    <div className="equipment-title"><EquipmentSymbol kind={data.typeKey} size={37}/>
      <div><strong>{data.label}</strong><small>{data.typeKey}</small></div></div>
    <div className="typed-port-list">{data.ports.map(p=><div className="typed-port" key={p.key}>
      {p.direction==='IN'&&<Handle id={'in:'+p.key} type="target" position={Position.Left}
        style={{top:'auto',left:-7,position:'relative',transform:'none'}}/>}
      <span className={'port-label '+p.direction.toLowerCase()} title={p.kind+' · '+p.medium}>
        {p.direction==='IN'?'←':'→'} {p.key} · {p.medium}</span>
      {p.direction==='OUT'&&<Handle id={'out:'+p.key} type="source" position={Position.Right}
        style={{top:'auto',right:-7,position:'relative',transform:'none'}}/>}
    </div>)}</div>
    <div className="equipment-foot">◌ UNKNOWN · инженерная схема</div>
  </div>;
}
const nodeTypes={equipment:EquipmentTile};
type Snapshot={equipment:Equipment[];connections:ProcessUnit['connections'];positions:Record<string,PositionXY>};
const equal=(a:unknown,b:unknown)=>JSON.stringify(a)===JSON.stringify(b);
const makeNodes=(items:Equipment[],positions:Record<string,PositionXY>):EquipmentNode[]=>
  items.map((e,i)=>({id:e.id,type:'equipment',position:positions[e.id]??initialPosition(i),
    data:{label:e.id,typeKey:e.typeKey,ports:e.ports}}));
function getEdges(connections:ProcessUnit['connections']):Edge[] {
  return connections.map((c,i)=>({id:'conn:'+i+':'+c.sourceEquipment+':'+c.sourcePort+':'+c.targetEquipment+':'+c.targetPort,
    source:c.sourceEquipment,target:c.targetEquipment,sourceHandle:'out:'+c.sourcePort,
    targetHandle:'in:'+c.targetPort,type:'smoothstep',style:{stroke:'#27abc0',strokeWidth:2}}));
}
function compatible(source:Port|undefined,target:Port|undefined):boolean {
  return Boolean(source&&target&&source.direction==='OUT'&&target.direction==='IN'&&
    source.kind===target.kind&&source.medium===target.medium);
}
function errorText(error:unknown):string {
  if(error instanceof ApiFailure)return (error.status===409?'Конфликт версий. Изменения сохранены в редакторе. ':'')+error.message;
  return error instanceof Error?error.message:'Неизвестная ошибка';
}

function EquipmentEditorInner({site,blueprint,unitId,catalog,onCommitted,onBack}:{
  site:string;blueprint:Blueprint;unitId:string;catalog:CatalogEntry[];
  onCommitted:(bp:Blueprint)=>void;onBack:()=>void;
}) {
  const unit=blueprint.units.find(u=>u.id===unitId);
  const [snapshot,setSnapshot]=useState<Snapshot>({equipment:unit?.equipment??[],
    connections:unit?.connections??[],positions:{}});
  const [past,setPast]=useState<Snapshot[]>([]);
  const [future,setFuture]=useState<Snapshot[]>([]);
  const [layoutVersion,setLayoutVersion]=useState(0);
  const [loadedLayout,setLoadedLayout]=useState(false);
  const [lastSaved,setLastSaved]=useState<Snapshot|null>(null);
  const [working,setWorking]=useState(false);
  const [message,setMessage]=useState('');
  const [selected,setSelected]=useState('');
  const instance=useRef<ReactFlowInstance<EquipmentNode,Edge>|null>(null);
  const [viewport,setViewport]=useState(viewDefault);
  const viewportRef=useRef(viewDefault);
  const dropRef=useRef<HTMLDivElement>(null);
  const changed=!lastSaved||!equal(snapshot,lastSaved);
  const editorRevision=blueprint.revision;
  const rootRef=useRef({site,blueprintId:blueprint.id,unitId});
  const scope=site+'|'+blueprint.id+'|'+unitId;
  const loadedRef=useRef('');
  // Stop remote layout lookups from overwriting pending local edits.
  useEffect(()=>{
    if(loadedRef.current===scope)return;
    loadedRef.current=scope;setLoadedLayout(false);
    let active=true;
    const base:Snapshot={equipment:unit?.equipment??[],connections:unit?.connections??[],positions:{}};
    (async()=>{
      try {
        const row=await studioApi.loadLayout(site,blueprint.id,layoutView(unitId)) as LayoutResponse;
        if(!active)return;
        const good=Object.fromEntries(Object.entries(row.layout.positions)
          .filter(([id])=>base.equipment.some(e=>e.id===id)));
        const value={...base,positions:good};
        setSnapshot(value);setLastSaved(value);setLayoutVersion(row.layoutVersion);
        setViewport(row.layout.viewport);viewportRef.current=row.layout.viewport;
      }catch(error){
        if(!active)return;
        if(!(error instanceof ApiFailure&&error.status===404))setMessage(errorText(error));
        setSnapshot(base);setLastSaved(base);setLayoutVersion(0);setViewport(viewDefault);
      }finally{if(active)setLoadedLayout(true);}
    })();
    return ()=>{active=false;};
  },[scope,site,blueprint.id,unitId]);
  const mutate=useCallback((change:(current:Snapshot)=>Snapshot)=>{
    setSnapshot(current=>{
      const next=change(current);
      if(equal(next,current))return current;
      setPast(history=>[...history.slice(-49),current]);setFuture([]);
      return next;
    });
  },[]);
  const undo=()=>{if(!past.length)return;const prev=past[past.length-1];
    setPast(xs=>xs.slice(0,-1));setFuture(xs=>[snapshot,...xs.slice(0,49)]);setSnapshot(prev);};
  const redo=()=>{if(!future.length)return;const [next,...rest]=future;
    setFuture(rest);setPast(xs=>[...xs.slice(-49),snapshot]);setSnapshot(next);};
  const insert=(key:string,pos?:PositionXY)=>{
    const type=catalog.find(e=>e.key===key);
    if(!type||!unit)return;
    const suffix=crypto.randomUUID().slice(0,8);
    const id=key.replace(/[^a-zA-Z0-9_-]/g,'-')+'-'+suffix;
    const equipment:Equipment={id,typeKey:type.key,templateVersion:type.version,
      ports:type.ports.map(p=>({key:p.key,direction:p.direction,kind:p.streamKind,medium:p.medium,
        min:p.minConnections,max:p.maxConnections})),properties:[]};
    mutate(x=>({...x,equipment:[...x.equipment,equipment],
      positions:{...x.positions,[id]:pos??initialPosition(x.equipment.length)}}));
    setSelected(id);setMessage('Добавлено в Draft. Подключите обязательные порты.');
  };
  const connect=useCallback((c:Connection)=>{
    if(!c.source||!c.target||!c.sourceHandle||!c.targetHandle)return;
    const src=snapshot.equipment.find(e=>e.id===c.source);
    const dst=snapshot.equipment.find(e=>e.id===c.target);
    const sp=src?.ports.find(p=>'out:'+p.key===c.sourceHandle);
    const tp=dst?.ports.find(p=>'in:'+p.key===c.targetHandle);
    if(c.source===c.target||!compatible(sp,tp)){setMessage('Нельзя соединять несовместимые порты.');return;}
    const candidate={sourceEquipment:c.source,sourcePort:sp!.key,targetEquipment:c.target,targetPort:tp!.key};
    if(snapshot.connections.some(e=>equal(e,candidate))){setMessage('Соединение уже существует.');return;}
    const degree=(equipmentId:string,key:string,direction:'source'|'target')=>
      snapshot.connections.filter(x=>direction==='source'?x.sourceEquipment===equipmentId&&x.sourcePort===key:
        x.targetEquipment===equipmentId&&x.targetPort===key).length;
    if(degree(src!.id,sp!.key,'source')>=sp!.max||degree(dst!.id,tp!.key,'target')>=tp!.max){
      setMessage('Превышена кратность подключения порта.');return;
    }
    mutate(x=>({...x,connections:[...x.connections,candidate]}));setMessage('');
  },[snapshot,mutate]);
  const removeSelected=()=>{
    if(!selected)return;
    if(unit?.boundaries.some(b=>b.equipmentId===selected)){
      setMessage('Оборудование привязано к межлинейной границе. Сначала пересмотрите Boundary/Transfer.');
      return;
    }
    mutate(x=>({...x,equipment:x.equipment.filter(e=>e.id!==selected),
      connections:x.connections.filter(c=>c.sourceEquipment!==selected&&c.targetEquipment!==selected),
      positions:Object.fromEntries(Object.entries(x.positions).filter(([id])=>id!==selected))}));
    setSelected('');
  };
  const save=async()=>{
    if(!unit||working)return;
    setWorking(true);setMessage('');
    try {
      const newUnit:ProcessUnit={...unit,equipment:snapshot.equipment,connections:snapshot.connections};
      const graphChanged=!equal(unit.equipment,newUnit.equipment)||!equal(unit.connections,newUnit.connections);
      let nextBlueprint=blueprint;
      if(graphChanged) {
        const next:{units:ProcessUnit[];revision:number}={
          revision:blueprint.revision+1,
          units:blueprint.units.map(u=>u.id===unitId?newUnit:u)
        };
        nextBlueprint=await studioApi.save(site,blueprint.id,{...blueprint,...next},blueprint.revision);
      }
      // Layout CAS is deliberately separate from graph revision and transaction.
      // A partial success must be reported, never silently treated as atomic.
      try {
        const body={expectedLayoutVersion:layoutVersion,blueprintRevision:nextBlueprint.revision,
          layout:{unitId,positions:snapshot.positions,viewport:viewportRef.current}};
        const saved=await studioApi.saveLayout(site,blueprint.id,layoutView(unitId),body);
        setLayoutVersion(saved.layoutVersion);
        setLastSaved(snapshot);setPast([]);setFuture([]);
        setMessage('Сохранено: Blueprint R'+nextBlueprint.revision+
          ' / Layout v'+saved.layoutVersion+'. Расположение не меняет инженерный граф.');
      }catch(error){
        setMessage('Blueprint R'+nextBlueprint.revision+
          ' сохранён, но Layout не сохранён: '+errorText(error)+'; повторите сохранение координат.');
        // On graph-only success, update parent revision to support retry safely.
        if(graphChanged)onCommitted(nextBlueprint);
        return;
      }
      if(graphChanged)onCommitted(nextBlueprint);
    }catch(error){setMessage(errorText(error));}
    finally{setWorking(false);}
  };
  const nodes=useMemo(()=>makeNodes(snapshot.equipment,snapshot.positions),[snapshot]);
  const edges=useMemo(()=>getEdges(snapshot.connections),[snapshot.connections]);
  const nodeChange=(changes:NodeChange<EquipmentNode>[])=>{
    // Visual dragging is staged until pointer release; avoids dozens of undo entries.
    const next=applyNodeChanges(changes,nodes);
    const update=Object.fromEntries(next.filter(n=>snapshot.positions[n.id]===undefined||
      !equal(n.position,snapshot.positions[n.id])).map(n=>[n.id,n.position]));
    if(Object.keys(update).length)mutate(x=>({...x,positions:{...x.positions,...update}}));
  };
  const dragEquipment=(event:DragEvent<HTMLButtonElement>,key:string)=>{
    event.dataTransfer.setData('application/x-zavod-equipment',key);
    event.dataTransfer.effectAllowed='copy';
  };
  const drop=(event:DragEvent)=>{
    event.preventDefault();
    if(!loadedLayout)return;
    const key=event.dataTransfer.getData('application/x-zavod-equipment');
    if(!catalog.some(x=>x.key===key))return;
    const where=instance.current?.screenToFlowPosition({x:event.clientX,y:event.clientY});
    insert(key,where);
  };
  if(!unit)return <div className="editor-error">Линия не найдена. <button onClick={onBack}>Вернуться</button></div>;
  return <div className="equipment-editor">
    <div className="editor-top">
      <div><button className="soft" onClick={onBack}>← Весь завод</button>
        <strong>Редактор линии {unitId}</strong>
        <span className="status-provenance">DRAFT · ENGINEERING ONLY</span></div>
      <div className="editor-actions">
        <button onClick={undo} disabled={!past.length||working}>Отменить</button>
        <button onClick={redo} disabled={!future.length||working}>Повторить</button>
        <button disabled={!selected||working} onClick={removeSelected}>Удалить агрегат</button>
        <button disabled={!loadedLayout||working||!changed} className="primary" onClick={()=>void save()}>
          Сохранить схему и расположение</button>
      </div>
    </div>
    {message&&<div className="editor-message" role="status">{message}</div>}
    <div className="editor-body">
      <div className="editor-catalog">
        <strong>Оборудование</strong>
        <p className="muted tiny">Перетащите агрегат на схему или добавьте кнопкой.</p>
        {catalog.map(type=><button key={type.key} draggable={!working}
          onDragStart={e=>dragEquipment(e,type.key)}
          onClick={()=>insert(type.key)} className="editor-catalog-tile">
            <EquipmentSymbol kind={type.key} size={24}/><span>{type.label}</span><span>＋</span></button>)}
      </div>
      <div className="editor-canvas" ref={dropRef}>
        {!loadedLayout?<span>Загрузка расположения...</span>:<ReactFlow
          nodes={nodes} edges={edges} nodeTypes={nodeTypes}
          onInit={api=>{instance.current=api;api.setViewport(viewport);}}
          onNodesChange={nodeChange}
          onNodeDragStop={(_,node)=>mutate(x=>({...x,positions:{...x.positions,[node.id]:node.position}}))}
          onNodeClick={(_,node)=>setSelected(node.id)}
          onPaneClick={()=>setSelected('')}
          onConnect={connect}
          onDrop={drop} onDragOver={e=>{e.preventDefault();e.dataTransfer.dropEffect='copy';}}
          onMoveEnd={(_,v)=>{viewportRef.current=v;}}
          nodesConnectable nodesDraggable={!working} elementsSelectable
          fitView fitViewOptions={{padding:.25}} minZoom={.2} maxZoom={2}>
          <Background color="var(--grid)" gap={20} size={1}/><Controls/><MiniMap/>
        </ReactFlow>}
      </div>
      <div className="editor-detail"><strong>Параметры</strong>
        <span className="muted tiny">{selected||'Выберите оборудование'}</span>
        {snapshot.equipment.find(e=>e.id===selected)?.ports.map(p=>
          <div className="property-row" key={p.key}><span>{p.key}</span>
            <span>{p.direction} · {p.medium} · {p.kind}</span></div>)}
        <div className="section-title">Состояние</div>
        <p className="muted tiny">UNKNOWN_NO_TELEMETRY. Схема не является командой для PLC.</p>
        <div className="section-title">Редактирование</div>
        <p className="muted tiny">Соединения создаются перетаскиванием между совместимыми типизированными портами.
          Изменения сохраняются с проверкой ревизии. Границы других линий защищены от неявного удаления.</p>
      </div>
    </div>
    <div className="editor-status">{snapshot.equipment.length} агрегатов · {snapshot.connections.length} соединений ·
      Blueprint R{editorRevision} · Layout v{layoutVersion} · {changed?'Есть изменения':'Сохранено'}</div>
  </div>;
}
export function EquipmentEditor(props:Parameters<typeof EquipmentEditorInner>[0]) {
  return <ReactFlowProvider><EquipmentEditorInner key={props.blueprint.id+'|'+props.unitId} {...props}/></ReactFlowProvider>;
}
