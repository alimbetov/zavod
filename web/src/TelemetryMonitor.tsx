import {useEffect,useState} from 'react';
import {studioApi} from './api';
import type {TelemetrySignalView,TelemetrySample} from './model';

/**
 * Read-only monitoring for SIMULATED local-development data.
 * Quality is evaluated by the backend. Never infer healthy PLC status from a graph.
 */
function Trace({samples}: {samples:TelemetrySample[]}) {
  if(samples.length===0)return <p className="muted tiny">Исторических измерений нет.</p>;
  const points=samples.map(s=>({sample:s,t:Date.parse(s.sourceTime),v:Number(s.value)}))
    .filter(p=>Number.isFinite(p.t)&&Number.isFinite(p.v));
  if(points.length===0)return <p className="muted tiny">Нет корректных числовых измерений.</p>;
  const minT=Math.min(...points.map(p=>p.t));
  const maxT=Math.max(...points.map(p=>p.t));
  const minV=Math.min(...points.map(p=>p.v));
  const maxV=Math.max(...points.map(p=>p.v));
  const x=(t:number)=>14+272*(t-minT)/Math.max(1,maxT-minT);
  const y=(v:number)=>72-58*(v-minV)/Math.max(1,maxV-minV);
  return <div className="telemetry-trace">
    <svg viewBox="0 0 300 90" role="img" aria-label="График симулированных исторических измерений">
      <rect x="0" y="0" width="300" height="90" rx="6" fill="none" stroke="currentColor" strokeOpacity=".17"/>
      {points.slice(1).map((p,i)=>{
        const previous=points[i];
        if(previous.sample.quality!=='GOOD'||p.sample.quality!=='GOOD')return null;
        return <line key={p.sample.sourceEventId} x1={x(previous.t)} y1={y(previous.v)}
          x2={x(p.t)} y2={y(p.v)} stroke="var(--accent)" strokeWidth="2"/>;
      })}
      {points.map(p=><circle key={p.sample.sourceEventId} cx={x(p.t)} cy={y(p.v)}
        r="3.5" fill={p.sample.quality==='GOOD'?'var(--accent)':'#c97979'}/>)}
    </svg>
    <div className="telemetry-time"><span>{new Date(minT).toLocaleTimeString()}</span>
      <span>{new Date(maxT).toLocaleTimeString()}</span></div>
    <small className="muted">История: метки источника · GOOD соединены линией, некачественные точки выделены отдельно</small>
  </div>;
}

export function TelemetryMonitor({site,blueprintId,revision,unitId}: {
  site:string;blueprintId:string;revision:number;unitId:string
}) {
  const [signals,setSignals]=useState<TelemetrySignalView[]>([]);
  const [error,setError]=useState<string|null>(null);
  const [loading,setLoading]=useState(false);
  const [reload,setReload]=useState(0);
  useEffect(()=>{
    if(!site||!blueprintId||!unitId||revision<1){
      setSignals([]);setError(null);return;
    }
    let active=true;
    setLoading(true);setError(null);setSignals([]);
    studioApi.telemetry(site,blueprintId,revision,unitId).then(data=>{
      if(active)setSignals(data);
    }).catch(e=>{
      if(active){setSignals([]);setError(e instanceof Error?e.message:'Ошибка чтения телеметрии');}
    }).finally(()=>{if(active)setLoading(false);});
    return ()=>{active=false;};
  },[site,blueprintId,revision,unitId,reload]);
  return <section className="telemetry-monitor" aria-label="Мониторинг телеметрии">
    <div className="telemetry-heading"><strong>Мониторинг · SIMULATED</strong>
      <button className="soft" disabled={loading} onClick={()=>setReload(n=>n+1)}>Обновить</button>
    </div>
    <p className="muted tiny">Только локальная симуляция. Нет подключения к PLC и подтверждённых LIVE-сигналов.</p>
    {loading&&<p role="status">Загрузка измерений…</p>}
    {error&&<p role="alert" className="telemetry-error">Данные недоступны: {error}</p>}
    {!error&&!loading&&signals.length===0&&<p role="status" className="muted">NO_DATA · Для этой линии сигналы не зарегистрированы.</p>}
    {!error&&!loading&&signals.map(({definition,latest,history})=>
      <article className="telemetry-card" key={definition.id}>
        <div className="telemetry-heading"><strong>{definition.equipmentId} · {definition.signalKey}</strong>
          <span className={'telemetry-quality status-'+latest.status.toLowerCase()}>{latest.status}</span>
        </div>
        <small className="muted">SIMULATED · {definition.unitCode} · Blueprint R{definition.blueprintRevision}</small>
        <div className="telemetry-reading">{latest.status==='CURRENT'&&latest.value!==null?
          latest.value+' '+latest.unitCode:'Значение не подтверждено'}</div>
        {latest.observedAt&&<small className="muted">Время источника: {new Date(latest.observedAt).toLocaleString()}</small>}
        <Trace samples={history}/>
      </article>)}
  </section>;
}
