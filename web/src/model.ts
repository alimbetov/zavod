export type Role = 'ENGINEER' | 'OPERATOR' | 'REVIEWER';
export type LayoutMode = 'FOCUS' | 'SPLIT' | 'OVERVIEW';
export type Lens = 'TOPOLOGY' | 'DEPENDENCY' | 'TEMPERATURE' | 'FLOW' | 'ENERGY' |
  'MAINTENANCE' | 'QUALITY' | 'BATCH' | 'CHANGE' | 'RESOURCE' | 'SIGNAL';

export const LENSES: Lens[] = ['TOPOLOGY','DEPENDENCY','TEMPERATURE','FLOW','ENERGY',
  'MAINTENANCE','QUALITY','BATCH','CHANGE','RESOURCE','SIGNAL'];
export const LENS_LABELS: Record<Lens,string> = {
  TOPOLOGY:'Топология',DEPENDENCY:'Зависимости',TEMPERATURE:'Температура',
  FLOW:'Потоки',ENERGY:'Энергия',MAINTENANCE:'Обслуживание',QUALITY:'Качество',
  BATCH:'Партии',CHANGE:'Изменения',RESOURCE:'Ресурсы',SIGNAL:'Сигналы'
};
export const ROLE_LABELS: Record<Role,string> = {
  ENGINEER:'Инженер',OPERATOR:'Оператор',REVIEWER:'Рецензент'
};
export const LAYOUT_LABELS: Record<LayoutMode,string> = {
  FOCUS:'Фокус',SPLIT:'Панели',OVERVIEW:'Обзор'
};

// The client is a projection of the Java ProcessBlueprint contract, not a
// competing engineering model. All physical validation remains server-owned.
export interface Port { key:string; direction:'IN'|'OUT'; kind:string; medium:string; min:number; max:number }
export interface Equipment {
  id:string;typeKey:string;templateVersion:number;ports:Port[];properties:unknown[];
}
export interface Boundary {
  key:string;direction:'IN'|'OUT';kind:string;medium:string;equipmentId:string|null;physicalPortKey:string|null;
}
export interface ProcessUnit {
  id:string;kind:'LINE'|'CIRCUIT'|'ZONE'|'STATION';profileKey:string;equipment:Equipment[];
  connections:{sourceEquipment:string;sourcePort:string;targetEquipment:string;targetPort:string}[];
  boundaries:Boundary[];resources:{sharedAssetId:string;demand:number}[];
  measurements:unknown[];recirculationAllowed:boolean;
}
export interface Blueprint {
  id:string;revision:number;maxActiveLines:number;units:ProcessUnit[];
  sharedAssets:{id:string;capacity:number;mode:string}[];
  transfers:{sourceUnit:string;sourceBoundary:string;targetUnit:string;targetBoundary:string}[];
  dependencies:{sourceUnit:string;targetUnit:string}[];
}
export interface XRayView {
  unitId:string;directUpstream:string[];directDownstream:string[];
  upstream:string[];downstream:string[];sharedResourcePeers:string[];sharedAssets:string[];
}
export interface Diagnostic {code:string;severity:string;entity:string;detail:string}
export interface EngineeringPropertyDefinition {
  key?:string; dimension?:string; canonicalUnit?:string
}
export interface CatalogPort {
  key:string;direction:'IN'|'OUT';medium:string;streamKind:string;
  minConnections:number;maxConnections:number;
}
export interface CatalogEntry {
  key:string;version:number;label:string;ports:CatalogPort[];properties:EngineeringPropertyDefinition[];
}
export type TelemetryQuality = 'UNKNOWN_NO_TELEMETRY'|'SIMULATED'|'STALE'|'OFFLINE'|'GOOD'|'BAD';
// The only shipped state is UNKNOWN_NO_TELEMETRY. No demo status may be
// presented as live equipment state without a qualified Edge source.
export const CURRENT_TELEMETRY: TelemetryQuality = 'UNKNOWN_NO_TELEMETRY';
export function variantId(role:Role, layout:LayoutMode, lens:Lens):string {
  const roles:Role[]=['ENGINEER','OPERATOR','REVIEWER'];
  const modes:LayoutMode[]=['FOCUS','SPLIT','OVERVIEW'];
  const index=(roles.indexOf(role)*modes.length+modes.indexOf(layout))*LENSES.length+LENSES.indexOf(lens);
  return 'ZV-'+String(index+1).padStart(3,'0');
}
export function createGraph(bp:Blueprint) {
  const lines=bp.units.map((unit,index)=>({
    id:unit.id,type:'plantUnit' as const,
    position:{x:80+(index%3)*350,y:60+Math.floor(index/3)*240},
    data:{label:unit.id,profile:unit.profileKey,
      equipmentCount:unit.equipment.length,
      kinds:unit.equipment.map(e=>e.typeKey)}
  }));
  const edges=bp.transfers.map((t,i)=>({
    id:'transfer-'+i,source:t.sourceUnit,target:t.targetUnit,
    label:t.sourceBoundary+' → '+t.targetBoundary,type:'smoothstep' as const,
    animated:false,style:{stroke:'#33a7c4',strokeWidth:2},
    labelStyle:{fill:'#7790a8',fontSize:10}
  })).concat(bp.dependencies.map((d,i)=>({
    id:'dependency-'+i,source:d.sourceUnit,target:d.targetUnit,label:'Dependency',
    type:'smoothstep' as const,animated:false,
    style:{stroke:'#97a5b6',strokeWidth:2,strokeDasharray:'5 5'},
    labelStyle:{fill:'#7790a8',fontSize:10}
  })));
  return {lines,edges};
}

export type SignalStatus = 'CURRENT'|'UNTRUSTED'|'STALE'|'DISCONNECTED'|'NO_DATA';
export type SampleQuality = 'GOOD'|'UNCERTAIN'|'BAD'|'STALE'|'DISCONNECTED';
export interface TelemetrySignalDefinition {
  id:string;siteId:string;blueprintId:string;blueprintRevision:number;
  unitId:string;equipmentId:string;signalKey:string;unitCode:string;
  sourceKind:'SIMULATED'|'EDGE_READ_ONLY';staleAfter:string;
}
export interface TelemetrySample {
  signalId:string;sourceKind:'SIMULATED'|'EDGE_READ_ONLY';sourceEventId:string;
  value:number;unitCode:string;sourceTime:string;receivedAt:string;quality:SampleQuality;
}
export interface TelemetryReading {
  status:SignalStatus;value:number|null;unitCode:string;
  observedAt:string|null;evaluatedAt:string;sourceKind:'SIMULATED'|'EDGE_READ_ONLY';
}
export interface TelemetrySignalView {
  definition:TelemetrySignalDefinition;latest:TelemetryReading;history:TelemetrySample[];
}
