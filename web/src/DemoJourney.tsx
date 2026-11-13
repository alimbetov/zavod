import {useEffect,useState} from 'react';
import {studioApi} from './api';
import type {DemoStatus} from './model';

type Stage='approve'|'publish'|'activate';
const steps:{id:Stage;label:string;available:(status:DemoStatus)=>boolean}[]=[
  {id:'approve',label:'1. Согласовать (DEMO)',available:s=>s.approvalState==='DRAFT'},
  {id:'publish',label:'2. Опубликовать',available:s=>s.approvalState==='APPROVED'},
  {id:'activate',label:'3. Выбрать активную версию',available:s=>s.approvalState==='PUBLISHED'&&!s.active}
];

/**
 * Non-production walkthrough: actual server-side lifecycle and simulated
 * telemetry, visibly attributed to synthetic local actors.
 */
export function DemoJourney({site,blueprintId,revision,onSample}: {
  site:string;blueprintId:string;revision:number;
  onSample:(unitId:string)=>void;
}) {
  const [status,setStatus]=useState<DemoStatus|null>(null);
  const [pending,setPending]=useState(false);
  const [failure,setFailure]=useState('');
  const [event,setEvent]=useState('');
  useEffect(()=>{
    let mounted=true;
    setStatus(null);setEvent('');setFailure('');
    studioApi.demoStatus(site,blueprintId,revision)
      .then(result=>{if(mounted)setStatus(result);})
      .catch(e=>{if(mounted)setFailure(e instanceof Error?e.message:'Недоступен DEV стенд');});
    return ()=>{mounted=false;};
  },[site,blueprintId,revision]);

  const act=async(step:Stage)=>{
    setPending(true);setFailure('');setEvent('');
    try{
      const result=await studioApi.demoStep(site,blueprintId,revision,step);
      setStatus(result);
      setEvent(step==='approve'?'Согласовано синтетическим рецензентом':
        step==='publish'?'Версия опубликована':
        'Инженерная конфигурация выбрана. PLC-команд не отправлено.');
    }catch(e){setFailure(e instanceof Error?e.message:'Шаг не выполнен');}
    finally{setPending(false);}
  };
  const simulate=async(scenario:'NORMAL'|'ALARM')=>{
    setPending(true);setFailure('');setEvent('');
    try{
      const result=await studioApi.demoSimulation(site,blueprintId,revision,scenario);
      setEvent(scenario==='NORMAL'?'SIMULATED: штатное значение 2.5 bar сохранено':
        'SIMULATED: тестовое превышение 6.2 bar сохранено; это не реальная авария');
      onSample(result.unitId);
      setStatus(await studioApi.demoStatus(site,blueprintId,revision));
    }catch(e){setFailure(e instanceof Error?e.message:'Симуляция не выполнена');}
    finally{setPending(false);}
  };
  const completed=(step:Stage):boolean=>Boolean(status&&(
    step==='approve'?status.approvalState!=='DRAFT':
    step==='publish'?status.approvalState==='PUBLISHED':
    status.active
  ));
  return <section className="demo-journey" role="region" aria-label="Демонстрационный производственный участок">
    <div className="demo-heading">
      <div><span className="eyebrow">DEMO · SIMULATED · DEV LOCAL</span>
        <strong>Путь конфигурации производственного участка</strong></div>
      <span className="demo-identity">Синтетические роли, не настоящие подписи</span>
    </div>
    <p className="muted tiny">Бетонный завод: инертные материалы → дозирование → смеситель. Каждый шаг фиксируется
      настоящим Backend и PostgreSQL, но физическое оборудование не управляется.</p>
    <div className="demo-steps">
      {steps.map(step=><button key={step.id} disabled={pending||!status||!step.available(status)}
        onClick={()=>void act(step.id)} className={completed(step.id)?'demo-complete':''}>
        {completed(step.id)?'✓ ':''}{step.label}
      </button>)}
      <button disabled={pending||!status?.active} onClick={()=>void simulate('NORMAL')}>
        4. Штатный сигнал
      </button>
      <button disabled={pending||!status?.active} onClick={()=>void simulate('ALARM')}>
        5. Учебная тревога
      </button>
    </div>
    {status&&<p className="demo-evidence" data-testid="demo-evidence">
      Состояние: {status.approvalState} · Выбрана: {status.active?'ДА':'НЕТ'} ·
      Журнал переходов: {status.transitionCount} · SIMULATED-сигналы: {status.signalCount}
    </p>}
    {event&&<p role="status" className="demo-message">{event}</p>}
    {failure&&<p role="alert" className="demo-error">{failure}</p>}
    <small className="muted">Учебная тревога определяется только по значению SIMULATED demo-pressure ≥ 5 bar.
      Показатель не является системой промышленной сигнализации или оценкой безопасности.</small>
  </section>;
}
