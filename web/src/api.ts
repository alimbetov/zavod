import type {Blueprint,CatalogEntry,Diagnostic,XRayView} from './model';
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
  xray:(site:string,id:string,unitId:string):Promise<XRayView> =>
    request(scope(site,id)+'/x-ray?unitId='+encodeURIComponent(unitId)),
};
