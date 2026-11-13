import {test,expect} from '@playwright/test';

// This suite is executed only by the dedicated PostgreSQL+Spring Boot CI job.
test.skip(!process.env.REAL_BACKEND_E2E,'Requires real local Spring Boot and PostgreSQL');

test('three concrete lines: move SVG, save independent layout, reopen; add equipment with undo and redo',async({page})=>{
  await page.goto('/');
  await page.getByRole('button',{name:'Создать локальный стенд'}).first().click();
  await expect(page.getByText(/LOCAL · local-/)).toBeVisible();
  await page.getByRole('button',{name:'Бетонный завод'}).click();
  await expect(page.getByText('3 линий')).toBeVisible();
  await page.getByRole('button',{name:'Проверить'}).click();
  await page.getByRole('button',{name:'Сохранить Draft'}).click();
  await expect(page.getByText('✓ Сохранено')).toBeVisible();
  const top=await page.locator('.top-project').innerText();
  const site=top.match(/local-[0-9a-f-]{36}/)?.[0];
  expect(site).toBeTruthy();
  const ref=await page.evaluate(()=>window.localStorage.getItem('zavod.studio.local-project'));
  const bpId=(JSON.parse(ref!) as {blueprintId:string}).blueprintId;
  const path='/api/v1/sites/'+site+'/blueprints/'+bpId;

  await page.getByRole('button',{name:/CP01-MIX/}).first().click();
  await page.getByRole('button',{name:/Редактировать линию/}).click();
  await expect(page.getByText('Редактор линии CP01-MIX')).toBeVisible();
  const node=page.locator('.react-flow__node[data-id="mixer"]');
  await expect(node).toBeVisible();
  const box=await node.boundingBox();expect(box).not.toBeNull();
  const start={x:box!.x+box!.width/2,y:box!.y+22};
  await page.mouse.move(start.x,start.y);
  await page.mouse.down();
  await page.mouse.move(start.x+100,start.y+65,{steps:12});
  await page.mouse.up();
  await page.getByRole('button',{name:'Сохранить схему и расположение'}).click();
  await expect(page.getByText(/Layout v1/).first()).toBeVisible();
  const original=await page.request.get(path+'/layouts/editor-CP01-MIX');
  expect(original.status()).toBe(200);
  const layout=await original.json();
  expect(layout.layoutVersion).toBe(1);
  expect(layout.blueprintRevision).toBe(2);
  const oldPosition=layout.layout.positions.mixer;
  expect(oldPosition).toBeDefined();
  const rev=await (await page.request.get(path)).json();
  expect(rev.revision).toBe(2);
  expect(rev.units.length).toBe(3);
  expect(rev.transfers.length).toBe(2);

  // Add another equipment through a catalog, prove reversible edit history.
  await page.getByRole('button',{name:/Pressure measurement/}).click();
  await expect(page.locator('.editor-status')).toContainText('5 агрегатов');
  await page.getByRole('button',{name:'Отменить'}).click();
  await expect(page.locator('.editor-status')).toContainText('4 агрегатов');
  await page.getByRole('button',{name:'Повторить'}).click();
  await expect(page.locator('.editor-status')).toContainText('5 агрегатов');
  await page.getByRole('button',{name:'Сохранить схему и расположение'}).click();
  await expect(page.locator('.editor-message').getByText(/Blueprint R3.*Layout v2/)).toBeVisible();
  await page.reload();
  await expect(page.getByText(/LOCAL · local-/)).toBeVisible();
  await expect(page.getByText('R3')).toBeVisible();
  await page.getByRole('button',{name:/CP01-MIX/}).first().click();
  await page.getByRole('button',{name:/Редактировать линию/}).click();
  await expect(page.getByText('Редактор линии CP01-MIX')).toBeVisible();
  await expect(page.locator('.editor-status')).toContainText('5 агрегатов');
  const stored=await (await page.request.get(path+'/layouts/editor-CP01-MIX')).json();
  expect(stored.layoutVersion).toBe(2);
  expect(stored.layout.positions.mixer).toEqual(oldPosition);
  const persisted=await (await page.request.get(path)).json();
  expect(persisted.revision).toBe(3);
  expect(persisted.units.length).toBe(3);
  expect(persisted.transfers.length).toBe(2);
  expect(persisted.units.find((u:{id:string})=>u.id==='CP01-MIX').equipment.length).toBe(5);
  await page.screenshot({path:test.info().outputPath('rs1-real-postgres-reopen.png'),fullPage:true});
});

test('TF-03: real PostgreSQL simulated telemetry is revision-bound, visible and fail-closed',async({page})=>{
  await page.goto('/');
  await page.getByRole('button',{name:'Создать локальный стенд'}).first().click();
  await page.getByRole('button',{name:'Бетонный завод'}).click();
  await page.getByRole('button',{name:'Сохранить Draft'}).click();
  await expect(page.getByText('✓ Сохранено')).toBeVisible();
  const ref=await page.evaluate(()=>window.localStorage.getItem('zavod.studio.local-project'));
  const {siteId,blueprintId}=JSON.parse(ref!) as {siteId:string;blueprintId:string};
  const saved=await (await page.request.get('/api/v1/sites/'+siteId+'/blueprints/'+blueprintId)).json();
  expect(saved.revision).toBe(2);
  const unit=saved.units.find((u:{id:string})=>u.id==='CP01-MIX');
  expect(unit).toBeDefined();
  const equipment=unit.equipment[0];
  expect(equipment).toBeDefined();
  const base='/api/v1/sites/'+siteId+'/blueprints/'+blueprintId+
    '/revisions/'+saved.revision+'/telemetry';
  const signalId=crypto.randomUUID();
  const setup=await page.request.post(base+'/signals',{
    headers:{'X-Zavod-Dev-Write':'true'},
    data:{id:signalId,siteId,blueprintId,blueprintRevision:2,
      unitId:'CP01-MIX',equipmentId:equipment.id,signalKey:'simulated-pressure',
      unitCode:'bar',sourceKind:'SIMULATED',staleAfter:'PT10S'}
  });
  expect(setup.status()).toBe(201);
  await page.getByRole('button',{name:/CP01-MIX/}).first().click();
  const monitor=page.getByRole('region',{name:'Мониторинг телеметрии'});
  await expect(monitor.getByText('NO_DATA').first()).toBeVisible();
  const sample=await page.request.post(base+'/signals/'+signalId+'/samples',{
    headers:{'X-Zavod-Dev-Write':'true'},
    data:{eventId:'tf03-e2e-1',value:2.5,unitCode:'bar',
      sourceTime:new Date(Date.now()-2000).toISOString(),quality:'GOOD'}
  });
  expect(sample.status()).toBe(200);
  await monitor.getByRole('button',{name:'Обновить'}).click();
  await expect(monitor.getByText('CURRENT')).toBeVisible();
  await expect(monitor.getByText('2.5 bar')).toBeVisible();
  const stale=await page.request.post(base+'/signals/'+signalId+'/samples',{
    headers:{'X-Zavod-Dev-Write':'true'},
    data:{eventId:'tf03-e2e-2',value:1.1,unitCode:'bar',
      sourceTime:new Date(Date.now()-45000).toISOString(),quality:'GOOD'}
  });
  expect(stale.status()).toBe(200);
  // Older sample must not replace the newer source timestamp.
  await monitor.getByRole('button',{name:'Обновить'}).click();
  await expect(monitor.getByText('2.5 bar')).toBeVisible();
  await page.screenshot({path:test.info().outputPath('tf03-real-postgres-monitor.png'),fullPage:true});
});
