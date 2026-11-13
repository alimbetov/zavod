import type {Blueprint,CatalogEntry,Diagnostic,XRayView,TelemetrySignalView,DemoStatus,DemoSimulation} from './model';
const ROOT='/api/v1';
export class ApiFailure extends Error {
  constructor(public status:number, message:string) { super(message); }
}
async function request<T>(path:string, init?:RequestInit):Promise<T> {
  const response=await fetch(ROOT+path,{...init,credentials:'same-origin',
    headers:{'Content-Type':'application/json',...(init?.headers??{})}});
  if(!response.ok) {
    const payload=await response.json().catch(()=>({message:response.statusText}));
    throw new ApiFailure(response.status, payload.message??payload.code??response.statusText);
  }
  return response.json() as Promise<T>;
}
function scope(site:string,blueprint:string):string {
  return '/sites/'+encodeURIComponent(site)+'/blueprints/'+encodeURIComponent(blueprint);
}
export const studioApi={
  newSite:():Promise<{siteId:string;maxActiveLines:number}> =>
    request('/dev/sites',{method:'POST',headers:{'X-Zavod-Dev-Write':'true'},body:JSON.stringify({maxActiveLines:10})}),
  newBlueprint:(site:string):Promise<Blueprint> =>
    request('/sites/'+encodeURIComponent(site)+'/blueprints',{method:'POST',headers:{'X-Zavod-Dev-Write':'true'}}),
  load:(site:string,id:string):Promise<Blueprint>=>request(scope(site,id)),
  concrete:(site:string,id:string):Promise<Blueprint>=>request(scope(site,id)+'/guided/concrete'),
  catalog:(site:string):Promise<CatalogEntry[]>=>request('/sites/'+encodeURIComponent(site)+'/equipment-types'),
  save:(site:string,id:string,bp:Blueprint,expectedRevision:number):Promise<Blueprint> =>
    request(scope(site,id)+'/draft',{method:'PUT',headers:{'X-Zavod-Dev-Write':'true'},
      body:JSON.stringify({expectedRevision,snapshot:bp})}),
  validate:(site:string,id:string,bp:Blueprint):Promise<Diagnostic[]> =>
    request(scope(site,id)+'/validate',{method:'POST',headers:{'X-Zavod-Dev-Write':'true'},body:JSON.stringify(bp)}),
  loadLayout:(site:string,id:string,viewId:string):Promise<{
    viewId:string;layoutVersion:number;blueprintRevision:number;
    layout:{unitId:string;positions:Record<string,{x:number;y:number}>;
      viewport:{x:number;y:number;zoom:number}}
  }>=>request(scope(site,id)+'/layouts/'+encodeURIComponent(viewId)),
  saveLayout:(site:string,id:string,viewId:string,body:{
    expectedLayoutVersion:number;blueprintRevision:number;
    layout:{unitId:string;positions:Record<string,{x:number;y:number}>;viewport:{x:number;y:number;zoom:number}}
  }):Promise<{viewId:string;layoutVersion:number;blueprintRevision:number}> =>
    request(scope(site,id)+'/layouts/'+encodeURIComponent(viewId),{
      method:'PUT',headers:{'X-Zavod-Dev-Write':'true'},body:JSON.stringify(body)}),
  telemetry:(site:string,id:string,revision:number,unitId:string,minutes=60):Promise<TelemetrySignalView[]> =>
    request(scope(site,id)+'/revisions/'+revision+'/telemetry?unitId='+
      encodeURIComponent(unitId)+'&minutes='+minutes+'&limit=100'),
  demoStatus:(site:string,id:string,revision:number):Promise<DemoStatus> =>
    request(scope(site,id)+'/revisions/'+revision+'/demo'),
  demoStep:(site:string,id:string,revision:number,step:'approve'|'publish'|'activate'):Promise<DemoStatus> =>
    request(scope(site,id)+'/revisions/'+revision+'/demo/'+step,{
      method:'POST',headers:{'X-Zavod-Dev-Write':'true'}}),
  demoSimulation:(site:string,id:string,revision:number,scenario:'NORMAL'|'ALARM'):Promise<DemoSimulation> =>
    request(scope(site,id)+'/revisions/'+revision+'/demo/simulate',{
      method:'POST',headers:{'X-Zavod-Dev-Write':'true'},
      body:JSON.stringify({scenario})}),
  xray:(site:string,id:string,unitId:string):Promise<XRayView> =>
    request(scope(site,id)+'/x-ray?unitId='+encodeURIComponent(unitId)),
};
