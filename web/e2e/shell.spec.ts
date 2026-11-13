import {test,expect} from '@playwright/test';

test('all three role defaults select stable UX-99 variants and view modes',async({page})=>{
  await page.goto('/');
  await expect(page.getByText('UX-99 · ZV-012')).toBeVisible();
  await expect(page.getByText('TELEMETRY UNKNOWN')).toBeVisible();
  await page.getByRole('button',{name:'Оператор'}).click();
  await expect(page.getByText('UX-99 · ZV-062')).toBeVisible();
  await expect(page.getByText('Операторский вид — только обзор. Нет подключённых PLC или фактических сигналов.')).toBeVisible();
  await page.getByRole('button',{name:'Рецензент'}).click();
  await expect(page.getByText('UX-99 · ZV-086')).toBeVisible();
  await page.getByRole('button',{name:'Инженер'}).click();
  await expect(page.getByText('UX-99 · ZV-012')).toBeVisible();
  await page.getByRole('button',{name:'Фокус'}).click();
  await expect(page.getByText('ENGINEERING INSPECTOR')).toHaveCount(0);
  await page.getByRole('button',{name:'Панели'}).click();
  await expect(page.getByText('ENGINEERING INSPECTOR')).toBeVisible();
  await page.screenshot({path:test.info().outputPath('workspace-shell.png'),fullPage:true});
});

test('semantic lens does not invent live telemetry',async({page})=>{
  await page.goto('/');
  await page.getByRole('combobox').selectOption('TEMPERATURE');
  await expect(page.getByText('UX-99 · ZV-014')).toBeVisible();
  await expect(page.getByText('UNKNOWN_NO_TELEMETRY').first()).toBeVisible();
  await expect(page.getByText('Нет подтверждённых измерений')).toBeVisible();
  await expect(page.getByRole('button',{name:'Создать локальный стенд'}).first()).toBeVisible();
});

test('guided three-line flow calls the site-scoped API and draft CAS, without PLC writes',async({page})=>{
  const empty={id:'00000000-0000-0000-0000-000000000001',revision:1,maxActiveLines:10,
    units:[],sharedAssets:[],transfers:[],dependencies:[]};
  const unit=(id:string)=>({id,kind:'LINE',profileKey:'batch.concrete.v1',
    equipment:[{id:'mixer',typeKey:'batch-mixer',templateVersion:1,ports:[],properties:[]}],
    connections:[],boundaries:[],resources:[],measurements:[],recirculationAllowed:false});
  const draft={...empty,revision:2,units:[unit('CP01-AGG'),unit('CP01-CEM'),unit('CP01-MIX')],
    transfers:[{sourceUnit:'CP01-AGG',sourceBoundary:'out',targetUnit:'CP01-MIX',targetBoundary:'in'},
      {sourceUnit:'CP01-CEM',sourceBoundary:'out',targetUnit:'CP01-MIX',targetBoundary:'in'}]};
  let saved:any=empty;const calls:string[]=[];
  await page.route('**/api/v1/**',async route=>{
    const url=new URL(route.request().url()),path=url.pathname,method=route.request().method();
    calls.push(method+' '+path);
    let data:unknown={code:'NOT_FOUND',message:'Unexpected mock route'};let status=200;
    if(path.endsWith('/dev/sites')&&method==='POST')data={siteId:'local-e2e',maxActiveLines:10};
    else if(path.endsWith('/blueprints')&&method==='POST')data=empty;
    else if(path.endsWith('/equipment-types')&&method==='GET')data=[{key:'batch-mixer',version:1,label:'Mixer',ports:[],properties:[]}];
    else if(path.endsWith('/guided/concrete'))data=draft;
    else if(path.endsWith('/validate')&&method==='POST')data=[];
    else if(path.endsWith('/draft')&&method==='PUT'){
      const b=route.request().postDataJSON();
      expect(b.expectedRevision).toBe(1);
      saved=b.snapshot;data=saved;
    } else if(path.endsWith('/x-ray'))data={unitId:url.searchParams.get('unitId'),
      directUpstream:[],directDownstream:[],upstream:[],downstream:[],sharedResourcePeers:[],sharedAssets:[]};
    else if(path.endsWith('/blueprints/'+empty.id)&&method==='GET')data=saved;
    else status=404;
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('/');
  await page.getByRole('button',{name:'Создать локальный стенд'}).first().click();
  await expect(page.getByText('LOCAL · local-e2e')).toBeVisible();
  await page.getByRole('button',{name:'Бетонный завод'}).click();
  await expect(page.getByText('3 линий')).toBeVisible();
  await page.getByRole('button',{name:'Проверить'}).click();
  await page.getByRole('button',{name:'Сохранить Draft'}).click();
  await expect(page.getByText('✓ Сохранено')).toBeVisible();
  await page.getByRole('button',{name:'Перезагрузить'}).click();
  await expect(page.getByText('R2')).toBeVisible();
  expect(calls.some(x=>x.includes('/guided/concrete'))).toBe(true);
  expect(calls.some(x=>x.includes('PUT ')&&x.endsWith('/draft'))).toBe(true);
  expect(calls.every(x=>!(/\/plc\/|\/write\/|\/command\//.test(x)))).toBe(true);
});
