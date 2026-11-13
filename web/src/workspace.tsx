import {useCallback,useEffect,useMemo,useState} from 'react';
import {ReactFlow,Background,Controls,Handle,MiniMap,Position,ReactFlowProvider,
  type NodeProps,type Node, type Edge} from '@xyflow/react';
import {studioApi,ApiFailure} from './api';
import {EquipmentSymbol} from './Symbols';
import {EquipmentEditor} from './EquipmentEditor';
import {TelemetryMonitor} from './TelemetryMonitor';
import {createGraph,CURRENT_TELEMETRY,LENSES,LENS_LABELS,LAYOUT_LABELS,ROLE_LABELS,
  variantId,type Blueprint,type CatalogEntry,type Diagnostic,type LayoutMode,type Lens,
  type Role,type ProcessUnit,type Equipment,type XRayView} from './model';

type UnitData={label:string;profile:string;equipmentCount:number;kinds:string[]};
function UnitNode({data,selected}:NodeProps<Node<UnitData>>) {
  return <div className={'unit-node'+(selected?' selected':'')} aria-label={'Линия '+data.label}>
    <Handle type="target" position={Position.Left}/>
    <div className="unit-head"><span className="unit-chip">LINE</span><strong>{data.label}</strong></div>
    <div className="symbol-row">
      {data.kinds.slice(0,4).map((kind,i)=><EquipmentSymbol key={kind+i} kind={kind} size={32}/>)}
    </div>
    <span className="unit-sub">{data.equipmentCount} агрегатов · {data.profile}</span>
    <div className="unit-status"><span className="dot unknown"/>UNKNOWN · нет телеметрии</div>
    <Handle type="source" position={Position.Right}/>
  </div>;
}
const nodeTypes={plantUnit:UnitNode};
const modes:LayoutMode[]=['FOCUS','SPLIT','OVERVIEW'];
const roles:Role[]=['ENGINEER','OPERATOR','REVIEWER'];
const defaults:Record<Role,{layout:LayoutMode;lens:Lens}>={
  ENGINEER:{layout:'SPLIT',lens:'TOPOLOGY'},
  OPERATOR:{layout:'OVERVIEW',lens:'QUALITY'},
  REVIEWER:{layout:'SPLIT',lens:'CHANGE'}
};
type Notice={type:'info'|'error'|'success';message:string};
function localXRay(bp:Blueprint,unitId:string):XRayView {
  const upstream=bp.transfers.filter(t=>t.targetUnit===unitId).map(t=>t.sourceUnit)
    .concat(bp.dependencies.filter(d=>d.targetUnit===unitId).map(d=>d.sourceUnit));
  const downstream=bp.transfers.filter(t=>t.sourceUnit===unitId).map(t=>t.targetUnit)
    .concat(bp.dependencies.filter(d=>d.sourceUnit===unitId).map(d=>d.targetUnit));
  const unit=bp.units.find(u=>u.id===unitId);
  const assets=unit?.resources.map(x=>x.sharedAssetId)??[];
  const peers=bp.units.filter(u=>u.id!==unitId&&u.resources.some(r=>assets.includes(r.sharedAssetId))).map(u=>u.id);
  return {unitId,directUpstream:upstream,directDownstream:downstream,
    upstream,downstream,sharedResourcePeers:peers,sharedAssets:assets};
}
function WorkspaceInner(){
  const [role,setRole]=useState<Role>('ENGINEER');
  const [layout,setLayout]=useState<LayoutMode>('SPLIT');
  const [lens,setLens]=useState<Lens>('TOPOLOGY');
  const [site,setSite]=useState('');
  const [blueprint,setBlueprint]=useState<Blueprint|null>(null);
  const [savedRevision,setSavedRevision]=useState(0);
  const [catalog,setCatalog]=useState<CatalogEntry[]>([]);
  const [selectedUnit,setSelectedUnit]=useState('');
  const [selectedEquipment,setSelectedEquipment]=useState('');
  const [editingUnit,setEditingUnit]=useState('');
  const [xray,setXray]=useState<XRayView|null>(null);
  const [diagnostics,setDiagnostics]=useState<Diagnostic[]>([]);
  const [pending,setPending]=useState(false);
  const [notice,setNotice]=useState<Notice|null>(null);
  const [dirty,setDirty]=useState(false);
  const [theme,setTheme]=useState<'dark'|'light'>('dark');
  const [nodes,setNodes]=useState<Node<UnitData>[]>([]);
  const [edges,setEdges]=useState<Edge[]>([]);
  const readOnly=role!=='ENGINEER';
  const selected=blueprint?.units.find(u=>u.id===selectedUnit);
  const equipment=selected?.equipment.find(e=>e.id===selectedEquipment);
  const setupGraph=useCallback((bp:Blueprint)=>{
    const graph=createGraph(bp);
    setNodes(graph.lines);
    setEdges(graph.edges);
    setSelectedUnit(bp.units[0]?.id??'');
    setSelectedEquipment('');
    setXray(null);
  },[]);
  const applySnapshot=useCallback((bp:Blueprint,persisted:boolean)=>{
    setBlueprint(bp);setupGraph(bp);
    if(persisted)setSavedRevision(bp.revision);
    setDirty(!persisted);
    setDiagnostics([]);
  },[setupGraph]);
  const execute=async (job:()=>Promise<void>)=>{
    setPending(true);setNotice(null);
    try {await job();} catch(e) {
      const message=e instanceof ApiFailure?(e.status===409?'Конфликт ревизий: обновите проект и сравните изменения. ':'' )+e.message:
        e instanceof Error?e.message:'Неизвестная ошибка';
      setNotice({type:'error',message});
    } finally {setPending(false);}
  };
  useEffect(()=>{
    const saved=window.localStorage.getItem('zavod.studio.local-project');
    if(!saved)return;
    let selection:{site:string;blueprintId:string};
    try {selection=JSON.parse(saved);} catch {window.localStorage.removeItem('zavod.studio.local-project');return;}
    if(!selection.site||!selection.blueprintId)return;
    let active=true;
    Promise.all([studioApi.load(selection.site,selection.blueprintId),studioApi.catalog(selection.site)])
      .then(([bp,items])=>{if(!active)return;setSite(selection.site);setCatalog(items);applySnapshot(bp,true);})
      .catch(()=>{if(active)setNotice({type:'error',message:'Не удалось восстановить локальный проект. Данные не удалены.'});});
    return ()=>{active=false;};
  },[applySnapshot]);
  const bootstrap=()=>void execute(async()=>{
    const newSite=await studioApi.newSite();
    const bp=await studioApi.newBlueprint(newSite.siteId);
    const entries=await studioApi.catalog(newSite.siteId);
    setSite(newSite.siteId);setCatalog(entries);applySnapshot(bp,true);
    window.localStorage.setItem('zavod.studio.local-project',JSON.stringify({site:newSite.siteId,blueprintId:bp.id}));
    setNotice({type:'success',message:'Локальная площадка создана. Конфигурация не активирует оборудование.'});
  });
  const loadKnown=()=>void execute(async()=>{
    if(!site||!blueprint)return;
    const bp=await studioApi.load(site,blueprint.id);
    applySnapshot(bp,true);setNotice({type:'info',message:'Получена последняя ревизия PostgreSQL.'});
  });
  const guided=()=>void execute(async()=>{
    if(!site||!blueprint)return;
    const draft=await studioApi.concrete(site,blueprint.id);
    applySnapshot(draft,false);
    setNotice({type:'info',message:'Три линии подготовлены локально. Проверьте и сохраните Draft.'});
  });
  const validate=()=>void execute(async()=>{
    if(!site||!blueprint)return;
    const result=await studioApi.validate(site,blueprint.id,blueprint);
    setDiagnostics(result);
    setNotice({type:result.length?'info':'success',
      message:result.length?result.length+' замечаний. Проверка структуры не является физической сертификацией.':
      'Структурная проверка пройдена. Физическая квалификация не проведена.'});
  });
  const save=()=>void execute(async()=>{
    if(!site||!blueprint)return;
    const stored=await studioApi.save(site,blueprint.id,blueprint,savedRevision);
    applySnapshot(stored,true);
    setNotice({type:'success',message:'Черновик сохранён с optimistic revision CAS.'});
  });
  useEffect(()=>{
    if(!blueprint||!selectedUnit)return;
    setXray(localXRay(blueprint,selectedUnit));
    if(dirty||!site)return;
    studioApi.xray(site,blueprint.id,selectedUnit)
      .then(view=>setXray(view))
      .catch(()=>setXray(localXRay(blueprint,selectedUnit)));
  },[blueprint,selectedUnit,site,dirty]);
  const graph=useMemo(()=>({nodes:nodes.map(n=>({
      ...n,className:xray&&(lens==='DEPENDENCY'||lens==='RESOURCE')?
        xray.unitId===n.id?'highlight-node':
        xray.upstream.includes(n.id)?'upstream-node':
        xray.downstream.includes(n.id)?'downstream-node':
        xray.sharedResourcePeers.includes(n.id)?'resource-node':'muted-node':''
    })),edges}),[nodes,edges,xray,lens]);
  const openUnit=(id:string)=>{setSelectedUnit(id);setSelectedEquipment('');};
  const enterEditor=()=>{
    if(!blueprint?.units.some(u=>u.id===selectedUnit)||dirty||readOnly)return;
    setEditingUnit(selectedUnit);
  };
  const changeRole=(r:Role)=>{
    setRole(r);setLayout(defaults[r].layout);setLens(defaults[r].lens);
  };
  const inspectEquipment=(e:Equipment)=>{setSelectedEquipment(e.id);};
  return <div className={'app '+theme+' layout-'+layout.toLowerCase()}>
    <header className="topbar">
      <div className="brand"><span className="brand-icon">◈</span><strong>ZAVOD</strong><span className="brand-sub">STUDIO</span></div>
      <div className="top-project"><span className="dot blue"/> {site?'LOCAL · '+site:'Новая инженерная площадка'}
        <span className="revision">{blueprint?'R'+blueprint.revision:'NO BLUEPRINT'}</span>
        <span className="tag">DEV LOCAL</span></div>
      <div className="top-actions"><span className={'save-status'+(dirty?' unsaved':'')}>{dirty?'● Не сохранено':'✓ Сохранено'}</span>
        <button className="soft" onClick={()=>setTheme(v=>v==='dark'?'light':'dark')} title="Тема">◐</button></div>
    </header>
    <div className="toolbar" role="toolbar" aria-label="Настройки рабочего пространства">
      <div className="role-tabs" aria-label="Ролевое представление">
        {roles.map(r=><button key={r} className={role===r?'active':''} onClick={()=>changeRole(r)}>{ROLE_LABELS[r]}</button>)}
      </div>
      <div className="mode-tabs" aria-label="Композиция">
        {modes.map(m=><button key={m} className={layout===m?'active':''} onClick={()=>setLayout(m)}>{LAYOUT_LABELS[m]}</button>)}
      </div>
      <label className="lens-select">Линза <select value={lens} onChange={e=>setLens(e.target.value as Lens)}>
        {LENSES.map(l=><option key={l} value={l}>{LENS_LABELS[l]}</option>)}</select></label>
      <span className="variant">UX-99 · {variantId(role,layout,lens)}</span>
    </div>
    <div className="commandbar">
      <div className="context-label"><strong>Plant Overview</strong><small>{blueprint?.units.length??0} линий · {blueprint?.transfers.length??0} межлинейных потоков</small></div>
      <div className="commands">
        {!site&&<button className="primary" disabled={pending} onClick={bootstrap}>Создать локальный стенд</button>}
        {site&&<><button disabled={pending||readOnly||dirty||Boolean(blueprint?.units.length)} onClick={guided}>+ Бетонный завод</button>
          <button disabled={pending||!blueprint} onClick={validate}>Проверить</button>
          <button disabled={pending||!dirty||readOnly} className="primary" onClick={save}>Сохранить Draft</button>
          <button disabled={pending} className="soft" onClick={loadKnown}>Перезагрузить</button></>}
      </div>
    </div>
    {notice&&<div className={'notice '+notice.type} role={notice.type==='error'?'alert':'status'}>{notice.message}
      <button onClick={()=>setNotice(null)} aria-label="Закрыть сообщение">×</button></div>}
    {editingUnit&&blueprint&&site?<EquipmentEditor site={site} blueprint={blueprint}
      unitId={editingUnit} catalog={catalog}
      onCommitted={saved=>{applySnapshot(saved,true);}}
      onBack={()=>{setEditingUnit('');}}/>:
    <main className="workspace">
      {layout!=='FOCUS'&&<aside className="palette pane">
        <div className="pane-label">ОБЪЕКТЫ И ЛИНИИ</div>
        <div className="section-title">Линии <span>{blueprint?.units.length??0}</span></div>
        <div className="unit-list">{blueprint?.units.map(u=><button key={u.id}
          className={'unit-option'+(selectedUnit===u.id?' active':'')} onClick={()=>openUnit(u.id)}>
          <span className="tiny-icon">◇</span><span>{u.id}<small>{u.equipment.length} агрегатов</small></span></button>)}</div>
        {role==='ENGINEER'&&<><div className="section-title">Библиотека оборудования</div>
          <div className="equipment-list">{catalog.slice(0,11).map(item=><div className="equipment-item" key={item.key}>
            <EquipmentSymbol kind={item.key} size={26}/><span>{item.label}</span></div>)}</div>
          <p className="muted tiny">Выберите линию и нажмите «Редактировать линию» для настройки типизированных портов, перемещения и Undo/Redo.</p></>}
        {role==='OPERATOR'&&<p className="muted">Операторский вид — только обзор. Нет подключённых PLC или фактических сигналов.</p>}
      </aside>}
      <section className="canvas-pane">
        <div className="canvas-header"><div className="canvas-heading">
          <span className="eyebrow">{LENS_LABELS[lens].toUpperCase()}</span>
          <strong>Схема производства</strong>
        </div><div className="canvas-badges">
          {role==='ENGINEER'&&<button className="soft" disabled={!blueprint?.units.length||dirty}
            onClick={enterEditor}>Редактировать линию ↗</button>}<span className="status-unknown">◌ TELEMETRY UNKNOWN</span><span className="status-provenance">ENGINEERING ONLY</span></div></div>
        {layout==='OVERVIEW'&&<div className="overview-strip">
          <div><strong>{blueprint?.units.length??0}</strong><small>Линии</small></div>
          <div><strong>{blueprint?.sharedAssets.length??0}</strong><small>Общие ресурсы</small></div>
          <div><strong>{diagnostics.length}</strong><small>Замечания</small></div>
          <div><strong>0</strong><small>Подключения OT</small></div>
        </div>}
        <div className="flow-wrap">
          {blueprint&&blueprint.units.length>0?<ReactFlow
            nodes={graph.nodes} edges={graph.edges} nodeTypes={nodeTypes}
            nodesDraggable={false} nodesConnectable={false} elementsSelectable
            onNodeClick={(_,node)=>openUnit(node.id)} onPaneClick={()=>setSelectedEquipment('')}
            fitView fitViewOptions={{padding:.22}} minZoom={.2} maxZoom={1.6}
            proOptions={{hideAttribution:false}}>
            <Background color="var(--grid)" gap={20} size={1}/>
            <MiniMap pannable zoomable nodeColor="#52738f"/>
            <Controls/>
          </ReactFlow>:<div className="empty-canvas"><div className="empty-symbol">◇</div>
            <strong>Инженерная площадка готова к проектированию</strong>
            <p>Создайте локальный стенд и выберите шаблон бетонного завода из трёх зависимых линий.</p>
            {!site&&<button className="primary" onClick={bootstrap} disabled={pending}>Начать проект</button>}</div>}
        </div>
      </section>
      {layout!=='FOCUS'&&<aside className="inspector pane">
        <div className="pane-label">ENGINEERING INSPECTOR</div>
        <div className="inspector-top"><span className="icon-well">{equipment?
          <EquipmentSymbol kind={equipment.typeKey} size={36}/>:'◇'}</span>
          <div><strong>{equipment?.id??selected?.id??'Выберите линию'}</strong>
            <small>{equipment?.typeKey??selected?.profileKey??'Здесь появятся свойства'}</small></div></div>
        <div className="inspector-status"><span className="dot unknown"/> UNKNOWN_NO_TELEMETRY
          <small>Фактическое состояние не установлено</small></div>
        {selected&&<><div className="section-title">Оборудование</div>
          <div className="inspector-equipment">{selected.equipment.map(e=><button key={e.id}
            className={'equipment-select'+(selectedEquipment===e.id?' active':'')} onClick={()=>inspectEquipment(e)}>
            <EquipmentSymbol kind={e.typeKey} size={22}/><span>{e.id}<small>{e.typeKey}</small></span></button>)}</div></>}
        {equipment&&<><div className="section-title">Порты · {equipment.ports.length}</div>
          {equipment.ports.map(p=><div key={p.key} className="property-row"><span>{p.key}</span>
            <span>{p.direction} · {p.medium}</span></div>)}</>}
        {xray&&<><div className="section-title">Engineering X-Ray</div>
          <div className="xr-item"><span>Выше по потоку</span><strong>{xray.upstream.join(', ')||'—'}</strong></div>
          <div className="xr-item"><span>Ниже по потоку</span><strong>{xray.downstream.join(', ')||'—'}</strong></div>
          <div className="xr-item"><span>Общие ресурсы</span><strong>{xray.sharedAssets.join(', ')||'—'}</strong></div>
          <div className="xr-item"><span>Совместное использование</span><strong>{xray.sharedResourcePeers.join(', ')||'—'}</strong></div></>}
        <div className="section-title">Монитор параметров</div>
        {site&&blueprint&&selectedUnit&&!dirty?
          <TelemetryMonitor site={site} blueprintId={blueprint.id}
            revision={savedRevision} unitId={selectedUnit}/>:
          <div className="empty-trend"><div className="trend-grid"/>
            <strong>Нет подтверждённых измерений</strong>
            <small>Монитор доступен для сохранённой версии Blueprint.
              LIVE PLC-сигналы не подключены.</small></div>}
      </aside>}
    </main>}
    <footer className="bottom-dock">
      <div className="dock-title"><span className="guardian-icon">✧</span><strong>Engineering Guardian</strong>
        <span className="muted">{diagnostics.length?'Найдено '+diagnostics.length:'Запустите серверную проверку'}</span></div>
      <div className="dock-findings">{diagnostics.slice(0,3).map((d,i)=><button key={d.code+d.entity+i}
        onClick={()=>{const id=d.entity.split('/')[0];if(blueprint?.units.some(x=>x.id===id))openUnit(id);}}>
        <span className={'severity '+d.severity.toLowerCase()}>{d.severity}</span>{d.code} · {d.entity}</button>)}
        {!diagnostics.length&&<span className="muted">Guardian не выдаёт физическое заключение по одной только топологии</span>}</div>
    </footer>
    <div className="statusbar"><span>LOCAL-ONLY · NO PLC WRITES</span><span>{readOnly?'Роль меняет представление, не права Backend':'Черновик редактируется только через защищённый DEV-LOCAL API'}</span>
      <span>{CURRENT_TELEMETRY}</span></div>
  </div>;
}
export function Workspace(){return <ReactFlowProvider><WorkspaceInner/></ReactFlowProvider>;}
