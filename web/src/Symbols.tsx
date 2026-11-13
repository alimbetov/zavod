import type {SVGProps} from 'react';

type IconKind='cement-silo'|'aggregate-bin'|'aggregate-scale'|'cement-scale'|
  'belt-conveyor'|'water-tank'|'water-doser'|'batch-mixer'|'discharge'|'default';
export function EquipmentSymbol({kind,size=42,...props}:SVGProps<SVGSVGElement>&{kind:string;size?:number}) {
  // Static, audited SVG primitives only; never inject catalog-provided markup.
  const key=(['cement-silo','aggregate-bin','aggregate-scale','cement-scale',
    'belt-conveyor','water-tank','water-doser','batch-mixer','discharge'] as IconKind[])
    .includes(kind as IconKind)?kind as IconKind:'default';
  return <svg {...props} width={size} height={size} viewBox="0 0 52 52"
    fill="none" role="img" aria-label={kind}>
    <g stroke="currentColor" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round">
      {key==='cement-silo'&&<><path d="M12 12H40V32L34 40H18L12 32Z"/><path d="M12 17H40M18 40V47M34 40V47M19 7H33"/></>}
      {key==='aggregate-bin'&&<><path d="M8 14H44L36 38H16Z"/><path d="M18 38V46M34 38V46M8 21H44"/></>}
      {(key==='aggregate-scale'||key==='cement-scale')&&<><path d="M8 18H44V35H8Z"/><path d="M14 35V44M38 35V44M21 23H31"/><circle cx="26" cy="27" r="6"/></>}
      {key==='belt-conveyor'&&<><path d="M7 32L43 15M9 39L45 22"/><circle cx="11" cy="36" r="4"/><circle cx="41" cy="20" r="4"/></>}
      {key==='water-tank'&&<><ellipse cx="26" cy="11" rx="16" ry="5"/><path d="M10 11V39C10 46 42 46 42 39V11M10 31C18 37 34 37 42 31"/></>}
      {key==='water-doser'&&<><path d="M26 6V17M13 17H39V36H13Z"/><path d="M26 36V47M19 25H33"/></>}
      {key==='batch-mixer'&&<><path d="M7 15H45L39 36H13Z"/><path d="M14 41H38M18 36V41M34 36V41M18 22L34 32M34 22L18 32"/></>}
      {key==='discharge'&&<><path d="M10 10H42L35 34H17Z"/><path d="M26 34V47M20 42H32"/></>}
      {key==='default'&&<><rect x="9" y="12" width="34" height="29" rx="5"/><path d="M18 21H34M18 28H34"/></>}
    </g>
  </svg>;
}
